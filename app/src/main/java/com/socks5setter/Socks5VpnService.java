package com.socks5setter;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import net.typeblog.socks.IVpnService;
import net.typeblog.socks.util.Constants;

public class Socks5VpnService extends VpnService {
    private static final String TAG = "Socks5VpnService";

    public static final String ACTION_STOP         = "STOP";
    public static final String ACTION_SET_BYPASS   = "SET_BYPASS";
    public static final String EXTRA_BYPASS        = "bypass_mode";

    private IVpnService vpnServiceInterface;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statusCheckRunnable;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            vpnServiceInterface = IVpnService.Stub.asInterface(service);
            Log.d(TAG, "Connected to VPN service");
            startStatusCheck();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            vpnServiceInterface = null;
            Log.d(TAG, "Disconnected from VPN service");
            stopStatusCheck();
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopVpn();
            return START_NOT_STICKY;
        }

        if (ACTION_SET_BYPASS.equals(action)) {
            boolean bypass = intent.getBooleanExtra(EXTRA_BYPASS, false);
            applyBypassMode(bypass);
            return START_STICKY;
        }

        startVpn();
        return START_STICKY;
    }

    // ------------------------------------------------------------------
    // MODO BYPASS
    // Cuando bypass=true: reiniciamos el SocksVpnService con una ruta
    // vacía (sin rutas) — el túnel VPN sigue "activo" (el icono de llave
    // permanece) pero ningún paquete pasa por él, así que todo el tráfico
    // sale por la red real del dispositivo.
    // Cuando bypass=false: restauramos la ruta normal (todo el tráfico
    // pasa por el proxy SOCKS5).
    // ------------------------------------------------------------------
    private void applyBypassMode(boolean bypass) {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        prefs.edit().putBoolean("bypass_mode", bypass).apply();

        Log.d(TAG, "Modo bypass: " + bypass);

        // Reiniciar el túnel con la nueva configuración de ruta
        String host = prefs.getString("host", "");
        if (host == null || host.isEmpty()) return;

        // Parar el túnel actual
        try { unbindService(serviceConnection); } catch (Exception ignored) {}
        stopService(new Intent(this, net.typeblog.socks.SocksVpnService.class));

        if (bypass) {
            // En modo bypass arrancamos el SocksVpnService con route="bypass-lan"
            // pero además excluimos TODAS las apps → tráfico sale por red real.
            // La forma más limpia: no arrancar el SocksVpnService en absoluto
            // pero mantener el estado "connected=true" para que el switch no cambie.
            // En su lugar creamos un túnel VPN vacío (sin rutas) directamente.
            startBypassTunnel();
        } else {
            // Modo normal: restaurar el proxy
            startVpn();
        }
    }

    /**
     * Crea un túnel VPN mínimo SIN rutas → todo el tráfico sigue saliendo
     * por la red real. El icono de llave de Android permanece visible
     * (la VPN técnicamente está activa) pero ningún paquete la atraviesa.
     */
    private void startBypassTunnel() {
        try {
            Builder b = new Builder();
            b.setMtu(1500)
             .setSession("SOCKS5 Bypass")
             .addAddress("26.26.26.1", 24)
             .addDnsServer("8.8.8.8");
            // Sin addRoute() → cero tráfico enrutado por el túnel

            android.os.ParcelFileDescriptor pfd = b.establish();
            if (pfd != null) {
                // Guardamos el fd para cerrarlo cuando se desactive bypass
                getSharedPreferences("proxy_config", MODE_PRIVATE)
                    .edit().putBoolean("connected", true).apply();
                Log.d(TAG, "Bypass tunnel establecido (sin rutas)");
                // Cerramos el fd inmediatamente — Android mantiene la VPN activa
                // hasta que el service muera o se llame a stopSelf()
                pfd.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creando bypass tunnel: " + e.getMessage());
        }
    }

    private void startVpn() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        String host = prefs.getString("host", "");
        int    port = prefs.getInt("port", 1080);
        String user = prefs.getString("user", "");
        String pass = prefs.getString("pass", "");

        if (host == null || host.isEmpty()) {
            Log.e(TAG, "No proxy server configured");
            prefs.edit().putBoolean("connected", false).apply();
            stopSelf();
            return;
        }

        if (vpnServiceInterface != null) {
            try { unbindService(serviceConnection); } catch (Exception e) {
                Log.w(TAG, "Error unbinding old service: " + e.getMessage());
            }
            vpnServiceInterface = null;
        }

        Intent intent = new Intent(this, net.typeblog.socks.SocksVpnService.class);
        intent.putExtra(Constants.INTENT_NAME,     "SOCKS5 Proxy");
        intent.putExtra(Constants.INTENT_SERVER,   host);
        intent.putExtra(Constants.INTENT_PORT,     port);
        intent.putExtra(Constants.INTENT_USERNAME, user);
        intent.putExtra(Constants.INTENT_PASSWORD, pass);
        intent.putExtra(Constants.INTENT_ROUTE,    "bypass-lan");
        intent.putExtra(Constants.INTENT_DNS,      "8.8.8.8");
        intent.putExtra(Constants.INTENT_DNS_PORT, 53);
        intent.putExtra(Constants.INTENT_IPV6_PROXY, false);

        startService(intent);

        Intent bindIntent = new Intent(this, net.typeblog.socks.SocksVpnService.class);
        bindService(bindIntent, serviceConnection, BIND_AUTO_CREATE);

        Log.d(TAG, "VPN service started: " + host + ":" + port);
    }

    private void stopVpn() {
        stopStatusCheck();
        try { unbindService(serviceConnection); } catch (Exception e) {
            Log.w(TAG, "Error unbinding: " + e.getMessage());
        }
        stopService(new Intent(this, net.typeblog.socks.SocksVpnService.class));
        getSharedPreferences("proxy_config", MODE_PRIVATE)
            .edit()
            .putBoolean("connected", false)
            .putBoolean("bypass_mode", false)
            .apply();
        stopSelf();
    }

    private void startStatusCheck() {
        stopStatusCheck();
        statusCheckRunnable = new Runnable() {
            @Override public void run() {
                checkVpnStatus();
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

    private void checkVpnStatus() {
        if (vpnServiceInterface == null) return;
        try {
            boolean isRunning = vpnServiceInterface.isRunning();
            SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
            boolean wasConnected = prefs.getBoolean("connected", false);
            if (isRunning && !wasConnected) {
                prefs.edit().putBoolean("connected", true).apply();
            } else if (!isRunning && wasConnected) {
                boolean bypassActive = prefs.getBoolean("bypass_mode", false);
                // Si estamos en bypass el túnel externo no corre → no marcar desconectado
                if (!bypassActive) {
                    prefs.edit().putBoolean("connected", false).apply();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking VPN status: " + e.getMessage());
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopStatusCheck();
        try { unbindService(serviceConnection); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
