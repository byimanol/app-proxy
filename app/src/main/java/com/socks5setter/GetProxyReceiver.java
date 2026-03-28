package com.socks5setter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class GetProxyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        SharedPreferences prefs = ctx.getSharedPreferences("proxy_config", Context.MODE_MULTI_PROCESS);
        String host = prefs.getString("host", "No configurado");
        int port = prefs.getInt("port", 0);
        String user = prefs.getString("user", "");
        boolean connected = prefs.getBoolean("connected", false);

        String result = "host=" + host + " port=" + port + " user=" + user + " connected=" + connected;

        setResultCode(1);
        setResultData(result);
    }
}