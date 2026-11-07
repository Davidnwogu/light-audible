import logging
from datetime import datetime, timezone

from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from light_audible import account
from light_audible.audible_gateway import SessionFactory
from light_audible.config import Settings
from light_audible.models import Book, Position

log = logging.getLogger(__name__)

EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
MAX_CONSECUTIVE_FAILURES = 5


async def resolve(
    db: AsyncSession, settings: Settings, factory: SessionFactory, asin: str
) -> tuple[int, datetime]:
    """Newest of the server copy and (when sync is on) the Audible copy."""
    server = await db.get(Position, asin)
    best = (server.position_ms, server.updated_at) if server else (0, EPOCH)
    acct = await account.get_account(db, settings)
    if not acct.position_sync_enabled:
        return best
    try:
        async with account.audible_session(db, settings, factory) as session:
            remote = await session.get_position(asin)
    except account.NeedsSignIn:
        return best
    except Exception as e:
        # Reads never count toward the write-failure auto-disable.
        log.warning("audible position read failed: %s", type(e).__name__)
        await db.rollback()
        return best
    if remote is not None and remote.updated_at > best[1]:
        return remote.position_ms, remote.updated_at
    return best


async def save(
    db: AsyncSession,
    settings: Settings,
    factory: SessionFactory,
    asin: str,
    position_ms: int,
    client_updated_at: datetime,
) -> None:
    stmt = insert(Position).values(
        asin=asin, position_ms=position_ms, updated_at=client_updated_at
    )
    applied = await db.execute(
        stmt.on_conflict_do_update(
            index_elements=["asin"],
            set_={"position_ms": stmt.excluded.position_ms, "updated_at": stmt.excluded.updated_at},
            where=Position.updated_at <= stmt.excluded.updated_at,
        ).returning(Position.asin)
    )
    fresh = applied.first() is not None
    await db.commit()
    if not fresh:
        return  # stale write: a newer position is already stored

    acct = await account.get_account(db, settings)
    book = await db.get(Book, asin)
    if not acct.position_sync_enabled or book is None or not book.acr:
        return
    try:
        async with account.audible_session(db, settings, factory) as session:
            await session.set_position(asin, book.acr, position_ms)
    except account.NeedsSignIn:
        return
    except Exception as e:
        log.warning("audible position write failed: %s", type(e).__name__)
        await db.rollback()
        acct = await account.get_account(db, settings)
        await db.refresh(acct)
        acct.consecutive_sync_failures += 1
        if acct.consecutive_sync_failures >= MAX_CONSECUTIVE_FAILURES:
            acct.position_sync_enabled = False
        await db.commit()
        return
    await db.refresh(acct)
    if acct.consecutive_sync_failures:
        acct.consecutive_sync_failures = 0
        await db.commit()
