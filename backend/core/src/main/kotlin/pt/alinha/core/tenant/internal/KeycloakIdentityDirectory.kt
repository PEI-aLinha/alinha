package pt.alinha.core.tenant.internal

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import pt.alinha.pluginapi.TenantId
import java.time.Duration

private const val DEFAULT_ROLES = "default-roles-"
private const val RESPONSIBLE_ROLE = "responsible"
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
private val READ_TIMEOUT: Duration = Duration.ofSeconds(10)

/** One realm role as the Admin REST API returns it; the id is needed to map or unmap it. */
data class RoleRepresentation(val id: String, val name: String)

private data class AccessToken(@param:JsonProperty("access_token") val accessToken: String)

/**
 * [IdentityDirectory] on the Keycloak Admin REST API, with the `alinha-provisioner` service account
 * (client credentials). The Keycloak user id is the token's `sub`.
 *
 * A self-registered account gets `registrant` through the realm's default composite role, so assigning a tenant
 * removes that composite mapping. The whole user representation is read and written back, because a partial
 * `PUT` would fail the realm's user profile validation.
 */
@Component
@EnableConfigurationProperties(IdentityProperties::class)
class KeycloakIdentityDirectory internal constructor(
    private val properties: IdentityProperties,
    restClientBuilder: RestClient.Builder,
) : IdentityDirectory {

    @Autowired
    constructor(properties: IdentityProperties) : this(
        properties,
        RestClient.builder().requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT)
                setReadTimeout(READ_TIMEOUT)
            },
        ),
    )

    private val http = restClientBuilder.baseUrl(properties.baseUrl.trimEnd('/')).build()
    private val users = "/admin/realms/${properties.realm}/users"

    override fun assignTenant(subject: String, tenantId: TenantId) {
        val token = token()
        setTenantAttribute(token, subject) { it + (TENANT_CLAIM to listOf(tenantId.value.toString())) }
        val mapped = mappedRealmRoles(token, subject)
        if (mapped.none { it.name == RESPONSIBLE_ROLE }) {
            val responsible = availableRealmRoles(token, subject).filter { it.name == RESPONSIBLE_ROLE }
            check(responsible.isNotEmpty()) { "Realm role '$RESPONSIBLE_ROLE' is missing" }
            mapRoles(token, subject, responsible)
        }
        unmapRoles(token, subject, mapped.filter { it.name == defaultRoles() })
    }

    override fun revokeTenant(subject: String, tenantId: TenantId) {
        val token = token()
        setTenantAttribute(token, subject) { attributes ->
            if (attributes[TENANT_CLAIM] == listOf(tenantId.value.toString())) attributes - TENANT_CLAIM else attributes
        }
        unmapRoles(token, subject, mappedRealmRoles(token, subject).filter { it.name == RESPONSIBLE_ROLE })
        mapRoles(token, subject, availableRealmRoles(token, subject).filter { it.name == defaultRoles() })
    }

    private fun defaultRoles() = DEFAULT_ROLES + properties.realm

    private fun token(): String {
        check(properties.clientSecret.isNotBlank()) {
            "alinha.identity.client-secret is not set (OpenBao key identity.provisioner-secret)"
        }
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "client_credentials")
            add("client_id", properties.clientId)
            add("client_secret", properties.clientSecret)
        }
        return checkNotNull(
            http.post().uri("/realms/{realm}/protocol/openid-connect/token", properties.realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body<AccessToken>(),
        ) { "Empty token response from the identity provider" }.accessToken
    }

    @Suppress("UNCHECKED_CAST")
    private fun setTenantAttribute(
        token: String,
        subject: String,
        change: (Map<String, List<String>>) -> Map<String, List<String>>,
    ) {
        val user = checkNotNull(
            http.get().uri("$users/{id}", subject).headers { it.setBearerAuth(token) }.retrieve()
                .body<Map<String, Any?>>(),
        ) { "Unknown account" }
        val attributes = (user["attributes"] as? Map<String, List<String>>).orEmpty()
        http.put().uri("$users/{id}", subject).headers { it.setBearerAuth(token) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(user + ("attributes" to change(attributes)))
            .retrieve().toBodilessEntity()
    }

    private fun mappedRealmRoles(token: String, subject: String): List<RoleRepresentation> =
        realmRoles(token, "$users/{id}/role-mappings/realm", subject)

    private fun availableRealmRoles(token: String, subject: String): List<RoleRepresentation> =
        realmRoles(token, "$users/{id}/role-mappings/realm/available", subject)

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
