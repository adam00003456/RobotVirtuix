package org.omnitrack.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JVM-friendly {@link Payload} backed by plain maps. Used by unit tests and by
 * any non-Android consumer of the codec.
 */
public final class MapPayload implements Payload {
    private final Map<String, Object> map = new HashMap<>();

    public MapPayload putFloat(String key, float value) {
        map.put(key, value);
        return this;
    }

    public MapPayload putInt(String key, int value) {
        map.put(key, value);
        return this;
    }

    public MapPayload putBoolean(String key, boolean value) {
        map.put(key, value);
        return this;
    }

    public MapPayload putString(String key, String value) {
        map.put(key, value);
        return this;
    }

    public MapPayload putStringArrayList(String key, List<String> value) {
        map.put(key, new ArrayList<String>(value));
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public float getFloat(String key, float def) {
        Object v = map.get(key);
        return v instanceof Float ? (Float) v : (v instanceof Number ? ((Number) v).floatValue() : def);
    }

    @Override
    @SuppressWarnings("unchecked")
    public int getInt(String key, int def) {
        Object v = map.get(key);
        return v instanceof Integer ? (Integer) v : (v instanceof Number ? ((Number) v).intValue() : def);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean getBoolean(String key, boolean def) {
        Object v = map.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String getString(String key, String def) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : def;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getStringArrayList(String key) {
        Object v = map.get(key);
        return v instanceof List ? (List<String>) v : null;
    }

    @Override
    public boolean containsKey(String key) {
        return map.containsKey(key);
    }

    public Map<String, Object> asMap() {
        return new HashMap<String, Object>(map);
    }
}
