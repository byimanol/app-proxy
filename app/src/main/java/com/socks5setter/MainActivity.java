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

public class MainActivity extends Activity {
    private static final int VPN_REQUEST = 1;
    private Handler handler = new Handler();
    private Runnable refreshRunnable;
    private Switch vpnSwitch;

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
    protected void onResume() {
        super.onResume();
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
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
            // Permission denied, uncheck the switch
            if (vpnSwitch != null) {
                vpnSwitch.setChecked(false);
            }
        }
    }

    private void startVpnService() {
        Intent intent = new Intent(this, Socks5VpnService.class);
        startService(intent);
    }

    private void stopVpn() {
        Intent intent = new Intent(this, Socks5VpnService.class);
        intent.setAction("STOP");
        startService(intent);
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
        
        // Update switch state to match connection status
        if (vpnSwitch != null && vpnSwitch.isChecked() != connected) {
            vpnSwitch.setChecked(connected);
        }
    }
}