## O quê

## Checklist
- [ ] Mudança na `plugin-api` ou em `contracts/`? → segui o procedimento do README e liguei os PRs dos plugins afetados
- [ ] Endpoints novos têm `@RequiresPermission`
- [ ] Escritas registam no Audit Trail
- [ ] Queries filtradas por tenant (via `TenantContext`)
- [ ] Testes a passar e `ModularityTest` verde
