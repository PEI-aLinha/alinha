"""Tests for check_contract_compat.py (CI job contract-compat, in the AI Service's locked venv)."""
import json
import subprocess
from pathlib import Path

import pytest
import yaml

import check_contract_compat
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


# --- main() against a throwaway git repo: base commit = "main", later commits = the PR ---
REPO = Path(__file__).resolve().parents[1]
JOB = "contracts/jobs/evidence-collect.v1.schema.json"
AI = "contracts/ai/ai-service.v1.yaml"
AI_EXAMPLE = "contracts/ai/examples/ai-service.v1/DraftRequest/valid/roadmap.json"


def run(repo: Path, *args: str) -> None:
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *args], cwd=repo, check=True, capture_output=True)


def commit(repo: Path) -> None:
    run(repo, "add", "-A")
    run(repo, "commit", "-qm", "change")


@pytest.fixture
def repo(tmp_path, monkeypatch):
    for path in (JOB, AI, AI_EXAMPLE):
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
    (repo / AI_EXAMPLE).with_name("other.json").write_text('{"task": "x", "context": {}}', encoding="utf-8")
    commit(repo)
    assert check_contract_compat.main(["check", "main"]) == 0


def test_an_edited_job_schema_fails(repo, capsys):
    path = repo / JOB
    path.write_text(path.read_text(encoding="utf-8").replace("provider plugin id", "provider id"), encoding="utf-8")
    commit(repo)
    assert check_contract_compat.main(["check", "main"]) == 1
    assert f"{JOB} is a published job contract" in capsys.readouterr().out


def test_a_deleted_job_schema_fails(repo):
    (repo / JOB).unlink()
    commit(repo)
    assert check_contract_compat.main(["check", "main"]) == 1


def test_an_ai_change_that_breaks_a_main_example_fails(repo, capsys):
    path = repo / AI
    path.write_text(path.read_text(encoding="utf-8").replace("required: [task, context]", "required: [task, context, tenant]"), encoding="utf-8")
    commit(repo)
    assert check_contract_compat.main(["check", "main"]) == 1
    assert f"{AI_EXAMPLE}: /: required (a valid example on main no longer validates)" in capsys.readouterr().out


def test_examples_changed_in_the_pr_cannot_hide_a_break(repo):
    path = repo / AI
    path.write_text(path.read_text(encoding="utf-8").replace("required: [task, context]", "required: [task, context, tenant]"), encoding="utf-8")
    (repo / AI_EXAMPLE).write_text('{"task": "roadmap", "context": {}, "tenant": "x"}', encoding="utf-8")
    commit(repo)
    assert check_contract_compat.main(["check", "main"]) == 1


def test_a_deleted_ai_contract_fails(repo, capsys):
    (repo / AI).unlink()
    commit(repo)
    assert check_contract_compat.main(["check", "main"]) == 1
    assert f"{AI} was deleted" in capsys.readouterr().out


def test_a_missing_base_fails(repo, capsys):
    assert check_contract_compat.main(["check", "no-such-branch"]) == 1
    assert "no-such-branch not found" in capsys.readouterr().out
