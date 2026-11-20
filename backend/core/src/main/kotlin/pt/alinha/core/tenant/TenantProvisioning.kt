package pt.alinha.core.tenant

import pt.alinha.pluginapi.TenantId

/**
 * Creates the organization (tenant) of the person who is registering it (ADR 0004). Call it from a
 * [TenantAgnostic] endpoint, outside any transaction.
 *
 * The caller must hold the `registrant` role and no tenant. [block] runs as the new tenant inside one
 * transaction, so everything it writes (and its audit entries) belongs to that tenant. When the transaction is
 * ready to commit, the account gets the tenant and the `responsible` role in the identity provider. Any failure
 * rolls everything back. The caller's token stays stale until the client refreshes it.
 *
 * Refusals are [org.springframework.web.ErrorResponseException]s whose Problem Details body has a `code`
 * property, with `Retry-After` in [org.springframework.web.ErrorResponseException.getHeaders] where waiting helps.
 * The app has no global Problem Details handler, and Spring's default resolver sends neither the `code` nor the
 * headers, so **the calling plugin must render them**: return `e.body` with `e.headers` and `e.statusCode` from an
 * `@ExceptionHandler(ErrorResponseException::class)` (see the organization plugin's `OrganizationProblems`), and
 * test `$.code` and `Retry-After` on the real response.
 *
 * @throws org.springframework.web.ErrorResponseException 409 `ORGANIZATION_ALREADY_EXISTS` when the account already
 *   has, or already created, an organization; 429 `TOO_MANY_REGISTRATIONS` over the per-client limit; 503
 *   `REGISTRATION_BUSY` or `IDENTITY_UNAVAILABLE`. All but the 409 carry `Retry-After`.
 * @throws org.springframework.security.access.AccessDeniedException when the caller is not a `registrant`.
 */
interface TenantProvisioning {
    fun <T> provisionForCurrentUser(block: (TenantId) -> T): T
}
