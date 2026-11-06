from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import select

from light_audible import account, positions
from light_audible.audible_gateway import RemotePosition
from light_audible.audible_gateway.fake import FakeAudibleSession
from light_audible.models import Account, Book, Position
from tests.test_setup_web import GOOD

T0 = datetime(2026, 1, 1, 12, 0, tzinfo=timezone.utc)
EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
AUTH = {"access_token": "x"}


@pytest.fixture
def fake():
    return FakeAudibleSession(auth=AUTH)


@pytest.fixture
def factory(fake):
    return lambda auth: fake


@pytest.fixture
async def signed_in(db, settings):
    await account.save_auth(db, settings, AUTH)


async def seed_book(db, asin="B1", acr="ACR1"):
    db.add(Book(asin=asin, title=asin, authors="[]", narrators="[]", runtime_ms=9000, acr=acr))
    await db.commit()


async def seed_position(db, asin, ms, at):
    db.add(Position(asin=asin, position_ms=ms, updated_at=at))
    await db.commit()


async def acct(db):
    db.expire_all()
    return await account.get_account(db)


async def test_newer_audible_wins(db, settings, factory, fake, signed_in):
    await seed_position(db, "B1", 1000, T0)
    fake.positions["B1"] = RemotePosition(5000, T0 + timedelta(minutes=1))
    assert await positions.resolve(db, settings, factory, "B1") == (
        5000, T0 + timedelta(minutes=1))


async def test_newer_server_wins(db, settings, factory, fake, signed_in):
    await seed_position(db, "B1", 1000, T0)
    fake.positions["B1"] = RemotePosition(5000, T0 - timedelta(minutes=1))
    assert await positions.resolve(db, settings, factory, "B1") == (1000, T0)


async def test_no_copy_returns_zero_epoch(db, settings, factory, signed_in):
    assert await positions.resolve(db, settings, factory, "B1") == (0, EPOCH)


async def test_audible_read_error_falls_back(db, settings, factory, fake, signed_in):
    await seed_position(db, "B1", 1000, T0)

    async def boom(asin):
        raise RuntimeError("down")

    fake.get_position = boom
    assert await positions.resolve(db, settings, factory, "B1") == (1000, T0)
    assert (await acct(db)).consecutive_sync_failures == 0


async def test_resolve_without_sign_in_uses_server_copy(db, settings, factory):
    await seed_position(db, "B1", 1000, T0)
    assert await positions.resolve(db, settings, factory, "B1") == (1000, T0)


async def test_put_saves_and_syncs(db, settings, factory, fake, signed_in):
    await seed_book(db)
    await positions.save(db, settings, factory, "B1", 4000, T0)
    row = await db.get(Position, "B1")
    assert (row.position_ms, row.updated_at) == (4000, T0)
    assert fake.set_position_calls == [("B1", "ACR1", 4000)]
    await positions.save(db, settings, factory, "B1", 6000, T0 + timedelta(seconds=5))
    db.expire_all()
    assert (await db.get(Position, "B1")).position_ms == 6000


async def test_five_failures_disable_sync(db, settings, factory, fake, signed_in):
    await seed_book(db)
    fake.fail_set_position = True
    for i in range(4):
        await positions.save(db, settings, factory, "B1", 100 + i, T0)
    a = await acct(db)
    assert (a.consecutive_sync_failures, a.position_sync_enabled) == (4, True)
    await positions.save(db, settings, factory, "B1", 200, T0)
    a = await acct(db)
    assert (a.consecutive_sync_failures, a.position_sync_enabled) == (5, False)
    assert (await db.get(Position, "B1")).position_ms == 200


async def test_success_resets_failure_count(db, settings, factory, fake, signed_in):
    await seed_book(db)
    fake.fail_set_position = True
    await positions.save(db, settings, factory, "B1", 1, T0)
    await positions.save(db, settings, factory, "B1", 2, T0)
    assert (await acct(db)).consecutive_sync_failures == 2
    fake.fail_set_position = False
    await positions.save(db, settings, factory, "B1", 3, T0)
    assert (await acct(db)).consecutive_sync_failures == 0


