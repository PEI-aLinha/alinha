# BFF — Backend for Frontend

**Spring Cloud Gateway** (technical report, 4.2).

- OIDC + MFA login with Keycloak; session stored in Redis.
- Tokens never reach the browser (HttpOnly cookie); token relay to the aLinha Application.
- Rate limiting backed by Redis.

## What the frontend shell expects (issue #14)

- `GET /api/me` → `200` with `MeDto` (`frontend/packages/api-client/src/index.ts`): user, roles,
  `<resource>:<action>` permissions and organizations (`id` = tenant UUID, `slug`, `name`).
  `401` when there is no session — not a redirect, the shell handles it.
- Login at `/oauth2/authorization/keycloak` (`LOGIN_PATH` in `frontend/shell/src/session/login.ts`).
  After the login, send the browser to `/`; the shell returns to the page the person was opening.
- Forward the `X-Alinha-Tenant` header to the app unchanged. The shell sends it on every call made inside an
  organization, and never on the consultant's portfolio. It only *chooses* among the organizations the token already
  allows: the app checks it against the token (`TenantContext`, issue #8) and answers 403 for a consultant's
  unassigned organization or a client user's other organization.
- A `401` from any other `/api` call makes the shell re-check `/api/me`; it only goes to the login when `/api/me`
  also answers `401`.
