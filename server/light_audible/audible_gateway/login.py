import asyncio
from urllib.parse import parse_qs, urlparse

import audible
from audible.localization import Locale

from light_audible.audible_gateway import PendingLogin


def start_login(locale: str) -> PendingLogin:
    loc = Locale(locale)
    verifier = audible.login.create_code_verifier()
    url, serial = audible.login.build_oauth_url(
        country_code=loc.country_code,
        domain=loc.domain,
        market_place_id=loc.market_place_id,
        code_verifier=verifier,
    )
    return PendingLogin(url=url, code_verifier=verifier.decode(), serial=serial, locale=locale)


def extract_auth_code(redirect_url: str) -> str:
    codes = parse_qs(urlparse(redirect_url).query).get("openid.oa2.authorization_code")
    if not codes:
        raise ValueError("No authorization code in that URL")
    return codes[0]


async def finish_login(pending: PendingLogin, redirect_url: str) -> dict:
    code = extract_auth_code(redirect_url)
    loc = Locale(pending.locale)
    registered = await asyncio.to_thread(
        audible.register.register,
        authorization_code=code,
        code_verifier=pending.code_verifier.encode(),
        domain=loc.domain,
        serial=pending.serial,
    )
    return {**registered, "locale_code": pending.locale}
