package pt.alinha

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Boots the real application (core + every plugin submodule, Flyway on PostgreSQL) with only OpenBao turned off.
 * Startup checks, such as every endpoint declaring `@RequiresPermission`, run here, so a plugin that breaks
 * them fails the build instead of the deployment.
 */
@Testcontainers
@SpringBootTest(classes = [AlinhaApplication::class])
class ApplicationContextTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17")

        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            // Flyway has its own owner credentials (db.migration-*); one container user is enough here.
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
        }
    }

    @Test
    fun `production context starts`() {
    }
}
