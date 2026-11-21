package pt.alinha.core.access.internal

import jakarta.servlet.DispatcherType
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * Boot's JWT decoder checks issuer and expiry, and checks `aud` only when audiences are configured.
 * Refuse to start without them, so tokens issued for other clients can never be accepted by accident.
 */
internal fun requireAudiences(properties: OAuth2ResourceServerProperties) {
    require(properties.jwt.audiences.isNotEmpty()) {
        "spring.security.oauth2.resourceserver.jwt.audiences must be set"
    }
}

/**
 * RNF07: only the health check skips the token. Permissions are checked by [RequiresPermissionInterceptor].
 * The JWT decoder is Boot's (`spring.security.oauth2.resourceserver.jwt.*`): lazy, issuer, expiry and audience.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity, properties: OAuth2ResourceServerProperties): SecurityFilterChain {
        requireAudiences(properties)
        return http
            .csrf { it.disable() } // bearer tokens only: no cookies for a cross-site request to ride on
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                // Internal error dispatches only; a direct GET /error is a REQUEST dispatch and needs a token.
                it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                it.requestMatchers("/actuator/health/**").permitAll()
                it.requestMatchers("/actuator/**").denyAll()
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer { server ->
                server.jwt { it.jwtAuthenticationConverter(KeycloakJwtAuthenticationConverter()) }
            }
            .build()
    }
}
