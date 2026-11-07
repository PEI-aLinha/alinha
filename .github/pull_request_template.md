## What

## Checklist
- [ ] Touches the `plugin-api` or `contracts/`? → followed the README procedure and linked the affected plugin PRs
- [ ] New endpoints have `@RequiresPermission`
- [ ] Writes are recorded in the Audit Trail
- [ ] Queries are filtered by tenant (via `TenantContext`)
- [ ] Tests pass and `ModularityTest` is green
