"""Tests for check_contract_compat.py. CI: ai-service/.venv/bin/pytest scripts/test_check_contract_compat.py"""
import json
from pathlib import Path

import yaml

from check_contract_compat import AI_CONTRACT, AI_VALID_EXAMPLE, JOB_SCHEMA, ai_breaks

CONTRACT = Path(__file__).resolve().parents[1] / "contracts" / "ai" / "ai-service.v1.yaml"
DRAFT = {
    "text": "t", "model": "m", "promptVersion": "p", "sources": [],
    "generatedAt": "2026-11-24T10:00:00Z", "aiGenerated": True, "requiresHumanReview": True,
}
EXAMPLES = {"AiDraft/valid/draft.json": ("AiDraft", DRAFT), "DraftRequest/valid/req.json": ("DraftRequest", {"task": "roadmap", "context": {}})}


def changed(edit) -> str:
    contract = yaml.safe_load(CONTRACT.read_text())
    edit(contract["components"]["schemas"])
    return yaml.safe_dump(contract)


def test_the_current_contract_accepts_its_examples():
    assert ai_breaks(CONTRACT.read_text(), EXAMPLES) == []


def test_a_new_optional_field_is_compatible():
    text = changed(lambda s: s["DraftRequest"]["properties"].update(correlationId={"type": "string"}))
    assert ai_breaks(text, EXAMPLES) == []


def test_a_new_required_field_breaks_the_old_examples():
    text = changed(lambda s: s["DraftRequest"]["required"].append("correlationId"))
    assert ai_breaks(text, EXAMPLES) == ["DraftRequest/valid/req.json: /: required"]


def test_a_narrowed_type_breaks_the_old_examples():
    text = changed(lambda s: s["AiDraft"]["properties"]["sources"].update(minItems=1))
    assert ai_breaks(text, EXAMPLES) == ["AiDraft/valid/draft.json: /sources: minItems"]


def test_a_removed_schema_is_reported():
    text = changed(lambda s: s.pop("AiDraft"))
    assert ai_breaks(text, EXAMPLES) == ["AiDraft/valid/draft.json: schema AiDraft was removed"]


def test_references_inside_the_contract_resolve():
    text = changed(lambda s: s.update(Sources={"type": "array", "items": {"type": "string"}, "minItems": 1})
                   or s["AiDraft"]["properties"].update(sources={"$ref": "#/components/schemas/Sources"}))
    assert ai_breaks(text, EXAMPLES) == ["AiDraft/valid/draft.json: /sources: minItems"]


def test_error_lines_never_echo_the_example_values():
    secret = "s3cr3t"
    text = changed(lambda s: s["AiDraft"]["properties"]["text"].update(maxLength=1))
    lines = ai_breaks(text, {"x.json": ("AiDraft", {**DRAFT, "text": secret})})
    assert lines == ["x.json: /text: maxLength"]
    assert secret not in json.dumps(lines)


def test_path_patterns():
    assert JOB_SCHEMA.match("contracts/jobs/evidence-collect.v1.schema.json")
    assert not JOB_SCHEMA.match("contracts/jobs/examples/evidence-collect.v1/valid/minimal.json")
    assert AI_CONTRACT.match("contracts/ai/ai-service.v1.yaml")[1] == "ai-service.v1"
    m = AI_VALID_EXAMPLE.match("contracts/ai/examples/ai-service.v1/AiDraft/valid/with-sources.json")
    assert (m["contract"], m["schema"]) == ("ai-service.v1", "AiDraft")
    assert not AI_VALID_EXAMPLE.match("contracts/ai/examples/ai-service.v1/AiDraft/invalid/missing-sources.json")
