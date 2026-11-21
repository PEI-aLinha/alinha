package pt.alinha.core.registry

import pt.alinha.core.access.internal.Roles
import pt.alinha.core.access.internal.knownPermissions
import pt.alinha.core.access.internal.permissionsByRole
import pt.alinha.pluginapi.NavArea
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
    validateReachability(manifests)

    val clashes = manifests
        .flatMap { m -> m.nav.map { (it.area to it.path) to m.id } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size > 1 }
    require(clashes.isEmpty()) {
        "Menu entries share an area and path: " +
            clashes.entries.joinToString { (key, ids) -> "${key.first.name.lowercase()}/${key.second} in $ids" }
    }
}

/**
 * Every declared permission is granted to some role, and every menu entry is visible to some role that opens its
 * area. Otherwise the endpoints behind that permission answer 403 to everyone and the entry is never shown.
 */
private fun validateReachability(manifests: List<PluginManifest>) {
    val byRole = permissionsByRole(manifests)
    val granted = byRole.values.flatten().toSet()
    manifests.forEach { manifest ->
        val ungranted = manifest.permissions - granted
        require(ungranted.isEmpty()) {
            "Plugin ${manifest.id} declares permissions no role is granted (add them to rolePermissions): $ungranted"
        }
        manifest.nav.forEach { entry ->
            val roles = Roles.opening(entry.area)
            val visible = entry.permission?.let { needed -> roles.any { needed in byRole[it].orEmpty() } } ?: roles.isNotEmpty()
            require(visible) {
                "Plugin ${manifest.id} has menu entry '${entry.path}' behind ${entry.permission}, but no role that opens " +
                    "the ${entry.area.name.lowercase()} area holds it"
            }
        }
    }
}

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
        require(it.area != NavArea.ONBOARDING) {
            "Plugin ${manifest.id} has menu entry '${it.path}' in the onboarding area, which has no menu"
        }
        require(NAV_PATH.matches(it.path)) {
            "Plugin ${manifest.id} has menu path '${it.path}': use relative kebab-case segments, e.g. 'risks/assets'"
        }
    }
}
