# aLinha

CISO-as-a-Service for Portuguese SMEs (NIS2 · GDPR · AI Act). PEI project — University of Aveiro.

This is the **main repo**: core, `plugin-api`, frontend shell, BFF, AI Service, workers, contracts and deployment.
Each Feature/Provider plugin lives in **its own repo** (backend + frontend) and is included here as a **git submodule**
under `plugins/<kind>/<id>`. Everything is built into a single deployable. Decision: [ADR 0001](docs/adr/0001-repositories-with-submodules.md).

```
alinha/
├── backend/
│   ├── plugin-api/       core ↔ plugin contract (manifest, events, TenantId)
│   ├── core/             tenant, access (RBAC), audit, registry, jobs, compliance, aigateway
│   └── app/              assembles core + plugins; ModularityTest (Spring Modulith verify)
├── frontend/
│   ├── shell/            router, layouts, session — mounts the plugins' routes and menu
│   └── packages/
│       ├── plugin-sdk/   shell ↔ plugin contract (FrontendPlugin, useHost)
│       ├── ui/           design system
│       └── api-client/   types generated from OpenAPI
├── plugins/
│   ├── feature/<id>/     → submodule alinha-plugin-<id>
│   └── provider/<id>/    → submodule alinha-provider-<id>
├── frameworks/           versioned YAML catalogues (NIS2/QNRCS, SOC-CMM, GDPR, AI Act)
├── contracts/            JSON Schema / OpenAPI shared between Kotlin, Python and TS
├── bff/  ai-service/  workers/
├── deploy/               development docker-compose, Keycloak
└── docs/adr/
```

## Language

Code, comments, docs and commit messages are in **English**.
Everything the user sees in the UI (labels, titles, URL paths) is in **Portuguese**, in plain language
(UI requirement in the technical report).

## Plugins and owners

> Proposed split — confirm as a team and mirror it in each repo's CODEOWNERS.

| Plugin | Repo | Phase | Owner |
|---|---|---|---|
| Organization | `alinha-plugin-organization` | MVP | Íris |
| Assessment | `alinha-plugin-assessment` | MVP | Afonso |
| Risk & Assets | `alinha-plugin-risk-assets` | MVP | Martim |
| Dashboard & Reports | `alinha-plugin-dashboard-reports` | MVP | Inês |
| Notifications | `alinha-plugin-notifications` | MVP | Xavier |
| Code Repository | `alinha-provider-code-repository` | MVP | Xavier |
| External Surface | `alinha-provider-external-surface` | MVP | Xavier |
| Policy & Evidence | create with `scripts/new-plugin.sh` | post-MVP | Afonso |
| Review & Approval | same | post-MVP | Martim |
| Incident Reporting | same | post-MVP | Íris |
| Identity / Cloud, SIEM | same | post-MVP | Xavier |

## Getting started (once per person)

```bash
git config --global submodule.recurse true             # pull/checkout also update submodules
git config --global push.recurseSubmodules on-demand   # pushing the main repo pushes plugins first

git clone --recurse-submodules git@github.com:PEI-aLinha/alinha.git
cd alinha
docker compose -f deploy/docker-compose.dev.yml up -d  # Postgres, Redis, Keycloak
./gradlew :app:bootRun                                 # backend on :8080
pnpm install && pnpm dev                               # frontend on :5173
```

Requirements: JDK 21, Node 22, pnpm 10, Docker.

### Optional: the `alinha` CLI

