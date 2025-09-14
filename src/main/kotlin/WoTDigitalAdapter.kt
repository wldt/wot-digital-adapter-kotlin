import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import it.wldt.adapter.digital.DigitalAdapter
import it.wldt.adapter.physical.PhysicalAssetAction
import it.wldt.adapter.physical.PhysicalAssetDescription
import it.wldt.adapter.physical.PhysicalAssetEvent
import it.wldt.adapter.physical.PhysicalAssetProperty
import it.wldt.core.state.DigitalTwinState
import it.wldt.core.state.DigitalTwinStateAction
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
import org.eclipse.thingweb.thing.schema.NullSchema
import org.eclipse.thingweb.thing.schema.PropertyAffordance
import org.eclipse.thingweb.thing.schema.StringSchema
import org.eclipse.thingweb.thing.schema.Type
import org.eclipse.thingweb.thing.schema.WoTExposedThing
import org.eclipse.thingweb.thing.schema.WoTThingDescription
import org.eclipse.thingweb.thing.schema.toInteractionInputValue
import org.slf4j.LoggerFactory
import java.util.function.Consumer
import java.util.function.Function
import java.util.stream.Collectors
import kotlin.jvm.optionals.toList
import kotlin.sequences.toList

/**
 * A Digital Adapter implementation based on the Eclipse ThingWeb (Kotlin WoT) library.
 * This adapter exposes a WoT Thing Description and manages the interaction with the underlying
 * Digital Twin through the WLDT framework.
 *
 * @param id The unique identifier for this Digital Adapter instance.
 * @param configuration The configuration object containing settings for the adapter, including the Servient instance.
 */
class WoTDigitalAdapter(id: String?, private val configuration: WoTDigitalAdapterConfiguration) : DigitalAdapter<String>(id) {

    private val servient = configuration.servient
    private val wot = Wot.create(servient)
    private lateinit var td: WoTThingDescription
    private lateinit var exposedThing: WoTExposedThing
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logger = LoggerFactory.getLogger(WoTDigitalAdapter::class.java)

    private val propertiesValues = mutableMapOf<String, InteractionInput>()
    private val propertiesObserved = mutableSetOf<String>()
    private val eventSubscribed = mutableSetOf<String>()

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
        coroutineScope.cancel()
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
    override fun onDigitalTwinStart() {}

