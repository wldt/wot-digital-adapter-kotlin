import it.wldt.adapter.digital.event.DigitalActionWldtEvent
import it.wldt.adapter.physical.PhysicalAssetAction
import it.wldt.adapter.physical.PhysicalAssetDescription
import it.wldt.adapter.physical.PhysicalAssetEvent
import it.wldt.adapter.physical.PhysicalAssetProperty
import it.wldt.adapter.physical.event.PhysicalAssetEventWldtEvent
import it.wldt.adapter.physical.event.PhysicalAssetPropertyWldtEvent
import it.wldt.adapter.physical.event.PhysicalAssetRelationshipInstanceCreatedWldtEvent
import it.wldt.adapter.physical.event.PhysicalAssetRelationshipInstanceDeletedWldtEvent
import it.wldt.core.model.ShadowingFunction
import it.wldt.core.state.DigitalTwinStateAction
import it.wldt.core.state.DigitalTwinStateEvent
import it.wldt.core.state.DigitalTwinStateEventNotification
import it.wldt.core.state.DigitalTwinStateProperty
import java.util.function.Consumer

class BasicShadowing(id: String? = "basic-shadowing") : ShadowingFunction(id) {

    override fun onCreate() {}

    override fun onStart() {}

    override fun onStop() {}

    override fun onDigitalTwinBound(adaptersPhysicalAssetDescriptionMap: MutableMap<String?, PhysicalAssetDescription?>) {
        try {
            this.digitalTwinStateManager.startStateTransaction()
            adaptersPhysicalAssetDescriptionMap.values.forEach(Consumer { pad: PhysicalAssetDescription? ->
                pad!!.properties.forEach(Consumer { property: PhysicalAssetProperty<*>? ->
                    try {
                        //Create and write the property on the DT's State

                        this.digitalTwinStateManager.createProperty(
                            DigitalTwinStateProperty(property!!.key, property.getInitialValue()
                            )
                        )

                        //Start observing the variation of the physical property in order to receive notifications
                        //Without this call the Shadowing Function will not receive any notifications or callback about
                        //incoming physical property of the target type and with the target key
                        this.observePhysicalAssetProperty(property)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                })
                //Iterate over available declared Physical Events for the target Physical Adapter's PAD
                pad.events.forEach(Consumer { event: PhysicalAssetEvent? ->
                    try {
                        //Instantiate a new DT State Event with the same key and type

                        val dtStateEvent = DigitalTwinStateEvent(event!!.key, event.type)

                        //Create and write the event on the DT's State
                        this.digitalTwinStateManager.registerEvent(dtStateEvent)

                        //Start observing the variation of the physical event in order to receive notifications
                        //Without this call the Shadowing Function will not receive any notifications or callback about
                        //incoming physical events of the target type and with the target key
                        this.observePhysicalAssetEvent(event)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                })

                //Iterate over available declared Physical Actions for the target Physical Adapter's PAD
                pad.actions.forEach(Consumer { action: PhysicalAssetAction? ->
                    try {
                        //Instantiate a new DT State Action with the same key and type
                        val dtStateAction = DigitalTwinStateAction(action!!.key, action.type, action.contentType)
                        //Enable the action on the DT's State
                        this.digitalTwinStateManager.enableAction(dtStateAction)

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                })
            })

            // NEW in 0.3.0 -> Commit DT State Change Transaction to apply the changes on the DT State and notify about the change
            this.digitalTwinStateManager.commitStateTransaction()

            //Start observation to receive all incoming Digital Action through active Digital Adapter
            //Without this call the Shadowing Function will not receive any notifications or callback about
            //incoming request to execute an exposed DT's Action
            observeDigitalActionEvents()

            //Notify the DT Core that the Bounding phase has been correctly completed and the DT has evaluated its
            //internal status according to what is available and declared through the Physical Adapters
            notifyShadowingSync()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDigitalTwinUnBound(pads: MutableMap<String, PhysicalAssetDescription>?, p1: String?) {}

    override fun onPhysicalAdapterBidingUpdate(paID: String?, pad: PhysicalAssetDescription?) {}

    override fun onPhysicalAssetPropertyVariation(propertyEvent: PhysicalAssetPropertyWldtEvent<*>?) {
        propertyEvent?.physicalPropertyId?.let { propertyId ->
            val value = propertyEvent.body
            digitalTwinStateManager.startStateTransaction()
            digitalTwinStateManager.updateProperty(DigitalTwinStateProperty(propertyId, value))
            digitalTwinStateManager.commitStateTransaction()
        }

    }

    override fun onPhysicalAssetEventNotification(physicalAssetEventWldtEvent: PhysicalAssetEventWldtEvent<*>) {
        try {
            this.digitalTwinStateManager.notifyDigitalTwinStateEvent(
                DigitalTwinStateEventNotification<Any?>(
                    physicalAssetEventWldtEvent.physicalEventKey,
                    physicalAssetEventWldtEvent.getBody(),
                    physicalAssetEventWldtEvent.creationTimestamp
                )
            )
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    override fun onPhysicalAssetRelationshipEstablished(relationshipEvent: PhysicalAssetRelationshipInstanceCreatedWldtEvent<*>?) {
        // no relationships
    }

    override fun onPhysicalAssetRelationshipDeleted(relationshipEvent: PhysicalAssetRelationshipInstanceDeletedWldtEvent<*>?) {
        // no relationships
    }

    override fun onDigitalActionEvent(digitalActionWldtEvent: DigitalActionWldtEvent<*>) {
        try {
            this.publishPhysicalAssetActionWldtEvent(
                digitalActionWldtEvent.actionKey,
                digitalActionWldtEvent.getBody()
            )
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }
}