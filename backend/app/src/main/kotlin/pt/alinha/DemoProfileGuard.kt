package pt.alinha

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.URI

/**
 * The `demo` profile seeds made-up organizations (db/demo) straight into plugin tables. It is meant for local
 * development and the demo server only, so without `alinha.demo.allow-in-deployment=true` it starts only when it
 * looks like local development: `demo` is the only active profile **and** the database is on this machine
 * (localhost). Anything else (another profile such as `prod`, `staging`, `production`, or a remote database, even
 * with `demo` alone) refuses to start, so demo data can never reach a real deployment by a copied env var.
 */
@Component
@Profile(DemoProfileGuard.DEMO)
class DemoProfileGuard(private val environment: Environment) : InitializingBean {
    override fun afterPropertiesSet() {
        val others = environment.activeProfiles.filter { it != DEMO }
        val url = environment.getProperty(DATASOURCE_URL).orEmpty()
        val local = others.isEmpty() && isLocalDatabase(url)
        check(local || environment.getProperty(ALLOW, Boolean::class.java, false)) {
            val why = if (others.isNotEmpty()) "it is active together with ${others.joinToString { "'$it'" }}"
            else "the database is not on localhost"
            "The '$DEMO' profile seeds made-up organizations and must not run on a real deployment, but $why. " +
                "Set $ALLOW=true only on the demo server."
        }
        log.warn("Profile '$DEMO' is active: demo organizations are seeded from db/demo (never on a real customer deployment)")
    }

    companion object {
        const val DEMO = "demo"
        const val ALLOW = "alinha.demo.allow-in-deployment"
        const val DATASOURCE_URL = "spring.datasource.url"
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")
        private val log = LoggerFactory.getLogger(DemoProfileGuard::class.java)

        /** `jdbc:postgresql://localhost:5432/alinha` is local; any other host, or no URL, is not. */
        internal fun isLocalDatabase(jdbcUrl: String): Boolean {
            if (!jdbcUrl.startsWith("jdbc:")) return false
            val host = runCatching { URI(jdbcUrl.removePrefix("jdbc:")).host }.getOrNull() ?: return false
            return host.lowercase() in LOCAL_HOSTS
        }
    }
}
