# Audit trail (NFR04, NFR14)

Every write on tenant data is recorded in `audit.audit_event`, in the **same transaction** as the write.
If the business write rolls back, so does its audit entry, and the other way round.

## Recording a write in a plugin

Inject `pt.alinha.core.audit.AuditTrail` and call it from the `@Transactional` service method that writes:

```kotlin
@Transactional
fun rename(id: UUID, name: String) {
    val asset = assets.findById(id) ?: throw NotFound()
    asset.name = name
    audit.record("asset.rename", "asset", id.toString(), mapOf("fields" to listOf("name")))
}
```

- `record` outside a transaction throws `IllegalTransactionStateException`.
- Never pass the tenant or the user: the tenant comes from `TenantContext`, the actor from the token
  (`sub`, or `system` for jobs and event listeners running under `TenantContext.runAs`).
- `action` is `<entity>.<verb>` (`assessment.answer`, `roadmap.validate`).
- `details` holds ids and the names of changed fields. **Never** secrets, tokens, document content or
  personal data beyond ids.

## AI-generated content (NFR14)

When the write stores or publishes something the AI drafted, pass its provenance:

```kotlin
audit.record("roadmap.draft", "roadmap", roadmapId, provenance = draft.provenance())
```

The model, prompt version, sources and generation date are stored in their own columns (all four or none).

## Reading the history

- Inside the backend: `pt.alinha.core.audit.AuditHistory.find(filter, pageable)`, current tenant only.
- HTTP: `GET /api/audit/events?entityType=&entityId=&actor=&from=&to=&page=&size=` (`audit:read`,
  consultant and Designated Officer). `from`/`to` are ISO-8601 instants, newest first, `size` at most 200.

## Append-only guarantees

- The app connects as `alinha_app`, which has only `SELECT, INSERT` on `audit.audit_event`.
  Flyway runs as the schema owner (`db.migration-username` in OpenBao).
- A trigger rejects `UPDATE`, `DELETE` and `TRUNCATE` for every role, owner and superuser included.
- Anonymising personal data after one year (NFR15) will run under its own role, never `alinha_app`.

Dev: the `alinha_app` role is created by `deploy/postgres/init/` only on a **fresh** volume. After pulling
this change once: `docker compose -f deploy/docker-compose.dev.yml down -v && docker compose -f deploy/docker-compose.dev.yml up -d`.

## Writes outside the database

Provider credentials (`TenantSecretStore`) live in OpenBao, outside the database transaction. The store records
`tenant_secret.put` / `tenant_secret.delete` (provider only, never the secret) in this order:

1. audit entry (not committed yet), 2. OpenBao call, 3. commit.

- OpenBao fails: the transaction rolls back, nothing changed, nothing recorded.
- The commit fails after OpenBao succeeded: the change is not in the audit trail. The store logs it as `ERROR`
  with tenant, provider and action, so it can be reconciled.
