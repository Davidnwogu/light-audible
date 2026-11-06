import pytest
from sqlalchemy import inspect, text
from sqlalchemy.exc import IntegrityError

EXPECTED = {"account", "books", "chapters", "conversions", "positions", "devices", "pairing_codes"}


async def test_tables_exist(db):
    conn = await db.connection()
    tables = await conn.run_sync(lambda c: set(inspect(c).get_table_names()) - {"alembic_version"})
    assert tables == EXPECTED


async def test_account_singleton(db):
    await db.execute(text("INSERT INTO account (id, locale) VALUES (1, 'us')"))
    with pytest.raises(IntegrityError):
        await db.execute(text("INSERT INTO account (id, locale) VALUES (2, 'us')"))
