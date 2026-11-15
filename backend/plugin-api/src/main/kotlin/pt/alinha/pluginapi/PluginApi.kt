package pt.alinha.pluginapi

import java.time.Instant
import java.util.UUID

/** Plugin kind, as in the architecture diagram. */
enum class PluginKind { FEATURE, FRAMEWORK, PROVIDER }

/**
 * Plugin manifest. Mirrors the plugin repo's `plugin.yaml` and is registered
 * in the core's Module Registry at startup.
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val kind: PluginKind,
    val version: String,
    /** Names of the events the plugin publishes (e.g. "AssessmentSubmitted"). */
    val publishes: Set<String> = emptySet(),
    /** Names of the events the plugin consumes. */
    val consumes: Set<String> = emptySet(),
    /** RBAC resources the plugin declares (e.g. "assessment:read"). */
    val permissions: Set<String> = emptySet(),
    /** Permissions granted to each realm role; every value must be listed in [permissions]. */
    val rolePermissions: Map<String, Set<String>> = emptyMap(),
)

/** Every plugin exposes exactly one bean implementing this interface. */
interface AlinhaPlugin {
    val manifest: PluginManifest
}

@JvmInline
value class TenantId(val value: UUID)

/**
 * Domain event. Published with Spring's ApplicationEventPublisher and
 * persisted by Spring Modulith (event publication registry).
 * Plugins talk to each other only through events and «api» contracts.
 */
interface DomainEvent {
    val tenantId: TenantId
    val occurredAt: Instant
}
