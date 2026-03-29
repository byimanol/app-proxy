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

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;

public class MainActivity extends Activity {
    private static final int VPN_REQUEST = 1;
    private static final int CONFIG_REQUEST = 2;
    private Handler handler = new Handler();
    private Runnable refreshRunnable;
    private Switch vpnSwitch;
    private boolean lastConnectedState = false;
    private boolean fetchingPublicIp = false;

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
    protected void onResume() {
        super.onResume();
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
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
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                java.net.NetworkInterface.getNetworkInterfaces();
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

    /**
     * Obtiene la IP pública conectándose DIRECTAMENTE al proxy SOCKS5
     * usando un socket Java puro (sin pasar por el túnel VPN).
     *
     * ¿Por qué funciona?
     * - addDisallowedApplication("com.socks5setter") excluye TODA nuestra app
     *   del túnel, así que nuestros sockets salen por la red real.
     * - Eso nos permite conectarnos directamente al servidor SOCKS5.
     * - Hacemos el handshake SOCKS5 manualmente y pedimos al proxy que se
     *   conecte a ipinfo.io por nosotros.
     * - ipinfo.io ve la IP del proxy → nos devuelve la IP del proxy. ✓
     */
    private void fetchPublicIpViaSocks5Direct() {
        if (fetchingPublicIp) return;
        fetchingPublicIp = true;

        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        final String proxyHost = prefs.getString("host", "");
        final int    proxyPort = prefs.getInt("port", 1080);
        final String proxyUser = prefs.getString("user", "");
        final String proxyPass = prefs.getString("pass", "");

        new Thread(() -> {
            String ip      = "";
            String country = "";

            // Dar tiempo al túnel para establecerse
            try { Thread.sleep(3000); } catch (Exception ignored) {}

            for (int attempt = 0; attempt < 10 && ip.isEmpty(); attempt++) {
                try {
                    // ── 1. Conectar al proxy SOCKS5 directamente ──────────────
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(proxyHost, proxyPort), 7000);
                    socket.setSoTimeout(7000);

                    OutputStream out = socket.getOutputStream();
                    InputStream  in  = socket.getInputStream();

                    // ── 2. Greeting: anunciar métodos de autenticación ────────
                    boolean needsAuth = proxyUser != null && !proxyUser.isEmpty();
                    if (needsAuth) {
                        out.write(new byte[]{0x05, 0x02, 0x00, 0x02}); // no-auth + user/pass
                    } else {
                        out.write(new byte[]{0x05, 0x01, 0x00});        // solo no-auth
                    }
                    out.flush();

                    byte[] methodResp = new byte[2];
                    readFully(in, methodResp);
                    if (methodResp[0] != 0x05) {
                        socket.close();
                        throw new IOException("Respuesta inesperada del servidor");
                    }

                    // ── 3. Autenticación usuario/contraseña (RFC 1929) ────────
                    if (methodResp[1] == 0x02) {
                        byte[] userBytes = proxyUser.getBytes("UTF-8");
                        byte[] passBytes = (proxyPass != null ? proxyPass : "").getBytes("UTF-8");
                        ByteArrayOutputStream authReq = new ByteArrayOutputStream();
                        authReq.write(0x01);
                        authReq.write(userBytes.length);
                        authReq.write(userBytes);
                        authReq.write(passBytes.length);
                        authReq.write(passBytes);
                        out.write(authReq.toByteArray());
                        out.flush();

                        byte[] authResp = new byte[2];
                        readFully(in, authResp);
                        if (authResp[1] != 0x00) {
                            socket.close();
                            throw new IOException("Autenticación SOCKS5 fallida");
                        }
                    } else if (methodResp[1] != 0x00) {
                        socket.close();
                        throw new IOException("Método de auth no aceptado: " + methodResp[1]);
                    }

                    // ── 4. CONNECT → ipinfo.io:80 (HTTP plano, sin TLS) ───────
                    String targetHost = "ipinfo.io";
                    int    targetPort = 80;
                    byte[] hostBytes  = targetHost.getBytes("UTF-8");

                    ByteArrayOutputStream connectReq = new ByteArrayOutputStream();
                    connectReq.write(0x05); // VER
                    connectReq.write(0x01); // CMD: CONNECT
                    connectReq.write(0x00); // RSV
                    connectReq.write(0x03); // ATYP: dominio
                    connectReq.write(hostBytes.length);
                    connectReq.write(hostBytes);
                    connectReq.write((targetPort >> 8) & 0xFF);
                    connectReq.write(targetPort & 0xFF);
                    out.write(connectReq.toByteArray());
                    out.flush();

                    // Leer respuesta CONNECT (4 bytes fijos + dirección variable)
                    byte[] connResp = new byte[4];
                    readFully(in, connResp);
                    if (connResp[1] != 0x00) {
                        socket.close();
                        throw new IOException("SOCKS5 CONNECT rechazado, código: " + connResp[1]);
                    }
                    // Consumir BND.ADDR y BND.PORT según el tipo de dirección
                    switch (connResp[3]) {
                        case 0x01: readFully(in, new byte[6]);           break; // IPv4 + port
                        case 0x03: readFully(in, new byte[in.read()+2]); break; // dominio + port
                        case 0x04: readFully(in, new byte[18]);          break; // IPv6 + port
                    }

                    // ── 5. HTTP GET a través del túnel SOCKS5 ─────────────────
                    String httpRequest =
                        "GET /json HTTP/1.1\r\n" +
                        "Host: ipinfo.io\r\n" +
                        "Accept: application/json\r\n" +
                        "Connection: close\r\n\r\n";
                    out.write(httpRequest.getBytes("UTF-8"));
                    out.flush();

                    // ── 6. Leer cuerpo HTTP (saltar headers) ──────────────────
                    BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                    StringBuilder body = new StringBuilder();
                    boolean headersDone = false;
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (!headersDone) {
                            if (line.isEmpty()) headersDone = true;
                        } else {
                            body.append(line);
                        }
                    }
                    socket.close();

                    ip      = extractJson(body.toString(), "ip");
                    country = extractJson(body.toString(), "country");

                } catch (Exception e) {
                    android.util.Log.w("MainActivity",
                        "Intento " + (attempt + 1) + " fallido: " + e.getMessage());
                    ip = "";
                    country = "";
                }

                if (ip.isEmpty()) {
                    try { Thread.sleep(3000); } catch (Exception ignored) {}
                }
            }

            final String finalIp      = ip.isEmpty() ? "No disponible" : ip;
            final String finalCountry = country;

            getSharedPreferences("proxy_config", MODE_PRIVATE).edit()
                .putString("public_ip", finalIp)
                .putString("public_country", finalCountry)
                .apply();

            fetchingPublicIp = false;
        }).start();
    }

    /** Lee exactamente buf.length bytes del stream. */
    private void readFully(InputStream in, byte[] buf) throws IOException {
        int offset = 0;
        while (offset < buf.length) {
            int read = in.read(buf, offset, buf.length - offset);
            if (read == -1) throw new IOException("Stream cerrado inesperadamente");
            offset += read;
        }
    }

    private String extractJson(String json, String key) {
        try {
            String search = "\"" + key + "\": \"";
            int start = json.indexOf(search);
            if (start == -1) {
                search = "\"" + key + "\":\"";
                start  = json.indexOf(search);
            }
            if (start == -1) return "";
            start += search.length();
            int end = json.indexOf("\"", start);
            return json.substring(start, end).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String countryFlag(String countryCode) {
        if (countryCode == null || countryCode.length() != 2) return "";
        int a = Character.codePointAt(countryCode, 0) - 'A' + 0x1F1E6;
        int b = Character.codePointAt(countryCode, 1) - 'A' + 0x1F1E6;
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }

    private void updateUI() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        String  host      = prefs.getString("host", "No configurado");
        int     port      = prefs.getInt("port", 0);
        String  user      = prefs.getString("user", "");
        String  pass      = prefs.getString("pass", "");
        String  alias     = prefs.getString("alias", "");
        boolean connected = prefs.getBoolean("connected", false);

        TextView title        = findViewById(R.id.title);
        TextView status       = findViewById(R.id.status);
        TextView info         = findViewById(R.id.info);
        TextView publicIpView = findViewById(R.id.public_ip);
        TextView flagView     = findViewById(R.id.country_flag);

        title.setText(alias.isEmpty() ? getLocalIp() : alias);
        status.setText(connected ? "● Conectado" : "○ Desconectado");
        status.setTextColor(connected ? 0xFF4CAF50 : 0xFFF44336);

        info.setText(
            "IPv4: "         + getLocalIp() +
            "\nServidor: "   + host +
            "\nPuerto: "     + port +
            "\nUsuario: "    + (user.isEmpty() ? "Sin autenticación" : user) +
            "\nContraseña: " + (pass.isEmpty() ? "Sin contraseña" : pass)
        );

        // Conectado → limpiar IP vieja y disparar fetch
        if (connected && !lastConnectedState) {
            prefs.edit()
                .remove("public_ip")
                .remove("public_country")
                .apply();
            fetchPublicIpViaSocks5Direct();
        }

        // Desconectado → limpiar
        if (!connected && lastConnectedState) {
            prefs.edit()
                .remove("public_ip")
                .remove("public_country")
                .apply();
            fetchingPublicIp = false;
        }

        lastConnectedState = connected;

        String publicIp      = prefs.getString("public_ip", "");
        String publicCountry = prefs.getString("public_country", "");

        if (connected && !publicIp.isEmpty()) {
            publicIpView.setText("IP Pública: " + publicIp +
                (publicCountry.isEmpty() ? "" : "  |  País: " + publicCountry));
            if (!publicCountry.isEmpty()) {
                flagView.setText(countryFlag(publicCountry));
                flagView.setVisibility(android.view.View.VISIBLE);
            } else {
                flagView.setVisibility(android.view.View.GONE);
            }
        } else if (connected) {
            publicIpView.setText("IP Pública: consultando...");
            flagView.setVisibility(android.view.View.GONE);
        } else {
            publicIpView.setText("");
            flagView.setVisibility(android.view.View.GONE);
        }

        if (vpnSwitch != null && vpnSwitch.isChecked() != connected) {
            vpnSwitch.setChecked(connected);
        }
    }
}
