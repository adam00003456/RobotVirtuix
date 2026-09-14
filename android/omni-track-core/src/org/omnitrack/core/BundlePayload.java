package org.omnitrack.core;

import android.os.Bundle;

import java.util.List;

/** {@link Payload} over an Android {@link Bundle}. */
public final class BundlePayload implements Payload {
    private final Bundle bundle;

    public BundlePayload(Bundle bundle) {
        this.bundle = bundle != null ? bundle : new Bundle();
    }

    public static BundlePayload of(Bundle bundle) {
        return new BundlePayload(bundle);
    }

    public static BundlePayload empty() {
        return new BundlePayload(new Bundle());
    }

    @Override
    public float getFloat(String key, float def) {
        return bundle.getFloat(key, def);
    }

    @Override
    public int getInt(String key, int def) {
        return bundle.getInt(key, def);
    }

    @Override
    public boolean getBoolean(String key, boolean def) {
        return bundle.getBoolean(key, def);
    }

    @Override
    public String getString(String key, String def) {
        return bundle.getString(key, def);
    }

    @Override
    public List<String> getStringArrayList(String key) {
        return bundle.getStringArrayList(key);
    }

    @Override
    public boolean containsKey(String key) {
        return bundle.containsKey(key);
    }

    public Bundle bundle() {
        return bundle;
    }
}
