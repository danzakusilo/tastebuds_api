package dev.danya.tastebuds.core.platform

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("tastebuds")
data class TastebudsProperties(
    /** Which part of the modular monolith this process runs. See [Role]. */
    val role: Role = Role.ALL,
    val cors: Cors = Cors(),
) {
    data class Cors(
        /** Exact origins allowed to call the API from a browser. Empty means no cross-origin access. */
        val allowedOrigins: List<String> = emptyList(),
    )
}
