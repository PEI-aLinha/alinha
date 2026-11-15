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

    private fun auth(role: String, claims: Map<String, Any> = emptyMap()): JwtAuthenticationToken =
        auth(listOf(role), claims)

    private fun auth(roles: List<String>, claims: Map<String, Any>): JwtAuthenticationToken {
        val jwt = Jwt.withTokenValue("t").header("alg", "none").subject("user-1")
            .claims { it.putAll(claims) }.build()
        return JwtAuthenticationToken(jwt, roles.map { SimpleGrantedAuthority("ROLE_$it") })
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
    fun `consultant with an empty tenants claim is denied`() {
        val empty = auth("consultant", mapOf(ASSIGNED_TENANTS_CLAIM to emptyList<String>()))
        assertThrows<AccessDeniedException> { resolveTenant(empty, orgA.toString()) }
    }

    @Test
    fun `consultant without a tenants claim is denied`() {
        assertThrows<AccessDeniedException> { resolveTenant(auth("consultant"), orgA.toString()) }
    }

    @Test
    fun `consultant with only malformed tenants entries is denied`() {
        val malformed = auth("consultant", mapOf(ASSIGNED_TENANTS_CLAIM to listOf("not-a-uuid", "")))
        assertThrows<AccessDeniedException> { resolveTenant(malformed, orgA.toString()) }
    }

    @Test
    fun `consultant keeps the valid entries when others are malformed`() {
        val mixed = auth("consultant", mapOf(ASSIGNED_TENANTS_CLAIM to listOf("not-a-uuid", orgA.toString())))
        assertEquals(TenantId(orgA), resolveTenant(mixed, orgA.toString()))
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
    fun `blank header counts as absent`() {
        assertEquals(TenantId(orgA), resolveTenant(client, ""))
        assertEquals(TenantId(orgA), resolveTenant(client, "   "))
        assertThrows<TenantNotSelectedException> { resolveTenant(consultant, "") }
    }

    @Test
    fun `nil uuid is never a tenant`() {
        val nil = UUID(0, 0).toString()

        assertThrows<AccessDeniedException> { resolveTenant(auth("responsible", mapOf(TENANT_CLAIM to nil)), null) }
        val nilConsultant = auth("consultant", mapOf(ASSIGNED_TENANTS_CLAIM to listOf(nil)))
        assertThrows<AccessDeniedException> { resolveTenant(nilConsultant, orgA.toString()) }
        assertThrows<TenantNotSelectedException> { resolveTenant(nilConsultant, nil) }
        assertThrows<TenantNotSelectedException> { resolveTenant(client, nil) }
    }

    @Test
    fun `request without jwt is denied`() {
        assertThrows<AccessDeniedException> { resolveTenant(null, orgA.toString()) }
    }

    @Test
    fun `a token with both consultant and responsible follows the consultant rules`() {
        val both = auth(
            listOf("consultant", "responsible"),
            mapOf(TENANT_CLAIM to orgA.toString(), ASSIGNED_TENANTS_CLAIM to listOf(orgB.toString())),
        )

        assertThrows<TenantNotSelectedException> { resolveTenant(both, null) }
        assertThrows<AccessDeniedException> { resolveTenant(both, orgA.toString()) }
        assertEquals(TenantId(orgB), resolveTenant(both, orgB.toString()))
    }
}
