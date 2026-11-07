import asyncio
import json
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from botocore.exceptions import ClientError
from sqlalchemy import delete, select
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
        "-map", "0:a", "-map_chapters", "0", "-c", "copy", "-movflags", "+faststart", "/t/out.m4b",
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


async def test_fresh_conversions_not_first_evicted(db, settings, fake, factory, storage, signed_in, tmp_path):
    old = tmp_path / "old.m4b"
    old.write_bytes(b"x" * 10_000)
    storage.upload(old, "books/OLD.m4b")
    await seed(db, "OLD", status="ready", object_key="books/OLD.m4b", size_bytes=10_000,
               last_played_at=NOW - timedelta(days=10))
    fake.licenses["B2"] = fake.licenses["B1"]
    await seed(db, "B1", status="preparing", started_at=NOW)
    await seed(db, "B2", status="preparing", started_at=NOW)

    await converter.convert_one(db, settings, factory, storage, "B1")
    a = await reload(db, Conversion, "B1")
    assert a.last_played_at == a.finished_at
    settings.bucket_soft_limit_bytes = 2 * a.size_bytes + 5_000  # forces exactly one eviction
    await converter.convert_one(db, settings, factory, storage, "B2")

    db.expire_all()
    remaining = {c.asin for c in (await db.execute(select(Conversion))).scalars()}
    assert remaining == {"B1", "B2"}


async def test_ffmpeg_error_redacts_key_and_iv(db, worker, signed_in, monkeypatch):
    def leaky(src, dest, key, iv):
        return ["sh", "-c", f"echo 'bad key {key} and iv {iv}' >&2; printf '%2000s' | tr ' ' x >&2; exit 1"]

    monkeypatch.setattr(converter, "ffmpeg_args", leaky)
    await seed(db, status="preparing")
    assert await worker.run_once() is True
    conv = await reload(db, Conversion, "B1")
    assert conv.status == "failed"
    assert KEY not in conv.error and IV not in conv.error
    assert len(conv.error) <= 500


async def test_ffmpeg_error_redacted_short_output(db, worker, signed_in, monkeypatch):
    monkeypatch.setattr(
        converter, "ffmpeg_args",
        lambda src, dest, key, iv: ["sh", "-c", f"echo 'bad key {key} iv {iv}' >&2; exit 1"],
    )
    await seed(db, status="preparing")
    await worker.run_once()
    conv = await reload(db, Conversion, "B1")
    assert conv.error == "bad key *** iv ***"


async def test_row_deleted_mid_conversion_cleans_up_object(
    db, settings, fake, session_factory, storage, signed_in, fixture_m4a
):
    class DeletesRowDuringDownload(FakeAudibleSession):
        async def download(self, url, dest):
            await super().download(url, dest)
            async with session_factory() as other:  # e.g. a library sync removing the book
                await other.execute(delete(Conversion).where(Conversion.asin == "B1"))
                await other.commit()

    session = DeletesRowDuringDownload(
        auth=AUTH, licenses=fake.licenses, download_source=fixture_m4a
    )
    worker = converter.Worker(session_factory, settings, lambda auth: session, storage, clock=lambda: NOW)
    await seed(db, status="preparing")
    assert await worker.run_once() is True
    assert await reload(db, Conversion, "B1") is None
    with pytest.raises(ClientError):
        storage._client.head_object(Bucket=storage._bucket, Key="books/B1.m4b")


async def test_eviction_failure_leaves_new_book_ready(
    db, settings, fake, session_factory, storage, signed_in, tmp_path, monkeypatch
):
    old = tmp_path / "old.m4b"
    old.write_bytes(b"x" * 10_000)
    storage.upload(old, "books/OLD.m4b")
    await seed(db, "OLD", status="ready", object_key="books/OLD.m4b", size_bytes=10_000,
               last_played_at=NOW - timedelta(days=10))
    await seed(db, "B1", status="preparing")
    settings.bucket_soft_limit_bytes = 1  # forces eviction of OLD

    def boom(key):
        raise RuntimeError("s3 down")

    monkeypatch.setattr(storage, "delete", boom)
    worker = converter.Worker(session_factory, settings, lambda auth: fake, storage, clock=lambda: NOW)
    assert await worker.run_once() is True
    assert (await reload(db, Conversion, "B1")).status == "ready"


async def test_finish_and_transient_do_not_touch_non_preparing_rows(db, worker):
    await seed(db, status="ready", object_key="books/B1.m4b", size_bytes=1)
    await worker._finish(db, "B1", error="late failure")
    assert (await reload(db, Conversion, "B1")).status == "ready"
    await worker._on_transient(db, "B1", TransientError("late"))
    conv = await reload(db, Conversion, "B1")
    assert (conv.status, conv.attempts) == ("ready", 0)


async def test_run_forever_recovers_stuck_rows_without_restart(db, worker, monkeypatch):
    await seed(db, status="preparing", started_at=NOW - timedelta(minutes=31))

    async def no_work():
        return False

    monkeypatch.setattr(worker, "run_once", no_work)
    worker.idle_sleep_s = 0.01
    worker.recover_interval_s = 0
    task = asyncio.create_task(worker.run_forever())
    try:
        for _ in range(100):
            await asyncio.sleep(0.02)
            if (await reload(db, Conversion, "B1")).started_at is None:
                break
    finally:
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
    assert (await reload(db, Conversion, "B1")).started_at is None
