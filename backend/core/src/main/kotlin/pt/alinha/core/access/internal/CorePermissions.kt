package pt.alinha.core.access.internal

/** Permissions the core itself owns, on top of what the plugin manifests declare. */
object CorePermissions {
    /** Granted to every known role. */
    val EVERY_ROLE: Set<String> = setOf("plugins:read")

    // Analysts excluded: least privilege, same rule as results/roadmap (docs/rbac.md).
    val BY_ROLE: Map<String, Set<String>> = mapOf(
        "consultant" to setOf("audit:read"),
        "responsible" to setOf("audit:read"),
    )

    val ALL: Set<String> = EVERY_ROLE + BY_ROLE.values.flatten()
}