async def test_put_without_acr_skips_audible(db, settings, factory, fake, signed_in):
    await seed_book(db, acr=None)
    a = await account.get_account(db, settings)
    a.consecutive_sync_failures = 2
    await db.commit()
    await positions.save(db, settings, factory, "B1", 4000, T0)
    assert fake.set_position_calls == []
    assert (await acct(db)).consecutive_sync_failures == 2
    assert (await db.get(Position, "B1")).position_ms == 4000


async def test_sync_disabled_skips_audible(db, settings, factory, fake, signed_in):
    await seed_book(db)
    a = await account.get_account(db, settings)
    a.position_sync_enabled = False
    await db.commit()
    await seed_position(db, "B1", 1000, T0)
    fake.positions["B1"] = RemotePosition(9999, T0 + timedelta(hours=1))
    assert await positions.resolve(db, settings, factory, "B1") == (1000, T0)
    await positions.save(db, settings, factory, "B1", 2000, T0 + timedelta(seconds=1))
    assert fake.set_position_calls == []


async def test_save_needs_sign_in_keeps_server_copy_no_failure(db, settings, factory, fake):
    await seed_book(db)
    await positions.save(db, settings, factory, "B1", 4000, T0)
    assert (await db.get(Position, "B1")).position_ms == 4000
    assert (await acct(db)).consecutive_sync_failures == 0


# --- HTTP ---

async def _headers(client, db, app):
    from light_audible import pairing

    app.state.clock = lambda: T0
    code = await pairing.create_code(db, now=T0)
    token = (await client.post("/api/pair", json={"code": code})).json()["token"]
    return {"Authorization": f"Bearer {token}"}


async def test_api_get_put_roundtrip(client, app, db, fake, signed_in):
    app.state.audible_factory = lambda auth: fake
    headers = await _headers(client, db, app)
    await seed_book(db)
    resp = await client.get("/api/books/B1/position", headers=headers)
    assert resp.json() == {"position_ms": 0, "updated_at": EPOCH.isoformat()}
    put = await client.put(
        "/api/books/B1/position",
        headers=headers,
        json={"position_ms": 7000, "client_updated_at": T0.isoformat()},
    )
    assert put.status_code == 204
    resp = await client.get("/api/books/B1/position", headers=headers)
    assert resp.json() == {"position_ms": 7000, "updated_at": T0.isoformat()}
    assert fake.set_position_calls == [("B1", "ACR1", 7000)]


async def test_api_unknown_book_404_and_auth(client, app, db, fake, signed_in):
    app.state.audible_factory = lambda auth: fake
    headers = await _headers(client, db, app)
    assert (await client.get("/api/books/NOPE/position", headers=headers)).status_code == 404
    assert (await client.put(
        "/api/books/NOPE/position", headers=headers,
        json={"position_ms": 1, "client_updated_at": T0.isoformat()})).status_code == 404
    assert (await client.get("/api/books/B1/position")).status_code == 401


async def test_api_put_naive_timestamp_rejected(client, app, db, fake, signed_in):
    app.state.audible_factory = lambda auth: fake
    headers = await _headers(client, db, app)
    await seed_book(db)
    resp = await client.put(
        "/api/books/B1/position", headers=headers,
        json={"position_ms": 1, "client_updated_at": "2026-01-01T12:00:00"})
    assert resp.status_code == 422


# --- setup page ---

async def test_setup_toggle_reenables(client, app, db, settings):
    a = await account.get_account(db, settings)
    a.position_sync_enabled = False
    a.consecutive_sync_failures = 5
    await db.commit()
    page = await client.get("/setup", headers=GOOD)
    assert "Audible position sync turned off after repeated failures" in page.text
    resp = await client.post("/setup/position-sync", headers=GOOD)
    assert resp.status_code == 200
    a = await acct(db)
    assert (a.position_sync_enabled, a.consecutive_sync_failures) == (True, 0)
    assert "turned off after repeated failures" not in resp.text
    # toggling again turns it off manually, without the failure message
    resp = await client.post("/setup/position-sync", headers=GOOD)
    a = await acct(db)
    assert a.position_sync_enabled is False
    assert "turned off after repeated failures" not in resp.text
