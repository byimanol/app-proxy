package com.socks5setter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;

public class SetProxyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String host = intent.getStringExtra("host");
        int port = intent.getIntExtra("port", 1080);
        String user = intent.getStringExtra("user");
        String pass = intent.getStringExtra("pass");

        SharedPreferences prefs = ctx.getSharedPreferences("proxy_config", Context.MODE_PRIVATE);
        prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("user", user)
            .putString("pass", pass)
            .putBoolean("connected", false)
            .apply();

        Intent vpnIntent = VpnService.prepare(ctx);
        if (vpnIntent == null) {
            Intent serviceIntent = new Intent(ctx, Socks5VpnService.class);
            ctx.startService(serviceIntent);
        } else {
            Intent activityIntent = new Intent(ctx, MainActivity.class);
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activityIntent.putExtra("request_vpn", true);
            ctx.startActivity(activityIntent);
        }
    }
}