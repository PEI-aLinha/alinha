package pt.alinha.bff.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.server.session.CookieWebSessionIdResolver
import org.springframework.web.server.session.WebSessionIdResolver
import pt.alinha.bff.BffProperties
import java.time.Duration

@Configuration
class SessionConfig(private val props: BffProperties) {

    /** Session cookie contract: HttpOnly + SameSite=Lax, Secure outside local http development. */
    @Bean
    fun webSessionIdResolver(): WebSessionIdResolver = CookieWebSessionIdResolver().apply {
        setCookieName("ALINHA_SESSION")
        setCookieMaxAge(Duration.ofHours(8))
        addCookieInitializer {
            it.path("/").httpOnly(true).secure(props.cookieSecure).sameSite("Lax")
        }
    }
}
