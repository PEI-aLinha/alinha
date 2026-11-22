"""POST /v1/drafts (contracts/ai/ai-service.v1.yaml).

The service only drafts: it returns text with provenance and never writes
platform data (RNF11). The context arrives already redacted by the core (RNF13).
"""

import json
from datetime import UTC, datetime
from typing import Any, Literal

from fastapi import APIRouter, Depends
from pydantic import BaseModel, ConfigDict, field_validator
from pydantic.alias_generators import to_camel

from ai_service.auth import require_service_token
from ai_service.deps import get_provider
from ai_service.errors import UnknownTask
from ai_service.prompts import get_template
from ai_service.providers.base import LlmProvider

router = APIRouter(prefix="/v1", dependencies=[Depends(require_service_token)])

# Real contexts are a few levels deep (organization -> controls -> answers). Deeper nesting is
# a bug or abuse, and very deep values are costly to serialise for the LLM.
MAX_CONTEXT_DEPTH = 20


def _depth(value: Any) -> int:
    """Nesting depth of dicts/lists, computed iteratively (no recursion on hostile input)."""
    deepest = 0
    stack: list[tuple[Any, int]] = [(value, 1)]
    while stack:
        current, level = stack.pop()
        if isinstance(current, dict):
            deepest = max(deepest, level)
            stack.extend((v, level + 1) for v in current.values())
        elif isinstance(current, list):
            deepest = max(deepest, level)
            stack.extend((v, level + 1) for v in current)
    return deepest


class DraftRequest(BaseModel):
    # Unknown fields are ignored, not rejected: a newer core may add optional fields
    # (e.g. a correlation id) before this service is updated. They never reach the LLM.
    model_config = ConfigDict(extra="ignore")

    task: str
    context: dict[str, Any]

    @field_validator("context")
    @classmethod
    def _not_too_deep(cls, value: dict[str, Any]) -> dict[str, Any]:
        if _depth(value) > MAX_CONTEXT_DEPTH:
            raise ValueError(f"context is nested more than {MAX_CONTEXT_DEPTH} levels")
        return value


class AiDraft(BaseModel):
    """Mirrors pt.alinha.core.aigateway.AiDraft (AI Act labelling, RNF14 provenance)."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")

    text: str
    model: str
    prompt_version: str
    sources: list[str]
    generated_at: datetime
    ai_generated: Literal[True] = True
    requires_human_review: Literal[True] = True


@router.post("/drafts", response_model=AiDraft)
async def create_draft(
    body: DraftRequest, provider: LlmProvider = Depends(get_provider)
) -> AiDraft:
    template = get_template(body.task)
    if template is None:
        raise UnknownTask()

    # The context goes only in the user message, as data; the instructions stay in `system`.
    user = json.dumps(body.context, ensure_ascii=False, sort_keys=True)
    result = await provider.complete(system=template.system, user=user)

    return AiDraft(
        text=result.text,
        model=result.model,
        prompt_version=template.version,
        sources=[],  # filled once RAG over the legislation exists
        generated_at=datetime.now(UTC),
    )
