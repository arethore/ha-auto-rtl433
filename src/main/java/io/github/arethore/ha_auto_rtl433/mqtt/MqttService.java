package io.github.arethore.ha_auto_rtl433.mqtt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.arethore.ha_auto_rtl433.conf.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates all MQTT interactions for entity provisioning and state
 * publication. It serialises publishes through a dedicated worker, maintains a
 * cache of known entities, honours queue backpressure, and reports telemetry
 * about dropped versus successful messages.
 */
public class MqttService implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOGGER = LoggerFactory.getLogger(MqttService.class);
    private static final int DEFAULT_QUEUE_CAPACITY = 256;

    private final MqttClient client;
    private final Set<String> knownEntities = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<PublishRequest> publishQueue;
    private final MqttConnectOptions connectOptions;
    private final int queueCapacity;
    private final LongAdder droppedPublishes = new LongAdder();
    private final LongAdder successfulPublishes = new LongAdder();
    private volatile boolean publisherRunning;
    private volatile boolean clientConnected;
    private volatile boolean reconnecting;
    private final Object reconnectMonitor = new Object();
    private Thread publisherThread;

    public MqttService(Config.Mqtt config) throws MqttException {
        Objects.requireNonNull(config, "MQTT config is required");
        String brokerUrl = "tcp://" + config.getHost() + ":" + config.getPort();
        MqttClientPersistence persistence = new MqttDefaultFilePersistence(
                System.getProperty("java.io.tmpdir") + "/ha-auto-rtl433-paho");
        this.client = new MqttClient(brokerUrl, MqttClient.generateClientId(), persistence);
        this.connectOptions = buildConnectOptions(config);
        this.client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                clientConnected = true;
                synchronized (reconnectMonitor) {
                    reconnecting = false;
                }
                if (reconnect) {
                    LOGGER.info("Reconnected to MQTT broker {}", serverURI);
                } else {
                    LOGGER.info("Connected to MQTT broker {}", serverURI);
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                clientConnected = false;
                LOGGER.warn("MQTT connection lost", cause);
                scheduleReconnect();
            }

            @Override
            public void messageArrived(String topic, org.eclipse.paho.client.mqttv3.MqttMessage message) {
                // no-op
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // no-op
            }
        });
        this.queueCapacity = resolveQueueCapacity(config.getQueueCapacity());
        this.publishQueue = new LinkedBlockingQueue<>(queueCapacity);
    }

    public void connect() throws MqttException {
        client.connect(connectOptions);
        clientConnected = client.isConnected();
        startPublisher();
    }

    public void loadExistingEntities(Duration waitTime) throws MqttException {
        IMqttMessageListener listener = (topic, message) -> {
            String[] parts = topic.split("/");
            if (parts.length >= 4) {
                String component = parts[1];
                String uniqueId = parts[2];
                knownEntities.add(component + "/" + uniqueId);
            }
        };

        client.subscribe("homeassistant/+/+/config", listener);
        try {
            Thread.sleep(waitTime.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                client.unsubscribe("homeassistant/+/+/config");
            } catch (MqttException e) {
                // ignore unsubscribe failure
            }
        }
    }

    public String ensureEntityConfig(Config.Rtl433.WhitelistEntry deviceEntry,
            Config.Rtl433.WhitelistEntry.Entity entity) {
        return ensureEntityConfig(deviceEntry, entity, false, false);
    }

    public String ensureEntityConfig(Config.Rtl433.WhitelistEntry deviceEntry,
            Config.Rtl433.WhitelistEntry.Entity entity, boolean force, boolean useFriendlyNames) {
        EntityIdentifiers identifiers = resolveIdentifiers(deviceEntry, entity);
        String key = identifiers.component + "/" + identifiers.uniqueId;

        if (!force && knownEntities.contains(key)) {
            return identifiers.stateTopic;
        }

        String[] modelParts = deviceEntry.getModel() != null ? deviceEntry.getModel().split("->", 2)
                : new String[0];
        String manufacturer = modelParts.length == 2 ? modelParts[0].trim() : deviceEntry.getModel();
        String modelName = modelParts.length == 2 ? modelParts[1].trim() : deviceEntry.getModel();

        String deviceName = useFriendlyNames
                ? firstNonBlank(deviceEntry.getName(), identifiers.deviceKey)
                : identifiers.deviceKey;
        String entityName = useFriendlyNames
                ? firstNonBlank(entity.getName(), identifiers.sanitizedAttribute)
                : identifiers.sanitizedAttribute;

        ObjectNode root = buildDiscoveryPayload(entity, identifiers, manufacturer, modelName, deviceName,
                entityName);

        String topic = "homeassistant/" + identifiers.component + "/" + identifiers.uniqueId + "/config";
        try {
            byte[] payload = MAPPER.writeValueAsBytes(root);
            MqttMessage message = new MqttMessage(payload);
            message.setRetained(true);
            publish(topic, message).join();
            knownEntities.add(key);
            LOGGER.info("Registered Home Assistant entity {} at {}", identifiers.uniqueId, topic);
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish entity config for topic " + topic, e);
        }

        return identifiers.stateTopic;
    }

    public boolean isEntityKnown(String component, String uniqueId) {
        return knownEntities.contains(component + "/" + uniqueId);
    }

    public void reconfigureExisting(List<Config.Rtl433.WhitelistEntry> entries) {
        if (entries == null) {
            return;
        }
        LOGGER.info("Reconfiguring Home Assistant entities using friendly names...");
        for (Config.Rtl433.WhitelistEntry entry : entries) {
            entry.getAllEntities().forEach(entity -> {
                try {
                    EntityIdentifiers identifiers = resolveIdentifiers(entry, entity);
                    String key = identifiers.component + "/" + identifiers.uniqueId;
                    if (knownEntities.contains(key)) {
                        ensureEntityConfig(entry, entity, true, true);
                    }
                } catch (RuntimeException ex) {
                    LOGGER.warn("Failed to reconfigure entity {}/{}", entry.getModel(), entity.getAttribute(), ex);
                }
            });
        }
    }

    public void publishState(String topic, Object value) {
        if (topic == null || topic.isBlank() || value == null) {
            return;
        }
        byte[] payload = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        MqttMessage message = new MqttMessage(payload);
        message.setRetained(true);
        publish(topic, message).exceptionally(ex -> {
            LOGGER.warn("Failed to publish state to topic {}", topic, ex);
            return null;
        });
    }

    @Override
    public void close() {
        try {
            stopPublisher();
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (MqttException e) {
            // best effort disconnect
        } finally {
            try {
                client.close();
            } catch (MqttException ignore) {
                // ignore
            }
            clientConnected = false;
            long sent = successfulPublishes.sum();
            long dropped = droppedPublishes.sum();
            LOGGER.info("MQTT publish summary: sent={} dropped={}", sent, dropped);
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        cleaned = cleaned.replaceAll("[^a-z0-9_]", "_");
        cleaned = cleaned.replaceAll("_+", "_");
        if (cleaned.startsWith("_")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.endsWith("_")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static void putIfNotBlank(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static void putAny(ObjectNode node, String field, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Boolean bool) {
            node.put(field, bool);
        } else if (value instanceof Integer integer) {
            node.put(field, integer);
        } else if (value instanceof Long longValue) {
            node.put(field, longValue);
        } else if (value instanceof Double doubleValue) {
            node.put(field, doubleValue);
        } else if (value instanceof Float floatValue) {
            node.put(field, floatValue);
        } else if (value instanceof Short shortValue) {
            node.put(field, shortValue.intValue());
        } else if (value instanceof Byte byteValue) {
            node.put(field, byteValue.intValue());
        } else if (value instanceof Number number) {
            node.put(field, number.doubleValue());
        } else {
            node.put(field, value.toString());
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value;
        }
        return fallback;
    }

    ObjectNode buildDiscoveryPayload(Config.Rtl433.WhitelistEntry.Entity entity, EntityIdentifiers identifiers,
            String manufacturer, String modelName, String deviceName, String entityName) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("name", entityName);
        root.put("unique_id", identifiers.uniqueId);
        root.put("state_topic", identifiers.stateTopic);
        root.put("availability_topic", ServiceStatusPublisher.STATUS_TOPIC);
        if (entity.getEntityClass() != null && !entity.getEntityClass().isBlank()) {
            root.put("device_class", entity.getEntityClass());
        }
        if (entity.getUnit() != null && !entity.getUnit().isBlank()) {
            root.put("unit_of_measurement", entity.getUnit());
        }
        putIfNotBlank(root, "value_template", entity.getValueTemplate());
        putAny(root, "payload_on", entity.getOnValue());
        putAny(root, "payload_off", entity.getOffValue());

        ObjectNode deviceNode = root.putObject("device");
        deviceNode.putArray("identifiers").add(identifiers.deviceKey);
        deviceNode.put("manufacturer", manufacturer);
        deviceNode.put("model", modelName);
        deviceNode.put("name", deviceName);
        return root;
    }

    private EntityIdentifiers resolveIdentifiers(Config.Rtl433.WhitelistEntry deviceEntry,
            Config.Rtl433.WhitelistEntry.Entity entity) {
        String component = requireNonBlank(entity.getType(), "Entity type is required");
        String attributeKey = entity.getRename();
        if (attributeKey == null || attributeKey.isBlank()) {
            attributeKey = requireNonBlank(entity.getAttribute(), "Entity attribute is required");
        }

        String sanitizedModel = sanitize(requireNonBlank(deviceEntry.getModel(), "Device model is required"));
        String sanitizedHaId = sanitize(requireNonBlank(deviceEntry.getHaId(), "Device haId is required"));
        String sanitizedAttribute = sanitize(attributeKey);
        String deviceKey = sanitizedModel + "_" + sanitizedHaId;
        String uniqueId = deviceKey + "_" + sanitizedAttribute;
        String stateTopic = "rtl_433/devices/" + deviceKey + "/" + sanitizedAttribute + "/state";
        return new EntityIdentifiers(component, uniqueId, deviceKey, sanitizedAttribute, stateTopic);
    }

    private int resolveQueueCapacity(Integer configuredCapacity) {
        if (configuredCapacity == null) {
            return DEFAULT_QUEUE_CAPACITY;
        }
        if (configuredCapacity < 1) {
            LOGGER.warn("Configured MQTT queue capacity {} is invalid. Falling back to {}",
                    configuredCapacity, DEFAULT_QUEUE_CAPACITY);
            return DEFAULT_QUEUE_CAPACITY;
        }
        return configuredCapacity;
    }

    private MqttConnectOptions buildConnectOptions(Config.Mqtt config) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(false);
        options.setCleanSession(false);
        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            options.setUserName(config.getUsername());
        }
        if (config.getPassword() != null && !config.getPassword().isBlank()) {
            options.setPassword(config.getPassword().toCharArray());
        }
        return options;
    }

    private void startPublisher() {
        if (publisherRunning) {
            return;
        }
        publisherRunning = true;
        publisherThread = new Thread(this::runPublisher, "mqtt-publisher");
        publisherThread.setDaemon(true);
        publisherThread.start();
    }

    private void stopPublisher() {
        if (!publisherRunning) {
            return;
        }
        publisherRunning = false;
        if (publisherThread != null) {
            publisherThread.interrupt();
            try {
                publisherThread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            publisherThread = null;
        }
        PublishRequest pending;
        while ((pending = publishQueue.poll()) != null) {
            droppedPublishes.increment();
            pending.future.completeExceptionally(
                    new IllegalStateException("MQTT publisher stopped before sending message"));
        }
    }

    private void scheduleReconnect() {
        synchronized (reconnectMonitor) {
            if (reconnecting) {
                return;
            }
            reconnecting = true;
        }
        Thread thread = new Thread(() -> {
            LOGGER.info("MQTT reconnect loop started");
            while (!clientConnected) {
                try {
                    LOGGER.info("Attempting MQTT reconnect...");
                    if (!client.isConnected()) {
                        client.reconnect();
                    }
                    for (int i = 0; i < 10 && !clientConnected; i++) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    if (clientConnected) {
                        LOGGER.info("MQTT reconnect successful");
                    }
                } catch (MqttException e) {
                    LOGGER.warn("MQTT reconnect attempt failed", e);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            synchronized (reconnectMonitor) {
                reconnecting = false;
            }
        }, "mqtt-reconnect");
        thread.setDaemon(true);
        thread.start();
    }

    private CompletableFuture<Void> publish(String topic, MqttMessage message) {
        if (!publisherRunning) {
            LOGGER.warn("Dropping publish to {} (payload={}) because publisher thread is not running",
                    topic, payloadPreview(message));
            droppedPublishes.increment();
            return failedFuture(new IllegalStateException("MQTT publisher is not running"));
        }
        if (!clientConnected || !client.isConnected()) {
            LOGGER.warn("Dropping publish to {} (payload={}) because MQTT client is disconnected",
                    topic, payloadPreview(message));
            droppedPublishes.increment();
            return failedFuture(new IllegalStateException("MQTT client not connected"));
        }
        PublishRequest request = new PublishRequest(topic, message);
        if (!publishQueue.offer(request)) {
            LOGGER.warn("Dropping publish to {} (payload={}) because queue capacity {} is exceeded",
                    topic, payloadPreview(message), queueCapacity);
            droppedPublishes.increment();
            return failedFuture(new IllegalStateException("MQTT publish queue full"));
        }
        return request.future;
    }

    private void runPublisher() {
        while (publisherRunning || !publishQueue.isEmpty()) {
            try {
                PublishRequest request = publishQueue.poll(200, TimeUnit.MILLISECONDS);
                if (request == null) {
                    continue;
                }
                try {
                    if (!clientConnected || !client.isConnected()) {
                        IllegalStateException ex = new IllegalStateException("MQTT client not connected");
                        LOGGER.warn("MQTT disconnected while sending to {} (payload={}). Dropping message.",
                                request.topic, payloadPreview(request.message));
                        droppedPublishes.increment();
                        request.future.completeExceptionally(ex);
                        continue;
                    }
                    client.publish(request.topic, request.message);
                    successfulPublishes.increment();
                    request.future.complete(null);
                } catch (Exception ex) {
                    LOGGER.warn("Failed to publish to {} (payload={}). Dropping message.",
                            request.topic, payloadPreview(request.message), ex);
                    droppedPublishes.increment();
                    request.future.completeExceptionally(ex);
                }
            } catch (InterruptedException e) {
                if (!publisherRunning && publishQueue.isEmpty()) {
                    break;
                }
            }
        }
    }

    private static final class PublishRequest {
        private final String topic;
        private final MqttMessage message;
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        private PublishRequest(String topic, MqttMessage message) {
            this.topic = topic;
            this.message = message;
        }
    }

    private static String payloadPreview(MqttMessage message) {
        if (message == null) {
            return "<null>";
        }
        byte[] payload = message.getPayload();
        if (payload == null || payload.length == 0) {
            return "<empty>";
        }
        String text = new String(payload, StandardCharsets.UTF_8);
        if (text.length() > 200) {
            return text.substring(0, 200) + "...";
        }
        return text;
    }

    private static CompletableFuture<Void> failedFuture(Exception ex) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        future.completeExceptionally(ex);
        return future;
    }

    static final class EntityIdentifiers {
        private final String component;
        private final String uniqueId;
        private final String deviceKey;
        private final String sanitizedAttribute;
        private final String stateTopic;

        EntityIdentifiers(String component, String uniqueId, String deviceKey, String sanitizedAttribute,
                String stateTopic) {
            this.component = component;
            this.uniqueId = uniqueId;
            this.deviceKey = deviceKey;
            this.sanitizedAttribute = sanitizedAttribute;
            this.stateTopic = stateTopic;
        }
    }
}
