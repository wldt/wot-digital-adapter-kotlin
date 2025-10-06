package wotDigitalAdapter

import it.wldt.adapter.digital.DigitalAdapter
import it.wldt.core.state.DigitalTwinState
import it.wldt.core.state.DigitalTwinStateAction
import it.wldt.core.state.DigitalTwinStateChange
import it.wldt.core.state.DigitalTwinStateEvent
import it.wldt.core.state.DigitalTwinStateEventNotification
import it.wldt.core.state.DigitalTwinStateProperty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import org.eclipse.thingweb.Wot
import org.eclipse.thingweb.thing.ThingDescription
import org.eclipse.thingweb.thing.schema.InteractionInput
import org.eclipse.thingweb.thing.schema.WoTExposedThing
import org.eclipse.thingweb.thing.schema.WoTThingDescription

import org.slf4j.LoggerFactory
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.reflect.full.memberProperties

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
    private lateinit var tdConstructionMap: Map<String, Any?>
    private val propertiesMap = HashMap<String, Any?>()
    private val actionsMap = HashMap<String, Any?>()
    private val eventsMap = HashMap<String, Any?>()
    private val propertiesValues = mutableMapOf<String, InteractionInput>()
    private val propertiesObserved = mutableSetOf<String>()
    private var synchronized = false

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logger = LoggerFactory.getLogger(WoTDigitalAdapter::class.java)

    val thingId: String = configuration.thingId ?: this@WoTDigitalAdapter.id

    /**
     * Callback to notify the adapter on its correct startup
     * Starts the Servient and exposes the Thing Description
     */
    override fun onAdapterStart() {
        logger.info("Starting WoT Digital Adapter $id...")
        coroutineScope.launch {
            while (!synchronized) {
                logger.info("Waiting for the DT to be in Sync to create the ExposedThing...")
                delay(500)
            }
            if (!::exposedThing.isInitialized) {
                logger.error("ExposedThing is not initialized. Cannot start the adapter.")
                return@launch
            }
            try {
                servient.addThing(exposedThing)
                servient.start()
                servient.expose(thingId)
                logger.info("WoT Digital Adapter $id started successfully")
                notifyDigitalAdapterBound()
            } catch (e: Exception) {
                logger.error("Error starting or exposing the servient: ${e.message}")
            }
        }

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
     * creates the ExposedThing based on the current Digital Twin State and configuration
     * updates the Thing Description to reflect the current ExposedThing
     * @param digitalTwinState
     */
    override fun onDigitalTwinSync(digitalTwinState: DigitalTwinState?) {
        logger.info("Digital Twin Sync")
        if (digitalTwinState == null) {
            logger.error("Digital Twin State is null during onDigitalTwinUnSync")
            return
        }
        try {
            tdConstructionMap = getTdConstructionMap(configuration.tdInfo, digitalTwinState)
            exposedThing = createExposedThing(ThingDescription.fromMap(tdConstructionMap))
            td = exposedThing.getThingDescription()
        } catch (e: Exception) {
            logger.error("Error creating Thing Description or Exposed Thing: ${e.message}",e)
        }
        synchronized = true
    }

    /**
     * DT Life Cycle notification that the DT is currently Not Sync
     * @param digitalTwinState
     */
    override fun onDigitalTwinUnSync(digitalTwinState: DigitalTwinState?) {
        logger.info("Digital Twin UnSync")
    }

    /**
     * DT Life Cycle notification that the DT has been created
     */
    override fun onDigitalTwinCreate() {
        logger.info("Digital Twin Create")
    }

    /**
     * DT Life Cycle Notification that the DT has correctly Started
     */
    override fun onDigitalTwinStart() {
        logger.info("Digital Twin Start")
    }

    /**
     * DT Life Cycle Notification that the DT has been stopped
     */
    override fun onDigitalTwinStop() {
        logger.info("Digital Twin Stop")
    }

    /**
     * DT Life Cycle Notification that the DT has destroyed
     */
    override fun onDigitalTwinDestroy() {
        logger.info("Digital Twin Destroyed")
    }

    /**
     * Callback method allowing the Digital Adapter to receive the updated Digital Twin State together with
     * the previous state and the list of applied changes.
     * If the Thing Description has changed, the ExposedThing is restarted and the TD is updated.
     * Needs updatePropertyValue to be used to notify property value changes.
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
            var restartThing = false
            for (stateChange in digitalTwinStateChangeList) {
                val operation = stateChange.operation
                val resourceType = stateChange.resourceType
                val resource = stateChange.resource
                var tdChanged = true

                try { when (resourceType) {
                    DigitalTwinStateChange.ResourceType.PROPERTY_VALUE -> {
                        tdChanged = false
                        if (operation == DigitalTwinStateChange.Operation.OPERATION_UPDATE_VALUE) {
                            val p = resource as DigitalTwinStateProperty<*>
                            val interactionInput = getInteractionInput(p.value)
                            propertiesValues[p.key] = interactionInput
                            if (propertiesObserved.contains(p.key)) {
                                coroutineScope.launch {
                                    exposedThing.emitPropertyChange(p.key, interactionInput)
                                    logger.info("Emitted property change for ${p.key} with value ${p.value}")
                                }
                            }
                        } else {
                            logger.warn("Unsupported operation $operation for resource type $resourceType")
                        }
                    }

                    DigitalTwinStateChange.ResourceType.PROPERTY -> {
                        val p = resource as DigitalTwinStateProperty<*>
                        when (operation) {
                            DigitalTwinStateChange.Operation.OPERATION_ADD,
                            DigitalTwinStateChange.Operation.OPERATION_UPDATE -> {
                                propertiesMap[p.key] = getPropertyMap(p)
                                propertiesValues[p.key] = getInteractionInput(p.value)
                            }
                            DigitalTwinStateChange.Operation.OPERATION_REMOVE -> {
                                propertiesMap.remove(p.key)
                                propertiesValues.remove(p.key)
                                propertiesObserved.remove(p.key)
                            }
                            else -> tdChanged = false
                        }
                    }

                    DigitalTwinStateChange.ResourceType.ACTION -> {
                        val a = resource as DigitalTwinStateAction
                        when (operation) {
                            DigitalTwinStateChange.Operation.OPERATION_ADD,
                            DigitalTwinStateChange.Operation.OPERATION_UPDATE ->
                                actionsMap[a.key] = getActionMap(a)

                            DigitalTwinStateChange.Operation.OPERATION_REMOVE ->
                                actionsMap.remove(a.key)

                            else -> tdChanged = false
                        }
                    }

                    DigitalTwinStateChange.ResourceType.EVENT -> {
                        val e = resource as DigitalTwinStateEvent
                        when (operation) {
                            DigitalTwinStateChange.Operation.OPERATION_ADD,
                            DigitalTwinStateChange.Operation.OPERATION_UPDATE ->
                                eventsMap[e.key] = getEventMap(e)

                            DigitalTwinStateChange.Operation.OPERATION_REMOVE ->
                                eventsMap.remove(e.key)

                            else -> tdChanged = false
                        }
                    }
                    /*DigitalTwinStateChange.ResourceType.RELATIONSHIP,
                    DigitalTwinStateChange.ResourceType.RELATIONSHIP_INSTANCE -> {
                        val r = if (resource is DigitalTwinStateRelationship<*>) {
                            resource.instances
                        } else {
                            listOf(resource as DigitalTwinStateRelationshipInstance<*>)
                        }
                        when (operation) {
                            DigitalTwinStateChange.Operation.OPERATION_ADD,
                            DigitalTwinStateChange.Operation.OPERATION_UPDATE ->
                                r.forEach { linksMap[it.key] = getRelationshipIstanceMap(it) }

                            DigitalTwinStateChange.Operation.OPERATION_REMOVE ->
                                r.forEach { linksMap.remove(it.key) }

                            else -> tdChanged = false
                        }
                    }*/
                    else -> {
                        tdChanged = false
                        logger.warn("Unsupported resource type $resourceType")
                    }
                } } catch (e: Exception) {
                    tdChanged = false
                    logger.error("Error processing state change $stateChange: ${e.message}")
                }
                restartThing = restartThing || tdChanged
            }

            if (restartThing) try {
                logger.info("TD changes detected, restarting ExposedThing...")
                coroutineScope.launch {
                    servient.destroy(thingId)
                    servient.things.remove(thingId)
                    exposedThing = createExposedThing(ThingDescription.fromMap(tdConstructionMap))
                    td = exposedThing.getThingDescription()
                    servient.addThing(exposedThing)
                    servient.expose(thingId)
                    logger.info("ExposedThing re-added to the Servient after TD update")
                }
            } catch (e: Exception) {
                logger.error("Error re-adding ExposedThing to the Servient: ${e.message}")
            }
        } else {
            logger.info("No state changes detected.")
        }
    }


    /**
     * Callback method to receive a new computed Event Notification (associated to event declared in the DT State).
     * If event subscription has been requested, the event is emitted by the ExposedThing.
     *
     * @param digitalTwinStateEventNotification The generated Notification associated to a DT Event
     */
    override fun onEventNotificationReceived(digitalTwinStateEventNotification: DigitalTwinStateEventNotification<*>?) {
        if (digitalTwinStateEventNotification != null) {
            val eventKey = digitalTwinStateEventNotification.digitalEventKey
            val eventValue = digitalTwinStateEventNotification.body
            coroutineScope.launch {
                try {
                    exposedThing.emitEvent(eventKey, getInteractionInput(eventValue))
                    logger.info("Emitted event $eventKey with value $eventValue")
                } catch (e: Exception) {
                    logger.error("Error emitting event $eventKey with value $eventValue: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Retrieves the Thing Description corresponding to the digital twin structure.
     * Could change over time if the DT changes structure.
     *
     * @return The current WoTThingDescription or null if not initialized.
     */
    fun getThingDescription(): WoTThingDescription? {
        if (!::td.isInitialized) {
            logger.info("Thing Description is not initialized yet.")
            return null
        }
        return td
    }

    /**
     * Builds a map to construct a based Thing Description without forms from the provided Digital Twin State and configuration info.
     */
    private fun getTdConstructionMap(base: Map<String, String>, dtState: DigitalTwinState?): Map<String, Any?> {
        val map = HashMap<String, Any?>()
        dtState?.propertyList?.ifPresent { list -> list.forEach { p ->
            propertiesMap[p.key] = getPropertyMap(p)
            propertiesValues[p.key] = getInteractionInput(p.value)
        } }
        dtState?.actionList?.ifPresent { list -> list.forEach { a -> actionsMap[a.key] = getActionMap(a) } }
        dtState?.eventList?.ifPresent { list -> list.forEach { e -> eventsMap[e.key] = getEventMap(e) } }

        map.putAll(base)
        map["id"] = thingId
        map["properties"] = propertiesMap
        map["actions"] = actionsMap
        map["events"] = eventsMap

        return map
    }

    /**
     * Creates and configures an ExposedThing based on the provided Thing Description.
     * Sets up property read handlers, action handlers, and event subscription handlers.
     */
    private fun createExposedThing(td: WoTThingDescription): WoTExposedThing {
        val exposedThing = wot.produce(td)
        td.properties.forEach { (pKey, pAff) ->
            exposedThing.setPropertyReadHandler(pKey) { _ -> propertiesValues[pKey] }
            if (pAff.observable) {
                exposedThing.setPropertyObserveHandler(pKey) { _ ->
                    logger.info("Property $pKey observe invoked")
                    propertiesObserved.add(pKey)
                    null
                }
                exposedThing.setPropertyUnobserveHandler(pKey) { _ ->
                    logger.info("Property $pKey unobserve invoked")
                    propertiesObserved.remove(pKey)
                    null
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
        td.events.keys.forEach { eKey ->
            exposedThing.setEventSubscribeHandler(eKey) { _ ->
                logger.info("Event $eKey subscription invoked")
                observeDigitalTwinEventNotification(eKey)
            }
            exposedThing.setEventUnsubscribeHandler(eKey) { _ ->
                logger.info("Event $eKey unsubscription invoked")
                unObserveDigitalTwinEventNotification(eKey)
            }
        }
        return exposedThing
    }

    /**
     * Builds a map representing the metadata of a Digital Twin State Property for inclusion in a Thing Description.
     */
    private fun getPropertyMap(p: DigitalTwinStateProperty<*>): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "observable" to (configuration.isPropertyObservable(p.key)),
            "readOnly" to (!p.isWritable && p.isReadable),
            "writeOnly" to (!p.isReadable && p.isWritable),
            "default" to p.value,
        )
        map.putAll(getTypePropertiesMap(p.type, p.value))

        return map
    }

    /**
     * Recursively builds a map representing the type and structure of a property or complex type.
     * This is used to define the schema of properties in the Thing Description.
     */
    private fun getTypePropertiesMap(type: String?, istance: Any?): Map<String, Any?> {
        if (type == null) return mapOf("type" to "string")
        val wotType = javaToWotType(type)
        val map = mutableMapOf<String, Any?>()
        map["type"] = wotType
        if (istance == null) return map
        try {
            when (wotType) {
                "object" -> {
                    map["properties"] = istance::class.memberProperties.associate { prop ->
                        val value = prop.getter.call(istance)
                        prop.name to getTypePropertiesMap(value?.javaClass.toString(), value)
                    }
                }

                "array" -> {
                    val item = (istance as Collection<*>).firstOrNull()
                    map["items"] = getTypePropertiesMap(item?.javaClass.toString(), item)
                }
            }
        } catch (e: Exception) {
            logger.error("Error getting type properties map for type: $type, instance: $istance: ${e.message}")
        }
        return map
    }

    /**
     * Builds a map representing the metadata of a Digital Twin State Action for inclusion in a Thing Description.
     */
    private fun getActionMap(a: DigitalTwinStateAction): Map<String, Any?> {
        return mapOf(
            "@type" to a.type,
            "input" to getTypePropertiesMap(a.contentType, null)
        )
    }

    /**
     * Builds a map representing the metadata of a Digital Twin State Event for inclusion in a Thing Description.
     */
    private fun getEventMap(e: DigitalTwinStateEvent): Map<String, Any?> {
        return mapOf(
            "@type" to e.type,
        )
    }
}