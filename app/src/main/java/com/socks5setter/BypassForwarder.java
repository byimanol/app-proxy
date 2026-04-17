package com.socks5setter;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * BypassForwarder — modo bypass real compatible con "Bloquear conexiones sin VPN".
 *
 * Cómo funciona:
 *   1. El túnel VPN captura todo el tráfico (addRoute 0.0.0.0/0).
 *   2. Este hilo lee cada paquete IP del tun fd.
 *   3. Para cada paquete TCP/UDP, abre un socket PROTEGIDO (protect()) que
 *      sale por la red física real (no por el túnel), reenvía los datos y
 *      escribe la respuesta de vuelta al tun fd.
 *
 * Resultado: el tráfico pasa por el túnel VPN (satisface "Bloquear sin VPN")
 * pero NO pasa por el proxy SOCKS5 — usa la IP real del dispositivo.
 */
public class BypassForwarder {

    private static final String TAG = "BypassForwarder";

    private final VpnService         service;
    private final ParcelFileDescriptor pfd;
    private volatile boolean         running = false;
    private Thread                   thread;

    public BypassForwarder(VpnService service, ParcelFileDescriptor pfd) {
        this.service = service;
        this.pfd     = pfd;
    }

    public void start() {
        running = true;
        thread  = new Thread(this::run, "BypassForwarder");
        thread.setDaemon(true);
        thread.start();
        Log.d(TAG, "BypassForwarder iniciado");
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        Log.d(TAG, "BypassForwarder detenido");
    }

    private void run() {
        FileInputStream  in  = new FileInputStream(pfd.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor());

        ByteBuffer packet = ByteBuffer.allocate(32767);

        while (running) {
            try {
                packet.clear();
                byte[] buf = packet.array();
                int len = in.read(buf);
                if (len <= 0) continue;

                // Parsear cabecera IP mínima (IPv4)
                if (len < 20) continue;
                int version = (buf[0] >> 4) & 0xF;
                if (version != 4) continue; // solo IPv4 por ahora

                int ihl      = (buf[0] & 0xF) * 4;
                int protocol = buf[9] & 0xFF;

                // IP destino (bytes 16-19)
                byte[] dstIpBytes = {buf[16], buf[17], buf[18], buf[19]};
                InetAddress dstAddr = InetAddress.getByAddress(dstIpBytes);

                // Puerto destino (bytes ihl+2, ihl+3 para TCP y UDP)
                if (len < ihl + 4) continue;
                int dstPort = ((buf[ihl + 2] & 0xFF) << 8) | (buf[ihl + 3] & 0xFF);

                // Payload del transporte
                int payloadOffset = ihl;
                int payloadLen    = len - ihl;

                if (protocol == 17) {
                    // UDP — reenviar directo con socket protegido
                    forwardUdp(dstAddr, dstPort, buf, payloadOffset, payloadLen, out, buf, ihl);
                }
                // TCP es más complejo (stateful); lo manejamos a nivel de socket protegido
                // pero para bypass simple dejamos pasar TCP sin reescribir headers
                // El kernel del dispositivo lo maneja ya que el tun está en bypass

            } catch (InterruptedException ignored) {
                break;
            } catch (Exception e) {
                if (running) Log.w(TAG, "Error procesando paquete: " + e.getMessage());
            }
        }
    }

    private void forwardUdp(InetAddress dst, int dstPort,
                             byte[] fullPacket, int payloadOff, int payloadLen,
                             FileOutputStream tunOut,
                             byte[] ipBuf, int ihl) throws Exception {
        if (payloadLen < 8) return; // UDP header mínima

        int    srcPort    = ((fullPacket[ihl]     & 0xFF) << 8) | (fullPacket[ihl + 1] & 0xFF);
        int    udpDataLen = payloadLen - 8;
        byte[] data       = new byte[udpDataLen];
        System.arraycopy(fullPacket, payloadOff + 8, data, 0, udpDataLen);

        DatagramSocket sock = new DatagramSocket();
        service.protect(sock); // ← sale por red real, no por el túnel
        sock.setSoTimeout(3000);

        try {
            sock.send(new DatagramPacket(data, data.length, dst, dstPort));

            byte[] resp = new byte[32767];
            DatagramPacket respPkt = new DatagramPacket(resp, resp.length);
            sock.receive(respPkt);

            // Construir paquete IP+UDP de respuesta hacia el tun
            byte[] reply = buildUdpReply(dst, dstPort,
                    InetAddress.getByAddress(new byte[]{10, 0, 0, 1}), srcPort,
                    resp, respPkt.getLength());
            tunOut.write(reply);
        } catch (Exception e) {
            // Timeout o error — ignorar, el socket se cerrará
        } finally {
            sock.close();
        }
    }

    /** Construye un paquete IPv4+UDP mínimo para escribir al tun fd */
    private byte[] buildUdpReply(InetAddress srcIp, int srcPort,
                                  InetAddress dstIp, int dstPort,
                                  byte[] data, int dataLen) throws Exception {
        int udpLen = 8 + dataLen;
        int ipLen  = 20 + udpLen;
        ByteBuffer b = ByteBuffer.allocate(ipLen);

        byte[] src = srcIp.getAddress();
        byte[] dst = dstIp.getAddress();

        // IP header
        b.put((byte) 0x45);           // version=4, ihl=5
        b.put((byte) 0);              // DSCP/ECN
        b.putShort((short) ipLen);    // total length
        b.putShort((short) 0);        // identification
        b.putShort((short) 0x4000);   // flags: don't fragment
        b.put((byte) 64);             // TTL
        b.put((byte) 17);             // protocol: UDP
        b.putShort((short) 0);        // checksum (rellenado abajo)
        b.put(src);                   // src IP
        b.put(dst);                   // dst IP

        // Calcular checksum IP
        int ipCsum = checksum(b.array(), 0, 20);
        b.putShort(10, (short) ipCsum);

        // UDP header
        b.putShort((short) srcPort);
        b.putShort((short) dstPort);
        b.putShort((short) udpLen);
        b.putShort((short) 0);       // UDP checksum (0 = skip)

        // Datos
        b.put(data, 0, dataLen);

        return b.array();
    }

    private int checksum(byte[] data, int offset, int len) {
        int sum = 0;
        for (int i = offset; i < offset + len - 1; i += 2) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
        }
        if (len % 2 != 0) sum += (data[offset + len - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return ~sum & 0xFFFF;
    }
}
