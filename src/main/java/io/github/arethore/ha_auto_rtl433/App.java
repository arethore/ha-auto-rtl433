package io.github.arethore.ha_auto_rtl433;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import io.github.arethore.ha_auto_rtl433.conf.Config;
import io.github.arethore.ha_auto_rtl433.conf.ConfigManager;
import io.github.arethore.ha_auto_rtl433.logging.LoggingConfigurator;
import io.github.arethore.ha_auto_rtl433.mqtt.MqttService;
import io.github.arethore.ha_auto_rtl433.mqtt.ServiceStatusPublisher;
import io.github.arethore.ha_auto_rtl433.sensor.RTL433Data;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.Expression;
import com.googlecode.aviator.runtime.function.AbstractFunction;
import com.googlecode.aviator.runtime.type.AviatorDouble;
import com.googlecode.aviator.runtime.type.AviatorObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the ha-auto-rtl433 application. It loads the YAML
 * configuration, initialises logging, wires MQTT services, and continuously
 * processes the JSON stream emitted by an rtl_433 command to provision and
 * update Home Assistant entities through MQTT discovery.
 */
public class App {
    private static final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final Map<String, Expression> CONVERSION_CACHE = new ConcurrentHashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger(App.class);
    private static final ExecutorService LINE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final LongAdder PROCESSED_LINE_COUNT = new LongAdder();
    private static final LongAdder TOTAL_LAG_MILLIS = new LongAdder();
    private static final AtomicLong MAX_LAG_MILLIS = new AtomicLong();
    private static final AtomicLong LAST_STATS_LOG_TIME = new AtomicLong(System.nanoTime());
    private static final AtomicLong LAST_STATS_LOG_COUNT = new AtomicLong();
    private static final int STATS_LOG_BATCH = 500;
    private static final long STATS_LOG_PERIOD_NANOS = TimeUnit.SECONDS.toNanos(30);

    static {
        AviatorEvaluator.addFunction(new AbstractFunction() {
            @Override
            public String getName() {
                return "round";
            }

            @Override
            public AviatorObject call(Map<String, Object> env, AviatorObject arg1) {
                Object value = arg1.getValue(env);
                double d = toDouble(value);
                return new AviatorDouble(Math.round(d));
            }
        });
    }
    private final MqttService mqttService;

    public App(MqttService mqttService) {
        this.mqttService = mqttService;
    }

    public static void main(String[] args) {
        // Load YAML config file
        if (args.length == 0 || args[0].isBlank()) {
            LOGGER.error("Configuration path missing. Usage: java -jar ha-auto-rtl433.jar <config.yaml>");
            System.exit(1);
        }
        ConfigManager.INSTANCE.setConfig(Optional.of(args[0]));
        Config config = ConfigManager.INSTANCE.getConfig();
        LoggingConfigurator.configure(config.getLogback());
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> LOGGER
                .error("Uncaught exception in thread {}: {}", thread.getName(), throwable.getMessage(), throwable));
        LOGGER.info("Configuration loaded successfully");

