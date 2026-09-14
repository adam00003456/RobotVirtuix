package org.omnitrack.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.omnitrack.core.OmniCodec;
import org.omnitrack.core.OmniTrackAdapter;
import org.omnitrack.core.OmniTrackClient;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that streams Omni One tracking data as JSON over UDP.
 *
 * Data frames go to the configured host:port (default broadcast 45454); command
 * frames come in on cmdPort (default 45455). Frame formats: docs/PROTOCOL.md.
 */
public class BridgeService extends Service implements CommandReceiver.Handler {

    private static final String TAG = "OmniTrackBridge";
    static final String CHANNEL_ID = "omnitrack_bridge";
    static final int NOTIFICATION_ID = 9042;
    static final String PREFS = "omnitrack_bridge";

    static final String KEY_HOST = "host";
    static final String KEY_DATA_PORT = "data_port";
    static final String KEY_CMD_PORT = "cmd_port";
    static final String KEY_POSE_MODE = "pose_mode";       // "zero" | "imu"
    static final String KEY_POSE_RATE_HZ = "pose_rate_hz";
    static final String KEY_MOVEMENT_MAX_HZ = "movement_max_hz";
    static final String KEY_STATUS_JSON = "status_json";

    static final String DEFAULT_HOST = "255.255.255.255";
    static final int DEFAULT_DATA_PORT = 45454;
    static final int DEFAULT_CMD_PORT = 45455;
    static final String DEFAULT_POSE_MODE = "imu";
    static final int DEFAULT_POSE_RATE_HZ = 50;
    static final int DEFAULT_MOVEMENT_MAX_HZ = 60;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final StatusSnapshot status = new StatusSnapshot();

    private OmniTrackClient client;
    private PoseSource poseSource;
    private UdpSender udp;
    private CommandReceiver commands;
    private Thread poseThread;
    private Handler heartbeat;
    private PowerManager.WakeLock wakeLock;
    private DatagramSocket replySocket;

