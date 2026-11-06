import asyncio
from datetime import datetime, timedelta, timezone

import httpx
import pytest
from sqlalchemy import func, select

from light_audible import account, pairing
from light_audible.audible_gateway import Chapter
from light_audible.audible_gateway.fake import FakeAudibleSession
from light_audible.models import Book, Conversion

T0 = datetime(2026, 1, 1, 12, 0, tzinfo=timezone.utc)
AUTH = {"access_token": "x"}


@pytest.fixture
def fake(app):
    f = FakeAudibleSession(auth=AUTH)
    app.state.audible_factory = lambda auth: f
    return f


@pytest.fixture
def storage_on_app(app, storage):
    app.state.storage = storage
    return storage


@pytest.fixture
async def signed_in(db, settings):
    await account.save_auth(db, settings, AUTH)


@pytest.fixture
async def headers(client, db, app):
    app.state.clock = lambda: T0
    code = await pairing.create_code(db, now=T0)
    token = (await client.post("/api/pair", json={"code": code})).json()["token"]
    return {"Authorization": f"Bearer {token}"}


async def seed(db, asin="B1", playable=True, reason=None, **conv):
    db.add(Book(asin=asin, title=asin, authors="[]", narrators="[]", runtime_ms=3000,
                playable=playable, unplayable_reason=reason))
    await db.flush()
    if conv:
        db.add(Conversion(asin=asin, **conv))
    await db.commit()


async def reload(db, asin):
    db.expire_all()
    return await db.get(Conversion, asin)


async def test_unknown_book_404(client, headers, signed_in):
    assert (await client.get("/api/books/NOPE", headers=headers)).status_code == 404
    assert (await client.post("/api/books/NOPE/prepare", headers=headers)).status_code == 404


async def test_books_require_token(client):
    assert (await client.get("/api/books/B1")).status_code == 401
    assert (await client.post("/api/books/B1/prepare")).status_code == 401


async def test_get_book_includes_chapters(client, db, fake, headers, signed_in):
    fake.chapters = {"B1": [Chapter(0, "Intro", 0, 1000), Chapter(1, "Two", 1000, 2000)]}
    await seed(db)
    resp = await client.get("/api/books/B1", headers=headers)
    assert resp.status_code == 200
    assert resp.json() == {
        "status": "not_ready",
        "chapters": [
            {"idx": 0, "title": "Intro", "start_ms": 0, "length_ms": 1000},
            {"idx": 1, "title": "Two", "start_ms": 1000, "length_ms": 2000},
        ],
    }
    assert await reload(db, "B1") is None


async def test_get_book_failed_and_unplayable(client, db, fake, headers, signed_in):
    await seed(db, "F1", status="failed", error="ffmpeg exploded")
    await seed(db, "U1", playable=False, reason="Not entitled")
    failed = (await client.get("/api/books/F1", headers=headers)).json()
    assert (failed["status"], failed["error"]) == ("failed", "ffmpeg exploded")
    unplayable = (await client.get("/api/books/U1", headers=headers)).json()
    assert (unplayable["status"], unplayable["error"]) == ("unplayable", "Not entitled")


async def test_prepare_queues(client, db, headers, signed_in):
    await seed(db)
    resp = await client.post("/api/books/B1/prepare", headers=headers)
    assert resp.status_code == 200
    assert resp.json() == {"status": "preparing"}
    conv = await reload(db, "B1")
    assert (conv.status, conv.attempts, conv.started_at) == ("preparing", 0, None)


async def test_prepare_twice_creates_one_job(app, db, headers, signed_in):
    await seed(db)
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as c:
        r1, r2 = await asyncio.gather(
            c.post("/api/books/B1/prepare", headers=headers),
            c.post("/api/books/B1/prepare", headers=headers),
        )
    assert r1.status_code == r2.status_code == 200
    assert r1.json()["status"] == r2.json()["status"] == "preparing"
    count = (await db.execute(select(func.count()).select_from(Conversion))).scalar_one()
    assert count == 1


async def test_prepare_ready_returns_presigned_url_and_updates_last_played(
    client, db, headers, signed_in, storage_on_app, tmp_path
):
    f = tmp_path / "b.m4b"
    f.write_bytes(b"audio")
    storage_on_app.upload(f, "books/B1.m4b")
    await seed(db, status="ready", object_key="books/B1.m4b", size_bytes=5,
               last_played_at=T0 - timedelta(days=3))
    resp = await client.post("/api/books/B1/prepare", headers=headers)
    body = resp.json()
    assert body["status"] == "ready"
    assert datetime.fromisoformat(body["url_expires_at"]) == T0 + timedelta(seconds=21600)
    fetched = httpx.get(body["url"])
    assert fetched.status_code == 200 and fetched.content == b"audio"
    assert (await reload(db, "B1")).last_played_at == T0


async def test_prepare_failed_requeues(client, db, headers, signed_in):
    await seed(db, status="failed", attempts=3, error="boom", started_at=T0)
    resp = await client.post("/api/books/B1/prepare", headers=headers)
    assert resp.json() == {"status": "preparing"}
    conv = await reload(db, "B1")
    assert (conv.status, conv.attempts, conv.error, conv.started_at) == ("preparing", 0, None, None)


async def test_prepare_unplayable_returned_as_is(client, db, headers, signed_in):
    await seed(db, playable=False, reason="Not entitled")
    resp = await client.post("/api/books/B1/prepare", headers=headers)
    assert resp.json() == {"status": "unplayable", "error": "Not entitled"}
    assert await reload(db, "B1") is None
