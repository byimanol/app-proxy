package com.socks5setter;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        updateUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
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