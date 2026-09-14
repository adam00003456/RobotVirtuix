package org.omnitrack.bridge;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/** Sends JSON frames as UDP datagrams to a configured host:port. */
final class UdpSender {
    private static final String TAG = "OmniTrackBridge";

    private final String host;
    private final int port;
    private DatagramSocket socket;

    UdpSender(String host, int port) {
        this.host = host;
        this.port = port;
    }

    synchronized void open() throws SocketException, UnknownHostException {
        if (socket != null && !socket.isClosed()) return;
        socket = new DatagramSocket();
        if (host.endsWith(".255") || host.equals("255.255.255.255")) {
            socket.setBroadcast(true);
        }
    }

    synchronized boolean isOpen() {
        return socket != null && !socket.isClosed();
    }

    synchronized void send(String json) {
        if (!isOpen()) return;
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            InetAddress target = InetAddress.getByName(host);
            socket.send(new DatagramPacket(bytes, bytes.length, target, port));
        } catch (Exception e) {
            Log.w(TAG, "udp send failed: " + e);
        }
    }

    synchronized void close() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        socket = null;
    }
}
