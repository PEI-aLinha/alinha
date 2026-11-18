package pt.alinha.core.registry

import pt.alinha.core.access.internal.CorePermissions
import pt.alinha.core.access.internal.Roles
import pt.alinha.pluginapi.PluginManifest

private val PLUGIN_ID = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
private val PERMISSION = Regex("^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$")
private val SEGMENT = "[a-z0-9]+(-[a-z0-9]+)*"
/** Relative, lowercase kebab-case segments: no leading slash, no "..", no spaces. */
private val NAV_PATH = Regex("^$SEGMENT(/$SEGMENT)*$")

/**
 * Checks the manifests as a whole and throws [IllegalArgumentException] on the first broken rule,
 * so a bad plugin stops the application at startup instead of failing at request time.
 */
internal fun validateManifests(manifests: List<PluginManifest>) {
    val duplicated = manifests.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(duplicated.isEmpty()) { "Duplicate plugin ids: $duplicated" }

    manifests.forEach(::validateOwnFields)

    val known = knownPermissions(manifests)
    manifests.forEach { manifest ->
        val unknown = manifest.nav.mapNotNull { it.permission }.toSet() - known
        require(unknown.isEmpty()) { "Plugin ${manifest.id} shows menu entries behind unknown permissions: $unknown" }
    }

    val clashes = manifests
        .flatMap { m -> m.nav.map { (it.area to it.path) to m.id } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size > 1 }
    require(clashes.isEmpty()) {
        "Menu entries share an area and path: " +
            clashes.entries.joinToString { (key, ids) -> "${key.first.name.lowercase()}/${key.second} in $ids" }
    }
}

/** Every permission that exists: declared by a plugin or owned by the core. */
internal fun knownPermissions(manifests: List<PluginManifest>): Set<String> =
    manifests.flatMap { it.permissions }.toSet() + CorePermissions.ALL

private fun validateOwnFields(manifest: PluginManifest) {
    require(PLUGIN_ID.matches(manifest.id)) { "Plugin id '${manifest.id}' must be kebab-case" }
    val malformed = manifest.permissions.filterNot(PERMISSION::matches)
    require(malformed.isEmpty()) { "Plugin ${manifest.id} declares permissions not in resource:action form: $malformed" }
    val unknownRoles = manifest.rolePermissions.keys - Roles.KNOWN
    require(unknownRoles.isEmpty()) { "Plugin ${manifest.id} maps unknown roles: $unknownRoles" }
    val undeclared = manifest.rolePermissions.values.flatten().toSet() - manifest.permissions
    require(undeclared.isEmpty()) { "Plugin ${manifest.id} maps undeclared permissions: $undeclared" }
    manifest.nav.forEach {
        require(it.label.isNotBlank()) { "Plugin ${manifest.id} has a menu entry without label: $it" }
        require(NAV_PATH.matches(it.path)) {
            "Plugin ${manifest.id} has menu path '${it.path}': use relative kebab-case segments, e.g. 'risks/assets'"
        }
    }
}
