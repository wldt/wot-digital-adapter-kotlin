// `src/main/kotlin/WoTDigitalAdapterConfiguration.kt`
import org.eclipse.thingweb.Servient

class WoTDigitalAdapterConfiguration(
    val servient: Servient,
    val thingTitle: String = "DT",
    val description: String? = null,
    observableProperties: Set<String> = emptySet(),
    val allPropertiesObservable: Boolean = false,
) {
    val observableProperties: Set<String> = observableProperties.toSet()

    val tdInfo: Map<String, String>
        get() = buildMap {
            put("title", thingTitle)
            description?.let { put("description", it) }
        }
}
