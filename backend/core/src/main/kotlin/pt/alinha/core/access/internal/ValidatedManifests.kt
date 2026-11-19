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
