"""Loopback-only synthetic demo client. Tokens/passwords stay in memory and are never printed."""
import base64
import hashlib
import html
import json
import os
import re
import secrets
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener, urlopen


def local_url(value):
    parsed = urlparse(value)
    if parsed.scheme != "http" or parsed.hostname not in ("localhost", "127.0.0.1", "::1") or parsed.username:
        raise ValueError("Only loopback development HTTP endpoints are allowed")
    return value.rstrip("/")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


def login(user="alice", password=None):
    issuer = local_url(os.environ.get("KEYCLOAK_ISSUER", "http://localhost:8180/realms/commerceflow"))
    browser = build_opener(NoRedirect)
    verifier, state = secrets.token_urlsafe(48), secrets.token_urlsafe(24)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    redirect = "http://localhost:5173/"
    query = urlencode(dict(client_id="commerceflow-web", redirect_uri=redirect, response_type="code",
                           scope="openid", state=state, nonce=secrets.token_urlsafe(24),
                           code_challenge=challenge, code_challenge_method="S256"))
    with browser.open(issuer + "/protocol/openid-connect/auth?" + query, timeout=15) as response:
        page = response.read().decode()
        cookies = "; ".join(value.split(";", 1)[0] for value in response.headers.get_all("Set-Cookie", []))
    match = re.search(r'<form[^>]+action="([^"]+)"', page)
    if not match:
        raise RuntimeError("Expected the local Keycloak login form")
    action = local_url(html.unescape(match.group(1)))
    credentials = urlencode(
        dict(username=user, password=password or "local-" + user + "-only", credentialId="")).encode()
    try:
        browser.open(Request(action, data=credentials, headers={"Cookie": cookies}), timeout=15)
        raise RuntimeError("Expected Keycloak authorization redirect")
    except HTTPError as response:
        if response.code != 302:
            raise RuntimeError("Local Keycloak login failed") from None
        parameters = parse_qs(urlparse(response.headers["Location"]).query)
        if parameters.get("state") != [state] or "code" not in parameters:
            raise RuntimeError("Authorization state/code did not match")
        code = parameters["code"][0]
    form = urlencode(dict(grant_type="authorization_code", client_id="commerceflow-web", redirect_uri=redirect,
                          code=code, code_verifier=verifier)).encode()
    with urlopen(Request(issuer + "/protocol/openid-connect/token", data=form), timeout=15) as response:
        return json.load(response)["access_token"]
