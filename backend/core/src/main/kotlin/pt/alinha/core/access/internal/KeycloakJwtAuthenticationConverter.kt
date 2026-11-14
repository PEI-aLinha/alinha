package pt.alinha.core.access.internal

import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

internal const val ROLE_PREFIX = "ROLE_"

/** Reads Keycloak's `realm_access.roles`. A missing or malformed claim means no roles, so access is denied. */
class KeycloakJwtAuthenticationConverter : Converter<Jwt, AbstractAuthenticationToken> {
    override fun convert(source: Jwt): AbstractAuthenticationToken {
        val realmAccess = source.claims["realm_access"] as? Map<*, *>
        val roles = (realmAccess?.get("roles") as? Collection<*>).orEmpty().filterIsInstance<String>()
        return JwtAuthenticationToken(source, roles.map { SimpleGrantedAuthority(ROLE_PREFIX + it) })
    }
}
