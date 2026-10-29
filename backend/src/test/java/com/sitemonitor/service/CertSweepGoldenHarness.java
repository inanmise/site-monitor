package com.sitemonitor.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sitemonitor.service.health.ClusterHealthStore;
import com.sitemonitor.service.outage.CertNetworkOutageService;
import com.sitemonitor.service.schedule.ClusterLockService;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Çok pod Sürüm 3-4 P0 — KLASİK SERTİFİKA TARAMASI altın kayıt düzeneği (tasarım R3-4 §9.1, L-1 / L-3). DEĞİŞMEMİŞ
 * {@code SchedulerService.runCheckForDomains(List, boolean, LockTicket, String)} gövdesini sabit denetleyici sonuçlarıyla
 * koşturur ve toplamadan SONRAKİ her şeyi tek, normalleştirilmiş döküm olarak verir:
 * <ul>
 *   <li>{@code checks} — {@code checkAsync} çağrıları (ÇOKLU KÜME, sıralı yazılır: parçalı yolda kontroller birçok pod'a dağılır,
 *       sıra kanıt değildir);</li>
 *   <li>{@code check_order} — klasik gönderme sırası (yalnız klasik yolun kanıtı; parçalı karşılaştırma bu bölümü atlar);</li>
 *   <li>{@code calls} — kilit alma, {@code saveResult} (çağrı anındaki harita — ardından benzetilen {@code deployment_status}
 *       yazımı {@code processResults}'ın gördüğü haritalarda görünür), {@code evictAllCaches}, {@code onRun} bağımsız
 *       değişkenleri, {@code processResults} / {@code resolveVerifiedStaleCertAlerts} listeleri, {@code recordScanRun}
 *       bağımsız değişkenleri, kilit bırakma — SIRALI;</li>
 *   <li>{@code state} — {@code lastRun*}, {@code running}, tur kimlikleri;</li>
 *   <li>{@code logs} — {@code SchedulerService} günlüğünün INFO ve üstü satırları.</li>
 * </ul>
 *
 * <p><b>Paket B için:</b> {@code CertChunkRoundTripTest} aynı senaryoyu parçalı yoldan koşturur: düzeneği kurar,
 * {@link #scheduler()}'a koordinatörü bağlar (alan enjeksiyonu), {@link #run()} çağırır ve {@link #transcript(String...)} ile
 * {@code check_order} DIŞINDAKİ bölümleri altın kayıtla karşılaştırır. Denetleyici sahtesi pod'dan bağımsızdır
 * ({@link #checker()} paylaşılabilir; {@link Outcome#hook} işaretleri bu düzeneğin zamanlayıcısına uygulanır).
 *
 * <p><b>Kararlılık:</b> tur kimliği {@code <run>}, örnek kimliği {@code <instance>}, 2020 ve sonrası ISO zaman damgaları
 * {@code <time>}, oran biçimi yerel ayardan bağımsız ({@code rate=0,50 → rate=0.50}). Senaryo verileri 2019 tarihlidir —
 * normalleştirilmez, ulaştığı kanıtlanır.
 */
final class CertSweepGoldenHarness {

    static final Path GOLDEN = Path.of("src/test/resources/r34-golden");
    static final String OWNER = "pod-a";

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Senaryolar
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Kontrol sonucunun {@code join()} anında yapılan yan etki (toplamanın ortasında olay benzetimi). */
    enum Hook { NONE, STOP, LOSE_LOCK }

    /**
     * Bir alan adının denetleyici çıktısı.
     *
     * @param result  tamamlanan sonuç (null → {@code failure} ile biten gelecek)
     * @param failure istisnayla biten kontrol
     * @param hook    {@code join()} anında: STOP = kapanış bayrağı ({@code startupCheckStopped}), LOSE_LOCK = kilit kaybı
     */
    record Outcome(Map<String, Object> result, RuntimeException failure, Hook hook) {
        static Outcome ok(Map<String, Object> r) {
            return new Outcome(r, null, Hook.NONE);
        }
    }

    /**
     * @param name                 dosya adı ({@code r34-golden/<name>.json})
     * @param kind                 hourly / stale / full
     * @param evictCaches          {@code runCheckForDomains} bağımsız değişkeni
     * @param preHeld              saatlik tur gibi kilit önceden alınmış mı
     * @param rows                 klasik vade listesi (sıra = tur sırası)
     * @param outcomes             alan adı → çıktı
     * @param ongoingOutage        açık kesinti var (onRun false döner)
     * @param expiryAlertsEnabled  {@code site.monitor.expiry.alert-enabled}
     * @param saveThrowsFor        bu alan adının {@code saveResult}'ı fırlatır (null = yok)
     */
    record Scenario(String name, String kind, boolean evictCaches, boolean preHeld, List<Map<String, Object>> rows,
                    Map<String, Outcome> outcomes, boolean ongoingOutage, boolean expiryAlertsEnabled,
                    String saveThrowsFor) {

        /** Alan adının çıktısının DERİN kopyası (denetleyici her çağrıda yeni harita döner — üretimdeki gibi). */
        Outcome outcome(String domain) {
            Outcome o = outcomes.get(domain);
            if (o == null) throw new IllegalStateException("senaryoda yok: " + domain);
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = o.result() == null ? null : (Map<String, Object>) deepCopy(o.result());
            return new Outcome(copy, o.failure(), o.hook());
        }

        /** Vade listesinin derin kopyası (her koşu kendi listesini alsın). */
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rowsCopy() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : rows) out.add((Map<String, Object>) deepCopy(r));
            return out;
        }
    }

    static List<Scenario> scenarios() {
        return List.of(hourly(), suspectedOutage(), ongoingOutageStale(), expiryAlertsOff(), lockLost(),
                shutdownDuringGather(), saveThrows(), checkThrows(), full());
    }

    static Scenario scenario(String name) {
        return scenarios().stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(name));
    }

    /** Sağlıklı saatlik tur: geçerli / uyarı / SSL / çözülemeyen ad (sayılmaz) / 2 ağ hatası (< 3) / BLOCKED → alarmlar işlenir. */
    static Scenario hourly() {
        Fx f = new Fx();
        f.add(row("a.example.com", 443, false, "", null, null), valid("a.example.com", 90, true, 412L));
        f.add(row("b.example.com", 8443, true, "browser", null, null), valid("b.example.com", 200, false, 1_830L));
        f.add(row("c.example.com", 443, false, "", 15, null), warning("c.example.com", 12));
        f.add(row("d.example.com", 443, false, "", null, null), sslError("d.example.com"));
        f.add(row("e.example.com", 443, false, "", null, null), unresolvable("e.example.com"));
        f.add(row("f.example.com", 443, false, "", null, null), networkError("f.example.com", "Connection timeout after 6s"));
        f.add(row("g.example.com", 443, false, "default", null, 6), valid("g.example.com", 365, false, 95L));
        f.add(row("h.example.com", 443, false, "", null, null), blocked("h.example.com"));
        f.add(row("i.example.com", 636, false, "", null, null), valid("i.example.com", 45, false, 230L));
        f.add(row("j.example.com", 443, false, null, null, null), dnsError("j.example.com"));
        return f.scenario("hourly", ClusterHealthStore.KIND_HOURLY, true, true);
    }

    /** Şüpheli toplu kesinti: 4 / 6 ağ sınıfı hata (≥ 3, ≥ %50) → onRun false → yalnız kapanış uzlaştırması. */
    static Scenario suspectedOutage() {
        Fx f = new Fx();
        f.add(row("k1.example.com", 443, false, "", null, null), networkError("k1.example.com", "Connection refused/unreachable: Connection refused"));
        f.add(row("k2.example.com", 443, true, "", null, null), valid("k2.example.com", 120, false, 640L));
        f.add(row("k3.example.com", 443, false, "", null, null), networkError("k3.example.com", "No route to host"));
        f.add(row("k4.example.com", 443, false, "", null, null), dnsError("k4.example.com"));
        f.add(row("k5.example.com", 443, false, "", null, null), valid("k5.example.com", 8, false, 120L));
        f.add(row("k6.example.com", 443, false, "", null, null), networkError("k6.example.com", "Connection timeout after 6s"));
        return f.scenario("suspected-outage", ClusterHealthStore.KIND_HOURLY, true, true);
    }

    /** Açık kesinti sürerken küçük bayat tur (önbellek boşaltmaz) → onRun false → kapanış uzlaştırması. */
    static Scenario ongoingOutageStale() {
        Fx f = new Fx();
        f.add(row("s1.example.com", 443, false, "", null, 24), valid("s1.example.com", 61, false, 300L));
        f.add(row("s2.example.com", 8443, false, "browser", 30, null), valid("s2.example.com", 3, false, 2_200L));
        f.ongoing = true;
        return f.scenario("ongoing-outage-stale", ClusterHealthStore.KIND_STALE, false, false);
    }

    /** Bitiş uyarısı kapalı tam tur → alarm işlenmez, yalnız kapanış uzlaştırması. */
    static Scenario expiryAlertsOff() {
        Fx f = new Fx();
        f.add(row("x1.example.com", 443, false, "", null, null), valid("x1.example.com", 5, false, 410L));
        f.add(row("x2.example.com", 443, false, "", null, null), warning("x2.example.com", 20));
        f.add(row("x3.example.com", 443, false, "", null, null), sslError("x3.example.com"));
        f.add(row("x4.example.com", 443, true, "", null, null), valid("x4.example.com", 300, true, 515L));
        f.alertsOff = true;
        return f.scenario("expiry-alerts-off", ClusterHealthStore.KIND_FULL, true, false);
    }

    /** Toplama sırasında kilit kaybı → hiçbir sonuç işlenmez, tarama durumu yazılmaz. */
    static Scenario lockLost() {
        Fx f = new Fx();
        f.add(row("l1.example.com", 443, false, "", null, null), valid("l1.example.com", 77, false, 100L));
        f.add(row("l2.example.com", 443, false, "", null, null), valid("l2.example.com", 78, false, 101L));
        f.add(row("l3.example.com", 443, false, "", null, null), new Outcome(valid("l3.example.com", 79, false, 102L), null, Hook.LOSE_LOCK));
        f.add(row("l4.example.com", 443, false, "", null, null), valid("l4.example.com", 80, false, 103L));
        return f.scenario("lock-lost", ClusterHealthStore.KIND_HOURLY, true, true);
    }

    /** Toplama sırasında kapanış → kalan sonuçlar beklenmez, hiçbir şey işlenmez ("2 / 5 sonuç"). */
    static Scenario shutdownDuringGather() {
        Fx f = new Fx();
        f.add(row("z1.example.com", 443, false, "", null, null), valid("z1.example.com", 50, false, 90L));
        f.add(row("z2.example.com", 443, false, "", null, null), new Outcome(valid("z2.example.com", 51, false, 91L), null, Hook.STOP));
        f.add(row("z3.example.com", 443, false, "", null, null), valid("z3.example.com", 52, false, 92L));
        f.add(row("z4.example.com", 443, false, "", null, null), valid("z4.example.com", 53, false, 93L));
        f.add(row("z5.example.com", 443, false, "", null, null), valid("z5.example.com", 54, false, 94L));
        return f.scenario("shutdown-during-gather", ClusterHealthStore.KIND_STALE, false, false);
    }

    /** {@code saveResult} 3. alan adında fırlatır → tur BAŞARISIZ (önceki kayıtlar kalır), hata tarama durumuna yazılır. */
    static Scenario saveThrows() {
        Fx f = new Fx();
        f.add(row("w1.example.com", 443, false, "", null, null), valid("w1.example.com", 33, false, 210L));
        f.add(row("w2.example.com", 443, false, "", null, null), warning("w2.example.com", 9));
        f.add(row("w3.example.com", 443, false, "", null, null), valid("w3.example.com", 34, false, 211L));
        f.add(row("w4.example.com", 443, false, "", null, null), networkError("w4.example.com", "Socket error: reset"));
        f.add(row("w5.example.com", 443, false, "", null, null), valid("w5.example.com", 35, false, 212L));
        f.saveThrowsFor = "w3.example.com";
        return f.scenario("save-throws", ClusterHealthStore.KIND_FULL, true, false);
    }

    /** Bir kontrolün geleceği istisnayla biter → toplama fırlatır, tur BAŞARISIZ, hiçbir sonuç kaydedilmez. */
    static Scenario checkThrows() {
        Fx f = new Fx();
        f.add(row("t1.example.com", 443, false, "", null, null), valid("t1.example.com", 60, false, 150L));
        f.add(row("t2.example.com", 443, false, "", null, null), valid("t2.example.com", 61, false, 151L));
        f.add(row("t3.example.com", 443, false, "", null, null),
                new Outcome(null, new IllegalStateException("denetleyici çöktü: t3"), Hook.NONE));
        f.add(row("t4.example.com", 443, false, "", null, null), valid("t4.example.com", 62, false, 152L));
        return f.scenario("check-throws", ClusterHealthStore.KIND_HOURLY, true, true);
    }

    /** Tam tur (12 alan adı): 3 ağ hatası (≥ min 3) ama oran %25 (< %50) → şüphe yok → alarmlar işlenir. */
    static Scenario full() {
        Fx f = new Fx();
        for (int i = 1; i <= 12; i++) {
            String d = "full" + i + ".example.org";
            Map<String, Object> r = switch (i) {
                case 3 -> networkError(d, "Connection timeout after 6s");
                case 5 -> warning(d, 25);
                case 7 -> networkError(d, "I/O error: Broken pipe");
                case 8 -> sslError(d);
                case 10 -> dnsError(d);
                case 12 -> unresolvable(d);
                default -> valid(d, 30 + 7 * i, i % 2 == 0, 100L + i);
            };
            f.add(row(d, i == 9 ? 8443 : 443, i == 4, i == 6 ? "browser" : "", i == 11 ? 20 : null, i == 2 ? 12 : null), r);
        }
        return f.scenario("full", ClusterHealthStore.KIND_FULL, true, false);
    }

    /** Senaryo kurucusu. */
    private static final class Fx {
        final List<Map<String, Object>> rows = new ArrayList<>();
        final Map<String, Outcome> outcomes = new LinkedHashMap<>();
        boolean ongoing;
        boolean alertsOff;
        String saveThrowsFor;

        void add(Map<String, Object> row, Map<String, Object> result) {
            add(row, Outcome.ok(result));
        }

        void add(Map<String, Object> row, Outcome o) {
            rows.add(row);
            outcomes.put((String) row.get("domain"), o);
        }

        Scenario scenario(String name, String kind, boolean evict, boolean preHeld) {
            return new Scenario(name, kind, evict, preHeld, List.copyOf(rows), Collections.unmodifiableMap(outcomes),
                    ongoing, !alertsOff, saveThrowsFor);
        }
    }

    // ── Satır ve sonuç kurucuları (üretimdeki anahtarlar ve sıra; tarihler 2019 — normalleştirilmez) ──────────────────

    /** {@code loadDomainsFromInventory} biçimi: zaman aşımı ve sıklık yalnız doluysa konur. */
    static Map<String, Object> row(String domain, int port, boolean useProxy, String tlsMode, Integer timeout, Integer hours) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", domain);
        r.put("port", port);
        r.put("use_proxy", useProxy);
        r.put("tls_mode", tlsMode);
        if (timeout != null) r.put("timeout_seconds", timeout);
        if (hours != null) r.put("check_interval_hours", hours);
        return r;
    }

    static Map<String, Object> valid(String domain, int days, boolean withChain, long elapsedMs) {
        Map<String, Object> r = leaf(domain, days);
        r.put("chain_status", withChain ? "VALID" : "INCOMPLETE");
        r.put("trust_status", "TRUSTED");
        r.put("trust_error", null);
        r.put("revocation_status", "GOOD");
        r.put("revocation_reason", "OCSP");
        r.put("revocation_detail", "[{\"m\":\"OCSP\",\"r\":\"GOOD\"}]");
        r.put("deployment_status", "UNKNOWN");
        r.put("fingerprint", "AB:CD:" + Integer.toHexString(domain.hashCode()).toUpperCase());
        r.put("intermediate_expiry", withChain ? "2019-11-30T00:00:00" : null);
        r.put("intermediate_days_remaining", withChain ? 300 : null);
        List<Object> chain = new ArrayList<>();
        if (withChain) {
            chain.add(chainCert("CN=" + domain, "CN=Corp Issuing CA 01", "2019-09-30T00:00:00", 90));
            chain.add(chainCert("CN=Corp Issuing CA 01", "CN=Corp Root CA", "2019-11-30T00:00:00", 300));
        }
        r.put("chain", chain);
        r.put("tls_version", "TLSv1.3");
        r.put("cipher_suite", "TLS_AES_256_GCM_SHA384");
        r.put("alpn", "h2");
        r.put("hsts", days > 100 ? "max-age=31536000; includeSubDomains" : null);
        r.put("http_status", domain.startsWith("i.") ? null : 200);
        r.put("resolved_ip", "10.20.30." + (Math.abs(domain.hashCode()) % 200 + 1));
        r.put("resolved_ips", List.of("10.20.30." + (Math.abs(domain.hashCode()) % 200 + 1)));
        r.put("via", "direct");
        r.put("tls_mode_used", "browser");
        r.put("elapsed_ms", elapsedMs);
        r.put("attempts_total", 1);
        return r;
    }

    static Map<String, Object> warning(String domain, int days) {
        Map<String, Object> r = valid(domain, days, false, 777L);
        r.put("warning", true);
        r.put("status", "warning");
        return r;
    }

    private static Map<String, Object> leaf(String domain, int days) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", domain);
        r.put("subject", domain);
        r.put("issuer", "Corp");
        r.put("issuer_cn", "Corp Issuing CA 01");
        r.put("not_before", "2019-01-01T00:00:00");
        r.put("not_after", "2019-12-31T00:00:00");
        r.put("days_remaining", days);
        r.put("warning", days <= 30);
        r.put("status", days <= 30 ? "warning" : "valid");
        r.put("san", List.of(domain, "www." + domain));
        r.put("checked_at", "2019-06-01T08:00:01");
        r.put("serial_number", "0A1B" + Math.abs(domain.hashCode()));
        r.put("signature_algorithm", "SHA256withRSA");
        r.put("public_key_algorithm", "RSA");
        r.put("public_key_size", 2048);
        r.put("subject_dn", "CN=" + domain);
        r.put("issuer_dn", "CN=Corp Issuing CA 01,O=Corp");
        r.put("key_usage", List.of("digitalSignature", "keyEncipherment"));
        r.put("ext_key_usage", List.of("serverAuth"));
        r.put("is_ca", false);
        r.put("ocsp_url", "http://ocsp.corp.example/");
        r.put("crl_url", null);
        r.put("cert_type", "DV");
        return r;
    }

    private static Map<String, Object> chainCert(String subject, String issuer, String notAfter, int days) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("subject", subject);
        c.put("issuer", issuer);
        c.put("not_after", notAfter);
        c.put("days_remaining", days);
        c.put("self_signed", false);
        return c;
    }

    private static Map<String, Object> error(String domain, String msg, String cls, String stage) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", domain);
        r.put("status", "error");
        r.put("error", msg);
        r.put("warning", true);
        r.put("checked_at", "2019-06-01T08:00:02");
        r.put("san", List.of());
        r.put("chain_status", "UNKNOWN");
        r.put("revocation_status", "UNKNOWN");
        r.put("deployment_status", "UNKNOWN");
        r.put("fingerprint", null);
        r.put("chain", List.of());
        r.put("subject_dn", null);
        r.put("issuer_dn", null);
        r.put("tls_version", null);
        r.put("hsts", null);
        r.put("error_class", cls);
        if (stage != null) r.put("error_stage", stage);
        return r;
    }

    static Map<String, Object> networkError(String domain, String msg) {
        Map<String, Object> r = error(domain, msg, "NETWORK", "connect");
        r.put("resolved_ips", List.of("10.9.8.7"));
        r.put("attempts_total", 2);
        r.put("attempts_elapsed_ms", 13_004L);
        r.put("retry_attempted", true);
        r.put("retry_recovered", false);
        r.put("via", "direct");
        return r;
    }

    static Map<String, Object> dnsError(String domain) {
        return error(domain, "Domain resolution failed", "DNS", "dns");
    }

    static Map<String, Object> unresolvable(String domain) {
        Map<String, Object> r = error(domain, "Domain resolution failed", "DNS", "dns");
        r.put("unresolvable_host", true);
        return r;
    }

    static Map<String, Object> sslError(String domain) {
        Map<String, Object> r = error(domain, "SSL handshake: Received fatal alert: handshake_failure", "SSL", "tls");
        r.put("resolved_ips", List.of("10.1.1.1", "10.1.1.2"));
        return r;
    }

    static Map<String, Object> blocked(String domain) {
        return error(domain, "Hedef engellendi: 169.254.169.254 (metadata)", "BLOCKED", null);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Düzenek
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    private final Scenario scenario;
    private final SchedulerService scheduler;
    private final CertificateCheckerService checker;
    private final CertificateService certService;
    private final EscalationService escalation;
    private final CertNetworkOutageService outage;
    private final ClusterHealthStore health;
    private final ClusterLockService lockService;
    private final ClusterLockService.Hold hold;
    private final AppSettingsService appSettings;

    private final List<Map<String, Object>> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> checks = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger seq = new AtomicInteger();
    private volatile boolean lockHeld = true;
    private final List<String> logs = new ArrayList<>();
    private final AtomicReference<String> runIdSeen = new AtomicReference<>();

    CertSweepGoldenHarness(Scenario scenario) throws Exception {
        this.scenario = scenario;
        checker = mock(CertificateCheckerService.class);
        certService = mock(CertificateService.class);
        escalation = mock(EscalationService.class);
        outage = mock(CertNetworkOutageService.class);
        health = mock(ClusterHealthStore.class);
        lockService = mock(ClusterLockService.class);
        hold = mock(ClusterLockService.Hold.class);
        appSettings = mock(AppSettingsService.class);

        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> {
            if ("site.monitor.expiry.alert-enabled".equals(i.getArgument(0))) return scenario.expiryAlertsEnabled();
            return i.getArgument(1);
        });
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));

        lenient().when(checker.checkAsync(anyString(), anyInt(), anyBoolean(), any(), any())).thenAnswer(i -> {
            String domain = i.getArgument(0);
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("domain", domain);
            call.put("port", i.getArgument(1));
            call.put("use_proxy", i.getArgument(2));
            call.put("tls_mode", i.getArgument(3));
            call.put("timeout_seconds", i.getArgument(4));
            checks.add(call);
            return future(scenario.outcome(domain));
        });

        doAnswer(i -> {
            Map<String, Object> m = i.getArgument(0);
            boolean throwsNow = m != null && scenario.saveThrowsFor() != null && scenario.saveThrowsFor().equals(m.get("domain"));
            record("saveResult", "result", deepCopy(m), "threw", throwsNow);
            if (throwsNow) throw new IllegalStateException("kayıt yazılamadı: " + m.get("domain"));
            // CertificateService.saveResult, determineDeploymentStatus sonucunu HARİTAYA yazar (CS:89-90) — karar aynı haritayı görür
            m.put("deployment_status", "valid".equals(m.get("status")) ? "SIM-OK" : "SIM-" + m.get("status"));
            return null;
        }).when(certService).saveResult(any());
        doAnswer(i -> {
            record("evictAllCaches");
            return null;
        }).when(certService).evictAllCaches();

        lenient().when(outage.onRun(anyLong(), anyInt(), anyDouble(), anyBoolean(), anyInt(), anyDouble())).thenAnswer(i -> {
            boolean suspected = i.getArgument(3);
            record("onRun", "networkErrors", i.getArgument(0), "total", i.getArgument(1), "rate", i.getArgument(2),
                    "suspected", suspected, "minErrors", i.getArgument(4), "threshold", i.getArgument(5));
            return !(suspected || scenario.ongoingOutage());
        });
        doAnswer(i -> {
            record("processResults", "results", deepCopy(i.getArgument(0)));
            return null;
        }).when(escalation).processResults(anyList());
        lenient().when(escalation.resolveVerifiedStaleCertAlerts(anyList())).thenAnswer(i -> {
            List<Map<String, Object>> l = i.getArgument(0);
            record("resolveVerifiedStaleCertAlerts", "results", deepCopy(l));
            return (int) l.stream().filter(m -> "valid".equals(m.get("status"))).count();
        });
        lenient().when(health.recordScanRun(anyString(), any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(i -> {
            record("recordScanRun", "kind", i.getArgument(0), "runId", i.getArgument(1), "instanceId", i.getArgument(2),
                    "at", i.getArgument(3), "durationMs", i.getArgument(4) == null ? null : "<duration>",
                    "total", i.getArgument(5), "warnings", i.getArgument(6), "errors", i.getArgument(7),
                    "failure", i.getArgument(8));
            return true;
        });

        lenient().when(lockService.owner()).thenReturn(OWNER);
        lenient().when(lockService.tryAcquire(anyString(), any(Duration.class), any(Duration.class))).thenAnswer(i -> {
            record("lock.acquire", "name", i.getArgument(0), "ttl", String.valueOf(i.getArgument(1, Duration.class)));
            return Optional.of(hold);
        });
        lenient().when(lockService.held(anyString())).thenAnswer(i -> lockHeld);
        lenient().when(lockService.hold(anyString())).thenAnswer(i -> lockHeld ? Optional.of(hold) : Optional.empty());
        lenient().when(lockService.release(any(ClusterLockService.Hold.class))).thenAnswer(i -> {
            record("lock.release");
            return true;
        });
        lenient().when(hold.name()).thenReturn("cert-check");

        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(CertificateCheckerService.class, checker);
        provided.put(CertificateService.class, certService);
        provided.put(EscalationService.class, escalation);
        provided.put(AppSettingsService.class, appSettings);
        provided.put(JdbcTemplate.class, mock(JdbcTemplate.class));
        scheduler = build(provided);
        ReflectionTestUtils.setField(scheduler, "certNetworkOutage", outage);
        ReflectionTestUtils.setField(scheduler, "clusterHealthStore", health);
        ReflectionTestUtils.setField(scheduler, "lockService", lockService);
        ReflectionTestUtils.setField(scheduler, "networkMinErrors", 3);
        ReflectionTestUtils.setField(scheduler, "networkErrorRateThreshold", 0.50);
        ReflectionTestUtils.setField(scheduler, "lockTtlMinutes", 10);
        ReflectionTestUtils.setField(scheduler, "certCheckMaxWaitSeconds", 900L);
    }

    private static SchedulerService build(Map<Class<?>, Object> provided) throws Exception {
        Constructor<?> c = Arrays.stream(SchedulerService.class.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = provided.containsKey(pt[i]) ? provided.get(pt[i]) : mock(pt[i]);
        return (SchedulerService) c.newInstance(args);
    }

    /** Senaryo çıktısını üretimdeki gibi tamamlanmış bir geleceğe çevirir; işaretler {@code join()} anında uygulanır. */
    private CompletableFuture<Map<String, Object>> future(Outcome o) {
        if (o.failure() != null) return CompletableFuture.failedFuture(o.failure());
        HookedFuture f = new HookedFuture(o.hook());
        f.complete(o.result());
        return f;
    }

    private final class HookedFuture extends CompletableFuture<Map<String, Object>> {
        private final Hook hook;

        HookedFuture(Hook hook) {
            this.hook = hook;
        }

        @Override
        public Map<String, Object> join() {
            if (hook == Hook.STOP) ReflectionTestUtils.setField(scheduler, "startupCheckStopped", true);
            if (hook == Hook.LOSE_LOCK) lockHeld = false;
            return super.join();
        }
    }

    private void record(String call, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("call", call);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        m.put("#", seq.incrementAndGet());
        calls.add(m);
    }

    SchedulerService scheduler() {
        return scheduler;
    }

    CertificateCheckerService checker() {
        return checker;
    }

    Scenario scenario() {
        return scenario;
    }

    /**
     * {@code runCheckForDomains(rows, evictCaches, preHeld ? LockTicket : null, kind)} — DEĞİŞMEMİŞ klasik gövde. Günlük
     * satırları yakalanır. Gövdenin kendisi istisna fırlatırsa (olmamalı) yeniden fırlatılır.
     */
    void run() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(SchedulerService.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            Method m = SchedulerService.class.getDeclaredMethod("runCheckForDomains", List.class, boolean.class,
                    SchedulerService.LockTicket.class, String.class);
            m.setAccessible(true);
            SchedulerService.LockTicket ticket = scenario.preHeld() ? new SchedulerService.LockTicket("cert-check", hold) : null;
            m.invoke(scheduler, scenario.rowsCopy(), scenario.evictCaches(), ticket, scenario.kind());
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
        for (ILoggingEvent ev : appender.list) {
            if (ev.getLevel().isGreaterOrEqual(Level.INFO)) logs.add(ev.getLevel() + " " + ev.getFormattedMessage());
        }
        runIdSeen.set(String.valueOf(field("lastRunId", AtomicReference.class).get()));
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Döküm
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Tüm bölümler. */
    String transcript() {
        return transcript("scenario", "checks", "check_order", "calls", "state", "logs");
    }

    /** İstenen bölümlerin normalleştirilmiş JSON dökümü (sondaki LF dahil). */
    String transcript(String... sections) {
        Map<String, Object> all = new LinkedHashMap<>();
        Map<String, Object> sc = new LinkedHashMap<>();
        sc.put("name", scenario.name());
        sc.put("kind", scenario.kind());
        sc.put("evict_caches", scenario.evictCaches());
        sc.put("pre_held", scenario.preHeld());
        sc.put("domains", scenario.rows().size());
        all.put("scenario", sc);

        List<Map<String, Object>> chk;
        synchronized (checks) {
            chk = new ArrayList<>(checks);
        }
        List<Object> order = new ArrayList<>();
        for (Map<String, Object> c : chk) order.add(c.get("domain"));
        List<Object> multiset = new ArrayList<>();
        for (Map<String, Object> c : chk) multiset.add(norm(c));
        multiset.sort(Comparator.comparing(GoldenJson::write));
        all.put("checks", multiset);
        all.put("check_order", order);

        List<Map<String, Object>> cl;
        synchronized (calls) {
            cl = new ArrayList<>(calls);
        }
        cl.sort(Comparator.comparingInt(m -> (Integer) m.get("#")));
        List<Object> callOut = new ArrayList<>();
        for (Map<String, Object> m : cl) {
            Map<String, Object> copy = new LinkedHashMap<>(m);
            copy.remove("#");
            callOut.add(norm(copy));
        }
        all.put("calls", callOut);
        all.put("state", norm(state()));
        List<Object> lg = new ArrayList<>();
        for (String l : logs) lg.add(norm(l));
        all.put("logs", lg);

        Map<String, Object> out = new LinkedHashMap<>();
        for (String s : sections) {
            if (!all.containsKey(s)) throw new IllegalArgumentException("bölüm yok: " + s);
            out.put(s, all.get(s));
        }
        return GoldenJson.write(out) + "\n";
    }

    private Map<String, Object> state() {
        Map<String, Object> st = new LinkedHashMap<>();
        Object lastRun = field("lastRun", AtomicReference.class).get();
        st.put("running", field("running", java.util.concurrent.atomic.AtomicBoolean.class).get());
        st.put("current_run_id", field("currentRunId", AtomicReference.class).get());
        st.put("last_run_id", field("lastRunId", AtomicReference.class).get());
        st.put("last_run", lastRun == null ? null : "<set>");
        long dur = field("lastRunDurationMs", AtomicLong.class).get();
        st.put("last_run_duration_ms", lastRun == null ? (Object) dur : "<duration>");
        st.put("last_run_total", field("lastRunTotal", AtomicInteger.class).get());
        st.put("last_run_warnings", field("lastRunWarnings", AtomicInteger.class).get());
        st.put("last_run_errors", field("lastRunErrors", AtomicInteger.class).get());
        st.put("last_run_failure", field("lastRunFailure", AtomicReference.class).get());
        st.put("startup_check_stopped", ReflectionTestUtils.getField(scheduler, "startupCheckStopped"));
        st.put("lock_held_at_end", lockHeld);
        return st;
    }

    private <T> T field(String name, Class<T> type) {
        return type.cast(ReflectionTestUtils.getField(scheduler, name));
    }

    // ── normalleştirme ───────────────────────────────────────────────────────────────────────────────────────────────

    /** 2020 ve sonrası (koşunun kendi) ISO zaman damgaları — senaryo verisi 2019'dur ve olduğu gibi kalır. */
    private static final Pattern NOW_ISO =
            Pattern.compile("(?:20[2-9]\\d|2[1-9]\\d\\d)-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z?");
    /** {@code String.format("%.2f")} yerel ayara bağlı (TR: 0,50) — makineden bağımsız olsun. */
    private static final Pattern RATE = Pattern.compile("rate=(\\d+),(\\d+)");

    private String normString(String s) {
        String out = s;
        String runId = runIdSeen.get();
        if (runId != null && !runId.isEmpty()) out = out.replace(runId, "<run>");
        out = out.replace(SchedulerService.instanceId(), "<instance>");
        out = NOW_ISO.matcher(out).replaceAll("<time>");
        out = RATE.matcher(out).replaceAll("rate=$1.$2");
        return out;
    }

    private Object norm(Object v) {
        if (v == null || v instanceof Boolean || v instanceof Number) return v;
        if (v instanceof CharSequence cs) return normString(cs.toString());
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), norm(e.getValue()));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(norm(o));
            return out;
        }
        return normString(String.valueOf(v));
    }

    /** Harita / liste derin kopyası (sıra korunur) — kaydedilen çağrı anındaki hâl. */
    static Object deepCopy(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), deepCopy(e.getValue()));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(deepCopy(o));
            return out;
        }
        return v;
    }

    /**
     * Küçük, kararlı JSON yazıcı (2 boşluk girinti, LF). Long ve Integer AYNI yazılır (L-3: JSON normalleştirmesi Long →
     * Integer döküme yansımaz); ondalık sayının tam değeri {@code x.0}.
     */
    static final class GoldenJson {
        private GoldenJson() {
        }

        static String write(Object v) {
            StringBuilder sb = new StringBuilder();
            write(sb, v, 0);
            return sb.toString();
        }

        private static void write(StringBuilder sb, Object v, int indent) {
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short
                    || v instanceof Byte) {
                sb.append(v);
            } else if (v instanceof Number n) {
                double d = n.doubleValue();
                if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) sb.append((long) d).append(".0");
                else sb.append(d);
            } else if (v instanceof Map<?, ?> m) {
                if (m.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append("{\n");
                int i = 0;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    pad(sb, indent + 1);
                    str(sb, String.valueOf(e.getKey()));
                    sb.append(": ");
                    write(sb, e.getValue(), indent + 1);
                    if (++i < m.size()) sb.append(',');
                    sb.append('\n');
                }
                pad(sb, indent);
                sb.append('}');
            } else if (v instanceof List<?> l) {
                if (l.isEmpty()) {
                    sb.append("[]");
                    return;
                }
                sb.append("[\n");
                for (int i = 0; i < l.size(); i++) {
                    pad(sb, indent + 1);
                    write(sb, l.get(i), indent + 1);
                    if (i + 1 < l.size()) sb.append(',');
                    sb.append('\n');
                }
                pad(sb, indent);
                sb.append(']');
            } else {
                str(sb, String.valueOf(v));
            }
        }

        private static void pad(StringBuilder sb, int indent) {
            sb.append("  ".repeat(indent));
        }

        private static void str(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }
    }
}
