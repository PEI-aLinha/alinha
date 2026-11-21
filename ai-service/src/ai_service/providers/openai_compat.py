"""Adapter for any provider exposing an OpenAI-compatible Chat Completions API.

Many providers (including EU-hosted ones) and self-hosted servers (vLLM, Ollama)
speak this protocol, so the vendor is chosen only by LLM_BASE_URL and LLM_MODEL.
"""

import asyncio

import httpx
from pydantic import SecretStr

from ai_service.providers.base import LlmResult, ProviderError, ProviderTimeout


class OpenAiCompatProvider:
    def __init__(
        self,
        *,
        base_url: str,
        model: str,
        api_key: SecretStr,
        timeout_seconds: float,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._model = model
        self._timeout_seconds = timeout_seconds
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
            text = body["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as e:
            raise ProviderError("unexpected response shape") from e

        if not isinstance(text, str) or not text.strip():
            raise ProviderError("empty completion")

        # Provenance (RNF14): use the provider's model name only if it is a real string;
        # anything else would break AiDraft validation and surface as a 500.
        model = body.get("model")
        if not isinstance(model, str) or not model.strip():
            model = self._model
        return LlmResult(text=text, model=model)

    async def aclose(self) -> None:
        await self._client.aclose()