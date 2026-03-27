package com.socks5setter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class SetProxyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String host = intent.getStringExtra("host");
        int port = intent.getIntExtra("port", 1080);
        String user = intent.getStringExtra("user");
        String pass = intent.getStringExtra("pass");

        Log.d("Socks5Setter", "host=" + host + " port=" + port);

        SharedPreferences prefs = ctx.getSharedPreferences("proxy_config", Context.MODE_PRIVATE);
        prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("user", user)
            .putString("pass", pass)
            .apply();
    }
}