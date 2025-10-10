package io.github.arethore.ha_auto_rtl433.logging;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;

import org.slf4j.LoggerFactory;

import io.github.arethore.ha_auto_rtl433.conf.Config;

/**
 * Programmatic Logback bootstrapper that applies the user-provided logging
 * settings to the root logger, supporting both console and rolling file
 * appenders without requiring an external XML configuration.
 */
public final class LoggingConfigurator {
    private static final String DEFAULT_PATTERN = "%-5level %logger{36} - %msg%n";

    private LoggingConfigurator() {
    }

    public static void configure(Config.Logback logbackConfig) {
        Config.Logback effectiveConfig = logbackConfig != null ? logbackConfig : new Config.Logback();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();

        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(resolveLevel(effectiveConfig.getLevel()));

        Encoder<ILoggingEvent> encoder = buildEncoder(context, effectiveConfig);
        Appender<ILoggingEvent> appender;

        if (hasText(effectiveConfig.getFile())) {
            appender = buildRollingFileAppender(context, effectiveConfig, encoder);
        } else {
            appender = buildConsoleAppender(context, encoder);
        }

        appender.start();
        rootLogger.addAppender(appender);
    }

    private static Appender<ILoggingEvent> buildConsoleAppender(LoggerContext context,
            Encoder<ILoggingEvent> encoder) {
        ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<>();
        consoleAppender.setName("STDOUT");
        consoleAppender.setContext(context);
        consoleAppender.setEncoder(encoder);
        return consoleAppender;
    }

    private static Appender<ILoggingEvent> buildRollingFileAppender(LoggerContext context, Config.Logback config,
            Encoder<ILoggingEvent> encoder) {
        RollingFileAppender<ILoggingEvent> fileAppender = new RollingFileAppender<>();
        fileAppender.setName("FILE");
        fileAppender.setContext(context);

        String filePath = config.getFile().trim();
        fileAppender.setFile(filePath);

        ensureParentDirectoryExists(filePath);

        fileAppender.setEncoder(encoder);

        if (hasText(config.getMaxFileSize())) {
            SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = new SizeAndTimeBasedRollingPolicy<>();
            policy.setContext(context);
            policy.setParent(fileAppender);
            policy.setFileNamePattern(resolvePattern(config, filePath, true));
            policy.setMaxFileSize(FileSize.valueOf(config.getMaxFileSize().trim()));
            if (config.getMaxHistory() != null) {
                policy.setMaxHistory(config.getMaxHistory());
            }
            fileAppender.setRollingPolicy(policy);
            fileAppender.setTriggeringPolicy(policy);
            policy.start();
        } else {
            TimeBasedRollingPolicy<ILoggingEvent> policy = new TimeBasedRollingPolicy<>();
            policy.setContext(context);
            policy.setParent(fileAppender);
            policy.setFileNamePattern(resolvePattern(config, filePath, false));
            if (config.getMaxHistory() != null) {
                policy.setMaxHistory(config.getMaxHistory());
            }
            fileAppender.setRollingPolicy(policy);
            fileAppender.setTriggeringPolicy(policy);
            policy.start();
        }

        return fileAppender;
    }

    private static Encoder<ILoggingEvent> buildEncoder(LoggerContext context, Config.Logback config) {
        String pattern = buildPattern(config);
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(pattern);
        encoder.setImmediateFlush(true);
        encoder.start();
        return encoder;
    }

    private static String buildPattern(Config.Logback config) {
        if (config == null || !"text".equalsIgnoreCase(config.getFormat())) {
            return DEFAULT_PATTERN;
        }

        StringBuilder pattern = new StringBuilder();
        if (config.isTimestamp()) {
            pattern.append("%d{yyyy-MM-dd HH:mm:ss.SSS} ");
        }
        if (config.isColor()) {
            pattern.append("%highlight(%-5level) ");
        } else {
            pattern.append("%-5level ");
        }
        pattern.append("[%thread] %logger{36} - %msg%n");
        return pattern.toString();
    }

    private static String resolvePattern(Config.Logback config, String filePath, boolean includeIndex) {
        if (config != null && hasText(config.getRollingPattern())) {
            return config.getRollingPattern().trim();
        }
        String basePattern = filePath + ".%d{yyyy-MM-dd}";
        if (includeIndex) {
            basePattern += ".%i";
        }
        return basePattern + ".log";
    }

    private static Level resolveLevel(String level) {
        if (level == null || level.isBlank()) {
            return Level.INFO;
        }
        return Level.toLevel(level.toUpperCase(), Level.INFO);
    }

    private static void ensureParentDirectoryExists(String filePath) {
        Path path = Paths.get(filePath).toAbsolutePath();
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create log directory: " + parent, e);
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
