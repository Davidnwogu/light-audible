from collections.abc import AsyncIterator

from fastapi import Request
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine


def async_url(url: str) -> str:
    """Make a libpq-style URL usable by SQLAlchemy's asyncpg dialect.

    asyncpg rejects `sslmode` and `channel_binding`, so translate the former to
    `ssl` and drop the latter.
    """
    for prefix in ("postgresql://", "postgres://"):
        if url.startswith(prefix):
            url = "postgresql+asyncpg://" + url[len(prefix):]
            break
    base, sep, query = url.partition("?")
    if not sep:
        return url
    params = []
    for part in query.split("&"):
        key, eq, value = part.partition("=")
        if key == "channel_binding":
            continue
        params.append("ssl" + eq + value if key == "sslmode" else part)
    return base + ("?" + "&".join(params) if params else "")


def make_engine(url: str) -> AsyncEngine:
    return create_async_engine(async_url(url))


async def get_session(request: Request) -> AsyncIterator[AsyncSession]:
    """FastAPI dependency; expects app.state.session_factory to be set at startup."""
    factory: async_sessionmaker[AsyncSession] = request.app.state.session_factory
    async with factory() as session:
        yield session
