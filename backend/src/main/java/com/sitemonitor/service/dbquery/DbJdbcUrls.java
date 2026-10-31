package com.sitemonitor.service.dbquery;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/**
 * JDBC bağlantı adresi ve sürücü özellikleri (2026-10-31). URL kullanıcıdan ALINMAZ: tür, SSRF denetiminden geçmiş IP,
 * port ve doğrulanmış veritabanı / servis adından kurulur; kimlik ve tüm ayarlar {@link Properties} ile verilir — kullanıcı
 * değeri URL'e parametre ekleyemez ({@code ;}, {@code ?}, {@code &}, parantez taşıyan değerler reddedilir).
 *
 * <p>TLS ad doğrulaması: PostgreSQL {@code verify-full} kipinde URL'de ana makine ADI kalır (sertifika adı bağlar);
 * MSSQL'de şifreli + sertifikaya güvenilmeyen bağlantıda {@code hostNameInCertificate} = ana makine adıdır. Diğer durumlarda
 * doğrulanmış IP'ye bağlanılır (DNS yeniden bağlama kapanır).
 */
final class DbJdbcUrls {

    /** URL'e girer: yalnız güvenli karakterler (sözleşme deseni {@code [A-Za-z0-9_.$#-]{1,128}}). */
    static final int MAX_NAME = 128, MAX_INSTANCE = 64;
    static final String APP_NAME = DbConnectionSpec.DEFAULT_APP_NAME;

    /** Oracle TCPS varsayılan portu (r177). */
    static final int ORACLE_TCPS_PORT = 2484;

    /**
     * Kurulmuş bağlantı: URL + özellikler (parola içerir — günlüğe YAZILMAZ). {@code tcps} yalnız Oracle TCPS'te dolu: URL'de
     * sunucu ADI kalır (sertifika adı), çözüm doğrulanmış IP'ye kilitlenir ve TLS bağlamı kurum CA paketiyle kurulur —
     * bağlantı {@code DriverManager} yerine sürücünün bağlantı kurucusuyla açılır ({@link DbQueryRunner}).
     */
    record Target(String url, Properties props, OracleTcps tcps) {
        Target(String url, Properties props) {
            this(url, props, null);
        }

        @Override
        public String toString() {
            return "Target[" + url + "]";
        }
    }

    /** @param host sertifikada beklenen ad (URL'deki HOST); @param pinned yalnız buna çözülür (SSRF denetiminden geçmiş IP). */
    record OracleTcps(String host, InetAddress pinned) {}

    private DbJdbcUrls() {}

    /**
     * @param ip             SSRF denetiminden dönen adres (bağlanılacak olan)
     * @param timeoutSeconds sorgu zaman aşımı (okuma zaman aşımı bunun 10 sn fazlası)
     */
    static Target build(DbConnectionSpec spec, InetAddress ip, int timeoutSeconds) {
        if (spec == null || spec.type() == null) throw new IllegalArgumentException("bağlantı türü yok");
        if (spec.port() < 1 || spec.port() > 65_535) throw new IllegalArgumentException("geçersiz port");
        int connectMs = clamp(spec.connectTimeoutMs() <= 0 ? 10_000 : spec.connectTimeoutMs(), 1_000, 30_000);
        int connectSec = (connectMs + 999) / 1_000;
        int readSec = Math.max(1, timeoutSeconds) + 10;
        Properties p = new Properties();
        if (spec.username() != null) p.setProperty("user", spec.username());
        if (spec.password() != null) p.setProperty("password", spec.password());
        return switch (spec.type()) {
            case POSTGRESQL -> postgres(spec, ip, p, connectSec, readSec);
            case MSSQL -> mssql(spec, ip, p, connectSec, readSec);
            case ORACLE -> oracle(spec, ip, p, connectMs, readSec);
        };
    }

