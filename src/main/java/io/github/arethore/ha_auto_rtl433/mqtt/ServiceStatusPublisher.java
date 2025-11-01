package io.github.arethore.ha_auto_rtl433.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.arethore.ha_auto_rtl433.conf.Config;

/**
 * Maintains a dedicated MQTT connection that drives the shared availability
 * topic {@code rtl_433/service/status}. It publishes a retained {@code online}
 * status on connect, registers a last will for {@code offline}, and re-announces
 * availability transparently after reconnects.
 */
public class ServiceStatusPublisher implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceStatusPublisher.class);
    public static final String STATUS_TOPIC = "rtl_433/service/status";
    private static final byte[] ONLINE_PAYLOAD = "online".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OFFLINE_PAYLOAD = "offline".getBytes(StandardCharsets.UTF_8);
    private static final long DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 60;

    private final MqttClient client;
    private final MqttConnectOptions connectOptions;
    private final ScheduledExecutorService heartbeatExecutor;
    private final long heartbeatIntervalSeconds;
    private volatile boolean connected;
    private volatile boolean announced;
    private final Object reconnectMonitor = new Object();
    private volatile boolean reconnecting;
    private volatile ScheduledFuture<?> heartbeatTask;

    public ServiceStatusPublisher(Config.Mqtt config) throws MqttException {
        Objects.requireNonNull(config, "MQTT config is required");
        String brokerUrl = "tcp://" + config.getHost() + ":" + config.getPort();
        String clientId = "ha-auto-rtl433-status-" + UUID.randomUUID();
        this.client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        this.connectOptions = buildOptions(config);
        this.heartbeatIntervalSeconds = resolveHeartbeatInterval(config.getAvailabilityHeartbeatSeconds());
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "mqtt-status-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                if (reconnect) {
                    LOGGER.info("Service status publisher reconnected to MQTT broker");
                    announced = false;
                    publishOnline();
                } else {
                    LOGGER.info("Service status publisher connected to MQTT broker");
                }
                connected = true;
                synchronized (reconnectMonitor) {
                    reconnecting = false;
                }
                startHeartbeat();
            }

            @Override
            public void connectionLost(Throwable cause) {
                LOGGER.warn("Service status publisher connection lost", cause);
                stopHeartbeat();
                connected = false;
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
    }

    public synchronized void connect() throws MqttException {
        if (connected) {
            return;
        }
        client.connect(connectOptions);
        connected = client.isConnected();
        if (connected) {
            LOGGER.info("Service status publisher connected to MQTT broker");
            startHeartbeat();
        }
    }

    public void publishOnline() {
        publishOnline(false);
    }

    private synchronized void publishOnline(boolean force) {
        if (!connected) {
            if (force) {
                announced = false;
                return;
            }
            throw new IllegalStateException("Status publisher is not connected");
        }
        if (announced && !force) {
            return;
        }
        try {
            MqttMessage message = new MqttMessage(ONLINE_PAYLOAD);
            message.setRetained(true);
            message.setQos(1);
            client.publish(STATUS_TOPIC, message);
            announced = true;
            LOGGER.info("Published service availability status 'online'");
        } catch (MqttException e) {
            throw new RuntimeException("Failed to publish service status", e);
        }
    }

    private void publishOffline() {
        if (!connected) {
            return;
        }
        try {
            MqttMessage message = new MqttMessage(OFFLINE_PAYLOAD);
            message.setRetained(true);
            message.setQos(1);
            client.publish(STATUS_TOPIC, message);
            LOGGER.info("Published service availability status 'offline'");
        } catch (MqttException e) {
            LOGGER.warn("Failed to publish offline status", e);
        }
    }

    @Override
    public synchronized void close() {
        stopHeartbeat();
        publishOffline();
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (MqttException e) {
            LOGGER.warn("Failed to disconnect status publisher cleanly", e);
        } finally {
            try {
                client.close();
            } catch (MqttException e) {
                LOGGER.warn("Failed to close status publisher client", e);
            }
            connected = false;
            heartbeatExecutor.shutdownNow();
        }
    }

    private MqttConnectOptions buildOptions(Config.Mqtt config) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(false);
        options.setCleanSession(true);
        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            options.setUserName(config.getUsername());
        }
        if (config.getPassword() != null && !config.getPassword().isBlank()) {
            options.setPassword(config.getPassword().toCharArray());
        }
        options.setWill(STATUS_TOPIC, OFFLINE_PAYLOAD, 1, true);
        return options;
    }

    private void scheduleReconnect() {
        synchronized (reconnectMonitor) {
            if (reconnecting) {
                return;
            }
            reconnecting = true;
        }
        Thread thread = new Thread(() -> {
            LOGGER.info("Service status reconnect loop started");
            while (!connected) {
                try {
                    LOGGER.info("Attempting service status reconnect...");
                    if (!client.isConnected()) {
                        client.reconnect();
                    }
                    for (int i = 0; i < 10 && !connected; i++) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    if (connected) {
                        LOGGER.info("Service status reconnect successful");
                        synchronized (ServiceStatusPublisher.this) {
                            announced = false;
                            publishOnline();
                        }
                    }
                } catch (MqttException e) {
                    LOGGER.warn("Service status reconnect attempt failed", e);
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
        }, "mqtt-status-reconnect");
        thread.setDaemon(true);
        thread.start();
    }

    private synchronized void startHeartbeat() {
        ScheduledFuture<?> current = heartbeatTask;
        if (current != null && !current.isCancelled() && !current.isDone()) {
            return;
        }
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                publishOnline(true);
            } catch (Exception ex) {
                LOGGER.debug("Service status heartbeat skipped: {}", ex.getMessage());
            }
        }, heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS);
    }

    private synchronized void stopHeartbeat() {
        ScheduledFuture<?> current = heartbeatTask;
        if (current != null) {
            current.cancel(true);
            heartbeatTask = null;
        }
    }

    private long resolveHeartbeatInterval(Integer requestedSeconds) {
        if (requestedSeconds == null) {
            return DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
        }
        if (requestedSeconds < 5) {
            LOGGER.warn("Configured heartbeat interval {}s is too low. Falling back to {}s", requestedSeconds,
                    DEFAULT_HEARTBEAT_INTERVAL_SECONDS);
            return DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
        }
        return requestedSeconds;
    }
}
