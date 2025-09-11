import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import it.wldt.adapter.digital.DigitalAdapter
import it.wldt.adapter.physical.PhysicalAssetAction
import it.wldt.adapter.physical.PhysicalAssetDescription
import it.wldt.adapter.physical.PhysicalAssetEvent
import it.wldt.adapter.physical.PhysicalAssetProperty
import it.wldt.core.state.DigitalTwinState
import it.wldt.core.state.DigitalTwinStateChange
import it.wldt.core.state.DigitalTwinStateEvent
import it.wldt.core.state.DigitalTwinStateEventNotification
import it.wldt.core.state.DigitalTwinStateProperty
import it.wldt.exception.EventBusException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.eclipse.thingweb.Wot
import org.eclipse.thingweb.thing.schema.toInteractionInputValue
import org.eclipse.thingweb.reflection.ExposedThingBuilder
import org.eclipse.thingweb.reflection.annotations.Property
import org.eclipse.thingweb.thing.ExposedThing
import org.eclipse.thingweb.thing.ThingDescription
import org.eclipse.thingweb.thing.schema.InteractionInput
import org.eclipse.thingweb.thing.schema.PropertyAffordance
import org.eclipse.thingweb.thing.schema.WoTExposedThing
import org.eclipse.thingweb.thing.schema.WoTThingDescription
import org.eclipse.thingweb.thing.schema.toInteractionInputValue
import org.slf4j.LoggerFactory
import java.util.function.Consumer
import java.util.function.Function
import java.util.stream.Collectors
import kotlin.jvm.optionals.toList
import kotlin.sequences.toList

/*

come ottenere il Servient? (adapter cofigurable?)
L'adapter crea automaticamente la Thing description e ha un metodo per esporla?
L'adapter espone una Thing corrispondente al DT?
Aggiornamento della Thing esposta
Gestionde del sync/unsync: aggiunta di property "last update" alla Thing?
 */

class WoTDigitalAdapter(id: String?, private val configuration: WoTDigitalAdapterConfiguration) : DigitalAdapter<String>(id) {

    private val servient = configuration.getServient()
    private val wot = Wot.create(servient)
    private lateinit var td: WoTThingDescription
    private lateinit var exposedThing: WoTExposedThing
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logger = LoggerFactory.getLogger(WoTDigitalAdapter::class.java)

    private val propertiesValues = mutableMapOf<String, InteractionInput>()

    /**
     * Callback to notify the adapter on its correct startup
     */
    override fun onAdapterStart() {
        coroutineScope.launch {
            while (!this@WoTDigitalAdapter::exposedThing.isInitialized) {
                logger.info("Waiting for the DT to be bound to create the ExposedThing...")
                kotlinx.coroutines.delay(200)
            }
            try {
                servient.addThing(exposedThing)
                servient.start()
                servient.expose(id)
            } catch (e: Exception) {
                logger.error("Error starting or exposing the servient: ${e.message}", e)
            }
        }
        logger.info("WoT Digital Adapter $id started")
        notifyDigitalAdapterBound()
    }

    /**
     * Callback to notify the adapter that has been stopped
     */
    override fun onAdapterStop() {
        logger.info("WoT Digital Adapter $id stopped")
    }

