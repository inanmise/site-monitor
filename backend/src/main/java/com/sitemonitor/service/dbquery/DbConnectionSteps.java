package com.sitemonitor.service.dbquery;

import com.sitemonitor.util.CauseChain;
import com.sitemonitor.util.Msg;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bağlantı denemesinin ADIM ADIM teşhisi (2026-11-05, dbq2 A3): DNS → TCP → TLS → oturum açma → {@code SELECT 1} → hesap
 * yetkileri. Saf yardımcı — adım kayıtları, kodlar ve hata → adım eşlemesi; ağ işini {@link DbQueryRunner} yapar.
 *
 * <p>Kodlar KALICIDIR (ön yüz çare metinleri {@code dbq.conn.fix.<KOD>}). Kural: ilk {@code fail} adımından sonraki adımlar
 * {@code skip}; deneme hiç başlamadıysa (bağlantı meşgul, parola çözülemedi …) altı adım da {@code skip} — neden üst düzey
 * hatadadır. {@code detail} sunucu metnidir, her zaman maskelenmiş hata metninden ({@link DbQueryErrors#mask}) gelir: parola,
 * kullanıcı adı ve veri değeri taşımaz.
 */
public final class DbConnectionSteps {

    public static final String DNS = "dns", TCP = "tcp", TLS = "tls", LOGIN = "login", QUERY = "query",
            PRIVILEGES = "privileges";
    /** Adımların SIRASI kalıcıdır. */
    public static final List<String> KEYS = List.of(DNS, TCP, TLS, LOGIN, QUERY, PRIVILEGES);

    public static final String OK = "ok", FAIL = "fail", WARN = "warn", SKIP = "skip";

    /** TCP adımı kodları. */
    public static final String TCP_REFUSED = "TCP_REFUSED", TCP_TIMEOUT = "TCP_TIMEOUT", TCP_UNREACHABLE = "TCP_UNREACHABLE";
    /** TLS adımı başarısızlık kodları (uyarı kodları {@link DbConnectionProfileService#SECURITY_WARNING_CODES}). */
    public static final String TLS_CERT = "TLS_CERT", TLS_FAILED = "TLS_FAILED";
    /** Oturum açma: veritabanı / servis / SID yok. */
    public static final String DB_NOT_FOUND = "DB_NOT_FOUND";
    /** Yetki adımı kodları. */
    public static final String ACCOUNT_WRITABLE = "ACCOUNT_WRITABLE", CHECK_FAILED = DbAccountPrivileges.CHECK_FAILED,
            NOT_CHECKED = "NOT_CHECKED";

    /** Adımların kullanabileceği bütün kodlar (ön yüz kapısı bununla eşleşir). */
    public static final List<String> CODES = List.of(DbQueryErrors.DB_DNS, DbQueryErrors.DB_BLOCKED, TCP_REFUSED,
            TCP_TIMEOUT, TCP_UNREACHABLE, DbQueryErrors.DB_DRIVER, TLS_CERT, TLS_FAILED,
            DbConnectionProfileService.SEC_SSL_DISABLED, DbConnectionProfileService.SEC_SSL_PREFER,
            DbConnectionProfileService.SEC_SSL_NOT_VERIFIED, DbConnectionProfileService.SEC_MSSQL_UNENCRYPTED,
            DbConnectionProfileService.SEC_MSSQL_TRUST_SERVER_CERT, DbQueryErrors.DB_AUTH, DB_NOT_FOUND,
            DbQueryErrors.DB_TIMEOUT, DbQueryErrors.DB_UNREACHABLE, DbQueryErrors.DB_SQL_ERROR, ACCOUNT_WRITABLE,
            CHECK_FAILED, NOT_CHECKED);

    /**
     * @param key    {@link #KEYS}
     * @param status {@link #OK} | {@link #FAIL} | {@link #WARN} | {@link #SKIP}
     * @param ms     adımın süresi (ölçülmediyse null)
     * @param code   kalıcı kod ya da null
     * @param detail kısa, maskeli sunucu metni ya da null
     */
    public record Step(String key, String status, Long ms, String code, String detail) {
        public Map<String, Object> toApi() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("status", status);
            m.put("ms", ms);
            m.put("code", code);
            m.put("detail", detail);
            return m;
        }
    }

    private DbConnectionSteps() {}

    /** Altı adımın tamamı {@code skip} (deneme başlamadı — neden üst düzey hatada). */
    public static List<Step> allSkipped() {
        List<Step> out = new ArrayList<>(KEYS.size());
        for (String k : KEYS) out.add(new Step(k, SKIP, null, null, null));
        return List.copyOf(out);
    }

    /** Verilen adımların ardına eksik adımları {@code skip} olarak ekler (ilk başarısızlıktan sonrası). */
    public static List<Step> completed(List<Step> done) {
        List<Step> out = new ArrayList<>(KEYS.size());
        if (done != null) out.addAll(done);
        for (int i = out.size(); i < KEYS.size(); i++) out.add(new Step(KEYS.get(i), SKIP, null, null, null));
        return List.copyOf(out);
    }

    /** API biçimi; null → altı adım {@code skip}. */
    public static List<Map<String, Object>> toApi(List<Step> steps) {
        List<Step> s = steps == null || steps.isEmpty() ? allSkipped() : completed(steps);
        List<Map<String, Object>> out = new ArrayList<>(s.size());
        for (Step x : s) out.add(x.toApi());
        return out;
    }

    // ── TCP ──────────────────────────────────────────────────────────────────

    /** Soket hatası → TCP kodu: ret {@link #TCP_REFUSED}, zaman aşımı {@link #TCP_TIMEOUT}, diğerleri {@link #TCP_UNREACHABLE}. */
    public static String tcpCode(Throwable e) {
        Throwable t = e;
        for (int causeDepth = 0; t != null && causeDepth < CauseChain.MAX_DEPTH; causeDepth++, t = t.getCause()) {
            if (t instanceof SocketTimeoutException) return TCP_TIMEOUT;
            if (t instanceof NoRouteToHostException) return TCP_UNREACHABLE;
            if (t instanceof ConnectException) {
                String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
                return m.contains("timed out") ? TCP_TIMEOUT : TCP_REFUSED;
            }
            if (t.getCause() == t) break;
        }
        return TCP_UNREACHABLE;
    }

    /** TCP başarısızlığının kullanıcı metni ({@code adres} = IP:port — kimlik / veri yok). */
    public static String tcpMessage(String code, String address) {
        String a = address == null ? "" : " (" + address + ")";
        return switch (code) {
            case TCP_REFUSED -> Msg.t("Veritabanı sunucusunun portuna bağlanılamadı" + a + " — bağlantı reddedildi · Sunucu bu "
                    + "portu dinlemiyor ya da güvenlik duvarı reddediyor · Port numarasını ve veritabanı servisinin çalıştığını "
                    + "denetleyin.", "Could not connect to the database server port" + a + " — connection refused · The server "
                    + "is not listening on this port or a firewall rejects it · Check the port number and that the database "
                    + "service is running.");
            case TCP_TIMEOUT -> Msg.t("Veritabanı sunucusunun portuna bağlanılamadı" + a + " — zaman aşımı · Güvenlik duvarı "
                    + "paketleri yanıtsız düşürüyor olabilir · Site Monitor'den bu porta erişim izni (firewall kuralı) isteyin.",
                    "Could not connect to the database server port" + a + " — timed out · A firewall may be dropping the "
                    + "packets · Ask for a firewall rule that allows Site Monitor to reach this port.");
            default -> Msg.t("Veritabanı sunucusuna ağ üzerinden ulaşılamadı" + a + " · Yönlendirme yok ya da ağ bağlantısı "
                    + "koptu · Ağ ekibiyle sunucuya giden yolu denetleyin.", "The database server could not be reached over the "
                    + "network" + a + " · There is no route or the network connection failed · Check the path to the server "
                    + "with your network team.");
        };
    }

    // ── TLS (başarılı bağlantıda yapılandırmadan) ───────────────────────────

    /**
     * Başarılı bağlantının TLS adımı — yapılandırmadan okunur (sürücü el sıkışmayı oturum açmanın içinde yapar): doğrulamalı
     * kipler {@code ok}; zayıf kipler {@code warn} + bağlantı güvenliği uyarı kodu ({@code security_warnings} ile aynı kodlar).
     * Oracle düz TCP: {@code warn} {@code SSL_DISABLED} (yerel ağ şifrelemesi sunucu ayarıdır — metin söyler).
     */
    public static Step tlsOutcome(DbConnectionSpec spec) {
        DbType type = spec == null ? null : spec.type();
        if (type == null) return new Step(TLS, SKIP, null, null, null);
        return switch (type) {
            case POSTGRESQL -> {
                String ssl = spec.sslMode() == null || spec.sslMode().isBlank() ? "prefer" : spec.sslMode().trim().toLowerCase(Locale.ROOT);
                yield switch (ssl) {
                    case "verify-full", "verify-ca" -> new Step(TLS, OK, null, null, Msg.t(
                            "TLS açık; sunucu sertifikası doğrulandı (" + ssl + ").", "TLS on; the server certificate was verified (" + ssl + ")."));
                    case "require" -> new Step(TLS, WARN, null, DbConnectionProfileService.SEC_SSL_NOT_VERIFIED, Msg.t(
                            "TLS açık ama sunucu sertifikası doğrulanmıyor (require) · Araya giren bir sunucu fark edilmez · "
                                    + "SSL kipini verify-full yapın.", "TLS is on but the server certificate is not verified "
                                    + "(require) · A server in the middle would go unnoticed · Set the SSL mode to verify-full."));
                    case "disable" -> new Step(TLS, WARN, null, DbConnectionProfileService.SEC_SSL_DISABLED, Msg.t(
                            "TLS kapalı (disable) · Parola ve sorgular ağda açık metin gider · SSL kipini require ya da "
                                    + "verify-full yapın.", "TLS is off (disable) · The password and queries travel in clear "
                                    + "text · Set the SSL mode to require or verify-full."));
                    default -> new Step(TLS, WARN, null, DbConnectionProfileService.SEC_SSL_PREFER, Msg.t(
                            "TLS yalnız sunucu destekliyorsa kullanılır (" + ssl + ") · Bağlantı şifresiz kurulmuş olabilir · "
                                    + "SSL kipini verify-full yapın.", "TLS is used only if the server supports it (" + ssl
                                    + ") · The connection may be unencrypted · Set the SSL mode to verify-full."));
                };
            }
            case MSSQL -> {
                if (!spec.mssqlEncrypt()) yield new Step(TLS, WARN, null, DbConnectionProfileService.SEC_MSSQL_UNENCRYPTED, Msg.t(
                        "Şifreleme kapalı · Parola ve sorgular ağda korunmasız gidebilir · Bağlantıda şifrelemeyi açın.",
                        "Encryption is off · The password and queries may travel unprotected · Turn on encryption for the connection."));
                if (spec.mssqlTrustServerCertificate()) yield new Step(TLS, WARN, null,
                        DbConnectionProfileService.SEC_MSSQL_TRUST_SERVER_CERT, Msg.t(
                        "Şifreli ama sunucu sertifikasına doğrulamadan güveniliyor · Araya giren bir sunucu fark edilmez · "
                                + "\"Sunucu sertifikasına güven\" seçeneğini kapatın (kurum CA'sı tanınır).",
                        "Encrypted, but the server certificate is trusted without verification · A server in the middle "
                                + "would go unnoticed · Turn off \"trust server certificate\" (the corporate CA is known)."));
                yield new Step(TLS, OK, null, null, Msg.t("Şifreli; sunucu sertifikası doğrulandı.",
                        "Encrypted; the server certificate was verified."));
            }
            case ORACLE -> spec.oracleTcps()
                    ? new Step(TLS, OK, null, null, Msg.t("TCPS (TLS) açık; sunucu sertifikası ve adı doğrulandı.",
                            "TCPS (TLS) on; the server certificate and name were verified."))
                    : new Step(TLS, WARN, null, DbConnectionProfileService.SEC_SSL_DISABLED, Msg.t(
                            "Bağlantı TCPS (TLS) kullanmıyor · Sunucuda yerel ağ şifrelemesi (NNE) açıksa trafik yine "
                                    + "şifrelidir · Mümkünse bağlantıda TCPS'i açın.", "The connection does not use TCPS (TLS) · "
                                    + "If native network encryption (NNE) is on at the server, the traffic is still encrypted "
                                    + "· Turn on TCPS for the connection if you can."));
        };
    }

    // ── Oturum açma hatası → adım + kod ───────────────────────────────────────

    /** @param step {@link #TLS} ya da {@link #LOGIN}; @param code adımın kodu */
    public record Placement(String step, String code) {}

    /**
     * Sürücü bağlantısının (TLS + oturum açma) hatası hangi adımda ve hangi kodla: zincirde {@code SSLException} /
     * sertifika hatası → TLS ({@link #TLS_CERT} ya da {@link #TLS_FAILED}); kimlik → {@code DB_AUTH}; veritabanı / servis yok
     * → {@link #DB_NOT_FOUND}; sertifika / güvenli bağlantı sözcükleri (MSSQL sarmalı) → TLS; diğerleri sınıflandırılmış kod.
     *
     * @param classified {@link DbQueryErrors#classify} sonucu (üst düzey {@code failure_reason} — değişmez)
     */
    public static Placement loginFailure(Throwable e, String classified) {
        boolean ssl = false, cert = false, notFound = false;
        StringBuilder text = new StringBuilder();
        Throwable t = e;
        for (int causeDepth = 0; t != null && causeDepth < CauseChain.MAX_DEPTH; causeDepth++, t = t.getCause()) {
            if (t instanceof javax.net.ssl.SSLException) ssl = true;
            if (t instanceof java.security.cert.CertificateException || t instanceof java.security.cert.CertPathValidatorException
                    || t instanceof javax.net.ssl.SSLPeerUnverifiedException
                    || t instanceof java.security.cert.CertPathBuilderException) {
                ssl = true;
                cert = true;
            }
            if (t instanceof SQLException se) {
                String st = se.getSQLState() == null ? "" : se.getSQLState().trim().toUpperCase(Locale.ROOT);
                int v = se.getErrorCode();
                if ("3D000".equals(st) || v == 4060 || v == 12514 || v == 12505) notFound = true;
            }
            String m = t.getMessage();
            if (m != null && text.length() < 4_000) text.append(' ').append(m.length() > 1_000 ? m.substring(0, 1_000) : m);
            if (t.getCause() == t) break;
        }
        String low = text.toString().toLowerCase(Locale.ROOT);
        if (low.contains("ora-12514") || low.contains("ora-12505")) notFound = true;
        if (low.contains("pkix") || low.contains("certificate") || low.contains("sertifika")) cert = true;
        if (ssl) return new Placement(TLS, cert ? TLS_CERT : TLS_FAILED);
        if (notFound) return new Placement(LOGIN, DB_NOT_FOUND);
        if (DbQueryErrors.DB_AUTH.equals(classified)) return new Placement(LOGIN, DbQueryErrors.DB_AUTH);
        // Sürücünün sarmaladığı TLS hatası (MSSQL "could not establish a secure connection …", PG "server does not support SSL")
        if (cert || low.contains("secure connection") || low.contains("does not support ssl")
                || low.contains("ssl handshake") || low.contains("tls handshake"))
            return new Placement(TLS, cert ? TLS_CERT : TLS_FAILED);
        return new Placement(LOGIN, classified == null ? DbQueryErrors.DB_DRIVER : classified);
    }

    // ── Yetki ────────────────────────────────────────────────────────────────

    /** Yetki denetimi sonucunun adımı. */
    public static Step privileges(DbAccountPrivileges.Result r, Long ms) {
        if (r == null || !r.checked()) return new Step(PRIVILEGES, SKIP, null, NOT_CHECKED, Msg.t(
                "Hesap yetki denetimi yönetici ayarıyla kapalı.", "The account privilege check is turned off by an administrator setting."));
        List<String> blocking = new ArrayList<>(), info = new ArrayList<>();
        if (r.findings() != null) {
            for (DbAccountPrivileges.Finding f : r.findings()) {
                if (f == null || f.code() == null || DbAccountPrivileges.CHECK_FAILED.equals(f.code())) continue;
                if (DbAccountPrivileges.INFO_CODES.contains(f.code())) { if (!info.contains(f.code())) info.add(f.code()); }
                else if (!blocking.contains(f.code())) blocking.add(f.code());
            }
        }
        if (r.writable()) return new Step(PRIVILEGES, WARN, ms, ACCOUNT_WRITABLE,
                blocking.isEmpty() ? null : String.join(", ", blocking));
        if (!Boolean.TRUE.equals(r.readOnly())) return new Step(PRIVILEGES, WARN, ms, CHECK_FAILED, Msg.t(
                "Hesabın yetkileri okunamadı; salt-okur olduğu doğrulanmadı.", "The account's privileges could not be read; it was not confirmed read-only."));
        return new Step(PRIVILEGES, OK, ms, null, info.isEmpty() ? null : String.join(", ", info));
    }
}
