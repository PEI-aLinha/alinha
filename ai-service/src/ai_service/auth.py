"""Service-to-service authentication: only the core's AI Gateway may call /v1 (RNF11).

The gateway sends the shared token from OpenBao as "Authorization: Bearer <token>".
/health stays open so the container healthcheck and the orchestrator can probe it.
"""

import hmac

from fastapi import Request


class Unauthorized(Exception):
    """Missing or wrong service token (maps to HTTP 401)."""


def require_service_token(request: Request) -> None:
    expected: str = request.app.state.service_token.get_secret_value()
    scheme, _, presented = request.headers.get("authorization", "").partition(" ")
    # compare_digest: the comparison time does not reveal how much of the token matched.
    if scheme.lower() != "bearer" or not hmac.compare_digest(
        presented.encode(), expected.encode()
    ):
        raise Unauthorized()
