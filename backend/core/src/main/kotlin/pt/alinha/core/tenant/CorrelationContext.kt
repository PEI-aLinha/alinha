package pt.alinha.core.tenant

import java.util.UUID

/**
 * Correlation id of the current work: shared by an HTTP request and every
 * [pt.alinha.pluginapi.DomainEvent] that follows from it, so the chain can be traced in the logs.
 * For tracing only: never use it for access decisions.
 */
interface CorrelationContext {
    /**
     * The id of the current request; inside [TenantContext.runAs], the event's id, or one id for the whole block
     * when it was given a tenant only (jobs). Anywhere else (start-up) every call returns a new id.
     */
    fun current(): UUID
}
