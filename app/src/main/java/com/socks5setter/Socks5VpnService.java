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
    private ParcelFileDescriptor bypassPfd; // túnel vacío cuando bypass está activo

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            vpnServiceInterface = IVpnService.Stub.asInterface(service);
            Log.d(TAG, "SocksVpnService conectado");
            startStatusCheck();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            vpnServiceInterface = null;
            Log.d(TAG, "SocksVpnService desconectado");
            stopStatusCheck();
        }
    };

    // ------------------------------------------------------------------
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }

        if (ACTION_SET_BYPASS.equals(action)) {
            boolean bypass = intent.getBooleanExtra(EXTRA_BYPASS, false);
            applyBypass(bypass);
            return START_STICKY;
        }

        // Arranque normal — leer estado de bypass guardado y decidir
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        boolean bypass = prefs.getBoolean("bypass_mode", false);

        if (bypass) {
            startBypassTunnel();
        } else {
            startProxyTunnel();
        }

        return START_STICKY;
    }

    // ------------------------------------------------------------------
    // BYPASS: túnel VPN vacío (sin rutas) → tráfico sale por red real
    // ------------------------------------------------------------------
    private void applyBypass(boolean bypass) {
        getSharedPreferences("proxy_config", MODE_PRIVATE)
            .edit().putBoolean("bypass_mode", bypass).apply();

        // Detener lo que esté corriendo
        teardownProxy();
        closeBypassTunnel();

        if (bypass) {
            startBypassTunnel();
        } else {
            startProxyTunnel();
        }
    }

    /**
     * Crea un túnel VPN mínimo SIN addRoute() → ningún paquete lo atraviesa.
     * El icono de llave de Android permanece (la VPN está "activa") pero
     * todo el tráfico sale por la red real del dispositivo.
     */
    private void startBypassTunnel() {
        try {
            closeBypassTunnel();
            Builder b = new Builder();
            b.setMtu(1500)
             .setSession("SOCKS5 Bypass")
             .addAddress("26.26.26.1", 24)
             .addDnsServer("8.8.8.8");
            // Sin addRoute() deliberadamente

            bypassPfd = b.establish();
            if (bypassPfd != null) {
                getSharedPreferences("proxy_config", MODE_PRIVATE)
                    .edit().putBoolean("connected", true).apply();
                Log.d(TAG, "Bypass tunnel activo (sin rutas)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creando bypass tunnel: " + e.getMessage());
        }
    }

    private void closeBypassTunnel() {
        if (bypassPfd != null) {
            try { bypassPfd.close(); } catch (Exception ignored) {}
            bypassPfd = null;
        }
    }

    // ------------------------------------------------------------------
    // PROXY: arrancar tun2socks + SocksVpnService
    // ------------------------------------------------------------------
    private void startProxyTunnel() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        String host = prefs.getString("host", "");
        int    port = prefs.getInt("port", 1080);
        String user = prefs.getString("user", "");
        String pass = prefs.getString("pass", "");

        if (host == null || host.isEmpty()) {
            // Sin proxy configurado → arrancar bypass tunnel para mantener
            // el "siempre conectado" aunque no haya proxy todavía
            Log.w(TAG, "Sin proxy configurado, arrancando bypass tunnel");
            startBypassTunnel();
            return;
        }

        teardownProxy();

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

        Intent bindIntent = new Intent(this, net.typeblog.socks.SocksVpnService.class);
        bindService(bindIntent, serviceConnection, BIND_AUTO_CREATE);

        Log.d(TAG, "Proxy tunnel arrancado: " + host + ":" + port);
    }

    private void teardownProxy() {
        stopStatusCheck();
        try { unbindService(serviceConnection); } catch (Exception ignored) {}
        vpnServiceInterface = null;
        stopService(new Intent(this, net.typeblog.socks.SocksVpnService.class));
    }

    // ------------------------------------------------------------------
    private void stopEverything() {
        teardownProxy();
        closeBypassTunnel();
        getSharedPreferences("proxy_config", MODE_PRIVATE)
            .edit()
            .putBoolean("connected",   false)
            .putBoolean("bypass_mode", false)
            .apply();
        stopSelf();
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
        boolean bypass = prefs.getBoolean("bypass_mode", false);

        // En bypass el estado lo gestiona bypassPfd, no el servicio externo
        if (bypass) return;

        if (vpnServiceInterface == null) return;
        try {
            boolean running     = vpnServiceInterface.isRunning();
            boolean wasConnected = prefs.getBoolean("connected", false);
            if (running && !wasConnected)
                prefs.edit().putBoolean("connected", true).apply();
            else if (!running && wasConnected)
                prefs.edit().putBoolean("connected", false).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error checkStatus: " + e.getMessage());
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
