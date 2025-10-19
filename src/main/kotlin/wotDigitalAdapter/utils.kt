package wotDigitalAdapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import it.wldt.core.state.DigitalTwinStateRelationship
import org.eclipse.thingweb.thing.schema.InteractionInput
import org.eclipse.thingweb.thing.schema.toInteractionInputValue
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach
import kotlin.reflect.full.memberProperties

/**
 * Maps a Java/Kotlin Class to its corresponding WoT type.
 *
 * @param clazz The Java/Kotlin Class to be mapped.
 * @return The corresponding WoT type as a string.
 */
internal fun javaClassToWotType(clazz: Class<*>): String {
    if (clazz.isArray) return "array"
    return stringToWotType(clazz.simpleName)
}


/**
 * Maps Java/Kotlin types name to WoT types.
 * If the type is not recognized, it defaults to "object".
 *
 * @param type The Java/Kotlin type as a string.
 * @return The corresponding WoT type as a string.
 */
internal fun stringToWotType(type: String): String {
    val t = type.trim()
        .removePrefix("class ")
        .substringBefore("$")
        .removeSuffix("?")
        .trim()

    val raw = t.substringBefore("<").trim()

    if (raw == "null" || raw.equals("void", true)) return "null"


    val groups = mapOf(
        "string" to setOf(
            "java.lang.String", "kotlin.String", "String",
            "java.lang.CharSequence", "kotlin.CharSequence", "CharSequence",
            "char", "java.lang.Character", "kotlin.Char",
            "text/plain"
        ),
        "boolean" to setOf("java.lang.Boolean", "boolean", "kotlin.Boolean", "Boolean"),
        "integer" to setOf(
            "java.lang.Integer", "int", "kotlin.Int", "Integer",
            "java.lang.Long", "long", "kotlin.Long", "Long",
            "java.lang.Short", "short", "kotlin.Short", "Short",
            "java.lang.Byte", "byte", "kotlin.Byte", "Byte",
            "java.math.BigInteger"
        ),
        "number" to setOf(
            "java.lang.Float", "float", "kotlin.Float", "Float",
            "java.lang.Double", "double", "kotlin.Double", "Double",
            "java.math.BigDecimal", "java.lang.Number", "kotlin.Number", "Number"
        ),
        "array" to setOf(
            "java.util.List", "java.util.Set", "java.util.Collection",
            "kotlin.collections.List", "kotlin.collections.Set", "kotlin.collections.Collection",
            "kotlin.collections.MutableList", "kotlin.collections.MutableSet", "kotlin.collections.MutableCollection",
            "java.util.ImmutableCollections", "kotlin.Array"
        ),
        "object" to setOf(
            "java.util.Map", "kotlin.collections.Map", "kotlin.collections.MutableMap",
            "java.lang.Object", "kotlin.Any",
            "java.util.Optional", "java.util.OptionalInt", "java.util.OptionalLong", "java.util.OptionalDouble"
        )
    )
    for ((jsonType, aliases) in groups) {
        if (raw in aliases) return jsonType
    }

    if (raw.contains("List") || raw.contains("Set") || raw.contains("Collection")) return "array"

    return "object"
}

/**
 * Converts various types of input into an `InteractionInput.Value`.
 * If the input is of an unrecognized type, it attempts to convert it using reflection.
 *
 * @param body The input to be converted. It can be of various types including `JsonNode`, `Int`, `Number`, `String`, `Boolean`, `MutableMap`, `MutableList`.
 * @return An `InteractionInput.Value` representing the input.
 * @throws IllegalArgumentException if the input is null or cannot be converted.
 */
internal fun getInteractionInput(body: Any?): InteractionInput.Value {
    return when (body) {
        null -> throw IllegalArgumentException("Null value cannot be converted to InteractionInput")
        is JsonNode -> InteractionInput.Value(body)
        is DigitalTwinStateRelationship<*> -> getRelationshipInteractionInput(body)
        is Int -> body.toInteractionInputValue()
        is Number -> body.toInteractionInputValue()
        is String -> body.toInteractionInputValue()
        is Boolean -> body.toInteractionInputValue()
        is Map<*, *> -> body.toMutableMap().toInteractionInputValue()
        is Iterable<*> -> body.toMutableList().toInteractionInputValue()
        is Array<*> -> body.toMutableList().toInteractionInputValue()
        else -> (body::class.memberProperties.associate { p ->
            p.name to try {
                p.getter.call(body)
            } catch (_: Exception) {
                null
            }
        }).toMutableMap().toInteractionInputValue()
    }
}

/**
 * Converts a `JsonNode` to a corresponding Kotlin value.
 *
 * @param node The `JsonNode` to be converted.
 * @return The corresponding Kotlin value. Returns "void" for null nodes.
 * @throws IllegalArgumentException if the `JsonNode` type is unsupported.
 */
internal fun jsonNodeToValue(node: JsonNode?): Any {
    return when {
        node == null || node is NullNode -> "void"
        node.isTextual -> node.asText()
        node.isBoolean -> node.asBoolean()
        node.isInt -> node.asInt()
        node.isLong -> node.asLong()
        node.isFloat || node.isDouble -> node.asDouble()
        node.isArray -> {
            val list = mutableListOf<Any?>()
            node.forEach { list.add(jsonNodeToValue(it)) }
            list
        }
        node.isObject -> {
            val map = mutableMapOf<String, Any?>()
            node.fields().forEach { (key, value) -> map[key] = jsonNodeToValue(value) }
            map
        }
        else -> throw IllegalArgumentException("Unsupported JsonNode type: ${node.nodeType}")
    }
}

private fun getRelationshipInteractionInput(rel: DigitalTwinStateRelationship<*>): InteractionInput.Value {
    val map = mutableMapOf<String, Any?>(
        "name" to rel.name,
        "type" to rel.type,
        "instanceId" to rel.instances.map {
            i -> mutableMapOf<String, Any?>(
                "relationshipName" to i.relationshipName,
                "targetId" to i.targetId,
                "key" to i.key,
                "metadata" to i.metadata.toMutableMap()
            ) }.toMutableList()
    )
    return map.toInteractionInputValue()
}