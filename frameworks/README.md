# Framework plugins (versioned catalogues)

Data, not code. Each framework has versioned YAML/JSON catalogues that the Compliance Engine loads
and announces with `CatalogueVersionPublished`.

| Framework | Phase (technical report, 4.1) |
|---|---|
| `nis2-qnrcs/` (NIS2 · RJC / QNRCS) | MVP |
| `soc-cmm/` | MVP |
| `gdpr/` | post-MVP |
| `ai-act/` | post-MVP |

```
frameworks/<framework>/vN/catalogue.yaml
frameworks/<framework>/vN/applicability.yaml
```

**RNF18 — a new version without recompiling or redeploying.** Catalogues do NOT go inside the jar:
the Compliance Engine reads them at runtime (folder mounted into the container, or imported into the database),
and this folder is the versioned source that gets published. A new version of a regulation = a new `vN/` folder;
a published version is never edited (older assessments keep pointing at the version they were made with — RNF05).

They live in the main repo (not in submodules) because they have no frontend or backend of their own.
