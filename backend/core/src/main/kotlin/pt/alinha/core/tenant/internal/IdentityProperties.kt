package pt.alinha.core.tenant.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Admin access to the identity provider. [clientSecret] comes from OpenBao (`identity.provisioner-secret`),
 * never from configuration files.
 */
@ConfigurationProperties("alinha.identity")
data class IdentityProperties(
    val baseUrl: String = "http://localhost:8180",
    val realm: String = "alinha",
    val clientId: String = "alinha-provisioner",
    val clientSecret: String = "",
    /** The group whose members are the consultants (FR04); it grants them the `consultant` role. */
    val consultantsGroup: String = "consultants",
)
