package com.socks5setter;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.net.*;

public class Socks5VpnService extends VpnService {
    private ParcelFileDescriptor vpnInterface;
    private Thread vpnThread;
    private boolean running = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        final String host = prefs.getString("host", "");
        final int port = prefs.getInt("port", 1080);
        final String user = prefs.getString("user", "");
        final String pass = prefs.getString("pass", "");

        try {
            vpnInterface = new Builder()
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setSession("Socks5VPN")
                .establish();

            running = true;
            prefs.edit().putBoolean("connected", true).apply();

            vpnThread = new Thread(() -> {
                FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
                FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
                byte[] buffer = new byte[32767];

                while (running) {
                    try {
                        int length = in.read(buffer);
                        if (length <= 0) continue;

                        // Conectar al proxy SOCKS5 y reenviar
                        Socket socket = new Socket();
                        protect(socket);
                        socket.connect(new InetSocketAddress(host, port), 5000);

                        OutputStream socksOut = socket.getOutputStream();
                        InputStream socksIn = socket.getInputStream();

                        // Handshake SOCKS5 con autenticación
                        socksOut.write(new byte[]{0x05, 0x02, 0x00, 0x02});
                        socksOut.flush();

                        byte[] response = new byte[2];
                        socksIn.read(response);

                        if (response[1] == 0x02) {
                            // Autenticación usuario/contraseña
                            byte[] uBytes = user.getBytes();
                            byte[] pBytes = pass.getBytes();
                            byte[] authPacket = new byte[3 + uBytes.length + pBytes.length];
                            authPacket[0] = 0x01;
                            authPacket[1] = (byte) uBytes.length;
                            System.arraycopy(uBytes, 0, authPacket, 2, uBytes.length);
                            authPacket[2 + uBytes.length] = (byte) pBytes.length;
                            System.arraycopy(pBytes, 0, authPacket, 3 + uBytes.length, pBytes.length);
                            socksOut.write(authPacket);
                            socksOut.flush();
                            socksIn.read(response);
                        }

                        socket.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
            vpnThread.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopVpn() {
        running = false;
        try {
            if (vpnInterface != null) vpnInterface.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        prefs.edit().putBoolean("connected", false).apply();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}