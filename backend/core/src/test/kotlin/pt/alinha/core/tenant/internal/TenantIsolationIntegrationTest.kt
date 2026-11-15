package pt.alinha.core.tenant.internal

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.stereotype.Component
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import pt.alinha.core.tenant.TenantContext
import pt.alinha.core.tenant.TenantOwnedEntity
import pt.alinha.pluginapi.TenantId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Testcontainers
// No mock web environment: it binds a fake request to the test thread, and runAs refuses inside a request.
@SpringBootTest(
    classes = [TenantIsolationTestApplication::class],
    properties = ["spring.jpa.hibernate.ddl-auto=create"],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
class TenantIsolationIntegrationTest {
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
        }
    }

    @Autowired
    private lateinit var tenants: TenantContext

    @Autowired
    private lateinit var notes: FixtureNoteRepository

    @Autowired
    private lateinit var tx: TransactionTemplate

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var events: ApplicationEventPublisher

    @Autowired
    private lateinit var listener: FixtureNoteListener

    private val tenantA = TenantId(UUID.randomUUID())
    private val tenantB = TenantId(UUID.randomUUID())

    private fun <T : Any> asTenant(tenant: TenantId, block: () -> T): T =
        tenants.runAs(tenant) { checkNotNull(tx.execute { block() }) }

    private fun noteOfTenantA(): UUID = asTenant(tenantA) { notes.save(FixtureNote(text = "A")).id }

    @Test
    fun `insert stamps the current tenant`() {
        val id = noteOfTenantA()
        val stored = jdbc.queryForObject("select tenant_id from fixture_note where id = ?", UUID::class.java, id)
        assertEquals(tenantA.value, stored)
    }

    @Test
    fun `another tenant cannot read the row`() {
        val id = noteOfTenantA()
        asTenant(tenantB) {
            assertTrue(notes.findById(id).isEmpty)
            assertTrue(notes.findAll().none { it.id == id })
        }
    }

    @Test
    fun `another tenant cannot delete the row`() {
        val id = noteOfTenantA()
        asTenant(tenantB) { notes.deleteById(id) }
        assertTrue(asTenant(tenantA) { notes.findById(id).isPresent })
    }

    @Test
    fun `another tenant cannot bulk update the row`() {
        val id = noteOfTenantA()
        asTenant(tenantB) { notes.renameAll("hacked") }
        assertEquals("A", asTenant(tenantA) { notes.findById(id).get().text })
    }

    @Test
    fun `without a tenant nothing is read`() {
        noteOfTenantA()
        assertTrue(checkNotNull(tx.execute { notes.findAll() }).isEmpty())
    }

    @Test
    fun `without a tenant nothing is written`() {
        assertThrows<RuntimeException> { tx.execute { notes.saveAndFlush(FixtureNote(text = "orphan")) } }
        assertEquals(0, jdbc.queryForObject("select count(*) from fixture_note where text = 'orphan'", Int::class.java))
    }

    @Test
    fun `runAs refuses to start inside a transaction`() {
        assertThrows<IllegalStateException> { tx.execute { tenants.runAs(tenantA) { } } }
    }

    /** The listener recipe from docs/tenancy.md (rule 4 for plugin authors). */
    @Test
    fun `async transactional listener writes for the event tenant through runAs`() {
        val text = "from-listener-${UUID.randomUUID()}"

        asTenant(tenantA) { events.publishEvent(FixtureNoteRequested(tenantA, text)) }
        val id = listener.result(text).get(10, TimeUnit.SECONDS)

        val stored = jdbc.queryForObject("select tenant_id from fixture_note where id = ?", UUID::class.java, id)
        assertEquals(tenantA.value, stored)
        asTenant(tenantB) { assertTrue(notes.findById(id).isEmpty) }
    }
}

data class FixtureNoteRequested(val tenant: TenantId, val text: String)

/** Recommended shape: async, after commit, `runAs` first and the transaction inside it. */
@Component
class FixtureNoteListener(
    private val tenants: TenantContext,
    private val tx: TransactionTemplate,
    private val notes: FixtureNoteRepository,
) {
    private val results = ConcurrentHashMap<String, CompletableFuture<UUID>>()

    fun result(text: String): CompletableFuture<UUID> = results.computeIfAbsent(text) { CompletableFuture() }

    @Async
    @TransactionalEventListener
    fun on(event: FixtureNoteRequested) {
        val result = result(event.text)
        runCatching { tenants.runAs(event.tenant) { checkNotNull(tx.execute { notes.save(FixtureNote(text = event.text)).id }) } }
            .fold(result::complete, result::completeExceptionally)
    }
}

@SpringBootConfiguration
@EnableAutoConfiguration
@EnableAsync
@Import(JwtTenantContext::class, HibernateTenantResolver::class, FixtureNoteListener::class)
class TenantIsolationTestApplication

@Entity
@Table(name = "fixture_note")
class FixtureNote(
    @Id val id: UUID = UUID.randomUUID(),
    var text: String = "",
) : TenantOwnedEntity()

interface FixtureNoteRepository : JpaRepository<FixtureNote, UUID> {
    @Modifying
    @Query("update FixtureNote n set n.text = :text")
    fun renameAll(text: String): Int
}
