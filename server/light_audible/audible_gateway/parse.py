"""Pure parsers for Audible API responses (no network, no `audible` import)."""

from datetime import UTC, date, datetime
from typing import Any

from light_audible.audible_gateway import (
    Chapter,
    License,
    LicenseDenied,
    LibraryItem,
    RemotePosition,
)


def _parse_date(value: str | None) -> date | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).date()
    except ValueError:
        return None


def parse_library(page: dict[str, Any]) -> list[LibraryItem]:
    items = []
    for raw in page.get("items") or []:
        items.append(
            LibraryItem(
                asin=raw["asin"],
                title=raw.get("title") or "",
                authors=[a["name"] for a in raw.get("authors") or [] if a.get("name")],
                narrators=[n["name"] for n in raw.get("narrators") or [] if n.get("name")],
                runtime_ms=int(raw.get("runtime_length_min") or 0) * 60_000,
                purchase_date=_parse_date(raw.get("purchase_date")),
            )
        )
    return items


def parse_chapters(metadata: dict[str, Any]) -> list[Chapter]:
    info = (metadata.get("content_metadata") or {}).get("chapter_info") or {}
    return [
        Chapter(
            idx=i,
            title=c.get("title") or "",
            start_ms=int(c.get("start_offset_ms") or 0),
            length_ms=int(c.get("length_ms") or 0),
        )
        for i, c in enumerate(info.get("chapters") or [])
    ]


def parse_license(license_response: dict[str, Any], voucher: dict[str, Any]) -> License:
    lic = license_response.get("content_license") or {}
    if lic.get("status_code") == "Denied":
        raise LicenseDenied(lic.get("message") or "License denied")
    meta = lic.get("content_metadata") or {}
    url = (meta.get("content_url") or {}).get("offline_url")
    if not url:
        raise LicenseDenied("No download URL in license response")
    acr = (meta.get("content_reference") or {}).get("acr") or ""
    return License(url=url, key=voucher["key"], iv=voucher["iv"], acr=acr)


def _utc(value: str) -> datetime:
    """Parse an Audible timestamp; the live API omits the zone, which is UTC."""
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def parse_lastposition(resp: dict[str, Any], asin: str) -> RemotePosition | None:
    for annot in resp.get("asin_last_position_heard_annots") or []:
        if annot.get("asin") != asin:
            continue
        pos = annot.get("last_position_heard") or {}
        if pos.get("status") != "Exists":
            return None
        return RemotePosition(
            position_ms=int(pos["position_ms"]),
            updated_at=_utc(pos["last_updated"]),
        )
    return None