    private int movementMaxHz = DEFAULT_MOVEMENT_MAX_HZ;
    private volatile long lastMovementSentAt = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running.compareAndSet(false, true)) {
            startBridge();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopBridge();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- startup

    private void startBridge() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String host = prefs.getString(KEY_HOST, DEFAULT_HOST);
        int dataPort = prefs.getInt(KEY_DATA_PORT, DEFAULT_DATA_PORT);
        int cmdPort = prefs.getInt(KEY_CMD_PORT, DEFAULT_CMD_PORT);
        String poseMode = prefs.getString(KEY_POSE_MODE, DEFAULT_POSE_MODE);
        int poseRateHz = prefs.getInt(KEY_POSE_RATE_HZ, DEFAULT_POSE_RATE_HZ);
        movementMaxHz = prefs.getInt(KEY_MOVEMENT_MAX_HZ, DEFAULT_MOVEMENT_MAX_HZ);

        Log.i(TAG, "bridge starting: " + host + ":" + dataPort + " cmd:" + cmdPort
              + " pose=" + poseMode + "@" + poseRateHz + "Hz");

        udp = new UdpSender(host, dataPort);
        try {
            udp.open();
        } catch (Exception e) {
            Log.w(TAG, "udp open failed: " + e);
        }

        poseSource = "imu".equals(poseMode) ? new ImuPoseSource(this) : new ZeroPoseSource();
        poseSource.start();

        client = OmniTrackClient.create(this);
        client.setListener(new PublishingListener());
        client.connect();

        commands = new CommandReceiver(cmdPort, this);
        commands.start();

        poseThread = new Thread(new PoseLoop(poseRateHz), "OmniTrackPose");
        poseThread.setDaemon(true);
        poseThread.start();

        heartbeat = new Handler(Looper.getMainLooper());
        heartbeat.post(heartbeatRunnable);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "omnitrack:bridge");
            wakeLock.acquire();
        }

        try {
            replySocket = new DatagramSocket();
        } catch (Exception e) {
            Log.w(TAG, "reply socket failed: " + e);
        }
    }

    private void stopBridge() {
        running.set(false);
        if (heartbeat != null) heartbeat.removeCallbacks(heartbeatRunnable);
        if (poseThread != null) poseThread.interrupt();
        if (poseSource != null) poseSource.stop();
        if (commands != null) commands.stop();
        if (client != null) client.disconnect();
        if (udp != null) udp.close();
        if (replySocket != null) replySocket.close();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ------------------------------------------------------------- CommandReceiver.Handler

    @Override
    public OmniTrackClient client() {
        return client;
    }

    @Override
    public StatusSnapshot status() {
        return status;
    }

    @Override
    public void publish(String json) {
        if (json != null) udp.send(json);
    }

    @Override
    public void reply(String json, InetAddress address, int port) {
        if (json == null || replySocket == null) return;
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            replySocket.send(new DatagramPacket(bytes, bytes.length, address, port));
        } catch (Exception e) {
            Log.w(TAG, "reply failed: " + e);
        }
    }

    // ---------------------------------------------------------------- listener

    private final class PublishingListener extends OmniTrackAdapter {
        @Override
        public void onClientConnected() {
            Log.i(TAG, "connected to Omni middleware");
            status.setClientConnected(true);
            publish(TrackJson.clientConnected(true));
            persistStatus();
        }

        @Override
        public void onClientDisconnected() {
            Log.w(TAG, "disconnected from Omni middleware; auto-reconnecting");
            status.setClientConnected(false);
            status.setOmniConnected(false);
            publish(TrackJson.clientConnected(false));
            persistStatus();
        }

        @Override
        public void onServiceBindFailed() {
            Log.e(TAG, "bind failed: Omni system app missing/not bindable");
            persistStatus();
        }

        @Override
        public void onMovementData(OmniCodec.Movement m) {
            status.setMovement(m);
            long now = System.currentTimeMillis();
            long minIntervalMs = 1000L / Math.max(1, movementMaxHz);
            if (now - lastMovementSentAt < minIntervalMs) return;
            lastMovementSentAt = now;

            Double headYaw = null;
            if (poseSource instanceof ImuPoseSource) {
                headYaw = (double) poseSource.snapshot().yawDeg;
            }
            publish(TrackJson.movement(m, headYaw));
        }

        @Override
        public void onStepCount(int count) {
            status.setSteps(count);
            publish(TrackJson.stepCount(count));
        }

        @Override
        public void onTreadmillScanResults(List<String> names) {
            publish(TrackJson.scanResults(names));
        }

        @Override
        public void onConnectedTreadmillName(String name) {
            Log.i(TAG, "connected treadmill: " + name);
            status.setTreadmillName(name);
            status.setOmniConnected(true);
            publish(TrackJson.treadmillConnected(name));
            persistStatus();
        }

        @Override
        public void onTreadmillDisconnected() {
            status.setTreadmillName(null);
            status.setOmniConnected(false);
            publish(TrackJson.treadmillDisconnected());
            persistStatus();
        }

        @Override
        public void onOmniConnected() {
            status.setOmniConnected(true);
            publish(TrackJson.omniConnected());
            persistStatus();
        }

        @Override
        public void onOmniDisconnected() {
            status.setOmniConnected(false);
            publish(TrackJson.omniDisconnected());
            persistStatus();
        }

        @Override
        public void onFootTrackerConnected(boolean left) {
            status.setFootTrackerConnected(left, true);
            publish(TrackJson.footTracker(left, true));
            client.requestFootTrackerBattery();
        }

        @Override
        public void onFootTrackerDisconnected(boolean left) {
            status.setFootTrackerConnected(left, false);
            publish(TrackJson.footTracker(left, false));
        }

        @Override
        public void onFootTrackerBattery(OmniCodec.FootTrackerBattery b) {
            status.setFootTrackerBattery(b);
            publish(TrackJson.footTrackerBattery(b));
        }

        @Override
        public void onBatteryStatus(OmniCodec.Battery b) {
            status.setBattery(b);
            publish(TrackJson.battery(b));
        }

        @Override
        public void onTreadmillLocked() {
            status.setTreadmillLocked(true);
            publish(TrackJson.treadmillLock(true));
            persistStatus();
        }

        @Override
        public void onTreadmillUnlocked() {
            status.setTreadmillLocked(false);
            publish(TrackJson.treadmillLock(false));
            persistStatus();
        }

        @Override
        public void onControlButton(OmniCodec.ControlButton b) {
            publish(TrackJson.controlButton(b));
        }

        @Override
        public void onShortButtonState(boolean pressed) {
            publish(TrackJson.shortButton(pressed));
        }

        @Override
        public void onBoundaryChanged(boolean omniMode) {
            status.setCalibrated(false);
            publish(TrackJson.boundary(true, omniMode));
            persistStatus();
        }

        @Override
        public void onBoundaryNotChanged() {
            status.setCalibrated(true);
            publish(TrackJson.boundary(false, false));
            persistStatus();
        }

        @Override
        public void onCalibrationResult() {
            publish(TrackJson.calibrationResult());
        }

        @Override
        public void onSettings(OmniCodec.Settings s) {
            Log.i(TAG, "settings: movement=" + s.movement + " restUrl=" + s.restUrl);
            publish(TrackJson.settings(s));
        }

        @Override
        public void onUnknownException(String message, String stackTrace) {
            Log.e(TAG, "omni unknown exception: " + message + "\n" + stackTrace);
        }

        @Override
        public void onKnownException(int type, String message, String stackTrace) {
            Log.e(TAG, "omni known exception type " + type + ": " + message + "\n" + stackTrace);
        }
    }

    // ---------------------------------------------------------------- loops

    private final class PoseLoop implements Runnable {
        private final int rateHz;

        PoseLoop(int rateHz) {
            this.rateHz = Math.max(1, rateHz);
        }

        @Override
        public void run() {
            long intervalNs = 1_000_000_000L / rateHz;
            long next = System.nanoTime();
            while (running.get()) {
                PoseSource.Pose p = poseSource.snapshot();
                // Omni convention (PROTOCOL.md 5.1): (roll, pitch, yaw); position 0 is fine.
                client.sendPlayerPose(p.rollDeg, p.pitchDeg, p.yawDeg, 0f, 0f, 0f);
                next += intervalNs;
                long sleepMs = (next - System.nanoTime()) / 1_000_000L;
                if (sleepMs <= 0) {
                    next = System.nanoTime(); // fell behind; resync
                } else if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }
    }

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running.get()) return;
            publish(TrackJson.status(status));
            persistStatus();
            heartbeat.postDelayed(this, 1000L);
        }
    };

    // ---------------------------------------------------------------- plumbing

    private void persistStatus() {
        try {
            String json = status.toJson().toString();
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_STATUS_JSON, json)
                .apply();
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "OmniTrack Bridge", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        Notification.Builder b;
        b = new Notification.Builder(this, CHANNEL_ID);
        b.setContentTitle("OmniTrack Bridge");
        b.setContentText("Streaming Omni One tracking over UDP");
        b.setSmallIcon(android.R.drawable.ic_menu_compass);
        return b.build();
    }
}
