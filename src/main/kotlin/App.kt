import it.wldt.core.engine.DigitalTwin
import it.wldt.core.engine.DigitalTwinEngine
import it.wldt.core.model.ShadowingFunction
import org.eclipse.thingweb.Servient
import org.eclipse.thingweb.binding.mqtt.MqttClientConfig
import org.eclipse.thingweb.binding.mqtt.MqttProtocolClientFactory
import org.eclipse.thingweb.binding.http.HttpProtocolClientFactory

import java.net.URI
import kotlinx.coroutines.*
import org.eclipse.thingweb.binding.http.HttpProtocolServer
import org.eclipse.thingweb.binding.mqtt.MqttProtocolServer
import org.slf4j.LoggerFactory

/**
 * Template for kotlin lib (WLDT + Kotlin-WoT).
 */
fun sampleFunction() {
    val logger = LoggerFactory.getLogger("AppMain")
    val thingId = TemperatureSensor::class.java.getAnnotation(org.eclipse.thingweb.reflection.annotations.Thing::class.java).id
    val mqttConsumerConfig = MqttClientConfig("localhost", 61890, "consumer-${thingId}-${System.currentTimeMillis()}")
    val mqttExposerConfig = MqttClientConfig("localhost", 61891, "exposer-${thingId}-${System.currentTimeMillis()}")

    val consumingServient = Servient(
        clientFactories = listOf(
            MqttProtocolClientFactory(mqttConsumerConfig), //needs mqtt first
            HttpProtocolClientFactory()
        )
    )

    val digitalServient = Servient(
        servers = listOf(
            HttpProtocolServer(bindPort = 8081, baseUrls = listOf("http://localhost:8081/")),
            MqttProtocolServer(mqttExposerConfig)
        ),
        clientFactories = listOf(
            HttpProtocolClientFactory(),
            MqttProtocolClientFactory(mqttExposerConfig)
        )
    )

    val physicalAdapter = WoTPhysicalAdapter(
        "adapter-$thingId",
        WoTPhysicalAdapterConfiguration(
            tdSource = ThingDescriptionSource.FromUri(URI.create("http://localhost:8080/$thingId")),
            servient = consumingServient,
            propertiesPollingOptions = mapOf(
                "temperature" to PollingOptions(1000, false),
                "humidity" to PollingOptions(2000, true)
            )
        )
    )

    val woTDigitalAdapter = WoTDigitalAdapter(
        "digital-adapter-$thingId",
        WoTDigitalAdapterConfiguration(
            servient = digitalServient,
            thingTitle = "temperature sensor",
            description = "A temperature sensor with humidity reading and reset/setTemperature actions",
            allPropertiesObservable = true
        )
    )

    val shadowing: ShadowingFunction = BasicShadowing()
    val engine = DigitalTwinEngine()
    val dt = DigitalTwin("dt-$thingId", shadowing)

    dt.addPhysicalAdapter(physicalAdapter)
    dt.addDigitalAdapter(woTDigitalAdapter)
    engine.addDigitalTwin(dt)

    runBlocking {
        try {
            logger.info("Starting DT...")
            engine.startAll()
            delay(200000)

            engine.stopAll()
            logger.info("Test sequence finished.")

        } catch (e: Exception) {
            logger.error("An error occurred in main: ${e.message}", e)
        }
    }
    logger.info("Application finished.")
}

