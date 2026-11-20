package pt.alinha.core.tenant.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class RegistrationRateLimiterTest {
    private val clock = object : Clock() {
        var now: Instant = Instant.parse("2026-11-16T10:00:00Z")
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }
    private val limiter = RegistrationRateLimiter(ProvisioningProperties(maxPerClientPerHour = 2), clock)

    private fun from(ip: String) {
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest().apply { remoteAddr = ip }))
    }

    @AfterEach
    fun clear() = RequestContextHolder.resetRequestAttributes()

    @Test
    fun `a client over its hourly attempts gets 429 until the window passes`() {
        from("10.0.0.1")
        repeat(2) { limiter.limit { } }
        clock.now = clock.now.plusSeconds(600)

        val error = assertThrows<ResponseStatusException> { limiter.limit { } }

        assertThat(error.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(error.body.properties?.get("code")).isEqualTo(TOO_MANY_REGISTRATIONS)
        // The oldest attempt leaves the hour 3000 s from now.
        assertThat(error.headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3000")
        clock.now = clock.now.plusSeconds(3001)
        assertThat(limiter.limit { "ok" }).isEqualTo("ok")
    }

    @Test
    fun `failed attempts count too`() {
        from("10.0.0.1")
        repeat(2) { runCatching { limiter.limit { error("boom") } } }

        assertThrows<ResponseStatusException> { limiter.limit { } }
    }

    @Test
    fun `an attempt the platform could not serve (503) costs the client nothing`() {
        from("10.0.0.1")
        val outage = RegistrationProblem(
            HttpStatus.SERVICE_UNAVAILABLE, "Identity provider unavailable", IDENTITY_UNAVAILABLE, IDENTITY_RETRY_AFTER,
        )
        // A user following Retry-After through a Keycloak outage must not end up with an hour-long 429.
        repeat(5) { assertThrows<ResponseStatusException> { limiter.limit { throw outage } } }

        assertThat(limiter.limit { "ok" }).isEqualTo("ok")
        assertThat(limiter.limit { "ok" }).isEqualTo("ok")
        assertThrows<ResponseStatusException> { limiter.limit { } }
    }

    @Test
    fun `other clients keep their own allowance`() {
        from("10.0.0.1")
        repeat(2) { limiter.limit { } }

        from("10.0.0.2")
        assertThat(limiter.limit { "ok" }).isEqualTo("ok")
    }

    @Test
    fun `overlapping attempts from one client are only counted, not refused`() {
        // A double submit is the provisioner's 503 REGISTRATION_BUSY (one per account), not a 429 from here.
        from("10.0.0.1")

        assertThat(limiter.limit { limiter.limit { "ok" } }).isEqualTo("ok")
        assertThrows<ResponseStatusException> { limiter.limit { } }
    }

    @Test
    fun `calls outside an HTTP request are not limited`() {
        repeat(5) { limiter.limit { } }
    }

    @Test
    fun `Retry-After is whole seconds, rounded up and never 0`() {
        assertThat(retryAfterSeconds(java.time.Duration.ofMillis(1))).isEqualTo(1)
        assertThat(retryAfterSeconds(java.time.Duration.ofMillis(1500))).isEqualTo(2)
        assertThat(retryAfterSeconds(java.time.Duration.ZERO)).isEqualTo(1)
    }

    @Test
    fun `IPv6 clients are limited by their 64-bit prefix`() {
        assertThat(clientKey("2001:db8:1:2:aaaa::1")).isEqualTo(clientKey("2001:db8:1:2:bbbb::9"))
        assertThat(clientKey("2001:db8:1:2::1")).isNotEqualTo(clientKey("2001:db8:1:3::1"))
        assertThat(clientKey("::ffff:10.0.0.1")).isEqualTo("10.0.0.1")
        assertThat(clientKey("10.0.0.1")).isEqualTo("10.0.0.1")
        assertThat(clientKey("not-an-ip.example")).isEqualTo("not-an-ip.example")
    }

    @Test
    fun `hex-only names are not IP literals and are kept as they are`() {
        // These would otherwise reach InetAddress.getByName and be looked up in DNS.
        assertThat(clientKey("cafe")).isEqualTo("cafe")
        assertThat(clientKey("dead.beef")).isEqualTo("dead.beef")
        assertThat(clientKey("cafe1")).isEqualTo("cafe1")
        assertThat(clientKey("1.2.3")).isEqualTo("1.2.3")
        assertThat(clientKey("256.0.0.1")).isEqualTo("256.0.0.1")
    }
}
