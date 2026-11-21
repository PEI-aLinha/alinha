package pt.alinha.core.access.internal

import jakarta.servlet.DispatcherType
import org.springframework.beans.factory.annotation.Value
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

/** Issuer and expiry (Spring defaults) plus the audience, so tokens for other clients are refused. */
internal fun alinhaJwtValidator(issuer: String, audience: String): OAuth2TokenValidator<Jwt> =
    DelegatingOAuth2TokenValidator(
        JwtValidators.createDefaultWithIssuer(issuer),
        JwtClaimValidator<List<String>?>(JwtClaimNames.AUD) { it?.contains(audience) == true },
    )

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

    /**
     * Lazy, so the application starts even while Keycloak is still coming up.
     *
     * With `alinha.security.jwk-set-uri` set, the keys come from that (internal) URL and the issuer is only compared
     * with the token's `iss`. Behind a proxy the public issuer URL may not be reachable from inside the container.
     */
    @Bean
    fun jwtDecoder(
        @Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}") issuer: String,
        @Value("\${spring.security.oauth2.resourceserver.jwt.audiences}") audience: String,
        @Value("\${alinha.security.jwk-set-uri:}") jwkSetUri: String,
    ): JwtDecoder = SupplierJwtDecoder {
        val builder =
            if (jwkSetUri.isBlank()) NimbusJwtDecoder.withIssuerLocation(issuer) else NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
        builder.build().apply {
            setJwtValidator(alinhaJwtValidator(issuer, audience))
        }
    }
}
