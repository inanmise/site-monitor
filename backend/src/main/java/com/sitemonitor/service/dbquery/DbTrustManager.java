package com.sitemonitor.service.dbquery;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Zincir + ANA MAKİNE ADI doğrulayan güven yöneticisi (r177, H-3): MSSQL {@code trustManagerClass} (yapıcı argümanı =
 * beklenen sunucu adı, {@code trustManagerConstructorArg}) ve Oracle TCPS bağlamı. Zincir {@link DbTls#chainTrust} (kurum
 * CA paketi + JVM cacerts) ile, ad {@link DbTls#matchesHost} ile denetlenir — sürücünün kendi ad denetimi özel güven
 * yöneticisinde devreye girmeyebilir, bu yüzden burada yapılır. Bağlantı SSRF denetiminden geçmiş IP'ye gider; sertifika
 * kullanıcının yazdığı ADA göre doğrulanır.
 */
public class DbTrustManager implements X509TrustManager {

    private final String expectedHost;
    private final X509TrustManager chain;

    /** @param expectedHost sertifikada beklenen sunucu adı (MSSQL {@code trustManagerConstructorArg}) */
    public DbTrustManager(String expectedHost) {
        this(expectedHost, DbTls.chainTrust());
    }

    DbTrustManager(String expectedHost, X509TrustManager chain) {
        if (expectedHost == null || expectedHost.isBlank()) throw new IllegalArgumentException("beklenen sunucu adı yok");
        this.expectedHost = expectedHost.trim();
        this.chain = chain;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
        if (certs == null || certs.length == 0) throw new CertificateException("sunucu sertifika göndermedi");
        com.sitemonitor.service.TrustEvaluator.checkServerTrustedAnyAuthType(chain, certs);
        if (!DbTls.matchesHost(certs[0], expectedHost)) {
            throw new CertificateException("sunucu sertifikası '" + expectedHost + "' adına ait değil");
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
        throw new CertificateException("yalnız sunucu doğrulaması");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return chain == null ? new X509Certificate[0] : chain.getAcceptedIssuers();
    }
}
