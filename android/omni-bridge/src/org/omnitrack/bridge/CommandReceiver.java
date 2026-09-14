package org.omnitrack.bridge;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.omnitrack.core.OmniTrackClient;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * UDP command channel. Robot-side clients send JSON commands to this port:
 * <pre>
 *   {"cmd":"scan"}                    start a treadmill scan
 *   {"cmd":"connect","name":"..."}    connect to a scanned treadmill
 *   {"cmd":"disconnect"}              disconnect the treadmill
 *   {"cmd":"request_status"}          reply with one "status" frame
 *   {"cmd":"get_battery"}             re-request battery status
 *   {"cmd":"get_steps"}               re-request step count
 *   {"cmd":"get_foot_tracker_battery"}
 *   {"cmd":"get_omni_status"}
 *   {"cmd":"get_lock_status"}
 *   {"cmd":"calibration_complete"}    tell the server calibration finished
 *   {"cmd":"echo"}                    reply with the same payload (latency test)
 * </pre>
 */
final class CommandReceiver implements Runnable {
    private static final String TAG = "OmniTrackBridge";

    interface Handler {
        OmniTrackClient client();

        StatusSnapshot status();

        /** Send a data frame to the configured UDP target. */
        void publish(String json);

        /** Send a reply directly to the command's origin address. */
        void reply(String json, InetAddress address, int port);
    }

    private final int port;
    private final Handler handler;
    private volatile boolean running = false;
    private DatagramSocket socket;

    CommandReceiver(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
    }

    void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this, "OmniTrackCmd");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) socket.close();
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(port);
            byte[] buf = new byte[2048];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                handle(packet);
            }
        } catch (Exception e) {
            if (running) Log.w(TAG, "command receiver stopped: " + e);
        } finally {
            if (socket != null && !socket.isClosed()) socket.close();
        }
    }

    private void handle(DatagramPacket packet) {
        String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        try {
            JSONObject cmd = new JSONObject(text);
            String name = cmd.optString("cmd", "");
            OmniTrackClient client = handler.client();
            String reply = null;

            if ("echo".equals(name)) {
                reply = cmd.put("type", "echo").put("t_ms", System.currentTimeMillis()).toString();
            } else if ("scan".equals(name)) {
                client.scanForTreadmills();
            } else if ("connect".equals(name)) {
                String treadmillName = cmd.optString("name", "");
                if (treadmillName.isEmpty()) {
                    reply = TrackJson.error("connect requires \"name\"");
                } else {
                    client.connectToTreadmill(treadmillName);
                }
            } else if ("disconnect".equals(name)) {
                client.disconnectTreadmill();
            } else if ("request_status".equals(name)) {
                reply = TrackJson.status(handler.status());
            } else if ("get_battery".equals(name)) {
                client.requestBatteryStatus();
            } else if ("get_steps".equals(name)) {
                client.requestStepCount();
            } else if ("get_foot_tracker_battery".equals(name)) {
                client.requestFootTrackerBattery();
            } else if ("get_omni_status".equals(name)) {
                client.requestOmniStatus();
            } else if ("get_lock_status".equals(name)) {
                client.requestTreadmillLockStatus();
            } else if ("calibration_complete".equals(name)) {
                client.sendForceCalibrationComplete();
            } else {
                reply = TrackJson.error("unknown cmd: " + name);
            }

            if (reply != null) {
                handler.reply(reply, packet.getAddress(), packet.getPort());
            }
        } catch (JSONException e) {
            handler.reply(TrackJson.error("bad json: " + text.trim()), packet.getAddress(), packet.getPort());
        } catch (Exception e) {
            Log.w(TAG, "command failed: " + e);
        }
    }
}
