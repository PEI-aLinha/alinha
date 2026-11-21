"""FastAPI dependencies shared by the routes."""

from fastapi import Request

from ai_service.providers.base import LlmProvider


def get_provider(request: Request) -> LlmProvider:
    """The LLM provider built at startup (see main.create_app)."""
    return request.app.state.provider
