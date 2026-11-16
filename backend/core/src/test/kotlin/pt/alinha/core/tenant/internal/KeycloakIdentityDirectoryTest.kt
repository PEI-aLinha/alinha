package pt.alinha.core.tenant.internal

import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import pt.alinha.pluginapi.TenantId
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class KeycloakIdentityDirectoryTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val directory = KeycloakIdentityDirectory(
        IdentityProperties(baseUrl = "http://kc", clientSecret = "s3cret"),
        builder,
    )

    private val subject = "8d0c1f4e-0000-4000-8000-000000000001"
    private val tenant = TenantId(UUID.fromString("33333333-3333-4333-8333-333333333333"))
    private val users = "http://kc/admin/realms/alinha/users/$subject"
    private val user = """{"id":"$subject","username":"nova@empresa.pt","email":"nova@empresa.pt","attributes":{"locale":["pt"]}}"""
    private val defaultRoles = """{"id":"r-default","name":"default-roles-alinha","composite":true}"""
    private val responsible = """{"id":"r-resp","name":"responsible"}"""

    private fun expectToken() {
        server.expect(requestTo("http://kc/realms/alinha/protocol/openid-connect/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().formDataContains(mapOf("grant_type" to "client_credentials", "client_id" to "alinha-provisioner", "client_secret" to "s3cret")))
            .andRespond(withSuccess("""{"access_token":"tok","expires_in":300}""", MediaType.APPLICATION_JSON))
    }

    private fun expectGet(uri: String, body: String) {
        server.expect(requestTo(uri)).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer tok"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    @Test
    fun `assigns the tenant, maps responsible and removes the default roles`() {
        expectToken()
        expectGet(users, user)
        server.expect(requestTo(users)).andExpect(method(HttpMethod.PUT))
            // the whole representation goes back, existing attributes kept
            .andExpect(content().json("""{"username":"nova@empresa.pt","attributes":{"locale":["pt"],"tenant_id":["${tenant.value}"]}}"""))
            .andRespond(withNoContent())
        expectGet("$users/role-mappings/realm", "[$defaultRoles]")
        expectGet("$users/role-mappings/realm/available", """[$responsible,{"id":"r-cons","name":"consultant"}]""")
        server.expect(requestTo("$users/role-mappings/realm")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""[{"id":"r-resp","name":"responsible"}]""", JsonCompareMode.STRICT))
            .andRespond(withNoContent())
        server.expect(requestTo("$users/role-mappings/realm")).andExpect(method(HttpMethod.DELETE))
            .andExpect(content().json("""[{"id":"r-default","name":"default-roles-alinha"}]"""))
            .andRespond(withNoContent())

        directory.assignTenant(subject, tenant)

        server.verify()
    }

    @Test
    fun `revoke restores the account and only removes its own tenant`() {
        expectToken()
        expectGet(users, """{"id":"$subject","attributes":{"tenant_id":["${tenant.value}"]}}""")
        server.expect(requestTo(users)).andExpect(method(HttpMethod.PUT))
            .andExpect(content().string(not(containsString("tenant_id"))))
            .andRespond(withNoContent())
        expectGet("$users/role-mappings/realm", "[$responsible]")
        server.expect(requestTo("$users/role-mappings/realm")).andExpect(method(HttpMethod.DELETE))
            .andExpect(content().json("[$responsible]"))
            .andRespond(withNoContent())
        expectGet("$users/role-mappings/realm/available", "[$defaultRoles]")
        server.expect(requestTo("$users/role-mappings/realm")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""[{"id":"r-default","name":"default-roles-alinha"}]""", JsonCompareMode.STRICT))
            .andRespond(withNoContent())

        directory.revokeTenant(subject, tenant)

        server.verify()
    }

    @Test
    fun `revoke leaves an account that now holds another tenant untouched`() {
        expectToken()
        expectGet(users, """{"id":"$subject","attributes":{"tenant_id":["${UUID.randomUUID()}"]}}""")

        directory.revokeTenant(subject, tenant)

        server.verify() // no PUT and no role change
    }

    @Test
    fun `reuses the service-account token until shortly before it expires`() {
        expectToken()
        expectGet(users, """{"id":"$subject","attributes":{}}""")
        expectGet(users, """{"id":"$subject","attributes":{}}""")

        directory.revokeTenant(subject, tenant)
        directory.revokeTenant(subject, tenant)

        server.verify() // a single token request
    }

    @Test
    fun `fails when the realm has no responsible role`() {
        expectToken()
        expectGet(users, user)
        server.expect(requestTo(users)).andExpect(method(HttpMethod.PUT)).andRespond(withNoContent())
        expectGet("$users/role-mappings/realm", "[$defaultRoles]")
        expectGet("$users/role-mappings/realm/available", "[]")

        assertThrows<IllegalStateException> { directory.assignTenant(subject, tenant) }
    }

    @Test
    fun `an identity provider error propagates`() {
        expectToken()
        server.expect(requestTo(users)).andRespond(withServerError())

        assertThrows<RestClientException> { directory.assignTenant(subject, tenant) }
    }

    @Test
    fun `refuses to run without a client secret`() {
        val unconfigured = KeycloakIdentityDirectory(IdentityProperties(baseUrl = "http://kc"), RestClient.builder())

        assertThrows<IllegalStateException> { unconfigured.assignTenant(subject, tenant) }
    }

    @Test
    fun `an assignment that takes too long stops before the next call`() {
        // Each reading of the clock moves it 4 s forward, so the 10 s budget runs out after a couple of calls.
        val slowClock = object : Clock() {
            private var now = Instant.parse("2026-11-16T10:00:00Z")
            override fun instant(): Instant = now.also { now = now.plusSeconds(4) }
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?): Clock = this
        }
        val slowBuilder = RestClient.builder()
        val slowServer = MockRestServiceServer.bindTo(slowBuilder).ignoreExpectOrder(true).build()
        slowServer.expect(ExpectedCount.between(0, 1), requestTo("http://kc/realms/alinha/protocol/openid-connect/token"))
            .andRespond(withSuccess("""{"access_token":"tok","expires_in":300}""", MediaType.APPLICATION_JSON))
        slowServer.expect(ExpectedCount.between(0, 1), requestTo(users)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(user, MediaType.APPLICATION_JSON))
        slowServer.expect(ExpectedCount.between(0, 1), requestTo(users)).andExpect(method(HttpMethod.PUT))
            .andRespond(withNoContent())
        val slow = KeycloakIdentityDirectory(IdentityProperties(baseUrl = "http://kc", clientSecret = "s3cret"), slowBuilder, slowClock)

        val error = assertThrows<IllegalStateException> { slow.assignTenant(subject, tenant) }

        assertThat(error.message).contains("exceeded")
    }
}
