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

## Evidence strength (#26)

`EvaluationInput.evidence` lists evidence per control (the caller maps actions to controls): its strength
(`SELF_REPORTED < DOCUMENT_VERIFIED < TECHNICALLY_VERIFIED < CONTINUOUSLY_VERIFIED`), whether it shows the control in
place, and a `ref`. Each `ControlResult` carries `strength`: the strongest passing evidence, `SELF_REPORTED` (the
answers) when there is none. Applied after the answer rule:

1. Failing evidence of strength `TECHNICALLY_VERIFIED` or more turns any status into not met (`EVIDENCE_FAILED`).
   Weaker failing evidence changes nothing.
2. Passing evidence only raises the strength: it never upgrades a `no` or `partial` answer.
3. A would-be met control whose strength is below the bar for its level is partial (`WEAK_EVIDENCE`):

| Framework | Levels that accept the answers | Levels that need `DOCUMENT_VERIFIED` | Technical control at those levels |
|---|---|---|---|
| QNRCS | basic, substantial | high | `TECHNICALLY_VERIFIED` |
| SOC-CMM | 1 to 3 | 4, 5 | `TECHNICALLY_VERIFIED` |

Evidence for controls that are not evaluated is ignored. `ComplianceRules.requiredStrength(control, catalogue)` gives
the bar. **MVP consequence:** there is no document upload yet, so an essential entity (target high) sees its high
controls as partial (weak evidence) until evidence of that strength exists.

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
| PR | PR.GA-2-S partial, PR.SD-1-S not met, PR.SP-4-S partial, PR.RI-2-S not met, PR.GA-3-S not met (answered yes, failing technical evidence) | basic |
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

Where each domain touches the QNRCS functions (read only, indicative, pending advisor review; no shared controls):

| SOC-CMM domain | QNRCS functions |
|---|---|
| BUS Negócio | GR Governação |
| PEO Pessoas | GR Governação, PR Proteger (awareness and training) |
| PRO Processos | RS Responder, RC Recuperar |
| TEC Tecnologia | PR Proteger, DE Detetar |
| SVC Serviços | DE Detetar, ID Identificar (vulnerabilities) |

Example (golden test `soc-cmm-v1-rui`, illustrative): Rui, important, target 3. BUS 2, PEO 1, PRO 3, TEC 2 (TEC.3
unanswered), SVC 0 (nobody looks at the alerts): overall maturity **0**, coverage 11 of 15 (73%).
