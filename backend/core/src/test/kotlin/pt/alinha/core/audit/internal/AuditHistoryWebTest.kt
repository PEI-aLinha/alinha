package pt.alinha.core.audit.internal

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import pt.alinha.core.access.internal.AccessWebConfig
import pt.alinha.core.access.internal.RequiresPermissionInterceptor
import pt.alinha.core.access.internal.RolePermissionRegistry
import pt.alinha.core.access.internal.SecurityConfig
import pt.alinha.core.audit.AuditEntry
import pt.alinha.core.audit.AuditFilter
import pt.alinha.core.audit.AuditHistory
import pt.alinha.core.registry.PluginRegistry
import pt.alinha.core.tenant.TenantContext
import pt.alinha.core.tenant.internal.ASSIGNED_TENANTS_CLAIM
import pt.alinha.core.tenant.internal.JwtTenantContext
import pt.alinha.core.tenant.internal.TENANT_CLAIM
import pt.alinha.core.tenant.internal.TENANT_HEADER
import pt.alinha.core.tenant.internal.TenantResolutionInterceptor
import pt.alinha.core.tenant.internal.TenantWebConfig
import java.time.Instant
import java.util.UUID

private val ORG = UUID.fromString("00000000-0000-0000-0000-00000000000a")
private const val PATH = "/api/audit/events"

/** `GET /api/audit/events`: permission audit:read (consultant, responsible) and a resolved tenant. */
@SpringBootTest(
    classes = [AuditWebTestApplication::class],
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://keycloak.test/realms/alinha",
        "spring.security.oauth2.resourceserver.jwt.audiences=alinha-api",
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
class AuditHistoryWebTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `responsible reads the history of its organization`() {
        mockMvc.get(PATH) { header("Authorization", "Bearer responsible") }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].entityId") { value(ORG.toString()) } // fake echoes the tenant it ran as
            jsonPath("$.total") { value(1) }
        }
    }

    @Test
    fun `consultant reads the history of a chosen assigned organization`() {
        mockMvc.get(PATH) {
            header("Authorization", "Bearer consultant")
            header(TENANT_HEADER, ORG.toString())
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `filters reach the history unchanged`() {
        mockMvc.get(PATH) {
            header("Authorization", "Bearer responsible")
            param("entityType", "asset")
            param("actor", "user-9")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].entityType") { value("asset") } // fake echoes the filter
            jsonPath("$.items[0].actor") { value("user-9") }
        }
    }

    @Test
    fun `pageSize defaults to 20`() {
        mockMvc.get(PATH) { header("Authorization", "Bearer responsible") }.andExpect { jsonPath("$.pageSize") { value(20) } }
    }

    @Test
    fun `pageSize is capped at 100`() {
        mockMvc.get(PATH) {
            header("Authorization", "Bearer responsible")
            param("pageSize", "1000")
        }.andExpect { jsonPath("$.pageSize") { value(100) } }
    }

    @Test
    fun `zero or negative pageSize and page fall back to the smallest valid values`() {
        mockMvc.get(PATH) {
            header("Authorization", "Bearer responsible")
            param("pageSize", "0")
            param("page", "-3")
        }.andExpect {
            status { isOk() }
            jsonPath("$.pageSize") { value(1) }
            jsonPath("$.page") { value(0) }
        }
    }

    @Test
    fun `from after to is a bad request`() {
        mockMvc.get(PATH) {
            header("Authorization", "Bearer responsible")
            param("from", "2026-11-16T10:00:00Z")
            param("to", "2026-11-15T10:00:00Z")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `analyst is denied`() {
        mockMvc.get(PATH) { header("Authorization", "Bearer analyst") }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `no token is unauthorized`() {
        mockMvc.get(PATH).andExpect { status { isUnauthorized() } }
    }
}

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(
    SecurityConfig::class,
    AccessWebConfig::class,
    RequiresPermissionInterceptor::class,
    RolePermissionRegistry::class,
    PluginRegistry::class,
    TenantWebConfig::class,
    TenantResolutionInterceptor::class,
    JwtTenantContext::class,
    AuditHistoryController::class,
)
class AuditWebTestApplication {
    /** The token value names the role; client users belong to ORG, the consultant is assigned ORG. */
    @Bean
    @Primary
    fun testJwtDecoder(): JwtDecoder = JwtDecoder { token ->
        val builder = Jwt.withTokenValue(token)
            .header("alg", "none")
            .subject("user-1")
            .claim("realm_access", mapOf("roles" to listOf(token)))
        if (token == "consultant") {
            builder.claim(ASSIGNED_TENANTS_CLAIM, listOf(ORG.toString()))
        } else {
            builder.claim(TENANT_CLAIM, ORG.toString())
        }
        builder.build()
    }

    /**
     * Returns one entry naming the tenant it ran as (proves the tenant comes from the token) and echoing
     * the entityType/actor filters (proves the controller passes them through).
     */
    @Bean
    fun fakeHistory(tenants: TenantContext): AuditHistory = object : AuditHistory {
        override fun find(filter: AuditFilter, pageable: Pageable): Page<AuditEntry> = PageImpl(
            listOf(
                AuditEntry(
                    id = UUID.randomUUID(),
                    actor = filter.actor ?: "user-1",
                    action = "fixture.read",
                    entityType = filter.entityType ?: "tenant",
                    entityId = tenants.current().value.toString(),
                    details = emptyMap(),
                    provenance = null,
                    occurredAt = Instant.EPOCH,
                ),
            ),
            pageable,
            1,
        )
    }
}
