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
            require(!backoffBase.isNegative && backoffMax >= backoffBase) { "backoff-max must be >= backoff-base" }
        }
    }

    /**
     * Consumer of `alinha:jobs:results`. poll-interval-ms is read by @Scheduled directly.
     * [consumerName] blank = a random name per start (several instances never share one).
     * An entry left unacknowledged for [claimMinIdle] (its consumer died, or applying it failed) is claimed again;
     * after [maxDeliveries] deliveries it goes to the dead-letter stream instead.
     */
    data class Results(
        val enabled: Boolean = true,
        val pollIntervalMs: Long = 1_000,
        val batchSize: Int = 50,
        val claimMinIdle: Duration = Duration.ofMinutes(1),
        val maxDeliveries: Int = 5,
        val consumerName: String = "",
    ) {
        init {
            require(pollIntervalMs > 0 && batchSize > 0 && maxDeliveries >= 1) { "Invalid alinha.jobs.results settings" }
            require(!claimMinIdle.isNegative) { "alinha.jobs.results.claim-min-idle must not be negative" }
        }
    }
}
