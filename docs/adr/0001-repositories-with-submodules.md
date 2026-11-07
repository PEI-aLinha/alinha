# ADR 0001 — One repository per plugin, linked as submodules

- **Status:** accepted
- **Date:** 2026-11-07

## Context
aLinha is a single application (Spring Boot + Spring Modulith) with a core and
Feature/Framework/Provider plugins. The team wants clear ownership of each plugin and separate history.

## Decision
- Main repo `alinha`: core, `plugin-api`, frontend shell, BFF, AI Service, workers, contracts, deployment.
- One repo per Feature/Provider plugin (`alinha-plugin-<id>`, `alinha-provider-<id>`) holding the plugin's **backend and frontend**.
- The main repo includes the plugins as **git submodules** under `plugins/<kind>/<id>`; Gradle and pnpm discover them automatically.
- Framework plugins (YAML catalogues) stay in the main repo.
- Everything is built into a single deployable; plugins are not loaded at runtime.

## Consequences
- (+) Per-plugin ownership and history; small, focused PRs.
- (+) Boundaries are still verified by Spring Modulith and by lint on the frontend.
- (−) A plugin repo does not build on its own: development happens inside a checkout of the main repo.
- (−) Changes to the `plugin-api` require coordinated PRs (procedure in the README).
- (−) Submodule pointers must be kept up to date (Dependabot `gitsubmodule`).

## Alternatives considered
- **Monorepo:** less coordination, but no separate repo per plugin.
- **Plugins published as jars and loaded at runtime:** incompatible with Spring Modulith (transaction shared with the Audit Trail, JPA, verification) and no third parties to justify it. A possible evolution after PEI.
