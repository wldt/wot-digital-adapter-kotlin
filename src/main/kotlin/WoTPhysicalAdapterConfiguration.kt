import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.eclipse.thingweb.Servient
import org.eclipse.thingweb.thing.ThingDescription
import org.eclipse.thingweb.thing.schema.WoTThingDescription
import java.net.URI

/**
 * Sealed class to represent the source of a Thing Description.
 * It can be from a URI (local file or web) or a raw String.
 */
sealed class ThingDescriptionSource {
    data class FromUri(val uri: URI) : ThingDescriptionSource()
    data class FromString(val content: String) : ThingDescriptionSource()
    data class FromFile(val filePath: String) : ThingDescriptionSource()
}

/**
 * Data class representing polling options for properties.
 *
 * @property pollingInterval The interval at which the property should be polled, in milliseconds.
 * @property onlyUpdatedValues If true, only updated values will be fetched; otherwise, all values will be fetched.
 */
data class PollingOptions(
    val pollingInterval: Long,
    val onlyUpdatedValues: Boolean
)

class WoTPhysicalAdapterConfiguration(
    private val tdSource: ThingDescriptionSource,
    private val servient: Servient,
    private val propertiesPollingOptions: Map<String, PollingOptions> = emptyMap()
) {
    fun getPollingOptionsForProperty(propertyName: String): PollingOptions? {
        return propertiesPollingOptions[propertyName]
    }

    fun getServient(): Servient {
        return servient
    }

    fun getThingDescription(): WoTThingDescription {
        return when (tdSource) {
            is ThingDescriptionSource.FromUri -> getThingDescriptionFromUri(tdSource.uri)
            is ThingDescriptionSource.FromString -> getThingDescriptionFromString(tdSource.content)
            is ThingDescriptionSource.FromFile -> getThingDescriptionFromFile(tdSource.filePath)
        }
    }

    private fun getThingDescriptionFromUri(uri: URI): WoTThingDescription {
        val content = uri.toURL().readText()
        return getThingDescriptionFromString(content)
    }

    private fun getThingDescriptionFromString(content: String): WoTThingDescription {
        val jsonNode = ObjectMapper().readTree(content)
        return getThingDescriptionFromJsonNode(jsonNode)
    }

    private fun getThingDescriptionFromFile(filePath: String): WoTThingDescription {
        val jsonNode = ObjectMapper().readTree(java.io.File(filePath))
        return getThingDescriptionFromJsonNode(jsonNode)
    }

    private fun getThingDescriptionFromJsonNode(jsonNode: JsonNode): WoTThingDescription {
        val tdJson = if (jsonNode.isArray && jsonNode.size() > 0) {
            jsonNode.get(0).toString()
        } else {
            jsonNode.toString()
        }
        return ThingDescription.fromJson(tdJson)
    }

    override fun toString(): String {
        return "WoTPhysicalAdapterConfiguration(tdSource=$tdSource, propertiesPollingOptions=$propertiesPollingOptions)"
    }
}