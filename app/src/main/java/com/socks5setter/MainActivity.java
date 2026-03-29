package com.socks5setter;

import android.app.Activity;
import android.content.*;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final int VPN_REQUEST = 1;
    private static final int CONFIG_REQUEST = 2;
    private Handler handler = new Handler();
    private Runnable refreshRunnable;
    private Switch vpnSwitch;
    private String cachedPublicIp = "";
    private String cachedCountry = "";
    private boolean fetchingPublicIp = false;
    private boolean lastConnectedState = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (getIntent().getBooleanExtra("request_vpn", false)) {
            requestVpnPermission();
        }

        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                updateUI();
                handler.postDelayed(this, 1000);
            }
        };
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        MenuItem switchItem = menu.findItem(R.id.switch_main);
        if (switchItem != null) {
            vpnSwitch = (Switch) switchItem.getActionView().findViewById(R.id.switch_action_button);
            if (vpnSwitch != null) {
                vpnSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        if (!isProxyConfigured()) {
                            Toast.makeText(MainActivity.this, "Configura el proxy primero", Toast.LENGTH_SHORT).show();
                            vpnSwitch.setChecked(false);
                            return;
                        }
                        requestVpnPermission();
                    } else {
                        stopVpn();
                    }
                });
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.prof_add) {
            Intent intent = new Intent(this, ConfigActivity.class);
            startActivityForResult(intent, CONFIG_REQUEST);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
    }

    private boolean isProxyConfigured() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        String host = prefs.getString("host", "");
        return !host.isEmpty();
    }

    private void requestVpnPermission() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST);
        } else {
            startVpnService();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            startVpnService();
        } else if (requestCode == VPN_REQUEST) {
            if (vpnSwitch != null) vpnSwitch.setChecked(false);
            Toast.makeText(this, "Permiso de VPN denegado", Toast.LENGTH_SHORT).show();
        } else if (requestCode == CONFIG_REQUEST) {
            updateUI();
        }
    }

    private void startVpnService() {
        startService(new Intent(this, Socks5VpnService.class));
    }

    private void stopVpn() {
        Intent intent = new Intent(this, Socks5VpnService.class);
        intent.setAction("STOP");
        startService(intent);
    }

    private String getLocalIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "IP desconocida";
    }

    private void fetchPublicIpInfo() {
        if (fetchingPublicIp) return;
        fetchingPublicIp = true;

        new Thread(() -> {
            try {
                int attempts = 0;

                while (attempts < 10) {
                    Thread.sleep(3000);
                    attempts++;

                    try {
                        URL url = new URL("https://ipinfo.io/json");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);

                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        br.close();

                        String json = sb.toString();
                        String ip = extractJson(json, "ip");
                        String country = extractJson(json, "country");

                        // Si la IP es diferente a la local, el proxy está activo
                        if (!ip.isEmpty() && !ip.equals(getLocalIp())) {
                            cachedPublicIp = ip;
                            cachedCountry = country;

                            getSharedPreferences("proxy_config", MODE_PRIVATE).edit()
                                .putString("public_ip", cachedPublicIp)
                                .putString("public_country", cachedCountry)
                                .apply();
                            break;
                        }

                    } catch (Exception ignored) {}
                }

                if (cachedPublicIp.isEmpty()) {
                    cachedPublicIp = "Error";
                    cachedCountry = "";
                }

            } catch (Exception e) {
                cachedPublicIp = "Error";
                cachedCountry = "";
            }
            fetchingPublicIp = false;
        }).start();
    }

    private String extractJson(String json, String key) {
        try {
            String search = "\"" + key + "\": \"";
            int start = json.indexOf(search);
            if (start == -1) {
                search = "\"" + key + "\":\"";
                start = json.indexOf(search);
            }
            start += search.length();
            int end = json.indexOf("\"", start);
            return json.substring(start, end).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void updateUI() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        String host = prefs.getString("host", "No configurado");
        int port = prefs.getInt("port", 0);
        String user = prefs.getString("user", "");
        String pass = prefs.getString("pass", "");
        String alias = prefs.getString("alias", "");
        boolean connected = prefs.getBoolean("connected", false);

        TextView title = findViewById(R.id.title);
        TextView status = findViewById(R.id.status);
        TextView info = findViewById(R.id.info);
        TextView publicIpView = findViewById(R.id.public_ip);

        title.setText(alias.isEmpty() ? getLocalIp() : alias);

        status.setText(connected ? "● Conectado" : "○ Desconectado");
        status.setTextColor(connected ? 0xFF4CAF50 : 0xFFF44336);

        info.setText(
            "IPv4: " + getLocalIp() +
            "\nServidor: " + host +
            "\nPuerto: " + port +
            "\nUsuario: " + (user.isEmpty() ? "Sin autenticación" : user) +
            "\nContraseña: " + (pass.isEmpty() ? "Sin contraseña" : pass)
        );

        // Solo consultar cuando cambia de desconectado a conectado
        if (connected && !lastConnectedState) {
            getSharedPreferences("proxy_config", MODE_PRIVATE).edit()
                .remove("public_ip")
                .remove("public_country")
                .apply();
            cachedPublicIp = "";
            cachedCountry = "";
            fetchPublicIpInfo();
        }

        // Resetear cuando se desconecta
        if (!connected && lastConnectedState) {
            cachedPublicIp = "";
            cachedCountry = "";
            getSharedPreferences("proxy_config", MODE_PRIVATE).edit()
                .remove("public_ip")
                .remove("public_country")
                .apply();
        }

        lastConnectedState = connected;

        // Cargar desde SharedPreferences si existe y no está en memoria
        if (cachedPublicIp.isEmpty()) {
            cachedPublicIp = prefs.getString("public_ip", "");
            cachedCountry = prefs.getString("public_country", "");
        }

        if (connected && !cachedPublicIp.isEmpty() && !cachedPublicIp.equals("Error")) {
            publicIpView.setText("IP Pública: " + cachedPublicIp + "  |  País: " + cachedCountry);
        } else if (connected) {
            publicIpView.setText("IP Pública: consultando...");
        } else {
            publicIpView.setText("");
        }

        if (vpnSwitch != null && vpnSwitch.isChecked() != connected) {
            vpnSwitch.setChecked(connected);
        }
    }
}