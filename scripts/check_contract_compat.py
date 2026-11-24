#!/usr/bin/env python3
"""Fails if a PR changes a published contract version incompatibly (contracts/README.md).

- contracts/jobs/*.vN.schema.json on the base branch must stay byte-identical: with `additionalProperties: false`
  any change breaks one side. A change is a new vN+1 file.
- contracts/ai/*.vN.yaml may grow (see the compatibility rule in the file), but every valid example the base branch
  has for it must still validate against the changed schemas.

The base is read from git (default origin/main), so a PR cannot change a contract and its examples together to hide a
break. Run from anywhere: python scripts/check_contract_compat.py [base]. Needs jsonschema[format-nongpl] and pyyaml
(CI uses the AI Service's locked venv).
"""
from __future__ import annotations

import copy
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
JOB_SCHEMA = re.compile(r"^contracts/jobs/[^/]+\.v\d+\.schema\.json$")
AI_CONTRACT = re.compile(r"^contracts/ai/([^/]+\.v\d+)\.yaml$")
AI_VALID_EXAMPLE = re.compile(r"^contracts/ai/examples/(?P<contract>[^/]+)/(?P<schema>[^/]+)/valid/[^/]+\.json$")


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout


def changed_job_schemas(base: str) -> list[str]:
    """Job schemas that existed at the merge base and were modified, deleted or retyped since."""
    diff = git("diff", "--name-only", "--diff-filter=MDT", "--no-renames", f"{base}...HEAD", "--", "contracts/jobs")
    return [path for path in diff.splitlines() if JOB_SCHEMA.match(path)]


def schema_validator(contract: dict[str, Any], schema: str) -> Draft202012Validator:
    """The named `components.schemas` entry, with `#/components/...` references resolving inside the contract."""
    if schema not in contract.get("components", {}).get("schemas", {}):
        raise KeyError(schema)
    root = copy.deepcopy(contract)
    root["$ref"] = f"#/components/schemas/{schema}"
    return Draft202012Validator(root, format_checker=FormatChecker())


def ai_breaks(contract_text: str, examples: dict[str, tuple[str, Any]]) -> list[str]:
    """`examples` maps an example path to (schema name, document). Returns one line per example that no longer fits."""
    contract = yaml.safe_load(contract_text)
    errors = []
    for path, (schema, document) in sorted(examples.items()):
        try:
            validator = schema_validator(contract, schema)
        except KeyError:
            errors.append(f"{path}: schema {schema} was removed")
            continue
        for error in validator.iter_errors(document):
            location = "/" + "/".join(str(part) for part in error.absolute_path)
            errors.append(f"{path}: {location}: {error.validator}")  # keyword only, like the core's validators
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
            example: (m["schema"], json.loads(git("show", f"{base}:{example}")))
            for example in published
            if (m := AI_VALID_EXAMPLE.match(example)) and m["contract"] == match[1]
        }
        errors += ai_breaks(current.read_text(encoding="utf-8"), examples)
    return errors


def main(argv: list[str]) -> int:
    base = argv[1] if len(argv) > 1 else "origin/main"
    try:
        git("rev-parse", "--verify", "--quiet", base)
    except subprocess.CalledProcessError:
        print(f"ERROR: {base} not found (fetch it first: git fetch origin main).")
        return 1

    errors = [
        f"{path} is a published job contract and cannot change. Create the next vN file instead."
        for path in changed_job_schemas(base)
    ]
    errors += [f"{line} (a valid example on {base} no longer validates)" for line in check_ai(base)]
    for error in errors:
        print(f"ERROR: {error}")
    if errors:
        return 1
    print("ok -- published contract versions are unchanged or compatible")
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main(sys.argv))
