package pt.alinha.fixture.remoteip

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.context.annotation.Import
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** A real Tomcat with the application's `server.*` settings and one endpoint that echoes the client address. */
@SpringBootConfiguration
@ImportAutoConfiguration(
    TomcatServletWebServerAutoConfiguration::class,
    DispatcherServletAutoConfiguration::class,
    WebMvcAutoConfiguration::class,
    HttpMessageConvertersAutoConfiguration::class,
)
@Import(RemoteAddrController::class)
class RemoteAddrFixtureApplication

@RestController
class RemoteAddrController {
    /** The address the registration rate limit keys on (`RegistrationRateLimiter`). */
    @GetMapping("/fixture/remote-addr")
    fun remoteAddr(request: HttpServletRequest): String = request.remoteAddr
}
