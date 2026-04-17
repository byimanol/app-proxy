package com.socks5setter;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import net.typeblog.socks.IVpnService;
import net.typeblog.socks.util.Constants;

public class Socks5VpnService extends VpnService {
    private static final String TAG = "Socks5VpnService";

    public static final String ACTION_STOP       = "STOP";
    public static final String ACTION_SET_BYPASS = "SET_BYPASS";
    public static final String EXTRA_BYPASS      = "bypass_mode";

    private IVpnService          vpnServiceInterface;
    private Handler              handler = new Handler(Looper.getMainLooper());
    private Runnable             statusCheckRunnable;
    private ParcelFileDescriptor bypassPfd;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            vpnServiceInterface = IVpnService.Stub.asInterface(service);
            startStatusCheck();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            vpnServiceInterface = null;
            stopStatusCheck();
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // Reinicio del sistema — restaurar estado previo
            restorePreviousState();
            return START_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            // Esto ya no "desconecta" visualmente — solo mata el servicio
            // completamente (usado internamente)
            teardownAll();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_SET_BYPASS.equals(action)) {
            boolean bypass = intent.getBooleanExtra(EXTRA_BYPASS, false);
            applyBypass(bypass);
            return START_STICKY;
        }

        // Arranque normal
        restorePreviousState();
        return START_STICKY;
    }

    /** Al arrancar, leer el estado guardado y aplicarlo */
    private void restorePreviousState() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        boolean bypass = prefs.getBoolean("bypass_mode", false);
        applyBypass(bypass);
    }

    // ------------------------------------------------------------------
    // BYPASS toggle
    // bypass=true  → túnel vacío, tráfico por red real, UI dice "Desconectado"
    // bypass=false → túnel SOCKS5 activo, tráfico por proxy, UI dice "Conectado"
    // ------------------------------------------------------------------
    private void applyBypass(boolean bypass) {
        teardownProxy();
        closeBypassTunnel();

        if (bypass) {
            startBypassTunnel();
        } else {
            startProxyTunnel();
        }
    }

    /** Túnel VPN vacío — sin rutas → tráfico sale por red real */
    private void startBypassTunnel() {
        try {
            Builder b = new Builder();
            b.setMtu(1500)
             .setSession("SOCKS5 VPN")
             .addAddress("26.26.26.1", 24)
             .addDnsServer("8.8.8.8")
             .addRoute("0.0.0.0", 0)        // capturar todo el tráfico
             .allowBypass();                 // pero permitir que salga por red real

            // Excluir TODAS las apps del túnel → tráfico sale por red real
            // Esto es equivalente a "bypass total" sin romper el túnel VPN
            for (android.content.pm.ApplicationInfo app :
                    getPackageManager().getInstalledApplications(0)) {
                try { b.addDisallowedApplication(app.packageName); }
                catch (Exception ignored) {}
            }

            bypassPfd = b.establish();
            Log.d(TAG, bypassPfd != null ? "Bypass tunnel activo" : "Error creando bypass tunnel");
        } catch (Exception e) {
            Log.e(TAG, "startBypassTunnel error: " + e.getMessage());
        }
    }

    private void closeBypassTunnel() {
        if (bypassPfd != null) {
            try { bypassPfd.close(); } catch (Exception ignored) {}
            bypassPfd = null;
        }
    }

    /** Túnel SOCKS5 real vía tun2socks */
    private void startProxyTunnel() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        String host = prefs.getString("host", "");
        int    port = prefs.getInt("port", 1080);
        String user = prefs.getString("user", "");
        String pass = prefs.getString("pass", "");

        if (host == null || host.isEmpty()) {
            // Sin proxy todavía → bypass tunnel para mantener el icono de llave
            Log.w(TAG, "Sin proxy, arrancando bypass tunnel temporal");
            startBypassTunnel();
            return;
        }

        Intent intent = new Intent(this, net.typeblog.socks.SocksVpnService.class);
        intent.putExtra(Constants.INTENT_NAME,       "SOCKS5 Proxy");
        intent.putExtra(Constants.INTENT_SERVER,     host);
        intent.putExtra(Constants.INTENT_PORT,       port);
        intent.putExtra(Constants.INTENT_USERNAME,   user);
        intent.putExtra(Constants.INTENT_PASSWORD,   pass);
        intent.putExtra(Constants.INTENT_ROUTE,      "bypass-lan");
        intent.putExtra(Constants.INTENT_DNS,        "8.8.8.8");
        intent.putExtra(Constants.INTENT_DNS_PORT,   53);
        intent.putExtra(Constants.INTENT_IPV6_PROXY, false);

        startService(intent);
        bindService(
            new Intent(this, net.typeblog.socks.SocksVpnService.class),
            serviceConnection, BIND_AUTO_CREATE
        );
        Log.d(TAG, "Proxy tunnel arrancado: " + host + ":" + port);
    }

    private void teardownProxy() {
        stopStatusCheck();
        try { unbindService(serviceConnection); } catch (Exception ignored) {}
        vpnServiceInterface = null;
        stopService(new Intent(this, net.typeblog.socks.SocksVpnService.class));
    }

    private void teardownAll() {
        teardownProxy();
        closeBypassTunnel();
        getSharedPreferences("proxy_config", MODE_PRIVATE)
            .edit()
            .putBoolean("connected",   false)
            .putBoolean("bypass_mode", false)
            .apply();
    }

    // ------------------------------------------------------------------
    private void startStatusCheck() {
        stopStatusCheck();
        statusCheckRunnable = new Runnable() {
            @Override public void run() {
                checkStatus();
                handler.postDelayed(this, 5000);
            }
        };
        handler.post(statusCheckRunnable);
    }

    private void stopStatusCheck() {
        if (statusCheckRunnable != null) {
            handler.removeCallbacks(statusCheckRunnable);
            statusCheckRunnable = null;
        }
    }

    private void checkStatus() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        if (prefs.getBoolean("bypass_mode", false)) return; // bypass no necesita check

        if (vpnServiceInterface == null) return;
        try {
            boolean running      = vpnServiceInterface.isRunning();
            boolean wasConnected = prefs.getBoolean("connected", false);
            if (running && !wasConnected)
                prefs.edit().putBoolean("connected", true).apply();
            else if (!running && wasConnected)
                prefs.edit().putBoolean("connected", false).apply();
        } catch (Exception e) {
            Log.e(TAG, "checkStatus: " + e.getMessage());
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopStatusCheck();
        closeBypassTunnel();
        try { unbindService(serviceConnection); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
