# ADR 0007 — Each framework catalogue declares its own level scale; SOC-CMM is separate from QNRCS

- **Status:** accepted
- **Date:** 2026-11-25
- **Issues:** #23 (SOC-CMM), #25 (Compliance Engine), #26 (evidence strength)
- **Refs:** `contracts/frameworks/`, technical report RF07, RF10, RF38, §4

## Context
#23 asks for a "SOC-CMM tier model" whose target comes from the NIS2 classification. The catalogue schema and the
core only knew the QNRCS compliance levels (basic, substantial, high), hard-coded as an enum in the schema, the
OpenAPI, the core and the assessment plugin. SOC-CMM rates maturity from 0 to 5 and measures something else: how
mature security operations are, not which legal measures apply. The report and the frontend prototype assumed one
maturity questionnaire with a single 1–5 maturity number (RF07, RF10).

## Decision
- **Compliance and maturity are separate scales and are never compared or derived from each other.** QNRCS has the
  compliance level (basic, substantial, high), set by the CNCS. SOC-CMM has the maturity level, 0 to 5, with its own
  names (0 Inexistente, 1 Inicial, 2 Gerido, 3 Definido, 4 Medido, 5 Otimizado). "Tier" is not used.
- **Each catalogue declares its ordered level scale** (catalogue schema v2). Controls point at a level of their own
  catalogue's scale, and the core compares positions in that scale, not a global enum. Maturity 0 has no controls: a
  domain is at 0 when any of its level-1 controls is not met.
- **One evaluation for every framework.** Control status is framework-neutral (met, partial, not met, unknown); a
  gap is any control not met; the current level per domain is the highest level fully met. The words "compliant" and
  "maturity" belong to the screen of each framework, not to the engine.
- **SOC-CMM is its own framework and its own assessment in the MVP**, with its own catalogue (`frameworks/soc-cmm/`)
  of controls in our own wording. Its target is a recommendation from the NIS2 classification, never a legal
  requirement.

## Considered options
- **Read QNRCS levels as SOC-CMM tiers.** Cheapest, but it mixes two models that measure different things.
- **A second hard-coded enum for maturity.** Every rule and screen would need a branch per framework.
- **One questionnaire producing both results** (shared controls, as in report §4). The best experience, but SOC-CMM
  levels 4–5 have no QNRCS counterpart, the mapping needs advisor review, and the assessment plugin holds one
  framework per assessment. Postponed until after the MVP.

## Consequences
- An SME answers two questionnaires (NIS2 and SOC-CMM), unlike RF07/RF10 as written. The shared questionnaire is the
  intended next step.
- The level-scale change is a coordinated `contracts/` change with the assessment plugin (schema, OpenAPI, database
  constraint, questionnaire screens).
- The prototype's maturity labels (2 Básico, 4 Gerido) are not used.