    /**
     * DT Life Cycle Notification that the DT has been stopped
     */
    override fun onDigitalTwinStop() {}

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
        if (digitalTwinStateChangeList != null && !digitalTwinStateChangeList.isEmpty()) {

            for (stateChange in digitalTwinStateChangeList) {
                val operation = stateChange.operation
                val resourceType = stateChange.resourceType
                val resource = stateChange.resource

                when (operation) {
                    DigitalTwinStateChange.Operation.OPERATION_UPDATE ->
                        if (resourceType == DigitalTwinStateChange.ResourceType.PROPERTY) {
                            val p = resource as DigitalTwinStateProperty<*>
                            val interactionInput = getInteractionInput(p.value)
                            propertiesValues[p.key] = interactionInput
                            if (propertiesObserved.contains(p.key)) {
                                coroutineScope.launch {
                                    exposedThing.emitPropertyChange(p.key, interactionInput)
                                }
                            }
                        }

                    DigitalTwinStateChange.Operation.OPERATION_UPDATE_VALUE ->
                        logger.info("Update value operation on $resourceType: $resource")

                    DigitalTwinStateChange.Operation.OPERATION_ADD ->
                        logger.info("Add operation on $resourceType: $resource")

                    DigitalTwinStateChange.Operation.OPERATION_REMOVE ->
                        logger.info("Remove operation on $resourceType: $resource")

                    else ->
                        logger.info("Unknown operation on $resourceType: $resource")
                }
            }
        } else {
            // No state changes
            logger.info("No state changes detected.")
        }
    }


    /**
     * Callback method to receive a new computed Event Notification (associated to event declared in the DT State)
     *
     * @param digitalTwinStateEventNotification The generated Notification associated to a DT Event
     */
    override fun onEventNotificationReceived(digitalTwinStateEventNotification: DigitalTwinStateEventNotification<*>?) {
        logger.info("RECEIVED event notification: $digitalTwinStateEventNotification")
        if (digitalTwinStateEventNotification != null) {
            val eventKey = digitalTwinStateEventNotification.digitalEventKey
            val eventValue = digitalTwinStateEventNotification.body
            if (eventSubscribed.contains(eventKey)) {
                coroutineScope.launch {
                    try {
                        logger.info("Emitted event $eventKey with value $eventValue")
                        exposedThing.emitEvent(eventKey, getInteractionInput(eventValue))
                    } catch (e: Exception) {
                        logger.error("Error emitting event $eventKey with value $eventValue: ${e.message}", e)
                    }
                }
            }
        }
    }



    override fun onDigitalTwinBound(adaptersPhysicalAssetDescriptionMap: Map<String?, PhysicalAssetDescription?>?) {
        super.onDigitalTwinBound(adaptersPhysicalAssetDescriptionMap)
        val properties = HashMap<String, Any?>()
        val actions = HashMap<String, Any?>()
        val events = HashMap<String, Any?>()

        adaptersPhysicalAssetDescriptionMap?.forEach { (_, pad) ->
            pad?.properties?.forEach { p -> properties.putIfAbsent(p.key, mapOf(
                "type" to mapToType(p.type),
                "observable" to (configuration.allPropertiesObservable || configuration.observableProperties.contains(p.key) && !p.isImmutable),
                "const" to if (p.isImmutable) p.initialValue else null,
                "readOnly" to !p.isWritable,
                "default" to p.initialValue
                ))
                propertiesValues[p.key] = getInteractionInput(p.initialValue)
            }
            pad?.actions?.forEach { a -> actions.putIfAbsent(a.key, mapOf(
                "@type" to a.type,
                "input" to mapOf("type" to a.contentType),
            )) }
            pad?.events?.forEach { e -> events.putIfAbsent(e.key, mapOf(
                "@type" to e.type,
            )) }
        }

        val constructionTD = createThingDescription(configuration.tdInfo, properties, actions, events)
        exposedThing = createExposedThing(constructionTD)
        td = exposedThing.getThingDescription()
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
        td.properties.forEach { (pKey, pAff) ->
            exposedThing.setPropertyReadHandler(pKey) { _ -> propertiesValues[pKey] }
            if (!pAff.readOnly) exposedThing.setPropertyWriteHandler(pKey) { input, _ ->
                logger.info("Property $pKey write invoked with input: ${input.value()}")
                getInteractionInput(input.value())
            }
            if (pAff.observable) {
                exposedThing.setPropertyObserveHandler(pKey) { _ ->
                    logger.info("Property $pKey observe invoked")
                    propertiesObserved.add(pKey)
                    propertiesValues[pKey]
                }
            }
        }
        td.actions.keys.forEach { aKey ->
            exposedThing.setActionHandler(aKey) { input, _ ->
                logger.info("Action $aKey invoked with input: ${input.value()}, type: ${input.value().nodeType}")
                publishDigitalActionWldtEvent(aKey, jsonNodeToValue(input.value()))
                getInteractionInput(input.value())
            }
        }
        td.events.forEach { (eKey, eAff) ->
            exposedThing.setEventSubscribeHandler(eKey) { _ ->
                logger.info("Event $eKey subscription invoked")
                eventSubscribed.add(eKey)
                observeDigitalTwinEventNotification(eKey)
            }
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

    private fun getInteractionInput(body: Any?): InteractionInput.Value {
        if (body is JsonNode) {
            return InteractionInput.Value(body)
        }
        return when (body) {
            null -> throw IllegalArgumentException("Null value cannot be converted to InteractionInput")
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

    private fun jsonNodeToValue(node: JsonNode?): Any? {
        return when {
            node == null || node is NullNode -> null
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
}


