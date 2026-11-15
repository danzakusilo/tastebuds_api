package dev.danya.tastebuds.core

import org.springframework.boot.fromApplication
import org.springframework.boot.with

/** Local development entry point: the real application wired to Testcontainers-managed infrastructure. */
fun main(args: Array<String>) {
    fromApplication<TastebudsCoreApplication>().with(TestcontainersConfiguration::class).run(*args)
}