    /**
     * DT Life Cycle notification that the DT is correctly on Sync
     * @param digitalTwinState
     */
    override fun onDigitalTwinSync(digitalTwinState: DigitalTwinState?) {
        try {
            //Retrieve the list of available events and observe all variations
            digitalTwinState!!.eventList
                .map<MutableList<String?>>(Function { eventList: MutableList<DigitalTwinStateEvent?>? ->
                    eventList!!.stream()
                        .map<String?> { obj: DigitalTwinStateEvent? -> obj!!.key }
                        .collect(Collectors.toList())
                })
                .ifPresent(Consumer { eventKeys: MutableList<String?>? ->
                    try {
                        observeDigitalTwinEventsNotifications(eventKeys)
                    } catch (e: EventBusException) {
                        e.printStackTrace()
                    }
                })
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    /**
     * DT Life Cycle notification that the DT is currently Not Sync
     * @param digitalTwinState
     */
    override fun onDigitalTwinUnSync(digitalTwinState: DigitalTwinState?) {}

    /**
     * DT Life Cycle notification that the DT has been created
     */
    override fun onDigitalTwinCreate() {}

    /**
     * DT Life Cycle Notification that the DT has correctly Started
     */
    override fun onDigitalTwinStart() {
        logger.info("DT started")
    }

    /**
     * DT Life Cycle Notification that the DT has been stopped
     */
    override fun onDigitalTwinStop() {
        coroutineScope.cancel()
        logger.info("DT stopped")
    }

    /**
     * DT Life Cycle Notification that the DT has destroyed
     */
    override fun onDigitalTwinDestroy() {

    }

    /**
     * Callback method allowing the Digital Adapter to receive the updated Digital Twin State together with
     * the previous state and the list of applied changes
     *
     * @param newDigitalTwinState The new Digital Twin State computed by the Shadowing Function
     * @param previousDigitalTwinState The previous Digital Twin State
     * @param digitalTwinStateChangeList The list of applied changes to compute the new Digital Twin State
     */
    override fun onStateUpdate(
        newDigitalTwinState: DigitalTwinState?,
        previousDigitalTwinState: DigitalTwinState?,
        digitalTwinStateChangeList: ArrayList<DigitalTwinStateChange>?
    ) {
        // We can also check each DT's state change potentially differentiating the behaviour for each change
        if (digitalTwinStateChangeList != null && !digitalTwinStateChangeList.isEmpty()) {
            // Iterate through each state change in the list

            for (stateChange in digitalTwinStateChangeList) {
                // Get information from the state change

                val operation = stateChange.operation
                val resourceType = stateChange.resourceType
                val resource = stateChange.resource


                // Perform different actions based on the type of operation
                when (operation) {
                    DigitalTwinStateChange.Operation.OPERATION_UPDATE ->                     // Handle an update operation
                        if (resourceType == DigitalTwinStateChange.ResourceType.PROPERTY) {
                            val p = resource as DigitalTwinStateProperty<*>
                            val interactionInput = getInteractionInput(p.value)
                            propertiesValues[p.key] = interactionInput
                            coroutineScope.launch {
                                exposedThing.emitPropertyChange(p.key, interactionInput)
                            }
                        } else {
                            logger.info("Update operation on $resourceType: $resource not handled")
                        }

                    DigitalTwinStateChange.Operation.OPERATION_UPDATE_VALUE ->                     // Handle an update value operation
                        println("Update value operation on $resourceType: $resource")

                    DigitalTwinStateChange.Operation.OPERATION_ADD ->                     // Handle an add operation
                        println("Add operation on $resourceType: $resource")

                    DigitalTwinStateChange.Operation.OPERATION_REMOVE ->                     // Handle a remove operation
                        println("Remove operation on $resourceType: $resource")

                    else ->                     // Handle unknown operation (optional)
                        println("Unknown operation on $resourceType: $resource")
                }
            }
        } else {
            // No state changes
            println("No state changes detected.")
        }
    }


    /**
     * Callback method to receive a new computed Event Notification (associated to event declared in the DT State)
     *
     * @param digitalTwinStateEventNotification The generated Notification associated to a DT Event
     */
    override fun onEventNotificationReceived(digitalTwinStateEventNotification: DigitalTwinStateEventNotification<*>?) {}



    override fun onDigitalTwinBound(adaptersPhysicalAssetDescriptionMap: Map<String?, PhysicalAssetDescription?>?) {
        super.onDigitalTwinBound(adaptersPhysicalAssetDescriptionMap)
        val properties = HashMap<String, Any?>()
        val actions = HashMap<String, Any?>()
        val events = HashMap<String, Any?>()

        adaptersPhysicalAssetDescriptionMap?.forEach { (_, pad) ->
            pad?.properties?.forEach { p -> properties.putIfAbsent(p.key, mapOf(
                "type" to mapToType(p.type),
                "observable" to false,
                "readOnly" to !p.isWritable
            )) }
            //"output", "descriptions", "title", "idempotent", "@type", "uriVariables", "description", "safe", "forms", "synchronous", "input", "titles"
            pad?.actions?.forEach { a -> actions.putIfAbsent(a.key, mapOf(
                "@type" to a.type
            )) }
            pad?.events?.forEach { e -> events.putIfAbsent(e.key, mapOf(
                "@type" to e.type
            )) }
        }

        td = createThingDescription(configuration.getTDinfo(), properties, actions, events)
        exposedThing = createExposedThing(td)
    }


    private fun createThingDescription(
        base: Map<String, String>,
        properties: Map<String, *>,
        actions: Map<String, *>,
        events: Map<String, *>)
    : WoTThingDescription {
        val map = HashMap<String, Any?>()
        map.putAll(base)
        map["id"] = id
        map["properties"] = properties
        map["actions"] = actions
        map["events"] = events
        return ThingDescription.fromMap(map)
    }

    private fun createExposedThing(td: WoTThingDescription): WoTExposedThing {
        val exposedThing = wot.produce(td)
        td.properties.keys.forEach { pKey ->
            exposedThing.setPropertyReadHandler(pKey) { _ -> propertiesValues[pKey] }
        }
        return exposedThing
    }

    private fun mapToType(type: String): String {
        val t = type.trim()

        if (t == "null") return "null"
        if (t.endsWith("[]") || t.startsWith("[")) return "array"
        val groups = mapOf(
            "string" to setOf("java.lang.String", "kotlin.String", "String"),
            "boolean" to setOf("java.lang.Boolean", "boolean", "kotlin.Boolean", "Boolean"),
            "integer" to setOf(
                "java.lang.Integer", "int", "kotlin.Int", "Integer",
                "java.lang.Long", "long", "kotlin.Long", "Long",
                "java.lang.Short", "short", "kotlin.Short", "Short",
                "java.math.BigInteger"
            ),
            "number" to setOf(
                "java.lang.Float", "float", "kotlin.Float", "Float",
                "java.lang.Double", "double", "kotlin.Double", "Double",
                "java.math.BigDecimal", "java.lang.Number", "kotlin.Number", "Number"
            ),
            "array" to setOf(
                "java.util.List", "java.util.Set", "java.util.Collection",
                "kotlin.collections.List", "kotlin.collections.Set", "kotlin.collections.Collection"
            ),
            "object" to setOf(
                "java.util.Map", "kotlin.collections.Map",
                "java.lang.Object", "kotlin.Any"
            )
        )

        for ((jsonType, aliases) in groups) {
            if (t in aliases) return jsonType
        }
        return "object"
    }

    private fun jsonNodeToKotlin(node: JsonNode): Any? = when {
        node.isNumber -> node.numberValue() // Integer, Long, BigInteger, Double, Float, BigDecimal
        node.isTextual -> node.textValue()
        node.isBoolean -> node.booleanValue()
        node.isArray -> node.elements().asSequence().map { jsonNodeToKotlin(it) }.toList()
        node.isObject -> node.fields().asSequence().associate { it.key to jsonNodeToKotlin(it.value) }
        node.isNull || node is NullNode -> null
        else -> node.toString() // fallback as string if unknown
    }

    private fun getInteractionInput(body: Any?): InteractionInput.Value {
        if (body is JsonNode) {
            val converted = jsonNodeToKotlin(body)
            require(converted != null) { "Unsupported null JsonNode for InteractionInput" }
            return getInteractionInput(converted)
        }
        return when (body) {
            null -> throw IllegalArgumentException("Null value not supported for InteractionInput")
            is Number -> body.toInteractionInputValue()
            is String -> body.toInteractionInputValue()
            is Boolean -> body.toInteractionInputValue()
            is MutableMap<*, *> -> body.toInteractionInputValue()
            is MutableList<*> -> body.toInteractionInputValue()
            else -> throw IllegalArgumentException(
                "Unsupported type: ${body::class.qualifiedName}. Only JsonNode, Number, String, Boolean, Map, List are supported."
            )
        }
    }
}


