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
    /** Menu entries the shell shows; `GET /api/plugins` returns only those the caller may see. */
    val nav: List<NavEntry> = emptyList(),
)

/**
 * Area of the shell. Mirrors `Area` in `@alinha/plugin-sdk`. [ONBOARDING] has no menu: the registry refuses menu
 * entries there (the frontend's `NavItem` takes `NavArea`, which excludes it).
 */
enum class NavArea { CLIENT, CONSULTANT, ONBOARDING }

/** The action a menu entry needs when none is given. */
const val DEFAULT_NAV_ACTION = "read"

/** One menu entry of a plugin. Same fields and defaults as `NavItem` in `@alinha/plugin-sdk`. */
data class NavEntry(
    /** Text shown in the menu, in European Portuguese. */
    val label: String,
    /** Path relative to the area root, in English kebab-case (e.g. "organization" or "risks/assets"). */
    val path: String,
    val area: NavArea,
    /** RBAC resource needed to see the entry, checked as "resource:action"; null means anyone who can open the area. */
    val resource: String? = null,
    /** Only meaningful with [resource]; without one it must stay the default. */
    val action: String = DEFAULT_NAV_ACTION,
    /** Lower comes first. */
    val order: Int = 100,
) {
    init {
        // An action without a resource would be silently dropped and the entry shown to everyone in the area.
        require(resource != null || action == DEFAULT_NAV_ACTION) {
            "Menu entry '$path' has action '$action' but no resource: set the resource it applies to"
        }
    }

    /** "resource:action" needed to see the entry, or null when anyone in the area may. */
    val permission: String? get() = resource?.let { "$it:$action" }
}

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
 * The list of events, with who publishes and consumes each one, is in docs/events.md.
 */
interface DomainEvent {
    val tenantId: TenantId

    /**
     * Shared by the request that started the work and every event that follows from it, so the whole chain can
     * be traced in the logs. Take it from the core's `CorrelationContext.current()`. For tracing only: never use
     * it for access decisions.
     */
    val correlationId: UUID
    val occurredAt: Instant
}
