package pt.alinha.core.compliance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pt.alinha.core.compliance.Nis2Classification.ESSENTIAL
import pt.alinha.core.compliance.Nis2Classification.IMPORTANT
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

class ApplicabilityResultTest {

    private val indicative = ApplicabilityResult("rules-1", CompanySize.MEDIUM, IMPORTANT, false, emptyList())

    @Test
    fun `the official classification overrides the indicative one when registered`() {
        assertEquals(IMPORTANT, indicative.effectiveNis2Classification)
        assertEquals(ESSENTIAL, indicative.copy(officialNis2Classification = ESSENTIAL).effectiveNis2Classification)
    }

    @Test
    fun `stored results stay plain data and read back equal`() {
        val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
        val result = indicative.copy(officialNis2Classification = ESSENTIAL)

        val json = mapper.writeValueAsString(result)

        assertFalse("indicative" in json || "effectiveNis2Classification" in json, json)
        assertTrue("officialNis2Classification" in json, json)
        assertEquals(result, mapper.readValue<ApplicabilityResult>(json))
    }
}
