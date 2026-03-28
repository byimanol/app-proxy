package com.socks5setter;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.*;

public class Socks5VpnService extends VpnService {

    private static final String TAG = "Socks5VPN";
    private ParcelFileDescriptor vpnInterface;
    private Process tun2socksProcess;

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

        try {
            // 1. Crear interfaz TUN
            vpnInterface = new Builder()
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setSession("Socks5VPN")
                .setMtu(1500)
                .establish();

            int fd = vpnInterface.getFd();

            // 2. Ruta del binario instalado con la app
            String tun2socksPath = new File(
                getApplicationInfo().nativeLibraryDir, "libtun2socks.so"
            ).getAbsolutePath();

            // 3. Armar comando
            // tun2socks espera: --device fd://NUM --proxy socks5://user:pass@host:port
            String proxyUrl;
            if (user != null && !user.isEmpty()) {
                proxyUrl = "socks5://" + user + ":" + pass + "@" + host + ":" + port;
            } else {
                proxyUrl = "socks5://" + host + ":" + port;
            }

            String[] cmd = {
                tun2socksPath,
                "--device", "fd://" + fd,
                "--proxy", proxyUrl,
                "--loglevel", "warning"
            };

            // 4. Lanzar proceso
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            tun2socksProcess = pb.start();

            // Log output en hilo separado
            new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(tun2socksProcess.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.d(TAG, line);
                    }
                } catch (Exception e) { /* ignorar */ }
            }).start();

            prefs.edit().putBoolean("connected", true).apply();
            Log.d(TAG, "VPN iniciada → " + proxyUrl);

        } catch (Exception e) {
            Log.e(TAG, "Error iniciando VPN", e);
            stopSelf();
        }
    }

    private void stopVpn() {
        if (tun2socksProcess != null) {
            tun2socksProcess.destroy();
            tun2socksProcess = null;
        }
        try {
            if (vpnInterface != null) vpnInterface.close();
        } catch (Exception ignored) {}

        getSharedPreferences("proxy_config", MODE_PRIVATE)
            .edit().putBoolean("connected", false).apply();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}