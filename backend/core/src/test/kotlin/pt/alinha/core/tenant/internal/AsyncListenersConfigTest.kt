package pt.alinha.core.tenant.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import pt.alinha.pluginapi.DomainEvent
import pt.alinha.pluginapi.TenantId
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class SomethingHappened(
    override val tenantId: TenantId,
    override val correlationId: UUID,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent

internal open class AsyncWork {
    open val failed = CountDownLatch(1)

    @Async
    open fun threadName(): CompletableFuture<String> = CompletableFuture.completedFuture(Thread.currentThread().name)

    @Async
    open fun fail(event: SomethingHappened) {
        failed.countDown()
        error("identity provider down")
    }
}

@ExtendWith(OutputCaptureExtension::class)
class AsyncListenersConfigTest {
    private val context = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration::class.java))
        .withUserConfiguration(AsyncListenersConfig::class.java)
        .withBean(AsyncWork::class.java)

    @Test
    fun `async methods run on the bounded, named listener executor`() = context.run { app ->
        val executor = app.getBean(AsyncListenersConfig.LISTENER_EXECUTOR, ThreadPoolTaskExecutor::class.java)
        assertThat(executor.queueCapacity).isEqualTo(500)
        assertThat(executor.maxPoolSize).isEqualTo(8)

        val name = app.getBean(AsyncWork::class.java).threadName().get(5, TimeUnit.SECONDS)

        assertThat(name).startsWith("async-listener-")
    }

    @Test
    fun `an exception thrown by an async listener is logged with the event's tenant`(output: CapturedOutput) =
        context.run { app ->
            val tenant = UUID.randomUUID()
            val correlation = UUID.randomUUID()
            val work = app.getBean(AsyncWork::class.java)

            work.fail(SomethingHappened(TenantId(tenant), correlation))

            assertThat(work.failed.await(5, TimeUnit.SECONDS)).isTrue()
            awaitLogged(output, tenant.toString())
            assertThat(output).contains("AsyncWork.fail", "SomethingHappened", correlation.toString(), "identity provider down")
        }

    @Test
    fun `an exception without an event is still logged`(output: CapturedOutput) {
        LoggingAsyncExceptionHandler().handleUncaughtException(
            IllegalStateException("boom"), AsyncWork::class.java.getMethod("threadName"),
        )

        assertThat(output).contains("Async method AsyncWork.threadName failed", "boom")
    }

    /** The handler runs on the executor's thread, after the method returned: wait for its line. */
    private fun awaitLogged(output: CapturedOutput, text: String) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (text !in output.out && System.nanoTime() < until) Thread.sleep(20)
        assertThat(output.out).contains(text)
    }
}
