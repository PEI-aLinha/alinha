#!/usr/bin/env python3
"""Fails if a PR changes a published contract version incompatibly (contracts/README.md).

- contracts/jobs/*.vN.schema.json at the merge base must stay byte-identical: with `additionalProperties: false`
  any change breaks one side. A change is a new vN+1 file.
- contracts/ai/*.vN.yaml may grow (see the compatibility rule in the file), but against the changed schemas:
  - every valid example the merge base has for it must still validate (nothing the core or the service sends today
    is refused tomorrow);
  - every invalid example of a *response* schema must still be refused (the core can keep trusting what it checks
    in a draft or an error; a request schema may accept more, which is compatible).

Everything is read at the merge base of the PR and the base branch (default origin/main), so a PR cannot change a
contract and its examples together to hide a break, and a base branch that moved on does not count against the PR.
Run: python scripts/check_contract_compat.py (compares with origin/main; fetch it first). Needs jsonschema[format-nongpl] and pyyaml (CI uses the AI
Service's locked venv). Not caught: changes no example exercises; keep the examples representative.
"""
from __future__ import annotations

import copy
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Iterator, NamedTuple

import yaml
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
JOB_SCHEMA = re.compile(r"^contracts/jobs/[^/]+\.v\d+\.schema\.json$")
AI_CONTRACT = re.compile(r"^contracts/ai/([^/]+\.v\d+)\.yaml$")
AI_EXAMPLE = re.compile(
    r"^contracts/ai/examples/(?P<contract>[^/]+)/(?P<schema>[^/]+)/(?P<kind>valid|invalid)/[^/]+\.json$"
)
BASE_BRANCH = "origin/main"
SCHEMA_REF = re.compile(r"^#/components/schemas/([^/]+)$")


class Example(NamedTuple):
    schema: str
    document: Any
    valid: bool


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout


def merge_base(base: str) -> str:
    """The commit the PR is compared with. Raises CalledProcessError when `base` is not a commit."""
    # --end-of-options: `base` comes from the command line and must never be read as a git option.
    commit = git("rev-parse", "--verify", "--quiet", "--end-of-options", f"{base}^{{commit}}").strip()
    return git("merge-base", commit, "HEAD").strip()


def changed_job_schemas(base: str) -> list[str]:
    """Job schemas that existed at `base` and were modified, deleted or retyped since."""
    diff = git("diff", "--name-only", "--diff-filter=MDT", "--no-renames", base, "HEAD", "--", "contracts/jobs")
    return [path for path in diff.splitlines() if JOB_SCHEMA.match(path)]


def schema_validator(contract: dict[str, Any], schema: str) -> Draft202012Validator:
    """The named `components.schemas` entry, with `#/components/...` references resolving inside the contract."""
    if schema not in contract.get("components", {}).get("schemas", {}):
        raise KeyError(schema)
    root = copy.deepcopy(contract)
    root["$ref"] = f"#/components/schemas/{schema}"
    return Draft202012Validator(root, format_checker=FormatChecker())


def _refs(node: Any) -> Iterator[str]:
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "$ref" and isinstance(value, str) and (match := SCHEMA_REF.match(value)):
                yield match[1]
            else:
                yield from _refs(value)
    elif isinstance(node, list):
        for value in node:
            yield from _refs(value)


def response_schemas(contract: dict[str, Any]) -> set[str]:
    """Schemas referenced by any response of any operation: what the consumer of the contract trusts."""
    return {
        name
        for operations in contract.get("paths", {}).values()
        for operation in operations.values()
        if isinstance(operation, dict)
        for name in _refs(operation.get("responses", {}))
    }


def ai_breaks(contract_text: str, examples: dict[str, Example]) -> list[str]:
    """One line per example the changed contract no longer treats as the base did. Keywords only, never values."""
    contract = yaml.safe_load(contract_text)
    responses = response_schemas(contract)
    errors = []
    for path, example in sorted(examples.items()):
        try:
            validator = schema_validator(contract, example.schema)
        except KeyError:
            errors.append(f"{path}: schema {example.schema} was removed")
            continue
        if example.valid:
            for error in validator.iter_errors(example.document):
                location = "/" + "/".join(str(part) for part in error.absolute_path)
                errors.append(f"{path}: {location}: {error.validator} (a valid example no longer validates)")
        elif example.schema in responses and validator.is_valid(example.document):
            errors.append(f"{path}: now accepted (the response schema {example.schema} was loosened)")
    return errors


def check_ai(base: str) -> list[str]:
    published = git("ls-tree", "-r", "--name-only", base, "--", "contracts/ai").splitlines()
    errors = []
    for path in published:
        match = AI_CONTRACT.match(path)
        if not match:
            continue
        current = ROOT / path
        if not current.is_file():
            errors.append(f"{path} was deleted: keep it until every consumer has moved to the next version")
            continue
        examples = {
            example: Example(m["schema"], json.loads(git("show", f"{base}:{example}")), m["kind"] == "valid")
            for example in published
            if (m := AI_EXAMPLE.match(example)) and m["contract"] == match[1]
        }
        errors += ai_breaks(current.read_text(encoding="utf-8"), examples)
    return errors


def main(name: str = BASE_BRANCH) -> int:
    """`name` is fixed in CI and set only by tests: no command-line value ever reaches a git argument."""
    try:
        base = merge_base(name)
    except subprocess.CalledProcessError:
        print(f"ERROR: {name} not found (fetch it first: git fetch origin main).")
        return 1

    errors = [
        f"{path} is a published job contract and cannot change. Create the next vN file instead."
        for path in changed_job_schemas(base)
    ]
    errors += check_ai(base)
    for error in errors:
        print(f"ERROR: {error}")
    if errors:
        return 1
    print(f"ok -- published contract versions are unchanged or compatible (compared with {base[:12]}, from {name})")
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
