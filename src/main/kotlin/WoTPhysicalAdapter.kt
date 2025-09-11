import ai.anfc.lmos.wot.binding.ProtocolClient
import it.wldt.adapter.physical.ConfigurablePhysicalAdapter
import it.wldt.adapter.physical.event.PhysicalAssetActionWldtEvent
import it.wldt.adapter.physical.event.PhysicalAssetPropertyWldtEvent
import it.wldt.adapter.physical.event.PhysicalAssetEventWldtEvent
import it.wldt.adapter.physical.PhysicalAssetDescription
import it.wldt.adapter.physical.PhysicalAssetAction
import it.wldt.adapter.physical.PhysicalAssetEvent
import it.wldt.adapter.physical.PhysicalAssetProperty

import kotlinx.coroutines.*

import org.eclipse.thingweb.Wot
import org.eclipse.thingweb.thing.schema.*
import org.eclipse.thingweb.thing.ConsumedThing
import org.eclipse.thingweb.thing.form.Form
import org.eclipse.thingweb.thing.form.Operation

import org.slf4j.LoggerFactory

/**
 * A physical adapter implementation for Web of Things (WoT) devices.
 * This adapter interacts with WoT devices using Thing Descriptions (TDs) and provides
 * functionality for observing properties, subscribing to events, and invoking actions.
 *
 * @param id The unique identifier for the adapter.
 * @param configuration The configuration object containing TD source, servient, and polling options.
 */
class WoTPhysicalAdapter(id: String?, configuration: WoTPhysicalAdapterConfiguration) : ConfigurablePhysicalAdapter<WoTPhysicalAdapterConfiguration>(id, configuration) {

    private val adapterConfig = configuration
    private val wot = Wot.create(adapterConfig.getServient())
    private lateinit var td: WoTThingDescription
    private lateinit var consumedThing: WoTConsumedThing
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logger = LoggerFactory.getLogger(WoTPhysicalAdapter::class.java)
    private val propertySubscriptions = mutableMapOf<String, Subscription>()
    private val eventSubscriptions = mutableMapOf<String, Subscription>()
    private val pollingActives = mutableMapOf<String, Boolean>()
    private val startedClients = mutableMapOf<String, ProtocolClient?>()

    /**
     * Starts the adapter and initializes the interaction with the WoT device.
     * This includes consuming the Thing Description, observing properties, subscribing to events,
     * and notifying the binding of the physical adapter.
     */
    override fun onAdapterStart() {
        logger.info("WoTPhysicalAdapter $id starting...")
        try {
            td = adapterConfig.getThingDescription()
            val pad = PhysicalAssetDescription().apply {
                td.properties.forEach { (name, propertyAffordance) ->
                    properties.add(getPhysicalAssetProperty(name, propertyAffordance))
                }
                td.events.forEach { (name, _) ->
                    events.add(PhysicalAssetEvent(name, "application/json"))
                }
                td.actions.forEach { (name, _) ->
                    actions.add(PhysicalAssetAction(name, "$name.default.action", "application/json"))
                }
            }
            this@WoTPhysicalAdapter.notifyPhysicalAdapterBound(pad)

            //consumedThing = consumeUsingMqttForSubscription()
            consumedThing = wot.consume(td)
            coroutineScope.launch {
                startClients()

                td.properties.forEach { (name, propertyAffordance) ->
                    launch { publishProperty(name,readProperty(name)) }
                    launch { startPropertyObservation(name, propertyAffordance) }
                }

                td.events.forEach { (name, _) ->
                    launch { subscribeToEvent(name) }
                }
            }
        } catch (e: Exception) {
            logger.error("Error during adapter startup: ${e.message}", e)
        }
    }

    /**
     * Stops the adapter and cleans up resources.
     * This includes stopping all active subscriptions and resetting polling states.
     */
    override fun onAdapterStop() {
        pollingActives.forEach { (name, _) -> pollingActives[name] = false }
        coroutineScope.launch {
            launch {
                eventSubscriptions.forEach { (_, sub) -> launch { sub.stop(InteractionOptions()) } }
                propertySubscriptions.forEach { (_, sub) -> launch { sub.stop(InteractionOptions()) } }
            }.join()
            startedClients.values.forEach { client -> launch { client?.stop() } }
        }
        logger.info("WoTPhysicalAdapter $id stopped.")
    }

