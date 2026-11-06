from datetime import datetime, timedelta, timezone
from pathlib import Path

import boto3
import httpx
import pytest
from botocore.config import Config
from botocore.exceptions import ClientError

from light_audible.models import Book, Conversion
from light_audible.storage import Storage, evict_if_needed

NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)


@pytest.fixture
def storage(settings) -> Storage:
    client = boto3.client(
        "s3",
        endpoint_url=settings.s3_endpoint,
        aws_access_key_id=settings.s3_access_key_id,
        aws_secret_access_key=settings.s3_secret_access_key,
        region_name=settings.s3_region,
        config=Config(s3={"addressing_style": "path"}),
    )
    for page in client.get_paginator("list_objects_v2").paginate(Bucket=settings.s3_bucket):
        for obj in page.get("Contents", []):
            client.delete_object(Bucket=settings.s3_bucket, Key=obj["Key"])
    return Storage(settings)


def exists(storage: Storage, key: str) -> bool:
    try:
        storage._client.head_object(Bucket=storage._bucket, Key=key)
        return True
    except ClientError:
        return False


def put(tmp_path: Path, storage: Storage, asin: str, size: int) -> str:
    f = tmp_path / f"{asin}.m4b"
    f.write_bytes(b"x" * size)
    key = f"books/{asin}.m4b"
    storage.upload(f, key)
    return key


async def add_conversion(db, storage, tmp_path, asin, size, status="ready", played_days_ago=None):
    db.add(Book(asin=asin, title=asin, authors="[]", narrators="[]", runtime_ms=1))
    await db.flush()
    key = put(tmp_path, storage, asin, size)
    last = None if played_days_ago is None else NOW - timedelta(days=played_days_ago)
    db.add(Conversion(asin=asin, status=status, object_key=key, size_bytes=size, last_played_at=last))
    await db.commit()
    return key


def test_upload_and_presign_fetchable(storage, tmp_path):
    f = tmp_path / "a.m4b"
    f.write_bytes(b"audio-bytes")
    assert storage.upload(f, "books/A1.m4b") == 11
    r = httpx.get(storage.presign("books/A1.m4b"))
    assert r.status_code == 200
    assert r.content == b"audio-bytes"


def test_delete(storage, tmp_path):
    key = put(tmp_path, storage, "A1", 5)
    assert exists(storage, key)
    storage.delete(key)
    assert not exists(storage, key)


async def test_evict_oldest_first(db, storage, tmp_path):
    k_null = await add_conversion(db, storage, tmp_path, "NULLED", 100)
    k_old = await add_conversion(db, storage, tmp_path, "OLD", 100, played_days_ago=10)
    k_new = await add_conversion(db, storage, tmp_path, "NEW", 100, played_days_ago=1)
    evicted = await evict_if_needed(db, storage, limit=150, protect=set())
    assert evicted == ["NULLED", "OLD"]
    assert not exists(storage, k_null) and not exists(storage, k_old)
    assert exists(storage, k_new)
    remaining = (await db.execute(Conversion.__table__.select())).all()
    assert [r.asin for r in remaining] == ["NEW"]


async def test_evict_noop_under_limit(db, storage, tmp_path):
    k = await add_conversion(db, storage, tmp_path, "A", 100)
    assert await evict_if_needed(db, storage, limit=100, protect=set()) == []
    assert exists(storage, k)


async def test_evict_skips_protected(db, storage, tmp_path):
    k_prot = await add_conversion(db, storage, tmp_path, "PROT", 100, played_days_ago=30)
    k_prep = await add_conversion(db, storage, tmp_path, "PREP", 100, status="preparing", played_days_ago=40)
    k_other = await add_conversion(db, storage, tmp_path, "OTHER", 100, played_days_ago=1)
    evicted = await evict_if_needed(db, storage, limit=100, protect={"PROT"})
    assert evicted == ["OTHER"]
    assert exists(storage, k_prot) and exists(storage, k_prep) and not exists(storage, k_other)
    asins = {r.asin for r in (await db.execute(Conversion.__table__.select())).all()}
    assert asins == {"PROT", "PREP"}


async def test_evict_failure_leaves_no_ready_row_for_missing_object(db, storage, tmp_path, monkeypatch):
    k1 = await add_conversion(db, storage, tmp_path, "A1", 100, played_days_ago=30)
    await add_conversion(db, storage, tmp_path, "A2", 100, played_days_ago=20)
    await add_conversion(db, storage, tmp_path, "A3", 100, played_days_ago=1)
    real_delete = storage.delete
    calls = []

    def flaky(key):
        calls.append(key)
        if len(calls) == 2:
            raise RuntimeError("s3 down")
        real_delete(key)

    monkeypatch.setattr(storage, "delete", flaky)
    with pytest.raises(RuntimeError):
        await evict_if_needed(db, storage, limit=0, protect=set())
    await db.rollback()
    assert not exists(storage, k1)
    rows = (await db.execute(Conversion.__table__.select())).all()
    assert "A1" not in {r.asin for r in rows}
    for r in rows:
        assert r.status != "ready" or exists(storage, r.object_key)
