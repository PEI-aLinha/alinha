"""FastAPI app for the AI Service. Run with: uvicorn ai_service.main:app"""

from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request

from ai_service.config import get_settings
from ai_service.errors import register_error_handlers
from ai_service.providers import build_provider
from ai_service.providers.base import LlmProvider


def create_app(provider: LlmProvider | None = None) -> FastAPI:
    """Builds the app. Tests pass a provider; in production it comes from Settings.

    The provider is built at startup, so a bad config or an unreachable OpenBao
    stops the process from starting instead of failing on the first request.
    """

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
        app.state.provider = provider or build_provider(get_settings())
        try:
            yield
        finally:
            await app.state.provider.aclose()

    app = FastAPI(title="aLinha AI Service", version="1.0.0", lifespan=lifespan)
    register_error_handlers(app)

    @app.get("/health")
    async def health() -> dict[str, str]:
        # Liveness only: never calls the LLM or OpenBao.
        return {"status": "ok"}

    return app


def get_provider(request: Request) -> LlmProvider:
    """Dependency for routes that need the LLM (e.g. /v1/drafts)."""
    return request.app.state.provider


app = create_app()
