import json

from cryptography.fernet import Fernet, InvalidToken


class BadKey(Exception):
    """The blob could not be decrypted with the given key."""


def encrypt_auth(data: dict, key: str) -> bytes:
    return Fernet(key.encode()).encrypt(json.dumps(data).encode())


def decrypt_auth(blob: bytes, key: str) -> dict:
    try:
        return json.loads(Fernet(key.encode()).decrypt(blob))
    except InvalidToken as e:
        raise BadKey("auth blob could not be decrypted") from e
