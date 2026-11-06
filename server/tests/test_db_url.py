from light_audible.db import async_url


def test_railway_url_only_changes_scheme():
    assert async_url("postgresql://u:p@h:5432/db") == "postgresql+asyncpg://u:p@h:5432/db"


def test_neon_url_translates_sslmode_and_drops_channel_binding():
    url = "postgresql://u:p@ep-x.neon.tech/db?sslmode=require&channel_binding=require"
    assert async_url(url) == "postgresql+asyncpg://u:p@ep-x.neon.tech/db?ssl=require"


def test_other_query_params_preserved():
    url = "postgres://u:p@h/db?sslmode=require&application_name=la"
    assert async_url(url) == "postgresql+asyncpg://u:p@h/db?ssl=require&application_name=la"


def test_already_asyncpg_url_unchanged():
    url = "postgresql+asyncpg://localhost/light_audible_dev"
    assert async_url(url) == url
