from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from light_audible import api, pairing, setup_web
from light_audible.audible_gateway import login as audible_login
from light_audible.config import Settings, get_settings
from light_audible.db import make_engine


async def _no_after_login(db: AsyncSession, settings: Settings) -> None:
    return None


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    engine = make_engine(settings.database_url)

    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        yield
        await engine.dispose()

    app = FastAPI(title="Light Audible", lifespan=lifespan)
    app.state.settings = settings
    app.state.engine = engine
    app.state.session_factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

    app.state.login = audible_login
    app.state.after_login = _no_after_login
    app.include_router(setup_web.router)

    app.add_exception_handler(pairing.UnauthorizedError, pairing.unauthorized_handler)
    app.include_router(api.router)

    return app
