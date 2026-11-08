package pt.alinha.core.access.internal

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.JwtValidationException
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date

class JwtValidationTest {
    private val issuer = "https://keycloak.test/realms/alinha"
    private val audience = "alinha-api"
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val decoder = NimbusJwtDecoder.withPublicKey(keys.public as RSAPublicKey).build().apply {
        setJwtValidator(alinhaJwtValidator(issuer, audience))
    }

    private fun token(
        tokenIssuer: String = issuer,
        tokenAudience: String = audience,
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
    fun `accepts token with expected issuer and audience`() {
        assertThat(decoder.decode(token()).subject).isEqualTo("user-1")
    }

    @Test
    fun `rejects token from another issuer`() {
        assertThatThrownBy { decoder.decode(token(tokenIssuer = "https://evil.test/realms/alinha")) }
            .isInstanceOf(JwtValidationException::class.java)
    }

    @Test
    fun `rejects token for another audience`() {
        assertThatThrownBy { decoder.decode(token(tokenAudience = "other-api")) }
            .isInstanceOf(JwtValidationException::class.java)
    }

    @Test
    fun `rejects expired token`() {
        assertThatThrownBy { decoder.decode(token(expiresAt = Instant.now().minusSeconds(3600))) }
            .isInstanceOf(JwtValidationException::class.java)
    }
}
