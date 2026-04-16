package com.socks5setter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.util.Log;

/**
 * Maneja dos broadcasts:
 *
 *  1) com.socks5setter.SET_PROXY  — configura proxy y controla conexión
 *     Extras:
 *       --es host    <ip>
 *       --ei port    <puerto>
 *       --es user    <usuario>   (opcional)
 *       --es pass    <password>  (opcional)
 *       --es alias   <nombre>    (opcional)
 *       --es connect "true"|"false"   (opcional, default true)
 *       --es bypass  "true"|"false"   (opcional, default false)
 *
 *  Ejemplos ADB:
 *
 *  # Conectar con proxy
 *  adb shell am broadcast -a com.socks5setter.SET_PROXY \
 *      -n com.socks5setter/.SetProxyReceiver \
 *      --es host 1.2.3.4 --ei port 1080 \
 *      --es connect true --es bypass false
 *
 *  # Activar bypass (tráfico por red real, sin proxy)
 *  adb shell am broadcast -a com.socks5setter.SET_PROXY \
 *      -n com.socks5setter/.SetProxyReceiver \
 *      --es bypass true
 *
 *  # Desactivar bypass (vuelve al proxy)
 *  adb shell am broadcast -a com.socks5setter.SET_PROXY \
 *      -n com.socks5setter/.SetProxyReceiver \
 *      --es bypass false
 *
 *  # Desconectar todo
 *  adb shell am broadcast -a com.socks5setter.SET_PROXY \
 *      -n com.socks5setter/.SetProxyReceiver \
 *      --es connect false
 */
public class SetProxyReceiver extends BroadcastReceiver {
    private static final String TAG = "SetProxyReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Log.d(TAG, "Broadcast recibido: " + intent.getAction());

        SharedPreferences prefs = ctx.getSharedPreferences("proxy_config", Context.MODE_PRIVATE);

        // --- Leer extras ---
        String  host    = intent.getStringExtra("host");
        int     port    = intent.getIntExtra("port", prefs.getInt("port", 1080));
        String  user    = intent.getStringExtra("user");
        String  pass    = intent.getStringExtra("pass");
        String  alias   = intent.getStringExtra("alias");
        String  connectStr = intent.getStringExtra("connect");
        String  bypassStr  = intent.getStringExtra("bypass");

        // Si no viene "connect" ni "bypass" se mantiene el estado actual
        boolean connectProvided = connectStr != null;
        boolean bypassProvided  = bypassStr  != null;

        boolean connect = connectProvided
                ? connectStr.equalsIgnoreCase("true")
                : true; // si no se pasa, asumir que se quiere conectar

        boolean bypass = bypassProvided
                ? bypassStr.equalsIgnoreCase("true")
                : prefs.getBoolean("bypass_mode", false); // mantener estado actual

        // --- Actualizar configuración solo si vienen los datos ---
        SharedPreferences.Editor editor = prefs.edit();

        if (host != null && !host.isEmpty()) {
            editor.putString("host", host);
            editor.putInt("port", port);
            editor.putString("user", user == null ? "" : user);
            editor.putString("pass", pass == null ? "" : pass);
        }
        if (alias != null && !alias.isEmpty()) {
            editor.putString("alias", alias);
        }
        editor.putBoolean("bypass_mode", bypass);
        editor.apply();

        Log.d(TAG, "connect=" + connect + " bypass=" + bypass + " host=" + host);

        // --- Aplicar estado ---
        if (!connect) {
            // Desconectar todo
            Intent stopIntent = new Intent(ctx, Socks5VpnService.class);
            stopIntent.setAction(Socks5VpnService.ACTION_STOP);
            ctx.startService(stopIntent);
            Log.d(TAG, "VPN detenida por connect=false");
            return;
        }

        // connect=true → asegurarse de que el servicio esté corriendo
        // y aplicar el modo bypass/proxy según corresponda
        new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            Intent vpnCheck = VpnService.prepare(ctx);
            if (vpnCheck != null) {
                // Necesita permiso del usuario — abrir MainActivity
                Intent activityIntent = new Intent(ctx, MainActivity.class);
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activityIntent.putExtra("request_vpn", true);
                ctx.startActivity(activityIntent);
                return;
            }

            if (bypassProvided && !host_changed(prefs, host)) {
                // Solo cambió el modo bypass, no hay nueva config de proxy
                // Enviar acción directamente al servicio en curso
                Intent bypassIntent = new Intent(ctx, Socks5VpnService.class);
                bypassIntent.setAction(Socks5VpnService.ACTION_SET_BYPASS);
                bypassIntent.putExtra(Socks5VpnService.EXTRA_BYPASS, bypass);
                ctx.startService(bypassIntent);
            } else {
                // Nueva config de proxy o primer arranque
                ctx.startService(new Intent(ctx, Socks5VpnService.class));
            }
        }).start();
    }

    /** Devuelve true si el host recibido es distinto al guardado */
    private boolean host_changed(SharedPreferences prefs, String newHost) {
        if (newHost == null) return false;
        String current = prefs.getString("host", "");
        return !newHost.equals(current);
    }
}
