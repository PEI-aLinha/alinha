package pt.alinha.core.access.internal

import pt.alinha.pluginapi.PluginManifest

/**
 * The plugin manifests after the Module Registry checked them at startup. Access control reads permissions
 * through this interface, so `core.access` never depends on `core.registry` (the registry depends on access
 * for roles and core permissions, not the other way round).
 */
interface ValidatedManifests {
    val manifests: List<PluginManifest>
}

/** Every permission that exists: declared by a plugin or owned by the core. */
internal fun knownPermissions(manifests: List<PluginManifest>): Set<String> =
    manifests.flatMap { it.permissions }.toSet() + CorePermissions.ALL

/** Permissions each known role is granted: from the plugin manifests plus the core's own. */
internal fun permissionsByRole(manifests: List<PluginManifest>): Map<String, Set<String>> {
    val fromPlugins = manifests.flatMap { it.rolePermissions.entries }.groupBy({ it.key }, { it.value })
    return Roles.KNOWN.associateWith { role ->
        fromPlugins[role].orEmpty().flatten().toSet() + CorePermissions.EVERY_ROLE + CorePermissions.BY_ROLE[role].orEmpty()
    }
}
