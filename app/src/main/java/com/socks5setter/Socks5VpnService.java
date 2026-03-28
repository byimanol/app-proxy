package com.socks5setter;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import net.typeblog.socks.util.Constants;
import net.typeblog.socks.util.Profile;
import net.typeblog.socks.util.Utility;
import java.io.*;

public class Socks5VpnService extends VpnService {

    private static final String TAG = "Socks5VPN";
    private ParcelFileDescriptor vpnInterface;
    private Thread vpnThread;

    static {
        System.loadLibrary("system");
        System.loadLibrary("tun2socks");
        System.loadLibrary("pdnsd");
    }

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
        String user = prefs.getString("user", "");
        String pass = prefs.getString("pass", "");

        if (host == null || host.isEmpty()) {
            Log.e(TAG, "No hay host configurado");
            stopSelf();
            return;
        }

        try {
            Builder builder = new Builder();
            builder.addAddress("10.0.0.2", 24);
            builder.addRoute("0.0.0.0", 0);
            builder.addDnsServer("8.8.8.8");
            builder.addDnsServer("8.8.4.4");
            builder.setSession("Socks5VPN");
            builder.setMtu(1500);

            vpnInterface = builder.establish();

            if (vpnInterface == null) {
                Log.e(TAG, "VPN interface es null");
                stopSelf();
                return;
            }

            int fd = vpnInterface.getFd();
            Log.d(TAG, "VPN fd: " + fd);

            // Usar Utility de SocksDroid para iniciar tun2socks
            Profile profile = new Profile();
            profile.setServer(host);
            profile.setPort(port);
            profile.setUsername(user);
            profile.setPassword(pass);

            Utility.startTun2Socks(
                this,
                fd,
                1500,
                "10.0.0.2",
                "255.255.255.0",
                "10.0.0.1",
                host + ":" + port,
                user.isEmpty() ? null : user,
                pass.isEmpty() ? null : pass,
                false
            );

            prefs.edit().putBoolean("connected", true).apply();
            Log.d(TAG, "VPN iniciada → " + host + ":" + port);

        } catch (Exception e) {
            Log.e(TAG, "Error iniciando VPN", e);
            stopSelf();
        }
    }

    private void stopVpn() {
        try {
            Utility.stopTun2Socks();
        } catch (Exception ignored) {}

        try {
            if (vpnInterface != null) vpnInterface.close();
        } catch (Exception ignored) {}

        getSharedPreferences("proxy_config", MODE_PRIVATE)
            .edit().putBoolean("connected", false).apply();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
