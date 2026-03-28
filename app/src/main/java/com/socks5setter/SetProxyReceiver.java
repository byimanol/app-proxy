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

        Log.d(TAG, "Proxy config - host: " + host + ", port: " + port + ", user: " + user);

        if (host == null || host.isEmpty()) {
            Log.e(TAG, "Host is empty, aborting");
            return;
        }

        // Always stop any existing connection first to ensure clean reconnection
        Log.d(TAG, "Stopping any existing VPN connection");
        Intent stopIntent = new Intent(ctx, Socks5VpnService.class);
        stopIntent.setAction("STOP");
        ctx.startService(stopIntent);
        
        // Force stop the native VPN service as well
        try {
            ctx.stopService(new Intent(ctx, net.typeblog.socks.SocksVpnService.class));
        } catch (Exception e) {
            Log.w(TAG, "Error stopping native VPN service: " + e.getMessage());
        }
        
        // Wait for complete shutdown
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting for VPN to stop");
        }

        // Save new configuration
        SharedPreferences prefs = ctx.getSharedPreferences("proxy_config", Context.MODE_PRIVATE);
        prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("user", user == null ? "" : user)
            .putString("pass", pass == null ? "" : pass)
            .putBoolean("connected", false)
            .apply();

        Log.d(TAG, "Configuration saved to SharedPreferences");

        // Check if VPN permission is already granted
        Intent vpnIntent = VpnService.prepare(ctx);
        if (vpnIntent == null) {
            // Permission already granted, start VPN service directly
            Log.d(TAG, "VPN permission already granted, starting service");
            Intent serviceIntent = new Intent(ctx, Socks5VpnService.class);
            ctx.startService(serviceIntent);
        } else {
            // Need to request VPN permission
            Log.d(TAG, "VPN permission not granted, launching MainActivity");
            Intent activityIntent = new Intent(ctx, MainActivity.class);
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activityIntent.putExtra("request_vpn", true);
            ctx.startActivity(activityIntent);
        }
    }
}