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
├── deploy/               docker-compose (dev and production), Dockerfiles, Keycloak, OpenBao
└── docs/adr/
```

## Language

Code, comments, docs, commit messages and URL paths are in **English**.
Everything else the user sees in the UI (labels, titles, messages) is in **Portuguese**, in plain language
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
cp deploy/.env.example deploy/.env                     # optional: dev defaults work without it
pnpm install && pnpm --filter @alinha/keycloak-theme build-keycloak-theme  # aLinha login pages (needs Maven)
docker compose -f deploy/docker-compose.dev.yml up -d  # Postgres, Redis, Keycloak, Mailpit, OpenBao
# Existing volume from before #10? See docs/audit.md (DB role alinha_app needs LOGIN)
BAO_TOKEN=dev-app-token SPRING_PROFILES_ACTIVE=demo ./gradlew :app:bootRun  # backend on :8080, with demo organizations
SPRING_PROFILES_ACTIVE=dev ./gradlew :bff:bootRun       # BFF on :8081 (OIDC login, session)
pnpm install && pnpm dev                               # frontend on :5173
```

| Service | URL | Dev credentials |
|---|---|---|
| Postgres | `localhost:5432` | `alinha` / `alinha` |
| Redis | `localhost:6379` | — |
| Keycloak | http://localhost:8180 | admin / admin |
| Mailpit | http://localhost:8025 | — |
| OpenBao | http://localhost:8200 | `BAO_TOKEN=dev-app-token` (root `dev-root-token`) |

The `alinha` realm, test users (Rui, Pedro, Marta, João) and their roles/tenants are in
[deploy/keycloak/README.md](deploy/keycloak/README.md). The dev password is `alinha-dev-1`; the first
login sets up TOTP (MFA). Emails land in Mailpit.

SMTP for the notifications plugin defaults to Mailpit. For a real relay set `SMTP_HOST`, `SMTP_PORT` (587 with STARTTLS),
`SMTP_USERNAME`, `SMTP_AUTH=true` and `SMTP_STARTTLS=true` (the last also makes STARTTLS mandatory). The password is not
an env var: it is `smtp.password` in OpenBao (`secret/alinha/app`), seeded with a placeholder in `deploy/openbao/init.sh`.

Requirements: JDK 21, Node 22, pnpm 10, Docker (Compose v2), Maven (only to build the Keycloak theme). The flow above is verified on Linux;
on macOS use Docker Desktop and the same commands.

`pnpm test` needs the plugin submodules (it checks that every plugin frontend is registered). If you cloned without
`--recurse-submodules`, run `git submodule update --init --recursive` first.

The shell logs in through the BFF (`/oauth2/authorization/keycloak`); in dev, Vite proxies `/api`, `/oauth2` and
`/auth` to `localhost:8081`. Without the BFF you can open it with a fake session: choose a role
(`consultant`, `responsible`, `analyst`, or `anonymous` to see the login redirect). This only affects `pnpm dev`, never a build:

```bash
VITE_DEV_ROLE=consultant pnpm dev
```

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

> **`FRAMEWORKS_DIR`** — the backend resolves framework catalogues from this path.
> The default (`../../frameworks`) works when running from `backend/app/` (i.e. via `./gradlew :app:bootRun`).
> In a container, set `FRAMEWORKS_DIR` to the absolute path of the mounted `frameworks/` folder.

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
- **Frontend:** a plugin only imports `@alinha/plugin-sdk`, `@alinha/ui` and `@alinha/api-client`, and never reaches outside its own `frontend/` folder. Checked by the ESLint rule `alinha/plugin-boundaries` (`eslint-rules/`). Data fetching, error messages, pt-PT formatting and UI wording: `docs/ui-text.md`.
- **Data:** each plugin has its own PostgreSQL schema; migrations in `db/migration/<plugin>/V<yyyyMMddHHmmss>__<plugin>_<description>.sql`.
- **Requests:** tenant comes from the token (`TenantContext`, rules in `docs/tenancy.md`); endpoints have `@RequiresPermission`; writes go to the Audit Trail in the same transaction.

## Non-functional requirements → where they are met

Numbering from the technical report (section 3.2).

