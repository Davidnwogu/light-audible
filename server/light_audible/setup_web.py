import logging
import secrets
from dataclasses import asdict
from pathlib import Path

from fastapi import APIRouter, Depends, Form, HTTPException, Request
from fastapi.responses import HTMLResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from fastapi.templating import Jinja2Templates
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from light_audible import account, pairing
from light_audible.audible_gateway import PendingLogin
from light_audible.db import get_session
from light_audible.models import Book

templates = Jinja2Templates(directory=Path(__file__).parent / "templates")
_basic = HTTPBasic(auto_error=False)
log = logging.getLogger(__name__)


def require_setup_password(
    request: Request, credentials: HTTPBasicCredentials | None = Depends(_basic)
) -> None:
    expected = request.app.state.settings.setup_password
    given = credentials.password if credentials else ""
    if credentials is None or not secrets.compare_digest(given.encode(), expected.encode()):
        raise HTTPException(401, "Unauthorized", headers={"WWW-Authenticate": 'Basic realm="setup"'})


router = APIRouter(prefix="/setup", dependencies=[Depends(require_setup_password)])


async def _render(
    request: Request,
    db: AsyncSession,
    *,
    login_url: str | None = None,
    error: str | None = None,
    note: str | None = None,
    pairing_code: str | None = None,
) -> HTMLResponse:
    acct = await account.get_account(db, request.app.state.settings)
    count = (await db.execute(select(func.count()).select_from(Book))).scalar_one()
    if login_url is None and acct.pending_login:
        login_url = acct.pending_login["url"]
    return templates.TemplateResponse(
        request,
        "setup.html",
        {
            "status": acct.status,
            "library_count": count,
            "position_sync_enabled": acct.position_sync_enabled,
            "login_url": login_url,
            "error": error,
            "note": note,
            "pairing_code": pairing_code,
        },
    )


@router.get("", response_class=HTMLResponse)
async def status_page(request: Request, db: AsyncSession = Depends(get_session)):
    return await _render(request, db)


@router.post("/login/start", response_class=HTMLResponse)
async def login_start(request: Request, db: AsyncSession = Depends(get_session)):
    settings = request.app.state.settings
    acct = await account.get_account(db, settings)
    pending = request.app.state.login.start_login(acct.locale)
    acct.pending_login = asdict(pending)
    await db.commit()
    return await _render(request, db, login_url=pending.url)


@router.post("/login/finish", response_class=HTMLResponse)
async def login_finish(
    request: Request, redirect_url: str = Form(...), db: AsyncSession = Depends(get_session)
):
    settings = request.app.state.settings
    acct = await account.get_account(db, settings)
    if not acct.pending_login:
        return await _render(request, db, error="Sign-in was not started. Start it first.")
    pending = PendingLogin(**acct.pending_login)
    try:
        auth = await request.app.state.login.finish_login(pending, redirect_url.strip())
    except ValueError as e:
        return await _render(request, db, error=str(e))
    except Exception:
        return await _render(request, db, error="Audible sign-in failed. Start again and retry.")
    await account.save_auth(db, settings, auth)
    try:
        await request.app.state.after_login(db, settings)
    except Exception as e:
        # Sign-in already succeeded; log only the exception type (no auth data / redirect URL).
        log.warning("post-login library sync failed: %s", type(e).__name__)
        await db.rollback()
        return await _render(
            request,
            db,
            note="Signed in. Your library couldn't be loaded yet — refresh it from the phone.",
        )
    return await _render(request, db)


@router.post("/pairing-code", response_class=HTMLResponse)
async def pairing_code(request: Request, db: AsyncSession = Depends(get_session)):
    clock = pairing.get_clock(request)
    code = await pairing.create_code(db, now=clock())
    return await _render(request, db, pairing_code=code)
