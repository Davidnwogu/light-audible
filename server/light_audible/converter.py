"""Background conversion of Audible downloads to DRM-free .m4b files in object storage.

Queue convention (see models.Conversion): status='preparing' with started_at NULL is
queued, NOT NULL is running. The worker claims one queued row at a time.
"""

import asyncio
import logging
import tempfile
import time
from collections.abc import Callable
from datetime import datetime, timedelta, timezone
from pathlib import Path

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from light_audible import account
from light_audible.audible_gateway import LicenseDenied, SessionFactory, TransientError
from light_audible.config import Settings
from light_audible.models import Book, Conversion
from light_audible.storage import Storage, evict_if_needed

log = logging.getLogger(__name__)

MAX_ATTEMPTS = 3
STUCK_AFTER = timedelta(minutes=30)
_ERROR_MAX_CHARS = 500


class ConversionError(Exception):
    """ffmpeg failed; message is the tail of its stderr."""


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def object_key(asin: str) -> str:
    return f"books/{asin}.m4b"


def ffmpeg_args(src: Path, dest: Path, key: str, iv: str) -> list[str]:
    return [
        "ffmpeg", "-y", "-audible_key", key, "-audible_iv", iv, "-i", str(src),
        "-map", "0:a", "-map_chapters", "0", "-c", "copy", str(dest),
    ]


def _redact(text: str, secrets: tuple[str, ...]) -> str:
    for secret in secrets:
        if secret:
            text = text.replace(secret, "***")
    return text


async def _run_ffmpeg(args: list[str], secrets: tuple[str, ...]) -> None:
    proc = await asyncio.create_subprocess_exec(
        *args, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE
    )
    try:
        _, stderr = await proc.communicate()
    except asyncio.CancelledError:
        proc.kill()
        await proc.wait()
        raise
    if proc.returncode != 0:
        # Redact before trimming so a secret straddling the cut can't leak partially.
        tail = _redact(stderr.decode(errors="replace"), secrets).strip()[-_ERROR_MAX_CHARS:]
        raise ConversionError(tail or f"ffmpeg exited with {proc.returncode}")


async def convert_one(
    db: AsyncSession, settings: Settings, factory: SessionFactory, storage: Storage, asin: str
) -> None:
    key = object_key(asin)
    with tempfile.TemporaryDirectory(prefix="light-audible-") as tmp:
        src = Path(tmp) / "source.aaxc"
        dest = Path(tmp) / "book.m4b"
        async with account.audible_session(db, settings, factory) as session:
            lic = await session.request_license(asin)
            book = await db.get(Book, asin)
            if book is not None:
                book.acr = lic.acr
                await db.commit()
            await session.download(lic.url, src)
        await _run_ffmpeg(ffmpeg_args(src, dest, lic.key, lic.iv), secrets=(lic.key, lic.iv))
        size = await asyncio.to_thread(storage.upload, dest, key)

    finished_at = _utcnow()
    # Atomic: the row may have been deleted (e.g. library sync) while we converted.
    marked = (
        await db.execute(
            update(Conversion)
            .where(Conversion.asin == asin, Conversion.status == "preparing")
            .values(
                status="ready",
                object_key=key,
                size_bytes=size,
                error=None,
                finished_at=finished_at,
                # The user just asked to play it: don't make it the first eviction candidate.
                last_played_at=finished_at,
            )
            .returning(Conversion.asin)
            .execution_options(synchronize_session="fetch")
        )
    ).scalar_one_or_none()
    await db.commit()
    if marked is None:
        await asyncio.to_thread(storage.delete, key)
        return
    try:
        await evict_if_needed(db, storage, settings.bucket_soft_limit_bytes, protect={asin})
    except Exception as e:
        # The conversion itself succeeded; a failed eviction must not mark it failed.
        await db.rollback()
        log.warning("eviction after converting %s failed: %s", asin, type(e).__name__)


def _short_error(e: BaseException) -> str:
    msg = str(e) or type(e).__name__
    return msg[:_ERROR_MAX_CHARS]


