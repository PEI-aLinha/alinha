package pt.alinha.core.tenant.internal

import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.server.ResponseStatusException
import pt.alinha.pluginapi.TenantId
import java.util.UUID

internal const val TENANT_HEADER = "X-Alinha-Tenant"
internal const val TENANT_CLAIM = "tenant_id"
internal const val ASSIGNED_TENANTS_CLAIM = "tenants"
private const val CONSULTANT_AUTHORITY = "ROLE_consultant"

/** Client error (400), as opposed to a denial (403). */
internal class TenantNotSelectedException(reason: String) : ResponseStatusException(HttpStatus.BAD_REQUEST, reason)

internal fun resolveTenant(authentication: Authentication?, header: String?): TenantId {
    val auth = authentication as? JwtAuthenticationToken
        ?: throw AccessDeniedException("A tenant needs an authenticated token")
    val selected = header?.let { parseTenant(it) ?: throw TenantNotSelectedException("Invalid $TENANT_HEADER") }
    val isConsultant = auth.authorities.any { it.authority == CONSULTANT_AUTHORITY }
    return if (isConsultant) consultantTenant(auth.token, selected) else clientTenant(auth.token, selected)
}

private fun consultantTenant(token: Jwt, selected: TenantId?): TenantId {
    val tenant = selected
        ?: throw TenantNotSelectedException("Consultants must choose an organization with $TENANT_HEADER")
    val assigned = token.getClaimAsStringList(ASSIGNED_TENANTS_CLAIM).orEmpty().mapNotNull(::parseTenant)
    if (tenant !in assigned) throw AccessDeniedException("Organization not assigned to this consultant")
    return tenant
}

private fun clientTenant(token: Jwt, selected: TenantId?): TenantId {
    val own = token.getClaimAsString(TENANT_CLAIM)?.let(::parseTenant)
        ?: throw AccessDeniedException("Token has no $TENANT_CLAIM")
    if (selected != null && selected != own) throw AccessDeniedException("Cannot act on another organization")
    return own
}

private fun parseTenant(raw: String): TenantId? =
    runCatching { TenantId(UUID.fromString(raw.trim())) }.getOrNull()