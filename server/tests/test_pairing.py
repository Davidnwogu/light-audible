import base64
import hashlib
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import select

from light_audible import pairing
from light_audible.models import Device, PairingCode

BASIC = {"Authorization": "Basic " + base64.b64encode(b"user:test-pw").decode()}
T0 = datetime(2026, 1, 1, 12, 0, tzinfo=timezone.utc)


@pytest.fixture
def clock(app):
    state = {"now": T0}
    app.state.clock = lambda: state["now"]
    return state


@pytest.fixture
async def api(client, app, db, clock):
    return client


async def new_code(db) -> str:
    return await pairing.create_code(db, now=T0)


async def pair(client, code: str):
    return await client.post("/api/pair", json={"code": code})


async def test_pair_success_returns_token(api, db):
    code = await new_code(db)
    resp = await pair(api, code)
    assert resp.status_code == 200
    assert isinstance(resp.json()["token"], str) and resp.json()["token"]


async def test_wrong_code_rejected(api, db):
    code = await new_code(db)
    wrong = "000000" if code != "000000" else "111111"
    resp = await pair(api, wrong)
    assert resp.status_code == 400
    assert resp.json() == {"error": "invalid_code"}


async def test_pair_without_any_code_rejected(api, db):
    resp = await pair(api, "123456")
    assert resp.status_code == 400
    assert resp.json() == {"error": "invalid_code"}


async def test_code_invalid_after_5_failures(api, db):
    code = await new_code(db)
    wrong = "000000" if code != "000000" else "111111"
    for _ in range(5):
        assert (await pair(api, wrong)).status_code == 400
    resp = await pair(api, code)
    assert resp.status_code == 400
    assert resp.json() == {"error": "invalid_code"}


async def test_code_expires_after_10_minutes(api, db, clock):
    code = await new_code(db)
    clock["now"] = T0 + timedelta(minutes=10, seconds=1)
    assert (await pair(api, code)).status_code == 400


async def test_code_valid_just_before_expiry(api, db, clock):
    code = await new_code(db)
    clock["now"] = T0 + timedelta(minutes=9, seconds=59)
    assert (await pair(api, code)).status_code == 200


async def test_code_is_single_use(api, db):
    code = await new_code(db)
    assert (await pair(api, code)).status_code == 200
    assert (await pair(api, code)).status_code == 400


async def test_new_code_replaces_old(api, db):
    first = await new_code(db)
    second = await new_code(db)
    while second == first:
        second = await new_code(db)
    assert (await pair(api, first)).status_code == 400
    assert (await pair(api, second)).status_code == 200
    rows = (await db.execute(select(PairingCode))).scalars().all()
    assert len(rows) <= 1


async def test_code_is_six_digits_and_stored_hashed(db):
    code = await new_code(db)
    assert len(code) == 6 and code.isdigit()
    row = (await db.execute(select(PairingCode))).scalar_one()
    assert row.code_hash == hashlib.sha256(code.encode()).hexdigest()
    assert code not in row.code_hash


async def test_protected_route_requires_token(api, db):
    resp = await api.get("/api/library")
    assert resp.status_code == 401
    assert resp.json() == {"error": "unauthorized"}
    resp = await api.get("/api/library", headers={"Authorization": "Bearer nope"})
    assert resp.status_code == 401
    assert resp.json() == {"error": "unauthorized"}
    token = (await pair(api, await new_code(db))).json()["token"]
    resp = await api.get("/api/library", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200
    assert resp.json() == []


async def test_health_stays_unauthenticated(api):
    assert (await api.get("/api/health")).status_code == 200


async def test_repair_revokes_old_token(api, db):
    old = (await pair(api, await new_code(db))).json()["token"]
    new = (await pair(api, await new_code(db))).json()["token"]
    assert (await api.get("/api/library", headers={"Authorization": f"Bearer {old}"})).status_code == 401
    assert (await api.get("/api/library", headers={"Authorization": f"Bearer {new}"})).status_code == 200


async def test_token_stored_hashed(api, db):
    token = (await pair(api, await new_code(db))).json()["token"]
    db.expire_all()
    devices = (await db.execute(select(Device))).scalars().all()
    assert len(devices) == 1
    assert token not in devices[0].token_hash
    assert devices[0].token_hash == hashlib.sha256(token.encode()).hexdigest()


async def test_setup_button_shows_code_once(api, db):
    resp = await api.post("/setup/pairing-code", headers=BASIC)
    assert resp.status_code == 200
    import re

    m = re.search(r"\b(\d{6})\b", resp.text)
    assert m
    code = m.group(1)
    assert (await api.get("/setup", headers=BASIC)).text.count(code) == 0
    assert (await pair(api, code)).status_code == 200


async def test_setup_pairing_code_requires_basic_auth(api):
    assert (await api.post("/setup/pairing-code")).status_code == 401


async def test_concurrent_wrong_guesses_capped_at_5(app, db):
    import asyncio

    code = await new_code(db)
    wrong = "000000" if code != "000000" else "111111"

    async def attempt():
        async with app.state.session_factory() as s:
            try:
                await pairing.redeem(s, wrong, now=T0)
            except pairing.PairingError:
                pass

    await asyncio.gather(*(attempt() for _ in range(20)))
    row = (await db.execute(select(PairingCode))).scalar_one()
    assert row.failed_attempts == 5
    async with app.state.session_factory() as s:
        with pytest.raises(pairing.PairingError):
            await pairing.redeem(s, code, now=T0)


async def test_concurrent_correct_redeems_single_winner(app, db):
    import asyncio

    code = await new_code(db)

    async def attempt():
        async with app.state.session_factory() as s:
            try:
                return await pairing.redeem(s, code, now=T0)
            except pairing.PairingError:
                return None

    results = await asyncio.gather(attempt(), attempt())
    assert len([r for r in results if r]) == 1
    devices = (await db.execute(select(Device).where(Device.revoked_at.is_(None)))).scalars().all()
    assert len(devices) == 1
