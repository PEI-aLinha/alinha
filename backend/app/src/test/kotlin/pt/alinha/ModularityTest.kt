package pt.alinha

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

/**
 * Enforces the architecture rules on every build (runs in the main repo's CI
 * AND in every plugin's CI):
 *  - no plugin accesses another module's internal classes (RNF17);
 *  - no cycles between modules;
 *  - only public types and «named interfaces» are used (api, events, core::*).
 */
class ModularityTest {

    private val modules = ApplicationModules.of(AlinhaApplication::class.java)

    @Test
    fun `respects module boundaries`() {
        modules.verify()
    }

    @Test
    fun `writes module documentation`() {
        // build/spring-modulith-docs — C4/PlantUML diagrams for the report
        Documenter(modules).writeDocumentation()
    }
}
