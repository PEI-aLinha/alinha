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
import org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpClientErrorException
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
    fun `assigns the tenant, maps responsible and removes only registrant from the default roles`() {
        expectToken()
        expectGet(users, user)
        server.expect(requestTo(users)).andExpect(method(HttpMethod.PUT))
            // the whole representation goes back, existing attributes kept
            .andExpect(content().json("""{"username":"nova@empresa.pt","attributes":{"locale":["pt"],"tenant_id":["${tenant.value}"]}}"""))
            .andRespond(withNoContent())
        expectGet("$users/role-mappings/realm", "[$defaultRoles]")
        expectGet("$users/role-mappings/realm/available", """[$responsible,{"id":"r-cons","name":"consultant"}]""")
        expectGet(
            "$users/role-mappings/realm/composite",
            """[$defaultRoles,{"id":"r-reg","name":"registrant"},{"id":"r-off","name":"offline_access"},{"id":"r-uma","name":"uma_authorization"}]""",
        )
        // offline_access and uma_authorization are mapped directly before the composite goes, so offline sessions survive
        server.expect(requestTo("$users/role-mappings/realm")).andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json(
                    """[{"id":"r-resp","name":"responsible"},{"id":"r-off","name":"offline_access"},{"id":"r-uma","name":"uma_authorization"}]""",
                    JsonCompareMode.STRICT,
                ),
            )
            .andRespond(withNoContent())
        server.expect(requestTo("$users/role-mappings/realm")).andExpect(method(HttpMethod.DELETE))
            .andExpect(content().json("""[{"id":"r-default","name":"default-roles-alinha"}]"""))
            .andRespond(withNoContent())

        directory.assignTenant(subject, tenant)

        server.verify()
    }

    @Test
    fun `revoke restores registrant first and removes the tenant last`() {
        expectToken()
        expectGet(users, """{"id":"$subject","attributes":{"tenant_id":["${tenant.value}"]}}""")
        expectGet("$users/role-mappings/realm/available", "[$defaultRoles]")
        server.expect(requestTo("$users/role-mappings/realm")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""[{"id":"r-default","name":"default-roles-alinha"}]""", JsonCompareMode.STRICT))
            .andRespond(withNoContent())
        expectGet("$users/role-mappings/realm", "[$responsible]")
        server.expect(requestTo("$users/role-mappings/realm")).andExpect(method(HttpMethod.DELETE))
            .andExpect(content().json("[$responsible]"))
            .andRespond(withNoContent())
        server.expect(requestTo(users)).andExpect(method(HttpMethod.PUT))
            .andExpect(content().string(not(containsString("tenant_id"))))
            .andRespond(withNoContent())

        directory.revokeTenant(subject, tenant)

        server.verify()
    }

    @Test
    fun `a revoke that fails halfway keeps the tenant attribute for a retry`() {
        expectToken()
        expectGet(users, """{"id":"$subject","attributes":{"tenant_id":["${tenant.value}"]}}""")
        expectGet("$users/role-mappings/realm/available", "[$defaultRoles]")
        server.expect(requestTo("$users/role-mappings/realm")).andExpect(method(HttpMethod.POST)).andRespond(withNoContent())
        expectGet("$users/role-mappings/realm", "[$responsible]")
        server.expect(requestTo("$users/role-mappings/realm")).andExpect(method(HttpMethod.DELETE)).andRespond(withServerError())

        assertThrows<RestClientException> { directory.revokeTenant(subject, tenant) }

        server.verify() // the PUT that removes tenant_id never happened
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

    @Test
    fun `the deadline is checked between reading and writing the account`() {
        // Each HTTP call is one step, so the worst case stays the budget plus one call (connect + read timeouts).
        val clock = object : Clock() {
            var now: Instant = Instant.parse("2026-11-16T10:00:00Z")
            override fun instant(): Instant = now
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?): Clock = this
        }
        val slowBuilder = RestClient.builder()
        val slowServer = MockRestServiceServer.bindTo(slowBuilder).build()
        slowServer.expect(requestTo("http://kc/realms/alinha/protocol/openid-connect/token"))
            .andRespond(withSuccess("""{"access_token":"tok","expires_in":300}""", MediaType.APPLICATION_JSON))
        slowServer.expect(requestTo(users)).andExpect(method(HttpMethod.GET))
            .andRespond { request ->
                clock.now = clock.now.plusSeconds(11) // the read used up the 10 s budget
                withSuccess(user, MediaType.APPLICATION_JSON).createResponse(request)
            }
        slowServer.expect(ExpectedCount.never(), requestTo(users)).andExpect(method(HttpMethod.PUT))
        val slow = KeycloakIdentityDirectory(IdentityProperties(baseUrl = "http://kc", clientSecret = "s3cret"), slowBuilder, clock)

        val error = assertThrows<IllegalStateException> { slow.assignTenant(subject, tenant) }

        assertThat(error.message).contains("exceeded")
        slowServer.verify()
    }

    @Test
    fun `an undo that takes too long stops before the next call`() {
        // Each reading of the clock moves it 2 s forward, so the 5 s budget runs out after a couple of calls.
        val slowClock = object : Clock() {
            private var now = Instant.parse("2026-11-16T10:00:00Z")
            override fun instant(): Instant = now.also { now = now.plusSeconds(2) }
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?): Clock = this
        }
        val slowBuilder = RestClient.builder()
        val slowServer = MockRestServiceServer.bindTo(slowBuilder).ignoreExpectOrder(true).build()
        slowServer.expect(ExpectedCount.between(0, 1), requestTo("http://kc/realms/alinha/protocol/openid-connect/token"))
            .andRespond(withSuccess("""{"access_token":"tok","expires_in":300}""", MediaType.APPLICATION_JSON))
        slowServer.expect(ExpectedCount.between(0, 1), requestTo(users)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""{"id":"$subject","attributes":{"tenant_id":["${tenant.value}"]}}""", MediaType.APPLICATION_JSON))
        slowServer.expect(ExpectedCount.between(0, 1), requestTo("$users/role-mappings/realm/available"))
            .andRespond(withSuccess("[$defaultRoles]", MediaType.APPLICATION_JSON))
        val slow = KeycloakIdentityDirectory(IdentityProperties(baseUrl = "http://kc", clientSecret = "s3cret"), slowBuilder, slowClock)

        val error = assertThrows<IllegalStateException> { slow.revokeTenant(subject, tenant) }

        assertThat(error.message).contains("exceeded")
    }

    @Test
    fun `a rejected token is dropped and a new one is fetched next time`() {
        expectToken()
        server.expect(requestTo(users)).andExpect(method(HttpMethod.GET)).andRespond(withUnauthorizedRequest())
        expectToken()
        expectGet(users, """{"id":"$subject","attributes":{}}""")

        assertThrows<HttpClientErrorException.Unauthorized> { directory.assignTenant(subject, tenant) }
        directory.revokeTenant(subject, tenant) // not ours: stops after reading the user

        server.verify()
    }

    @Test
    fun `joining by invitation maps the analyst role, not responsible`() {
        val analyst = """{"id":"r-an","name":"analyst"}"""
        expectToken()
        expectGet(users, user)
        server.expect(requestTo(users)).andExpect(method(HttpMethod.PUT))
            .andExpect(content().json("""{"attributes":{"tenant_id":["${tenant.value}"]}}"""))
            .andRespond(withNoContent())
        expectGet("$users/role-mappings/realm", "[$defaultRoles]")
        expectGet("$users/role-mappings/realm/available", """[$responsible,$analyst]""")
        expectGet("$users/role-mappings/realm/composite", """[$defaultRoles,{"id":"r-reg","name":"registrant"}]""")
        server.expect(requestTo("$users/role-mappings/realm")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json("[$analyst]", JsonCompareMode.STRICT))
            .andRespond(withNoContent())
        server.expect(requestTo("$users/role-mappings/realm")).andExpect(method(HttpMethod.DELETE))
            .andExpect(content().json("""[{"id":"r-default","name":"default-roles-alinha"}]"""))
            .andRespond(withNoContent())

        directory.joinTenant(subject, tenant, "analyst")

        server.verify()
    }

    @Test
    fun `members are the accounts with the tenant attribute, with their roles`() {
        val other = "8d0c1f4e-0000-4000-8000-000000000002"
        expectToken()
        server.expect(requestTo(containsString("/admin/realms/alinha/users?q=tenant_id:${tenant.value}")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """[{"id":"$subject","email":"ana@empresa.pt","firstName":"Ana","lastName":"Silva",
                        "attributes":{"tenant_id":["${tenant.value}"]}},
                       {"id":"$other","email":"x@outra.pt","attributes":{"tenant_id":["${UUID.randomUUID()}"]}}]""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        expectGet("$users/role-mappings/realm", """[{"id":"r-an","name":"analyst"}]""")

        val members = directory.members(tenant)

        // The second account came back from the search but is in another tenant: dropped, and its roles never read.
        assertThat(members).containsExactly(DirectoryMember(subject, "ana@empresa.pt", "Ana Silva", setOf("analyst")))
        server.verify()
    }

    @Test
    fun `ending sessions signs the account out`() {
        expectToken()
        server.expect(requestTo("$users/logout")).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer tok"))
            .andRespond(withNoContent())

        directory.endSessions(subject)

        server.verify()
    }
}
