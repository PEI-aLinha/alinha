package pt.alinha.core.access.internal

import org.springframework.stereotype.Component
import pt.alinha.pluginapi.NavArea

/** Role -> permission map built from the plugin manifests, already validated by the Module Registry. */
@Component
class RolePermissionRegistry(plugins: ValidatedManifests) {
    /** Every permission that exists: declared by a plugin or owned by the core. */
    val knownPermissions: Set<String> = knownPermissions(plugins.manifests)

    private val byRole: Map<String, Set<String>> = plugins.manifests
        .flatMap { it.rolePermissions.entries }
        .groupBy({ it.key }, { it.value })
        .mapValues { (_, permissions) -> permissions.flatten().toSet() }

    fun permissionsFor(roles: Set<String>): Set<String> =
        roles.filter { it in Roles.KNOWN }
            .flatMap { byRole[it].orEmpty() + CorePermissions.EVERY_ROLE + CorePermissions.BY_ROLE[it].orEmpty() }
            .toSet()

    /** Permissions of the caller of the current request. */
    fun permissionsOfCurrentUser(): Set<String> = permissionsFor(currentRoles())

    /** Shell areas the caller of the current request may open. */
    fun areasOfCurrentUser(): Set<NavArea> = Roles.areasFor(currentRoles())
}
