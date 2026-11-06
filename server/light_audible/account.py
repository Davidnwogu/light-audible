from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy.ext.asyncio import AsyncSession

from light_audible import crypto
from light_audible.audible_gateway import AudibleSession, AuthExpired, SessionFactory
from light_audible.config import Settings
from light_audible.models import Account


class NeedsSignIn(Exception):
    pass


async def get_account(db: AsyncSession, settings: Settings | None = None) -> Account:
    acct = await db.get(Account, 1)
    if acct is None:
        if settings is None:
            from light_audible.config import get_settings

            settings = get_settings()
        acct = Account(id=1, locale=settings.audible_locale)
        db.add(acct)
        await db.commit()
    return acct


async def save_auth(db: AsyncSession, settings: Settings, auth: dict) -> None:
    acct = await get_account(db, settings)
    acct.encrypted_auth = crypto.encrypt_auth(auth, settings.auth_encryption_key)
    acct.status = "ok"
    acct.pending_login = None
    await db.commit()


async def mark_needs_sign_in(db: AsyncSession) -> None:
    acct = await get_account(db)
    acct.status = "needs_sign_in"
    await db.commit()


@asynccontextmanager
async def audible_session(
    db: AsyncSession, settings: Settings, factory: SessionFactory
) -> AsyncIterator[AudibleSession]:
    acct = await get_account(db, settings)
    if acct.status != "ok" or acct.encrypted_auth is None:
        raise NeedsSignIn()
    auth = crypto.decrypt_auth(acct.encrypted_auth, settings.auth_encryption_key)
    session = factory(auth)
    try:
        yield session
    except AuthExpired as e:
        await mark_needs_sign_in(db)
        raise NeedsSignIn() from e
    except BaseException:
        await _save_if_changed(db, settings, session, auth)
        raise
    else:
        await _save_if_changed(db, settings, session, auth)


async def _save_if_changed(
    db: AsyncSession, settings: Settings, session: AudibleSession, original: dict
) -> None:
    current = session.export_auth()
    if current != original:
        await save_auth(db, settings, current)
