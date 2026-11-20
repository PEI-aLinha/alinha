package pt.alinha.core.tenant.internal

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

internal const val CORRELATION_HEADER = "X-Correlation-Id"

/**
 * Gives every request a correlation id, before security so its logs carry it too. Reuses the caller's
 * [CORRELATION_HEADER] when it is a UUID (e.g. sent by the BFF), otherwise creates one, and echoes it in the
 * response. The value is for tracing only and grants nothing, so a client-chosen one is harmless.
 * Async dispatches (DeferredResult, SSE) and the `/error` page run outside this scope: their logs have no id.
 */
@Component
// Right after Spring's CharacterEncodingFilter (HIGHEST_PRECEDENCE), before Spring Security (-100).
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val id = parse(request.getHeader(CORRELATION_HEADER)) ?: UUID.randomUUID()
        response.setHeader(CORRELATION_HEADER, id.toString())
        CorrelationScope.runWith(id) { chain.doFilter(request, response) }
    }

    /** Only the canonical 36-character form: `UUID.fromString` also accepts short forms like "1-1-1-1-1". */
    private fun parse(header: String?): UUID? =
        header?.takeIf { it.length == UUID_LENGTH }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private companion object {
        const val UUID_LENGTH = 36
    }
}
