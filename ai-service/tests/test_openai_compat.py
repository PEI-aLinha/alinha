import asyncio
import json

import httpx
import pytest
import respx
from pydantic import SecretStr

from ai_service.config import Settings
from ai_service.providers import build_provider
from ai_service.providers.base import ProviderError, ProviderTimeout, ProviderTruncated
from ai_service.providers.openai_compat import OpenAiCompatProvider

BASE_URL = "https://llm.example.eu/v1"
ENDPOINT = f"{BASE_URL}/chat/completions"


def completion(text="Rascunho.", model="eu-model-2025", finish_reason="stop"):
    return {
        "model": model,
        "choices": [
            {"message": {"role": "assistant", "content": text}, "finish_reason": finish_reason}
        ],
    }


@pytest.fixture
async def provider():
    p = OpenAiCompatProvider(
        base_url=BASE_URL, model="eu-model", api_key=SecretStr("sk-test"), timeout_seconds=1.0
    )
    yield p
    await p.aclose()


@pytest.fixture
def mock():
    with respx.mock(assert_all_called=False) as m:
        yield m


async def test_sends_chat_completion_and_returns_text(provider, mock):
    route = mock.post(ENDPOINT).respond(200, json=completion())

    result = await provider.complete(system="SYS", user='{"gaps": []}')

    assert result.text == "Rascunho."
    assert result.model == "eu-model-2025"  # the model that answered, as provenance
    request = route.calls.last.request
    assert request.headers["Authorization"] == "Bearer sk-test"
    body = json.loads(request.content)
    assert body["model"] == "eu-model"
    assert body["messages"] == [
        {"role": "system", "content": "SYS"},
        {"role": "user", "content": '{"gaps": []}'},
    ]


async def test_trailing_slash_in_base_url(mock):
    route = mock.post(ENDPOINT).respond(200, json=completion())
    p = OpenAiCompatProvider(
        base_url=BASE_URL + "/", model="m", api_key=SecretStr("k"), timeout_seconds=1.0
    )
    try:
        await p.complete(system="s", user="u")
    finally:
        await p.aclose()
    assert route.called


async def test_falls_back_to_configured_model(provider, mock):
    body = completion()
    del body["model"]
    mock.post(ENDPOINT).respond(200, json=body)

    assert (await provider.complete(system="s", user="u")).model == "eu-model"


@pytest.mark.parametrize("bad_model", [123, {"name": "x"}, ["m"], "", "   ", None, True])
async def test_non_string_model_falls_back_to_configured_model(provider, mock, bad_model):
    mock.post(ENDPOINT).respond(200, json=completion(model=bad_model))

    result = await provider.complete(system="s", user="u")

    assert result.model == "eu-model"


@pytest.mark.parametrize("status", [400, 401, 429, 500, 503])
async def test_non_200_is_provider_error_without_body(provider, mock, status):
    mock.post(ENDPOINT).respond(status, json={"error": "context: MFA em falta"})

    with pytest.raises(ProviderError) as exc:
        await provider.complete(system="s", user="u")

    assert not isinstance(exc.value, ProviderTimeout)
    assert str(status) in str(exc.value) and "MFA" not in str(exc.value)


@pytest.mark.parametrize("response", [
    httpx.Response(200, text="not json"),
    httpx.Response(200, json={}),
    httpx.Response(200, json={"choices": []}),
    httpx.Response(200, json={"choices": [{"message": {}}]}),
    httpx.Response(200, json={"choices": [{"message": {"content": None}}]}),
    httpx.Response(200, json={"choices": [{"message": {"content": "   "}}]}),
    httpx.Response(200, json=["not", "an", "object"]),
])
async def test_unusable_response_is_provider_error(provider, mock, response):
    mock.post(ENDPOINT).mock(return_value=response)

    with pytest.raises(ProviderError):
        await provider.complete(system="s", user="u")


@pytest.mark.parametrize("error", [httpx.ConnectTimeout("t"), httpx.ReadTimeout("t")])
async def test_httpx_timeout_is_provider_timeout(provider, mock, error):
    mock.post(ENDPOINT).mock(side_effect=error)

    with pytest.raises(ProviderTimeout):
        await provider.complete(system="s", user="u")


