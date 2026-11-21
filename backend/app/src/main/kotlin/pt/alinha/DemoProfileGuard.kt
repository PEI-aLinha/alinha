package pt.alinha

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

/**
 * The `demo` profile seeds made-up organizations (db/demo) straight into plugin tables. It is meant for local
 * development and the demo server only. Together with `prod` it refuses to start unless
 * `alinha.demo.allow-in-prod=true` says this production-like deployment is the demo server, so demo data can never
 * reach a real customer deployment by a stray profile.
 */
@Component
@Profile("demo")
class DemoProfileGuard(private val environment: Environment) : InitializingBean {
    override fun afterPropertiesSet() {
        val inProd = environment.acceptsProfiles(Profiles.of(PROD))
        check(!inProd || environment.getProperty(ALLOW_IN_PROD, Boolean::class.java, false)) {
            "The 'demo' profile seeds made-up organizations and must not run on a real deployment. " +
                "It is active together with '$PROD': set $ALLOW_IN_PROD=true only on the demo server."
        }
        log.warn("Profile 'demo' is active: demo organizations are seeded from db/demo (never on a real customer deployment)")
    }

    companion object {
        const val PROD = "prod"
        const val ALLOW_IN_PROD = "alinha.demo.allow-in-prod"
        private val log = LoggerFactory.getLogger(DemoProfileGuard::class.java)
    }
}
