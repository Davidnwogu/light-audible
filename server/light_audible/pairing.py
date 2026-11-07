import hashlib
import secrets
from collections.abc import Callable
from datetime import datetime, timedelta, timezone

from fastapi import Depends, Request
from fastapi.responses import JSONResponse
from sqlalchemy import delete, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from light_audible.db import get_session
from light_audible.models import Device, PairingCode

CODE_TTL = timedelta(minutes=10)
MAX_FAILED_ATTEMPTS = 5


class PairingError(Exception):
    pass


class UnauthorizedError(Exception):
    pass


def unauthorized_handler(_request: Request, _exc: UnauthorizedError) -> JSONResponse:
    return JSONResponse({"error": "unauthorized"}, status_code=401)


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


async def create_code(db: AsyncSession, now: datetime | None = None) -> str:
    """Create a fresh 6-digit pairing code, replacing any existing one. Returned once, stored hashed."""
    now = now or _utcnow()
    code = f"{secrets.randbelow(1_000_000):06d}"
    await db.execute(delete(PairingCode))
    db.add(PairingCode(code_hash=_sha256(code), expires_at=now + CODE_TTL, failed_attempts=0))
    await db.commit()
    return code


async def redeem(db: AsyncSession, code: str, now: datetime | None = None) -> str:
    """Exchange a valid pairing code for a new device token, revoking all earlier devices."""
    now = now or _utcnow()
    # Row lock serializes concurrent redeems: attempts counting and consumption are race-free.
    row = (await db.execute(select(PairingCode).limit(1).with_for_update())).scalar_one_or_none()
    if row is None or row.expires_at <= now or row.failed_attempts >= MAX_FAILED_ATTEMPTS:
        await db.rollback()
        raise PairingError("invalid_code")
    if not secrets.compare_digest(row.code_hash, _sha256(code)):
        row.failed_attempts += 1
        await db.commit()
        raise PairingError("invalid_code")

    token = secrets.token_urlsafe(32)
    await db.execute(update(Device).where(Device.revoked_at.is_(None)).values(revoked_at=now))
    db.add(Device(token_hash=_sha256(token)))
    await db.delete(row)
    await db.commit()
    return token


async def require_device(request: Request, db: AsyncSession = Depends(get_session)) -> Device:
    header = request.headers.get("authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise UnauthorizedError
    device = (
        await db.execute(
            select(Device).where(Device.token_hash == _sha256(token.strip()), Device.revoked_at.is_(None))
        )
    ).scalar_one_or_none()
    if device is None:
        raise UnauthorizedError
    return device


def get_clock(request: Request) -> Callable[[], datetime]:
    return getattr(request.app.state, "clock", _utcnow)
