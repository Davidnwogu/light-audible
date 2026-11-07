import httpx
import pytest

from light_audible.audible_gateway import AuthExpired, PendingLogin, TransientError
from light_audible.audible_gateway import login
from light_audible.audible_gateway import session as session_mod
from light_audible.audible_gateway.session import map_errors
from audible import exceptions as aex


def _resp(code: int) -> httpx.Response:
    return httpx.Response(code, request=httpx.Request("GET", "https://x"), json={})


@pytest.mark.parametrize(
    "exc, expected",
    [
        (aex.NotResponding(), TransientError),
        (aex.NetworkError(), TransientError),
        (aex.ServerError(_resp(503), {}), TransientError),
        (httpx.ConnectError("boom"), TransientError),
        (aex.Unauthorized(_resp(401), {}), AuthExpired),
        (aex.NoRefreshToken("no"), AuthExpired),
        (httpx.HTTPStatusError("x", request=_resp(400).request, response=_resp(400)), AuthExpired),
        (httpx.HTTPStatusError("x", request=_resp(502).request, response=_resp(502)), TransientError),
    ],
)
def test_map_errors(exc, expected):
    with pytest.raises(expected):
        with map_errors():
            raise exc


@pytest.mark.parametrize(
    "exc",
    [aex.NotFoundError(_resp(404), {}), aex.BadRequest(_resp(400), {}), aex.UnexpectedError(_resp(418), {})],
)
def test_map_errors_permanent_4xx_not_transient(exc):
    with pytest.raises(type(exc)) as ei:
        with map_errors():
            raise exc
    assert not isinstance(ei.value, TransientError)


async def test_download_read_timeout_is_transient(monkeypatch, tmp_path):
    def handler(request):
        raise httpx.ReadTimeout("stalled", request=request)

    real = httpx.AsyncClient
    seen = {}

    def client(**kw):
        seen.update(kw)
        return real(transport=httpx.MockTransport(handler), **kw)

    monkeypatch.setattr(session_mod.httpx, "AsyncClient", client)
    s = object.__new__(session_mod.RealAudibleSession)
    with pytest.raises(TransientError):
        await s.download("https://cdn.test/x", tmp_path / "x.m4b")
    assert seen["timeout"].read == 120.0 and seen["timeout"].connect == 30.0


def test_start_login_builds_url_and_verifier():
    p = login.start_login("us")
    assert p.url.startswith("https://www.amazon.com/ap/signin?")
    assert p.locale == "us" and p.serial and p.code_verifier


async def test_finish_login_registers_and_tags_locale(monkeypatch):
    seen = {}

    def fake_register(**kw):
        seen.update(kw)
        return {"adp_token": "t"}

    monkeypatch.setattr(login.audible.register, "register", fake_register)
    pending = PendingLogin(url="u", code_verifier="verif", serial="S", locale="us")
    out = await login.finish_login(pending, "https://a/b?openid.oa2.authorization_code=CODE")
    assert out == {"adp_token": "t", "locale_code": "us"}
    assert seen["authorization_code"] == "CODE"
    assert seen["code_verifier"] == b"verif" and seen["domain"] == "com" and seen["serial"] == "S"


async def test_download_error_does_not_leak_signed_url(monkeypatch, tmp_path):
    url = "https://cdn.test/x?Signature=SECRETSIG"
    real = httpx.AsyncClient
    monkeypatch.setattr(
        session_mod.httpx,
        "AsyncClient",
        lambda **kw: real(transport=httpx.MockTransport(lambda r: httpx.Response(403)), **kw),
    )
    s = object.__new__(session_mod.RealAudibleSession)
    with pytest.raises(TransientError) as ei:
        await s.download(url, tmp_path / "x.m4b")
    assert "SECRETSIG" not in str(ei.value) and "cdn.test" not in str(ei.value)
    assert "403" in str(ei.value)
