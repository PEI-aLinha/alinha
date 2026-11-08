package pt.alinha.core.access.internal

import org.springframework.stereotype.Component
import pt.alinha.core.registry.PluginRegistry
import pt.alinha.pluginapi.PluginManifest

private val KNOWN_ROLES = setOf("consultant", "responsible", "analyst")
private val CORE_PERMISSIONS = setOf("plugins:read")

/** Role -> permission map built from the plugin manifests. Invalid manifests stop the application at startup. */
@Component
class RolePermissionRegistry(plugins: PluginRegistry) {
    private val byRole: Map<String, Set<String>> = plugins.manifests
        .onEach(::validate)
        .flatMap { it.rolePermissions.entries }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, permissions) -> permissions.flatten().toSet() }

    fun permissionsFor(roles: Set<String>): Set<String> =
        roles.filter { it in KNOWN_ROLES }
            .flatMap { byRole[it].orEmpty() + CORE_PERMISSIONS }
            .toSet()

    private fun validate(manifest: PluginManifest) {
        val unknownRoles = manifest.rolePermissions.keys - KNOWN_ROLES
        require(unknownRoles.isEmpty()) { "Plugin ${manifest.id} maps unknown roles: $unknownRoles" }
        val undeclared = manifest.rolePermissions.values.flatten().toSet() - manifest.permissions
        require(undeclared.isEmpty()) { "Plugin ${manifest.id} maps undeclared permissions: $undeclared" }
    }
}
