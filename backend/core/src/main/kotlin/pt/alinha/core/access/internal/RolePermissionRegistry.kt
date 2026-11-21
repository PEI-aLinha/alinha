package pt.alinha.core.access.internal

import org.springframework.stereotype.Component
import pt.alinha.pluginapi.NavArea

/** Role -> permission map built from the plugin manifests, already validated by the Module Registry. */
@Component
class RolePermissionRegistry(plugins: ValidatedManifests) {
    /** Every permission that exists: declared by a plugin or owned by the core. */
    val knownPermissions: Set<String> = knownPermissions(plugins.manifests)

    private val byRole: Map<String, Set<String>> = permissionsByRole(plugins.manifests)

    /** Every permission at least one role holds. Startup validation makes this every known permission. */
    val grantedPermissions: Set<String> = byRole.values.flatten().toSet()

    fun permissionsFor(roles: Set<String>): Set<String> = roles.flatMap { byRole[it].orEmpty() }.toSet()

    /** Permissions of the caller of the current request. */
    fun permissionsOfCurrentUser(): Set<String> = permissionsFor(currentRoles())

    /** Shell areas the caller of the current request may open. */
    fun areasOfCurrentUser(): Set<NavArea> = Roles.areasFor(currentRoles())
}
