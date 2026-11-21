package pt.alinha.core.tenant.internal

import org.assertj.core.api.Assertions.assertThat
import org.hibernate.integrator.spi.Integrator
import org.hibernate.jpa.boot.spi.IntegratorProvider
import org.hibernate.jpa.boot.spi.JpaSettings
import org.junit.jupiter.api.Test

class HibernateTenantResolverTest {
    private val resolver = HibernateTenantResolver(JwtTenantContext())

    private fun integrators(properties: Map<String, Any>): List<Integrator> =
        (properties[JpaSettings.INTEGRATOR_PROVIDER] as IntegratorProvider).integrators

    @Test
    fun `registers the no-tenant write guard`() {
        val properties = mutableMapOf<String, Any>()

        resolver.customize(properties)

        assertThat(integrators(properties)).containsExactly(NoTenantWriteGuard)
    }

    @Test
    fun `keeps integrators registered before it`() {
        val other = object : Integrator {}
        val properties = mutableMapOf<String, Any>(JpaSettings.INTEGRATOR_PROVIDER to IntegratorProvider { listOf(other) })

        resolver.customize(properties)

        assertThat(integrators(properties)).containsExactly(other, NoTenantWriteGuard)
    }
}
