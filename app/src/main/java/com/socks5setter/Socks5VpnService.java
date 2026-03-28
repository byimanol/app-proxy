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
        if (!outFile.exists()) {
            Log.d(TAG, "Extrayendo binario...");
            InputStream is = getAssets().open("tun2socks");
            FileOutputStream fos = new FileOutputStream(outFile);
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
            fos.close();
            is.close();
        }
        outFile.setExecutable(true);

        Log.d(TAG, "Binary size: " + outFile.length() + " bytes");
        Log.d(TAG, "Binary executable: " + outFile.canExecute());

        try {
            Process test = Runtime.getRuntime().exec(new String[]{outFile.getAbsolutePath(), "--version"});
            BufferedReader br = new BufferedReader(new InputStreamReader(test.getInputStream()));
            BufferedReader brErr = new BufferedReader(new InputStreamReader(test.getErrorStream()));
            String line;
            while ((line = br.readLine()) != null) Log.d(TAG, "Version stdout: " + line);
            while ((line = brErr.readLine()) != null) Log.d(TAG, "Version stderr: " + line);
            int code = test.waitFor();
            Log.d(TAG, "Version exit code: " + code);
        } catch (Exception e) {
            Log.e(TAG, "Error probando binario: " + e.getMessage());
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

            String tun2socksPath = extractBinary();

            // Crear config file para hev-socks5-tunnel
            String config =
                "tunnel:\n" +
                "  mtu: 1500\n" +
                "  ipv4: 10.0.0.2\n" +
                "  fd: " + fd + "\n" +
                "socks5:\n" +
                "  port: " + port + "\n" +
                "  address: " + host + "\n" +
                (user.isEmpty() ? "" :
                "  username: " + user + "\n" +
                "  password: " + pass + "\n") +
                "misc:\n" +
                "  log-level: debug\n";

            Log.d(TAG, "Config:\n" + config);

            File configFile = new File(getFilesDir(), "config.yml");
            FileOutputStream fos = new FileOutputStream(configFile);
            fos.write(config.getBytes());
            fos.close();

            String[] cmd = {tun2socksPath, configFile.getAbsolutePath()};
            Log.d(TAG, "CMD: " + tun2socksPath + " " + configFile.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            tun2socksProcess = pb.start();

            Log.d(TAG, "tun2socks proceso iniciado");

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

                int exitCode = -1;
                try { exitCode = tun2socksProcess.waitFor(); } catch (Exception ignored) {}
                Log.e(TAG, "tun2socks terminó con código: " + exitCode);
                prefs.edit().putBoolean("connected", false).apply();
            }).start();

            prefs.edit().putBoolean("connected", true).apply();
            Log.d(TAG, "VPN iniciada → " + host + ":" + port);

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