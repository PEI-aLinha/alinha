# aLinha

CISO-as-a-Service para PME portuguesas (NIS2 · GDPR · AI Act). Projeto PEI — Universidade de Aveiro.

Este é o **repo principal**: Core, `plugin-api`, shell do frontend, BFF, AI Service, workers, contratos e deploy.
Cada Feature/Provider plugin vive no **seu próprio repo** (backend + frontend) e entra aqui como **git submodule**
em `plugins/<tipo>/<id>`. Tudo é compilado num único deployable. Decisão: [ADR 0001](docs/adr/0001-repositorios-com-submodules.md).

```
alinha/
├── backend/
│   ├── plugin-api/       contrato Core ↔ plugins (manifest, eventos, TenantId)
│   ├── core/             tenant, access (RBAC), audit, registry, jobs, compliance, aigateway
│   └── app/              junta Core + plugins; ModularityTest (Spring Modulith verify)
├── frontend/
│   ├── shell/            router, layouts, sessão — monta as rotas e o menu dos plugins
│   └── packages/
│       ├── plugin-sdk/   contrato shell ↔ plugins (FrontendPlugin, useHost)
│       ├── ui/           design system
│       └── api-client/   tipos gerados do OpenAPI
├── plugins/
│   ├── feature/<id>/     → submodule alinha-plugin-<id>
│   └── provider/<id>/    → submodule alinha-provider-<id>
├── frameworks/           catálogos YAML versionados (NIS2/QNRCS, GDPR, AI Act, SOC-CMM)
├── contracts/            JSON Schema / OpenAPI partilhados entre Kotlin, Python e TS
├── bff/  ai-service/  workers/
├── deploy/               docker-compose de desenvolvimento, Keycloak
└── docs/adr/
```

## Plugins e donos

> Distribuição proposta — confirmar em equipa e refletir no CODEOWNERS de cada repo.

| Plugin | Repo | Fase | Dono |
|---|---|---|---|
| Organization | `alinha-plugin-organization` | MVP | Íris |
| Assessment | `alinha-plugin-assessment` | MVP | Afonso |
| Risk & Assets | `alinha-plugin-risk-assets` | MVP | Martim |
| Dashboard & Reports | `alinha-plugin-dashboard-reports` | MVP | Inês |
| Notifications | `alinha-plugin-notifications` | MVP | Xavier |
| Code Repository | `alinha-provider-code-repository` | MVP | Xavier |
| External Surface | `alinha-provider-external-surface` | MVP | Xavier |
| Policy & Evidence | criar com `scripts/new-plugin.sh` | post-MVP | Afonso |
| Review & Approval | idem | post-MVP | Martim |
| Incident Reporting | idem | post-MVP | Íris |
| Identity / Cloud, SIEM | idem | post-MVP | Xavier |

## Começar (uma vez por pessoa)

```bash
git config --global submodule.recurse true             # pull/checkout atualizam os submodules
git config --global push.recurseSubmodules on-demand   # push do principal faz push dos plugins primeiro

git clone --recurse-submodules git@github.com:PEI-aLinha/alinha.git
cd alinha
docker compose -f deploy/docker-compose.dev.yml up -d  # Postgres, Redis, Keycloak
./gradlew :app:bootRun                                 # backend em :8080
pnpm install && pnpm dev                               # frontend em :5173
```

Requisitos: JDK 21, Node 22, pnpm 10, Docker.

## Trabalhar num plugin

Desenvolve-se **sempre dentro do checkout do repo principal** — o repo de um plugin não compila sozinho
(precisa da `plugin-api`, do `plugin-sdk` e do `ui`).

```bash
cd plugins/feature/assessment
git switch -c feat/roadmap            # branch NO REPO DO PLUGIN
# ... código ...
git commit -am "Roadmap: geração inicial"
git push -u origin feat/roadmap       # PR no repo alinha-plugin-assessment
```

Depois do merge no plugin, o ponteiro no repo principal é atualizado pelo **Dependabot** (PR diário)
ou à mão:

```bash
git submodule update --remote plugins/feature/assessment
git commit -am "plugins: atualiza assessment"
```

## Mudar a `plugin-api` ou um contrato

É a única situação que mexe em vários repos ao mesmo tempo. Por esta ordem:

1. Branch no repo principal com a mudança na `plugin-api` / `contracts/`.
2. Branch com o mesmo nome em cada plugin afetado, já adaptado.
3. No branch do principal, apontar os submodules para esses branches e abrir o PR — o CI testa o conjunto.
4. Merge dos PRs dos plugins.
5. No PR do principal: `git submodule update --remote` (ponteiros para o `main` dos plugins) → CI verde → merge.

Combinar isto no sync semanal; não fazer em cima de uma entrega.

## Regras da arquitetura (verificadas no CI)

- **Backend:** um plugin só usa os tipos públicos de outros módulos — `pt.alinha.<plugin>` (base), `.api`, `.events` e as named interfaces do Core (`core.tenant`, `core.access`, `core.audit`, `core.jobs`, `core.compliance`, `core.aigateway`). Nunca `internal`. Verificado pelo `ModularityTest`.
- **Frontend:** um plugin só importa `@alinha/plugin-sdk`, `@alinha/ui` e `@alinha/api-client`. Verificado pelo ESLint.
- **Dados:** cada plugin tem o seu schema PostgreSQL; migrações em `db/migration/<plugin>/V<yyyyMMddHHmm>__<plugin>_<descricao>.sql`.
- **Pedidos:** tenant vem do token (`TenantContext`); endpoints com `@RequiresPermission`; escritas com Audit Trail na mesma transação.

## Plugin novo

```bash
./scripts/new-plugin.sh feature policy-evidence "Policy & Evidence"
git push
```

Cria o repo a partir do `alinha-plugin-template`, inicializa-o e adiciona-o como submodule.
O Gradle e o shell do frontend descobrem-no sozinhos.

## Sprints (MVP)

| Sprint | Datas | Objetivo |
|---|---|---|
| 0 | 4–10 nov | Walking skeleton: CI verde, compose, tenant + RBAC + audit, um plugin de UI → BFF → app → DB, `plugin-api` congelada |
| 1 | 11–24 nov | Organization, Risk & Assets, catálogos, `ComplianceEngine.applicability()` |
| 2 | 25 nov–8 dez | Assessment (evaluate, roadmap, `RoadmapValidated`), AI Service, workers + 2 providers, Notifications |
| 3 | 9–15 dez | Dashboard & Reports, integração, demo |
| — | 16–22 dez | Estabilização |
