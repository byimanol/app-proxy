package com.socks5setter;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.IBinder;

public class Socks5VpnService extends VpnService {

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

        Intent intent = new Intent(this, net.typeblog.socks.SocksVpnService.class);
        intent.putExtra("server", host);
        intent.putExtra("port", port);
        intent.putExtra("username", user);
        intent.putExtra("password", pass);
        intent.putExtra("route", "bypass-lan");
        intent.putExtra("dns", "8.8.8.8");
        startService(intent);

        prefs.edit().putBoolean("connected", true).apply();
    }

    private void stopVpn() {
        stopService(new Intent(this, net.typeblog.socks.SocksVpnService.class));
        getSharedPreferences("proxy_config", MODE_PRIVATE)
            .edit().putBoolean("connected", false).apply();
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}