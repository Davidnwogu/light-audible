import os
import subprocess
import sys
from pathlib import Path

import httpx
import pytest
from cryptography.fernet import Fernet

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from light_audible.config import Settings
from light_audible.db import make_engine
from light_audible.main import create_app


@pytest.fixture
def settings() -> Settings:
    return Settings(
        database_url="postgresql+asyncpg://localhost/light_audible_test",
        s3_endpoint="http://localhost:9000",
        s3_bucket="light-audible-test",
        s3_access_key_id="minioadmin",
        s3_secret_access_key="minioadmin",
        s3_region="us-east-1",
        auth_encryption_key=Fernet.generate_key().decode(),
        setup_password="test-pw",
        public_base_url="http://testserver",
    )


@pytest.fixture
async def client(settings: Settings):
    transport = httpx.ASGITransport(app=create_app(settings))
    async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as c:
        yield c


TEST_DATABASE_URL = "postgresql+asyncpg://localhost/light_audible_test"
TABLES = "account, books, chapters, conversions, positions, devices, pairing_codes"


@pytest.fixture(scope="session")
def migrated_database() -> str:
    server_dir = Path(__file__).resolve().parent.parent
    subprocess.run(
        [sys.executable, "-m", "alembic", "upgrade", "head"],
        cwd=server_dir,
        env={**os.environ, "DATABASE_URL": TEST_DATABASE_URL},
        check=True,
    )
    return TEST_DATABASE_URL


@pytest.fixture
async def db(migrated_database: str):
    engine = make_engine(migrated_database)
    async with engine.begin() as conn:
        await conn.execute(text(f"TRUNCATE {TABLES} RESTART IDENTITY CASCADE"))
    async with AsyncSession(engine, expire_on_commit=False) as session:
        yield session
    await engine.dispose()
