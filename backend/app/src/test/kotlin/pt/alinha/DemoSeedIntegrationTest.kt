package pt.alinha

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import pt.alinha.core.compliance.CatalogueRegistry
import pt.alinha.core.compliance.ComplianceLevel
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Boots the real application with the `demo` profile, so db/demo runs against the schema the plugins' migrations
 * actually build. A plugin that renames or adds a NOT NULL column used by the seed fails here, not on the demo server.
 */
@Testcontainers
@ActiveProfiles("demo")
// Its own context (demo profile): close it while its container still runs, instead of at JVM shutdown.
@DirtiesContext
@SpringBootTest(classes = [AlinhaApplication::class])
class DemoSeedIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17")

        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) {
            // Testcontainers maps the port on localhost, which DemoProfileGuard accepts as local development.
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
            registry.add("alinha.jobs.relay.enabled") { "false" }
            registry.add("alinha.jobs.results.enabled") { "false" }
        }

        const val COMPONENTES_AGUEDA = "11111111-1111-4111-8111-111111111111"
        const val METALURGICA_AVEIRO = "22222222-2222-4222-8222-222222222222"
        const val DEMO_ASSESSMENT = "de330000-0000-4000-8000-000000000001"
    }

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var catalogues: CatalogueRegistry

    @Test
    fun `seeds both persona organizations into the migrated schema`() {
        val names = jdbc.queryForList(
            "select name from organization.organization_profile where tenant_id in (?::uuid, ?::uuid) order by name",
            String::class.java,
            COMPONENTES_AGUEDA,
            METALURGICA_AVEIRO,
        )
        assertThat(names).containsExactly("Componentes Águeda, Lda.", "Metalúrgica de Aveiro, S.A.")
    }

    @Test
    fun `every tenant in Marta's portfolio has a core tenant and an organization profile`() {
        val portfolio = realmUser("marta.ferreira@alinha.pt")["attributes"]["tenants"].strings()
        assertThat(portfolio).hasSize(12).doesNotHaveDuplicates()

        // Only the seed's tenants (owner `demo-seed:<id>`), so another tenant in the database does not matter.
        val seeded = jdbc.queryForList("select id::text from core.tenant where owner_subject like 'demo-seed:%'", String::class.java)
        val profiles = jdbc.queryForList("select tenant_id::text from organization.organization_profile", String::class.java)
        assertThat(seeded).containsExactlyInAnyOrderElementsOf(portfolio)
        assertThat(profiles).containsAll(portfolio)
    }

    @Test
    fun `the reset script deletes exactly the tenants in Marta's portfolio`() {
        val portfolio = realmUser("marta.ferreira@alinha.pt")["attributes"]["tenants"].strings()
        val script = Files.readString(repoRoot().resolve("scripts/reset-demo.sh"))
        val ids = Regex("'([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})'").findAll(script).map { it.groupValues[1] }
        assertThat(ids.toList()).containsExactlyInAnyOrderElementsOf(portfolio)
    }

    @Test
    fun `the persona users only see demo tenants`() {
        val tenants = jdbc.queryForList("select id::text from core.tenant", String::class.java)
        for (user in listOf("rui.santos@compagueda.pt", "pedro.almeida@tiagueda.pt", "joao.pereira@alinha.pt")) {
            val attributes = realmUser(user)["attributes"]
            val claims = (attributes["tenant_id"] ?: attributes["tenants"]).strings()
            assertThat(tenants).describedAs(user).containsAll(claims)
        }
    }

    @Test
    fun `the factory has a submitted NIS2 assessment answering exactly its questionnaire`() {
        val assessment = jdbc.queryForMap(
            "select tenant_id::text as tenant, framework, catalogue_version, max_level, status " +
                "from assessment.assessment where id = ?::uuid",
            DEMO_ASSESSMENT,
        )
        assertThat(assessment["tenant"]).isEqualTo(COMPONENTES_AGUEDA)
        assertThat(assessment["status"]).isEqualTo("SUBMITTED")

        val catalogue = catalogues.get(assessment["framework"] as String, assessment["catalogue_version"] as Int)!!
        val maxLevel = ComplianceLevel.valueOf(assessment["max_level"] as String)
        val expected = catalogue.controls.filter { it.level <= maxLevel }.flatMap { c -> c.questions.map { it.id } }

        val answered = jdbc.queryForList(
            "select question_id from assessment.answer where assessment_id = ?::uuid and tenant_id = ?::uuid",
            String::class.java,
            DEMO_ASSESSMENT,
            COMPONENTES_AGUEDA,
        )
        assertThat(answered).containsExactlyInAnyOrderElementsOf(expected)
    }

    @Test
    fun `the factory's applicability answers match the scope its assessment was started with`() {
        val classification = jdbc.queryForObject(
            "select classification from organization.cncs_qualification where tenant_id = ?::uuid",
            String::class.java,
            COMPONENTES_AGUEDA,
        )
        val scope = jdbc.queryForMap(
            "select nis2_classification, scope_input ->> 'publicBody' as public_body, " +
                "scope_input -> 'nis2' ->> 'entityTypes' as entity_types, " +
                "(scope_input -> 'size' ->> 'employees')::int as employees " +
                "from assessment.assessment where id = ?::uuid",
            DEMO_ASSESSMENT,
        )
        val answers = jdbc.queryForMap(
            "select public_body, array_to_json(nis2_entity_types)::text as entity_types " +
                "from organization.applicability_answers where tenant_id = ?::uuid",
            COMPONENTES_AGUEDA,
        )
        val employees = jdbc.queryForObject(
            "select employees from organization.organization_profile where tenant_id = ?::uuid",
            Int::class.java,
            COMPONENTES_AGUEDA,
        )
        assertThat(scope["nis2_classification"]).isEqualTo(classification)
        assertThat(scope["public_body"]).isEqualTo(answers["public_body"].toString())
        assertThat(scope["entity_types"]).isEqualTo(answers["entity_types"])
        assertThat(scope["employees"]).isEqualTo(employees)
    }

    private fun realmUser(username: String): JsonNode {
        val realm = JsonMapper().readTree(Files.readString(repoRoot().resolve("deploy/keycloak/alinha-realm.json")))
        return realm["users"].first { it["username"].asString() == username }
    }

    private fun JsonNode.strings(): List<String> = (0 until size()).map { get(it).asString() }

    /** Walks up from the test's working directory to the folder holding `settings.gradle.kts`. */
    private fun repoRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { it.resolve("settings.gradle.kts").exists() }
}
