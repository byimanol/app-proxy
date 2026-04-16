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

public class MainActivity extends Activity {
    private static final int VPN_REQUEST    = 1;
    private static final int CONFIG_REQUEST = 2;

    private Handler  handler = new Handler();
    private Runnable refreshRunnable;
    private Switch   vpnSwitch;
    private boolean  updatingUI = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        refreshRunnable = new Runnable() {
            @Override public void run() {
                updateUI();
                handler.postDelayed(this, 1000);
            }
        };

        // Pedir permiso VPN al abrir la app si el túnel no está activo todavía
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        boolean connected = prefs.getBoolean("connected", false);
        boolean bypass    = prefs.getBoolean("bypass_mode", false);
        boolean tunnelActive = connected || bypass;

        if (!tunnelActive || getIntent().getBooleanExtra("request_vpn", false)) {
            requestVpnPermission();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refreshRunnable);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        MenuItem switchItem = menu.findItem(R.id.switch_main);
        if (switchItem != null) {
            vpnSwitch = (Switch) switchItem.getActionView()
                                           .findViewById(R.id.switch_action_button);
            if (vpnSwitch != null) {
                vpnSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                    if (updatingUI) return;
                    if (isChecked) {
                        requestVpnPermission();
                    } else {
                        sendBypass(true); // switch OFF → bypass → "Desconectado"
                    }
                });
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.prof_add) {
            startActivityForResult(new Intent(this, ConfigActivity.class), CONFIG_REQUEST);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            // Permiso concedido → arrancar el servicio (se conecta en bypass
            // si no hay proxy, o con proxy si ya está configurado)
            startService(new Intent(this, Socks5VpnService.class));
        } else if (requestCode == VPN_REQUEST) {
            // Usuario denegó el permiso — mostrar aviso pero no hacer nada más
            Toast.makeText(this, "Permiso de VPN denegado", Toast.LENGTH_SHORT).show();
        } else if (requestCode == CONFIG_REQUEST) {
            updateUI();
        }
    }

    private void sendBypass(boolean bypass) {
        Intent i = new Intent(this, Socks5VpnService.class);
        i.setAction(Socks5VpnService.ACTION_SET_BYPASS);
        i.putExtra(Socks5VpnService.EXTRA_BYPASS, bypass);
        getSharedPreferences("proxy_config", MODE_PRIVATE)
            .edit()
            .putBoolean("bypass_mode", bypass)
            .putBoolean("connected", !bypass)
            .apply();
        startService(i);
    }

    private void requestVpnPermission() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            // Android muestra el diálogo de permiso VPN
            startActivityForResult(intent, VPN_REQUEST);
        } else {
            // Permiso ya concedido anteriormente → arrancar directo
            startService(new Intent(this, Socks5VpnService.class));
        }
    }

    private String getLocalIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                java.net.NetworkInterface iface = ifaces.nextElement();
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address)
                        return addr.getHostAddress();
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return "IP desconocida";
    }

    private void updateUI() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        String  host      = prefs.getString("host",  "No configurado");
        int     port      = prefs.getInt("port", 0);
        String  user      = prefs.getString("user",  "");
        String  alias     = prefs.getString("alias", "");
        boolean connected = prefs.getBoolean("connected",   false);
        boolean bypass    = prefs.getBoolean("bypass_mode", false);

        TextView title  = findViewById(R.id.title);
        TextView status = findViewById(R.id.status);
        TextView info   = findViewById(R.id.info);

        title.setText(alias.isEmpty() ? getLocalIp() : alias);

        boolean showConnected = connected && !bypass;
        status.setText(showConnected ? "● Conectado" : "○ Desconectado");
        status.setTextColor(showConnected ? 0xFF4CAF50 : 0xFFF44336);

        info.setText(
            "IPv4: "       + getLocalIp() +
            "\nServidor: " + host +
            "\nPuerto: "   + port +
            "\nUsuario: "  + (user.isEmpty() ? "Sin autenticación" : user)
        );

        updatingUI = true;
        if (vpnSwitch != null && vpnSwitch.isChecked() != showConnected)
            vpnSwitch.setChecked(showConnected);
        updatingUI = false;
    }
}
