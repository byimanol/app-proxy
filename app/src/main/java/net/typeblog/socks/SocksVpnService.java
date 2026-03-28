package net.typeblog.socks;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import net.typeblog.socks.util.Routes;
import net.typeblog.socks.util.Utility;

import java.util.Locale;
import java.util.Objects;

import static net.typeblog.socks.util.Constants.*;

public class SocksVpnService extends VpnService {
    class VpnBinder extends IVpnService.Stub {
        @Override
        public boolean isRunning() {
            return mRunning;
        }

        @Override
        public void stop() {
            stopMe();
        }
    }

    private static final String TAG = SocksVpnService.class.getSimpleName();

    private ParcelFileDescriptor mInterface;
    private boolean mRunning = false;
    private final IBinder mBinder = new VpnBinder();
    private String mCurrentServer = "";
    private int mCurrentPort = 0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        final String name = intent.getStringExtra(INTENT_NAME);
        final String server = intent.getStringExtra(INTENT_SERVER);
        final int port = intent.getIntExtra(INTENT_PORT, 1080);
        final String username = intent.getStringExtra(INTENT_USERNAME);
        final String passwd = intent.getStringExtra(INTENT_PASSWORD);
        final String route = intent.getStringExtra(INTENT_ROUTE);
        final String dns = intent.getStringExtra(INTENT_DNS);
        final int dnsPort = intent.getIntExtra(INTENT_DNS_PORT, 53);
        final boolean perApp = intent.getBooleanExtra(INTENT_PER_APP, false);
        final boolean appBypass = intent.getBooleanExtra(INTENT_APP_BYPASS, false);
        final String[] appList = intent.getStringArrayExtra(INTENT_APP_LIST);
        final boolean ipv6 = intent.getBooleanExtra(INTENT_IPV6_PROXY, false);
        final String udpgw = intent.getStringExtra(INTENT_UDP_GW);

        // Check if there's already a connection to a different server
        if (mRunning && (!server.equals(mCurrentServer) || port != mCurrentPort)) {
            Log.d(TAG, "Different server detected, stopping old connection");
            stopMe();
            mRunning = false;
            mCurrentServer = "";
            mCurrentPort = 0;
        }

        // If already running with the same configuration, don't reconnect
        if (mRunning) {
            Log.d(TAG, "Already connected to " + server + ":" + port);
            return START_STICKY;
        }

        // Store current server and port
        mCurrentServer = server;
        mCurrentPort = port;

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            String NOTIFICATION_CHANNEL_ID = "com.socks5setter";
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                    "Socks5 VPN", NotificationManager.IMPORTANCE_NONE);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            Objects.requireNonNull(notificationManager).createNotificationChannel(channel);
            builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        int NOTIFICATION_ID = 1;
        int intentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, com.socks5setter.MainActivity.class), intentFlags);

        startForeground(NOTIFICATION_ID, builder
                .setContentTitle("SOCKS5 Proxy")
                .setContentText("Conectado a " + server)
                .setPriority(Notification.PRIORITY_MIN)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(contentIntent)
                .build());

        configure(name, route, perApp, appBypass, appList, ipv6);

        if (mInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface");
            stopMe();
            return START_NOT_STICKY;
        }

        if (!start(mInterface.getFd(), server, port, username, passwd, dns, dnsPort, ipv6, udpgw)) {
            Log.e(TAG, "Failed to start VPN tunnel");
            stopMe();
            return START_NOT_STICKY;
        }

        Log.d(TAG, "VPN Connection established successfully");
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        super.onRevoke();
        stopMe();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopMe();
    }

    private void stopMe() {
        stopForeground(true);
        Utility.killPidFile(getFilesDir() + "/tun2socks.pid");
        Utility.killPidFile(getFilesDir() + "/pdnsd.pid");
        try {
            System.jniclose(mInterface.getFd());
            mInterface.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        mRunning = false;
        mCurrentServer = "";
        mCurrentPort = 0;
        stopSelf();
    }

    private void configure(String name, String route, boolean perApp, boolean bypass, String[] apps, boolean ipv6) {
        Builder b = new Builder();
        b.setMtu(1500)
                .setSession(name != null ? name : "Socks5VPN")
                .addAddress("26.26.26.1", 24)
                .addDnsServer("8.8.8.8");

        if (ipv6) {
            b.addAddress("fdfe:dcba:9876::1", 126)
                    .addRoute("::", 0);
        }

        Routes.addRoutes(this, b, route);
        b.addRoute("8.8.8.8", 32);

        if (!perApp) {
            try {
                b.addDisallowedApplication("com.socks5setter");
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            if (bypass) {
                try {
                    b.addDisallowedApplication("com.socks5setter");
                } catch (Exception e) {
                    e.printStackTrace();
                }
                for (String p : apps) {
                    if (TextUtils.isEmpty(p)) continue;
                    try {
                        b.addDisallowedApplication(p.trim());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } else {
                for (String p : apps) {
                    if (TextUtils.isEmpty(p) || p.trim().equals("com.socks5setter")) continue;
                    try {
                        b.addAllowedApplication(p.trim());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        mInterface = b.establish();
    }

    private boolean start(int fd, String server, int port, String user, String passwd, String dns, int dnsPort, boolean ipv6, String udpgw) {
        Utility.makePdnsdConf(this, dns, dnsPort);

        if (Utility.exec(String.format(Locale.US, "%s/libpdnsd.so -c %s/pdnsd.conf",
                getApplicationInfo().nativeLibraryDir, getFilesDir())) != 0) {
            Log.e(TAG, "Failed to start pdnsd");
            return false;
        }

        String command = String.format(Locale.US,
                "%s/libtun2socks.so --netif-ipaddr 26.26.26.2"
                        + " --netif-netmask 255.255.255.0"
                        + " --socks-server-addr %s:%d"
                        + " --tunfd %d"
                        + " --tunmtu 1500"
                        + " --loglevel 3"
                        + " --pid %s/tun2socks.pid"
                        + " --sock %s/sock_path",
                getApplicationInfo().nativeLibraryDir, server, port, fd,
                getFilesDir(), getApplicationInfo().dataDir);

        if (user != null) {
            command += " --username " + user;
            command += " --password " + passwd;
        }

        if (ipv6) {
            command += " --netif-ip6addr fdfe:dcba:9876::2";
        }

        command += " --dnsgw 26.26.26.1:8091";

        if (udpgw != null) {
            command += " --udpgw-remote-server-addr " + udpgw;
        }

        if (Utility.exec(command) != 0) {
            Log.e(TAG, "Failed to execute tun2socks");
            return false;
        }

        int i = 0;
        while (i < 5) {
            if (System.sendfd(fd, getApplicationInfo().dataDir + "/sock_path") != -1) {
                mRunning = true;
                Log.d(TAG, "Successfully sent file descriptor to native process");
                return true;
            }
            i++;
            try {
                Thread.sleep(1000L * i);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        Log.e(TAG, "Failed to send file descriptor to native process after 5 attempts");
        return false;
    }
}