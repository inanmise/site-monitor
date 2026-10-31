package com.sitemonitor.service.dbquery;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Veritabanı TLS güveni (r177, H-3): RFC 6125 ad eşleşmesi (joker yalnız tek sol etiket, IP için SAN iPAddress, SAN dNS
 * yoksa CN), zincir + ad doğrulayan güven yöneticisi, PostgreSQL {@code sslfactory} sınıfının iki yapıcısı.
 */
class DbTlsTest {

    private static KeyPair kp;

    @BeforeAll
    static void keys() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        kp = g.generateKeyPair();
    }

    @AfterEach
    void reset() {
        DbTls.setTrustForTests(null);
    }

    private static X509Certificate cert(String cn, GeneralName... sans) throws Exception {
        X500Name name = new X500Name("CN=" + cn);
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(System.nanoTime()),
                Date.from(now.minus(1, ChronoUnit.HOURS)), Date.from(now.plus(40, ChronoUnit.DAYS)), name, kp.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        if (sans.length > 0) b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sans));
        return new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(b.build(new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(kp.getPrivate())));
    }

    private static GeneralName dns(String n) { return new GeneralName(GeneralName.dNSName, n); }

    private static GeneralName ip(String n) { return new GeneralName(GeneralName.iPAddress, n); }

    @Test
    void sanDnsRules() throws Exception {
        X509Certificate c = cert("ignored.example", dns("db.ornek.com"), dns("*.prod.ornek.com"));
        assertThat(DbTls.matchesHost(c, "db.ornek.com")).isTrue();
        assertThat(DbTls.matchesHost(c, "DB.Ornek.COM.")).isTrue();
        assertThat(DbTls.matchesHost(c, "a.prod.ornek.com")).isTrue();
        assertThat(DbTls.matchesHost(c, "a.b.prod.ornek.com")).isFalse();   // joker tek etiket
        assertThat(DbTls.matchesHost(c, "prod.ornek.com")).isFalse();
        assertThat(DbTls.matchesHost(c, "other.ornek.com")).isFalse();
        // SAN dNS varken CN'e bakılmaz
        assertThat(DbTls.matchesHost(c, "ignored.example")).isFalse();
        assertThat(DbTls.matchesHost(c, null)).isFalse();
        assertThat(DbTls.matchesHost(c, " ")).isFalse();
    }

    @Test
    void wildcardNeverCoversTopLevel() throws Exception {
        X509Certificate c = cert("x", dns("*.com"));
        assertThat(DbTls.matchesHost(c, "ornek.com")).isFalse();
        assertThat(DbTls.dnsMatches("*.ornek.com", "a.ornek.com")).isTrue();
        assertThat(DbTls.dnsMatches("*.*.ornek.com", "a.b.ornek.com")).isFalse();
    }

    @Test
    void ipTargetsNeedSanIp() throws Exception {
        X509Certificate c = cert("10.0.0.5", dns("db.ornek.com"), ip("10.0.0.5"));
        assertThat(DbTls.matchesHost(c, "10.0.0.5")).isTrue();
        assertThat(DbTls.matchesHost(c, "10.0.0.6")).isFalse();
        // IP hedefte CN kullanılmaz
        X509Certificate cnOnly = cert("10.0.0.5");
        assertThat(DbTls.matchesHost(cnOnly, "10.0.0.5")).isFalse();
        assertThat(DbTls.isIpLiteral("10.0.0.5")).isTrue();
        assertThat(DbTls.isIpLiteral("::1")).isTrue();
        assertThat(DbTls.isIpLiteral("db1")).isFalse();
    }

    @Test
    void cnFallbackOnlyWithoutSanDns() throws Exception {
        X509Certificate c = cert("legacy.ornek.com");
        assertThat(DbTls.matchesHost(c, "legacy.ornek.com")).isTrue();
        assertThat(DbTls.matchesHost(c, "x.ornek.com")).isFalse();
    }

    @Test
    void trustManagerChecksChainAndName() throws Exception {
        X509Certificate c = cert("db.ornek.com", dns("db.ornek.com"));
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("db", c);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        X509TrustManager found = null;
        for (TrustManager m : tmf.getTrustManagers()) if (m instanceof X509TrustManager x) found = x;
        final X509TrustManager chain = found;

        new DbTrustManager("db.ornek.com", chain).checkServerTrusted(new X509Certificate[]{c}, "UNKNOWN");
        assertThatThrownBy(() -> new DbTrustManager("baska.ornek.com", chain).checkServerTrusted(new X509Certificate[]{c}, "UNKNOWN"))
                .isInstanceOf(CertificateException.class).hasMessageContaining("baska.ornek.com");
        // güvenilmeyen zincir (boş depo) reddedilir
        KeyStore empty = KeyStore.getInstance(KeyStore.getDefaultType());
        empty.load(null, null);
        empty.setCertificateEntry("other", cert("other"));
        TrustManagerFactory t2 = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        t2.init(empty);
        X509TrustManager none = (X509TrustManager) t2.getTrustManagers()[0];
        assertThatThrownBy(() -> new DbTrustManager("db.ornek.com", none).checkServerTrusted(new X509Certificate[]{c}, "UNKNOWN"))
                .isInstanceOf(CertificateException.class);
        assertThatThrownBy(() -> new DbTrustManager("db.ornek.com", chain).checkServerTrusted(new X509Certificate[0], "UNKNOWN"))
                .isInstanceOf(CertificateException.class);
        assertThatThrownBy(() -> new DbTrustManager(" ", chain)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DbTrustManager("db.ornek.com", chain).checkClientTrusted(new X509Certificate[]{c}, "RSA"))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    void socketFactoryConstructorsUseJvmTrustWithoutBean() {
        // Spring bileşeni yokken JVM cacerts (asla "her şeye güven")
        assertThat(new DbTrustSslSocketFactory().getDefaultCipherSuites()).isNotEmpty();
        assertThat(new DbTrustSslSocketFactory(new Properties()).getSupportedCipherSuites()).isNotEmpty();
        assertThat(DbTls.chainTrust().getAcceptedIssuers()).isNotEmpty();
    }
}
