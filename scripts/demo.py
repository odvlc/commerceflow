#!/usr/bin/env python3
"""Seed/verify synthetic loopback-only demonstration data using real Keycloak PKCE login."""
import base64
import hashlib
import html
import json
import os
import re
import sys
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener, urlopen

root = Path(__file__).resolve().parent.parent
api = os.environ.get("BASE_URL", "http://localhost:8080")
issuer = os.environ.get("KEYCLOAK_ISSUER", "http://localhost:8180/realms/commerceflow")
for address in [api, issuer]:
    if urlparse(address).hostname not in ["localhost", "127.0.0.1", "::1"]:
        raise ValueError("Demonstration seeding is restricted to loopback development services")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def login():
    browser = build_opener(NoRedirect)
    verifier = str(uuid.uuid4()) + str(uuid.uuid4())
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    redirect = "http://localhost:5173/"
    query = urlencode({"client_id": "commerceflow-web", "redirect_uri": redirect, "response_type": "code",
                       "scope": "openid", "state": str(uuid.uuid4()), "nonce": str(uuid.uuid4()),
                       "code_challenge": challenge, "code_challenge_method": "S256"})
    with browser.open(issuer + "/protocol/openid-connect/auth?" + query, timeout=10) as response:
        page = response.read().decode()
        cookies = "; ".join(value.split(";", 1)[0] for value in response.headers.get_all("Set-Cookie", []))
    action = html.unescape(re.search(r'<form[^>]+action="([^"]+)"', page).group(1))
    credentials = urlencode({"username": "admin", "password": "local-admin-only", "credentialId": ""}).encode()
    try:
        browser.open(Request(action, data=credentials, headers={"Cookie": cookies}), timeout=10)
        raise RuntimeError("Expected a successful Keycloak login redirect")
    except HTTPError as response:
        if response.code != 302:
            raise
        code = parse_qs(urlparse(response.headers["Location"]).query)["code"][0]
    form = urlencode({"grant_type": "authorization_code", "client_id": "commerceflow-web",
                      "redirect_uri": redirect, "code": code, "code_verifier": verifier}).encode()
    with urlopen(Request(issuer + "/protocol/openid-connect/token", data=form), timeout=10) as response:
        return json.load(response)["access_token"]


token = login()


def call(path, method="GET", body=None, headers=None):
    request_headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json", **(headers or {})}
    data = None if body is None else json.dumps(body).encode()
    with urlopen(Request(api + "/api/v1" + path, data=data, headers=request_headers, method=method),
                 timeout=15) as response:
        return json.load(response)


state = root / ".cache/verification/demo-products.json"
if len(sys.argv) < 2 or sys.argv[1] == "seed":
    items = call("/admin/products?size=100")["items"]
    existing = {p["sku"]: p for p in items}
    products = []
    for sku, name, category, price in [
        ("KEYBOARD-01", "Studio mechanical keyboard", "Workspace", "129.00"),
        ("HEADPHONES-01", "Everyday headphones", "Audio", "89.00"),
        ("MUG-01", "Morning ceramic mug", "Home", "24.00"),
        ("LAMP-01", "Arc desk lamp", "Workspace", "65.00"),
        ("BAG-01", "Daily canvas tote", "Everyday", "32.00"),
        ("NOTEBOOK-01", "Pocket notebook", "Workspace", "12.00"),
    ]:
        product = existing.get(sku)
        if product is None:
            product = call("/admin/products", "POST", {"sku": sku, "name": name, "category": category,
                                                       "description": "A considered essential for everyday life.",
                                                       "price": price,
                                                       "currency": "USD", "active": True},
                           {"Idempotency-Key": "commerceflow-demo-" + sku})
        try:
            stock = call("/admin/inventory/" + product["id"])
            if stock["reservedQuantity"]:
                raise RuntimeError("Finish pending demo orders before reseeding stock")
            quantity = {"availableQuantity": 25, "version": stock["version"]}
        except HTTPError as failure:
            if failure.code != 404:
                raise
            quantity = {"availableQuantity": 25}
        call("/admin/inventory/" + product["id"], "PUT", quantity)
        products.append({"id": product["id"], "sku": sku})
    state.parent.mkdir(parents=True, exist_ok=True)
    state.write_text(json.dumps(products, indent=2))
    # Explicitly exercise cache-aside reads without using cached prices for checkout.
    call("/products/" + products[0]["id"])
    call("/products/" + products[0]["id"])
    print("Seeded six synthetic products, stock and cached detail reads")
elif sys.argv[1] == "verify":
    products = {p["sku"]: p["id"] for p in json.loads(state.read_text())}
    for sku, expected in [("MUG-01", 24), ("NOTEBOOK-01", 25)]:
        stock = call("/admin/inventory/" + products[sku])
        assert stock["availableQuantity"] == expected, (sku, stock)
        assert stock["reservedQuantity"] == 0, (sku, stock)
    print("Verified approved stock commitment and exact declined-payment stock release")
else:
    raise ValueError("Use seed or verify")
