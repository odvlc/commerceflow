# Local identity and authorization

<!--
Maintenance: Documents Keycloak identity, role/scope and resource ownership boundaries across
services.
-->

OpenID Connect (OIDC) adds sign-in and identity information to OAuth 2.0. In CommerceFlow, React
uses OIDC to sign the user in through Keycloak and obtain an access token for the APIs.
Authorization Code with PKCE (Proof Key for Code Exchange) protects the browser login exchange:
the app creates a one-time verifier and sends its derived challenge with the login request. When
the app exchanges the returned authorization code for tokens, it proves possession of the verifier.
This lets a public browser client complete login without storing a client secret.

Keycloak owns passwords, login sessions and token signing. The local realm fixture is
`infra/keycloak/commerceflow-realm.json`. Its public development users are alice/bob (CUSTOMER),
admin (ADMIN), and support (SUPPORT); passwords are `local-<username>-only`. These credentials
belong to synthetic local fixtures and must not be used for deployment. Service client secrets are
supplied through `SERVICE_CLIENT_SECRET`; Compose's documented default is development-only.

`commerceflow-web` is a public Authorization Code client requiring PKCE S256; implicit and password
grants are disabled. Redirects/web origins are limited to localhost ports 5173 and 3000. Protocol
mappers explicitly include audience `commerceflow-api`, realm roles, username and UUID subject. The
basic subject scope is defined explicitly because realm imports do not synthesize missing default
scopes.

All backends and the gateway validate issuer, expiry, audience and UUID subject. ADMIN protects
catalog, stock and payment mutations; SUPPORT can read administrative order monitoring.
Customer/cart operations require CUSTOMER or ADMIN and order ownership always derives from JWT
subject, never a caller-provided customer ID. A different customer's order returns 404, including
when bypassing the gateway. Internal endpoints require the `commerceflow.internal` service-account
scope and are not gateway routes.

The gateway relays the bearer token to backends; each backend independently validates it. Configure
`SECURITY_ISSUER` to the browser-visible Keycloak realm URL and `SECURITY_JWK_SET_URI` to a
reachable internal certs URL when container DNS differs. Tokens are never signed by application
code. For cart requests, the gateway forwards the browser's access token, and Cart Service validates
it before the authenticated subject is used as the customer UUID passed to `CartService`. This
keeps cart data scoped to the signed-in user. Test-only JWKS signing fixtures supplement real
Keycloak PKCE tests.

Development Compose infrastructure binds host ports to loopback and uses plaintext within its local
network. It is not a hardened remote deployment. Production identity secrets, TLS, network
restrictions and Kafka ACLs require deployment-specific configuration. Do not expose the local
fixture publicly.

## Copilot and deployment boundaries

See [AI security](ai-security.md). Caller context controls ownership/mode and fixed downstream
targets; model arguments cannot supply authority or arbitrary endpoints. Confirmation requires an
authenticated typed command. Agreement in model or user text cannot authorize execution. Generation
outages reject new inference work; owned committed Cart intents can reconcile without generation.
Only current public order state/cause/version/observation reaches the model; full Order history
remains in its authorized API.

The local kind setup installs Calico and tests which connections its policies allow and deny.
AI/model/provisioning pods are excluded from the base namespace allowance and get explicit
dependency policies. Native inference uses a private nonroot proxy limited to
tags/version/chat/embed, no published host port and an exact /32 destination rule. Model pull access
is separate, explicit and temporary. CI artifacts use `collect-safe-evidence.py`; raw browser
traces, HTTP dumps, logs and rendered Kubernetes Secrets are excluded.
