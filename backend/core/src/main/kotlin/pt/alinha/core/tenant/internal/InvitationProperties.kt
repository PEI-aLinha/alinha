package pt.alinha.core.tenant.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** How long an analyst invitation can be accepted (ADR 0007). */
@ConfigurationProperties("alinha.invitations")
data class InvitationProperties(val ttl: Duration = Duration.ofDays(7))
