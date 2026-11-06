import copy
from contextlib import contextmanager
from pathlib import Path

import audible
import httpx
from audible import exceptions as aex
from audible.aescipher import decrypt_voucher_from_licenserequest

from light_audible.audible_gateway import (
    AuthExpired,
    Chapter,
    LibraryItem,
    License,
    RemotePosition,
    TransientError,
    parse,
)

_TRANSIENT = (
    aex.NotResponding,
    aex.NetworkError,
    aex.ServerError,
    aex.RatelimitError,
    httpx.TransportError,
)
_DOWNLOAD_UA = "Audible/671 CFNetwork/1240.0.4 Darwin/20.6.0"
_DOWNLOAD_TIMEOUT = httpx.Timeout(30.0, read=120.0)
_AUTH = (aex.Unauthorized, aex.NoRefreshToken)


@contextmanager
def map_errors():
    """Translate audible/httpx failures into gateway exceptions."""
    try:
        yield
    except _AUTH as e:
        raise AuthExpired() from e
    except _TRANSIENT as e:
        raise TransientError(str(e) or type(e).__name__) from e
    except httpx.HTTPStatusError as e:
        # raised directly by the token-refresh call
        if e.response.status_code >= 500:
            raise TransientError(str(e)) from e
        raise AuthExpired() from e
    except aex.StatusError as e:
        # permanent 4xx (400, 404, ...) propagate unchanged
        if e.code >= 500:
            raise TransientError(str(e)) from e
        raise
    except aex.RequestError as e:
        # non-status request failures (wrapped httpx errors)
        raise TransientError(str(e) or type(e).__name__) from e


class RealAudibleSession:
    def __init__(self, auth: dict):
        # Authenticator.from_dict mutates its input
        self._auth = audible.Authenticator.from_dict(copy.deepcopy(auth))
        self._client = audible.AsyncClient(auth=self._auth)

    def export_auth(self) -> dict:
        return self._auth.to_dict()

    # verified against live account in Task 12
    async def fetch_library(self) -> list[LibraryItem]:
        items: list[LibraryItem] = []
        page = 1
        with map_errors():
            while True:
                resp = await self._client.get(
                    "1.0/library",
                    num_results=1000,
                    page=page,
                    response_groups="contributors,product_attrs,product_desc",
                )
                batch = parse.parse_library(resp)
                items.extend(batch)
                if not resp.get("items") or len(resp["items"]) < 1000:
                    return items
                page += 1

    # verified against live account in Task 12
    async def fetch_chapters(self, asin: str) -> list[Chapter]:
        with map_errors():
            resp = await self._client.get(
                f"1.0/content/{asin}/metadata",
                response_groups="chapter_info",
                chapter_titles_type="Flat",
                drm_type="Adrm",
            )
        return parse.parse_chapters(resp)

    # verified against live account in Task 12
    async def request_license(self, asin: str) -> License:
        body = {
            "supported_drm_types": ["Mpeg", "Adrm"],
            "quality": "High",
            "consumption_type": "Download",
            "response_groups": "last_position_heard,pdf_url,content_reference",
        }
        headers = {"Accept": "application/json", "Content-Type": "application/json"}
        with map_errors():
            resp = await self._client.post(
                f"1.0/content/{asin}/licenserequest", body=body, headers=headers
            )
        if (resp.get("content_license") or {}).get("status_code") == "Denied":
            return parse.parse_license(resp, {})  # raises LicenseDenied
        voucher = decrypt_voucher_from_licenserequest(self._auth, resp)
        return parse.parse_license(resp, voucher)

    # verified against live account in Task 12
    async def download(self, url: str, dest: Path) -> None:
        try:
            async with httpx.AsyncClient(follow_redirects=True, timeout=_DOWNLOAD_TIMEOUT) as http:
                async with http.stream("GET", url, headers={"User-Agent": _DOWNLOAD_UA}) as resp:
                    resp.raise_for_status()
                    with open(dest, "wb") as f:
                        async for chunk in resp.aiter_bytes(1024 * 1024):
                            f.write(chunk)
        except (httpx.TransportError, httpx.HTTPStatusError) as e:
            raise TransientError(str(e) or type(e).__name__) from e

    # verified against live account in Task 12
    async def get_position(self, asin: str) -> RemotePosition | None:
        with map_errors():
            resp = await self._client.get("1.0/annotations/lastpositions", asins=asin)
        return parse.parse_lastposition(resp, asin)

    # verified against live account in Task 12
    async def set_position(self, asin: str, acr: str, position_ms: int) -> None:
        with map_errors():
            await self._client.put(
                f"1.0/lastpositions/{asin}",
                body={"acr": acr, "asin": asin, "position_ms": position_ms},
            )
