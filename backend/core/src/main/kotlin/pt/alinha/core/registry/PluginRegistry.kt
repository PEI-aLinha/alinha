package pt.alinha.core.registry

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import pt.alinha.core.access.RequiresPermission
import pt.alinha.core.tenant.TenantAgnostic
import pt.alinha.pluginapi.AlinhaPlugin
import pt.alinha.pluginapi.PluginManifest

/** Module Registry: knows every plugin present in the build. */
@Component
class PluginRegistry(plugins: List<AlinhaPlugin>) {
    private val log = LoggerFactory.getLogger(javaClass)

    val manifests: List<PluginManifest> = plugins.map { it.manifest }.sortedBy { it.id }

    init {
        val duplicated = manifests.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "Duplicate plugin ids: $duplicated" }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun logPlugins() {
        log.info("aLinha started with {} plugins: {}", manifests.size, manifests.joinToString { "${it.id}@${it.version}" })
    }
}

@RestController
@TenantAgnostic
class PluginRegistryController(private val registry: PluginRegistry) {
    @RequiresPermission("plugins")
    @GetMapping("/api/plugins")
    fun list(): List<PluginManifest> = registry.manifests
}
