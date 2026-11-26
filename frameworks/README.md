# Framework plugins (versioned catalogues)

Data, not code. Each framework has versioned YAML/JSON catalogues that the Compliance Engine loads
and announces with `CatalogueVersionPublished`.

| Framework | Phase (technical report, 4.1) |
|---|---|
| `nis2-qnrcs/` (NIS2 · RJC / QNRCS) | MVP |
| `soc-cmm/` (SOC-CMM v3, our wording, unreviewed: not in `RELEASES`) | MVP |
| `gdpr/` | post-MVP |
| `ai-act/` | post-MVP |

```
frameworks/<framework>/vN/catalogue.yaml
frameworks/<framework>/vN/applicability.yaml
```

**Level scale (ADR 0007).** A catalogue in the v2 format (`contracts/frameworks/catalogue.v2.schema.json`) declares
its own ordered `levels: [{id, label}]`, lowest first, and each control's `level` is one of those ids. A v1 catalogue
(no `levels`, e.g. `nis2-qnrcs/v1`, already released) has the QNRCS compliance levels basic, substantial, high.
`scripts/validate-frameworks.sh` picks the schema by the presence of `levels:`. A catalogue that declares `levels`
gets no implicit evidence bars (#26): a future `nis2-qnrcs/v2` must restate `requiredStrength: DOCUMENT_VERIFIED` and
`requiredStrengthTechnical: TECHNICALLY_VERIFIED` for `high`.

**RNF18 — a new version without recompiling or redeploying.** Catalogues do NOT go inside the jar:
the Compliance Engine reads them at runtime (folder mounted into the container, or imported into the database),
and this folder is the versioned source that gets published. A new version of a regulation = a new `vN/` folder;
a published version is never edited (older assessments keep pointing at the version they were made with — RNF05).

They live in the main repo (not in submodules) because they have no frontend or backend of their own.
