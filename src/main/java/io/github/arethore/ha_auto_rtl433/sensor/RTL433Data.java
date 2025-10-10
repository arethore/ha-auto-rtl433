package io.github.arethore.ha_auto_rtl433.sensor;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;

/**
 * Jackson-mapped representation of a single rtl_433 JSON record. Known fields
 * such as {@code model} and {@code id} are explicitly exposed while every other
 * attribute is captured in a dynamic map for later lookup.
 */
public class RTL433Data {

    @JsonProperty("model")
    private String model;

    private String id;

    // Store all other fields dynamically
    private final Map<String, Object> extraFields = new HashMap<>();

    @JsonAnySetter
    public void addExtraField(String key, Object value) {
        extraFields.put(key, value);
    }

    public String getModel() {
        return model;
    }

    @JsonProperty("id")
    public void setId(Object id) {
        this.id = id != null ? String.valueOf(id) : null;
    }

    public String getId() {
        return id;
    }

    public Object get(String key) {
        return extraFields.get(key);
    }

    public <T> T getAs(String key, Class<T> type) {
        Object value = extraFields.get(key);
        if (value == null)
            return null;
        if (type.isInstance(value))
            return type.cast(value);
        // Try to convert primitive values
        if (value instanceof Number number) {
            if (type == Float.class || type == float.class)
                return type.cast(number.floatValue());
            if (type == Double.class || type == double.class)
                return type.cast(number.doubleValue());
            if (type == Integer.class || type == int.class)
                return type.cast(number.intValue());
        }
        throw new IllegalArgumentException("Cannot convert value '" + value + "' to type " + type);
    }

    public Map<String, Object> getExtraFields() {
        return extraFields;
    }

    @Override
    public String toString() {
        return "SensorData{" +
                "model='" + model + '\'' +
                ", id=" + id +
                ", extraFields=" + extraFields +
                '}';
    }
}
