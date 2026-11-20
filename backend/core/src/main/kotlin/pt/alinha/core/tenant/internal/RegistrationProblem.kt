package pt.alinha.core.tenant.internal

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

private const val MILLIS_PER_SECOND = 1_000L

/**
 * A refused registration: Problem Details with a `code` (ADR 0003) and, when trying again later can work, a
 * `Retry-After` header in whole seconds. A subclass because [ResponseStatusException.getHeaders] is always empty.
 */
internal class RegistrationProblem(
    status: HttpStatus,
    reason: String,
    code: String,
    retryAfter: Duration? = null,
    cause: Throwable? = null,
) : ResponseStatusException(status, reason, cause) {

    private val responseHeaders = HttpHeaders().apply {
        retryAfter?.let { set(HttpHeaders.RETRY_AFTER, retryAfterSeconds(it).toString()) }
    }

    init {
        body.setProperty("code", code)
    }

    override fun getHeaders(): HttpHeaders = responseHeaders
}

/** Rounded up, and at least 1: `Retry-After: 0` would invite an immediate retry. */
internal fun retryAfterSeconds(wait: Duration): Long =
    maxOf(1L, (wait.toMillis() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND)
