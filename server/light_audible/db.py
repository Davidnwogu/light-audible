from collections.abc import AsyncIterator

from fastapi import Request
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine


def async_url(url: str) -> str:
    for prefix in ("postgresql://", "postgres://"):
        if url.startswith(prefix):
            return "postgresql+asyncpg://" + url[len(prefix):]
    return url


def make_engine(url: str) -> AsyncEngine:
    return create_async_engine(async_url(url))


async def get_session(request: Request) -> AsyncIterator[AsyncSession]:
    """FastAPI dependency; expects app.state.session_factory to be set at startup."""
    factory: async_sessionmaker[AsyncSession] = request.app.state.session_factory
    async with factory() as session:
        yield session
