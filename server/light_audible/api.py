import asyncio
from datetime import timedelta

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse, Response
from pydantic import AwareDatetime, BaseModel, Field
from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from light_audible import account, converter, library, pairing, positions
from light_audible.config import Settings
from light_audible.db import get_session
from light_audible.models import Book, Conversion, Device

PRESIGN_EXPIRES_S = 21600

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


def _not_found() -> JSONResponse:
    return JSONResponse({"error": "not_found"}, status_code=404)


def _book_state(book: Book, conv: Conversion | None) -> dict:
    if not book.playable:
        return {"status": "unplayable", "error": book.unplayable_reason}
    if conv is None:
        return {"status": "not_ready"}
    state: dict = {"status": conv.status}
    if conv.status == "failed":
        state["error"] = conv.error
    return state


@router.get("/books/{asin}")
async def get_book(
    asin: str,
    request: Request,
    db: AsyncSession = Depends(get_session),
    _device: Device = Depends(pairing.require_device),
):
    book = await db.get(Book, asin)
    if book is None:
        return _not_found()
    state = _book_state(book, await db.get(Conversion, asin))
    chapters = await library.get_chapters(
        db, request.app.state.settings, request.app.state.audible_factory, asin
    )
    state["chapters"] = [
        {"idx": c.idx, "title": c.title, "start_ms": c.start_ms, "length_ms": c.length_ms}
        for c in chapters
    ]
    return state


@router.post("/books/{asin}/prepare")
async def prepare_book(
    asin: str,
    request: Request,
    db: AsyncSession = Depends(get_session),
    clock=Depends(pairing.get_clock),
    _device: Device = Depends(pairing.require_device),
):
    book = await db.get(Book, asin)
    if book is None:
        return _not_found()
    if not book.playable:
        return _book_state(book, None)

    # Idempotent under concurrency: only one queued row per asin can ever be created.
    await db.execute(
        insert(Conversion)
        .values(asin=asin, status="preparing", attempts=0)
        .on_conflict_do_nothing(index_elements=["asin"])
    )
    conv = (
        await db.execute(select(Conversion).where(Conversion.asin == asin).with_for_update())
    ).scalar_one()

    if conv.status == "failed":
        conv.status = "preparing"
        conv.attempts = 0
        conv.error = None
        conv.started_at = None
        conv.finished_at = None
        await db.commit()
        return _book_state(book, conv)

    if conv.status == "ready":
        now = clock()
        key = conv.object_key or converter.object_key(asin)
        url = await asyncio.to_thread(
            request.app.state.storage.presign, key, PRESIGN_EXPIRES_S
        )
        conv.last_played_at = now
        await db.commit()
        return {
            "status": "ready",
            "url": url,
            "url_expires_at": (now + timedelta(seconds=PRESIGN_EXPIRES_S)).isoformat(),
        }

    await db.commit()
    return _book_state(book, conv)


class PositionBody(BaseModel):
    position_ms: int = Field(ge=0)
    client_updated_at: AwareDatetime


@router.get("/books/{asin}/position")
async def get_position(
    asin: str,
    request: Request,
    db: AsyncSession = Depends(get_session),
    _device: Device = Depends(pairing.require_device),
):
    if await db.get(Book, asin) is None:
        return _not_found()
    position_ms, updated_at = await positions.resolve(
        db, request.app.state.settings, request.app.state.audible_factory, asin
    )
    return {"position_ms": position_ms, "updated_at": updated_at.isoformat()}


@router.put("/books/{asin}/position", status_code=204)
async def put_position(
    asin: str,
    body: PositionBody,
    request: Request,
    db: AsyncSession = Depends(get_session),
    _device: Device = Depends(pairing.require_device),
):
    if await db.get(Book, asin) is None:
        return _not_found()
    await positions.save(
        db,
        request.app.state.settings,
        request.app.state.audible_factory,
        asin,
        body.position_ms,
        body.client_updated_at,
    )
    return Response(status_code=204)
