package pt.alinha.core.registry

import com.fasterxml.jackson.annotation.JsonInclude
import org.slf4j.LoggerFactory
import java.text.Collator
import java.util.Locale
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import pt.alinha.core.access.RequiresPermission
import pt.alinha.core.access.internal.RolePermissionRegistry
import pt.alinha.core.tenant.TenantAgnostic
import pt.alinha.pluginapi.AlinhaPlugin
import pt.alinha.pluginapi.NavArea
import pt.alinha.pluginapi.NavEntry
import pt.alinha.pluginapi.PluginKind
import pt.alinha.pluginapi.PluginManifest

/** Module Registry: knows every plugin present in the build. */
@Component
class PluginRegistry(plugins: List<AlinhaPlugin>) {
    private val log = LoggerFactory.getLogger(javaClass)

    val manifests: List<PluginManifest> = plugins.map { it.manifest }.sortedBy { it.id }

    init {
        validateManifests(manifests)
    }

    @EventListener(ApplicationReadyEvent::class)
    fun logPlugins() {
        log.info("aLinha started with {} plugins: {}", manifests.size, manifests.joinToString { "${it.id}@${it.version}" })
    }
}

@RestController
@TenantAgnostic
class PluginRegistryController(
    private val registry: PluginRegistry,
    private val permissions: RolePermissionRegistry,
) {
    /**
     * Every plugin, with only the menu entries the caller may see: in an area the caller's roles open, and
     * behind a permission the caller holds. Role mappings stay on the server.
     */
    @RequiresPermission("plugins")
    @GetMapping("/api/plugins")
    fun list(): List<PluginView> {
        val granted = permissions.permissionsOfCurrentUser()
        val areas = permissions.areasOfCurrentUser()
        return registry.manifests.map { PluginView.of(it, granted, areas) }
    }
}

/** `PluginManifest` in the OpenAPI contract. */
data class PluginView(
    val id: String,
    val name: String,
    val kind: PluginKind,
    val version: String,
    val publishes: Set<String>,
    val consumes: Set<String>,
    val permissions: Set<String>,
    val nav: List<NavView>,
) {
    companion object {
        fun of(manifest: PluginManifest, granted: Set<String>, areas: Set<NavArea>) = PluginView(
            id = manifest.id,
            name = manifest.name,
            kind = manifest.kind,
            version = manifest.version,
            publishes = manifest.publishes,
            consumes = manifest.consumes,
            permissions = manifest.permissions,
            nav = manifest.nav
                .filter { entry -> entry.area in areas && entry.permission?.let { it in granted } ?: true }
                .sortedWith(compareBy<NavEntry>({ it.area }, { it.order }).thenBy(PT_COLLATOR) { it.label })
                .map(NavView::of),
        )
    }
}

/** Same label order as the shell's `localeCompare(…, 'pt')`. */
private val PT_COLLATOR: Collator = Collator.getInstance(Locale.forLanguageTag("pt-PT"))

/** `NavEntry` in the OpenAPI contract: the fields of `NavItem` in `@alinha/plugin-sdk`, area in lowercase. */
data class NavView(
    val label: String,
    val path: String,
    val area: String,
    /** Left out of the JSON when null: the contract makes it optional, not nullable. */
    @field:JsonInclude(JsonInclude.Include.NON_NULL) val resource: String?,
    val action: String,
    val order: Int,
) {
    companion object {
        fun of(entry: NavEntry) =
            NavView(entry.label, entry.path, entry.area.name.lowercase(), entry.resource, entry.action, entry.order)
    }
}
