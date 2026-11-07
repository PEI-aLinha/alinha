package pt.alinha

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic

/**
 * Every direct subpackage of pt.alinha is a Spring Modulith module:
 * core, pluginapi and one per plugin (pt.alinha.assessment, ...).
 */
@SpringBootApplication
@Modulithic(systemName = "aLinha", sharedModules = ["pluginapi"])
class AlinhaApplication

fun main(args: Array<String>) {
    runApplication<AlinhaApplication>(*args)
}