    private static Target postgres(DbConnectionSpec spec, InetAddress ip, Properties p, int connectSec, int readSec) {
        String db = requireName(spec.database(), true);
        String ssl = spec.sslMode() == null || spec.sslMode().isBlank() ? "prefer"
                : spec.sslMode().trim().toLowerCase(Locale.ROOT);
        if (!ssl.equals("disable") && !ssl.equals("prefer") && !ssl.equals("require") && !ssl.equals("verify-full")
                && !ssl.equals("verify-ca") && !ssl.equals("allow"))
            throw new IllegalArgumentException("geçersiz ssl kipi");
        boolean verifyFull = ssl.equals("verify-full");
        boolean verifyChain = verifyFull || ssl.equals("verify-ca");
        String host = verifyFull ? requireHostName(spec.host()) : hostLiteral(ip);
        String url = "jdbc:postgresql://" + host + ":" + spec.port() + "/"
                + URLEncoder.encode(db, StandardCharsets.UTF_8);
        if (verifyFull) {
            // Ad URL'de kalır (sertifika adı + SNI), ama bağlantı YALNIZ doğrulanmış IP'ye gider — DNS yeniden bağlama kapalı
            p.setProperty("socketFactory", PinnedAddressSocketFactory.class.getName());
            p.setProperty(PinnedAddressSocketFactory.ARG, bareAddress(ip));
        }
        p.setProperty("loginTimeout", String.valueOf(connectSec));
        p.setProperty("connectTimeout", String.valueOf(connectSec));
        p.setProperty("socketTimeout", String.valueOf(readSec));
        p.setProperty("readOnly", "true");
        p.setProperty("ApplicationName", spec.safeAppName());
        p.setProperty("sslmode", ssl);
        // Doğrulamalı kiplerde zincir kurum CA paketi + JVM cacerts ile doğrulanır (r177, H-3): sürücünün varsayılanı
        // ~/.postgresql/root.crt okur — konteynerde yok, verify-full hiç çalışamıyordu. Ad denetimini sürücü ayrıca yapar.
        if (verifyChain) p.setProperty("sslfactory", DbTrustSslSocketFactory.class.getName());
        // Sunucu tarafı hazır ifade önbelleği yok (her çalıştırma taze bağlantı); genişletilmiş protokol kalır — satır
        // sınırı ve parça parça okuma (fetchSize) yalnız onunla çalışır
        p.setProperty("prepareThreshold", "0");
        return new Target(url, p);
    }

    private static Target mssql(DbConnectionSpec spec, InetAddress ip, Properties p, int connectSec, int readSec) {
        String db = requireName(spec.database(), true);
        String instance = spec.mssqlInstance() == null || spec.mssqlInstance().isBlank() ? null
                : requireName(spec.mssqlInstance(), false);
        p.setProperty("serverName", ip.getHostAddress());
        p.setProperty("portNumber", String.valueOf(spec.port()));
        p.setProperty("databaseName", db);
        if (instance != null) p.setProperty("instanceName", instance);
        p.setProperty("loginTimeout", String.valueOf(connectSec));
        p.setProperty("socketTimeout", String.valueOf(readSec * 1_000));
        p.setProperty("lockTimeout", "2000");
        p.setProperty("applicationIntent", "ReadOnly");
        p.setProperty("applicationName", spec.safeAppName());
        p.setProperty("encrypt", String.valueOf(spec.mssqlEncrypt()));
        p.setProperty("trustServerCertificate", String.valueOf(spec.mssqlTrustServerCertificate()));
        if (spec.mssqlEncrypt() && !spec.mssqlTrustServerCertificate()) {
            String name = requireHostName(spec.host());
            p.setProperty("hostNameInCertificate", name);
            // Zincir kurum CA paketi + JVM cacerts, AD da bizim güven yöneticimizde denetlenir (r177, H-3): iç CA'lı sunucu
            // için "sertifikaya güven" açmak zorunda kalınmasın. Argüman = beklenen ad (IPv6 köşeli parantezsiz).
            p.setProperty("trustManagerClass", DbTrustManager.class.getName());
            p.setProperty("trustManagerConstructorArg", bareHost(name));
        }
        // Adres URL'de değil özellikte: IPv6 ve örnek adı (ad\örnek) kaçış derdi olmadan geçer
        return new Target("jdbc:sqlserver://", p);
    }

