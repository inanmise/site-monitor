package com.sitemonitor.service.dbquery;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Properties;

/**
 * Bağlantıyı SSRF denetiminden geçmiş TEK bir IP'ye kilitleyen soket fabrikası (bağımsız inceleme O2, 2026-10-31).
 *
 * <p>PostgreSQL {@code sslmode=verify-full} kipinde sertifika ANA MAKİNE ADINA göre doğrulanır (SNI + ad denetimi), bu yüzden
 * URL'de ad kalmalıdır; ama sürücü adı bağlanırken YENİDEN çözer — doğrulama ile bağlantı arasında DNS kaydı değişirse
 * (yeniden bağlama) bağlantı iç adrese / bulut üst verisine gidebilirdi. pgjdbc bu sınıfı {@code socketFactory} özelliğiyle
 * kurar ({@code Properties} yapıcısı); {@value #ARG} özelliği doğrulanmış IP'dir (yalın IP yazımı — {@link
 * InetAddress#ofLiteral} DNS'e GİTMEZ). Sürücünün verdiği adresin ana makinesi yok sayılır, YALNIZ portu kullanılır; TLS
 * katmanı sürücünün kendi fabrikasıyla bu soketin üstüne ad doğrulamasıyla kurulur.
 *
 * <p>Özellikler yalnız {@link DbJdbcUrls} tarafından kurulur; kullanıcı alanları özellik ekleyemez.
 */
public final class PinnedAddressSocketFactory extends SocketFactory {

    /** pgjdbc'nin soket fabrikası argüman özelliği — doğrulanmış IP (yalın yazım). */
    public static final String ARG = "socketFactoryArg";

    private final InetAddress pinned;

    /** pgjdbc yansıma ile bu yapıcıyı çağırır. IP yoksa / yalın IP değilse fırlatır (bağlantı kurulmaz). */
    public PinnedAddressSocketFactory(Properties info) {
        String raw = info == null ? null : info.getProperty(ARG);
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("kilitli IP verilmedi");
        this.pinned = InetAddress.ofLiteral(raw.trim());
    }

    InetAddress pinned() {
        return pinned;
    }

    @Override
    public Socket createSocket() {
        return new PinnedSocket(pinned);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket s = createSocket();
        s.connect(new InetSocketAddress(pinned, port));
        return s;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket s = createSocket();
        s.bind(new InetSocketAddress(localHost, localPort));
        s.connect(new InetSocketAddress(pinned, port));
        return s;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return createSocket((String) null, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return createSocket((String) null, port, localAddress, localPort);
    }

    /** Hedefin ana makinesini yok sayıp kilitli IP'ye bağlanan soket (port korunur). */
    static final class PinnedSocket extends Socket {
        private final InetAddress pinned;

        PinnedSocket(InetAddress pinned) {
            this.pinned = pinned;
        }

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            connect(endpoint, 0);
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (!(endpoint instanceof InetSocketAddress isa)) throw new IOException("desteklenmeyen adres türü");
            super.connect(new InetSocketAddress(pinned, isa.getPort()), timeout);
        }
    }
}
