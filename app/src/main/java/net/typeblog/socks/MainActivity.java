package net.typeblog.socks;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.widget.TextView;
import net.typeblog.socks.util.Profile;
import net.typeblog.socks.util.ProfileManager;

public class MainActivity extends Activity {
    private static final int VPN_REQUEST = 1;
    private Handler handler = new Handler();
    private Runnable refreshRunnable;
    private ProfileManager mManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mManager = new ProfileManager(this);

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

    private void updateUI() {
        Profile profile = mManager.getDefault();
        SharedPreferences prefs = getSharedPreferences("proxy_status", MODE_PRIVATE);
        boolean connected = prefs.getBoolean("connected", false);

        TextView status = findViewById(R.id.status);
        TextView info = findViewById(R.id.info);

        if (profile != null) {
            status.setText(connected ? "● Conectado" : "○ Desconectado");
            status.setTextColor(connected ? 0xFF4CAF50 : 0xFFF44336);
            info.setText(
                "Servidor: " + profile.getServer() +
                "\nPuerto: " + profile.getPort() +
                "\nUsuario: " + profile.getUsername()
            );
        } else {
            status.setText("○ No configurado");
            status.setTextColor(0xFFF44336);
            info.setText("Envía configuración por ADB");
        }
    }
}