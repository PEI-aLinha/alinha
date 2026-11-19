package pt.alinha.core.access.internal

import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import pt.alinha.core.access.RequiresPermission
import pt.alinha.core.tenant.TenantAgnostic
import pt.alinha.core.tenant.internal.validatedTenantIds

/**
 * Roles BFF `/api/me` may show (the contract's Role enum), their permissions and the organizations the access
 * token validates.
 */
data class InternalMeResponse(val roles: List<String>, val permissions: List<String>, val tenants: List<String>)

/**
 * Internal contract with the BFF (#14). Authenticated, but not tenant-scoped: it only echoes what the token
 * already grants. `session:read` is a core permission every known role has, only used here.
 */
@RestController
@TenantAgnostic
class InternalMeController(private val registry: RolePermissionRegistry) {

    @RequiresPermission("session")
    @GetMapping("/internal/me")
    fun me(authentication: Authentication): InternalMeResponse {
        val roles = authentication.authorities.orEmpty()
            .mapNotNull { it.authority }
            .filter { it.startsWith(ROLE_PREFIX) }
            .map { it.removePrefix(ROLE_PREFIX) }
            .filter { it in KNOWN_ROLES }
            .toSet()
        return InternalMeResponse(
            roles = roles.sorted(),
            permissions = registry.permissionsFor(roles).sorted(),
            tenants = validatedTenantIds(authentication).map { it.value.toString() },
        )
    }
}
