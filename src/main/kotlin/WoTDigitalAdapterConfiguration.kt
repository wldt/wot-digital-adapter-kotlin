import org.eclipse.thingweb.Servient


class WoTDigitalAdapterConfiguration(
    private val servient: Servient,
    private val thingTitle: String = "DT",
    private val description: String? = null,
) {

    fun getServient(): Servient {
        return servient
    }

    fun getTDinfo(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        map["title"] = thingTitle
        if (description != null) {
            map["description"] = description
        }
        return map
    }
}