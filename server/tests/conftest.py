import httpx
import pytest
from cryptography.fernet import Fernet

from light_audible.config import Settings
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
