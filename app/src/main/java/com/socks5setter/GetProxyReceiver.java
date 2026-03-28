package com.socks5setter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.File;

public class GetProxyReceiver extends BroadcastReceiver {
    private static final String TAG = "GetProxyReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        SharedPreferences prefs = ctx.getSharedPreferences("proxy_config", Context.MODE_MULTI_PROCESS);
        String host = prefs.getString("host", "No configurado");
        int port = prefs.getInt("port", 0);
        String user = prefs.getString("user", "");
        boolean connected = prefs.getBoolean("connected", false);

        Log.d(TAG, "=== PROXY STATUS ===");
        Log.d(TAG, "host=" + host + " port=" + port + " user=" + user + " connected=" + connected);
        Log.d(TAG, "===================");
    }
}