# Compliance Engine (RF38, RF39)

`core.compliance.ComplianceEngine.evaluate(input)` (implemented by `ComplianceRules`) turns an assessment's answers
into control statuses, coverage, gaps and a current level per domain. Deterministic and versioned (RNF05): never an
LLM, no clock or randomness, output in catalogue order. Every result carries `rulesVersion`, `framework` and
`catalogueVersion`; callers store it with them and do not recompute on read. The engine writes nothing: the caller
stores, audits and checks permissions. Words: see `GLOSSARY.md` and ADR 0007.

- **Levels** are ids of the catalogue's own level scale (`Catalogue.levels`, ADR 0007), compared by position.
- **Input:** the catalogue, the **target level** (chosen by the caller, e.g. from the NIS2 classification in the
  assessment plugin) and the answers (question id → yes, partial, no, unknown). Controls above the target are not
  evaluated; answers to other questions are ignored.
- **Result:** one `ControlResult` per evaluated control, the gaps (with the requirements that point to each), the
  current level per domain (domain = control id up to the first dot, e.g. `GR`), the overall current level and the
  coverage. The AI Gateway reads stored results through `EvaluationResults.latest(tenant, framework)`.

## Control status

| Answers to the control's questions | Status | Reason |
|---|---|---|
| any `no` | not met | `ANSWERED_NO` |
| otherwise any `unknown` or unanswered | unknown | `NOT_ANSWERED` |
| otherwise any `partial` | partial | `ANSWERED_PARTIAL` |
| all `yes` | met | — |

"Unknown" and "not answered" are the same. A **gap** is any control that is not met.

## Current level

Per domain: the highest level L, up to the target, such that every evaluated control of the domain at level L or
below is met. A level with no controls in that domain counts as met. When the lowest level is not met, the current
level is `null` (QNRCS: not even basic). The overall current level is the lowest domain level.

A compliance level (QNRCS) and a maturity level (SOC-CMM) are different scales and are never compared (ADR 0007).

## Coverage

Met controls / evaluated controls. It measures progress, **never a verdict of compliance**: 90% coverage with one
unmet basic control is still not basic.

## Example: Rui (golden test)

Componentes Águeda, 120 employees, CAE 29320: important entity, target substantial. Fixture
`backend/core/src/test/resources/compliance/golden/nis2-qnrcs-v1-rui.*.json` (illustrative answers).

| Domain | Gaps | Current level |
|---|---|---|
| GR, ID, RS, RC | none | substantial |
| PR | PR.GA-2-S partial, PR.SD-1-S not met, PR.SP-4-S partial, PR.RI-2-S not met | basic |
| DE | DE.MC-6-S, DE.MC-7-S, DE.AE-4-S unknown | basic |
| **Overall** | 7 of 102 evaluated controls | **basic**, coverage 93% |
