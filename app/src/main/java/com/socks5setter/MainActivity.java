package com.socks5setter;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int VPN_REQUEST = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (getIntent().getBooleanExtra("request_vpn", false)) {
            requestVpnPermission();
        }
        updateUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
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
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            startVpnService();
        }
    }

    private void startVpnService() {
        Intent intent = new Intent(this, Socks5VpnService.class);
        startService(intent);
        updateUI();
    }

    private void updateUI() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        String host = prefs.getString("host", "No configurado");
        int port = prefs.getInt("port", 0);
        String user = prefs.getString("user", "");
        boolean connected = prefs.getBoolean("connected", false);

        TextView status = findViewById(R.id.status);
        TextView info = findViewById(R.id.info);

        status.setText(connected ? "● Conectado" : "○ Desconectado");
        status.setTextColor(connected ? 0xFF4CAF50 : 0xFFF44336);
        info.setText("Servidor: " + host + "\nPuerto: " + port + "\nUsuario: " + user);
    }
}