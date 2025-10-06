# wot-digital-adapter-kotlin

This is a Kotlin implementation of a digital adapter that allows interaction to the digital twin as a Web of Things (WoT) Thing.

## Quick start

```kotlin
    // Example of a Servient with HTTP and MQTT binding from library kotlin-wot

    val mqttConfig = MqttClientConfig("localhost", 61890, "exposer-0")

    val exposingServient = Servient(
        servers = listOf(
            HttpProtocolServer(),      // default: "http://localhost:8080"
            MqttProtocolServer(mqttConfig)
        ),
        clientFactories = listOf(
            HttpProtocolClientFactory(),
            MqttProtocolClientFactory(mqttConfig)
        )
    )

    val config = WoTDigitalAdapterConfiguration(
        servient = exposingServient,
        thingId = "temperature-sensor-0",
        thingTitle = "temperature sensor",
        description = "A temperature sensor with humidity reading and reset/setTemperature actions",
        observableProperties = setOf("temperature-property-key"),
        //allPropertiesObservable = true,
    )

    val woTDigitalAdapter = WoTDigitalAdapter("digital-adapter-0", config)
```
### how to reach the WoT Thing exposed by the adapter
```kotlin
    val thingId = woTDigitalAdapter.thingId
    val thingDescription = woTDigitalAdapter.getThingDescription().toString() // adapter need to be fully started
    val thingUrl = "${baseUrl}/${thingId}"
```
## Thing description generation
### Properties mapping
From
```kotlin
DigitalTwinStateProperty(
    key = 'temperature-property-key',
    value = TemperaturePropertyObject(23.5, 14, listOf(false, false, false)),
    type='physical.TemperaturePropertyObject',
    readable=true,
    writable=true,
    exposed=true
)
```
To Thing Description
```json
{
  "properties": {
    "temperature-property-key": {
      "type": "object",
      "forms": [
        {
          "href": "mqtt://localhost:61890/temperature-sensor-0/properties/temperature-property-key",
          "contentType": "application/json",
          "op": [
            "readproperty",
            "writeproperty"
          ]
        },
        {
          "href": "mqtt://localhost:61890/temperature-sensor-0/properties/temperature-property-key/observable",
          "contentType": "application/json",
          "op": [
            "observeproperty",
            "unobserveproperty"
          ]
        },
        {
          "href": "http://localhost:8080/temperature-sensor-0/properties/temperature-property-key",
          "contentType": "application/json",
          "op": [
            "readproperty",
            "writeproperty"
          ],
          "optionalProperties": {
            "htv:methodName": ""
          }
        },
        {
          "href": "http://localhost:8080/temperature-sensor-0/properties/temperature-property-key/observable",
          "contentType": "application/json",
          "subprotocol": "longpoll",
          "op": [
            "observeproperty",
            "unobserveproperty"
          ]
        }
      ],
      "observable": true,
      "properties": {
        "temperature": {
          "type": "number"
        },
        "hour": {
          "type": "integer"
        },
        "alarms": {
          "type": "array",
          "items": {
            "type": "boolean"
          }
        }
      },
      "default": {
        "temperature": 0.0,
        "hour": 0,
        "alarms": [
          false,
          false,
          false
        ]
      }
    }
  }
}
```
### Actions mapping
From
```kotlin
DigitalTwinStateAction(
    key='set-temperature-action-key',
    type='temperature.actuation',
    contentType='text/plain', exposed=true
)
```
To Thing Description
```json
{
    "actions": {
        "set-temperature-action-key": {
          "forms": [
            {
            "href": "mqtt://localhost:61890/temperature-sensor-0/actions/set-temperature-action-key",
            "contentType": "application/json",
            "op": [
            "invokeaction"
            ]
            },
            {
              "href": "http://localhost:8080/temperature-sensor-0/actions/set-temperature-action-key",
              "contentType": "application/json",
              "op": [
                "invokeaction"
              ]
            }
          ],
          "@type": "temperature.actuation",
          "input": {
            "type": "string"
          }
        }
    }
}
```
NOTE: Serialization of collection or object type input is not supported, Use string instead.

### Events mapping
From
```kotlin
DigitalTwinStateEvent(
    key='overheating-event-key',
    type='text/plain'
)
```
To Thing Description
```json
{
  "events": {
    "overheating-event-key": {
      "@type": "text/plain",
      "forms": [
        {
          "href": "mqtt://localhost:61890/temperature-sensor-0/events/overheating-event-key",
          "contentType": "application/json",
          "op": [
            "subscribeevent",
            "unsubscribeevent"
          ],
          "optionalProperties": {
            "mqtt:qos": 0,
            "mqtt:retain": false
          }
        },
        {
          "href": "http://localhost:8080/temperature-sensor-0/events/overheating-event-key",
          "contentType": "application/json",
          "subprotocol": "longpoll",
          "op": [
            "subscribeevent"
          ]
        }
      ]
    }
  }
}
```
### Relationships
Relationships are not supported yet, they won't have a representation in the Thing Description produced by the adapter
