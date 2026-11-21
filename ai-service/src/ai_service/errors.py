"""Maps errors to the contract's Error body ({code, message}; contracts/ai/ai-service.v1.yaml).

Messages are fixed strings: they never echo the request or the provider's answer,
which may contain (redacted but still sensitive) client context.
"""

import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from ai_service.providers.base import ProviderError, ProviderTimeout

log = logging.getLogger(__name__)


def error_response(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"code": code, "message": message})


async def _provider_timeout(request: Request, exc: Exception) -> JSONResponse:
    log.warning("LLM provider timed out on %s", request.url.path)
    return error_response(504, "provider_timeout", "The LLM provider did not answer in time.")


async def _provider_error(request: Request, exc: Exception) -> JSONResponse:
    log.warning("LLM provider failed on %s: %s", request.url.path, type(exc).__name__)
    return error_response(502, "provider_error", "The LLM provider failed.")


async def _invalid_request(request: Request, exc: Exception) -> JSONResponse:
    # Only field locations, never the input values (they may contain client context).
    fields = []
    if isinstance(exc, RequestValidationError):
        fields = sorted({".".join(str(p) for p in err.get("loc", ())) for err in exc.errors()})
    message = "Invalid request" + (f": {', '.join(fields)}" if fields else ".")
    return error_response(422, "invalid_request", message)


def register_error_handlers(app: FastAPI) -> None:
    # Starlette picks the handler for the most specific class in the MRO,
    # so ProviderTimeout (a ProviderError subclass) gets 504, not 502.
    app.add_exception_handler(ProviderTimeout, _provider_timeout)
    app.add_exception_handler(ProviderError, _provider_error)
    app.add_exception_handler(RequestValidationError, _invalid_request)
