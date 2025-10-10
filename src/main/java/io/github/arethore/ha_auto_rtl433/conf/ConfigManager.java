package io.github.arethore.ha_auto_rtl433.conf;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Optional;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.introspector.PropertyUtils;

/**
 * Singleton responsible for loading {@link Config} from YAML while gracefully
 * adapting snake_case keys to the camelCase Java representation.
 */
public enum ConfigManager {
    INSTANCE;

    private Config config;

    public Config getConfig() {
        return config;
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    public void setConfig(Optional<String> configPath) {
        LoaderOptions options = new LoaderOptions();
        Constructor constructor = new Constructor(Config.class, options);
        RelaxedPropertyUtils propertyUtils = new RelaxedPropertyUtils();
        propertyUtils.setSkipMissingProperties(false);
        constructor.setPropertyUtils(propertyUtils);

        TypeDescription rtl433Desc = new TypeDescription(Config.Rtl433.class);
        rtl433Desc.putListPropertyType("whitelist", Config.Rtl433.WhitelistEntry.class);
        constructor.addTypeDescription(rtl433Desc);

        TypeDescription whitelistDesc = new TypeDescription(Config.Rtl433.WhitelistEntry.class);
        whitelistDesc.putListPropertyType("entities", Config.Rtl433.WhitelistEntry.Entity.class);
        constructor.addTypeDescription(whitelistDesc);

        if (configPath.isEmpty() || configPath.get().isBlank()) {
            throw new IllegalArgumentException("Configuration file path is required. Pass the YAML path as argument 0.");
        }

        Yaml yaml = new Yaml(constructor);
        try (InputStream input = new FileInputStream(configPath.get())) {
            this.config = yaml.loadAs(input, Config.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config", e);
        }
    }

    private static final class RelaxedPropertyUtils extends PropertyUtils {
        @Override
        public Property getProperty(Class<?> type, String name) throws YAMLException {
            try {
                return super.getProperty(type, name);
            } catch (YAMLException original) {
                String remapped = remapName(type, name);
                if (!name.equals(remapped)) {
                    return super.getProperty(type, remapped);
                }
                throw original;
            }
        }

        private String remapName(Class<?> type, String originalName) {
            if (Config.Rtl433.WhitelistEntry.Entity.class.isAssignableFrom(type) && "class".equals(originalName)) {
                return "entityClass";
            }
            return toCamelCase(originalName);
        }

        private String toCamelCase(String value) {
            if (value == null || value.isBlank() || !value.contains("_")) {
                return value;
            }
            StringBuilder builder = new StringBuilder();
            boolean upperNext = false;
            for (char c : value.toCharArray()) {
                if (c == '_') {
                    upperNext = true;
                } else if (upperNext) {
                    builder.append(Character.toUpperCase(c));
                    upperNext = false;
                } else {
                    builder.append(c);
                }
            }
            return builder.toString();
        }
    }
}
