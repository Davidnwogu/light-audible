from functools import lru_cache
from typing import Literal

from cryptography.fernet import Fernet
from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore", hide_input_in_errors=True)

    database_url: str
    s3_endpoint: str
    s3_bucket: str
    s3_access_key_id: str
    s3_secret_access_key: str
    s3_region: str = "us-east-1"
    # "path" for MinIO; Railway buckets use "virtual" (virtual-hosted style).
    s3_addressing_style: Literal["path", "virtual"] = "path"
    auth_encryption_key: str
    setup_password: str
    audible_locale: str = "us"
    public_base_url: str
    bucket_soft_limit_bytes: int = 8_000_000_000

    @field_validator("setup_password")
    @classmethod
    def _setup_password_long_enough(cls, value: str) -> str:
        if len(value) < 8:
            raise ValueError("SETUP_PASSWORD must be at least 8 characters")
        return value

    @field_validator("auth_encryption_key")
    @classmethod
    def _encryption_key_is_fernet(cls, value: str) -> str:
        try:
            Fernet(value)
        except Exception as exc:
            raise ValueError("AUTH_ENCRYPTION_KEY must be a valid Fernet key") from exc
        return value


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
