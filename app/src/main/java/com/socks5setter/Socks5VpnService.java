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
        String host = prefs.getString("host", "");
        int port = prefs.getInt("port", 1080);

        try {
            vpnInterface = new Builder()
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setSession("Socks5VPN")
                .establish();

            running = true;
            prefs.edit().putBoolean("connected", true).apply();

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