| NFR | How | Status |
|---|---|---|
| RNF02 no job lost on failure | `core.jobs.JobDispatcher` writes to `core.job_outbox` in the caller's transaction; `OutboxRelay` publishes to Redis Streams (`SKIP LOCKED`, backoff, at-least-once); `JobResultConsumer` applies worker results (`JobCompleted`/`JobFailed`, retries, dead letters); see `docs/jobs.md` | **done** (watchdog #86, retention and alerts #87) |
| RNF03 tenant isolation | `core.tenant.TenantContext` (tenant from the token, consultant picks with `X-Alinha-Tenant`); entities extend `TenantOwnedEntity` (Hibernate `@TenantId`); see `docs/tenancy.md` | **done** in the backend (Keycloak claims pending #5); the BFF relays the header |
| RNF04 audit trail | `core.audit.AuditTrail` in the caller's transaction; app role `alinha_app` INSERT/SELECT only + trigger blocking UPDATE/DELETE/TRUNCATE; history `GET /api/audit/events` (see `docs/audit.md`) | **done** |
| RNF07/RNF08 OIDC + roles per endpoint | Keycloak JWTs + `@RequiresPermission`, deny by default; the BFF owns the login and relays tokens; see `docs/rbac.md` | **done** in the backend (not yet tested against a real Keycloak realm, see #5) |
| RNF09 no secrets in code/config | OpenBao (`secret/alinha/app` via Spring Cloud Vault); AI Service reads `secret/alinha/ai-service` (policy `alinha-ai-service`) via `hvac`; per-tenant provider credentials via `pluginapi.TenantSecretStore`; gitleaks job in CI | **done** |
| RNF11 AI Service cannot write platform data | AI Service has no database access and only returns drafts; `/v1` requires a service token shared with the AI Gateway (`contracts/ai/ai-service.v1.yaml`) | AI Service side **done**; AI Gateway client to do |
| RNF12/RNF14 AI approval and provenance | `core.aigateway.AiDraft` and `contracts/ai` (model, prompt version, sources, date); `AiDraft.provenance()` stored in the audit trail; AI Service fills model, prompt version and date, `sources` empty until RAG | provenance recorded; approval to do |
| RNF13 context redacted before leaving the core | redaction in `core.aigateway` before calling the AI Service; the AI Service treats context as untrusted data | **to do** (core) |
| LLM processes data in the EU | `EU_LLM_HOSTS` allowlist in `ai-service/src/ai_service/config.py`, checked at startup | **done** |
| RNF17 new plugin without touching the core | `settings.gradle.kts` and `gen-frontend-plugins.mjs` discover the submodules; `ModularityTest`; `PluginRegistry` validates the manifests at startup and serves `GET /api/plugins`; `PluginYamlConsistencyTest` | **done** |
| RNF18 catalogues as data | `frameworks/` read from disk at start-up by `core.compliance.CatalogueRegistry` (`CatalogueLoader`); schema in `contracts/frameworks/`; released versions frozen by `scripts/check-immutable-frameworks.sh` | **done** for loading and versioning; see `frameworks/README.md` |
| RNF19 coverage ≥ 70% and CI blocks merge | `./gradlew koverVerify` (Kotlin, core modules only; plugin backends are not counted yet), Vitest and pytest fail CI below 70%; reports go to SonarCloud (`sonar-project.properties`, job `sonar`). Missing: branch protection/rulesets, applied in #4 | partial |
| RNF21 whole system starts with one command | `deploy/docker-compose.prod.yml` (Nginx, BFF, app, Keycloak, OpenBao, Postgres, Redis, job consumer): `make staging` locally, `deploy.yml` on the VM; see `deploy/DEPLOYMENT.md` | **done** except the AI Service (not implemented yet) |

## Deploy

Releases go to https://pei-alinha.duckdns.org: a `vX.Y.Z` release publishes the images to GHCR (`publish.yml`) and
**Deploy to Production VM** (`deploy.yml`) runs them on the VM's self-hosted runner. Details, secrets and the one-time VM setup: [`deploy/DEPLOYMENT.md`](deploy/DEPLOYMENT.md).

| Command | Description |
|---|---|
| `make staging-env` | Create `deploy/.env.staging` with random secrets |
| `make staging-build` | Build the production images from this checkout |
| `make staging` | Start the production stack locally on http://localhost:8088 |
| `make staging-down` / `make staging-clean` | Stop it (`-clean` also deletes the volumes) |
| Actions → **Get Production Logs** | Production: save every container's logs on the VM (`deploy/DEPLOYMENT.md`) |

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
| 1 | 11–24 Nov | Organization, Risk & Assets, catalogues, applicability rules (`ApplicabilityRules`, `docs/applicability.md`) |
| 2 | 25 Nov–8 Dec | Assessment (evaluate, roadmap, `RoadmapValidated`), AI Service, workers + 2 providers, Notifications |
| 3 | 9–15 Dec | Dashboard & Reports, integration, demo |
| — | 16–22 Dec | Stabilisation |
