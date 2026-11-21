package pt.alinha.core.access.internal

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

class UnannotatedEndpointStartupTest {
    @Test
    fun `application refuses to start with an endpoint without RequiresPermission`() {
        val app = SpringApplicationBuilder(AccessControlTestApplication::class.java, UnannotatedController::class.java)
            .properties(
                "server.port=0",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://keycloak.test/realms/alinha",
                "spring.security.oauth2.resourceserver.jwt.audiences=alinha-api",
                "spring.autoconfigure.exclude=" +
                    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
                    "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            )

        assertThatThrownBy { app.run().close() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Endpoints without @RequiresPermission")
            .hasMessageContaining("UnannotatedController")
    }
}

@RestController
class UnannotatedController {
    @GetMapping("/fixture/unannotated")
    fun unannotated() = "ok"
}
