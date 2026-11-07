from collections.abc import Callable
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from typing import Protocol


@dataclass(frozen=True)
class LibraryItem:
    asin: str
    title: str
    authors: list[str]
    narrators: list[str]
    runtime_ms: int
    purchase_date: date | None


@dataclass(frozen=True)
class Chapter:
    idx: int
    title: str
    start_ms: int
    length_ms: int


@dataclass(frozen=True)
class License:
    url: str
    key: str
    iv: str
    acr: str


@dataclass(frozen=True)
class RemotePosition:
    position_ms: int
    updated_at: datetime


@dataclass(frozen=True)
class PendingLogin:
    url: str
    code_verifier: str
    serial: str
    locale: str


class AuthExpired(Exception):
    pass


class LicenseDenied(Exception):
    pass


class TransientError(Exception):
    """Network error or 5xx from Audible."""


class AudibleSession(Protocol):
    async def fetch_library(self) -> list[LibraryItem]: ...
    async def fetch_chapters(self, asin: str) -> list[Chapter]: ...
    async def request_license(self, asin: str) -> License: ...
    async def download(self, url: str, dest: Path) -> None: ...
    async def get_position(self, asin: str) -> RemotePosition | None: ...
    async def set_position(self, asin: str, acr: str, position_ms: int) -> None: ...
    def export_auth(self) -> dict: ...


SessionFactory = Callable[[dict], AudibleSession]