    /**
     * Handles incoming physical action events and invokes the corresponding action on the consumed thing.
     *
     * @param event The incoming action event containing the action key and optional body.
     */
    override fun onIncomingPhysicalAction(event: PhysicalAssetActionWldtEvent<*>?) {
        try {
            event?.let { actionEvent ->
                val actionKey = actionEvent.actionKey
                if (actionKey == null) {
                    logger.warn("Received action event with null actionKey.")
                    return@let
                }

                val actionAffordance = td.actions[actionKey]
                if (actionAffordance == null) {
                    logger.warn("ActionKey '$actionKey' not found in Thing Description.")
                    return@let
                }

                val actionBody = actionEvent.getBody()
                logger.info("Action Received: $actionKey with body: $actionBody")

                coroutineScope.launch {
                    try {
                        val output = if (actionAffordance.input !is NullSchema) {
                            if (actionBody != null) {
                                consumedThing.invokeAction(actionKey, getInteractionInput(actionBody))
                            } else {
                                logger.error("Action '$actionKey' requires input ${actionAffordance.input}, but null body recived. Aborting invocation.")
                                return@launch
                            }
                        } else {
                            consumedThing.invokeAction(actionKey, getInteractionInput("")) // to handle actions without placeholder
                        }
                        logger.info("Action '$actionKey' invoked successfully. Output: ${output.value()}")
                    } catch (e: Exception) {
                        logger.error("Error invoking action '$actionKey' on consumed thing: ${e.message}", e)
                    }
                }
            } ?: logger.warn("Received null PhysicalAssetActionWldtEvent.")
        } catch (e: Exception) {
            logger.error("Error processing incoming physical action: ${e.message}")
        }
    }

    /**
     * Converts a property affordance into a PhysicalAssetProperty object.
     *
     * @param name The name of the property.
     * @param propertyAffordance The affordance describing the property.
     * @return A PhysicalAssetProperty object representing the property.
     */
    private fun getPhysicalAssetProperty(name: String, propertyAffordance: PropertyAffordance<*>): PhysicalAssetProperty<*> {
        return when (propertyAffordance) {
            is NumberProperty -> PhysicalAssetProperty(name, propertyAffordance.default ?: 0.0)
            is StringProperty -> PhysicalAssetProperty(name, propertyAffordance.default ?: "")
            is BooleanProperty -> PhysicalAssetProperty(name, propertyAffordance.default ?: false)
            is ObjectProperty -> PhysicalAssetProperty(name, propertyAffordance.default ?: emptyMap<String, Any>())
            is ArrayProperty<*> -> PhysicalAssetProperty(name, propertyAffordance.default ?: emptyList<Any>())
            else -> throw IllegalArgumentException("Unsupported Property Affordance type: ${propertyAffordance::class.simpleName}")
        }
    }

    /**
     * Starts observing a property on the consumed thing. If observation fails, falls back to polling.
     *
     * @param name The name of the property to observe.
     * @param propertyAffordance The affordance describing the property.
     */
    private suspend fun startPropertyObservation(name: String, propertyAffordance: PropertyAffordance<*>) {
        val observationSuccessful = propertyAffordance.observable && try {
            logger.info("Try subscribing for property: $name")
            val subscription = consumedThing.observeProperty(
                name,
                listener = { output ->
                    logger.info("Property observed ($name): ${output.value()}")
                    publishProperty(name, output.value())
                },
                errorListener = { error ->
                    // Exceptions stopped the observation, so we can start polling
                    logger.error("Error observing property '$name': ${error.message}")
                    startPollingForProperty(name)
                },
            )
            logger.info("Subscribed to property '$name' successfully.")
            propertySubscriptions[name] = subscription
            subscription.active
        } catch (e: Exception) {
            logger.warn("Could not observe property '$name', falling back to polling. Reason: ${e.message}")
            false
        }
        if (!observationSuccessful) {
            startPollingForProperty(name)
        }
    }

    /**
     * Starts polling for a property if observation is not possible.
     *
     * @param name The name of the property to poll.
     */
    private fun startPollingForProperty(name: String) {
        val options = adapterConfig.getPollingOptionsForProperty(name) ?: return
        val interval = options.pollingInterval
        if (interval < 0) {
            return
        }

        coroutineScope.launch {
            logger.info("Starting polling for property '$name'")
            pollingActives[name] = true
            delay(interval)
            if (options.onlyUpdatedValues) {
                var lastValue : Any? = null
                while (pollingActives[name] == true) {
                    val newValue = readProperty(name)
                    if (newValue != lastValue) {
                        lastValue = newValue
                        publishProperty(name, newValue)
                        delay(interval)
                    }
                }
            } else {
                while (pollingActives[name] == true) {
                    publishProperty(name, readProperty(name))
                    delay(interval)
                }
            }
        }
    }

