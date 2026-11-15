package pt.alinha.core.audit.internal

import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component

/** Who acts: the token's `sub` (not name or e-mail: less personal data, RNF15); `system` for jobs and listeners. */
@Component
class AuditActor {
    fun current(): String {
        val auth = SecurityContextHolder.getContext().authentication
        if (auth == null || auth is AnonymousAuthenticationToken || !auth.isAuthenticated) return SYSTEM
        return (auth.principal as? Jwt)?.subject ?: auth.name
    }

    companion object {
        const val SYSTEM = "system"
    }
}
