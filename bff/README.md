# BFF — Backend for Frontend

**Spring Cloud Gateway** (technical report, 4.2).

- OIDC + MFA login with Keycloak; session stored in Redis.
- Tokens never reach the browser (HttpOnly cookie); token relay to the aLinha Application.
- Rate limiting backed by Redis.

## Running it (dev)

```bash
docker compose -f deploy/docker-compose.dev.yml up -d   # Redis + Keycloak
BAO_TOKEN=dev-app-token ./gradlew :app:bootRun          # app on :8080
./gradlew :bff:bootRun                                  # BFF on :8081
```

Vite proxies `/api`, `/oauth2` and `/auth` to `:8081`. The Keycloak client is `bff`
(`dev-bff-secret`), callback `http://localhost:8081/login/oauth2/code/keycloak`.

### Behind Nginx

`forward-headers-strategy: framework` trusts `X-Forwarded-*`. Only safe when the BFF is reachable **only** through
Nginx: Nginx must overwrite `X-Forwarded-For` (`proxy_set_header X-Forwarded-For $remote_addr`;
`$proxy_add_x_forwarded_for` appends and is not enough) and the BFF port must not be published.
If `:8081` is reachable directly, anyone can spoof the header and dodge the IP-keyed limiter.

| Variable | Default | Purpose |
|---|---|---|
| `ALINHA_APP_URL` | `http://localhost:8080` | the application behind the gateway |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | session store and rate limiter |
| `BFF_CLIENT_ID` / `BFF_CLIENT_SECRET` | `bff` / `dev-bff-secret` | Keycloak client |
| `BFF_REDIRECT_URI` | `http://localhost:8081/login/oauth2/code/keycloak` | OIDC callback |
| `BFF_AFTER_LOGIN_URI` / `BFF_POST_LOGOUT_URI` | `http://localhost:5173/` | where the browser returns |
| `BFF_COOKIE_SECURE` | `false` | must be `true` over HTTPS |
| `BFF_RATE_LIMIT_ENABLED` / `BFF_RATE_LIMIT_REQUESTS` / `BFF_RATE_LIMIT_WINDOW` | `true` / `120` / `1m` | fixed-window limit per user, and per address on the login |
| `BFF_RATE_LIMIT_IP_API_REQUESTS` | `1200` | per-address limit on `/api/**` + `/auth/**` (one office NAT shares it) |

## Sessions and tokens

- Cookie `ALINHA_SESSION` (`HttpOnly`, `SameSite=Lax`, 8 h) holds only the session id; the tokens live in Redis.
- CSRF is Spring's double-submit cookie: the BFF sets `XSRF-TOKEN` (readable), the shell echoes it in
  `X-XSRF-TOKEN` on writes. The shell does this in `withCsrf` (`frontend/shell/src/http.ts`).
- `POST /auth/logout` (CSRF-protected) clears the session and ends the Keycloak SSO session (RP-initiated logout).
  The answer is a redirect to Keycloak, so the shell must submit a **full-page form POST** (with the
  `X-XSRF-TOKEN` value as a hidden `_csrf` field), not `fetch`: fetch would follow the redirect to Keycloak and fail
  CORS instead of navigating the browser.
- `POST /auth/refresh` forces a new access token from the stored refresh token (used after registration, #102).
- Rate limiting, all before authentication except the per-user limit; it fails open when Redis is unavailable:
  - `/oauth2/**` + `/login/**`: per address (`BFF_RATE_LIMIT_REQUESTS`).
  - `/api/**` + `/auth/**`: per address in a separate, higher bucket (`BFF_RATE_LIMIT_IP_API_REQUESTS`, since a
    whole office NAT shares it and a forged session cookie must still be counted), and per user (token `sub`,
    `BFF_RATE_LIMIT_REQUESTS`) once authenticated.

## What the frontend shell expects (issue #14)

- `GET /api/me` → `200` with `MeDto` (`frontend/packages/api-client/src/index.ts`): user, roles,
  `<resource>:<action>` permissions and organizations (`id` = tenant UUID, `slug`, `name`).
  `401` when there is no session — **not a 302 to the login** (Spring Security's default for browsers): `/api/**`
  must answer 401 to XHR/fetch, and no `/api/**` route ever answers 3xx. The shell does not follow redirects and treats one as "no session" too, but a 401
  is the contract. Organizations come from `/internal/me`, which the app derives from the access token; after
  `/auth/refresh` a changed organization list is visible without a new login.
- Login at `/oauth2/authorization/keycloak` (`LOGIN_PATH` in `frontend/shell/src/session/login.ts`).
  After the login, send the browser to `/`; the shell returns to the page the person was opening.
- Forward the `X-Alinha-Tenant` header to the app unchanged. The shell sends it on every call made inside an
  organization, and never on the consultant's portfolio. It only *chooses* among the organizations the token already
  allows: the app checks it against the token (`TenantContext`, issue #8) and answers 403 for a consultant's
  unassigned organization or a client user's other organization. Decision: `docs/adr/0002-tenant-selection-header.md`.
- A `401` from any other `/api` call makes the shell re-check `/api/me`; it only goes to the login when `/api/me`
  also answers `401`.
