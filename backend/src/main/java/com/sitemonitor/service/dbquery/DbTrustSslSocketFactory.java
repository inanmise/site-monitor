package com.sitemonitor.service.dbquery;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Properties;

/**
 * PostgreSQL {@code sslfactory} (r177, H-3): sunucu zincirini kurum CA paketi + JVM cacerts ile doğrulayan TLS soketleri
 * ({@link DbTls#chainTrust}). Yalnız {@code verify-ca} / {@code verify-full} kiplerinde kurulur ({@link DbJdbcUrls});
 * {@code verify-full}'da ANA MAKİNE ADI denetimini sürücü ayrıca yapar (pgjdbc {@code MakeSSL.verifyPeerName}, fabrikadan
 * bağımsız). pgjdbc sınıfı ADIYLA kurar: {@code Properties} yapıcısı (önce denenir) ya da argümansız yapıcı.
 *
 * <p>Neden: pgjdbc'nin varsayılan fabrikası ({@code LibPQFactory}) doğrulamada {@code ~/.postgresql/root.crt} dosyasını
 * okur; konteynerde dosya yok, kök dosya sistemi salt-okur — {@code verify-full} bugüne dek hiç çalışamıyordu.
 */
public class DbTrustSslSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    public DbTrustSslSocketFactory() {
        this.delegate = DbTls.sslContext(DbTls.chainTrust()).getSocketFactory();
    }

    /** pgjdbc {@code ObjectFactory} önce bu yapıcıyı dener. */
    public DbTrustSslSocketFactory(Properties info) {
        this();
    }

    @Override
    public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }

    @Override
    public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return delegate.createSocket(s, host, port, autoClose);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return delegate.createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return delegate.createSocket(address, port, localAddress, localPort);
    }
}
