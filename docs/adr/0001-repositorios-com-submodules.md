# ADR 0001 — Um repositório por plugin, ligados por submodules

- **Estado:** aceite
- **Data:** 2026-11-07

## Contexto
A aLinha é uma aplicação única (Spring Boot + Spring Modulith) com Core e plugins
Feature/Framework/Provider. A equipa quer posse clara de cada plugin e histórico separado.

## Decisão
- Repo principal `alinha`: Core, `plugin-api`, shell do frontend, BFF, AI Service, workers, contratos, deploy.
- Um repo por Feature/Provider plugin (`alinha-plugin-<id>`, `alinha-provider-<id>`) com **backend e frontend** do plugin.
- O repo principal inclui os plugins como **git submodules** em `plugins/<tipo>/<id>`; o Gradle e o pnpm descobrem-nos automaticamente.
- Framework plugins (catálogos YAML) ficam no repo principal.
- Tudo é compilado num único deployable; não há carregamento de plugins em runtime.

## Consequências
- (+) Posse e histórico por plugin; PRs pequenos e focados.
- (+) As fronteiras continuam verificadas pelo Spring Modulith e por lint no frontend.
- (−) Um repo de plugin não compila sozinho: desenvolve-se dentro do checkout do repo principal.
- (−) Mudanças na `plugin-api` exigem PRs coordenados (procedimento no README).
- (−) Ponteiros dos submodules têm de ser atualizados (Dependabot `gitsubmodule`).

## Alternativas consideradas
- **Monorepo:** menos coordenação, mas sem repos separados por plugin.
- **Plugins publicados como jars e carregados em runtime:** incompatível com o Spring Modulith (transação partilhada com o Audit Trail, JPA, verificação) e sem terceiros que o justifiquem. Evolução possível pós-PEI.
