package pt.alinha.core.tenant.internal

import com.fasterxml.jackson.annotation.JsonProperty
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import pt.alinha.pluginapi.TenantId
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val DEFAULT_ROLES = "default-roles-"
private const val RESPONSIBLE_ROLE = "responsible"
private const val REGISTRANT_ROLE = "registrant"
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
private val READ_TIMEOUT: Duration = Duration.ofSeconds(3)
/**
 * Upper bound for one assignment; it runs inside the provisioning transaction (ADR 0004). Checked before each call,
 * so the worst case is this plus one call (connect + read timeouts), about 15 s.
 */
private val ASSIGN_DEADLINE: Duration = Duration.ofSeconds(10)
/**
 * Upper bound for undoing a failed assignment. It runs after the rollback but before the transaction releases its
 * connection and provisioning slot, so it is kept short: at most this plus one call, about 10 s.
 */
private val REVOKE_DEADLINE: Duration = Duration.ofSeconds(5)
/** Upper bound of [KeycloakIdentityDirectory.members]: far above an SME's team, low enough to keep the call small. */
internal const val MAX_MEMBERS = 200
/** Renew the service-account token this long before it expires. */
private val TOKEN_MARGIN: Duration = Duration.ofSeconds(10)

/** One realm role as the Admin REST API returns it; the id is needed to map or unmap it. */
data class RoleRepresentation(val id: String, val name: String)

private data class AccessToken(
    @param:JsonProperty("access_token") val accessToken: String,
    @param:JsonProperty("expires_in") val expiresIn: Long = 0,
)

private data class CachedToken(val value: String, val renewAt: Instant)

/**
 * [IdentityDirectory] on the Keycloak Admin REST API, with the `alinha-provisioner` service account
 * (client credentials). The Keycloak user id is the token's `sub`.
 *
 * A self-registered account gets `registrant` through the realm's default composite role. Assigning a tenant removes
 * that composite mapping, after mapping its other roles (`offline_access`, `uma_authorization`) directly, so only
 * `registrant` goes away and offline sessions keep working. The whole user representation is read and written back, because a partial
 * `PUT` would fail the realm's user profile validation.
 *
 * Provisioning calls this inside its database transaction, so the calls are kept short: the service-account
 * token is cached until shortly before it expires, and every call has a connect and read timeout.
 */
