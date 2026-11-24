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
import java.util.UUID

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
/**
 * Upper bound for listing the consultants or assigning one an organization (FR04). These run in an event listener's
 * transaction, not in a user's request, so the bound only keeps a stuck identity provider from holding the connection.
 */
private val CONSULTANT_DEADLINE: Duration = Duration.ofSeconds(10)
/** Page size when reading the consultants group; the Admin API returns at most this many members per call. */
private const val MEMBERS_PAGE = 100
/** Renew the service-account token this long before it expires. */
private val TOKEN_MARGIN: Duration = Duration.ofSeconds(10)

/** One realm role as the Admin REST API returns it; the id is needed to map or unmap it. */
data class RoleRepresentation(val id: String, val name: String)

private data class AccessToken(
    @param:JsonProperty("access_token") val accessToken: String,
    @param:JsonProperty("expires_in") val expiresIn: Long = 0,
)

private data class CachedToken(val value: String, val renewAt: Instant)

private data class GroupRepresentation(val id: String, val name: String)

/**
 * [IdentityDirectory] and [ConsultantIdentity] on the Keycloak Admin REST API, with the `alinha-provisioner` service account
 * (client credentials). The Keycloak user id is the token's `sub`.
 *
 * A self-registered account gets `registrant` through the realm's default composite role. Assigning a tenant removes
 * that composite mapping, after mapping its other roles (`offline_access`, `uma_authorization`) directly, so only
 * `registrant` goes away and offline sessions keep working. The whole user representation is read and written back, because a partial
 * `PUT` would fail the realm's user profile validation.
 *
 * Consultants are the members of the [IdentityProperties.consultantsGroup] group, which grants the `consultant` role.
 * A group, not the role itself, because listing a role's users needs `view-realm`, which the service account does not
 * have; reading a group's members only needs the `view-users` it already has.
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
) : IdentityDirectory, ConsultantIdentity {

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

    override fun assignTenant(subject: String, tenantId: TenantId) = forgettingRejectedToken {
        val deadline = Deadline(ASSIGN_DEADLINE, "assignment")
        fun <T> step(call: () -> T): T = deadline.step(call)
        val token = step { token() }
        val user = step { readUser(token, subject) }
        step { writeUser(token, subject, user, attributesOf(user) + (TENANT_CLAIM to listOf(tenantId.value.toString()))) }
        val mapped = step { mappedRealmRoles(token, subject) }
        val toMap = buildList {
            if (mapped.none { it.name == RESPONSIBLE_ROLE }) {
                val responsible = step { availableRealmRoles(token, subject) }.filter { it.name == RESPONSIBLE_ROLE }
                check(responsible.isNotEmpty()) { "Realm role '$RESPONSIBLE_ROLE' is missing" }
                addAll(responsible)
            }
            if (mapped.any { it.name == defaultRoles() }) addAll(step { rolesKeptFromDefault(token, subject, mapped) })
        }
        step { mapRoles(token, subject, toMap) }
        step { unmapRoles(token, subject, mapped.filter { it.name == defaultRoles() }) }
    }

    override fun consultants(): List<Consultant> = forgettingRejectedToken {
        val deadline = Deadline(CONSULTANT_DEADLINE, "consultant listing")
        val token = deadline.step { token() }
        consultantMembers(token, deadline).map { Consultant(it.id, tenantsOf(it.attributes)) }
    }

    override fun assign(subject: String, tenantId: TenantId) = forgettingRejectedToken {
        val deadline = Deadline(CONSULTANT_DEADLINE, "consultant assignment")
        val token = deadline.step { token() }
        require(consultantMembers(token, deadline).any { it.id == subject }) { "Not an enabled consultant" }
        val user = deadline.step { readUser(token, subject) }
        val attributes = attributesOf(user)
        val current = attributes[ASSIGNED_TENANTS_CLAIM].orEmpty()
        if (current.any { it.equals(tenantId.value.toString(), ignoreCase = true) }) return@forgettingRejectedToken
        deadline.step {
            writeUser(token, subject, user, attributes + (ASSIGNED_TENANTS_CLAIM to current + tenantId.value.toString()))
        }
    }

    private data class Member(val id: String, val attributes: Map<String, List<String>>)

    /** Enabled members of the consultants group, every page. The group must exist (deploy/keycloak/alinha-realm.json). */
    private fun consultantMembers(token: String, deadline: Deadline): List<Member> {
        val name = properties.consultantsGroup
        val group = deadline.step {
            http.get().uri("/admin/realms/{realm}/groups?search={name}&exact=true", properties.realm, name)
                .headers { it.setBearerAuth(token) }.retrieve().body<List<GroupRepresentation>>().orEmpty()
        }.firstOrNull { it.name == name } ?: error("Group '$name' is missing in the identity provider")
        val members = mutableListOf<Map<String, Any?>>()
        do {
            val page = deadline.step {
                http.get()
                    .uri(
                        "/admin/realms/{realm}/groups/{id}/members?first={first}&max={max}&briefRepresentation=false",
                        properties.realm, group.id, members.size, MEMBERS_PAGE,
                    )
                    .headers { it.setBearerAuth(token) }.retrieve().body<List<Map<String, Any?>>>().orEmpty()
            }
            members += page
        } while (page.size == MEMBERS_PAGE)
        return members.filter { it["enabled"] == true }.map { Member(it["id"] as String, attributesOf(it)) }
    }

    /** The `tenants` attribute as tenant ids. A value that is not a UUID is skipped, as token resolution does. */
    private fun tenantsOf(attributes: Map<String, List<String>>): Set<TenantId> =
        attributes[ASSIGNED_TENANTS_CLAIM].orEmpty()
            .mapNotNull { runCatching { TenantId(UUID.fromString(it)) }.getOrNull() }
            .toSet()

    /** The roles the account gets through the default composite, except `registrant`, that are not mapped directly. */
    private fun rolesKeptFromDefault(token: String, subject: String, mapped: List<RoleRepresentation>) =
        effectiveRealmRoles(token, subject).filter { role ->
            role.name != REGISTRANT_ROLE && role.name != defaultRoles() && mapped.none { it.id == role.id }
        }

    override fun revokeTenant(subject: String, tenantId: TenantId) = forgettingRejectedToken {
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
        val responsible = step { mappedRealmRoles(token, subject) }.filter { it.name == RESPONSIBLE_ROLE }
        step { unmapRoles(token, subject, responsible) }
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
    private fun <T> forgettingRejectedToken(block: () -> T): T {
        return try {
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
