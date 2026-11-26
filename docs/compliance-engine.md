# Compliance Engine (RF38, RF39)

`core.compliance.ComplianceEngine.evaluate(catalogue, maxLevel, answers)` (implemented by `ControlRules`) turns an
assessment's answers into control statuses, coverage, gaps and a current level per domain. Deterministic and
versioned (RNF05): never an LLM, no clock or randomness, output in catalogue order. Every result carries
`engineVersion`, `framework` and `catalogueVersion`; callers store it with them and do not recompute on read. The
engine writes nothing: the caller stores, audits and checks permissions. Words and scales: ADR 0007.

- **Levels** are ids of the catalogue's own level scale (`Catalogue.levels`, ADR 0007), compared by position.
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

## Evidence strength (#26)

The `evidence` argument lists evidence per control (the caller maps actions to controls): its strength
(`SELF_REPORTED < DOCUMENT_VERIFIED < TECHNICALLY_VERIFIED < CONTINUOUSLY_VERIFIED`), whether it shows the control in
place, and a `ref`. Each `ControlEvaluation` carries `strength`: the strongest passing evidence, `SELF_REPORTED` (the
answers) when there is none. Applied after the answer rule:

1. Failing evidence of strength `TECHNICALLY_VERIFIED` or more turns any status into a gap (`EVIDENCE_FAILED`).
   Weaker failing evidence changes nothing.
2. Passing evidence only raises the strength: it never upgrades a `no` or `partial` answer.
3. A would-be compliant control whose strength is below the bar for its level is partial (`WEAK_EVIDENCE`). Each level of
   the catalogue's scale declares its bar (`levels[].requiredStrength`, and `requiredStrengthTechnical` for technical
   controls; default `SELF_REPORTED`, catalogue schema v2). The implicit QNRCS scale of v1 catalogues has it in code:

| Framework | Levels that accept the answers | Levels that need `DOCUMENT_VERIFIED` | Technical control at those levels |
|---|---|---|---|
| QNRCS (in code, `QNRCS_LEVELS`) | basic, substantial | high | `TECHNICALLY_VERIFIED` |
| SOC-CMM (`soc-cmm/v1` YAML) | 1 to 3 | 4, 5 | `TECHNICALLY_VERIFIED` |

Evidence for controls that are not evaluated is ignored.
**MVP consequence:** there is no document upload yet, so an essential entity (target high) sees its high
controls as partial (weak evidence) until evidence of that strength exists. Golden test `nis2-qnrcs-v1-essential`: every question
answered yes, verified evidence only for RC.CO-2-E: RC reaches high, the other five domains stop at substantial
(50 high controls partial, `WEAK_EVIDENCE`), overall substantial, coverage 67%.

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
| PR | PR.GA-2-S partial, PR.SD-1-S no, PR.SP-4-S partial, PR.RI-2-S no, PR.GA-3-S gap (answered yes, failing technical evidence) | basic |
| DE | DE.MC-6-S, DE.MC-7-S, DE.AE-4-S unknown | basic |
| **Overall** | 8 of 102 evaluated controls | **basic**, coverage 92% |

## SOC-CMM (maturity, #23)

`frameworks/soc-cmm/v1/catalogue.yaml`: our own plain wording based on SOC-CMM v3, **unreviewed** (not in
`frameworks/RELEASES`). Its own framework and its own assessment, never compared with QNRCS (ADR 0007). "The SOC" is
whoever runs the company's security operations: its own staff, an outside company or aLinha.

- **Maturity levels:** 0 Inexistente, 1 Inicial, 2 Gerido, 3 Definido, 4 Medido, 5 Otimizado. The catalogue declares
  1 to 5; level 0 has no controls. A domain is at **0** (not `null`) when any of its level-1 controls is not met: the
  engine reports `"0"` for any numbered scale whose first level is above 0.
- **Domains:** five, one control per level each (25 controls): `BUS` Negócio, `PEO` Pessoas, `PRO` Processos,
  `TEC` Tecnologia, `SVC` Serviços.
- **Target:** *recomendado*, never "exigido por lei". Set by the assessment plugin (`ScopeRules.recommendedMaturity`)
  from the NIS2 classification (official when registered):

| NIS2 classification | Recommended maturity |
|---|---|
| not covered | 2 Gerido |
| important | 3 Definido |
| essential, to be assessed | 4 Medido |

The target is also the **questionnaire ceiling**: the assessment asks only up to it, so an important organization
is never asked levels 4–5 and its maturity can never show above 3.

Where each domain touches the QNRCS functions (read only, indicative, pending advisor review; no shared controls):

| SOC-CMM domain | QNRCS functions |
|---|---|
| BUS Negócio | GR Governação |
| PEO Pessoas | GR Governação, PR Proteger (awareness and training) |
| PRO Processos | RS Responder, RC Recuperar |
| TEC Tecnologia | PR Proteger, DE Detetar |
| SVC Serviços | DE Detetar, ID Identificar (vulnerabilities) |

Example (golden test `soc-cmm-v1-rui`, illustrative): Rui, important, target 3. BUS 2, PEO 1, PRO 3, TEC 2 (TEC.3
unknown), SVC 0 (nobody looks at the alerts): overall maturity **0**, coverage 11 of 15 (73%).
