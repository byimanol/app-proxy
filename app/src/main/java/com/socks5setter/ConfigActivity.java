package com.socks5setter;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class ConfigActivity extends Activity {
    private EditText editServer, editPort, editUser, editPassword;
    private Button btnSave, btnCancel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.config_dialog);

        editServer = findViewById(R.id.edit_server);
        editPort = findViewById(R.id.edit_port);
        editUser = findViewById(R.id.edit_user);
        editPassword = findViewById(R.id.edit_password);
        btnSave = findViewById(R.id.btn_save);
        btnCancel = findViewById(R.id.btn_cancel);

        loadConfiguration();

        btnSave.setOnClickListener(v -> saveConfiguration());
        btnCancel.setOnClickListener(v -> finish());
    }

    private void loadConfiguration() {
        SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
        editServer.setText(prefs.getString("host", ""));
        editPort.setText(String.valueOf(prefs.getInt("port", 1080)));
        editUser.setText(prefs.getString("user", ""));
        editPassword.setText(prefs.getString("pass", ""));
    }

    private void saveConfiguration() {
        String server = editServer.getText().toString().trim();
        String portStr = editPort.getText().toString().trim();
        String user = editUser.getText().toString().trim();
        String password = editPassword.getText().toString().trim();

        if (server.isEmpty()) {
            Toast.makeText(this, "El servidor es requerido", Toast.LENGTH_SHORT).show();
            return;
        }

        if (portStr.isEmpty()) {
            Toast.makeText(this, "El puerto es requerido", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            int port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) {
                Toast.makeText(this, "Puerto inválido (1-65535)", Toast.LENGTH_SHORT).show();
                return;
            }

            SharedPreferences prefs = getSharedPreferences("proxy_config", MODE_PRIVATE);
            prefs.edit()
                .putString("host", server)
                .putInt("port", port)
                .putString("user", user)
                .putString("pass", password)
                .apply();

            Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show();
            finish();

        } catch (NumberFormatException e) {
            Toast.makeText(this, "Puerto debe ser un número", Toast.LENGTH_SHORT).show();
        }
    }
}
