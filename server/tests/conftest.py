import os
import subprocess
import sys
from pathlib import Path

import boto3
import httpx
import pytest
from botocore.config import Config
from cryptography.fernet import Fernet

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from light_audible.config import Settings
from light_audible.db import make_engine
from light_audible.main import create_app
from light_audible.storage import Storage


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
def app(settings: Settings):
    return create_app(settings)


@pytest.fixture
async def client(app):
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as c:
        yield c
    await app.state.engine.dispose()


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


@pytest.fixture
def storage(settings) -> Storage:
    client = boto3.client(
        "s3",
        endpoint_url=settings.s3_endpoint,
        aws_access_key_id=settings.s3_access_key_id,
        aws_secret_access_key=settings.s3_secret_access_key,
        region_name=settings.s3_region,
        config=Config(s3={"addressing_style": "path"}),
    )
    for page in client.get_paginator("list_objects_v2").paginate(Bucket=settings.s3_bucket):
        for obj in page.get("Contents", []):
            client.delete_object(Bucket=settings.s3_bucket, Key=obj["Key"])
    return Storage(settings)
