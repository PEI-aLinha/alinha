"""Tests for check_contract_compat.py (CI job contract-compat, in the AI Service's locked venv)."""
import json
import subprocess
from pathlib import Path

import pytest
import yaml

import check_contract_compat
from check_contract_compat import AI_CONTRACT, AI_EXAMPLE, JOB_SCHEMA, Example, ai_breaks, response_schemas

REPO = Path(__file__).resolve().parents[1]
CONTRACT = REPO / "contracts" / "ai" / "ai-service.v1.yaml"
DRAFT = {
    "text": "t", "model": "m", "promptVersion": "p", "sources": [],
    "generatedAt": "2026-11-24T10:00:00Z", "aiGenerated": True, "requiresHumanReview": True,
}
EXAMPLES = {
    "AiDraft/valid/draft.json": Example("AiDraft", DRAFT, valid=True),
    "DraftRequest/valid/req.json": Example("DraftRequest", {"task": "roadmap", "context": {}}, valid=True),
    "AiDraft/invalid/no-sources.json": Example("AiDraft", {k: v for k, v in DRAFT.items() if k != "sources"}, valid=False),
    "DraftRequest/invalid/no-context.json": Example("DraftRequest", {"task": "roadmap"}, valid=False),
}


def changed(edit) -> str:
    contract = yaml.safe_load(CONTRACT.read_text())
    edit(contract["components"]["schemas"])
    return yaml.safe_dump(contract)


def test_the_current_contract_treats_its_examples_as_before():
    assert ai_breaks(CONTRACT.read_text(), EXAMPLES) == []


def test_a_new_optional_field_is_compatible():
    text = changed(lambda s: s["DraftRequest"]["properties"].update(correlationId={"type": "string"}))
    assert ai_breaks(text, EXAMPLES) == []


def test_a_new_required_field_breaks_the_old_examples():
    text = changed(lambda s: s["DraftRequest"]["required"].append("correlationId"))
    assert ai_breaks(text, EXAMPLES) == ["DraftRequest/valid/req.json: /: required (a valid example no longer validates)"]


def test_a_narrowed_type_breaks_the_old_examples():
    text = changed(lambda s: s["AiDraft"]["properties"]["sources"].update(minItems=1))
    assert ai_breaks(text, EXAMPLES) == ["AiDraft/valid/draft.json: /sources: minItems (a valid example no longer validates)"]


def test_a_loosened_response_schema_breaks():
    text = changed(lambda s: s["AiDraft"]["required"].remove("sources"))
    assert ai_breaks(text, EXAMPLES) == ["AiDraft/invalid/no-sources.json: now accepted (the response schema AiDraft was loosened)"]


def test_a_loosened_request_schema_is_compatible():
    text = changed(lambda s: s["DraftRequest"]["required"].remove("context"))
    assert ai_breaks(text, EXAMPLES) == []


def test_response_schemas_come_from_the_operations():
    assert response_schemas(yaml.safe_load(CONTRACT.read_text())) == {"AiDraft", "Error"}


def test_a_removed_schema_is_reported():
    text = changed(lambda s: s.pop("AiDraft"))
    assert ai_breaks(text, EXAMPLES) == [
        "AiDraft/invalid/no-sources.json: schema AiDraft was removed",
        "AiDraft/valid/draft.json: schema AiDraft was removed",
    ]


def test_references_inside_the_contract_resolve():
    text = changed(lambda s: s.update(Sources={"type": "array", "items": {"type": "string"}, "minItems": 1})
                   or s["AiDraft"]["properties"].update(sources={"$ref": "#/components/schemas/Sources"}))
    assert ai_breaks(text, EXAMPLES) == ["AiDraft/valid/draft.json: /sources: minItems (a valid example no longer validates)"]


def test_date_time_is_enforced():
    # Needs rfc3339-validator (jsonschema's format extra). Without it date-time is silently skipped and the check weakens.
    examples = {"x.json": Example("AiDraft", {**DRAFT, "generatedAt": "today"}, valid=True)}
    assert ai_breaks(CONTRACT.read_text(), examples) == ["x.json: /generatedAt: format (a valid example no longer validates)"]


def test_error_lines_never_echo_the_example_values():
    secret = "s3cr3t"
    text = changed(lambda s: s["AiDraft"]["properties"]["text"].update(maxLength=1))
    lines = ai_breaks(text, {"x.json": Example("AiDraft", {**DRAFT, "text": secret}, valid=True)})
    assert lines == ["x.json: /text: maxLength (a valid example no longer validates)"]
    assert secret not in json.dumps(lines)


