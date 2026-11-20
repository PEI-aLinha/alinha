package pt.alinha.bff

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class BffApplication

fun main(args: Array<String>) {
    runApplication<BffApplication>(*args)
}
