import asyncio
import os

from alembic import context
from sqlalchemy.engine import Connection

from light_audible.db import make_engine
from light_audible.models import Base

config = context.config
target_metadata = Base.metadata


def _url() -> str:
    return os.environ["DATABASE_URL"]


def _do_run(connection: Connection) -> None:
    context.configure(connection=connection, target_metadata=target_metadata)
    with context.begin_transaction():
        context.run_migrations()


async def _run_online() -> None:
    engine = make_engine(_url())
    async with engine.connect() as connection:
        await connection.run_sync(_do_run)
    await engine.dispose()


if context.is_offline_mode():
    context.configure(url=_url(), target_metadata=target_metadata, literal_binds=True)
    with context.begin_transaction():
        context.run_migrations()
else:
    asyncio.run(_run_online())
