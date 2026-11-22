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
    }

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `seeds both demo organizations into the migrated schema`() {
        val names = jdbc.queryForList(
            "select name from organization.organization_profile where tenant_id in (?::uuid, ?::uuid) order by name",
            String::class.java,
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
        )
        assertThat(names).containsExactly("Componentes Águeda, Lda.", "Metalúrgica de Aveiro, S.A.")
    }
}
