package com.socks5setter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.util.Log;

public class SetProxyReceiver extends BroadcastReceiver {
    private static final String TAG = "SetProxyReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Log.d(TAG, "Broadcast received: " + intent.getAction());

        String host = intent.getStringExtra("host");
        int port = intent.getIntExtra("port", 1080);
        String user = intent.getStringExtra("user");
        String pass = intent.getStringExtra("pass");

        if (host == null || host.isEmpty()) {
            Log.e(TAG, "Host is empty, aborting");
            return;
        }

        Log.d(TAG, "New config - host: " + host + ", port: " + port);

        // 1. Detener completamente ambos servicios
        Intent stopSocks5 = new Intent(ctx, Socks5VpnService.class);
        stopSocks5.setAction("STOP");
        ctx.startService(stopSocks5);

        ctx.stopService(new Intent(ctx, net.typeblog.socks.SocksVpnService.class));

        // 2. Guardar nueva configuración
        SharedPreferences prefs = ctx.getSharedPreferences("proxy_config", Context.MODE_PRIVATE);
        prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("user", user == null ? "" : user)
            .putString("pass", pass == null ? "" : pass)
            .putBoolean("connected", false)
            .apply();

        Log.d(TAG, "New config saved");

        // 3. Esperar que se detenga y reiniciar con nueva config
        new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted");
            }

            Intent vpnIntent = VpnService.prepare(ctx);
            if (vpnIntent == null) {
                Log.d(TAG, "Starting VPN with new config");
                Intent serviceIntent = new Intent(ctx, Socks5VpnService.class);
                ctx.startService(serviceIntent);
            } else {
                Log.d(TAG, "VPN permission needed, launching MainActivity");
                Intent activityIntent = new Intent(ctx, MainActivity.class);
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activityIntent.putExtra("request_vpn", true);
                ctx.startActivity(activityIntent);
            }
        }).start();
    }
}