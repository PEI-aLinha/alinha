"""Generic LLM provider interface.

The rest of the service only depends on this module, never on a concrete
adapter, so switching provider is a configuration change.
"""

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class LlmResult:
    """What a provider returns for one completion."""

    text: str
    model: str  # the model that actually answered, recorded as provenance (RNF14)


class ProviderError(Exception):
    """The provider failed or returned something unusable (maps to HTTP 502)."""


class ProviderTimeout(ProviderError):
    """The provider did not answer in time (maps to HTTP 504)."""


class LlmProvider(Protocol):
    """Anything that can turn a system prompt plus user content into text."""

    async def complete(self, *, system: str, user: str) -> LlmResult:
        """Run one completion.

        `system` holds our versioned instructions; `user` holds the redacted,
        untrusted context. Keeping them separate is what stops the context
        from being treated as instructions.

        Raises ProviderTimeout or ProviderError, never a library-specific error.
        """
        ...
        
    async def aclose(self) -> None:
        """Close any resources (e.g. HTTP session) held by the provider."""
        ...