package com.socks5setter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public class ProxyContentProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        SharedPreferences prefs = getContext().getSharedPreferences("proxy_config", android.content.Context.MODE_PRIVATE);
        String host = prefs.getString("host", "No configurado");
        int port = prefs.getInt("port", 0);
        String user = prefs.getString("user", "");
        String pass = prefs.getString("pass", "");
        boolean connected = prefs.getBoolean("connected", false);
        String connectionString = String.format("%s:%s@%s:%s", user, pass, host, port);

        MatrixCursor cursor = new MatrixCursor(new String[]{"host", "port", "user", "pass", "connected"});
        // cursor.addRow(new Object[]{host, port, user, pass, connected ? "true" : "false"});
        cursor.addRow(new Object[]{connectionString, connected ? "true" : "false"});
        return cursor;
    }

    @Override
    public String getType(Uri uri) { return null; }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}