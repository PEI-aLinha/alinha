"""POST /v1/drafts (contracts/ai/ai-service.v1.yaml).

The service only drafts: it returns text with provenance and never writes
platform data (RNF11). The context arrives already redacted by the core (RNF13).
"""

import json
from datetime import UTC, datetime
from typing import Any, Literal

from fastapi import APIRouter, Depends
from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

from ai_service.deps import get_provider
from ai_service.errors import UnknownTask
from ai_service.prompts import get_template
from ai_service.providers.base import LlmProvider

router = APIRouter(prefix="/v1")


class DraftRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    task: str
    context: dict[str, Any]


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
