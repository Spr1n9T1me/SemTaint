package com.semtaint.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;
import pascal.taie.util.collection.Maps;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global configuration manager that loads settings from YAML files
 * and provides typed access to configuration values.
 */
public class ConfManager {

    private static final Logger logger = LogManager.getLogger(ConfManager.class);

    /**
     * Singleton instance
     */
    private static ConfManager instance;

    /**
     * Default configuration file path
     */
    private static final String DEFAULT_CONFIG_PATH = "src/main/resources/semtaint-default.yml";

    /**
     * Store all configuration values
     */
    private final Map<String, Object> configMap = new ConcurrentHashMap<>();

    /**
     * Cache for transformed configuration values
     */
    private final Map<String, Object> valueCache = Maps.newConcurrentMap();

    /**
     * Private constructor to prevent instantiation
     */
    private ConfManager() {
        // Empty constructor
    }

    /**
     * Get the singleton instance of GlobalSetting
     *
     * @return the singleton instance
     */
    public static synchronized ConfManager v() {
        if (instance == null) {
            instance = new ConfManager();
            instance.loadDefaultConfig();
        }
        return instance;
    }

    /**
     * Load the default configuration file
     */
    private void loadDefaultConfig() {
        try {
            loadConfig(DEFAULT_CONFIG_PATH);
        } catch (IOException e) {
            logger.warn("Failed to load default configuration from {}", DEFAULT_CONFIG_PATH);
            logger.warn("Using empty configuration");
        }
    }

    /**
     * Load configuration from specified file path
     *
     * @param configPath path to the YAML configuration file
     * @throws IOException if the file cannot be read
     */
    public void loadConfig(String configPath) throws IOException {
        Path path = Paths.get(configPath);
        if (!Files.exists(path)) {
            throw new IOException("Configuration file not found: " + configPath);
        }

        try (InputStream input = new FileInputStream(path.toFile())) {
            Yaml yaml = new Yaml();
            Map<String, Object> loadedConfig = yaml.load(input);
            if (loadedConfig != null) {
                configMap.putAll(loadedConfig);
                valueCache.clear(); // Clear cache when loading new config
                logger.info("Loaded configuration from {}", configPath);
            }
        }
    }

    /**
     * Merge additional configuration from a file
     *
     * @param configPath path to the additional YAML configuration file
     * @throws IOException if the file cannot be read
     */
    public void mergeConfig(String configPath) throws IOException {
        Path path = Paths.get(configPath);
        if (!Files.exists(path)) {
            throw new IOException("Configuration file not found: " + configPath);
        }

        try (InputStream input = new FileInputStream(path.toFile())) {
            Yaml yaml = new Yaml();
            Map<String, Object> additionalConfig = yaml.load(input);
            if (additionalConfig != null) {
                mergeConfigMaps(configMap, additionalConfig);
                valueCache.clear(); // Clear cache after merging
                logger.info("Merged configuration from {}", configPath);
            }
        }
    }

