import it.wldt.adapter.digital.event.DigitalActionWldtEvent
import it.wldt.adapter.physical.PhysicalAssetDescription
import it.wldt.adapter.physical.event.PhysicalAssetEventWldtEvent
import it.wldt.adapter.physical.event.PhysicalAssetPropertyWldtEvent
import it.wldt.adapter.physical.event.PhysicalAssetRelationshipInstanceCreatedWldtEvent
import it.wldt.adapter.physical.event.PhysicalAssetRelationshipInstanceDeletedWldtEvent
import it.wldt.core.model.ShadowingFunction
import it.wldt.core.state.DigitalTwinStateProperty

class BasicShadowing(id: String? = "basic-shadowing") : ShadowingFunction(id) {

    override fun onCreate() {}

    override fun onStart() {}

    override fun onStop() {}

    override fun onDigitalTwinBound(pads: MutableMap<String, PhysicalAssetDescription>?) {
        digitalTwinStateManager.startStateTransaction()
        pads?.values?.forEach { pad ->
            pad.properties.forEach { property ->
                observePhysicalAssetProperty(property)
                digitalTwinStateManager.createProperty(
                    DigitalTwinStateProperty(
                        property.key,
                        property.initialValue as Any
                    )
                )

            }
        }
        digitalTwinStateManager.commitStateTransaction()
        notifyShadowingSync()
    }

    override fun onDigitalTwinUnBound(pads: MutableMap<String, PhysicalAssetDescription>?, p1: String?) {}

    override fun onPhysicalAdapterBidingUpdate(paID: String?, pad: PhysicalAssetDescription?) {}

    override fun onPhysicalAssetPropertyVariation(propertyEvent: PhysicalAssetPropertyWldtEvent<*>?) {
        propertyEvent?.physicalPropertyId?.let { propertyId ->
            val temperature = propertyEvent.body as Any
            digitalTwinStateManager.startStateTransaction()
            digitalTwinStateManager.updateProperty(DigitalTwinStateProperty(propertyId, temperature))
            digitalTwinStateManager.commitStateTransaction()
        }

    }

    override fun onPhysicalAssetEventNotification(event: PhysicalAssetEventWldtEvent<*>?) {
        // no events
    }

    override fun onPhysicalAssetRelationshipEstablished(relationshipEvent: PhysicalAssetRelationshipInstanceCreatedWldtEvent<*>?) {
        // no relationships
    }

    override fun onPhysicalAssetRelationshipDeleted(relationshipEvent: PhysicalAssetRelationshipInstanceDeletedWldtEvent<*>?) {
        // no relationships
    }

    override fun onDigitalActionEvent(actionEvent: DigitalActionWldtEvent<*>?) {
        // no actions
    }
}