package pt.alinha.core.tenant

import pt.alinha.pluginapi.TenantId
import java.time.Instant
import java.util.UUID

/** A new invitation. [token] is the secret for the link; it is shown once and only its hash is stored. */
data class IssuedInvitation(val id: UUID, val email: String, val token: String, val expiresAt: Instant)

/** An invitation that was neither accepted nor revoked. [expired] ones stay listed so they can be sent again. */
data class PendingInvitation(val id: UUID, val email: String, val createdAt: Instant, val expiresAt: Instant, val expired: Boolean)

/** A person with an account in the organization. [role] is `responsible` or `analyst`. */
data class TenantMember(val subject: String, val email: String?, val name: String?, val role: String)

/**
 * Who belongs to the current organization, and analyst invitations (ADR 0007, organization#6). The organization
 * plugin owns the endpoints and their permissions; the core owns the invitation secrets and the one step a plugin
 * cannot take: giving an account a tenant.
 *
 * Refusals are [org.springframework.web.ErrorResponseException]s whose Problem Details body has a `code`, as in
 * [TenantProvisioning]: the calling plugin must render `e.body` with `e.headers` and `e.statusCode`.
 */
interface TenantMembership {
    /**
     * Invites [email] to the current tenant as an analyst. Valid for `alinha.invitations.ttl` (7 days) and once. A
     * pending invitation to the same email is revoked: inviting again is how an invitation is sent again.
     *
     * @throws org.springframework.web.ErrorResponseException 422 `INVALID_EMAIL`.
     */
    fun invite(email: String): IssuedInvitation

    /** The current tenant's pending invitations, newest first. */
    fun pendingInvitations(): List<PendingInvitation>

    /** @throws org.springframework.web.ErrorResponseException 404 `INVITATION_NOT_FOUND` (not pending in this tenant). */
    fun revokeInvitation(invitationId: UUID)

    /**
     * The calling account accepts the invitation of [token] and joins its tenant as an analyst. Call it from a
     * [TenantAgnostic] endpoint, outside any transaction. The caller must be a `registrant` with no tenant and a
     * verified email equal to the invited one. Its token stays stale until the client refreshes it.
     *
     * @throws org.springframework.web.ErrorResponseException 404 `INVITATION_NOT_FOUND` (unknown or revoked),
     *   410 `INVITATION_EXPIRED`, 409 `INVITATION_ALREADY_USED`, 403 `INVITATION_FOR_ANOTHER_EMAIL`,
     *   409 `ORGANIZATION_ALREADY_EXISTS` (the account already has an organization), 503 `REGISTRATION_BUSY` or
     *   `IDENTITY_UNAVAILABLE` with `Retry-After`.
     * @throws org.springframework.security.access.AccessDeniedException when the caller is not a `registrant`.
     */
    fun acceptForCurrentUser(token: String): TenantId

    /** Everyone with an account in the current tenant. Consultants are not members: they are assigned. */
    fun members(): List<TenantMember>

    /**
     * Removes an analyst from the current tenant and ends their sessions. The account itself stays, as a new account.
     *
     * @throws org.springframework.web.ErrorResponseException 404 `MEMBER_NOT_FOUND` (not an analyst of this tenant),
     *   503 `IDENTITY_UNAVAILABLE`.
     */
    fun removeAnalyst(subject: String)
}
