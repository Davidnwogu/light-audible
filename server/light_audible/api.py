from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from light_audible import account, library, pairing
from light_audible.config import Settings
from light_audible.db import get_session
from light_audible.models import Book, Device

router = APIRouter(prefix="/api")


class PairRequest(BaseModel):
    code: str


@router.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@router.post("/pair")
async def pair(
    body: PairRequest,
    db: AsyncSession = Depends(get_session),
    clock=Depends(pairing.get_clock),
):
    try:
        token = await pairing.redeem(db, body.code, now=clock())
    except pairing.PairingError:
        return JSONResponse({"error": "invalid_code"}, status_code=400)
    return {"token": token}


async def _library_response(db: AsyncSession) -> list[dict]:
    books = (
        await db.execute(select(Book).order_by(Book.purchase_date.desc().nulls_last(), Book.asin))
    ).scalars()
    return [
        {
            "asin": b.asin,
            "title": b.title,
            "authors": library.split_names(b.authors),
            "narrators": library.split_names(b.narrators),
            "runtime_ms": b.runtime_ms,
            "playable": b.playable,
        }
        for b in books
    ]


async def _library_json(db: AsyncSession, settings: Settings) -> JSONResponse:
    acct = await account.get_account(db, settings)
    await db.refresh(acct)
    headers = {}
    if acct.library_synced_at is not None:
        headers["ETag"] = f'"{acct.library_synced_at.isoformat()}"'
    return JSONResponse(await _library_response(db), headers=headers)


@router.get("/library")
async def get_library(
    request: Request,
    db: AsyncSession = Depends(get_session),
    _device: Device = Depends(pairing.require_device),
):
    resp = await _library_json(db, request.app.state.settings)
    etag = resp.headers.get("etag")
    if etag is not None and request.headers.get("if-none-match") == etag:
        return Response(status_code=304, headers={"ETag": etag})
    return resp


@router.post("/library/refresh")
async def refresh_library(
    request: Request,
    db: AsyncSession = Depends(get_session),
    _device: Device = Depends(pairing.require_device),
):
    settings = request.app.state.settings
    try:
        await library.sync_library(db, settings, request.app.state.audible_factory)
    except account.NeedsSignIn:
        raise
    except Exception:
        await db.rollback()
        return JSONResponse({"error": "audible_error"}, status_code=502)
    return await _library_json(db, settings)
