package wotDigitalAdapter

import org.eclipse.thingweb.Servient

/**
 * Configuration for the WoT Digital Adapter.
 *
 * @property servient The Servient instance to be used by the adapter.
 * @property thingId An optional identifier for the Thing Description. If not provided, will be the digital adapter id.
 * @property thingTitle The title of the Thing Description (default is "DT").
 * @property description An optional human-readable description for the Thing Description.
 * @property observableProperties A set of property names that should be observable.
 * @property allPropertiesObservable If true, all mutable properties will be treated as observable.
 */
class WoTDigitalAdapterConfiguration(
    val servient: Servient,
    val thingId : String? = null,
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