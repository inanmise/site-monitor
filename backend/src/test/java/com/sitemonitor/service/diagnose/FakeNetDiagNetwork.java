package com.sitemonitor.service.diagnose;

import com.sitemonitor.service.ProcessProbe;
import com.sitemonitor.service.SsrfGuard;
import org.xbill.DNS.Message;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Ağsız {@link NetDiagNetwork} — tanılama servis testleri hiçbir gerçek sokete / DNS'e / sürece dokunmaz. Her dikiş bir
 * harita ya da işlevle yönlendirilir; yapılan çağrılar {@link #calls} listesine yazılır (yan etki denetimi için).
 */
class FakeNetDiagNetwork implements NetDiagNetwork {

    /** host → vetted IP'ler; {@link #blocked}/{@link #unresolvable} önceliklidir. */
    final Map<String, List<InetAddress>> hosts = new HashMap<>();
    final Map<String, String> blocked = new HashMap<>();
    final java.util.Set<String> unresolvable = new java.util.HashSet<>();
    /** komut adı (ping / traceroute / tracert) → sonuç. */
    final Map<String, ProcessProbe.Result> commands = new HashMap<>();
    /** "ip:port" → bağlantı sonucu (Socket ya da fırlatılacak IOException). */
    final Map<String, Object> connects = new HashMap<>();
    boolean proxy;
    boolean proxyAuth;
    String proxySecret;
    /** "host:port" → tünel sonucu (Tunnel ya da IOException). */
    final Map<String, Object> tunnels = new HashMap<>();
    /** TLS sonucu (TlsSession'ın soketi null ise çağrılan ham soket kullanılır) ya da IOException. */
    Object tls;
    Trust trust = new Trust(true, null);
    /** "ip:port" → UDP sonucu. */
    final Map<String, UdpResult> udps = new HashMap<>();
    /** DNS yanıtlayıcısı: (sunucu IP, sorgu, tcp) → yanıt; null → zaman aşımı. */
    DnsResponder dns = (server, q, tcp) -> null;
    final List<InetSocketAddress> resolvers = new ArrayList<>();
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    interface DnsResponder {
        Message answer(String server, Message query, boolean tcp) throws IOException;
    }

    static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);   // IP metni — ad çözümlemesi yapılmaz
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public List<InetAddress> vet(String host) {
        calls.add("vet " + host);
        if (blocked.containsKey(host)) throw new SsrfGuard.BlockedException(blocked.get(host));
        if (unresolvable.contains(host) || !hosts.containsKey(host)) {
            throw new SsrfGuard.UnresolvableHostException(SsrfGuard.UNRESOLVABLE_PREFIX + host);
        }
        return hosts.get(host);
    }

    @Override
    public ProcessProbe.Result exec(List<String> args, int timeoutSec) {
        calls.add("exec " + String.join(" ", args));
        ProcessProbe.Result r = commands.get(args.get(0));
        return r != null ? r : new ProcessProbe.Result("komut çalıştırılamadı: " + args.get(0), -1, false);
    }

    @Override
    public Socket connect(InetAddress ip, int port, int timeoutMs) throws IOException {
        String key = NetDiagRun.ip(ip) + ":" + port;
        calls.add("connect " + key);
        Object r = connects.get(key);
        if (r instanceof IOException e) throw e;
        if (r instanceof Socket s) return s;
        throw new java.net.SocketTimeoutException("connect timed out");
    }

    @Override public boolean proxyConfigured() { return proxy; }
    @Override public String proxyAddress() { return proxy ? "proxy.example.test:8080" : ""; }
    @Override public boolean proxyAuth() { return proxy && proxyAuth; }
    @Override public List<String> secretValues() { return proxySecret == null ? List.of() : List.of(proxySecret); }

    @Override
    public Tunnel tunnel(String host, int port, int timeoutMs) throws IOException {
        calls.add("tunnel " + host + ":" + port);
        Object r = tunnels.get(host + ":" + port);
        if (r instanceof IOException e) throw e;
        if (r instanceof Tunnel t) return t;
        throw new java.net.ConnectException("Connection refused");
    }

    @Override
    public TlsSession tls(Socket raw, String host, int port, int timeoutMs) throws IOException {
        calls.add("tls " + host + ":" + port);
        if (tls instanceof IOException e) throw e;
        if (tls instanceof TlsSession s) {
            return new TlsSession(s.socket() != null ? s.socket() : raw, s.protocol(), s.cipher(), s.alpn(), s.chain());
        }
        throw new javax.net.ssl.SSLHandshakeException("Remote host terminated the handshake");
    }

    @Override
    public UdpResult udp(InetAddress ip, int port, byte[] payload, int timeoutMs) {
        String key = NetDiagRun.ip(ip) + ":" + port;
        calls.add("udp " + key);
        UdpResult r = udps.get(key);
        return r != null ? r : new UdpResult("timeout", 0, null);
    }

    @Override
    public Message dns(InetSocketAddress server, Message query, int timeoutMs, boolean tcp) throws IOException {
        String ip = NetDiagRun.ip(server.getAddress());
        calls.add("dns " + ip + " " + query.getQuestion().getName() + " " + org.xbill.DNS.Type.string(query.getQuestion().getType())
                + (tcp ? " tcp" : ""));
        Message m = dns.answer(ip, query, tcp);
        if (m == null) throw new java.net.SocketTimeoutException("Timed out while trying to resolve " + query.getQuestion().getName());
        return m;
    }

    @Override public List<InetSocketAddress> systemResolvers() { return resolvers; }

    @Override public Trust trust(X509Certificate[] chain) { return trust; }

    /** Bayt akışlı sahte soket: girdi sabit, çıktı yakalanır. */
    static final class FakeSocket extends Socket {
        final ByteArrayInputStream in;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean closed;

        FakeSocket(byte[] input) {
            this.in = new ByteArrayInputStream(input == null ? new byte[0] : input);
        }

        static FakeSocket of(String input) {
            return new FakeSocket(input == null ? new byte[0] : input.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        }

        @Override public InputStream getInputStream() { return in; }
        @Override public OutputStream getOutputStream() { return out; }
        @Override public synchronized void setSoTimeout(int timeout) { /* sahte */ }
        @Override public synchronized void close() { closed = true; }
        @Override public boolean isClosed() { return closed; }
        String written() { return out.toString(java.nio.charset.StandardCharsets.ISO_8859_1); }
    }

    /** Okumada zaman aşımına düşen sahte soket (banner/HTTP sessiz sunucu). */
    static final class SilentSocket extends Socket {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        @Override public InputStream getInputStream() {
            return new InputStream() {
                @Override public int read() throws IOException { throw new java.net.SocketTimeoutException("Read timed out"); }
                @Override public int read(byte[] b, int off, int len) throws IOException { throw new java.net.SocketTimeoutException("Read timed out"); }
            };
        }
        @Override public OutputStream getOutputStream() { return out; }
        @Override public synchronized void setSoTimeout(int timeout) { /* sahte */ }
        @Override public synchronized void close() { /* sahte */ }
    }

    static Function<String, ProcessProbe.Result> ok(String out) { return a -> new ProcessProbe.Result(out, 0, false); }
}
