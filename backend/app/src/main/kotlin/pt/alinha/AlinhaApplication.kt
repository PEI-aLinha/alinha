package pt.alinha

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic

/**
 * Cada subpackage direto de pt.alinha é um módulo do Spring Modulith:
 * core, pluginapi e um por plugin (pt.alinha.assessment, ...).
 */
@SpringBootApplication
@Modulithic(systemName = "aLinha", sharedModules = ["pluginapi"])
class AlinhaApplication

fun main(args: Array<String>) {
    runApplication<AlinhaApplication>(*args)
}
