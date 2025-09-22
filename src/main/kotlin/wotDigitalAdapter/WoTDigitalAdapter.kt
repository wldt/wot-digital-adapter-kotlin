package wotDigitalAdapter

import it.wldt.adapter.digital.DigitalAdapter
import it.wldt.adapter.physical.PhysicalAssetDescription
import it.wldt.core.state.DigitalTwinState
import it.wldt.core.state.DigitalTwinStateChange
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

    /**
     * Callback to notify the adapter on its correct startup
     */
    override fun onAdapterStart() {
        coroutineScope.launch {
            while (!this@WoTDigitalAdapter::exposedThing.isInitialized) {
                logger.info("Waiting for the DT to be bound to create the ExposedThing...")
                delay(200)
            }
            try {
                servient.addThing(exposedThing)
                servient.start()
                servient.expose(id)
            } catch (e: Exception) {
                logger.error("Error starting or exposing the servient: ${e.message}")
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
        logger.info("Digital Twin Sync")
    }

    /**
     * DT Life Cycle notification that the DT is currently Not Sync
     * @param digitalTwinState
     */
    override fun onDigitalTwinUnSync(digitalTwinState: DigitalTwinState?) {
        logger.info("Digital Twin Unsync")
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
                    DigitalTwinStateChange.Operation.OPERATION_UPDATE,
                    DigitalTwinStateChange.Operation.OPERATION_UPDATE_VALUE ->
                        if (resourceType == DigitalTwinStateChange.ResourceType.PROPERTY ||
                            resourceType == DigitalTwinStateChange.ResourceType.PROPERTY_VALUE)
                        {
                            val p = resource as DigitalTwinStateProperty<*>
                            val interactionInput = getInteractionInput(p.value)
                            propertiesValues[p.key] = interactionInput
                            if (propertiesObserved.contains(p.key)) {
                                coroutineScope.launch {
                                    exposedThing.emitPropertyChange(p.key, interactionInput)
                                    logger.info("Emitted property change for ${p.key} with value ${p.value}")
                                }
                            }
                        }

                    DigitalTwinStateChange.Operation.OPERATION_ADD ->
                        logger.info("Add operation on $resourceType: $resource")

                    DigitalTwinStateChange.Operation.OPERATION_REMOVE ->
                        logger.info("Remove operation on $resourceType: $resource")

                    else ->
                        logger.info("Unknown operation on $resourceType: $resource")
                }
            }
        } else {
            logger.info("No state changes detected.")
        }
    }


    /**
     * Callback method to receive a new computed Event Notification (associated to event declared in the DT State)
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

    override fun onDigitalTwinBound(adaptersPhysicalAssetDescriptionMap: Map<String?, PhysicalAssetDescription?>?) {
        logger.info("Digital Twin Bound")
        super.onDigitalTwinBound(adaptersPhysicalAssetDescriptionMap)
        val properties = HashMap<String, Any?>()
        val actions = HashMap<String, Any?>()
        val events = HashMap<String, Any?>()

        adaptersPhysicalAssetDescriptionMap?.forEach { (_, pad) ->
            pad?.properties?.forEach { p -> properties.putIfAbsent(p.key, mapOf(
                "type" to javaToWotType(p.type),
                "observable" to (!p.isImmutable && configuration.isPropertyObservable(p.key)),
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
}