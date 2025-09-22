package wotDigitalAdapter

import org.eclipse.thingweb.Servient

class WoTDigitalAdapterConfiguration(
    val servient: Servient,
    private val thingTitle: String = "DT",
    private val description: String? = null,
    private val observableProperties: Set<String> = emptySet(),
    private val allPropertiesObservable: Boolean = false,
) {

    val tdInfo: Map<String, String>
        get() = buildMap {
            put("title", thingTitle)
            description?.let { put("description", it) }
        }

    fun isPropertyObservable(key: String): Boolean {
        return allPropertiesObservable || observableProperties.contains(key)
    }
}