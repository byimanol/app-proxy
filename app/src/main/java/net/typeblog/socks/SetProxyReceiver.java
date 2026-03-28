package net.typeblog.socks;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import net.typeblog.socks.util.Profile;
import net.typeblog.socks.util.ProfileManager;

public class SetProxyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String server = intent.getStringExtra("server");
        int port = intent.getIntExtra("port", 1080);
        String username = intent.getStringExtra("username");
        String password = intent.getStringExtra("password");

        ProfileManager manager = new ProfileManager(ctx);
        Profile profile = manager.getDefault();
        if (profile == null) profile = new Profile();

        profile.setServer(server);
        profile.setPort(port);
        profile.setUsername(username);
        profile.setPassword(password);
        manager.save(profile);

        SharedPreferences prefs = ctx.getSharedPreferences("proxy_status", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("connected", true).apply();

        Intent serviceIntent = new Intent(ctx, SocksVpnService.class);
        ctx.startService(serviceIntent);
    }
}