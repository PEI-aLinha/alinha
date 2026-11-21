from ai_service.config import Settings
from ai_service.providers.base import LlmProvider


def build_provider(settings: Settings) -> LlmProvider:
    if settings.llm_provider == "fake":
        from ai_service.providers.fake import FakeProvider
        return FakeProvider()

    from ai_service.providers.openai_compat import OpenAiCompatProvider
    from ai_service.secrets import read_llm_api_key

    # Guaranteed by Settings' validator; the assert only narrows the types for mypy.
    assert settings.llm_base_url and settings.llm_model
    return OpenAiCompatProvider(
        base_url=settings.llm_base_url,
        model=settings.llm_model,
        api_key=read_llm_api_key(settings),
        timeout_seconds=settings.llm_timeout_seconds,
    )
