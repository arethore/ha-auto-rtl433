package io.github.arethore.ha_auto_rtl433.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.Test;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.arethore.ha_auto_rtl433.conf.Config;

public class MqttServiceTest {

    @Test
    public void buildsDiscoveryPayloadWithExpectedIdentifiers() throws MqttException {
        Config.Mqtt mqttConfig = new Config.Mqtt();
        mqttConfig.setHost("localhost");
        mqttConfig.setPort(1883);
        mqttConfig.setQueueCapacity(10);

        MqttService service = new MqttService(mqttConfig);
        try {
            Config.Rtl433.WhitelistEntry.Entity entity = new Config.Rtl433.WhitelistEntry.Entity();
            entity.setType("sensor");
            entity.setAttribute("temperature");
            entity.setName("Temperature");
            entity.setEntityClass("temperature");
            entity.setUnit("°C");

            MqttService.EntityIdentifiers identifiers = new MqttService.EntityIdentifiers(
                    "sensor",
                    "esperanza_ews_001_temperature",
                    "esperanza_ews_001",
                    "temperature",
                    "rtl_433/devices/esperanza_ews_001/temperature/state");

            ObjectNode payload = service.buildDiscoveryPayload(entity, identifiers, "Esperanza", "EWS",
                    "Fridge Thermometer", "Temperature");

            assertNotNull(payload);
            assertEquals("Temperature", payload.get("name").asText());
            assertEquals("esperanza_ews_001_temperature", payload.get("unique_id").asText());
            assertEquals("rtl_433/devices/esperanza_ews_001/temperature/state", payload.get("state_topic").asText());
            assertEquals("rtl_433/service/status", payload.get("availability_topic").asText());
            assertEquals("temperature", payload.get("device_class").asText());

            ObjectNode device = (ObjectNode) payload.get("device");
            assertNotNull(device);
            assertEquals("Esperanza", device.get("manufacturer").asText());
            assertEquals("EWS", device.get("model").asText());
            assertEquals("Fridge Thermometer", device.get("name").asText());
            ArrayNode identifiersArray = (ArrayNode) device.get("identifiers");
            assertNotNull(identifiersArray);
            assertEquals("esperanza_ews_001", identifiersArray.get(0).asText());
        } finally {
            service.close();
        }
    }
}
