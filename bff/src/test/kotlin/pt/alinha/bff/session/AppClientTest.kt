package pt.alinha.bff.session

import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.TimeoutException

class AppClientTest {

    private val hungApp = WebClient.builder().exchangeFunction { Mono.never() }
    private val client = AppClient(hungApp, BffProperties(appTimeout = Duration.ofMillis(50)))

    @Test
    fun `a hung application times out instead of hanging the session`() {
        StepVerifier.create(client.internalMe("token"))
            .expectError(TimeoutException::class.java)
            .verify(Duration.ofSeconds(5))
    }

    @Test
    fun `a hung organization profile times out too`() {
        StepVerifier.create(client.organization("token", "tenant"))
            .expectError(TimeoutException::class.java)
            .verify(Duration.ofSeconds(5))
    }
}