[`alinha-cli`](https://github.com/PEI-aLinha/alinha-cli) wraps these steps and shows the state of every plugin. It needs [uv](https://docs.astral.sh/uv/).

```bash
uv tool install git+ssh://git@github.com/PEI-aLinha/alinha-cli.git@v0.1.0

alinha doctor          # check tools, submodules, ports and dev secrets
alinha plugins status  # branch, local changes and pointer drift per plugin
alinha dev             # start dependencies, backend and frontend
alinha down            # stop the containers
```

The supported CLI versions are pinned in `.alinha-cli-version`.

## Working on a plugin

Always work **inside a checkout of the main repo** — a plugin repo does not build on its own
(it needs the `plugin-api`, the `plugin-sdk` and `ui`).

```bash
cd plugins/feature/assessment
git switch -c feat/roadmap            # branch IN THE PLUGIN REPO
# ... code ...
git commit -am "Roadmap: initial generation"
git push -u origin feat/roadmap       # PR in alinha-plugin-assessment
```

After the plugin PR is merged, the pointer in the main repo is updated by **Dependabot** (daily PR)
or by hand:

```bash
git submodule update --remote plugins/feature/assessment
git commit -am "plugins: update assessment"
```

## Changing the `plugin-api` or a contract

This is the only change that touches several repos at once. In this order:

1. Branch in the main repo with the change to `plugin-api` / `contracts/`.
2. A branch with the same name in every affected plugin, already adapted.
3. On the main repo branch, point the submodules at those branches and open the PR — CI tests the whole set.
4. Merge the plugin PRs.
5. In the main repo PR: `git submodule update --remote` (pointers to each plugin's `main`) → CI green → merge.

Agree on this in the weekly sync; never do it right before a deadline.

## Architecture rules (checked in CI)

- **Backend:** a plugin only uses other modules' public types — `pt.alinha.<plugin>` (base), `.api`, `.events` and the core's named interfaces (`core.tenant`, `core.access`, `core.audit`, `core.jobs`, `core.compliance`, `core.aigateway`). Never `internal`. Checked by `ModularityTest`.
- **Frontend:** a plugin only imports `@alinha/plugin-sdk`, `@alinha/ui` and `@alinha/api-client`. Checked by ESLint.
- **Data:** each plugin has its own PostgreSQL schema; migrations in `db/migration/<plugin>/V<yyyyMMddHHmmss>__<plugin>_<description>.sql`.
- **Requests:** tenant comes from the token (`TenantContext`); endpoints have `@RequiresPermission`; writes go to the Audit Trail in the same transaction.

## Non-functional requirements → where they are met

Numbering from the technical report (section 3.2).

| NFR | How | Status |
|---|---|---|
| RNF03 tenant isolation | `core.tenant.TenantContext` (tenant from the token); filtered queries | contract defined, implement in sprint 0 |
| RNF04 audit trail | `core.audit.AuditTrail`, same transaction; `audit.audit_event` table INSERT-only | same |
| RNF07/RNF08 OIDC + roles per endpoint | Keycloak + `@RequiresPermission` | same |
| RNF12/RNF14 AI approval and provenance | `core.aigateway.AiDraft` (model, prompt version, sources, date) | contract defined |
| RNF17 new plugin without touching the core | `settings.gradle.kts` and `gen-frontend-plugins.mjs` discover the submodules; `ModularityTest` | **done** |
| RNF18 catalogues as data | `frameworks/` read at runtime by the Compliance Engine | see `frameworks/README.md` |
| RNF19 coverage ≥ 70% and CI blocks merge | missing: Kover (Kotlin) + Vitest coverage in CI; blocking merges needs branch protection (GitHub Team) | **to do** |
| RNF21 whole system starts with one command | missing: full `deploy/docker-compose.yml` (Nginx, BFF, app, AI Service, workers, OpenBao) | **to do** |

## New plugin

```bash
./scripts/new-plugin.sh feature policy-evidence "Policy & Evidence"
git push
```

Creates the repo from `alinha-plugin-template`, initialises it and adds it as a submodule.
Gradle and the frontend shell pick it up automatically.

## Sprints (MVP)

| Sprint | Dates | Goal |
|---|---|---|
| 0 | 4–10 Nov | Walking skeleton: green CI, compose, tenant + RBAC + audit, one plugin from UI → BFF → app → DB, `plugin-api` frozen |
| 1 | 11–24 Nov | Organization, Risk & Assets, catalogues, `ComplianceEngine.applicability()` |
| 2 | 25 Nov–8 Dec | Assessment (evaluate, roadmap, `RoadmapValidated`), AI Service, workers + 2 providers, Notifications |
| 3 | 9–15 Dec | Dashboard & Reports, integration, demo |
| — | 16–22 Dec | Stabilisation |
