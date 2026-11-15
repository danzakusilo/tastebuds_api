package dev.danya.tastebuds.core.platform

import dev.danya.tastebuds.observability.WideEvent
import dev.danya.tastebuds.observability.WideEventEnricher
import org.springframework.stereotype.Component

@Component
internal class RoleWideEventEnricher(
    private val properties: TastebudsProperties,
) : WideEventEnricher {
    override fun enrich(event: WideEvent) {
        event.put("app.role", properties.role)
    }
}
