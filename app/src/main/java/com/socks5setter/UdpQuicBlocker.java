package com.socks5setter;

import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * UdpQuicBlocker
 *
 * Lee paquetes IPv4 del túnel VPN.  Los paquetes UDP cuyo puerto destino
 * sea 443 (QUIC/HTTP3) son descartados silenciosamente.  El resto se reenvía
 * tal cual al mismo file descriptor para que tun2socks los procese normal.
 *
 * Al bloquear QUIC, Chrome y otros navegadores hacen fallback automático a
 * HTTPS sobre TCP, que sí funciona correctamente con el proxy SOCKS5.
 */
public class UdpQuicBlocker implements Runnable {

    private static final String TAG = "UdpQuicBlocker";

    // Tamaño máximo de un paquete IPv4 (MTU 1500 bytes)
    private static final int PACKET_SIZE = 1500;

    // Protocolos IP
    private static final int PROTO_UDP = 17;

    // Puerto QUIC (UDP 443)
    private static final int QUIC_PORT = 443;

    private final FileDescriptor mTunFd;
    private volatile boolean mRunning = true;

    public UdpQuicBlocker(FileDescriptor tunFd) {
        this.mTunFd = tunFd;
    }

    public void stop() {
        mRunning = false;
    }

    @Override
    public void run() {
        FileInputStream  in  = new FileInputStream(mTunFd);
        FileOutputStream out = new FileOutputStream(mTunFd);
        ByteBuffer packet = ByteBuffer.allocate(PACKET_SIZE);

        Log.d(TAG, "UdpQuicBlocker iniciado — bloqueando QUIC (UDP 443)");

        while (mRunning) {
            try {
                packet.clear();
                byte[] buf = packet.array();
                int len = in.read(buf);

                if (len <= 0) continue;

                // --- Parsear cabecera IPv4 mínima ---
                // Byte 0 = versión (4 bits) + IHL (4 bits)
                int version = (buf[0] >> 4) & 0xF;
                if (version != 4) {
                    // IPv6 u otro — pasar tal cual
                    out.write(buf, 0, len);
                    continue;
                }

                int ihl = (buf[0] & 0xF) * 4; // longitud cabecera en bytes
                if (len < ihl + 4) {
                    out.write(buf, 0, len);
                    continue;
                }

                int protocol = buf[9] & 0xFF;

                if (protocol == PROTO_UDP) {
                    // Cabecera UDP: src_port(2) + dst_port(2) + ...
                    if (len >= ihl + 4) {
                        int dstPort = ((buf[ihl + 2] & 0xFF) << 8)
                                    | (buf[ihl + 3] & 0xFF);

                        if (dstPort == QUIC_PORT) {
                            // Descartar silenciosamente — fuerza fallback a TCP/HTTPS
                            Log.v(TAG, "Paquete QUIC bloqueado (UDP 443)");
                            continue;
                        }
                    }
                }

                // Cualquier otro paquete pasa normalmente
                out.write(buf, 0, len);

            } catch (IOException e) {
                if (mRunning) {
                    Log.e(TAG, "Error leyendo túnel: " + e.getMessage());
                }
                break;
            }
        }

        Log.d(TAG, "UdpQuicBlocker detenido");
    }
}
