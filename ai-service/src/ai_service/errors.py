"""Maps errors to the contract's Error body ({code, message}; contracts/ai/ai-service.v1.yaml).

Messages are fixed strings: they never echo the request or the provider's answer,
which may contain (redacted but still sensitive) client context.
"""

import logging
from collections.abc import Awaitable, Callable

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from starlette.exceptions import HTTPException as StarletteHTTPException

from ai_service.auth import Unauthorized
from ai_service.providers.base import ProviderError, ProviderTimeout

log = logging.getLogger(__name__)


class UnknownTask(Exception):
    """The request names a task with no prompt template (maps to HTTP 422)."""


def error_response(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"code": code, "message": message})


async def _provider_timeout(request: Request, exc: Exception) -> JSONResponse:
    log.warning("LLM provider timed out on %s", request.url.path)
    return error_response(504, "provider_timeout", "The LLM provider did not answer in time.")


async def _provider_error(request: Request, exc: Exception) -> JSONResponse:
    log.warning("LLM provider failed on %s: %s", request.url.path, type(exc).__name__)
    return error_response(502, "provider_error", "The LLM provider failed.")


async def _unauthorized(request: Request, exc: Exception) -> JSONResponse:
    log.warning("Rejected request without a valid service token on %s", request.url.path)
    response = error_response(401, "unauthorized", "Missing or invalid service token.")
    response.headers["WWW-Authenticate"] = "Bearer"
    return response


async def _unknown_task(request: Request, exc: Exception) -> JSONResponse:
    from ai_service.prompts import TEMPLATES

    known = ", ".join(sorted(TEMPLATES))
    return error_response(422, "unknown_task", f"Unknown task. Known tasks: {known}.")


async def _invalid_request(request: Request, exc: Exception) -> JSONResponse:
    # Only field locations, never the input values (they may contain client context).
    fields = []
    if isinstance(exc, RequestValidationError):
        fields = sorted({".".join(str(p) for p in err.get("loc", ())) for err in exc.errors()})
    message = "Invalid request" + (f": {', '.join(fields)}" if fields else ".")
    return error_response(422, "invalid_request", message)


_HTTP_CODES = {
    400: ("invalid_request", "Invalid request body."),  # e.g. JSON FastAPI cannot parse
    404: ("not_found", "Not found."),
    405: ("method_not_allowed", "Method not allowed."),
}


async def _http_error(request: Request, exc: Exception) -> JSONResponse:
    # Routing errors (404, 405, ...) raised by Starlette; the default body is {"detail": ...}.
    status = exc.status_code if isinstance(exc, StarletteHTTPException) else 500
    code, message = _HTTP_CODES.get(status, ("http_error", "Request failed."))
    response = error_response(status, code, message)
    headers = getattr(exc, "headers", None) or {}
    response.headers.update(headers)  # e.g. Allow on 405
    return response


async def _catch_unexpected(
    request: Request, call_next: Callable[[Request], Awaitable[Response]]
) -> Response:
    # A middleware, not an Exception handler: Starlette re-raises after running an
    # Exception handler so the server can log it, and uvicorn would then log the full
    # traceback, whose message may carry client context. Here the error stops.
    try:
        return await call_next(request)
    except Exception as exc:
        # Only the type and path, never the message or traceback.
        log.error("Unexpected %s on %s", type(exc).__name__, request.url.path)
        return error_response(500, "internal_error", "Unexpected error.")


def register_error_handlers(app: FastAPI) -> None:
    # Starlette picks the handler for the most specific class in the MRO,
    # so ProviderTimeout (a ProviderError subclass) gets 504, not 502.
    # Anything not listed here becomes a 500 in _catch_unexpected.
    app.add_exception_handler(StarletteHTTPException, _http_error)
    app.middleware("http")(_catch_unexpected)
    app.add_exception_handler(ProviderTimeout, _provider_timeout)
    app.add_exception_handler(ProviderError, _provider_error)
    app.add_exception_handler(Unauthorized, _unauthorized)
    app.add_exception_handler(UnknownTask, _unknown_task)
    app.add_exception_handler(RequestValidationError, _invalid_request)
