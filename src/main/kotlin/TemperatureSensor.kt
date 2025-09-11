import org.eclipse.thingweb.reflection.annotations.*
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

@Thing(
    id = "tSensor",
    title = "Temperature Sensor",
    description = "A thing with properties, actions, and events."
)
@VersionInfo(instance = "1.0.0")
class TemperatureSensor {

    private val MAX_TEMP_THRESHOLD = 24.0
    private var t: MutableStateFlow<Double> = MutableStateFlow(22.0)
    private var h: Double = 50.0
    private val u: String = "C"
    // replay=1 to keep the last emitted event for new subscribers
    private val overheatFlow = MutableSharedFlow<String>(replay = 1)
    private var lastOverheatState = false

    init {
        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(777)
                t.value += 0.1//Random.nextDouble(-0.5, 0.5)
                checkOverheat()
            }
        }
        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(2000)
                val r = Random.nextInt(4)
                h += when (r) {
                    0 -> -0.01
                    1 -> 0.01
                    else -> 0.0
                }
            }
        }
    }

    @Property(title = "temperature", readOnly = true)
    val temperature: MutableStateFlow<Double>
        get() = t

    @Property(title = "humidity", readOnly = true)
    val humidity: Double
        get() = h

    @Property(title = "unit", readOnly = true)
    val unit: String
        get() = u

    @Action(title = "reset")
    fun reset(): String {
        t.value = 22.0
        h = 50.0
        lastOverheatState = false
        return "Sensor reset to 22.0°C"
    }

    @Action(title = "setTemperature")
    fun setTemperature(newTemp: Double): String {
        if (newTemp < -50.0 || newTemp > 100.0) {
            return "Error: Temperature must be between -50.0°C and 100.0°C"
        }
        t.value = newTemp
        checkOverheat()
        return "Temperature set to $newTemp°C"
    }

    @Event(title = "overheat")
    fun overheatEvent(): kotlinx.coroutines.flow.Flow<String> {
        return overheatFlow
    }


    private fun checkOverheat() {
        CoroutineScope(Dispatchers.Default).launch {
            val isCurrentlyOverheating = t.value > MAX_TEMP_THRESHOLD
            if (isCurrentlyOverheating && !lastOverheatState) {
                val alertMessage = "ALERT: Temperature exceeded 24°C! Current: ${String.format("%.1f", t.value)}°C"
                println("DEBUG: Emitting overheat event: $alertMessage")
                overheatFlow.emit(alertMessage)
            }
            lastOverheatState = isCurrentlyOverheating
        }
    }
}