def test_path_patterns():
    assert JOB_SCHEMA.match("contracts/jobs/evidence-collect.v1.schema.json")
    assert not JOB_SCHEMA.match("contracts/jobs/examples/evidence-collect.v1/valid/minimal.json")
    assert AI_CONTRACT.match("contracts/ai/ai-service.v1.yaml")[1] == "ai-service.v1"
    m = AI_EXAMPLE.match("contracts/ai/examples/ai-service.v1/AiDraft/invalid/missing-sources.json")
    assert (m["contract"], m["schema"], m["kind"]) == ("ai-service.v1", "AiDraft", "invalid")
    assert not AI_EXAMPLE.match("contracts/ai/examples/ai-service.v1/AiDraft/expected-errors.json")


# --- main() against a throwaway git repo: "main" is the base branch, "pr" the PR ---
JOB = "contracts/jobs/evidence-collect.v1.schema.json"
AI = "contracts/ai/ai-service.v1.yaml"
AI_VALID = "contracts/ai/examples/ai-service.v1/DraftRequest/valid/roadmap.json"
AI_INVALID = "contracts/ai/examples/ai-service.v1/AiDraft/invalid/missing-sources.json"


def run(repo: Path, *args: str) -> None:
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *args], cwd=repo, check=True, capture_output=True)


def commit(repo: Path) -> None:
    run(repo, "add", "-A")
    run(repo, "commit", "-qm", "change")


def edit(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    assert old in text
    path.write_text(text.replace(old, new), encoding="utf-8")


@pytest.fixture
def repo(tmp_path, monkeypatch):
    for path in (JOB, AI, AI_VALID, AI_INVALID):
        target = tmp_path / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text((REPO / path).read_text(encoding="utf-8"), encoding="utf-8")
    run(tmp_path, "init", "-q", "-b", "main")
    commit(tmp_path)
    run(tmp_path, "switch", "-qc", "pr")
    monkeypatch.setattr(check_contract_compat, "ROOT", tmp_path)
    return tmp_path


def test_unchanged_contracts_pass(repo, capsys):
    assert check_contract_compat.main(["check", "main"]) == 0
    assert "ok --" in capsys.readouterr().out


def test_a_new_job_version_and_new_examples_pass(repo):
    (repo / "contracts/jobs/evidence-collect.v2.schema.json").write_text("{}", encoding="utf-8")
    (repo / AI_VALID).with_name("other.json").write_text('{"task": "x", "context": {}}', encoding="utf-8")
    commit(repo)
    assert check_contract_compat.main(["check", "main"]) == 0


def test_an_edited_job_schema_fails(repo, capsys):
    edit(repo / JOB, "provider plugin id", "provider id")
    commit(repo)
    assert check_contract_compat.main(["check", "main"]) == 1
    assert f"{JOB} is a published job contract" in capsys.readouterr().out


def test_a_deleted_job_schema_fails(repo):
    (repo / JOB).unlink()
    commit(repo)
    assert check_contract_compat.main(["check", "main"]) == 1


def test_an_ai_change_that_breaks_a_base_example_fails(repo, capsys):
    edit(repo / AI, "required: [task, context]", "required: [task, context, tenant]")
    commit(repo)
    assert check_contract_compat.main(["check", "main"]) == 1
    assert f"{AI_VALID}: /: required (a valid example no longer validates)" in capsys.readouterr().out


def test_a_loosened_draft_fails(repo, capsys):
    edit(repo / AI, "required: [text, model, promptVersion, sources,", "required: [text, model, promptVersion,")
    commit(repo)
    assert check_contract_compat.main(["check", "main"]) == 1
    assert f"{AI_INVALID}: now accepted" in capsys.readouterr().out


def test_examples_changed_in_the_pr_cannot_hide_a_break(repo):
    edit(repo / AI, "required: [task, context]", "required: [task, context, tenant]")
    (repo / AI_VALID).write_text('{"task": "roadmap", "context": {}, "tenant": "x"}', encoding="utf-8")
    commit(repo)
    assert check_contract_compat.main(["check", "main"]) == 1


def test_changes_on_the_base_after_the_pr_branched_off_do_not_count(repo):
    # main gains a new contract and tightens nothing the PR sees: compared with the merge base, the PR is fine.
    run(repo, "switch", "-q", "main")
    (repo / "contracts/ai/other.v1.yaml").write_text("openapi: 3.1.0\n", encoding="utf-8")
    commit(repo)
    run(repo, "switch", "-q", "pr")
    assert check_contract_compat.main(["check", "main"]) == 0


def test_a_deleted_ai_contract_fails(repo, capsys):
    (repo / AI).unlink()
    commit(repo)
    assert check_contract_compat.main(["check", "main"]) == 1
    assert f"{AI} was deleted" in capsys.readouterr().out


@pytest.mark.parametrize("base", ["no-such-branch", "--output=/tmp/x"])
def test_a_missing_or_option_like_base_fails(repo, capsys, base):
    assert check_contract_compat.main(["check", base]) == 1
    assert f"{base} not found" in capsys.readouterr().out
