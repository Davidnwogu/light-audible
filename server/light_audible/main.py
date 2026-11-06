import asyncio
import contextlib
from collections.abc import AsyncIterator
from datetime import datetime, timezone
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from light_audible import account, api, converter, library, pairing, setup_web
from light_audible.audible_gateway import login as audible_login
from light_audible.audible_gateway.session import RealAudibleSession
from light_audible.config import Settings, get_settings
from light_audible.db import make_engine
from light_audible.storage import Storage


def _needs_sign_in_handler(_request: Request, _exc: account.NeedsSignIn) -> JSONResponse:
    return JSONResponse({"error": "needs_sign_in"}, status_code=409)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    engine = make_engine(settings.database_url)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        clock = getattr(app.state, "clock", None) or (lambda: datetime.now(timezone.utc))
        async with app.state.session_factory() as db:
            await converter.recover_stuck(db, clock())
        worker = converter.Worker(
            app.state.session_factory,
            app.state.settings,
            app.state.audible_factory,
            app.state.storage,
            clock=clock,
        )
        task = asyncio.create_task(worker.run_forever())
        try:
            yield
        finally:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
            await engine.dispose()

    app = FastAPI(title="Light Audible", lifespan=lifespan)
    app.state.settings = settings
    app.state.engine = engine
    app.state.session_factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

    app.state.login = audible_login
    app.state.audible_factory = RealAudibleSession
    app.state.storage = Storage(settings)

    async def after_login(db: AsyncSession, settings: Settings) -> None:
        await library.sync_library(db, settings, app.state.audible_factory)

    app.state.after_login = after_login
    app.include_router(setup_web.router)

    app.add_exception_handler(pairing.UnauthorizedError, pairing.unauthorized_handler)
    app.add_exception_handler(account.NeedsSignIn, _needs_sign_in_handler)
    app.include_router(api.router)

    return app
