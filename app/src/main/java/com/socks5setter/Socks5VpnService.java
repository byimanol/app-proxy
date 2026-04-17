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
    private BypassForwarder      bypassForwarder;

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
            restorePreviousState();
            return START_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            teardownAll();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_SET_BYPASS.equals(action)) {
            boolean bypass = intent.getBooleanExtra(EXTRA_BYPASS, false);
            applyBypass(bypass);
            return START_STICKY;
        }

        restorePreviousState();
        return START_STICKY;
    }

    private void restorePreviousState() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        boolean bypass = prefs.getBoolean("bypass_mode", false);
        applyBypass(bypass);
    }

    private void applyBypass(boolean bypass) {
        teardownProxy();
        closeBypassTunnel();
        if (bypass) {
            startBypassTunnel();
        } else {
            startProxyTunnel();
        }
    }

    /**
     * Modo bypass — tráfico por red real usando IP del dispositivo.
     *
     * 1. Túnel VPN con ruta 0.0.0.0/0  →  satisface "Bloquear conexiones sin VPN"
     * 2. BypassForwarder lee paquetes del tun fd y los reenvía con sockets
     *    protect()-ados  →  salen por la red física, SIN pasar por proxy SOCKS5
     */
    private void startBypassTunnel() {
        try {
            Builder b = new Builder();
            b.setMtu(1500)
             .setSession("SOCKS5 VPN")
             .addAddress("10.0.0.1", 30)
             .addDnsServer("8.8.8.8")
             .addDnsServer("1.1.1.1")
             .addRoute("0.0.0.0", 0);

            bypassPfd = b.establish();

            if (bypassPfd == null) {
                Log.e(TAG, "No se pudo establecer el bypass tunnel");
                return;
            }

            bypassForwarder = new BypassForwarder(this, bypassPfd);
            bypassForwarder.start();

            Log.d(TAG, "Bypass tunnel activo con BypassForwarder");
        } catch (Exception e) {
            Log.e(TAG, "startBypassTunnel error: " + e.getMessage());
        }
    }

    private void closeBypassTunnel() {
        if (bypassForwarder != null) {
            bypassForwarder.stop();
            bypassForwarder = null;
        }
        if (bypassPfd != null) {
            try { bypassPfd.close(); } catch (Exception ignored) {}
            bypassPfd = null;
        }
    }

    private void startProxyTunnel() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        String host = prefs.getString("host", "");
        int    port = prefs.getInt("port", 1080);
        String user = prefs.getString("user", "");
        String pass = prefs.getString("pass", "");

        if (host == null || host.isEmpty()) {
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
        if (prefs.getBoolean("bypass_mode", false)) return;
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
