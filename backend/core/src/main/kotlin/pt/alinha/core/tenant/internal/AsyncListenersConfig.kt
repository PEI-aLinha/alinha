package pt.alinha.core.tenant.internal

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync

/**
 * Enables `@Async` for the whole app. Event listeners that work as a tenant need it: `TenantContext.runAs` refuses to
 * start inside an HTTP request or a transaction, so the documented recipe is `@Async @TransactionalEventListener`
 * (pt.alinha.organization.events.OrganizationRegistered, docs/events.md). Listeners without their own executor run on
 * Spring Boot's `applicationTaskExecutor`.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncListenersConfig
