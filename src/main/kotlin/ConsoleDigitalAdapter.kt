import it.wldt.adapter.digital.DigitalAdapter
import it.wldt.core.state.DigitalTwinState
import it.wldt.core.state.DigitalTwinStateChange
import it.wldt.core.state.DigitalTwinStateEventNotification
import java.util.ArrayList

class ConsoleDigitalAdapter(id: String?) : DigitalAdapter<String>(id) {

    override fun onStateUpdate(newState: DigitalTwinState?, oldState: DigitalTwinState?, p2: ArrayList<DigitalTwinStateChange>?) {
        println("State update: $newState")
    }

    override fun onEventNotificationReceived(p0: DigitalTwinStateEventNotification<*>?) {}

    override fun onAdapterStart() {}

    override fun onAdapterStop() {}

    override fun onDigitalTwinSync(state: DigitalTwinState?) {}

    override fun onDigitalTwinUnSync(state: DigitalTwinState?) {}

    override fun onDigitalTwinCreate() {}

    override fun onDigitalTwinStart() {}

    override fun onDigitalTwinStop() {}

    override fun onDigitalTwinDestroy() {}
}