"""Adapter for any provider exposing an OpenAI-compatible Chat Completions API.

Many providers (including EU-hosted ones) and self-hosted servers (vLLM, Ollama)
speak this protocol, so the vendor is chosen only by LLM_BASE_URL and LLM_MODEL.
"""

import asyncio

import httpx
from pydantic import SecretStr

from ai_service.providers.base import (
    LlmResult,
    ProviderError,
    ProviderTimeout,
    ProviderTruncated,
)

# Safety net for providers that ignore max_tokens: a token is 3-4 characters, so this
# leaves a wide margin before treating the text as runaway output.
MAX_CHARS_PER_TOKEN = 8


class OpenAiCompatProvider:
    def __init__(
        self,
        *,
        base_url: str,
        model: str,
        api_key: SecretStr,
        timeout_seconds: float,
        max_output_tokens: int = 4096,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._model = model
        self._timeout_seconds = timeout_seconds
        self._max_output_tokens = max_output_tokens
        # Do not set follow_redirects=True: httpx does not follow redirects by default, so a
        # redirect can never take the API key and client context to a host outside EU_LLM_HOSTS.
        self._client = client or httpx.AsyncClient(
            base_url=base_url.rstrip("/"),
            headers={"Authorization": f"Bearer {api_key.get_secret_value()}"},
            timeout=timeout_seconds,
        )

    async def complete(self, *, system: str, user: str) -> LlmResult:
        payload = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": 0.2,
            "max_tokens": self._max_output_tokens,  # output goes to a paid LLM: always bounded
        }

        try:
            async with asyncio.timeout(self._timeout_seconds):
                response = await self._client.post("/chat/completions", json=payload)
        except (TimeoutError, httpx.TimeoutException) as e:
            raise ProviderTimeout(f"no answer within {self._timeout_seconds}s") from e
        except httpx.RequestError as e:
            raise ProviderError(f"request failed: {type(e).__name__}") from e

        if response.status_code != 200:
            raise ProviderError(f"provider returned HTTP {response.status_code}")

        try:
            body = response.json()
            choice = body["choices"][0]
            text = choice["message"]["content"]
            finish_reason = choice.get("finish_reason")
        except (ValueError, KeyError, IndexError, TypeError, AttributeError) as e:
            raise ProviderError("unexpected response shape") from e

        # A cut-off roadmap must not come back as a normal draft.
        if finish_reason == "length":
            raise ProviderTruncated(f"completion hit max_tokens={self._max_output_tokens}")
        if not isinstance(text, str) or not text.strip():
            raise ProviderError("empty completion")
        if len(text) > self._max_output_tokens * MAX_CHARS_PER_TOKEN:
            raise ProviderError("completion longer than the output limit allows")

        # Provenance (RNF14): use the provider's model name only if it is a real string;
        # anything else would break AiDraft validation and surface as a 500.
        model = body.get("model")
        if not isinstance(model, str) or not model.strip():
            model = self._model
        return LlmResult(text=text, model=model)

    async def aclose(self) -> None:
        await self._client.aclose()
