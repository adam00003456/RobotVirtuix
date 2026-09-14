package org.omnitrack.core;

import java.util.List;

/**
 * Read view over a protocol message payload (an Android {@code Bundle} on
 * device, a plain map in JVM tests). Type-defaults match the reference client.
 */
public interface Payload {
    float getFloat(String key, float def);
    int getInt(String key, int def);
    boolean getBoolean(String key, boolean def);
    String getString(String key, String def);
    List<String> getStringArrayList(String key);
    boolean containsKey(String key);
}