async def test_overall_deadline_is_provider_timeout(mock):
    async def slow(request):
        await asyncio.sleep(5)
        return httpx.Response(200, json=completion())

    mock.post(ENDPOINT).mock(side_effect=slow)
    p = OpenAiCompatProvider(
        base_url=BASE_URL, model="m", api_key=SecretStr("k"), timeout_seconds=0.05
    )
    try:
        with pytest.raises(ProviderTimeout):
            await p.complete(system="s", user="u")
    finally:
        await p.aclose()


async def test_connection_failure_is_provider_error(provider, mock):
    mock.post(ENDPOINT).mock(side_effect=httpx.ConnectError("refused"))

    with pytest.raises(ProviderError) as exc:
        await provider.complete(system="s", user="u")

    assert not isinstance(exc.value, ProviderTimeout)


async def test_aclose_closes_http_client():
    client = httpx.AsyncClient()
    p = OpenAiCompatProvider(
        base_url=BASE_URL, model="m", api_key=SecretStr("k"), timeout_seconds=1.0, client=client
    )
    await p.aclose()
    assert client.is_closed


async def test_build_provider_reads_key_from_openbao(monkeypatch, mock):
    monkeypatch.setattr(
        "ai_service.secrets.read_llm_api_key", lambda settings: SecretStr("from-bao")
    )
    route = mock.post(ENDPOINT).respond(200, json=completion())
    settings = Settings(
        llm_provider="openai_compat",
        llm_base_url=BASE_URL,
        llm_model="eu-model",
        bao_token=SecretStr("t"),
    )

    p = build_provider(settings)
    try:
        await p.complete(system="s", user="u")
    finally:
        await p.aclose()

    assert isinstance(p, OpenAiCompatProvider)
    assert route.calls.last.request.headers["Authorization"] == "Bearer from-bao"


async def test_sends_max_tokens(provider, mock):
    route = mock.post(ENDPOINT).respond(200, json=completion())

    await provider.complete(system="s", user="u")

    assert json.loads(route.calls.last.request.content)["max_tokens"] == 4096


async def test_max_tokens_is_configurable(mock):
    route = mock.post(ENDPOINT).respond(200, json=completion())
    p = OpenAiCompatProvider(
        base_url=BASE_URL, model="m", api_key=SecretStr("k"), timeout_seconds=1.0,
        max_output_tokens=500,
    )
    try:
        await p.complete(system="s", user="u")
    finally:
        await p.aclose()

    assert json.loads(route.calls.last.request.content)["max_tokens"] == 500


async def test_truncated_completion_is_provider_truncated(provider, mock):
    mock.post(ENDPOINT).respond(200, json=completion(text="Roteiro cortad", finish_reason="length"))

    with pytest.raises(ProviderTruncated) as exc:
        await provider.complete(system="s", user="u")

    assert "cortad" not in str(exc.value)


@pytest.mark.parametrize("finish_reason", ["stop", None])
async def test_normal_finish_reasons_pass(provider, mock, finish_reason):
    mock.post(ENDPOINT).respond(200, json=completion(finish_reason=finish_reason))

    assert (await provider.complete(system="s", user="u")).text == "Rascunho."


async def test_text_beyond_the_output_cap_is_provider_error(mock):
    # A provider that ignores max_tokens: 10 tokens allow at most 80 characters.
    mock.post(ENDPOINT).respond(200, json=completion(text="x" * 81))
    p = OpenAiCompatProvider(
        base_url=BASE_URL, model="m", api_key=SecretStr("k"), timeout_seconds=1.0,
        max_output_tokens=10,
    )
    try:
        with pytest.raises(ProviderError, match="output limit") as exc:
            await p.complete(system="s", user="u")
    finally:
        await p.aclose()

    assert not isinstance(exc.value, ProviderTruncated)


async def test_build_provider_passes_the_output_limit(monkeypatch, mock):
    monkeypatch.setattr(
        "ai_service.secrets.read_llm_api_key", lambda settings: SecretStr("k")
    )
    route = mock.post(ENDPOINT).respond(200, json=completion())
    settings = Settings(
        llm_provider="openai_compat", llm_base_url=BASE_URL, llm_model="m",
        bao_token=SecretStr("t"), llm_max_output_tokens=1234,
    )

    p = build_provider(settings)
    try:
        await p.complete(system="s", user="u")
    finally:
        await p.aclose()

    assert json.loads(route.calls.last.request.content)["max_tokens"] == 1234
