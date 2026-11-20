package pt.alinha.core.tenant.internal

import org.slf4j.MDC
import org.springframework.stereotype.Component
import pt.alinha.core.tenant.CorrelationContext
import java.util.UUID

/** MDC key, printed by `logging.pattern.correlation` in application.yml. */
internal const val CORRELATION_MDC_KEY = "correlationId"

/** Correlation id of the work running on this thread: set by [CorrelationIdFilter] and by both `runAs`. */
internal object CorrelationScope {
    private val scoped = ThreadLocal<UUID>()

    fun currentOrNull(): UUID? = scoped.get()

    fun <T> runWith(id: UUID, block: () -> T): T {
        val previous = scoped.get()
        scoped.set(id)
        MDC.put(CORRELATION_MDC_KEY, id.toString())
        try {
            return block()
        } finally {
            if (previous == null) {
                scoped.remove()
                MDC.remove(CORRELATION_MDC_KEY)
            } else {
                scoped.set(previous)
                MDC.put(CORRELATION_MDC_KEY, previous.toString())
            }
        }
    }
}

@Component
class ScopedCorrelationContext : CorrelationContext {
    override fun current(): UUID = CorrelationScope.currentOrNull() ?: UUID.randomUUID()
}
