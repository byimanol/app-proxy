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
        String alias = intent.getStringExtra("alias");

        if (host == null || host.isEmpty()) {
            Log.e(TAG, "Host is empty, aborting");
            return;
        }

        // Detener servicios existentes
        Intent stopSocks5 = new Intent(ctx, Socks5VpnService.class);
        stopSocks5.setAction("STOP");
        ctx.startService(stopSocks5);
        ctx.stopService(new Intent(ctx, net.typeblog.socks.SocksVpnService.class));

        // Guardar configuración
        SharedPreferences prefs = ctx.getSharedPreferences("proxy_config", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("user", user == null ? "" : user)
            .putString("pass", pass == null ? "" : pass)
            .putBoolean("connected", false);

        // Solo actualizar alias si viene en el broadcast
        if (alias != null && !alias.isEmpty()) {
            editor.putString("alias", alias);
        }
        // Si no viene alias, se mantiene el anterior (o queda vacío = usará IP)

        editor.apply();

        Log.d(TAG, "Config saved - host: " + host + " alias: " + alias);

        new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

            Intent vpnIntent = VpnService.prepare(ctx);
            if (vpnIntent == null) {
                ctx.startService(new Intent(ctx, Socks5VpnService.class));
            } else {
                Intent activityIntent = new Intent(ctx, MainActivity.class);
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activityIntent.putExtra("request_vpn", true);
                ctx.startActivity(activityIntent);
            }
        }).start();
    }
}