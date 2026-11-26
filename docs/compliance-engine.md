# Compliance Engine (RF38, RF39)

`core.compliance.ComplianceEngine.evaluate(catalogue, maxLevel, answers)` (implemented by `ControlRules`) turns an
assessment's answers into control statuses, coverage, gaps and a current level per domain. Deterministic and
versioned (RNF05): never an LLM, no clock or randomness, output in catalogue order. Every result carries
`engineVersion`, `framework` and `catalogueVersion`; callers store it with them and do not recompute on read. The engine writes nothing: the caller
stores, audits and checks permissions. Words and scales: ADR 0007.

- **Input:** the catalogue, the **target level** (`maxLevel`, chosen by the caller, e.g. from the NIS2
  classification in the assessment plugin) and the answers (question id → yes, partial, no, unknown). Controls above
  the target are not evaluated; answers to other questions are ignored. Every question of the evaluated controls must
  have an answer: otherwise `IncompleteAnswersException` lists the unanswered ones (the plugin answers 422).
- **Result:** one `ControlEvaluation` per evaluated control, the gaps (with the requirements that point to each), the
  current level per domain (domain = control id up to the first dot, e.g. `GR`), the overall current level and the
  coverage. The AI Gateway reads stored results through `EvaluationResults.latest(framework)`, for the current
  tenant (`TenantContext`).

## Control status

| Answers to the control's questions | Status | Reason |
|---|---|---|
| any `no` | gap | `ANSWERED_NO` |
| otherwise any `unknown` | gap | `ANSWERED_UNKNOWN` |
| otherwise any `partial` | partial | `ANSWERED_PARTIAL` |
| all `yes` | compliant | — |

Not confirmed is not in place: `unknown` counts as a gap. A **gap** in the result is any control that is not
compliant, with the catalogue requirements that point to it.

## Current level

Overall: the highest level L, up to the target, such that every evaluated control at level L or below is compliant.
A level with no controls at all is skipped: never reached on its own, never blocking the levels above. Per domain the
same over the same levels, where a level with no controls in that domain counts as compliant; so the overall level is
the lowest domain level. When the lowest level is not compliant the current level is `null` (QNRCS: not even
basic).

A compliance level (QNRCS) and a maturity level (SOC-CMM) are different scales and are never compared (ADR 0007).

## Coverage

Compliant controls / evaluated controls. It measures progress, **never a verdict of compliance**: 90% coverage with one
basic control not compliant is still not basic.

## Example: Rui (golden test)

Componentes Águeda, 120 employees, CAE 29320: important entity, target substantial. Fixture
`backend/core/src/test/resources/compliance/golden/nis2-qnrcs-v1-rui.*.json` (illustrative answers).

| Domain | Gaps | Current level |
|---|---|---|
| GR, ID, RS, RC | none | substantial |
| PR | PR.GA-2-S partial, PR.SD-1-S no, PR.SP-4-S partial, PR.RI-2-S no | basic |
| DE | DE.MC-6-S, DE.MC-7-S, DE.AE-4-S unknown | basic |
| **Overall** | 7 of 102 evaluated controls | **basic**, coverage 93% |
