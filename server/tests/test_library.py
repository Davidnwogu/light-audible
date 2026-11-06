from datetime import date, datetime, timezone

import pytest
from sqlalchemy import select

from light_audible import account, library, pairing
from light_audible.audible_gateway import Chapter, LibraryItem
from light_audible.audible_gateway.fake import FakeAudibleSession
from light_audible.models import Book, Chapter as ChapterRow, Conversion

T0 = datetime(2026, 1, 1, 12, 0, tzinfo=timezone.utc)
AUTH = {"access_token": "x"}


def item(asin, title="T", purchase=date(2025, 1, 1), authors=("A", "B"), narrators=("N",)):
    return LibraryItem(asin, title, list(authors), list(narrators), 3_600_000, purchase)


@pytest.fixture
def fake():
    return FakeAudibleSession(auth=AUTH)


@pytest.fixture
def factory(fake, app):
    f = lambda auth: fake  # noqa: E731
    app.state.audible_factory = f
    return f


@pytest.fixture
async def signed_in(db, settings):
    await account.save_auth(db, settings, AUTH)


@pytest.fixture
async def headers(client, db, app):
    app.state.clock = lambda: T0
    code = await pairing.create_code(db, now=T0)
    token = (await client.post("/api/pair", json={"code": code})).json()["token"]
    return {"Authorization": f"Bearer {token}"}


async def test_sync_inserts_books(db, settings, factory, fake, signed_in):
    fake.library = [item("B1", "One"), item("B2", "Two")]
    assert await library.sync_library(db, settings, factory) == 2
    books = (await db.execute(select(Book).order_by(Book.asin))).scalars().all()
    assert [b.title for b in books] == ["One", "Two"]
    assert books[0].authors == "A, B"
    assert (await account.get_account(db, settings)).library_synced_at is not None


async def test_sync_removes_unowned(db, settings, factory, fake, signed_in):
    fake.library = [item("B1"), item("B2")]
    await library.sync_library(db, settings, factory)
    db.add(ChapterRow(asin="B2", idx=0, title="c", start_ms=0, length_ms=1))
    db.add(Conversion(asin="B2", status="ready"))
    await db.commit()
    fake.library = [item("B1")]
    assert await library.sync_library(db, settings, factory) == 1
    db.expire_all()
    assert [b.asin for b in (await db.execute(select(Book))).scalars()] == ["B1"]
    assert (await db.execute(select(ChapterRow))).scalars().all() == []
    assert (await db.execute(select(Conversion))).scalars().all() == []


async def test_sync_keeps_unplayable_flag(db, settings, factory, fake, signed_in):
    fake.library = [item("B1", "Old")]
    await library.sync_library(db, settings, factory)
    book = await db.get(Book, "B1")
    book.playable = False
    book.unplayable_reason = "drm"
    await db.commit()
    fake.library = [item("B1", "New")]
    await library.sync_library(db, settings, factory)
    db.expire_all()
    book = await db.get(Book, "B1")
    assert book.title == "New"
    assert book.playable is False
    assert book.unplayable_reason == "drm"


async def test_library_etag_304(client, db, settings, factory, fake, signed_in, headers):
    fake.library = [item("B1", purchase=date(2024, 1, 1)), item("B2", purchase=date(2025, 1, 1))]
    await library.sync_library(db, settings, factory)
    resp = await client.get("/api/library", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert [b["asin"] for b in body] == ["B2", "B1"]
    assert body[0] == {
        "asin": "B2", "title": "T", "authors": ["A", "B"], "narrators": ["N"],
        "runtime_ms": 3_600_000, "playable": True,
    }
    etag = resp.headers["etag"]
    acct = await account.get_account(db, settings)
    await db.refresh(acct)
    assert etag == f'"{acct.library_synced_at.isoformat()}"'
    again = await client.get("/api/library", headers={**headers, "If-None-Match": etag})
    assert again.status_code == 304


async def test_refresh_resyncs(client, db, settings, factory, fake, signed_in, headers):
    fake.library = [item("B1")]
    await library.sync_library(db, settings, factory)
    old = (await client.get("/api/library", headers=headers)).headers["etag"]
    fake.library = [item("B1"), item("B2", purchase=date(2026, 1, 1))]
    resp = await client.post("/api/library/refresh", headers=headers)
    assert resp.status_code == 200
    assert [b["asin"] for b in resp.json()] == ["B2", "B1"]
    assert resp.headers["etag"] != old


async def test_library_needs_sign_in_409(client, db, settings, factory, fake, signed_in, headers):
    fake.library = [item("B1")]
    await library.sync_library(db, settings, factory)
    await account.mark_needs_sign_in(db)
    resp = await client.post("/api/library/refresh", headers=headers)
    assert resp.status_code == 409
    assert resp.json() == {"error": "needs_sign_in"}
    stored = await client.get("/api/library", headers=headers)
    assert stored.status_code == 200
    assert [b["asin"] for b in stored.json()] == ["B1"]


async def test_refresh_unexpected_audible_error_502(client, db, settings, app, signed_in, headers):
    class Boom(FakeAudibleSession):
        async def fetch_library(self):
            raise RuntimeError("404 not found")

    app.state.audible_factory = lambda auth: Boom(auth=AUTH)
    resp = await client.post("/api/library/refresh", headers=headers)
    assert resp.status_code == 502
    assert resp.json() == {"error": "audible_error"}


async def test_library_requires_token(client):
    assert (await client.get("/api/library")).status_code == 401
    assert (await client.post("/api/library/refresh")).status_code == 401


async def test_chapters_fetched_once(db, settings, factory, fake, signed_in):
    fake.library = [item("B1")]
    fake.chapters = {"B1": [Chapter(0, "Intro", 0, 1000), Chapter(1, "Two", 1000, 2000)]}
    await library.sync_library(db, settings, factory)
    first = await library.get_chapters(db, settings, factory, "B1")
    second = await library.get_chapters(db, settings, factory, "B1")
    assert first == second == fake.chapters["B1"]
    assert fake.fetch_chapters_calls == ["B1"]
    assert (await db.get(Book, "B1")).chapters_fetched is True
