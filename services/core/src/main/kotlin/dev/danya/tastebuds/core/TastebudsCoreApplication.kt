package dev.danya.tastebuds.core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

@SpringBootApplication
@ConfigurationPropertiesScan
class TastebudsCoreApplication

fun main(args: Array<String>) {
    if (args.firstOrNull() == MigrationCommand.NAME) {
        exitProcess(MigrationCommand.run(args.copyOfRange(1, args.size)))
    }
    runApplication<TastebudsCoreApplication>(*args)
}
