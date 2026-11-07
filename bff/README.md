# BFF — Backend for Frontend

**Spring Cloud Gateway** (technical report, 4.2).

- OIDC + MFA login with Keycloak; session stored in Redis.
- Tokens never reach the browser (HttpOnly cookie); token relay to the aLinha Application.
- Rate limiting backed by Redis.
