package dev.danya.tastebuds.core

import org.springframework.boot.fromApplication
import org.springframework.boot.with

/**
 * Local development entry point: the real application wired to Testcontainers-managed Postgres and Redis, with
 * an ephemeral anonymous-session signing key.
 */
fun main(args: Array<String>) {
    fromApplication<TastebudsCoreApplication>()
        .with(TestcontainersConfiguration::class, RedisTestcontainersConfiguration::class)
        .run("--tastebuds.anon-session.ephemeral-signing-key=true", *args)
}
