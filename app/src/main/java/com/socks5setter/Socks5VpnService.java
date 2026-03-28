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
        intent.putExtra(Constants.INTENT_IPV6_PROXY, false);
        
        startService(intent);
        
        // Bind to the VPN service to monitor its status
        Intent bindIntent = new Intent(this, net.typeblog.socks.SocksVpnService.class);
        bindService(bindIntent, serviceConnection, BIND_AUTO_CREATE);
        
        Log.d(TAG, "VPN service started with host: " + host + ", monitoring connection...");
    }

    private void stopVpn() {
        stopStatusCheck();
        
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
        try {
            unbindService(serviceConnection);
        } catch (Exception e) {
            Log.w(TAG, "Error unbinding service on destroy: " + e.getMessage());
        }
        super.onDestroy();
    }
}