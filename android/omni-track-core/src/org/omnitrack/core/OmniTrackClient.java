package org.omnitrack.core;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;

/**
 * Engine-agnostic client for Omni One treadmill tracking.
 *
 * <p>Replaces the proprietary {@code com.virtuix.omni_one_unity} middleware AAR:
 * it binds straight to the Omni system service
 * {@code com.virtuix.android_middleware/.MainService} and speaks the tracking
 * protocol documented in {@code docs/PROTOCOL.md}. Contains no Virtuix code.
 *
 * <p>Typical use:
 * <pre>{@code
 * OmniTrackClient client = OmniTrackClient.create(context);
 * client.setListener(new OmniTrackAdapter() {
 *     public void onMovementData(OmniCodec.Movement m) { ... } // ~tens of Hz
 * });
 * client.connect();
 * // feed the headset pose at a steady rate (30-50 Hz is plenty for teleop):
 * client.sendPlayerPose(roll, pitch, yaw, 0, 0, 0);
 * ...
 * client.disconnect();
 * }</pre>
 *
 * <p>Callbacks arrive on a dedicated background thread. The client queues
 * outgoing messages while disconnected and flushes them on reconnect, mirroring
 * the reference client; queued pose messages are coalesced to the newest one.
 */
public final class OmniTrackClient {

    public static final String MIDDLEWARE_PACKAGE = "com.virtuix.android_middleware";
    public static final String MIDDLEWARE_SERVICE = "com.virtuix.android_middleware.MainService";
    public static final long DEFAULT_RECONNECT_DELAY_MS = 5000L;

    private final Context appContext;
    private final HandlerThread thread;
    private final Handler handler;
    private final Messenger replyToMessenger;

    private final Object lock = new Object();
    private Messenger serverMessenger;
    private final List<Message> pending = new ArrayList<Message>();
    private boolean bound = false;
    private boolean wantConnected = false;
    private boolean registerAsUnityClient = true;
    private long reconnectDelayMs = DEFAULT_RECONNECT_DELAY_MS;

