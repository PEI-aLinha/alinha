package pt.alinha.bff

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("alinha.bff")
data class BffProperties(
    /** The aLinha application behind the BFF. */
    val appUrl: String = "http://localhost:8080",
    /** Where the browser goes after the login (the shell). */
    val afterLoginUri: String = "/",
    /** Where Keycloak sends the browser after the logout. */
    val postLogoutUri: String = "http://localhost:5173/",
    /** Keycloak's RP-initiated logout endpoint. */
    val endSessionUri: String = "http://localhost:8180/realms/alinha/protocol/openid-connect/logout",
    /** Spring Security registration id; must match the one in application.yml. */
    val registrationId: String = "keycloak",
    /** False only for local http development. */
    val cookieSecure: Boolean = false,
    val rateLimit: RateLimit = RateLimit(),
) {
    data class RateLimit(
        val enabled: Boolean = true,
        val requests: Long = 120,
        val window: Duration = Duration.ofMinutes(1),
    )
}
