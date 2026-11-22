"""FastAPI app for the AI Service. Run with: uvicorn ai_service.main:app"""

from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from pydantic import SecretStr

from ai_service.config import get_settings
from ai_service.drafts import router as drafts_router
from ai_service.errors import register_error_handlers
from ai_service.limits import BodySizeLimit
from ai_service.providers import build_provider
from ai_service.providers.base import LlmProvider
from ai_service.secrets import read_service_token


def create_app(
    provider: LlmProvider | None = None,
    service_token: SecretStr | None = None,
    max_request_bytes: int | None = None,
) -> FastAPI:
    """Builds the app. Tests pass these in; in production they come from Settings/OpenBao.

    Both are loaded at startup, so a bad config or an unreachable OpenBao
    stops the process from starting instead of failing on the first request.
    The token is read first so a failure there never leaves an unclosed provider.
    """

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
        app.state.service_token = service_token or read_service_token(get_settings())
        app.state.max_request_bytes = max_request_bytes or get_settings().max_request_bytes
        app.state.provider = provider or build_provider(get_settings())
        try:
            yield
        finally:
            await app.state.provider.aclose()

    app = FastAPI(title="aLinha AI Service", version="1.0.0", lifespan=lifespan)
    register_error_handlers(app)
    # Added last, so it is the outermost middleware: the body is bounded before anything reads it.
    app.add_middleware(BodySizeLimit)
    app.include_router(drafts_router)

    @app.get("/health")
    async def health() -> dict[str, str]:
        # Liveness only: never calls the LLM or OpenBao.
        return {"status": "ok"}

    return app


app = create_app()
