package pt.alinha.core.tenant.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import pt.alinha.pluginapi.DomainEvent
import pt.alinha.pluginapi.TenantId
import java.time.Instant
import java.util.UUID

class CorrelationTest {

    private val correlation = ScopedCorrelationContext()
    private val tenants = JwtTenantContext()

    @Test
    fun `outside a request or event every call gets a new id`() {
        assertNotEquals(correlation.current(), correlation.current())
    }

    @Test
    fun `a request without the header gets a new id, echoed in the response and logs`() {
        val response = MockHttpServletResponse()
        var seen: UUID? = null
        var logged: String? = null
        CorrelationIdFilter().doFilter(MockHttpServletRequest(), response) { _, _ ->
            seen = correlation.current()
            logged = MDC.get(CORRELATION_MDC_KEY)
        }

        assertEquals(seen.toString(), response.getHeader(CORRELATION_HEADER))
        assertEquals(seen.toString(), logged)
        assertNull(CorrelationScope.currentOrNull())
        assertNull(MDC.get(CORRELATION_MDC_KEY))
    }

    @Test
    fun `a request with a valid id keeps it`() {
        val sent = UUID.randomUUID()
        assertEquals(sent, seenBy(sent.toString()))
    }

    /** A kept value would be the same both times; only a generated one differs. */
    @Test
    fun `a request with an invalid id gets a new one`() {
        val shortForm = "1-1-1-1-1" // UUID.fromString accepts it
        for (invalid in listOf("not-a-uuid", shortForm, "", "x".repeat(500), "1".repeat(36))) {
            assertNotEquals(seenBy(invalid), seenBy(invalid), "kept invalid header '$invalid'")
        }
    }

    @Test
    fun `runAs with a tenant only keeps one id for the whole block`() {
        val tenant = TenantId(UUID.randomUUID())

        val (first, second) = tenants.runAs(tenant) { correlation.current() to correlation.current() }

        assertEquals(first, second)
        assertNull(CorrelationScope.currentOrNull())
    }

    @Test
    fun `runAs with an event inside a job switches to the event's id and back`() {
        val event = FixtureEvent(TenantId(UUID.randomUUID()), UUID.randomUUID())

        val ids = tenants.runAs(event.tenantId) {
            val job = correlation.current()
            val inEvent = CorrelationScope.runWith(event.correlationId) { correlation.current() }
            Triple(job, inEvent, correlation.current())
        }

        assertEquals(event.correlationId, ids.second)
        assertEquals(ids.first, ids.third)
    }

    @Test
    fun `runAs with an event sets its tenant and correlation id and restores both`() {
        val event = FixtureEvent(TenantId(UUID.randomUUID()), UUID.randomUUID())

        val (tenant, id) = tenants.runAs(event) { tenants.current() to correlation.current() }

        assertEquals(event.tenantId, tenant)
        assertEquals(event.correlationId, id)
        assertNull(tenants.currentOrNull())
        assertNull(CorrelationScope.currentOrNull())
    }

    private fun seenBy(header: String): UUID {
        var seen: UUID? = null
        val request = MockHttpServletRequest().apply { addHeader(CORRELATION_HEADER, header) }
        CorrelationIdFilter().doFilter(request, MockHttpServletResponse()) { _, _ -> seen = correlation.current() }
        return checkNotNull(seen)
    }

    private data class FixtureEvent(
        override val tenantId: TenantId,
        override val correlationId: UUID,
        override val occurredAt: Instant = Instant.now(),
    ) : DomainEvent
}
