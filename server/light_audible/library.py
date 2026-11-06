from datetime import datetime, timezone

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from light_audible import account
from light_audible.audible_gateway import Chapter, SessionFactory
from light_audible.config import Settings
from light_audible.models import Book, Conversion
from light_audible.models import Chapter as ChapterRow

# authors/narrators are stored as "A, B" text and split back into lists by the API.
NAME_SEP = ", "


def split_names(value: str) -> list[str]:
    return value.split(NAME_SEP) if value else []


async def sync_library(db: AsyncSession, settings: Settings, factory: SessionFactory) -> int:
    async with account.audible_session(db, settings, factory) as session:
        items = await session.fetch_library()

    existing = {b.asin: b for b in (await db.execute(select(Book))).scalars()}
    for it in items:
        book = existing.get(it.asin)
        if book is None:
            book = Book(asin=it.asin)
            db.add(book)
        book.title = it.title
        book.authors = NAME_SEP.join(it.authors)
        book.narrators = NAME_SEP.join(it.narrators)
        book.runtime_ms = it.runtime_ms
        book.purchase_date = it.purchase_date
        # playable / unplayable_reason are deliberately left untouched.

    owned = {it.asin for it in items}
    removed = [asin for asin in existing if asin not in owned]
    if removed:
        await db.execute(delete(ChapterRow).where(ChapterRow.asin.in_(removed)))
        await db.execute(delete(Conversion).where(Conversion.asin.in_(removed)))
        await db.execute(delete(Book).where(Book.asin.in_(removed)))

    acct = await account.get_account(db, settings)
    acct.library_synced_at = datetime.now(timezone.utc)
    await db.commit()
    return len(owned)


async def get_chapters(
    db: AsyncSession, settings: Settings, factory: SessionFactory, asin: str
) -> list[Chapter]:
    book = await db.get(Book, asin)
    if book is None:
        raise LookupError(asin)
    if not book.chapters_fetched:
        async with account.audible_session(db, settings, factory) as session:
            fetched = await session.fetch_chapters(asin)
        await db.execute(delete(ChapterRow).where(ChapterRow.asin == asin))
        db.add_all(
            ChapterRow(asin=asin, idx=c.idx, title=c.title, start_ms=c.start_ms, length_ms=c.length_ms)
            for c in fetched
        )
        book.chapters_fetched = True
        await db.commit()
    rows = (
        await db.execute(select(ChapterRow).where(ChapterRow.asin == asin).order_by(ChapterRow.idx))
    ).scalars()
    return [Chapter(r.idx, r.title, r.start_ms, r.length_ms) for r in rows]
