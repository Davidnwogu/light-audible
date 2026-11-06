import shutil
from pathlib import Path

from light_audible.audible_gateway import (
    Chapter,
    LibraryItem,
    License,
    LicenseDenied,
    PendingLogin,
    RemotePosition,
    TransientError,
)


class FakeAudibleSession:
    def __init__(
        self,
        library: list[LibraryItem] | None = None,
        chapters: dict[str, list[Chapter]] | None = None,
        licenses: dict[str, License] | None = None,
        positions: dict[str, RemotePosition] | None = None,
        fail_set_position: bool = False,
        download_source: Path | None = None,
        auth: dict | None = None,
    ):
        self.library = library or []
        self.chapters = chapters or {}
        self.licenses = licenses or {}
        self.positions = positions if positions is not None else {}
        self.fail_set_position = fail_set_position
        self.download_source = download_source
        self.auth = auth if auth is not None else {}
        self.set_position_calls: list[tuple[str, str, int]] = []
        self.fetch_chapters_calls: list[str] = []

    async def fetch_library(self) -> list[LibraryItem]:
        return list(self.library)

    async def fetch_chapters(self, asin: str) -> list[Chapter]:
        self.fetch_chapters_calls.append(asin)
        return list(self.chapters.get(asin, []))

    async def request_license(self, asin: str) -> License:
        try:
            return self.licenses[asin]
        except KeyError:
            raise LicenseDenied(asin) from None

    async def download(self, url: str, dest: Path) -> None:
        if self.download_source is None:
            raise TransientError("no download_source configured")
        shutil.copyfile(self.download_source, dest)

    async def get_position(self, asin: str) -> RemotePosition | None:
        return self.positions.get(asin)

    async def set_position(self, asin: str, acr: str, position_ms: int) -> None:
        if self.fail_set_position:
            raise TransientError("set_position failed")
        self.set_position_calls.append((asin, acr, position_ms))

    def export_auth(self) -> dict:
        return self.auth


class FakeLogin:
    """Mirrors login.start_login / finish_login without network."""

    def __init__(self, auth: dict | None = None):
        self.auth = auth if auth is not None else {"access_token": "fake"}

    def start_login(self, locale: str) -> PendingLogin:
        return PendingLogin(
            url=f"https://fake.audible.test/signin?locale={locale}",
            code_verifier="fake-verifier",
            serial="FAKESERIAL",
            locale=locale,
        )

    async def finish_login(self, pending: PendingLogin, redirect_url: str) -> dict:
        from light_audible.audible_gateway.login import extract_auth_code

        extract_auth_code(redirect_url)
        return {**self.auth, "locale_code": pending.locale}
