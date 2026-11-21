package pt.alinha.core.tenant.internal

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import jakarta.persistence.EntityManager
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import pt.alinha.pluginapi.TenantId
import java.util.UUID
import java.util.concurrent.Executors
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
    fun `runAs is rejected inside a tenant-agnostic request`() {
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))

        assertThrows<IllegalStateException> { context.runAs(otherTenant) { } }
        assertNull(context.currentOrNull())
    }

    @Test
    fun `runAs sets the tenant outside a request and restores it afterwards`() {
        assertEquals(otherTenant, context.runAs(otherTenant) { context.current() })
        assertNull(context.currentOrNull())
    }

    @Test
    fun `a new tenant can be bound inside a tenant-agnostic request, and only for the block`() {
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))

        assertEquals(otherTenant, context.runAsNewTenant(otherTenant) { context.current() })
        assertNull(context.currentOrNull())
    }

    @Test
    fun `a new tenant cannot be bound while an open-in-view JPA session is bound to the thread`() {
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))
        val session = java.lang.reflect.Proxy.newProxyInstance(
            EntityManager::class.java.classLoader, arrayOf(EntityManager::class.java),
        ) { _, _, _ -> null } as EntityManager
        val key = Any()
        TransactionSynchronizationManager.bindResource(key, EntityManagerHolder(session))
        try {
            assertThrows<IllegalStateException> { context.runAsNewTenant(otherTenant) { } }
            assertNull(context.currentOrNull())
        } finally {
            TransactionSynchronizationManager.unbindResource(key)
        }
    }

    @Test
    fun `a new tenant cannot be bound inside a tenant-scoped request`() {
        val request = MockHttpServletRequest().apply { setAttribute(TENANT_ATTRIBUTE, requestTenant) }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))

        assertThrows<IllegalStateException> { context.runAsNewTenant(otherTenant) { } }
        assertEquals(requestTenant, context.current())
    }

    @Test
    fun `a thread started from a request does not inherit the request tenant`() {
        val request = MockHttpServletRequest().apply { setAttribute(TENANT_ATTRIBUTE, requestTenant) }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))

        val seenByAsyncWork = Executors.newSingleThreadExecutor().use { executor ->
            executor.submit<TenantId?> { context.currentOrNull() }.get()
        }

        assertNull(seenByAsyncWork)
        assertEquals(requestTenant, context.current())
    }
}