    /**
     * Recursively merge configuration maps
     */
    @SuppressWarnings("unchecked")
    private void mergeConfigMaps(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map && target.containsKey(key) && target.get(key) instanceof Map) {
                // If both values are maps, merge them recursively
                mergeConfigMaps(
                        (Map<String, Object>) target.get(key),
                        (Map<String, Object>) value
                );
            } else {
                // Otherwise, overwrite the value
                target.put(key, value);
            }
        }
    }

    /**
     * Set a configuration value programmatically
     *
     * @param key   configuration key (dot notation for nested keys)
     * @param value configuration value
     */
    public void set(String key, Object value) {
        setNestedValue(configMap, key, value);
        valueCache.remove(key); // Invalidate cache for this key
    }

    /**
     * Set a nested value using dot notation
     */
    @SuppressWarnings("unchecked")
    private void setNestedValue(Map<String, Object> map, String key, Object value) {
        int dotIndex = key.indexOf('.');
        if (dotIndex == -1) {
            // This is the final key
            map.put(key, value);
        } else {
            // Handle nested keys
            String firstKey = key.substring(0, dotIndex);
            String remainingKey = key.substring(dotIndex + 1);

            if (!map.containsKey(firstKey)) {
                map.put(firstKey, new ConcurrentHashMap<String, Object>());
            } else if (!(map.get(firstKey) instanceof Map)) {
                // Current value is not a map, override it
                map.put(firstKey, new ConcurrentHashMap<String, Object>());
            }

            setNestedValue((Map<String, Object>) map.get(firstKey), remainingKey, value);
        }
    }

    /**
     * Get a configuration value as an Object
     *
     * @param key          configuration key (dot notation for nested keys)
     * @param defaultValue default value if key not found
     * @return the configuration value or defaultValue if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        return (T) valueCache.computeIfAbsent(key, k -> {
            Object value = getNestedValue(configMap, k);
            return value != null ? value : defaultValue;
        });
    }

    /**
     * Get a nested value using dot notation
     */
    @SuppressWarnings("unchecked")
    private Object getNestedValue(Map<String, Object> map, String key) {
        int dotIndex = key.indexOf('.');
        if (dotIndex == -1) {
            // This is the final key
            return map.get(key);
        } else {
            // Handle nested keys
            String firstKey = key.substring(0, dotIndex);
            String remainingKey = key.substring(dotIndex + 1);

            Object value = map.get(firstKey);
            if (value instanceof Map) {
                return getNestedValue((Map<String, Object>) value, remainingKey);
            } else {
                return null;
            }
        }
    }

    /**
     * Get a configuration value as a String
     *
     * @param key configuration key
     * @return the String value or null if not found
     */
    public String getString(String key) {
        return getString(key, null);
    }

    /**
     * Get a configuration value as a String
     *
     * @param key          configuration key
     * @param defaultValue default value if key not found
     * @return the String value or defaultValue if not found
     */
    public String getString(String key, String defaultValue) {
        Object value = get(key, defaultValue);
        return value != null ? value.toString() : null;
    }

    /**
     * Get a configuration value as an Integer
     *
     * @param key configuration key
     * @return the Integer value or null if not found or not an Integer
     */
    public Integer getInt(String key) {
        return getInt(key, null);
    }

    /**
     * Get a configuration value as an Integer
     *
     * @param key          configuration key
     * @param defaultValue default value if key not found or not an Integer
     * @return the Integer value or defaultValue if not found or not an Integer
     */
    public Integer getInt(String key, Integer defaultValue) {
        Object value = get(key, defaultValue);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        } else if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                logger.warn("Could not parse '{}' as Integer for key '{}'", value, key);
                return defaultValue;
            }
        } else if (value == null) {
            return defaultValue;
        } else {
            logger.warn("Value for key '{}' is not an Integer: {}", key, value);
            return defaultValue;
        }
    }

    /**
     * Get a configuration value as a Boolean
     *
     * @param key configuration key
     * @return the Boolean value or false if not found or not a Boolean
     */
    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    /**
     * Get a configuration value as a Boolean
     *
     * @param key          configuration key
     * @param defaultValue default value if key not found or not a Boolean
     * @return the Boolean value or defaultValue if not found or not a Boolean
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = get(key, defaultValue);
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        } else if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        } else if (value == null) {
            return defaultValue;
        } else {
            logger.warn("Value for key '{}' could not be converted to Boolean: {}", key, value);
            return defaultValue;
        }
    }

    /**
     * Get a configuration value as a Double
     *
     * @param key configuration key
     * @return the Double value or null if not found or not a Double
     */
    public Double getDouble(String key) {
        return getDouble(key, null);
    }

    /**
     * Get a configuration value as a Double
     *
     * @param key          configuration key
     * @param defaultValue default value if key not found or not a Double
     * @return the Double value or defaultValue if not found or not a Double
     */
    public Double getDouble(String key, Double defaultValue) {
        Object value = get(key, defaultValue);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        } else if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                logger.warn("Could not parse '{}' as Double for key '{}'", value, key);
                return defaultValue;
            }
        } else if (value == null) {
            return defaultValue;
        } else {
            logger.warn("Value for key '{}' is not a Double: {}", key, value);
            return defaultValue;
        }
    }

    /**
     * Get a configuration value as a List
     *
     * @param key configuration key
     * @return the List value or empty list if not found or not a List
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getList(String key) {
        Object value = get(key, Collections.emptyList());
        if (value instanceof List) {
            return (List<T>) value;
        } else if (value == null) {
            return Collections.emptyList();
        } else {
            logger.warn("Value for key '{}' is not a List: {}", key, value);
            return Collections.emptyList();
        }
    }

    /**
     * Get a configuration value as a Map
     *
     * @param key configuration key
     * @return the Map value or empty map if not found or not a Map
     */
    @SuppressWarnings("unchecked")
    public <K, V> Map<K, V> getMap(String key) {
        Object value = get(key, Collections.emptyMap());
        if (value instanceof Map) {
            return (Map<K, V>) value;
        } else if (value == null) {
            return Collections.emptyMap();
        } else {
            logger.warn("Value for key '{}' is not a Map: {}", key, value);
            return Collections.emptyMap();
        }
    }

    /**
     * Check if a configuration key exists
     *
     * @param key configuration key
     * @return true if the key exists, false otherwise
     */
    public boolean hasKey(String key) {
        return getNestedValue(configMap, key) != null;
    }

    /**
     * Get a copy of the entire configuration map
     *
     * @return unmodifiable copy of the configuration map
     */
    public Map<String, Object> getAll() {
        return Collections.unmodifiableMap(configMap);
    }

    /**
     * Clear all configurations
     */
    public void clear() {
        configMap.clear();
        valueCache.clear();
    }

    @Override
    public String toString() {
        return "GlobalSetting{" + configMap + "}";
    }
}
