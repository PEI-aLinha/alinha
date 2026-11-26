package pt.alinha.pluginapi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/** The evidence contract the Compliance Engine relies on: the strength order and the event's invariants. */
class EvidenceTest {

    @Test
    fun `strengths go from self-reported to continuously verified`() {
        assertEquals(
            listOf("SELF_REPORTED", "DOCUMENT_VERIFIED", "TECHNICALLY_VERIFIED", "CONTINUOUSLY_VERIFIED"),
            EvidenceStrength.entries.map { it.name },
        )
    }

    @Test
    fun `isAtLeast holds for the same strength and every weaker one, and never for a stronger one`() {
        val all = EvidenceStrength.entries
        for (a in all) {
            for (b in all) {
                assertEquals(all.indexOf(a) >= all.indexOf(b), a.isAtLeast(b), "$a.isAtLeast($b)")
            }
        }
        assertTrue(EvidenceStrength.CONTINUOUSLY_VERIFIED.isAtLeast(EvidenceStrength.TECHNICALLY_VERIFIED))
        assertFalse(EvidenceStrength.SELF_REPORTED.isAtLeast(EvidenceStrength.DOCUMENT_VERIFIED))
    }

    @Test
    fun `an event needs a provider`() {
        assertThrows<IllegalArgumentException> { event(provider = " ") }
        assertThrows<IllegalArgumentException> { event(provider = "") }
    }

    @Test
    fun `an event needs at least one control`() {
        assertThrows<IllegalArgumentException> { event(controlIds = emptySet()) }
    }

    @Test
    fun `a well-formed event is accepted`() {
        assertDoesNotThrow { event() }
    }

    private fun event(provider: String = "code-repository", controlIds: Set<String> = setOf("PR.SP-4-S")) =
        EvidenceCollected(
            tenantId = TenantId(UUID.randomUUID()),
            correlationId = UUID.randomUUID(),
            occurredAt = Instant.now(),
            provider = provider,
            evidenceId = UUID.randomUUID(),
            controlIds = controlIds,
            outcome = EvidenceOutcome.SATISFIED,
            strength = EvidenceStrength.TECHNICALLY_VERIFIED,
        )
}
