package pt.alinha.core.access.internal

import pt.alinha.pluginapi.NavArea

/** Realm roles the platform knows, and the shell area each one opens. */
object Roles {
    /**
     * Mirrors the shell's routing (`frontend/shell/src/session/permissions.ts`): consultants work in
     * `/portfolio`, the client's people in `/org/:org`, a new account (`registrant`, ADR 0004) only in `/onboarding`.
     */
    private val AREA: Map<String, NavArea> = mapOf(
        "consultant" to NavArea.CONSULTANT,
        "responsible" to NavArea.CLIENT,
        "analyst" to NavArea.CLIENT,
        "registrant" to NavArea.ONBOARDING,
    )

    val KNOWN: Set<String> = AREA.keys

    fun areasFor(roles: Set<String>): Set<NavArea> = roles.mapNotNull(AREA::get).toSet()
}
