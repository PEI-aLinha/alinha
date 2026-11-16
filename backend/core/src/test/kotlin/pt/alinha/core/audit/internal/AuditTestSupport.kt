package pt.alinha.core.audit.internal

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.postgresql.PostgreSQLContainer
import pt.alinha.core.tenant.internal.HibernateTenantResolver
import pt.alinha.core.tenant.internal.JwtTenantContext

/** Audit beans on a real PostgreSQL schema built by the core Flyway migrations (no ddl-auto). */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(
    JwtTenantContext::class,
    HibernateTenantResolver::class,
    AuditActor::class,
    JpaAuditTrail::class,
    JpaAuditHistory::class,
)
class AuditTestApplication

object AuditDatabase {
    /** One container for every audit test class in the JVM. */
    val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17").apply { start() }

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
        registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        registry.add("spring.flyway.locations") { "classpath:db/migration/core" }
        // Every test class has its own context (its own @DynamicPropertySource), and Spring keeps them all open. At
        // Hikari's default of 10 connections each, a dozen classes use up PostgreSQL's max_connections of 100.
        registry.add("spring.datasource.hikari.maximum-pool-size") { MAX_POOL_SIZE }
        registry.add("spring.datasource.hikari.minimum-idle") { 1 }
    }

    private const val MAX_POOL_SIZE = 4
}
