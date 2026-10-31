package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.TrustEvaluator;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.net.IDN;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * İzlenen veritabanlarına TLS güveni (r177, H-3): JDBC sürücüleri sınıfı ADIYLA (yansımayla) kurar, Spring çekirdeğine
 * erişemez — bu bileşen açılışta {@link TrustEvaluator}'ı (JVM cacerts + Ayarlar'daki kurum CA paketi, canlı yeniden
 * yükleme) statik bir tutucuya koyar; {@link DbTrustSslSocketFactory} (PostgreSQL {@code sslfactory}) ve
 * {@link DbTrustManager} (MSSQL {@code trustManagerClass}, Oracle TCPS) güveni buradan alır. Tutucu boşsa (bileşen
 * kurulmamış dilim testleri) JVM varsayılan güveni kullanılır — asla "her şeye güven".
 *
 * <p>Ad doğrulaması {@link #matchesHost} — RFC 6125: SAN dNSName (joker yalnız en soldaki tam etiket, alt alan değil), IP
 * hedefte SAN iPAddress; SAN dNSName yoksa CN. Büyük-küçük harf duyarsız, IDN ASCII biçimiyle.
 */
@Component
public class DbTls {

    private static volatile TrustEvaluator trust;

    public DbTls(TrustEvaluator evaluator) {
        trust = evaluator;
    }

    /** Testler: tutucuyu değiştirir (null = JVM varsayılanı). */
    static void setTrustForTests(TrustEvaluator evaluator) {
        trust = evaluator;
    }

    /** Zincir güveni: kurum CA paketi + JVM cacerts (canlı); tutucu yoksa yalnız JVM cacerts. */
    static X509TrustManager chainTrust() {
        TrustEvaluator t = trust;
        if (t != null) return t.compositeTrustManager();
        return jvmDefault();
    }

    /** Zincir güveniyle kurulmuş TLS bağlamı (sürücüler bunun soket fabrikasını kullanır). */
    static SSLContext sslContext(X509TrustManager tm) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("TLS bağlamı kurulamadı", e);
        }
    }

    private static X509TrustManager jvmDefault() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (TrustManager m : tmf.getTrustManagers()) if (m instanceof X509TrustManager x) return x;
        } catch (Exception ignored) {
            // aşağıda reddeden yönetici döner
        }
        return new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException {
                throw new CertificateException("güven deposu yok");
            }
            public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException {
                throw new CertificateException("güven deposu yok");
            }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }

    // ── Ad doğrulaması ───────────────────────────────────────────────────────

    /** Sertifika beklenen sunucu adına (ya da IP'ye) ait mi — RFC 6125 kuralları (sınıf belgesi). */
    public static boolean matchesHost(X509Certificate cert, String host) {
        if (cert == null || host == null || host.isBlank()) return false;
        String h = normalizeHost(host);
        if (h == null) return false;
        boolean ip = isIpLiteral(h);
        Collection<List<?>> sans;
        try {
            sans = cert.getSubjectAlternativeNames();
        } catch (CertificateParsingException e) {
            return false;
        }
        boolean anyDns = false;
        if (sans != null) {
            for (List<?> san : sans) {
                if (san == null || san.size() < 2 || !(san.get(0) instanceof Integer type)) continue;
                Object v = san.get(1);
                if (!(v instanceof String value)) continue;
                if (type == 2) {
                    anyDns = true;
                    if (!ip && dnsMatches(value, h)) return true;
                } else if (type == 7 && ip && sameIp(value, h)) {
                    return true;
                }
            }
        }
        if (ip || anyDns) return false;
        String cn = commonName(cert);
        return cn != null && dnsMatches(cn, h);
    }

    static String normalizeHost(String host) {
        String h = host.trim();
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.isEmpty() || h.length() > 255) return null;
        if (isIpLiteral(h)) return h.toLowerCase(Locale.ROOT);
        try {
            return IDN.toASCII(h, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean isIpLiteral(String h) {
        if (h.indexOf(':') >= 0) return true;
        int dots = 0;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (c == '.') dots++;
            else if (c < '0' || c > '9') return false;
        }
        return dots == 3;
    }

    private static boolean sameIp(String a, String b) {
        try {
            return java.net.InetAddress.ofLiteral(a).equals(java.net.InetAddress.ofLiteral(b));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** {@code *.ornek.com} yalnız TEK etiketi karşılar ({@code a.ornek.com} evet, {@code a.b.ornek.com} ve {@code ornek.com} hayır). */
    static boolean dnsMatches(String pattern, String host) {
        String p;
        try {
            String raw = pattern.trim();
            if (raw.endsWith(".")) raw = raw.substring(0, raw.length() - 1);
            p = raw.startsWith("*.") ? "*." + IDN.toASCII(raw.substring(2), IDN.ALLOW_UNASSIGNED)
                    : IDN.toASCII(raw, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            return false;
        }
        p = p.toLowerCase(Locale.ROOT);
        if (!p.startsWith("*.")) return p.equals(host);
        String suffix = p.substring(1);   // ".ornek.com"
        if (suffix.indexOf('*') >= 0 || suffix.chars().filter(c -> c == '.').count() < 2) return false;   // "*.com" yok
        if (!host.endsWith(suffix)) return false;
        String label = host.substring(0, host.length() - suffix.length());
        return !label.isEmpty() && label.indexOf('.') < 0;
    }

    private static String commonName(X509Certificate cert) {
        try {
            javax.naming.ldap.LdapName dn = new javax.naming.ldap.LdapName(cert.getSubjectX500Principal().getName());
            String cn = null;
            for (javax.naming.ldap.Rdn r : dn.getRdns()) {
                if ("CN".equalsIgnoreCase(r.getType())) cn = String.valueOf(r.getValue());
            }
            return cn;
        } catch (Exception e) {
            return null;
        }
    }
}
