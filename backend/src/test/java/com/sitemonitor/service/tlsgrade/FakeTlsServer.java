package com.sitemonitor.service.tlsgrade;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Test için SAHTE TLS sunucusu (2026-10-10): loopback'te dinler, gelen ClientHello'yu ayrıştırır ({@link Hello}) ve
 * yanıtı test verir — ServerHello, uyarı, çöp, bekleme ya da kapatma. Hiçbir gerçek TLS yığını yok; yalnız ilk iletiler.
 * Her bağlantının kendi zaman aşımı var; sunucu en fazla {@code maxConnections} bağlantı kabul eder (sonsuz döngü yok).
 */
final class FakeTlsServer implements AutoCloseable {

    /** Ayrıştırılmış ClientHello. */
    record Hello(int legacyVersion, boolean offersTls13, boolean statusRequest, List<Integer> suites, String sni) { }

    /** Yanıt: yazılacak baytlar + yazdıktan sonra bekleme (ms) + kapatma. */
    record Reply(byte[] bytes, long stallMs) {
        static Reply of(byte[] b) { return new Reply(b, 0); }
        static Reply stall(long ms) { return new Reply(new byte[0], ms); }
        static Reply close() { return new Reply(new byte[0], 0); }
    }

    private final ServerSocket server;
    private final Thread thread;
    final List<Hello> hellos = new CopyOnWriteArrayList<>();

    FakeTlsServer(Function<Hello, Reply> responder, int maxConnections) throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.setSoTimeout(20_000);
        thread = new Thread(() -> {
            for (int i = 0; i < maxConnections && !server.isClosed(); i++) {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(5_000);
                    Hello h = readHello(s.getInputStream());
                    hellos.add(h);
                    Reply r = responder.apply(h);
                    OutputStream out = s.getOutputStream();
                    if (r.bytes().length > 0) {
                        out.write(r.bytes());
                        out.flush();
                    }
                    if (r.stallMs() > 0) Thread.sleep(r.stallMs());
                } catch (IOException e) {
                    if (server.isClosed()) return;
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "fake-tls");
        thread.setDaemon(true);
        thread.start();
    }

    int port() { return server.getLocalPort(); }

    TlsHelloProbe.Connector connector() {
        return t -> {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port()), t);
            return s;
        };
    }

    @Override
    public void close() throws IOException {
        server.close();
        thread.interrupt();
    }

    static Hello readHello(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        byte[] hdr = new byte[5];
        in.readFully(hdr);
        int len = ((hdr[3] & 0xff) << 8) | (hdr[4] & 0xff);
        byte[] b = new byte[len];
        in.readFully(b);
        int p = 4;                                   // el sıkışması başlığı
        int legacy = u16(b, p); p += 2;
        p += 32;                                     // random
        p += 1 + (b[p] & 0xff);                      // session id
        int suitesLen = u16(b, p); p += 2;
        List<Integer> suites = new ArrayList<>();
        for (int i = 0; i < suitesLen; i += 2) suites.add(u16(b, p + i));
        p += suitesLen;
        p += 1 + (b[p] & 0xff);                      // sıkıştırma
        boolean tls13 = false, status = false;
        String sni = null;
        int extEnd = p + 2 + u16(b, p);
        p += 2;
        while (p + 4 <= extEnd) {
            int type = u16(b, p), elen = u16(b, p + 2);
            if (type == 0x002b) tls13 = true;
            if (type == 0x0005) status = true;
            if (type == 0x0000) sni = new String(b, p + 4 + 5, u16(b, p + 4 + 3), java.nio.charset.StandardCharsets.US_ASCII);
            p += 4 + elen;
        }
        return new Hello(legacy, tls13, status, suites, sni);
    }

    // ── Yanıt kurucuları ──────────────────────────────────────────────────────────────────────

    static byte[] serverHello(int version, int cipher, boolean supportedVersions, boolean statusEcho) {
        ByteArrayOutputStream ext = new ByteArrayOutputStream();
        if (supportedVersions) { u16(ext, 0x002b); u16(ext, 2); u16(ext, version); }
        if (statusEcho) { u16(ext, 0x0005); u16(ext, 0); }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        u16(body, supportedVersions ? 0x0303 : version);
        body.writeBytes(new byte[32]);              // random
        body.write(0);                              // boş oturum kimliği
        u16(body, cipher);
        body.write(0);                              // sıkıştırma
        byte[] e = ext.toByteArray();
        if (e.length > 0) { u16(body, e.length); body.writeBytes(e); }
        return record(22, handshake(2, body.toByteArray()));
    }

    static byte[] handshake(int type, byte[] body) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(type);
        o.write((body.length >>> 16) & 0xff);
        o.write((body.length >>> 8) & 0xff);
        o.write(body.length & 0xff);
        o.writeBytes(body);
        return o.toByteArray();
    }

    static byte[] record(int type, byte[] body) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(type);
        u16(o, 0x0303);
        u16(o, body.length);
        o.writeBytes(body);
        return o.toByteArray();
    }

    static byte[] alert(int level, int desc) {
        return record(21, new byte[] {(byte) level, (byte) desc});
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    private static void u16(ByteArrayOutputStream o, int v) {
        o.write((v >>> 8) & 0xff);
        o.write(v & 0xff);
    }

    private static int u16(byte[] b, int p) {
        return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
    }
}