class Worker:
    def __init__(
        self,
        session_factory: async_sessionmaker[AsyncSession],
        settings: Settings,
        audible_factory: SessionFactory,
        storage: Storage,
        clock: Callable[[], datetime] = _utcnow,
    ) -> None:
        self.session_factory = session_factory
        self.settings = settings
        self.audible_factory = audible_factory
        self.storage = storage
        self.clock = clock
        self.idle_sleep_s = 2.0
        self.recover_interval_s = 300.0
        self._last_recover: float | None = None

    async def run_forever(self) -> None:
        while True:
            try:
                worked = await self.run_once()
            except Exception:
                log.exception("conversion worker iteration failed")
                worked = False
            if not worked:
                await self._maybe_recover()
                await asyncio.sleep(self.idle_sleep_s)

    async def _maybe_recover(self) -> None:
        now = time.monotonic()
        if self._last_recover is not None and now - self._last_recover < self.recover_interval_s:
            return
        self._last_recover = now
        try:
            async with self.session_factory() as db:
                n = await recover_stuck(db, self.clock())
            if n:
                log.info("re-queued %d stuck conversion(s)", n)
        except Exception as e:
            log.warning("stuck-conversion recovery failed: %s", type(e).__name__)

    async def run_once(self) -> bool:
        """Claim and process one queued conversion. Returns whether a row was processed."""
        async with self.session_factory() as db:
            conv = (
                await db.execute(
                    select(Conversion)
                    .where(Conversion.status == "preparing", Conversion.started_at.is_(None))
                    .order_by(Conversion.asin)
                    .limit(1)
                    .with_for_update(skip_locked=True)
                )
            ).scalar_one_or_none()
            if conv is None:
                await db.rollback()
                return False
            asin = conv.asin
            conv.started_at = self.clock()
            await db.commit()

            try:
                await convert_one(db, self.settings, self.audible_factory, self.storage, asin)
            except account.NeedsSignIn:
                await self._finish(db, asin, requeue=True)
                log.info("conversion of %s paused: Audible sign-in required", asin)
                return False
            except TransientError as e:
                await self._on_transient(db, asin, e)
            except LicenseDenied as e:
                await self._on_license_denied(db, asin, e)
            except Exception as e:
                log.warning("conversion of %s failed: %s", asin, type(e).__name__)
                await self._finish(db, asin, error=_short_error(e))
            return True

    async def _finish(
        self, db: AsyncSession, asin: str, *, requeue: bool = False, error: str | None = None
    ) -> None:
        await db.rollback()
        conv = await db.get(Conversion, asin, populate_existing=True)
        if conv is None or conv.status != "preparing":
            return
        if requeue:
            conv.started_at = None
        else:
            conv.status = "failed"
            conv.error = error
            conv.finished_at = self.clock()
        await db.commit()

    async def _on_transient(self, db: AsyncSession, asin: str, e: TransientError) -> None:
        await db.rollback()
        conv = await db.get(Conversion, asin, populate_existing=True)
        if conv is None or conv.status != "preparing":
            return
        conv.attempts += 1
        log.info("conversion of %s: transient error (attempt %d)", asin, conv.attempts)
        if conv.attempts < MAX_ATTEMPTS:
            conv.started_at = None
        else:
            conv.status = "failed"
            conv.error = _short_error(e)
            conv.finished_at = self.clock()
        await db.commit()

    async def _on_license_denied(self, db: AsyncSession, asin: str, e: LicenseDenied) -> None:
        await db.rollback()
        book = await db.get(Book, asin, populate_existing=True)
        if book is not None:
            book.playable = False
            book.unplayable_reason = _short_error(e)
        conv = await db.get(Conversion, asin, populate_existing=True)
        if conv is not None:
            await db.delete(conv)
        await db.commit()


async def recover_stuck(db: AsyncSession, now: datetime) -> int:
    """Re-queue conversions left running (e.g. by a crash) for longer than STUCK_AFTER."""
    result = await db.execute(
        update(Conversion)
        .where(Conversion.status == "preparing", Conversion.started_at < now - STUCK_AFTER)
        .values(started_at=None)
    )
    await db.commit()
    return result.rowcount
