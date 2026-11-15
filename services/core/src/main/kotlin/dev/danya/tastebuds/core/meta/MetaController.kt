package dev.danya.tastebuds.core.meta

import dev.danya.tastebuds.core.platform.ConditionalOnRole
import dev.danya.tastebuds.core.platform.Role
import dev.danya.tastebuds.core.platform.TastebudsProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class MetaResponse(
    val service: String,
    val version: String,
    val role: Role,
)

/** Public service metadata; also the smoke-test target for the observability and security pipeline. */
@RestController
@RequestMapping("/v1/meta")
@ConditionalOnRole(Role.API)
class MetaController(
    private val properties: TastebudsProperties,
    private val buildProperties: ObjectProvider<BuildProperties>,
) {
    @GetMapping
    fun meta(): MetaResponse =
        MetaResponse(
            service = SERVICE_NAME,
            version = buildProperties.ifAvailable?.version ?: "unknown",
            role = properties.role,
        )

    private companion object {
        const val SERVICE_NAME = "tastebuds-core"
    }
}
