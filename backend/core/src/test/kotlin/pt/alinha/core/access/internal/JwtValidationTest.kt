package pt.alinha.core.access.internal

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidationException
import org.springframework.security.web.DefaultSecurityFilterChain
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.AnyRequestMatcher
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date

/**
 * Exercises Boot's own JWT decoder with the same properties as `application.yml`, so the issuer, expiry and
 * audience checks we rely on are tested rather than assumed. Keys are served from a local JWKS endpoint.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JwtValidationTest {
    private val issuer = "https://keycloak.test/realms/alinha"
    private val audience = "alinha-api"
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private lateinit var jwks: HttpServer

    @BeforeAll
    fun startJwks() {
        val body = JWKSet(RSAKey.Builder(keys.public as RSAPublicKey).build()).toString().toByteArray()
        jwks = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/jwks") { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    @AfterAll
    fun stopJwks() = jwks.stop(0)

    private fun withDecoder(audiences: String, assertions: (JwtDecoder) -> Unit) {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OAuth2ResourceServerAutoConfiguration::class.java))
            // Our own filter chain is not under test; any chain stops Boot from building its default one.
            .withBean(SecurityFilterChain::class.java, { DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE) })
            .withPropertyValues(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=$issuer",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:${jwks.address.port}/jwks",
                "spring.security.oauth2.resourceserver.jwt.audiences=$audiences",
            )
            .run { context -> assertions(context.getBean(JwtDecoder::class.java)) }
    }

    private fun token(
        tokenIssuer: String = issuer,
        tokenAudience: List<String> = listOf(audience),
        expiresAt: Instant = Instant.now().plusSeconds(300),
    ): String {
        val claims = JWTClaimsSet.Builder()
            .subject("user-1")
            .issuer(tokenIssuer)
            .audience(tokenAudience)
            .expirationTime(Date.from(expiresAt))
            .build()
        return SignedJWT(JWSHeader(JWSAlgorithm.RS256), claims).apply { sign(RSASSASigner(keys.private)) }.serialize()
    }

    @Test
    fun `accepts token with expected issuer and audience`() = withDecoder(audience) { decoder ->
        assertThat(decoder.decode(token()).subject).isEqualTo("user-1")
    }

    @Test
    fun `rejects token from another issuer`() = withDecoder(audience) { decoder ->
        assertThatThrownBy { decoder.decode(token(tokenIssuer = "https://evil.test/realms/alinha")) }
            .isInstanceOf(JwtValidationException::class.java)
    }

    @Test
    fun `rejects token for another audience`() = withDecoder(audience) { decoder ->
        assertThatThrownBy { decoder.decode(token(tokenAudience = listOf("other-api"))) }
            .isInstanceOf(JwtValidationException::class.java)
    }

    @Test
    fun `rejects expired token`() = withDecoder(audience) { decoder ->
        assertThatThrownBy { decoder.decode(token(expiresAt = Instant.now().minusSeconds(3600))) }
            .isInstanceOf(JwtValidationException::class.java)
    }

    @Test
    fun `accepts token when any audience matches any configured audience`() = withDecoder("bff,$audience") { decoder ->
        assertThat(decoder.decode(token(tokenAudience = listOf("account", audience))).subject).isEqualTo("user-1")
    }

    @Test
    fun `rejects token without audience`() = withDecoder(audience) { decoder ->
        assertThatThrownBy { decoder.decode(token(tokenAudience = emptyList())) }
            .isInstanceOf(JwtValidationException::class.java)
    }

    @Test
    fun `refuses empty audience configuration`() {
        assertThatThrownBy { requireAudiences(OAuth2ResourceServerProperties()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
