import json
from datetime import UTC, date, datetime
from pathlib import Path

import pytest

from light_audible.audible_gateway import License, LicenseDenied
from light_audible.audible_gateway import parse
from light_audible.audible_gateway.login import extract_auth_code

FIX = Path(__file__).parent / "fixtures" / "audible"


def load(name: str) -> dict:
    return json.loads((FIX / name).read_text())


def test_parse_library():
    items = parse.parse_library(load("library_page.json"))
    assert [i.asin for i in items] == ["B0ABC12345", "B0DEF67890"]
    hobbit, dune = items
    assert hobbit.title == "The Hobbit"
    assert hobbit.authors == ["J.R.R. Tolkien"]
    assert hobbit.narrators == ["Rob Inglis"]
    assert hobbit.runtime_ms == 660 * 60_000
    assert hobbit.purchase_date == date(2024, 3, 5)
    assert dune.narrators == ["Scott Brick", "Orlagh Cassidy"]
    assert dune.purchase_date == date(2023, 11, 20)


def test_parse_library_sparse_defaults():
    (item,) = parse.parse_library(load("library_sparse.json"))
    assert item.authors == []
    assert item.narrators == []
    assert item.runtime_ms == 0
    assert item.purchase_date is None


def test_parse_chapters():
    chapters = parse.parse_chapters(load("metadata_chapters.json"))
    assert [c.idx for c in chapters] == [0, 1, 2]
    assert [c.title for c in chapters] == ["Opening Credits", "Chapter 1", "Chapter 2"]
    assert [c.start_ms for c in chapters] == [0, 600000, 2400000]
    assert [c.length_ms for c in chapters] == [600000, 1800000, 1200000]


def test_parse_chapters_missing_returns_empty():
    assert parse.parse_chapters({}) == []
    assert parse.parse_chapters({"content_metadata": {}}) == []


def test_parse_license_granted():
    lic = parse.parse_license(load("license_granted.json"), {"key": "KEY", "iv": "IV"})
    assert lic == License(
        url="https://cds.audible.com/download?asin=B0ABC12345&codec=AAX_44_128",
        key="KEY",
        iv="IV",
        acr="CR!ABCDEF123456",
    )


def test_parse_license_denied_raises():
    with pytest.raises(LicenseDenied):
        parse.parse_license(load("license_denied.json"), {"key": "K", "iv": "I"})


def test_parse_license_without_url_raises():
    lr = load("license_granted.json")
    del lr["content_license"]["content_metadata"]["content_url"]
    with pytest.raises(LicenseDenied):
        parse.parse_license(lr, {"key": "K", "iv": "I"})


def test_parse_lastposition():
    pos = parse.parse_lastposition(load("lastpositions.json"), "B0ABC12345")
    assert pos is not None
    assert pos.position_ms == 1234567
    assert pos.updated_at == datetime(2025, 1, 2, 3, 4, 5, 678000, tzinfo=UTC)


def test_parse_lastposition_none_when_not_exists():
    assert parse.parse_lastposition(load("lastpositions_none.json"), "B0ABC12345") is None
    assert parse.parse_lastposition({"asin_last_position_heard_annots": []}, "B0ABC12345") is None
    assert parse.parse_lastposition({}, "B0ABC12345") is None


def test_extract_auth_code():
    url = "https://www.amazon.com/ap/maplanding?openid.mode=id_res&openid.oa2.authorization_code=abc123&x=1"
    assert extract_auth_code(url) == "abc123"


def test_extract_auth_code_missing_raises():
    with pytest.raises(ValueError, match="No authorization code in that URL"):
        extract_auth_code("https://www.amazon.com/ap/maplanding?openid.mode=id_res")
