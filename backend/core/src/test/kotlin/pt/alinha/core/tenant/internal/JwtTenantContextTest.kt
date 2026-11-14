package pt.alinha.core.tenant.internal

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import pt.alinha.pluginapi.TenantId
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

class JwtTenantContextTest {

    private val context = JwtTenantContext()
    private val requestTenant = TenantId(UUID.randomUUID())
    private val otherTenant = TenantId(UUID.randomUUID())

    @AfterEach
    fun clearRequest() = RequestContextHolder.resetRequestAttributes()

    @Test
    fun `runAs is rejected inside a tenant-scoped request`() {
        val request = MockHttpServletRequest().apply { setAttribute(TENANT_ATTRIBUTE, requestTenant) }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))

        assertThrows<IllegalStateException> { context.runAs(otherTenant) { } }
        assertEquals(requestTenant, context.current())
    }

    @Test
    fun `runAs sets the tenant outside a request and restores it afterwards`() {
        assertEquals(otherTenant, context.runAs(otherTenant) { context.current() })
        assertNull(context.currentOrNull())
    }
}
