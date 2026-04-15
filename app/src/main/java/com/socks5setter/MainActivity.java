package com.socks5setter;

import android.app.Activity;
import android.content.*;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int VPN_REQUEST    = 1;
    private static final int CONFIG_REQUEST = 2;

    private Handler  handler = new Handler();
    private Runnable refreshRunnable;

    private Switch vpnSwitch;
    private Switch bypassSwitch;

    private boolean updatingUI = false; // evita loops de listener

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Toggle bypass en la pantalla principal
        bypassSwitch = findViewById(R.id.switch_bypass);
        bypassSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            if (updatingUI) return;
            SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
            boolean connected = prefs.getBoolean("connected", false);
            if (!connected) {
                // No tiene sentido bypass si la VPN no está activa
                Toast.makeText(this, "Activa la VPN primero", Toast.LENGTH_SHORT).show();
                updatingUI = true;
                bypassSwitch.setChecked(false);
                updatingUI = false;
                return;
            }
            setBypasMode(isChecked);
        });

        if (getIntent().getBooleanExtra("request_vpn", false)) {
            requestVpnPermission();
        }

        refreshRunnable = new Runnable() {
            @Override public void run() {
                updateUI();
                handler.postDelayed(this, 1000);
            }
        };
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
            vpnSwitch = (Switch) switchItem.getActionView().findViewById(R.id.switch_action_button);
            if (vpnSwitch != null) {
                vpnSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                    if (updatingUI) return;
                    if (isChecked) {
                        if (!isProxyConfigured()) {
                            Toast.makeText(this, "Configura el proxy primero", Toast.LENGTH_SHORT).show();
                            updatingUI = true;
                            vpnSwitch.setChecked(false);
                            updatingUI = false;
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
            startActivityForResult(new Intent(this, ConfigActivity.class), CONFIG_REQUEST);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            startService(new Intent(this, Socks5VpnService.class));
        } else if (requestCode == VPN_REQUEST) {
            if (vpnSwitch != null) { updatingUI = true; vpnSwitch.setChecked(false); updatingUI = false; }
            Toast.makeText(this, "Permiso de VPN denegado", Toast.LENGTH_SHORT).show();
        } else if (requestCode == CONFIG_REQUEST) {
            updateUI();
        }
    }

    // ------------------------------------------------------------------
    private void setBypasMode(boolean bypass) {
        Intent i = new Intent(this, Socks5VpnService.class);
        i.setAction(Socks5VpnService.ACTION_SET_BYPASS);
        i.putExtra(Socks5VpnService.EXTRA_BYPASS, bypass);
        startService(i);

        String msg = bypass ? "Bypass activado — red original" : "Proxy restaurado";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private boolean isProxyConfigured() {
        return !getSharedPreferences("proxy_config", MODE_PRIVATE)
                .getString("host", "").isEmpty();
    }

    private void requestVpnPermission() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) startActivityForResult(intent, VPN_REQUEST);
        else startService(new Intent(this, Socks5VpnService.class));
    }

    private void stopVpn() {
        Intent i = new Intent(this, Socks5VpnService.class);
        i.setAction(Socks5VpnService.ACTION_STOP);
        startService(i);
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
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address)
                        return addr.getHostAddress();
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return "IP desconocida";
    }

    private void updateUI() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        String  host      = prefs.getString("host", "No configurado");
        int     port      = prefs.getInt("port", 0);
        String  user      = prefs.getString("user", "");
        String  pass      = prefs.getString("pass", "");
        String  alias     = prefs.getString("alias", "");
        boolean connected = prefs.getBoolean("connected", false);
        boolean bypass    = prefs.getBoolean("bypass_mode", false);

        TextView title      = findViewById(R.id.title);
        TextView status     = findViewById(R.id.status);
        TextView info       = findViewById(R.id.info);
        TextView bypassDesc = findViewById(R.id.bypass_desc);

        title.setText(alias.isEmpty() ? getLocalIp() : alias);

        if (connected && bypass) {
            status.setText("⇄ Bypass activo");
            status.setTextColor(0xFFFF9800); // naranja
        } else if (connected) {
            status.setText("● Conectado");
            status.setTextColor(0xFF4CAF50); // verde
        } else {
            status.setText("○ Desconectado");
            status.setTextColor(0xFFF44336); // rojo
        }

        bypassDesc.setText(bypass
            ? "Tráfico saliendo por red original"
            : "VPN activa, tráfico por proxy");

        info.setText(
            "IPv4: "         + getLocalIp()  +
            "\nServidor: "   + host           +
            "\nPuerto: "     + port           +
            "\nUsuario: "    + (user.isEmpty() ? "Sin autenticación" : user) +
            "\nContraseña: " + (pass.isEmpty() ? "Sin contraseña"    : pass)
        );

        updatingUI = true;
        if (vpnSwitch != null && vpnSwitch.isChecked() != connected)
            vpnSwitch.setChecked(connected);
        if (bypassSwitch != null && bypassSwitch.isChecked() != bypass)
            bypassSwitch.setChecked(bypass);
        updatingUI = false;
    }
}
