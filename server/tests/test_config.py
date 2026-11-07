import pytest
from cryptography.fernet import Fernet
from pydantic import ValidationError

from light_audible.config import Settings

BASE = dict(
    database_url="postgresql+asyncpg://localhost/x",
    s3_endpoint="http://localhost:9000",
    s3_bucket="b",
    s3_access_key_id="k",
    s3_secret_access_key="s",
    public_base_url="http://testserver",
)


def make(**overrides) -> Settings:
    values = {
        **BASE,
        "auth_encryption_key": Fernet.generate_key().decode(),
        "setup_password": "long-enough-pw",
        **overrides,
    }
    return Settings(_env_file=None, **values)


def test_valid_settings_construct():
    assert make().setup_password == "long-enough-pw"


@pytest.mark.parametrize("password", ["", "short"])
def test_rejects_short_or_empty_setup_password(password):
    with pytest.raises(ValidationError):
        make(setup_password=password)


@pytest.mark.parametrize("key", ["", "not-a-fernet-key"])
def test_rejects_invalid_encryption_key(key):
    with pytest.raises(ValidationError):
        make(auth_encryption_key=key)


def test_validation_errors_do_not_echo_secret_values():
    with pytest.raises(ValidationError) as ei:
        make(setup_password="short", auth_encryption_key="bad-key-secret-value")
    text = str(ei.value)
    assert "short'" not in text and "bad-key-secret-value" not in text
