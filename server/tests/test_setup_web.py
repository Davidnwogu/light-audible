import base64

import pytest
from sqlalchemy import select

from light_audible.account import get_account
from light_audible.audible_gateway.fake import FakeLogin
from light_audible.models import Account


def basic(password: str) -> dict[str, str]:
    token = base64.b64encode(f"user:{password}".encode()).decode()
    return {"Authorization": f"Basic {token}"}


GOOD = basic("test-pw")
GOOD_URL = "https://www.amazon.com/ap/maplanding?openid.oa2.authorization_code=abc123"


@pytest.fixture
def after_login_calls(app) -> list:
    calls: list = []

    async def after_login(db, settings):
        calls.append(True)

    app.state.after_login = after_login
    return calls


@pytest.fixture
def fake_login(app) -> FakeLogin:
    app.state.login = FakeLogin()
    return app.state.login


async def _account(app) -> Account:
    async with app.state.session_factory() as s:
        return (await s.execute(select(Account))).scalar_one()


@pytest.fixture(autouse=True)
async def _clean(db):
    # db fixture truncates tables and ensures the schema is migrated
    yield


async def test_setup_requires_password(client):
    assert (await client.get("/setup")).status_code == 401
    assert (await client.get("/setup", headers=basic("wrong"))).status_code == 401
    resp = await client.get("/setup", headers=GOOD)
    assert resp.status_code == 200
    assert "signed_out" in resp.text


async def test_login_start_shows_amazon_url(client, app):
    resp = await client.post("/setup/login/start", headers=GOOD)
    assert resp.status_code == 200
    assert "https://www.amazon.com/ap/signin" in resp.text
    assert "Page not found" in resp.text
    acct = await _account(app)
    assert acct.pending_login["url"].startswith("https://www.amazon.com/ap/signin")


async def test_login_start_requires_password(client):
    assert (await client.post("/setup/login/start")).status_code == 401
    assert (await client.post("/setup/login/finish", data={"redirect_url": "x"})).status_code == 401


async def test_finish_login_saves_auth(client, app, fake_login, after_login_calls):
    await client.post("/setup/login/start", headers=GOOD)
    resp = await client.post("/setup/login/finish", headers=GOOD, data={"redirect_url": GOOD_URL})
    assert resp.status_code == 200
    acct = await _account(app)
    assert acct.status == "ok"
    assert acct.pending_login is None
    assert len(after_login_calls) == 1


async def test_finish_login_bad_url(client, app, fake_login, after_login_calls):
    await client.post("/setup/login/start", headers=GOOD)
    resp = await client.post(
        "/setup/login/finish", headers=GOOD, data={"redirect_url": "https://www.amazon.com/nothing"}
    )
    assert resp.status_code == 200
    assert "No authorization code in that URL" in resp.text
    acct = await _account(app)
    assert acct.status == "signed_out"
    assert acct.pending_login is not None
    assert after_login_calls == []


async def test_finish_without_start(client, app, fake_login, after_login_calls):
    resp = await client.post("/setup/login/finish", headers=GOOD, data={"redirect_url": GOOD_URL})
    assert resp.status_code == 200
    assert "start" in resp.text.lower()
    acct = await _account(app)
    assert acct.status == "signed_out"
    assert after_login_calls == []


async def test_status_page_shows_library_count_and_sync_state(client, app):
    async with app.state.session_factory() as s:
        acct = await get_account(s, app.state.settings)
        acct.position_sync_enabled = False
        await s.commit()
    resp = await client.get("/setup", headers=GOOD)
    assert "Library: 0 books" in resp.text
    assert "Position sync: off" in resp.text


async def test_pending_login_verifier_never_rendered(client, fake_login):
    resp = await client.post("/setup/login/start", headers=GOOD)
    assert "fake-verifier" not in resp.text


async def test_finish_login_survives_after_login_failure(client, app, fake_login):
    async def boom(db, settings):
        raise RuntimeError("sync exploded")

    app.state.after_login = boom
    await client.post("/setup/login/start", headers=GOOD)
    resp = await client.post("/setup/login/finish", headers=GOOD, data={"redirect_url": GOOD_URL})
    assert resp.status_code == 200
    assert (await _account(app)).status == "ok"
    assert "Signed in. Your library couldn&#39;t be loaded yet" in resp.text or "couldn't be loaded yet" in resp.text
    assert "refresh it from the phone" in resp.text
