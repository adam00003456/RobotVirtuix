package org.omnitrack.core;

/**
 * 64-bit correlation id carried in Message.arg1/arg2 of every protocol message.
 *
 * <pre>
 *   sourceId = ((long) arg2 &lt;&lt; 32) + (arg1 &amp; 0xFFFFFFFFL)
 *   arg1 = (int)(sourceId &amp; 0xFFFFFFFFL)
 *   arg2 = (int)(sourceId &gt;&gt;&gt; 32)
 * </pre>
 *
 * Pure Java; unit-testable on the JVM.
 */
public final class SourceId {
    /** Sentinel used by the reference client for fire-and-forget messages. */
    public static final long NO_CLIENT_SOURCE = Long.MIN_VALUE;

    private SourceId() {}

    public static long fromArgs(int arg1, int arg2) {
        return ((long) arg2 << 32) + (arg1 & 0xFFFFFFFFL);
    }

    public static int lowArg(long sourceId) {
        return (int) (sourceId & 0xFFFFFFFFL);
    }

    public static int highArg(long sourceId) {
        return (int) (sourceId >>> 32);
    }
}
