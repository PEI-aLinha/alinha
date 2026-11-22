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
 * Exercises Boot's own JWT decoder with the same properties as `application.yml` (only `issuer-uri` and
 * `audiences`), so the issuer, expiry and audience checks we rely on are tested on the decoder production uses.
 * A local server plays the issuer: it serves the OpenID discovery document and the JWKS it points to.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JwtValidationTest {
    private val audience = "alinha-api"
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private lateinit var server: HttpServer
    private lateinit var issuer: String

    @BeforeAll
    fun startIssuer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        issuer = "http://127.0.0.1:${server.address.port}/realms/alinha"
        val jwks = JWKSet(RSAKey.Builder(keys.public as RSAPublicKey).build()).toString()
        val discovery = """{"issuer":"$issuer","jwks_uri":"$issuer/protocol/openid-connect/certs","subject_types_supported":["public"]}"""
        server.serveJson("/realms/alinha/.well-known/openid-configuration", discovery)
        server.serveJson("/realms/alinha/protocol/openid-connect/certs", jwks)
        server.start()
    }

    @AfterAll
    fun stopIssuer() = server.stop(0)

    private fun HttpServer.serveJson(path: String, json: String) {
        val body = json.toByteArray()
        createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private fun withDecoder(audiences: String, assertions: (JwtDecoder) -> Unit) =
        withDecoder(issuer, audiences, extraProperties = emptyArray(), assertions)

    private fun withDecoder(
        issuerUri: String,
        audiences: String,
        extraProperties: Array<String>,
        assertions: (JwtDecoder) -> Unit,
    ) {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OAuth2ResourceServerAutoConfiguration::class.java))
            // Our own filter chain is not under test; any chain stops Boot from building its default one.
            .withBean(SecurityFilterChain::class.java, { DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE) })
            .withPropertyValues(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=$issuerUri",
                "spring.security.oauth2.resourceserver.jwt.audiences=$audiences",
                *extraProperties,
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

    /**
     * Production (docker-compose.prod.yml): the issuer is the public URL, which the container cannot reach, and the
     * keys come from Keycloak's internal JWK set URI. Boot must still check `iss` and `aud` on that path.
     */
    private fun withProductionDecoder(assertions: (JwtDecoder) -> Unit) = withDecoder(
        PUBLIC_ISSUER,
        audience,
        arrayOf("spring.security.oauth2.resourceserver.jwt.jwk-set-uri=$issuer/protocol/openid-connect/certs"),
        assertions,
    )

    @Test
    fun `with a jwk set uri, accepts a token from the unreachable public issuer`() = withProductionDecoder { decoder ->
        assertThat(decoder.decode(token(tokenIssuer = PUBLIC_ISSUER)).subject).isEqualTo("user-1")
    }

    @Test
    fun `with a jwk set uri, still rejects a token from another issuer`() = withProductionDecoder { decoder ->
        assertThatThrownBy { decoder.decode(token(tokenIssuer = issuer)) }
            .isInstanceOf(JwtValidationException::class.java)
    }

    @Test
    fun `with a jwk set uri, still rejects a token for another audience`() = withProductionDecoder { decoder ->
        assertThatThrownBy { decoder.decode(token(tokenIssuer = PUBLIC_ISSUER, tokenAudience = listOf("other-api"))) }
            .isInstanceOf(JwtValidationException::class.java)
    }

    private companion object {
        /** Nothing listens here: the decoder must never need to reach the issuer. */
        const val PUBLIC_ISSUER = "https://alinha.invalid/kc/realms/alinha"
    }
}
