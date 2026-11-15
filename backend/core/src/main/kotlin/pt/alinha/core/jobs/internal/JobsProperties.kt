package pt.alinha.core.jobs.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("alinha.jobs")
data class JobsProperties(
    val relay: Relay = Relay(),
    val defaultMaxAttempts: Int = 3,
    val streamMaxLength: Long = 10_000,
) {
    init {
        require(defaultMaxAttempts >= 1) { "alinha.jobs.default-max-attempts must be >= 1" }
        require(streamMaxLength > 0) { "alinha.jobs.stream-max-length must be > 0" }
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
}
