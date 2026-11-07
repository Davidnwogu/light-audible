"""S3-compatible object storage and LRU eviction of converted audiobooks.

`Storage` is synchronous (boto3 is blocking). Async callers wrap its methods in
`asyncio.to_thread`; `evict_if_needed` does this itself.
"""

import asyncio
from pathlib import Path

import boto3
from botocore.config import Config
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from light_audible.config import Settings
from light_audible.models import Conversion


class Storage:
    def __init__(self, settings: Settings) -> None:
        self._bucket = settings.s3_bucket
        self._client = boto3.client(
            "s3",
            endpoint_url=settings.s3_endpoint,
            aws_access_key_id=settings.s3_access_key_id,
            aws_secret_access_key=settings.s3_secret_access_key,
            region_name=settings.s3_region,
            config=Config(signature_version="s3v4", s3={"addressing_style": settings.s3_addressing_style}),
        )

    def upload(self, path: Path, key: str) -> int:
        self._client.upload_file(str(path), self._bucket, key)
        return path.stat().st_size

    def presign(self, key: str, expires_s: int = 21600) -> str:
        return self._client.generate_presigned_url(
            "get_object",
            Params={"Bucket": self._bucket, "Key": key},
            ExpiresIn=expires_s,
        )

    def delete(self, key: str) -> None:
        self._client.delete_object(Bucket=self._bucket, Key=key)


async def evict_if_needed(
    db: AsyncSession, storage: Storage, limit: int, protect: set[str]
) -> list[str]:
    """Delete least-recently-played ready conversions until ready bytes <= limit."""
    rows = (
        await db.execute(select(Conversion).where(Conversion.status == "ready"))
    ).scalars().all()
    total = sum(r.size_bytes or 0 for r in rows)
    candidates = sorted(
        (r for r in rows if r.asin not in protect),
        key=lambda r: (r.last_played_at is not None, r.last_played_at),
    )
    evicted: list[str] = []
    for row in candidates:
        if total <= limit:
            break
        key = row.object_key or f"books/{row.asin}.m4b"
        size = row.size_bytes or 0
        # Row first, then object: a failure leaves an orphan object (harmless),
        # never a ready row pointing at a missing object.
        await db.delete(row)
        await db.commit()
        await asyncio.to_thread(storage.delete, key)
        total -= size
        evicted.append(row.asin)
    return evicted
