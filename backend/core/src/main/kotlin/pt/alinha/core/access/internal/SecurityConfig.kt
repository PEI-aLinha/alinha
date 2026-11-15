package pt.alinha.core.access.internal

import jakarta.servlet.DispatcherType
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder
import org.springframework.security.web.SecurityFilterChain

/**
 * Issuer and expiry (Spring defaults) plus the audience, so tokens for other clients are refused.
 * The token is accepted when any of its `aud` values is one of the configured [audiences].
 */
internal fun alinhaJwtValidator(issuer: String, audiences: Collection<String>): OAuth2TokenValidator<Jwt> {
    require(audiences.isNotEmpty()) { "At least one JWT audience must be configured" }
    return DelegatingOAuth2TokenValidator(
        JwtValidators.createDefaultWithIssuer(issuer),
        JwtClaimValidator<List<String>?>(JwtClaimNames.AUD) { aud -> aud.orEmpty().any { it in audiences } },
    )
}

/** RNF07: only the health check skips the token. Permissions are checked by [RequiresPermissionInterceptor]. */
@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() } // bearer tokens only: no cookies for a cross-site request to ride on
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                it.requestMatchers("/actuator/health/**").permitAll()
                it.requestMatchers("/actuator/**").denyAll()
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer { server ->
                server.jwt { it.jwtAuthenticationConverter(KeycloakJwtAuthenticationConverter()) }
            }
            .build()

    /** Lazy, so the application starts even while Keycloak is still coming up. */
    @Bean
    fun jwtDecoder(properties: OAuth2ResourceServerProperties): JwtDecoder {
        val issuer = requireNotNull(properties.jwt.issuerUri) {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri must be set"
        }
        val validator = alinhaJwtValidator(issuer, properties.jwt.audiences)
        return SupplierJwtDecoder {
            NimbusJwtDecoder.withIssuerLocation(issuer).build().apply { setJwtValidator(validator) }
        }
    }
}
