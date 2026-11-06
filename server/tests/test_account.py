import pytest
from cryptography.fernet import Fernet

from light_audible import account, crypto
from light_audible.audible_gateway import AuthExpired


class DoubleSession:
    """Minimal AudibleSession double: only export_auth matters here."""

    def __init__(self, auth: dict):
        self.auth = dict(auth)

    def export_auth(self) -> dict:
        return self.auth


def factory_for(holder: list):
    def factory(auth: dict):
        s = DoubleSession(auth)
        holder.append(s)
        return s

    return factory


def test_encrypt_roundtrip():
    key = Fernet.generate_key().decode()
    data = {"access_token": "abc", "n": 1}
    assert crypto.decrypt_auth(crypto.encrypt_auth(data, key), key) == data


def test_decrypt_wrong_key_raises():
    blob = crypto.encrypt_auth({"a": 1}, Fernet.generate_key().decode())
    with pytest.raises(crypto.BadKey):
        crypto.decrypt_auth(blob, Fernet.generate_key().decode())


def test_blob_is_not_plaintext():
    blob = crypto.encrypt_auth({"access_token": "supersecret"}, Fernet.generate_key().decode())
    assert b"access_token" not in blob
    assert b"supersecret" not in blob


async def test_session_requires_ok_status(db, settings):
    await account.get_account(db, settings)
    with pytest.raises(account.NeedsSignIn):
        async with account.audible_session(db, settings, factory_for([])):
            pass


async def test_session_persists_refreshed_auth(db, settings):
    await account.save_auth(db, settings, {"access_token": "old"})
    created: list[DoubleSession] = []
    async with account.audible_session(db, settings, factory_for(created)) as s:
        assert s.auth == {"access_token": "old"}
        s.auth["access_token"] = "new"
    acct = await account.get_account(db, settings)
    await db.refresh(acct)
    assert crypto.decrypt_auth(acct.encrypted_auth, settings.auth_encryption_key) == {
        "access_token": "new"
    }
    assert acct.status == "ok"


async def test_auth_expired_marks_needs_sign_in(db, settings):
    await account.save_auth(db, settings, {"access_token": "old"})
    with pytest.raises(account.NeedsSignIn):
        async with account.audible_session(db, settings, factory_for([])):
            raise AuthExpired()
    acct = await account.get_account(db, settings)
    await db.refresh(acct)
    assert acct.status == "needs_sign_in"


class MutatingSession:
    """Mimics audible.Authenticator.from_dict, which mutates the dict it is given."""

    def __init__(self, auth: dict):
        self.auth = auth
        auth["access_token"] = "refreshed"
        auth.pop("locale_code", None)

    def export_auth(self) -> dict:
        return {**self.auth, "locale_code": "us"}


async def test_session_factory_mutating_input_still_persists_refresh(db, settings):
    await account.save_auth(db, settings, {"access_token": "old", "locale_code": "us"})
    async with account.audible_session(db, settings, MutatingSession):
        pass
    acct = await account.get_account(db, settings)
    await db.refresh(acct)
    assert crypto.decrypt_auth(acct.encrypted_auth, settings.auth_encryption_key) == {
        "access_token": "refreshed",
        "locale_code": "us",
    }


async def test_session_no_change_when_factory_mutates_but_export_matches(db, settings):
    await account.save_auth(db, settings, {"access_token": "same", "locale_code": "us"})

    class Quiet:
        def __init__(self, auth: dict):
            auth.pop("locale_code")  # consume like from_dict

        def export_auth(self) -> dict:
            return {"access_token": "same", "locale_code": "us"}

    before = (await account.get_account(db, settings)).encrypted_auth
    async with account.audible_session(db, settings, Quiet):
        pass
    acct = await account.get_account(db, settings)
    await db.refresh(acct)
    assert acct.encrypted_auth == before