    private volatile OmniTrackListener listener = new OmniTrackAdapter();

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (lock) {
                serverMessenger = new Messenger(service);
            }
            // Handshake, mirroring the reference client: register, ask for settings,
            // then pull an initial status snapshot.
            send(MessageCodes.REGISTER_UNITY_CLIENT, null);
            send(MessageCodes.CONFIGURATION, null);
            send(MessageCodes.GET_OMNI_ONE_CONNECTED_TREADMILL_NAME, null);
            send(MessageCodes.GET_OMNI_STATUS, null);
            send(MessageCodes.GET_TREADMILL_LOCK_STATUS, null);
            send(MessageCodes.GET_BATTERY_STATUS, null);
            flushPending();
            notifyConnected();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (lock) {
                serverMessenger = null;
            }
            notifyDisconnected();
            scheduleReconnect();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            synchronized (lock) {
                serverMessenger = null;
            }
            notifyDisconnected();
            scheduleReconnect();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            notifyBindFailed();
            scheduleReconnect();
        }
    };

    private OmniTrackClient(Context context) {
        this.appContext = context.getApplicationContext();
        this.thread = new HandlerThread("OmniTrackClient");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
        this.replyToMessenger = new Messenger(new IncomingHandler());
    }

    /** Creates a client. {@code context} can be any context; the app context is kept. */
    public static OmniTrackClient create(Context context) {
        if (context == null) throw new IllegalArgumentException("context required");
        return new OmniTrackClient(context);
    }

    public void setListener(OmniTrackListener l) {
        this.listener = (l != null) ? l : new OmniTrackAdapter();
    }

    /** True = send REGISTER_UNITY_CLIENT (10007, reference behavior). False = REGISTER_CLIENT (1). */
    public void setRegisterAsUnityClient(boolean asUnity) {
        this.registerAsUnityClient = asUnity;
    }

    public void setReconnectDelayMs(long delayMs) {
        this.reconnectDelayMs = delayMs;
    }

    /** True once the service binder is available and registration was sent. */
    public boolean isReady() {
        synchronized (lock) {
            return serverMessenger != null;
        }
    }

    /** Binds to the Omni system service (idempotent). */
    public void connect() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                wantConnected = true;
                bind();
            }
        });
    }

    /** Unregisters, unbinds, and stops the callback thread. Safe to call repeatedly. */
    public void disconnect() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                wantConnected = false;
                handler.removeCallbacks(reconnectRunnable);
                send(MessageCodes.UNREGISTER_UNITY_CLIENT, null);
                synchronized (lock) {
                    pending.clear();
                }
                if (bound) {
                    try {
                        appContext.unbindService(connection);
                    } catch (IllegalArgumentException ignored) {
                    }
                    bound = false;
                }
                synchronized (lock) {
                    serverMessenger = null;
                }
            }
        });
        thread.quitSafely();
    }

    // ---------------------------------------------------------------- requests

    /**
     * SET_PLAYER_DATA (what = 4): feed the headset pose so the tracking solution
     * stays view-relative. Rotation is (roll, pitch, yaw) in degrees; position in
     * meters. Zero position is fine; see PROTOCOL.md 5.1 for the axis convention.
     */
    public void sendPlayerPose(float rotationX, float rotationY, float rotationZ,
                               float positionX, float positionY, float positionZ) {
        Bundle b = new Bundle();
        b.putFloat(Keys.ROTATION_X, rotationX);
        b.putFloat(Keys.ROTATION_Y, rotationY);
        b.putFloat(Keys.ROTATION_Z, rotationZ);
        b.putFloat(Keys.POSITION_X, positionX);
        b.putFloat(Keys.POSITION_Y, positionY);
        b.putFloat(Keys.POSITION_Z, positionZ);
        send(MessageCodes.SET_PLAYER_DATA, b);
    }

    /** REQUEST_STEP_COUNT (10009). */
    public void requestStepCount() {
        send(MessageCodes.REQUEST_STEP_COUNT, null);
    }

    /** OMNI_ONE_TREADMILL_SCAN (160019): start a treadmill scan. */
    public void scanForTreadmills() {
        send(MessageCodes.OMNI_ONE_TREADMILL_SCAN, null);
    }

    /** CONNECT_TO_TREADMILL (160016). */
    public void connectToTreadmill(String name) {
        Bundle b = new Bundle();
        b.putString(Keys.TREADMILL_NAME, name);
        send(MessageCodes.CONNECT_TO_TREADMILL, b);
    }

    /** DISCONNECT_FROM_TREADMILL (160020). */
    public void disconnectTreadmill() {
        send(MessageCodes.DISCONNECT_FROM_TREADMILL, null);
    }

    /** GET_OMNI_ONE_CONNECTED_TREADMILL_NAME (160017). */
    public void requestConnectedTreadmillName() {
        send(MessageCodes.GET_OMNI_ONE_CONNECTED_TREADMILL_NAME, null);
    }

    /** GET_OMNI_STATUS (31000011). */
    public void requestOmniStatus() {
        send(MessageCodes.GET_OMNI_STATUS, null);
    }

    /** GET_BATTERY_STATUS (16008). */
    public void requestBatteryStatus() {
        send(MessageCodes.GET_BATTERY_STATUS, null);
    }

    /** GET_FOOT_TRACKER_BATTERY_STATUS (31000027). */
    public void requestFootTrackerBattery() {
        send(MessageCodes.GET_FOOT_TRACKER_BATTERY_STATUS, null);
    }

    /** GET_TREADMILL_LOCK_STATUS (31000048). */
    public void requestTreadmillLockStatus() {
        send(MessageCodes.GET_TREADMILL_LOCK_STATUS, null);
    }

    /** WAS_SHORT_BUTTON_PRESSED (31000028). */
    public void requestShortButtonState() {
        send(MessageCodes.WAS_SHORT_BUTTON_PRESSED, null);
    }

    /** WAS_BOUNDARY_CHANGED (31000031). */
    public void requestBoundaryChanged() {
        send(MessageCodes.WAS_BOUNDARY_CHANGED, null);
    }

    /** FORCE_CALIBRATION_COMPLETE (100056): tell the server your calibration finished. */
    public void sendForceCalibrationComplete() {
        send(MessageCodes.FORCE_CALIBRATION_COMPLETE, null);
    }

    /** PING (777777777) keepalive. The reference client leaves pings off. */
    public void sendPing() {
        send(MessageCodes.PING, null);
    }

    /** CONFIGURATION (100037): re-request SETTINGS. */
    public void requestSettings() {
        send(MessageCodes.CONFIGURATION, null);
    }

    // ---------------------------------------------------------------- internals

    private void bind() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(MIDDLEWARE_PACKAGE, MIDDLEWARE_SERVICE));
        boolean ok = false;
        try {
            ok = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            ok = false;
        }
        bound = ok;
        if (!ok) {
            notifyBindFailed();
            scheduleReconnect();
        }
    }

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (wantConnected) {
                bound = false;
                bind();
            }
        }
    };

    private void scheduleReconnect() {
        if (wantConnected) {
            handler.removeCallbacks(reconnectRunnable);
            handler.postDelayed(reconnectRunnable, reconnectDelayMs);
        }
    }

    /** Sends a fire-and-forget message (sourceId = NO_CLIENT_SOURCE), queueing if disconnected. */
    private void send(int what, Bundle data) {
        Message msg = Message.obtain();
        msg.what = what;
        if (data != null) {
            msg.setData(data);
        }
        msg.replyTo = replyToMessenger;
        msg.arg1 = SourceId.lowArg(SourceId.NO_CLIENT_SOURCE);
        msg.arg2 = SourceId.highArg(SourceId.NO_CLIENT_SOURCE);

        synchronized (lock) {
            if (serverMessenger == null) {
                if (what == MessageCodes.SET_PLAYER_DATA) {
                    // Coalesce pose frames: only the newest matters once we reconnect.
                    for (int i = pending.size() - 1; i >= 0; i--) {
                        if (pending.get(i).what == MessageCodes.SET_PLAYER_DATA) {
                            pending.remove(i);
                        }
                    }
                }
                pending.add(msg);
                return;
            }
        }
        trySend(msg);
    }

    private void flushPending() {
        List<Message> toSend;
        synchronized (lock) {
            toSend = new ArrayList<Message>(pending);
            pending.clear();
        }
        for (Message m : toSend) {
            trySend(m);
        }
    }

    private void trySend(Message msg) {
        Messenger target;
        synchronized (lock) {
            target = serverMessenger;
        }
        if (target == null) return;
        try {
            target.send(msg);
        } catch (RemoteException e) {
            // Server died; onServiceDisconnected / onBindingDied will fire and rebind.
        }
    }

    private final class IncomingHandler extends Handler {
        IncomingHandler() {
            super(thread.getLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            dispatch(msg.what, BundlePayload.of(msg.peekData()));
        }
    }

    private void dispatch(int what, BundlePayload p) {
        OmniTrackListener l = listener;
        switch (what) {
            case MessageCodes.SET_CONTROLLER_DATA:
                l.onMovementData(OmniCodec.parseMovement(p));
                return;
            case MessageCodes.CURRENT_STEP_COUNT:
                l.onStepCount(OmniCodec.parseStepCount(p));
                return;
            case MessageCodes.OMNI_ONE_SCAN_RESULT:
                l.onTreadmillScanResults(OmniCodec.parseScanResults(p));
                return;
            case MessageCodes.OMNI_ONE_CONNECTED_DEVICE_NAME:
                l.onConnectedTreadmillName(OmniCodec.parseTreadmillName(p));
                return;
            case MessageCodes.DISCONNECTED_FROM_TREADMILL:
                l.onTreadmillDisconnected();
                return;
            case MessageCodes.OMNI_CONNECTED:
                l.onOmniConnected();
                return;
            case MessageCodes.OMNI_DISCONNECTED:
                l.onOmniDisconnected();
                return;
            case MessageCodes.LEFT_FOOT_TRACKER_CONNECTED:
                l.onFootTrackerConnected(true);
                return;
            case MessageCodes.LEFT_FOOT_TRACKER_DISCONNECTED:
                l.onFootTrackerDisconnected(true);
                return;
            case MessageCodes.RIGHT_FOOT_TRACKER_CONNECTED:
                l.onFootTrackerConnected(false);
                return;
            case MessageCodes.RIGHT_FOOT_TRACKER_DISCONNECTED:
                l.onFootTrackerDisconnected(false);
                return;
            case MessageCodes.FOOT_TRACKER_BATTERY_STATUS:
                l.onFootTrackerBattery(OmniCodec.parseFootTrackerBattery(p));
                return;
            case MessageCodes.BATTERY_STATUS:
                l.onBatteryStatus(OmniCodec.parseBattery(p));
                return;
            case MessageCodes.TREADMILL_LOCKED:
                l.onTreadmillLocked();
                return;
            case MessageCodes.TREADMILL_UNLOCKED:
                l.onTreadmillUnlocked();
                return;
            case MessageCodes.CONTROL_BUTTON_PRESSED:
                l.onControlButton(OmniCodec.parseControlButton(p));
                return;
            case MessageCodes.SHORT_BUTTON_PRESSED:
                l.onShortButtonState(true);
                return;
            case MessageCodes.SHORT_BUTTON_NOT_PRESSED:
                l.onShortButtonState(false);
                return;
            case MessageCodes.BOUNDARY_CHANGED_OMNI:
                l.onBoundaryChanged(true);
                return;
            case MessageCodes.BOUNDARY_CHANGED_ROOM:
                l.onBoundaryChanged(false);
                return;
            case MessageCodes.BOUNDARY_NOT_CHANGED:
                l.onBoundaryNotChanged();
                return;
            case MessageCodes.CALIBRATION_RESULT:
                l.onCalibrationResult();
                return;
            case MessageCodes.FORCE_UNITY_CALIBRATION:
                l.onForceCalibrationRequested();
                return;
            case MessageCodes.SETTINGS:
                l.onSettings(OmniCodec.parseSettings(p));
                return;
            case MessageCodes.UNKNOWN_EXCEPTION: {
                String[] e = OmniCodec.parseUnknownException(p);
                l.onUnknownException(e[0], e[1]);
                return;
            }
            case MessageCodes.KNOWN_EXCEPTION: {
                Object[] e = OmniCodec.parseKnownException(p);
                l.onKnownException((Integer) e[0], (String) e[1], (String) e[2]);
                return;
            }
            default:
                l.onUnknownMessage(what, p != null ? p.bundle() : null);
        }
    }

    private void notifyConnected() {
        listener.onClientConnected();
    }

    private void notifyDisconnected() {
        listener.onClientDisconnected();
    }

    private void notifyBindFailed() {
        listener.onServiceBindFailed();
    }
}
