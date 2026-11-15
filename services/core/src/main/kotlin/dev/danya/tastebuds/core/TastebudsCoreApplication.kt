package dev.danya.tastebuds.core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class TastebudsCoreApplication

fun main(args: Array<String>) {
    runApplication<TastebudsCoreApplication>(*args)
}
