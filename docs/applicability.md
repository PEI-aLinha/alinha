# Applicability rules (FR02, FR03)

`core.compliance.ApplicabilityRules.assess(input)` tells an organization which frameworks apply to it and its
indicative NIS2 classification. The rules are deterministic and versioned (`ApplicabilityRules.VERSION`, RNF05):
store the version with every result. Tests: `ApplicabilityRulesTest`.

The result is **indicative**. The official NIS2 qualification is made by the CNCS (RJC art. 8.º), and findings
marked `review` must be confirmed by a consultant. Every finding carries its legal basis and a plain-language
explanation in Portuguese for the user.

Sources:

- **RJC**: Regime Jurídico da Cibersegurança, approved by Decree-Law 125/2025 (Diário da República 234/2025,
  1.ª série, 4 Dec 2025). Articles below are of the RJC (the decree's annex).
- **RGPD**: Regulation (EU) 2016/679.
- **AI Act**: Regulation (EU) 2024/1689.

## Company size (RJC annex III, art. 2.º)

Headcount **and** (turnover **or** balance sheet) under the limit:

| Category | Employees | Turnover | Balance sheet |
|---|---|---|---|
| Micro | < 10 | ≤ 2 M€ | or ≤ 2 M€ |
| Small | < 50 | ≤ 10 M€ | or ≤ 10 M€ |
| Medium | < 250 | ≤ 50 M€ | or ≤ 43 M€ |
| Large | anything above | | |

Turnover is required; the balance sheet is optional and can only keep a company in a smaller category.

## NIS2 (RJC)

The user picks the **entity types** of annexes I and II that describe the organization (`Nis2EntityType`). The law
defines most types by what the entity does, not by its CAE: the CAE only suggests the manufacturing types of
annex II, 5.b to 5.f, which the annex itself defines by NACE Rev. 2 division (26 to 30).

| Situation | Classification | Basis |
|---|---|---|
| Critical entity (Directive (EU) 2022/2557), any size, public ones included | essential | art. 3.º n.º 5, art. 6.º n.º 1 e) |
| Public body or higher-education institution, not a critical entity | to be assessed (consultant) | art. 3.º n.os 3 to 6, art. 7.º |
| No annex type | not covered | art. 3.º n.º 1 |
| Qualified trust service provider, TLD registry, DNS provider, any size | essential | art. 3.º n.º 2 a), art. 6.º n.º 1 b) |
| Electronic communications, medium or large | essential | art. 6.º n.º 1 a) and c) |
| Electronic communications, micro or small | important | art. 3.º n.º 2 a) i), art. 6.º n.º 2 |
| Non-qualified trust service provider, not large | important | art. 3.º n.º 2 a) ii), art. 6.º n.º 2 |
| Annex I, large | essential | art. 3.º n.º 1, art. 6.º n.º 1 a) |
| Annex I medium, or annex II medium or large | important | art. 3.º n.º 1, art. 6.º n.º 2 |
| Annex type, micro or small | not covered | art. 3.º n.º 1 a) |

- With several types, the most demanding classification wins (art. 9.º). Every type keeps its finding, most
  demanding first and then in annex order, so the result never depends on the order the types arrived in (RNF05).
- **Significant impact** (sole provider of an essential service, serious effect on public safety or health, systemic
  or national/regional importance) with an annex type and not already essential: `cncsMayQualify`, because the
  CNCS may still cover the organization or raise it to essential (art. 3.º n.º 2 b) to e), art. 6.º n.º 1 f) and
  n.º 3). NIS2 then shows as `REVIEW` when the rules alone say "not covered".
- **Significant impact with no annex type**: art. 3.º n.º 2 covers only annex types ("entidades de um dos tipos que
  constam nos anexos i ou ii"; "independentemente" refers to nature and size, not sector). The organization has
  probably not found its type in the list, so NIS2 shows as `REVIEW` and the consultant checks.

## Input validation

Contradictory answers are rejected with `IllegalArgumentException`, never answered silently: GDPR details with
`processesPersonalData = false`, and AI Act details (high-risk area, chatbot or generated content) with neither
`usesAiSystems` nor `providesAiSystems`. The screen that collects the answers must not allow these combinations.

## RGPD

| Situation | Finding | Basis |
|---|---|---|
| No personal data | does not apply | art. 2.º n.º 1 |
| Processes personal data | applies | art. 2.º n.º 1, art. 3.º n.º 1, art. 4.º 1) and 2) |
| ≥ 250 employees, or regular/risky processing, or special or criminal data, or large-scale monitoring or special data | records of processing required | art. 30.º n.os 1 and 5 |
| Otherwise | records exempt | art. 30.º n.º 5 |
| Public body, or core activity of large-scale monitoring or large-scale special data | DPO required | art. 37.º n.º 1 |

## AI Act

| Situation | Finding | Basis |
|---|---|---|
| Neither uses nor provides AI | does not apply | art. 2.º n.º 1 |
| Develops AI and offers it under its own name | provider | art. 2.º n.º 1 a), art. 3.º 3) |
| Uses AI at work | deployer | art. 2.º n.º 1 b), art. 3.º 4) |
| Uses or provides AI | AI literacy of its staff | art. 4.º |
| AI in an annex III area (recruitment, credit, insurance, education, biometrics, ...) | possible high risk, **review** | art. 6.º n.os 2 and 3, annex III |
| Chatbot, or generated content that is published | possible transparency duties, **review** (art. 50.º splits them between providers and deployers) | art. 50.º |

## Not covered by the rules (MVP)

- Territorial scope: the organization is assumed to be established in Portugal (RJC art. 4.º, RGPD art. 3.º).
- Partner and linked companies: size uses only the organization's own figures. Recommendation 2003/361/EC adds
  them, but RJC annex III does not reproduce those rules.
- Domain name registration service providers (RJC art. 3.º n.º 2 a) iii)): not in the annexes; they have the
  separate duties of art. 35.º.
- Public sector groups A and B (RJC art. 7.º): left to the consultant.
- AI Act prohibited practices (art. 5.º), the art. 6.º n.º 3 exceptions and the application dates.
- CAE suggestions beyond NACE divisions 26 to 30: CAE Rev. 4 should be checked against NACE Rev. 2 before adding more.
