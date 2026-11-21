package pt.alinha

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * The `demo` profile seeds made-up organizations (db/demo) straight into plugin tables. It is meant for local
 * development and the demo server only. Alone, it is local development. With any other profile (`prod`,
 * `staging`, `production`, ...) it is a deployment, and the app refuses to start unless
 * `alinha.demo.allow-in-deployment=true` says that deployment is the demo server. An allowlist, so a profile name
 * nobody thought of can never let demo data into a real customer deployment.
 */
@Component
@Profile(DemoProfileGuard.DEMO)
class DemoProfileGuard(private val environment: Environment) : InitializingBean {
    override fun afterPropertiesSet() {
        val others = environment.activeProfiles.filter { it != DEMO }
        check(others.isEmpty() || environment.getProperty(ALLOW, Boolean::class.java, false)) {
            "The '$DEMO' profile seeds made-up organizations and must not run on a real deployment. It is active " +
                "together with ${others.joinToString { "'$it'" }}: set $ALLOW=true only on the demo server."
        }
        log.warn("Profile '$DEMO' is active: demo organizations are seeded from db/demo (never on a real customer deployment)")
    }

    companion object {
        const val DEMO = "demo"
        const val ALLOW = "alinha.demo.allow-in-deployment"
        private val log = LoggerFactory.getLogger(DemoProfileGuard::class.java)
    }
}
