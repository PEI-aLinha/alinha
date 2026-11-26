package pt.alinha.core.tenant.internal

import org.slf4j.LoggerFactory
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import pt.alinha.pluginapi.DomainEvent
import java.lang.reflect.Method
import java.time.Duration
import java.util.concurrent.Executor

/**
 * Enables `@Async` for the whole app. Event listeners that work as a tenant need it: `TenantContext.runAs` refuses to
 * start inside an HTTP request or a transaction, so the documented recipe is `@Async @TransactionalEventListener`
 * (pt.alinha.organization.events.OrganizationRegistered, docs/events.md).
 *
 * An `@Async` method without its own executor runs on [LISTENER_EXECUTOR]: bounded, so a burst of events queues
 * instead of starting a thread each, and named, so its threads are recognisable in logs and thread dumps. It is built
 * with Boot's builder, so `TaskDecorator` beans still apply. An exception thrown by such a method reaches no caller:
 * [LoggingAsyncExceptionHandler] logs it with the event's tenant and correlation id.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncListenersConfig {

    @Bean(LISTENER_EXECUTOR)
    fun asyncListenerExecutor(builder: ThreadPoolTaskExecutorBuilder): ThreadPoolTaskExecutor = builder
        .corePoolSize(CORE_THREADS)
        .maxPoolSize(MAX_THREADS)
        .queueCapacity(QUEUE_CAPACITY)
        .threadNamePrefix("async-listener-")
        .awaitTermination(true)
        .awaitTerminationPeriod(SHUTDOWN_WAIT)
        .build()

    @Bean
    fun asyncListenerConfigurer(@Qualifier(LISTENER_EXECUTOR) executor: Executor): AsyncConfigurer = object : AsyncConfigurer {
        override fun getAsyncExecutor(): Executor = executor
        override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler = LoggingAsyncExceptionHandler()
    }

    companion object {
        const val LISTENER_EXECUTOR = "asyncListenerExecutor"

        // Events are rare (a registration, a finished job): a few threads are enough. The queue absorbs a burst; past
        // it, up to MAX_THREADS run. Beyond that the publisher gets a TaskRejectedException and the event publication
        // stays incomplete in the registry (docs/events.md).
        private const val CORE_THREADS = 4
        private const val MAX_THREADS = 8
        private const val QUEUE_CAPACITY = 500
        private val SHUTDOWN_WAIT: Duration = Duration.ofSeconds(30)
    }
}

/** Logs what an `@Async` method threw, with the tenant and correlation id of the event it was handling, if any. */
class LoggingAsyncExceptionHandler : AsyncUncaughtExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handleUncaughtException(ex: Throwable, method: Method, vararg params: Any?) {
        val name = "${method.declaringClass.simpleName}.${method.name}"
        val event = params.filterIsInstance<DomainEvent>().firstOrNull()
        if (event == null) {
            log.error("Async method {} failed", name, ex)
        } else {
            log.error(
                "Async method {} failed on {} for tenant {} (correlation {})",
                name, event.javaClass.simpleName, event.tenantId.value, event.correlationId, ex,
            )
        }
    }
}
