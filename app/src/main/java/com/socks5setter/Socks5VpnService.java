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

    private String extractBinary() throws IOException {
        File outFile = new File(getFilesDir(), "tun2socks");
        Log.d(TAG, "Binary path: " + outFile.getAbsolutePath());
        Log.d(TAG, "Binary exists: " + outFile.exists());
        if (!outFile.exists()) {
            Log.d(TAG, "Extracting binary from assets...");
            InputStream is = getAssets().open("tun2socks");
            FileOutputStream fos = new FileOutputStream(outFile);
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
            fos.close();
            is.close();
            outFile.setExecutable(true);
            Log.d(TAG, "Binary extracted OK");
        }
        return outFile.getAbsolutePath();
    }

    private void startVpn() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        String host = prefs.getString("host", "");
        int port = prefs.getInt("port", 1080);
        String user = prefs.getString("user", "");
        String pass = prefs.getString("pass", "");

        if (host == null || host.isEmpty()) {
            Log.e(TAG, "No hay host configurado");
            stopSelf();
            return;
        }

        try {
            // 1. Crear interfaz TUN
            vpnInterface = new Builder()
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .addDnsServer("1.1.1.1")
                .setSession("Socks5VPN")
                .setMtu(1500)
                .establish();

            if (vpnInterface == null) {
                Log.e(TAG, "VPN interface es null - permiso no concedido");
                stopSelf();
                return;
            }

            int fd = vpnInterface.getFd();
            Log.d(TAG, "VPN interface creada, fd: " + fd);

            // 2. Extraer binario
            String tun2socksPath = extractBinary();

            // 3. Armar proxy URL
            String proxyUrl;
            if (user != null && !user.isEmpty()) {
                proxyUrl = "socks5://" + user + ":" + pass + "@" + host + ":" + port;
            } else {
                proxyUrl = "socks5://" + host + ":" + port;
            }

            // 4. Armar comando
            String[] cmd = {
                tun2socksPath,
                "--device", "fd://" + fd,
                "--proxy", proxyUrl,
                "--loglevel", "debug"
            };

            Log.d(TAG, "CMD: " + android.text.TextUtils.join(" ", cmd));

            // 5. Lanzar proceso
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            tun2socksProcess = pb.start();

            Log.d(TAG, "tun2socks proceso iniciado");

            // 6. Leer output del proceso en hilo separado
            new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(tun2socksProcess.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.d(TAG, "[tun2socks] " + line);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error leyendo output tun2socks", e);
                }

                // Cuando el proceso muere
                int exitCode = -1;
                try { exitCode = tun2socksProcess.waitFor(); } catch (Exception ignored) {}
                Log.e(TAG, "tun2socks terminó con código: " + exitCode);
                prefs.edit().putBoolean("connected", false).apply();
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