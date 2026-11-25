# Domain events

Plugins talk to each other asynchronously through domain events, and synchronously only through `api/`.
This page lists every event, who publishes it and who consumes it.

## How an event works

- An event is a Kotlin `data class` in the publishing plugin's `events` package that implements
  `pt.alinha.pluginapi.DomainEvent`: `tenantId`, `correlationId` and `occurredAt`.
- Publish it with Spring's `ApplicationEventPublisher` **inside the transaction** that made the change.
  Transactional listeners (the recipe in `docs/tenancy.md`) run only after that transaction commits: published
  outside one, the event never reaches them.
- The Spring Modulith event publication registry stores every publication in `public.event_publication`
  (migration `V20261116090100`) and marks it complete when its listener finishes. A listener that fails leaves
  its publication **incomplete, and nothing retries it yet**: `republish-outstanding-events-on-restart` is off.
  Log the failure. **Listeners must be idempotent** anyway, so retries can be turned on later.
- Events are stored in plain text. Put **ids and status values only** in them (user ids included): no names,
  emails or other personal data, no secrets, no document content. A consumer that needs more reads it through
  the publisher's `api/`.
- An event's fields are a contract between plugins. Do not remove or rename a field; add a new event
  (`...V2`) and keep the old one until every consumer has moved.

## Correlation id

`correlationId` links the request that started some work to every event that follows from it, so the whole
chain can be traced in the logs (`[correlation-id]` in each log line).

- The core gives every HTTP request an id (`X-Correlation-Id`: kept if the caller sends a UUID, otherwise
  created, and always returned in the response).
- When you create an event, take the id from `CorrelationContext.current()` (`core.tenant`).
- In a listener, wrap the work in `tenantContext.runAs(event) { ... }`: it sets the event's tenant **and**
  its correlation id, so the events you publish there continue the same chain. See `docs/tenancy.md`, rule 4.
- A job without an event uses `runAs(tenantId) { ... }`, which gives the whole block one new id.
- It is for tracing only. Never use it for access decisions: a caller can choose it, so two requests may
  share an id on purpose.
- Not covered: async MVC dispatches (`DeferredResult`, SSE) and the `/error` page run after the request's scope,
  so their log lines have no id (the response header still has it).

```kotlin
data class RiskRegistered(
    override val tenantId: TenantId,
    override val correlationId: UUID,
    val riskId: UUID,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent

// in a @Transactional service method
events.publishEvent(RiskRegistered(tenants.current(), correlation.current(), risk.id))
```

## Catalogue

Fields are proposed: the publishing plugin's issue settles them. Every domain event also has `tenantId`,
`correlationId` and `occurredAt`. Issues are in the plugin's own repository (e.g. organization #2 is
`PEI-aLinha/alinha-plugin-organization#2`).

| Event | Published by | Consumed by | Fields | Issue |
|---|---|---|---|---|
| `OrganizationRegistered` | organization | organization (automatic consultant assignment) | `organizationId` | organization #2, #5 |
| `ConsultantAssigned` | organization | dashboard-reports, notifications ("new client assigned") | `organizationId`, `consultantId` | organization #5, notifications #3 |
| `TaskAssigned` | organization | notifications (assignment e-mail) | `taskId`, `type`, `reference`, `assigneeId`, `dueDate` | organization #7, notifications #3 |
| `TaskCompleted` | organization | none yet | `taskId`, `type`, `reference`, `assigneeId` | organization #7 |
| `AssetRegistered` | risk-assets | none yet | `assetId` | risk-assets #2 |
| `RiskRegistered` | risk-assets | dashboard-reports | `riskId` | risk-assets #4, dashboard-reports #2 |
| `AssessmentSubmitted` | assessment | notifications (warns the consultant) | `assessmentId` | assessment #5, notifications #4 |
| `AssessmentEvaluated` | assessment | dashboard-reports | `assessmentId`, `framework`, `catalogueVersion` | assessment #5, dashboard-reports #2 |
| `RoadmapValidated` | assessment | dashboard-reports, notifications (warns the Designated Officer) | `assessmentId`, `roadmapId`, `validatedBy` | assessment #8, notifications #4 |
| `EvidenceCollected` | code-repository, external-surface | assessment (verifies or pre-fills answers), dashboard-reports | `provider`, `evidenceId`, `controlIds` | code-repository #1, external-surface #1, assessment #10 |
| `JobCompleted` / `JobFailed` | core (`core.jobs`) | the plugin that dispatched the job (filter by `requestedBy`) | `jobId`, `type`, `requestedBy`, `attempt` (+ `error` on `JobFailed`) | alinha #13 |
| `CatalogueVersionPublished` | core (`core.compliance`) | none yet | `framework`, `version`, `title` | alinha #21 |

`RoadmapValidated` is also the consultant's approval of the AI-drafted roadmap: nothing AI-generated reaches the
client before it (see `CLAUDE.md` > "AI drafts, humans decide").

### Global events

`CatalogueVersionPublished` is **not** a `DomainEvent`: a catalogue applies to every tenant, so it has no
`tenantId` or `correlationId`. It is published on every startup, once per loaded catalogue version, so its
consumers must be idempotent. Do not call `runAs` in its listeners.

## Declaring events in the plugin

Each plugin lists the names of the events it publishes and consumes in its `PluginManifest`
(`publishes`, `consumes`). Update the manifest in the same PR that adds the publisher or the listener, and keep
this page in sync.
