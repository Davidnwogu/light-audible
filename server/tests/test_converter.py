import asyncio
import json
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from light_audible import account, converter
from light_audible.audible_gateway import License, LicenseDenied, TransientError
from light_audible.audible_gateway.fake import FakeAudibleSession
from light_audible.db import make_engine
from light_audible.main import create_app
from light_audible.models import Book, Conversion

AUTH = {"access_token": "x"}
NOW = datetime(2026, 1, 1, 12, 0, tzinfo=timezone.utc)
KEY = "0" * 32
IV = "1" * 32
CHAPTERS = """;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=1500
title=One
[CHAPTER]
TIMEBASE=1/1000
START=1500
END=3000
title=Two
"""


@pytest.fixture
def fixture_m4a(tmp_path: Path) -> Path:
    chapters = tmp_path / "chapters.txt"
    chapters.write_text(CHAPTERS)
    out = tmp_path / "fixture.m4a"
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=duration=3",
         "-i", str(chapters), "-map_metadata", "1", "-c:a", "aac", str(out)],
        check=True,
    )
    return out


@pytest.fixture
def fake(fixture_m4a):
    return FakeAudibleSession(
        auth=AUTH,
        licenses={"B1": License(url="https://cdn.test/B1", key=KEY, iv=IV, acr="ACR1")},
        download_source=fixture_m4a,
    )


@pytest.fixture
def factory(fake):
    return lambda auth: fake


@pytest.fixture
async def signed_in(db, settings):
    await account.save_auth(db, settings, AUTH)


@pytest.fixture
async def session_factory(db, migrated_database):
    engine = make_engine(migrated_database)
    yield async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    await engine.dispose()


@pytest.fixture
def worker(session_factory, settings, factory, storage):
    return converter.Worker(session_factory, settings, factory, storage, clock=lambda: NOW)


async def seed(db, asin="B1", **conv):
    db.add(Book(asin=asin, title=asin, authors="[]", narrators="[]", runtime_ms=3000))
    await db.flush()
    if conv:
        db.add(Conversion(asin=asin, **conv))
    await db.commit()


async def reload(db, model, key):
    db.expire_all()
    return await db.get(model, key)


def test_ffmpeg_args_exact():
    assert converter.ffmpeg_args(Path("/t/in.aaxc"), Path("/t/out.m4b"), "k", "v") == [
        "ffmpeg", "-y", "-audible_key", "k", "-audible_iv", "v", "-i", "/t/in.aaxc",
        "-map", "0:a", "-map_chapters", "0", "-c", "copy", "/t/out.m4b",
    ]


async def test_convert_one_real_ffmpeg(db, settings, factory, storage, signed_in, tmp_path):
    await seed(db, status="preparing", started_at=NOW)
    await converter.convert_one(db, settings, factory, storage, "B1")

    conv = await reload(db, Conversion, "B1")
    assert conv.status == "ready"
    assert conv.object_key == "books/B1.m4b"
    assert conv.size_bytes > 0
    assert conv.finished_at is not None
    assert (await db.get(Book, "B1")).acr == "ACR1"

    out = tmp_path / "downloaded.m4b"
    storage._client.download_file(storage._bucket, "books/B1.m4b", str(out))
    assert out.stat().st_size == conv.size_bytes
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-show_chapters", "-of", "json", str(out)],
        check=True, capture_output=True, text=True,
    )
    assert len(json.loads(probe.stdout)["chapters"]) >= 1


async def test_convert_one_ffmpeg_failure_raises(db, settings, fake, factory, storage, signed_in, tmp_path):
    bad = tmp_path / "bad.aaxc"
    bad.write_bytes(b"not audio")
    fake.download_source = bad
    await seed(db, status="preparing", started_at=NOW)
    with pytest.raises(converter.ConversionError):
        await converter.convert_one(db, settings, factory, storage, "B1")


async def test_worker_converts_queued_row(db, worker, signed_in):
    await seed(db, status="preparing")
    assert await worker.run_once() is True
    assert (await reload(db, Conversion, "B1")).status == "ready"
    assert await worker.run_once() is False


async def test_transient_error_retries_then_fails(db, worker, fake, signed_in):
    fake.license_errors["B1"] = TransientError("network down")
    await seed(db, status="preparing")
    for attempt in (1, 2):
        assert await worker.run_once() is True
        conv = await reload(db, Conversion, "B1")
        assert (conv.status, conv.attempts, conv.started_at) == ("preparing", attempt, None)
    assert await worker.run_once() is True
    conv = await reload(db, Conversion, "B1")
    assert (conv.status, conv.attempts) == ("failed", 3)
    assert "network down" in conv.error
    assert await worker.run_once() is False


async def test_conversion_error_fails_immediately(db, worker, fake, signed_in, tmp_path):
    bad = tmp_path / "bad.aaxc"
    bad.write_bytes(b"not audio")
    fake.download_source = bad
    await seed(db, status="preparing")
    assert await worker.run_once() is True
    conv = await reload(db, Conversion, "B1")
    assert conv.status == "failed"
    assert conv.error


async def test_license_denied_marks_unplayable(db, worker, fake, signed_in):
    fake.license_errors["B1"] = LicenseDenied("Not entitled")
    await seed(db, status="preparing")
    assert await worker.run_once() is True
    assert await reload(db, Conversion, "B1") is None
    book = await db.get(Book, "B1")
    assert book.playable is False
    assert book.unplayable_reason == "Not entitled"


async def test_unexpected_error_marks_failed(db, worker, fake, signed_in):
    fake.license_errors["B1"] = Exception("404 boom")
    await seed(db, status="preparing")
    assert await worker.run_once() is True
    conv = await reload(db, Conversion, "B1")
    assert conv.status == "failed"
    assert "404 boom" in conv.error


async def test_needs_sign_in_leaves_row_queued(db, worker, signed_in):
    await account.mark_needs_sign_in(db)
    await seed(db, status="preparing")
    assert await worker.run_once() is False
    conv = await reload(db, Conversion, "B1")
    assert (conv.status, conv.attempts, conv.started_at) == ("preparing", 0, None)


async def test_run_forever_survives_run_once_errors(worker, monkeypatch):
    calls = 0

    async def flaky():
        nonlocal calls
        calls += 1
        if calls == 1:
            raise RuntimeError("db down")
        if calls == 3:
            raise SystemExit  # stop the loop
        return False

    monkeypatch.setattr(worker, "run_once", flaky)
    worker.idle_sleep_s = 0
    with pytest.raises(SystemExit):
        await worker.run_forever()
    assert calls == 3


async def test_recover_stuck(db):
    await seed(db, "OLD", status="preparing", started_at=NOW - timedelta(minutes=31))
    await seed(db, "FRESH", status="preparing", started_at=NOW - timedelta(minutes=10))
    await seed(db, "DONE", status="ready", started_at=NOW - timedelta(hours=2))
    assert await converter.recover_stuck(db, NOW) == 1
    assert (await reload(db, Conversion, "OLD")).started_at is None
    assert (await reload(db, Conversion, "FRESH")).started_at is not None
    assert (await reload(db, Conversion, "DONE")).started_at is not None


async def test_lifespan_recovers_stuck_and_runs_worker(db, settings):
    await seed(db, "OLD", status="preparing", started_at=datetime.now(timezone.utc) - timedelta(hours=1))
    app = create_app(settings)  # signed out: worker claims the row, hits NeedsSignIn, re-queues it
    async with app.router.lifespan_context(app):
        await asyncio.sleep(0.5)
    conv = await reload(db, Conversion, "OLD")
    assert (conv.status, conv.attempts, conv.started_at) == ("preparing", 0, None)