        // Execute RTL_433 command
        try (ServiceStatusPublisher statusPublisher = new ServiceStatusPublisher(config.getMqtt());
                MqttService mqttService = new MqttService(config.getMqtt())) {
            statusPublisher.connect();
            statusPublisher.publishOnline();
            mqttService.connect();
            mqttService.loadExistingEntities(Duration.ofSeconds(2));
            if (config.getMqtt().isReconfigExisting()) {
                mqttService.reconfigureExisting(config.getRtl433().getWhitelist());
            }
            new App(mqttService).runCommand(config.getRtl433().getProcess());
        } catch (Exception e) {
            LOGGER.error("Application terminated unexpectedly", e);
        } finally {
            shutdownLineExecutor();
        }
    }

    public void runCommand(String commandLine) throws Exception {
        // Split the command string into individual arguments
        // (simple approach; for complex commands consider using "bash -c")
        List<String> command = List.of(commandLine.split("\\s+"));

        // Create and start the process
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true); // merge stderr and stdout
        Process process = pb.start();

        LOGGER.info("Started rtl_433 command: {}", commandLine);

        // Virtual thread executor - lightweight per-line processing
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                final String jsonLine = line;
                final long enqueuedAt = System.nanoTime();
                // Each line is processed in its own virtual thread
                LINE_EXECUTOR.submit(() -> processJson(jsonLine, enqueuedAt));
            }
        }

        // Wait for the external command to finish (optional)
        int exitCode = process.waitFor();
        LOGGER.info("Command exited with code {}", exitCode);
    }

    private void processJson(String json, long enqueuedAtNanos) {
        recordProcessingLag(enqueuedAtNanos);
        LOGGER.debug("Received payload {}", json);

        try {
            RTL433Data data = mapper.readValue(json, RTL433Data.class);
            String dataModel = data.getModel();
            String dataId = data.getId();
            Optional<Config.Rtl433.WhitelistEntry> whitelistEntry = ConfigManager.INSTANCE.getConfig().getRtl433()
                    .getWhitelist()
                    .stream()
                    .filter(entry -> entry.getModel().equalsIgnoreCase(dataModel)
                            && entry.getId() != null
                            && dataId != null
                            && entry.getId().equalsIgnoreCase(dataId))
                    .findFirst();

            if (whitelistEntry.isPresent()) {
                Config.Rtl433.WhitelistEntry entry = whitelistEntry.get();
                LOGGER.info("✅ Allowed: Model={} | ID={}", dataModel, formatId(dataId));
                entry.getAllEntities().forEach(entity -> {
                    String attribute = entity.getAttribute();
                    Object rawValue = attribute != null ? data.get(attribute) : null;
                    Object value = applyConversion(entry, entity, rawValue);
                    boolean converted = rawValue != null && entity.getConversion() != null
                            && !entity.getConversion().isBlank();
                    String label = entity.getName() != null ? entity.getName() : attribute;

                    if (value == null) {
                        LOGGER.info("   • {} ({}): <absent>", label, attribute);
                    } else if (converted && rawValue != null && !rawValue.equals(value)) {
                        LOGGER.info("   • {} ({}): {} (converted -> {})", label, attribute, rawValue, value);
                    } else {
                        LOGGER.info("   • {} ({}): {}", label, attribute, value);
                    }
                    try {
                        String stateTopic = mqttService.ensureEntityConfig(entry, entity);
                        if (value != null) {
                            mqttService.publishState(stateTopic, value);
                            LOGGER.debug("     ↳ state topic: {} (published {})", stateTopic, value);
                        } else {
                            LOGGER.debug("     ↳ state topic: {} (no value)", stateTopic);
                        }
                    } catch (RuntimeException ex) {
                        LOGGER.warn("Failed to configure entity {}", label, ex);
                    }
                });

            } else {
                LOGGER.debug("🚫 Ignored: Model={} | ID={}", dataModel, formatId(dataId));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to parse payload {}", json, e);
        }
    }

    private static String formatId(String id) {
        return id != null ? id : "n/a";
    }

    private static Object applyConversion(Config.Rtl433.WhitelistEntry entry,
            Config.Rtl433.WhitelistEntry.Entity entity, Object value) {
        if (value == null) {
            return null;
        }

        String expression = entity.getConversion();
        if (expression == null || expression.isBlank()) {
            return value;
        }

        try {
            Expression compiled = CONVERSION_CACHE.computeIfAbsent(expression,
                    expr -> AviatorEvaluator.compile(expr, true));
            Map<String, Object> env = new HashMap<>();
            env.put("value", coerceOperand(value));
            Object result = compiled.execute(env);
            return result;
        } catch (Exception ex) {
            LOGGER.warn("Failed to apply conversion '{}' for {}/{}", expression, entry.getModel(),
                    entity.getAttribute(), ex);
            return value;
        }
    }

    private static Object coerceOperand(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String str) {
            try {
                if (str.contains(".")) {
                    return Double.parseDouble(str);
                }
                return Long.parseLong(str);
            } catch (NumberFormatException ignored) {
                // fallback to original string
            }
        }
        return value;
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String str) {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException ignored) {
            }
        }
        throw new IllegalArgumentException("Cannot convert value '" + value + "' to double");
    }

    private static void recordProcessingLag(long enqueuedAtNanos) {
        long lagMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - enqueuedAtNanos);
        if (lagMillis < 0) {
            lagMillis = 0;
        }
        PROCESSED_LINE_COUNT.increment();
        TOTAL_LAG_MILLIS.add(lagMillis);
        MAX_LAG_MILLIS.accumulateAndGet(lagMillis, Math::max);
        maybeLogProcessingStats();
    }

    private static void maybeLogProcessingStats() {
        long count = PROCESSED_LINE_COUNT.sum();
        long now = System.nanoTime();

        boolean shouldLog = false;

        long lastTime = LAST_STATS_LOG_TIME.get();
        if (now - lastTime >= STATS_LOG_PERIOD_NANOS && LAST_STATS_LOG_TIME.compareAndSet(lastTime, now)) {
            shouldLog = true;
        }

        if (!shouldLog) {
            long lastCount = LAST_STATS_LOG_COUNT.get();
            if (count - lastCount >= STATS_LOG_BATCH && LAST_STATS_LOG_COUNT.compareAndSet(lastCount, count)) {
                shouldLog = true;
                LAST_STATS_LOG_TIME.set(now);
            }
        } else {
            LAST_STATS_LOG_COUNT.set(count);
        }

        if (shouldLog) {
            long totalLag = TOTAL_LAG_MILLIS.sum();
            long avgLag = count > 0 ? totalLag / count : 0;
            long maxLag = MAX_LAG_MILLIS.get();
            LOGGER.info("RTL433 processing stats: processed={} avgLag={}ms maxLag={}ms",
                    count, avgLag, maxLag);
        }
    }

    private static void logProcessingSummary() {
        long count = PROCESSED_LINE_COUNT.sum();
        if (count == 0) {
            LOGGER.info("RTL433 processing stats: no lines processed.");
            return;
        }
        long totalLag = TOTAL_LAG_MILLIS.sum();
        long avgLag = totalLag / count;
        long maxLag = MAX_LAG_MILLIS.get();
        LOGGER.info("Final RTL433 processing stats: processed={} avgLag={}ms maxLag={}ms",
                count, avgLag, maxLag);
    }

    private static void shutdownLineExecutor() {
        LOGGER.info("Shutting down line executor...");
        LINE_EXECUTOR.shutdown();
        try {
            if (!LINE_EXECUTOR.awaitTermination(10, TimeUnit.SECONDS)) {
                LINE_EXECUTOR.shutdownNow();
                if (!LINE_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("Line processing executor did not terminate cleanly");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LINE_EXECUTOR.shutdownNow();
        }
        LOGGER.info("Line executor terminated.");
        logProcessingSummary();
    }
}