@Component
@EnableConfigurationProperties(IdentityProperties::class)
class KeycloakIdentityDirectory internal constructor(
    private val properties: IdentityProperties,
    restClientBuilder: RestClient.Builder,
    private val clock: Clock = Clock.systemUTC(),
) : IdentityDirectory, IdentityMembership {

    @Volatile private var cached: CachedToken? = null

    /** With the application's observation registry, so the calls get `http.client.requests` metrics and traces. */
    @Autowired
    constructor(properties: IdentityProperties, observations: ObjectProvider<ObservationRegistry>) : this(
        properties,
        RestClient.builder()
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(CONNECT_TIMEOUT)
                    setReadTimeout(READ_TIMEOUT)
                },
            )
            .observationRegistry(observations.getIfAvailable { ObservationRegistry.NOOP }),
    )

    private val http = restClientBuilder.baseUrl(properties.baseUrl.trimEnd('/')).build()
    private val users = "/admin/realms/${properties.realm}/users"

    override fun assignTenant(subject: String, tenantId: TenantId) = assign(subject, tenantId, RESPONSIBLE_ROLE)

    override fun revokeTenant(subject: String, tenantId: TenantId) = revoke(subject, tenantId, RESPONSIBLE_ROLE)

    override fun joinTenant(subject: String, tenantId: TenantId, role: String) = assign(subject, tenantId, role)

    override fun leaveTenant(subject: String, tenantId: TenantId, role: String) = revoke(subject, tenantId, role)

    /**
     * Accounts whose `tenant_id` attribute is [tenantId], with their realm roles. One call for the users plus one
     * per user for the roles: an organization has a handful of members, at most [MAX_MEMBERS].
     */
    override fun members(tenantId: TenantId): List<DirectoryMember> {
        var result: List<DirectoryMember> = emptyList()
        forgettingRejectedToken {
            val token = token()
            val found = http.get()
                .uri { it.path(users).queryParam("q", "$TENANT_CLAIM:${tenantId.value}").queryParam("exact", true)
                    .queryParam("briefRepresentation", false).queryParam("max", MAX_MEMBERS).build() }
                .headers { it.setBearerAuth(token) }.retrieve()
                .body<List<Map<String, Any?>>>().orEmpty()
            result = found
                // Keycloak's attribute search is a filter we do not control: check the attribute ourselves.
                .filter { user -> attributesOf(user)[TENANT_CLAIM].orEmpty().any { it.equals(tenantId.value.toString(), true) } }
                .map { user ->
                    val id = user["id"] as String
                    DirectoryMember(
                        subject = id,
                        email = user["email"] as String?,
                        name = listOfNotNull(user["firstName"] as String?, user["lastName"] as String?)
                            .joinToString(" ").ifBlank { null },
                        roles = mappedRealmRoles(token, id).map { it.name }.toSet(),
                    )
                }
        }
        return result
    }

    /** Signs the account out everywhere, so a removed member's refresh token stops working at once. */
    override fun endSessions(subject: String) = forgettingRejectedToken {
        val token = token()
        http.post().uri("$users/{id}/logout", subject).headers { it.setBearerAuth(token) }.retrieve().toBodilessEntity()
    }

    private fun assign(subject: String, tenantId: TenantId, role: String) = forgettingRejectedToken {
        val deadline = Deadline(ASSIGN_DEADLINE, "assignment")
        fun <T> step(call: () -> T): T = deadline.step(call)
        val token = step { token() }
        val user = step { readUser(token, subject) }
        step { writeUser(token, subject, user, attributesOf(user) + (TENANT_CLAIM to listOf(tenantId.value.toString()))) }
        val mapped = step { mappedRealmRoles(token, subject) }
        val toMap = buildList {
            if (mapped.none { it.name == role }) {
                val granted = step { availableRealmRoles(token, subject) }.filter { it.name == role }
                check(granted.isNotEmpty()) { "Realm role '$role' is missing" }
                addAll(granted)
            }
            if (mapped.any { it.name == defaultRoles() }) addAll(step { rolesKeptFromDefault(token, subject, mapped) })
        }
        step { mapRoles(token, subject, toMap) }
        step { unmapRoles(token, subject, mapped.filter { it.name == defaultRoles() }) }
    }

    /** The roles the account gets through the default composite, except `registrant`, that are not mapped directly. */
    private fun rolesKeptFromDefault(token: String, subject: String, mapped: List<RoleRepresentation>) =
        effectiveRealmRoles(token, subject).filter { role ->
            role.name != REGISTRANT_ROLE && role.name != defaultRoles() && mapped.none { it.id == role.id }
        }

    private fun revoke(subject: String, tenantId: TenantId, role: String) = forgettingRejectedToken {
        val deadline = Deadline(REVOKE_DEADLINE, "undo")
        fun <T> step(call: () -> T): T = deadline.step(call)
        val token = step { token() }
        val user = step { readUser(token, subject) }
        // Only undo our own assignment: if the attribute holds another tenant (or none), a later request already
        // owns the account, or the assignment never got this far. Roles follow the attribute, so leave them too.
        val own = attributesOf(user)[TENANT_CLAIM].orEmpty().any { it.equals(tenantId.value.toString(), ignoreCase = true) }
        if (!own) return@forgettingRejectedToken
        // Restore `registrant` first and drop the attribute last: a failure halfway leaves the attribute in place, so
        // a retry (or #92's reconciliation) still recognises the account, and it is never left without any role.
        // The roles mapped directly from the default composite stay: the composite grants them again anyway.
        val default = step { availableRealmRoles(token, subject) }.filter { it.name == defaultRoles() }
        step { mapRoles(token, subject, default) }
        val granted = step { mappedRealmRoles(token, subject) }.filter { it.name == role }
        step { unmapRoles(token, subject, granted) }
        step { writeUser(token, subject, user, attributesOf(user) - TENANT_CLAIM) }
    }

    /**
     * Runs each call only while [budget] has not run out, so the worst case is the budget plus one call. A step
     * must make exactly one HTTP call, or that bound (and `BUSY_RETRY_AFTER`, which relies on it) no longer holds.
     */
    private inner class Deadline(private val budget: Duration, private val what: String) {
        private val at = clock.instant().plus(budget)

        fun <T> step(call: () -> T): T {
            check(clock.instant().isBefore(at)) { "Identity $what exceeded $budget" }
            return call()
        }
    }

    /** A 401 means the cached token was revoked or the secret rotated: fetch a new one on the next call. */
    private fun forgettingRejectedToken(block: () -> Unit) {
        try {
            block()
        } catch (e: HttpClientErrorException.Unauthorized) {
            cached = null
            throw e
        }
    }

    private fun defaultRoles() = DEFAULT_ROLES + properties.realm

    private fun token(): String {
        cached?.takeIf { clock.instant().isBefore(it.renewAt) }?.let { return it.value }
        check(properties.clientSecret.isNotBlank()) {
            "alinha.identity.client-secret is not set (OpenBao key identity.provisioner-secret)"
        }
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "client_credentials")
            add("client_id", properties.clientId)
            add("client_secret", properties.clientSecret)
        }
        val response = checkNotNull(
            http.post().uri("/realms/{realm}/protocol/openid-connect/token", properties.realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body<AccessToken>(),
        ) { "Empty token response from the identity provider" }
        val renewAt = clock.instant().plusSeconds(response.expiresIn).minus(TOKEN_MARGIN)
        cached = CachedToken(response.accessToken, renewAt)
        return response.accessToken
    }

    private fun readUser(token: String, subject: String): Map<String, Any?> = checkNotNull(
        http.get().uri("$users/{id}", subject).headers { it.setBearerAuth(token) }.retrieve()
            .body<Map<String, Any?>>(),
    ) { "Unknown account" }

    @Suppress("UNCHECKED_CAST")
    private fun attributesOf(user: Map<String, Any?>): Map<String, List<String>> =
        (user["attributes"] as? Map<String, List<String>>).orEmpty()

    /**
     * Writes back the whole representation read by [readUser]: Keycloak 26 refuses a partial `PUT`
     * (`error-user-attribute-required`). A profile change made between the read and this write (e.g. by an admin; users
     * have no account console, see deploy/keycloak/README.md) is overwritten. The window is two back-to-back calls, and
     * only right after registration.
     */
    private fun writeUser(token: String, subject: String, user: Map<String, Any?>, attributes: Map<String, List<String>>) {
        http.put().uri("$users/{id}", subject).headers { it.setBearerAuth(token) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(user + ("attributes" to attributes))
            .retrieve().toBodilessEntity()
    }

    private fun mappedRealmRoles(token: String, subject: String): List<RoleRepresentation> =
        realmRoles(token, "$users/{id}/role-mappings/realm", subject)

    private fun availableRealmRoles(token: String, subject: String): List<RoleRepresentation> =
        realmRoles(token, "$users/{id}/role-mappings/realm/available", subject)

    /** Every realm role the account has, composites expanded. */
    private fun effectiveRealmRoles(token: String, subject: String): List<RoleRepresentation> =
        realmRoles(token, "$users/{id}/role-mappings/realm/composite", subject)

    private fun realmRoles(token: String, uri: String, subject: String): List<RoleRepresentation> =
        http.get().uri(uri, subject).headers { it.setBearerAuth(token) }.retrieve()
            .body<List<RoleRepresentation>>().orEmpty()

    private fun mapRoles(token: String, subject: String, roles: List<RoleRepresentation>) {
        if (roles.isEmpty()) return
        http.post().uri("$users/{id}/role-mappings/realm", subject).headers { it.setBearerAuth(token) }
            .contentType(MediaType.APPLICATION_JSON).body(roles).retrieve().toBodilessEntity()
    }

    private fun unmapRoles(token: String, subject: String, roles: List<RoleRepresentation>) {
        if (roles.isEmpty()) return
        http.method(HttpMethod.DELETE).uri("$users/{id}/role-mappings/realm", subject)
            .headers { it.setBearerAuth(token) }
            .contentType(MediaType.APPLICATION_JSON).body(roles).retrieve().toBodilessEntity()
    }
}
