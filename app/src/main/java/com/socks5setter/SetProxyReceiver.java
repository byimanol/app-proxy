package com.socks5setter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.util.Log;

/**
 * Broadcast: com.socks5setter.SET_PROXY
 *
 * Extras:
 *   --es host    <ip>
 *   --ei port    <puerto>
 *   --es user    <usuario>   (opcional)
 *   --es pass    <password>  (opcional)
 *   --es alias   <nombre>    (opcional)
 *   --es connect "true"|"false"
 *
 * connect=true  → tráfico pasa por el proxy SOCKS5   → "● Conectado"  (verde)
 * connect=false → bypass: tráfico por red real        → "○ Desconectado" (rojo)
 *
 * El túnel VPN SIEMPRE está activo. "Desconectado" solo significa
 * que el tráfico NO está pasando por el proxy en ese momento.
 */
public class SetProxyReceiver extends BroadcastReceiver {
    private static final String TAG = "SetProxyReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Log.d(TAG, "Broadcast recibido");

        SharedPreferences prefs = ctx.getSharedPreferences("proxy_config", Context.MODE_PRIVATE);

        String host       = intent.getStringExtra("host");
        int    port       = intent.getIntExtra("port", prefs.getInt("port", 1080));
        String user       = intent.getStringExtra("user");
        String pass       = intent.getStringExtra("pass");
        String alias      = intent.getStringExtra("alias");
        String connectStr = intent.getStringExtra("connect");

        // Si no viene "connect", asumir true (conectar)
        boolean connect = connectStr == null || connectStr.equalsIgnoreCase("true");

        // Guardar config si vienen datos nuevos
        SharedPreferences.Editor editor = prefs.edit();
        if (host != null && !host.isEmpty()) {
            editor.putString("host", host)
                  .putInt("port", port)
                  .putString("user", user == null ? "" : user)
                  .putString("pass", pass == null ? "" : pass);
        }
        if (alias != null && !alias.isEmpty()) {
            editor.putString("alias", alias);
        }
        // "connected" = si el tráfico va por proxy (no si el túnel existe)
        editor.putBoolean("connected", connect);
        editor.putBoolean("bypass_mode", !connect); // bypass = inverso de connect
        editor.apply();

        Log.d(TAG, "connect=" + connect);

        new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            // Comprobar permiso VPN
            Intent vpnCheck = VpnService.prepare(ctx);
            if (vpnCheck != null) {
                Intent activityIntent = new Intent(ctx, MainActivity.class);
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activityIntent.putExtra("request_vpn", true);
                ctx.startActivity(activityIntent);
                return;
            }

            // Enviar acción al servicio
            Intent svcIntent = new Intent(ctx, Socks5VpnService.class);
            svcIntent.setAction(Socks5VpnService.ACTION_SET_BYPASS);
            svcIntent.putExtra(Socks5VpnService.EXTRA_BYPASS, !connect);
            ctx.startService(svcIntent);

        }).start();
    }
}