    /**
     * Subscribes to an event on the consumed thing and publishes the event when received.
     *
     * @param name The name of the event to subscribe to.
     */
    private suspend fun subscribeToEvent(name: String) {
        logger.info("Subscribing to event: $name")
        val subscription = consumedThing.subscribeEvent(
            name,
            listener = { output ->
                val eventValue = output.value()
                if ("$eventValue".isNotEmpty()) {
                    logger.info("EVENT RECEIVED ($name): $eventValue")
                    this@WoTPhysicalAdapter.publishPhysicalAssetEventWldtEvent(PhysicalAssetEventWldtEvent(name, eventValue))
                }
            },
            errorListener = { error ->
                logger.error("Error in event subscription for '$name': ${error.message}")
            }
        )
        eventSubscriptions[name] = subscription
    }

    /**
     * Reads the value of a property from the consumed thing.
     *
     * @param name The name of the property to read.
     * @return The value of the property, or null if an error occurs.
     */
    private suspend fun readProperty(name: String): Any {
        return try {
            consumedThing.readProperty(name).value()
        } catch (e: Exception) {
            logger.error("Error reading property $name: ${e.message}")
        }
    }

    /**
     * Simulates an incoming action event for testing purposes.
     *
     * @param actionKey The key of the action to simulate.
     * @param body The body of the action, or null if no body is required.
     */
    fun simulateIncomingAction(actionKey: String, body: Any?) {
        val actionEvent = if (body == null) {
            PhysicalAssetActionWldtEvent(actionKey)
        } else {
            PhysicalAssetActionWldtEvent(actionKey, body)
        }
        onIncomingPhysicalAction(actionEvent)
    }

    /**
     * Converts a body object into an InteractionInput.Value for invoking actions.
     *
     * @param body The body object to convert.
     * @return The InteractionInput.Value representation of the body.
     * @throws IllegalArgumentException If the body type is unsupported.
     */
    private fun getInteractionInput(body: Any): InteractionInput.Value {
        return when (body) {
            is Number -> body.toInteractionInputValue()
            is String -> body.toInteractionInputValue()
            is Boolean -> body.toInteractionInputValue()
            is MutableMap<*, *> -> body.toInteractionInputValue()
            is MutableList<*> -> body.toInteractionInputValue()
            else -> throw IllegalArgumentException("Unsupported body type: ${body::class.simpleName}, only Number, String, Boolean, Map, List are supported.")
        }
    }

    /**
     * Publishes a property value to the WLDT framework.
     *
     * @param name The name of the property.
     * @param value The value of the property.
     */
    private fun publishProperty(name : String, value : Any?) {
        logger.info("Publishing property '$name' with value: $value")
        this@WoTPhysicalAdapter.publishPhysicalAssetPropertyWldtEvent(PhysicalAssetPropertyWldtEvent(name, value))
    }

    private suspend fun startClients() {
        try {
            val cd = consumedThing as ConsumedThing

            val allForms = td.properties.values.flatMap { it.forms } +
                    td.events.values.flatMap { it.forms } +
                    td.actions.values.flatMap { it.forms }

            allForms.forEach { form ->
                val baseHref = form.href.substringBefore(td.id+"/")
                if (baseHref !in startedClients.keys) {
                    startedClients[baseHref] = null
                    try {
                        // Use a generic operation type, as the client is for the protocol
                        val client = cd.getClientFor( Form(baseHref, form.contentType), Operation.READ_PROPERTY).first
                        client?.start() //not implemented for all protocols
                        startedClients[baseHref] = client
                        logger.info("Client started with from: '$baseHref'")
                    } catch (e: Throwable) {
                        logger.info("Failed to start client with form: '$baseHref' - ${e.message}")
                    }
                }
            }

        } catch (e: Exception) {
            logger.error("Error during client startup process", e)
        }
    }

    /**
     * Consumes the Thing Description removing all not mqtt href from forms with operation OBSERVE or SUBSCRIBE.
     *
     * @return The consumed thing.
     */
    private fun consumeUsingMqttForSubscription(): WoTConsumedThing {
        val cd = wot.consume(td) as ConsumedThing

        cd.properties.values.filter { it.forms.any { form -> form.hrefScheme == "mqtt" && form.op?.contains(Operation.OBSERVE_PROPERTY) ?: false } }
            .forEach { propertyAffordance ->
                propertyAffordance.forms.removeIf { it.op?.contains(Operation.OBSERVE_PROPERTY) ?: false && it.hrefScheme != "mqtt" }
                logger.info("REMOVED non-mqtt forms for property: $propertyAffordance")
            }

        cd.events.values.filter { it.forms.any { form -> form.hrefScheme == "mqtt" && form.op?.contains(Operation.SUBSCRIBE_EVENT) ?: false } }
            .forEach { eventAffordance ->
                eventAffordance.forms.removeIf { it.op?.contains(Operation.SUBSCRIBE_EVENT) ?: false && it.hrefScheme != "mqtt" }
                logger.info("REMOVED non-mqtt forms for event: $eventAffordance")
            }

        return cd
    }
}
