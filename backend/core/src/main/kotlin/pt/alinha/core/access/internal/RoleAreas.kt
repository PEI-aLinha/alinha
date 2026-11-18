package pt.alinha.core.access.internal

import pt.alinha.pluginapi.NavArea

/**
 * Shell areas each realm role opens. Mirrors the shell's routing (`frontend/shell/src/session/permissions.ts`):
 * consultants work in `/portfolio`, the client's people in `/org/:org`, a new account only in `/onboarding`.
 */
object RoleAreas {
    private val BY_ROLE: Map<String, NavArea> = mapOf(
        "consultant" to NavArea.CONSULTANT,
        "responsible" to NavArea.CLIENT,
        "analyst" to NavArea.CLIENT,
        "registrant" to NavArea.ONBOARDING,
    )

    fun areasFor(roles: Set<String>): Set<NavArea> = roles.mapNotNull(BY_ROLE::get).toSet()
}
