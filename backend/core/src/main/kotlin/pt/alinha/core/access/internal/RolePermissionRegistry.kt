package pt.alinha.core.access.internal

import org.springframework.stereotype.Component
import pt.alinha.core.registry.PluginRegistry
import pt.alinha.pluginapi.PluginManifest

private val KNOWN_ROLES = setOf("consultant", "responsible", "analyst")
private val CORE_PERMISSIONS = setOf("plugins:read")
// Analysts excluded: least privilege, same rule as results/roadmap (docs/rbac.md).
private val CORE_ROLE_PERMISSIONS = mapOf(
    "consultant" to setOf("audit:read"),
    "responsible" to setOf("audit:read"),
)

/** Role -> permission map built from the plugin manifests. Invalid manifests stop the application at startup. */
@Component
class RolePermissionRegistry(plugins: PluginRegistry) {
    init {
        plugins.manifests.forEach(::validate)
        requireUniqueOwners(plugins.manifests)
    }

    /** Every permission an endpoint may require: the core's plus each plugin's. */
    val declaredPermissions: Set<String> = CORE_PERMISSIONS + plugins.manifests.flatMap { it.permissions }

    private val byRole: Map<String, Set<String>> = plugins.manifests
        .flatMap { it.rolePermissions.entries }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, permissions) -> permissions.flatten().toSet() }

    fun permissionsFor(roles: Set<String>): Set<String> =
        roles.filter { it in KNOWN_ROLES }
            .flatMap { byRole[it].orEmpty() + CORE_PERMISSIONS + CORE_ROLE_PERMISSIONS[it].orEmpty() }
            .toSet()

    private fun validate(manifest: PluginManifest) {
        val unknownRoles = manifest.rolePermissions.keys - KNOWN_ROLES
        require(unknownRoles.isEmpty()) { "Plugin ${manifest.id} maps unknown roles: $unknownRoles" }
        val undeclared = manifest.rolePermissions.values.flatten().toSet() - manifest.permissions
        require(undeclared.isEmpty()) { "Plugin ${manifest.id} maps undeclared permissions: $undeclared" }
        val reserved = manifest.permissions intersect CORE_PERMISSIONS
        require(reserved.isEmpty()) { "Plugin ${manifest.id} declares core permissions: $reserved" }
    }

    /**
     * A permission belongs to exactly one plugin. Since a plugin may only map permissions it declares,
     * this also stops a plugin from granting another plugin's permissions.
     */
    private fun requireUniqueOwners(manifests: List<PluginManifest>) {
        val shared = manifests
            .flatMap { manifest -> manifest.permissions.map { it to manifest.id } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        require(shared.isEmpty()) { "Permissions declared by more than one plugin: $shared" }
    }
}
