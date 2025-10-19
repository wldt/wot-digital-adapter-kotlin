package wotDigitalAdapter

import it.wldt.core.state.DigitalTwinStateAction
import it.wldt.core.state.DigitalTwinStateEvent
import it.wldt.core.state.DigitalTwinStateProperty
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties


/**
 * Builds a map representing the metadata of a Digital Twin State Property for inclusion in a Thing Description.
 */
internal fun getPropertyMap(p: DigitalTwinStateProperty<*>, observable : Boolean): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>(
        "observable" to observable,
        "readOnly" to (!p.isWritable && p.isReadable),
        "writeOnly" to (!p.isReadable && p.isWritable),
        "default" to p.value,
    )
    map.putAll(getTypePropertiesMap(p.value.javaClass, p.value))
    map["type"] = stringToWotType(p.type)
    return map
}


/**
 * Recursively builds a map representing the type and structure of a property or complex type.
 * This is used to define the schema of properties in the Thing Description.
 */
private fun getTypePropertiesMap(cls : Class<*>, instance: Any?): Map<String, Any?> {
    val wotType = javaClassToWotType(cls)
    val map = mutableMapOf<String, Any?>()
    map["type"] = wotType
    when (wotType) {
        "object" -> {
            val propMap = mutableMapOf<String, Any?>()
            if (instance is Map<*, *>) {
                instance.forEach { (key, value) ->
                    if (value != null) {
                        propMap[key.toString()] = getTypePropertiesMap(value.javaClass, value)
                    }
                }
            }
            if (instance != null) {
                instance::class.memberProperties.forEach { prop ->
                    val value = try {
                        prop.getter.call(instance)
                    } catch (_: Exception) {
                        null
                    }
                    propMap.putIfAbsent(prop.name, getTypePropertiesMap((prop.returnType.classifier as KClass<*>).java,value ))
                }
            }
            cls.fields.forEach { field ->
                val fieldInstance = try {
                    field.get(instance)
                } catch (_: Exception) {
                    null
                }
                propMap.putIfAbsent(field.name, getTypePropertiesMap(field.type, fieldInstance))
            }
            map["properties"] = propMap
        }
        "array" -> {
            if (cls.isArray) {
                val componentType = cls.componentType
                map["items"] = getTypePropertiesMap(componentType, null)
            } else if (instance is Iterable<*>) {
                val e = instance.firstOrNull()
                if (e != null) {
                    map["items"] = getTypePropertiesMap(e.javaClass, e)
                }
            }

        }
    }

    return map
}

/**
 * Builds a map representing the metadata of a Digital Twin State Action for inclusion in a Thing Description.
 */
internal fun getActionMap(a: DigitalTwinStateAction): Map<String, Any?> {
    return mapOf(
        "@type" to a.type,
        "input" to mapOf<String, Any?>("type" to a.contentType)
    )
}

/**
 * Builds a map representing the metadata of a Digital Twin State Event for inclusion in a Thing Description.
 */
internal fun getEventMap(e: DigitalTwinStateEvent): Map<String, Any?> {
    return mapOf(
        "@type" to e.type,
    )
}

internal fun getRelationshipMap() : Map<String, Any?> {
    val map = mutableMapOf<String, Any?>(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf("type" to "string"),
            "type" to mapOf("type" to "string"),
            "instances" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string"),
                        "targetId" to mapOf("type" to "string"),
                        "targetType" to mapOf("type" to "string"),
                        "attributes" to mapOf("type" to "object"),
                    )
                )
            )
        ),
        "observable" to false,
        "readOnly" to true,
    )

    return map
}
