"""Fake LLM provider for tests and local development. Never calls a real model."""

import asyncio

from ai_service.providers.base import LlmResult


class FakeProvider:
    """Returns a fixed answer, or raises a given error, so tests can drive every path."""

    def __init__(
        self,
        *,
        text: str = "Fake draft.",
        model: str = "fake-model",
        error: Exception | None = None,
        delay_seconds: float = 0.0,
    ) -> None:
        self._text = text
        self._model = model
        self._error = error
        self._delay_seconds = delay_seconds
        self.calls: list[tuple[str, str]] = []

    async def complete(self, *, system: str, user: str) -> LlmResult:
        self.calls.append((system, user))
        if self._delay_seconds:
            await asyncio.sleep(self._delay_seconds)
        if self._error is not None:
            raise self._error
        return LlmResult(text=self._text, model=self._model)

    async def aclose(self) -> None:
        pass  # nothing to close, but we need the method to satisfy the interface
