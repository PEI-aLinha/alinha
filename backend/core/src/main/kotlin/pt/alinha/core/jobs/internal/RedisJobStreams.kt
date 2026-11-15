package pt.alinha.core.jobs.internal

import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class RedisJobStreams(
    private val redis: StringRedisTemplate,
    private val properties: JobsProperties,
) {
    /** XADD … MAXLEN ~ n. `attempt` lives in the entry, not the envelope, so the contract is the same on every retry. */
    fun publish(job: OutboxJob): RecordId = checkNotNull(
        redis.opsForStream<String, String>().add(
            streamKey(job.type, job.version),
            mapOf(ENVELOPE to job.envelope, ATTEMPT to job.attempt.toString()),
            XAddOptions.maxlen(properties.streamMaxLength).approximateTrimming(true),
        ),
    ) { "Redis returned no record id for job ${job.id}" }

    companion object {
        const val ENVELOPE = "envelope"
        const val ATTEMPT = "attempt"

        fun streamKey(type: String, version: Int) = "alinha:jobs:$type:v$version"
    }
}
