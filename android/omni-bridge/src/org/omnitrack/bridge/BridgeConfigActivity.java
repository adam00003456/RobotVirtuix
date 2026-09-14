package org.omnitrack.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** Minimal configuration UI (programmatic layout; no resource files needed). */
public class BridgeConfigActivity extends Activity {

    private EditText hostField;
    private EditText dataPortField;
    private EditText cmdPortField;
    private EditText rateField;
    private RadioGroup poseGroup;
    private TextView statusView;
    private Button startStopButton;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        startStatusPolling();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(statusPoll);
        super.onDestroy();
    }

    private LinearLayout buildLayout() {
        SharedPreferences prefs = getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(16));
        root.setBackgroundColor(Color.rgb(16, 20, 26));

        TextView title = new TextView(this);
        title.setText("OmniTrack Bridge");
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Omni One tracking -> JSON over UDP (open-source, no Unity/Unreal)");
        sub.setTextSize(13f);
        sub.setTextColor(Color.LTGRAY);
        root.addView(sub);

        root.addView(label("Device IP (configure your robot to talk to this):"));
        TextView ip = new TextView(this);
        ip.setText(deviceIp());
        ip.setTextSize(16f);
        ip.setTextColor(Color.rgb(120, 220, 120));
        root.addView(ip);

        root.addView(label("Target host (IP or broadcast; 255.255.255.255 = LAN broadcast)"));
        hostField = edit(prefs.getString(BridgeService.KEY_HOST, BridgeService.DEFAULT_HOST));
        root.addView(hostField);

        root.addView(label("Data port (JSON frames out)"));
        dataPortField = edit(String.valueOf(prefs.getInt(BridgeService.KEY_DATA_PORT, BridgeService.DEFAULT_DATA_PORT)));
        dataPortField.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(dataPortField);

        root.addView(label("Command port (JSON commands in)"));
        cmdPortField = edit(String.valueOf(prefs.getInt(BridgeService.KEY_CMD_PORT, BridgeService.DEFAULT_CMD_PORT)));
        cmdPortField.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(cmdPortField);

        root.addView(label("Pose rate (Hz, SET_PLAYER_DATA feed)"));
        rateField = edit(String.valueOf(prefs.getInt(BridgeService.KEY_POSE_RATE_HZ, BridgeService.DEFAULT_POSE_RATE_HZ)));
        rateField.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(rateField);

        root.addView(label("Head pose source:"));
        poseGroup = new RadioGroup(this);
        poseGroup.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton imu = new RadioButton(this);
        imu.setId(1);
        imu.setText("IMU");
        RadioButton zero = new RadioButton(this);
        zero.setId(2);
        zero.setText("Zero");
        poseGroup.addView(imu);
        poseGroup.addView(zero);
        String mode = prefs.getString(BridgeService.KEY_POSE_MODE, BridgeService.DEFAULT_POSE_MODE);
        (BridgeService.DEFAULT_POSE_MODE.equals(mode) ? imu : zero).setChecked(true);
        root.addView(poseGroup);

        startStopButton = new Button(this);
        startStopButton.setText("Start bridge");
        startStopButton.setTextSize(16f);
        startStopButton.setOnClickListener(v -> toggle());
        root.addView(startStopButton);
        LinearLayout.LayoutParams lp =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(16);
        startStopButton.setLayoutParams(lp);

        root.addView(label("Live status:"));
        statusView = new TextView(this);
        statusView.setTextSize(13f);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setTextColor(Color.rgb(130, 220, 255));
        root.addView(statusView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12f);
        t.setTextColor(Color.LTGRAY);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private EditText edit(String initial) {
        EditText e = new EditText(this);
        e.setText(initial);
        e.setTextColor(Color.WHITE);
        e.setSingleLine(true);
        return e;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toggle() {
        SharedPreferences.Editor ed = getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE).edit();
        ed.putString(BridgeService.KEY_HOST, hostField.getText().toString().trim());
        ed.putInt(BridgeService.KEY_DATA_PORT, parseInt(dataPortField, BridgeService.DEFAULT_DATA_PORT));
        ed.putInt(BridgeService.KEY_CMD_PORT, parseInt(cmdPortField, BridgeService.DEFAULT_CMD_PORT));
        ed.putInt(BridgeService.KEY_POSE_RATE_HZ, parseInt(rateField, BridgeService.DEFAULT_POSE_RATE_HZ));
        ed.putString(BridgeService.KEY_POSE_MODE,
            poseGroup.getCheckedRadioButtonId() == 1 ? "imu" : "zero");
        ed.apply();

        startStopButton.setText("Stop bridge");
        requestNotificationPermissionIfNeeded();
        Intent svc = new Intent(this, BridgeService.class);
        startForegroundService(svc);
        Toast.makeText(this, "Bridge started", Toast.LENGTH_SHORT).show();
    }

    private int parseInt(EditText field, int def) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
               != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private final Runnable statusPoll = new Runnable() {
        @Override
        public void run() {
            String json = getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE)
                .getString(BridgeService.KEY_STATUS_JSON, "no status yet - press Start");
            statusView.setText(json == null ? "" : json.replace(",", ",\n"));
            ui.postDelayed(this, 1000L);
        }
    };

    private void startStatusPolling() {
        ui.post(statusPoll);
    }

    private String deviceIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface n = ifaces.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                Enumeration<InetAddress> addrs = n.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress() + "  (" + n.getName() + ")";
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "unknown (connect to WiFi)";
    }
}
