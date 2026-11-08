package pt.alinha.core.access.internal

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pt.alinha.core.access.RequiresPermission
import pt.alinha.core.registry.PluginRegistry
import pt.alinha.core.registry.PluginRegistryController
import pt.alinha.pluginapi.AlinhaPlugin
import pt.alinha.pluginapi.PluginKind
import pt.alinha.pluginapi.PluginManifest

private const val INVALID_TOKEN = "invalid"

@SpringBootTest(
    classes = [AccessControlTestApplication::class],
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://keycloak.test/realms/alinha",
        "spring.security.oauth2.resourceserver.jwt.audiences=alinha-api",
        "management.endpoints.web.exposure.include=health,info",
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
class AccessControlIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private fun getAs(token: String?, path: String) = mockMvc.get(path) {
        token?.let { header("Authorization", "Bearer $it") }
    }

    @Test
    fun `rejects request without token`() {
        getAs(null, "/fixture/assessment").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `rejects invalid token`() {
        getAs(INVALID_TOKEN, "/fixture/assessment").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `health check is public`() {
        getAs(null, "/actuator/health").andExpect { status { isOk() } }
    }

    @Test
    fun `other actuator endpoints are denied even with a token`() {
        getAs("consultant", "/actuator/info").andExpect { status { isForbidden() } }
    }

    @Test
    fun `denies authenticated user on endpoint without annotation`() {
        getAs("consultant", "/fixture/unannotated").andExpect { status { isForbidden() } }
    }

    @Test
    fun `denies token without known roles`() {
        getAs("intruder", "/fixture/assessment").andExpect { status { isForbidden() } }
    }

    @Test
    fun `accepts bearer POST without CSRF token`() {
        mockMvc.post("/fixture/assessment") { header("Authorization", "Bearer responsible") }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `plugin registry is readable by a role with plugins read`() {
        getAs("analyst", "/api/plugins").andExpect { status { isOk() } }
    }

    @ParameterizedTest(name = "{0} on {1} gives {2}")
    @CsvSource(
        "consultant, /fixture/assessment, 200",
        "consultant, /fixture/results, 200",
        "consultant, /fixture/roadmap, 200",
        "responsible, /fixture/assessment, 200",
        "responsible, /fixture/results, 200",
        "responsible, /fixture/roadmap, 200",
        "analyst, /fixture/assessment, 200",
        "analyst, /fixture/results, 403",
        "analyst, /fixture/roadmap, 403",
    )
    fun `applies permissions per role`(role: String, path: String, expected: Int) {
        getAs(role, path).andExpect { status { isEqualTo(expected) } }
    }

    @ParameterizedTest(name = "{0} on {1} gives {2}")
    @CsvSource(
        "consultant, /fixture/class, 200",
        "analyst, /fixture/class, 403",
        "consultant, /fixture/class/override, 403",
        "analyst, /fixture/class/override, 200",
    )
    fun `method annotation overrides class annotation`(role: String, path: String, expected: Int) {
        getAs(role, path).andExpect { status { isEqualTo(expected) } }
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
    PluginRegistryController::class,
    FixtureController::class,
    FixtureClassLevelController::class,
)
class AccessControlTestApplication {
    /** The token value names the role, so requests run the real converter without Keycloak. */
    @Bean
    @Primary
    fun testJwtDecoder(): JwtDecoder = JwtDecoder { token ->
        if (token == INVALID_TOKEN) throw BadJwtException("invalid token")
        Jwt.withTokenValue(token)
            .header("alg", "none")
            .subject("user-1")
            .claim("realm_access", mapOf("roles" to listOf(token)))
            .build()
    }

    @Bean
    fun fixturePlugin(): AlinhaPlugin = object : AlinhaPlugin {
        private val read = setOf("assessment:read", "results:read", "roadmap:read")
        override val manifest = PluginManifest(
            id = "fixture",
            name = "Fixture",
            kind = PluginKind.FEATURE,
            version = "1.0.0",
            permissions = read + "assessment:edit",
            rolePermissions = mapOf(
                "consultant" to read,
                "responsible" to read + "assessment:edit",
                "analyst" to setOf("assessment:read", "assessment:edit"),
            ),
        )
    }
}

@RestController
@RequestMapping("/fixture")
class FixtureController {
    @RequiresPermission("assessment")
    @GetMapping("/assessment")
    fun assessment() = "ok"

    @RequiresPermission("assessment", "edit")
    @PostMapping("/assessment")
    fun editAssessment() = "ok"

    @RequiresPermission("results")
    @GetMapping("/results")
    fun results() = "ok"

    @RequiresPermission("roadmap")
    @GetMapping("/roadmap")
    fun roadmap() = "ok"

    @GetMapping("/unannotated")
    fun unannotated() = "ok"
}

@RestController
@RequiresPermission("results")
@RequestMapping("/fixture/class")
class FixtureClassLevelController {
    @GetMapping
    fun inherited() = "ok"

    @RequiresPermission("assessment", "edit")
    @GetMapping("/override")
    fun override() = "ok"
}