    private static Target oracle(DbConnectionSpec spec, InetAddress ip, Properties p, int connectMs, int readSec) {
        String name = requireName(spec.database(), false);
        boolean sid = "SID".equalsIgnoreCase(spec.oracleMode() == null ? "" : spec.oracleMode().trim());
        String data = sid ? "(SID=" + name + ")" : "(SERVICE_NAME=" + name + ")";
        boolean tcps = spec.oracleTcps();
        // TCPS (r177): HOST = sunucu ADI (sertifika DN / SAN eşleşmesi) — çözüm kurucuda doğrulanmış IP'ye kilitlenir
        String host = tcps ? bareHost(requireHostName(spec.host())) : ip.getHostAddress();
        String url = "jdbc:oracle:thin:@(DESCRIPTION=(CONNECT_TIMEOUT=" + Math.max(1, connectMs / 1_000)
                + ")(ADDRESS=(PROTOCOL=" + (tcps ? "TCPS" : "TCP") + ")(HOST=" + host + ")(PORT=" + spec.port()
                + "))(CONNECT_DATA=" + data + ")"
                + (tcps ? "(SECURITY=(SSL_SERVER_DN_MATCH=TRUE))" : "") + ")";
        p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(connectMs));
        p.setProperty("oracle.jdbc.ReadTimeout", String.valueOf(readSec * 1_000));
        p.setProperty("v$session.program", spec.safeAppName());
        if (tcps) {
            p.setProperty("oracle.net.ssl_server_dn_match", "true");
            return new Target(url, p, new OracleTcps(host, ip));
        }
        return new Target(url, p);
    }

    /** Ad doğrulamasının köşeli parantezsiz biçimi (IPv6 sertifika eşleşmesi düz yazımla yapılır). */
    static String bareHost(String name) {
        if (name == null) return null;
        return name.startsWith("[") && name.endsWith("]") ? name.substring(1, name.length() - 1) : name;
    }

    /** IPv6 köşeli parantezle; bölge kimliği atılır. */
    static String hostLiteral(InetAddress ip) {
        String h = bareAddress(ip);
        return ip instanceof Inet6Address ? "[" + h + "]" : h;
    }

    /** Yalın IP yazımı (bölge kimliği yok, köşeli parantez yok). */
    static String bareAddress(InetAddress ip) {
        String h = ip.getHostAddress();
        int z = h.indexOf('%');
        return z >= 0 ? h.substring(0, z) : h;
    }

    /** Veritabanı / servis / örnek adı: yalnız {@code [A-Za-z0-9_.$#-]} — URL'e ya da tanımlayıcıya parametre sızamaz. */
    static String requireName(String raw, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) throw new IllegalArgumentException("veritabanı adı yok");
            throw new IllegalArgumentException("ad yok");
        }
        String s = raw.trim();
        if (s.length() > MAX_NAME) throw new IllegalArgumentException("ad çok uzun");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '$' || c == '#' || c == '-';
            if (!ok) throw new IllegalArgumentException("ad geçersiz karakter içeriyor");
        }
        return s;
    }

    /** TLS ad doğrulaması için ana makine adı (harf, rakam, nokta, tire; IPv6 için iki nokta). */
    static String requireHostName(String raw) {
        if (raw == null || raw.isBlank() || raw.trim().length() > 255) throw new IllegalArgumentException("ana makine yok");
        String s = raw.trim();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_' || c == ':';
            if (!ok) throw new IllegalArgumentException("ana makine adı geçersiz");
        }
        return s.indexOf(':') >= 0 ? "[" + s + "]" : s;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
