package pt.alinha.bff.session

data class MeResponse(
    val user: MeUser,
    val roles: List<String>,
    val permissions: List<String>,
    val organizations: List<MeOrganization>,
)

data class MeUser(val id: String, val name: String, val email: String?)

data class MeOrganization(val id: String, val slug: String, val name: String)

/** What the application answers on `/internal/me`: the roles it knows and their permissions. */
data class InternalMe(val roles: List<String>, val permissions: List<String>)

data class AppOrganization(val name: String)
