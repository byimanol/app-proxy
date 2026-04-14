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
    private IVpnService vpnServiceInterface;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statusCheckRunnable;

    // --- QUIC blocker ---
    private UdpQuicBlocker mQuicBlocker;
    private Thread         mQuicBlockerThread;

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
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        String host = prefs.getString("host", "");
        int port = prefs.getInt("port", 1080);
        String user = prefs.getString("user", "");
        String pass = prefs.getString("pass", "");

        if (host == null || host.isEmpty()) {
            Log.e(TAG, "No proxy server configured");
            prefs.edit().putBoolean("connected", false).apply();
            stopSelf();
            return;
        }

        // If already bound to a service, unbind first to reset state
        if (vpnServiceInterface != null) {
            Log.d(TAG, "Clearing old VPN service interface");
            try {
                unbindService(serviceConnection);
            } catch (Exception e) {
                Log.w(TAG, "Error unbinding old service: " + e.getMessage());
            }
            vpnServiceInterface = null;
        }

        Intent intent = new Intent(this, net.typeblog.socks.SocksVpnService.class);
        intent.putExtra(Constants.INTENT_NAME, "SOCKS5 Proxy");
        intent.putExtra(Constants.INTENT_SERVER, host);
        intent.putExtra(Constants.INTENT_PORT, port);
        intent.putExtra(Constants.INTENT_USERNAME, user);
        intent.putExtra(Constants.INTENT_PASSWORD, pass);
        intent.putExtra(Constants.INTENT_ROUTE, "bypass-lan");
        intent.putExtra(Constants.INTENT_DNS, "8.8.8.8");
        intent.putExtra(Constants.INTENT_DNS_PORT, 53);
        intent.putExtra(Constants.INTENT_IPV6_PROXY, true);
        
        startService(intent);

        // --- Arrancar bloqueador QUIC (UDP 443) ---
        startQuicBlocker();
        
        // Bind to the VPN service to monitor its status
        Intent bindIntent = new Intent(this, net.typeblog.socks.SocksVpnService.class);
        bindService(bindIntent, serviceConnection, BIND_AUTO_CREATE);
        
        Log.d(TAG, "VPN service started with host: " + host + ", QUIC blocker activo");
    }

    private void stopVpn() {
        stopStatusCheck();
        stopQuicBlocker();
        
        try {
            unbindService(serviceConnection);
        } catch (Exception e) {
            Log.w(TAG, "Error unbinding service: " + e.getMessage());
        }
        
        stopService(new Intent(this, net.typeblog.socks.SocksVpnService.class));
        getSharedPreferences("proxy_config", MODE_PRIVATE)
            .edit().putBoolean("connected", false).apply();
        stopSelf();
    }

    private void startStatusCheck() {
        stopStatusCheck();
        statusCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkVpnStatus();
                handler.postDelayed(this, 5000); // Check every 5 seconds
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
        if (vpnServiceInterface == null) {
            Log.w(TAG, "VPN interface not available");
            return;
        }

        try {
            boolean isRunning = vpnServiceInterface.isRunning();
            SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
            boolean wasConnected = prefs.getBoolean("connected", false);
            
            if (isRunning && !wasConnected) {
                Log.d(TAG, "VPN tunnel established");
                prefs.edit().putBoolean("connected", true).apply();
            } else if (!isRunning && wasConnected) {
                Log.w(TAG, "VPN tunnel lost");
                prefs.edit().putBoolean("connected", false).apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking VPN status: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopStatusCheck();
        stopQuicBlocker();
        try {
            unbindService(serviceConnection);
        } catch (Exception e) {
            Log.w(TAG, "Error unbinding service on destroy: " + e.getMessage());
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------
    // QUIC Blocker — bloquea UDP 443 para forzar fallback TCP en Chrome
    // ---------------------------------------------------------------
    private void startQuicBlocker() {
        stopQuicBlocker();
        try {
            // Crear una interfaz VPN temporal solo para leer/escribir paquetes
            // y filtrar QUIC. Usamos el mismo fd del túnel activo via /proc/self/fd
            // Buscamos el fd del tun0 abierto por tun2socks
            java.io.File fdDir = new java.io.File("/proc/self/fd");
            java.io.FileDescriptor tunFd = null;

            if (fdDir.exists()) {
                for (String fdName : fdDir.list()) {
                    try {
                        String link = new java.io.File("/proc/self/fd/" + fdName).getCanonicalPath();
                        if (link.contains("tun")) {
                            // Abrir directamente por número de fd
                            int fdNum = Integer.parseInt(fdName);
                            tunFd = getFdByNumber(fdNum);
                            if (tunFd != null) break;
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (tunFd == null) {
                Log.w(TAG, "No se encontró fd del túnel, QUIC blocker no iniciado");
                return;
            }

            mQuicBlocker = new UdpQuicBlocker(tunFd);
            mQuicBlockerThread = new Thread(mQuicBlocker, "QuicBlocker");
            mQuicBlockerThread.setDaemon(true);
            mQuicBlockerThread.start();
            Log.d(TAG, "QUIC blocker iniciado correctamente");
        } catch (Exception e) {
            Log.e(TAG, "Error iniciando QUIC blocker: " + e.getMessage());
        }
    }

    private java.io.FileDescriptor getFdByNumber(int fdNum) {
        try {
            java.lang.reflect.Field f = java.io.FileDescriptor.class.getDeclaredField("descriptor");
            f.setAccessible(true);
            java.io.FileDescriptor fd = new java.io.FileDescriptor();
            f.setInt(fd, fdNum);
            return fd;
        } catch (Exception e) {
            return null;
        }
    }

    private void stopQuicBlocker() {
        if (mQuicBlocker != null) {
            mQuicBlocker.stop();
            mQuicBlocker = null;
        }
        if (mQuicBlockerThread != null) {
            mQuicBlockerThread.interrupt();
            mQuicBlockerThread = null;
        }
    }

}