package pt.alinha.core.tenant.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import pt.alinha.pluginapi.TenantId
import java.util.UUID

class TenantResolutionTest {
    private val orgA = UUID.randomUUID()
    private val orgB = UUID.randomUUID()

    private fun auth(role: String, claims: Map<String, Any> = emptyMap()): JwtAuthenticationToken {
        val jwt = Jwt.withTokenValue("t").header("alg", "none").subject("user-1")
            .claims { it.putAll(claims) }.build()
        return JwtAuthenticationToken(jwt, listOf(SimpleGrantedAuthority("ROLE_$role")))
    }

    private val client = auth("responsible", mapOf(TENANT_CLAIM to orgA.toString()))
    private val consultant = auth("consultant", mapOf(ASSIGNED_TENANTS_CLAIM to listOf(orgA.toString())))

    @Test
    fun `client user gets the tenant from the token`() {
        assertEquals(TenantId(orgA), resolveTenant(client, null))
    }

    @Test
    fun `client user may repeat its own tenant in the header`() {
        assertEquals(TenantId(orgA), resolveTenant(client, orgA.toString()))
    }

    @Test
    fun `client user cannot select another tenant`() {
        assertThrows<AccessDeniedException> { resolveTenant(client, orgB.toString()) }
    }

    @Test
    fun `client user without tenant claim is denied`() {
        assertThrows<AccessDeniedException> { resolveTenant(auth("analyst"), null) }
    }

    @Test
    fun `consultant gets an assigned organization`() {
        assertEquals(TenantId(orgA), resolveTenant(consultant, orgA.toString()))
    }

    @Test
    fun `consultant cannot select an organization not assigned`() {
        assertThrows<AccessDeniedException> { resolveTenant(consultant, orgB.toString()) }
    }

    @Test
    fun `consultant without header must choose an organization`() {
        assertThrows<TenantNotSelectedException> { resolveTenant(consultant, null) }
    }

    @Test
    fun `malformed header is a bad request`() {
        assertThrows<TenantNotSelectedException> { resolveTenant(client, "not-a-uuid") }
    }

    @Test
    fun `request without jwt is denied`() {
        assertThrows<AccessDeniedException> { resolveTenant(null, orgA.toString()) }
    }
}
