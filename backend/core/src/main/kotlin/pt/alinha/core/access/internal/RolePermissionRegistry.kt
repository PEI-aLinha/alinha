package pt.alinha.core.access.internal

import org.springframework.stereotype.Component
import pt.alinha.core.registry.PluginRegistry
import pt.alinha.pluginapi.NavArea
import pt.alinha.pluginapi.PluginManifest

// `registrant`: account created through self-registration, no organization yet (ADR 0004).
private val KNOWN_ROLES = setOf("consultant", "responsible", "analyst", "registrant")

/** Role -> permission map built from the plugin manifests. Invalid manifests stop the application at startup. */
@Component
class RolePermissionRegistry(plugins: PluginRegistry) {
    /** Every permission that exists: declared by a plugin or owned by the core. */
    val knownPermissions: Set<String> = plugins.manifests.flatMap { it.permissions }.toSet() + CorePermissions.ALL

    private val byRole: Map<String, Set<String>> = plugins.manifests
        .onEach(::validate)
        .flatMap { it.rolePermissions.entries }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, permissions) -> permissions.flatten().toSet() }

    fun permissionsFor(roles: Set<String>): Set<String> =
        roles.filter { it in KNOWN_ROLES }
            .flatMap { byRole[it].orEmpty() + CorePermissions.EVERY_ROLE + CorePermissions.BY_ROLE[it].orEmpty() }
            .toSet()

    /** Permissions of the caller of the current request. */
    fun permissionsOfCurrentUser(): Set<String> = permissionsFor(currentRoles())

    /** Shell areas the caller of the current request may open. */
    fun areasOfCurrentUser(): Set<NavArea> = RoleAreas.areasFor(currentRoles().filter { it in KNOWN_ROLES }.toSet())

    private fun validate(manifest: PluginManifest) {
        val unknownRoles = manifest.rolePermissions.keys - KNOWN_ROLES
        require(unknownRoles.isEmpty()) { "Plugin ${manifest.id} maps unknown roles: $unknownRoles" }
        val undeclared = manifest.rolePermissions.values.flatten().toSet() - manifest.permissions
        require(undeclared.isEmpty()) { "Plugin ${manifest.id} maps undeclared permissions: $undeclared" }
    }
}
