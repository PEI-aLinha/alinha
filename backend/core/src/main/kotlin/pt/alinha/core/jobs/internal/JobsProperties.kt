package pt.alinha.core.jobs.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("alinha.jobs")
data class JobsProperties(
    val relay: Relay = Relay(),
    val results: Results = Results(),
    val defaultMaxAttempts: Int = 3,
) {
    init {
        require(defaultMaxAttempts >= 1) { "alinha.jobs.default-max-attempts must be >= 1" }
    }

    /** poll-interval-ms is read by @Scheduled directly; it is here so it is documented and bound. */
    data class Relay(
        val enabled: Boolean = true,
        val pollIntervalMs: Long = 1_000,
        val batchSize: Int = 50,
        val maxPublishFailures: Int = 10,
        val backoffBase: Duration = Duration.ofSeconds(2),
        val backoffMax: Duration = Duration.ofMinutes(5),
    ) {
        init {
            require(pollIntervalMs > 0 && batchSize > 0 && maxPublishFailures >= 1) { "Invalid alinha.jobs.relay settings" }
            // Zero would make a failed job due again at once, so one round would use up all its publish failures.
            require(backoffBase.isPositive) { "alinha.jobs.relay.backoff-base must be positive" }
            require(backoffMax >= backoffBase) { "backoff-max must be >= backoff-base" }
        }
    }

    /**
     * Consumer of `alinha:jobs:results`. poll-interval-ms is read by @Scheduled directly.
     * [consumerName] blank = a random name per start (several instances never share one).
     * An entry left unacknowledged for [claimMinIdle] (its consumer died, or applying it failed) is claimed again;
     * after [maxDeliveries] attempts that reached a database taking writes (not stream deliveries), or
     * [maxTotalDeliveries] deliveries whatever happened (attempts given back during outages included), it goes to
     * the dead-letter stream instead and its job is failed. A `result` field longer than
     * [maxResultBytes] (UTF-8) is dead-lettered without it: it would be stored as is in core.job_outbox.
     */
    data class Results(
        val enabled: Boolean = true,
        val pollIntervalMs: Long = 1_000,
        val batchSize: Int = 50,
        val claimMinIdle: Duration = Duration.ofMinutes(1),
        val maxDeliveries: Int = 5,
        val maxTotalDeliveries: Int = 100,
        val consumerName: String = "",
        val maxResultBytes: Int = 1024 * 1024,
    ) {
        init {
            require(pollIntervalMs > 0 && batchSize > 0 && maxDeliveries >= 1 && maxResultBytes > 0) {
                "Invalid alinha.jobs.results settings"
            }
            require(!claimMinIdle.isNegative) { "alinha.jobs.results.claim-min-idle must not be negative" }
            require(maxTotalDeliveries >= maxDeliveries) { "max-total-deliveries must be >= max-deliveries" }
        }
    }
}
