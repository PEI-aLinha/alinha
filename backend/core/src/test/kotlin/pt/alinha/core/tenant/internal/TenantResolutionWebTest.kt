package pt.alinha.core.tenant.internal

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pt.alinha.core.access.internal.SecurityConfig
import pt.alinha.core.tenant.TenantAgnostic
import pt.alinha.core.tenant.TenantContext
import java.util.UUID

private val ORG_A = UUID.fromString("00000000-0000-0000-0000-00000000000a")
private val ORG_B = UUID.fromString("00000000-0000-0000-0000-00000000000b")
private const val CONSULTANT = "consultant"

@SpringBootTest(
    classes = [TenantWebTestApplication::class],
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://keycloak.test/realms/alinha",
        "spring.security.oauth2.resourceserver.jwt.audiences=alinha-api",
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
class TenantResolutionWebTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private fun call(token: String, path: String = "/fixture/tenant", tenant: UUID? = null) = mockMvc.get(path) {
        header("Authorization", "Bearer $token")
        tenant?.let { header(TENANT_HEADER, it.toString()) }
    }

    @Test
    fun `client user acts on its own tenant`() {
        call("responsible").andExpect {
            status { isOk() }
            content { string(ORG_A.toString()) }
        }
    }

    @Test
    fun `client user cannot switch tenant with the header`() {
        call("responsible", tenant = ORG_B).andExpect { status { isForbidden() } }
    }

    @Test
    fun `consultant acts on a chosen assigned organization`() {
        call(CONSULTANT, tenant = ORG_A).andExpect {
            status { isOk() }
            content { string(ORG_A.toString()) }
        }
    }

    @Test
    fun `consultant cannot choose an organization not assigned`() {
        call(CONSULTANT, tenant = ORG_B).andExpect { status { isForbidden() } }
    }

    @Test
    fun `consultant without a chosen organization gets 400`() {
        call(CONSULTANT).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `tenant agnostic endpoint needs no tenant`() {
        call(CONSULTANT, path = "/fixture/agnostic").andExpect { status { isOk() } }
    }
}

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(
    SecurityConfig::class,
    TenantWebConfig::class,
    TenantResolutionInterceptor::class,
    JwtTenantContext::class,
    TenantFixtureController::class,
)
class TenantWebTestApplication {
    /** The token value names the role; client users belong to ORG_A, the consultant is assigned ORG_A only. */
    @Bean
    @Primary
    fun testJwtDecoder(): JwtDecoder = JwtDecoder { token ->
        val builder = Jwt.withTokenValue(token)
            .header("alg", "none")
            .subject("user-1")
            .claim("realm_access", mapOf("roles" to listOf(token)))
        if (token == CONSULTANT) {
            builder.claim(ASSIGNED_TENANTS_CLAIM, listOf(ORG_A.toString()))
        } else {
            builder.claim(TENANT_CLAIM, ORG_A.toString())
        }
        builder.build()
    }
}

@RestController
@RequestMapping("/fixture")
class TenantFixtureController(private val tenants: TenantContext) {
    @GetMapping("/tenant")
    fun tenant() = tenants.current().value.toString()

    @TenantAgnostic
    @GetMapping("/agnostic")
    fun agnostic() = "ok"
}
