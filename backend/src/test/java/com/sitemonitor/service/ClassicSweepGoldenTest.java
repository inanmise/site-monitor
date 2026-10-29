package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DnsRecord;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.HttpCheckRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.KeywordResultRepository;
import com.sitemonitor.repository.PageCheckRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PageResourceIssueRepository;
import com.sitemonitor.repository.PageSpeedCheckRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.PageSpeedResourceRepository;
import com.sitemonitor.repository.PingCheckRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortCheckRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.ScriptedCheckRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.service.page.HttpPhaseProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-3 P0 — KLASİK TARAMA ALTIN KAYITLARI (tasarım R3-3 §9.1, K-1 / K-3). Sekiz dağıtılacak türün DEĞİŞMEMİŞ
 * {@code run<Tür>ChecksLocked()} gövdesi sabit denetleyici sonuçlarıyla koşturulur; {@code MonitoringOutageService}'e giden
 * {@code handleSweepResults} / {@code handleDnsSweep} bağımsız değişkenleri (alarm türü, alan, ayrıntı, up, hata, bağlam JSON'u,
 * {@code recheck != null}; DNS değişiklikleri), {@code EscalationService} çağrıları (öksüz temizliği, envanter dışı sessiz
 * kapanış, açık bütünlük alarmı bağlamı), etkinlik kayıtları, denetleyici çağrıları ve geçmiş satırı kayıtları TEK sıralı
 * döküm olarak {@code src/test/resources/r33-golden/<tür>.json} ile karşılaştırılır.
 *
 * <p><b>Neden:</b> paket B klasik gövdelerden kurucular (PORT_SLOW / KEYWORD_SLOW kalemleri, DNS kalem bölümü, DNS iş gövdesi,
 * kapılar) AYIRIR ve tek satırlık {@code distributedMode} kapısı ekler. Ayırma SAF olmalıdır: bu kayıtlar 20.169.0'ın
 * (886f8fe6) kodundan alınmıştır; B'nin dalında da birebir aynı kalmalıdır (anahtar kapalı ≡ 20.169.0).
 *
 * <p><b>Döküm bölümleri:</b> {@code decisions} (MOS + EscalationService çağrıları, SIRALI — alarm kararının girdisi),
 * {@code writes} (geçmiş satırı / etkinlik kayıtları, SIRALI) ve {@code checks} (denetleyici çağrıları, ÇOKLU KÜME: üretimde
 * kontroller {@code certCheckExecutor}'da eşzamanlı başlar; burada yürütücü yok, satır içi koşar — sıra kanıt değildir).
 *
 * <p><b>Kararlılık:</b> duvar saati ya da rastgelelik içermez — ISO zaman damgaları {@code "<time>"}, kümeler sıralanır
 * ({@code Set.of} sırası JVM başına değişir), sıralamasız haritalar anahtara göre sıralanır; bağlam haritaları ekleme
 * sırasıyla (B'nin kurucuları aynı sırayla koymalı). Yeniden kayıt (YALNIZ bilerek davranış değişikliğinde, gerekçesiyle):
 * {@code mvn -o test -Dtest=ClassicSweepGoldenTest -Dr33.writeGolden=true}.
 */
class ClassicSweepGoldenTest {

    static final Path GOLDEN = Path.of("src/test/resources/r33-golden");
    static final boolean WRITE = Boolean.getBoolean("r33.writeGolden");

    // ── mock'lar ─────────────────────────────────────────────────────────────────────────────────────────────────────
    MonitoringOutageService outage;
    EscalationService escalation;
    ActivityLogService activity;
    AppSettingsService appSettings;
    CertificateInventoryRepository inventoryRepo;
    JdbcTemplate jdbc;

    PortCheckerService portChecker;
    PortMonitorRepository portRepo;
    PortCheckRepository portCheckRepo;
    KeywordCheckerService keywordChecker;
    KeywordMonitorRepository keywordRepo;
    KeywordResultRepository keywordResultRepo;
    KeywordHeaderSecrets keywordHeaders;
    HttpCheckerService httpChecker;
    HttpMonitorRepository httpRepo;
    HttpCheckRepository httpCheckRepo;
    PingCheckerService pingChecker;
    PingMonitorRepository pingRepo;
    PingCheckRepository pingCheckRepo;
    DnsCheckerService dnsChecker;
    DnsMonitorRepository dnsRepo;
    DnsRecordRepository dnsRecordRepo;
    PageCheckerService pageChecker;
    PageMonitorRepository pageRepo;
    PageCheckRepository pageCheckRepo;
    PageResourceIssueRepository pageIssueRepo;
    PageSpeedCheckerService pageSpeedChecker;
    PageSpeedMonitorRepository pageSpeedRepo;
    PageSpeedCheckRepository pageSpeedCheckRepo;
    PageSpeedResourceRepository pageSpeedResourceRepo;
    ScriptedCheckerService scriptedChecker;
    ScriptedMonitorRepository scriptedRepo;
    ScriptedCheckRepository scriptedCheckRepo;

    SchedulerService scheduler;

    @SuppressWarnings("unchecked")
    private static <T> T build(Class<T> type, Map<Class<?>, Object> provided) throws Exception {
        Constructor<?> c = java.util.Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = provided.containsKey(pt[i]) ? provided.get(pt[i]) : mock(pt[i]);
        return (T) c.newInstance(args);
    }

    @BeforeEach
    void setUp() throws Exception {
        outage = mock(MonitoringOutageService.class);
        escalation = mock(EscalationService.class);
        activity = mock(ActivityLogService.class);
        appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        inventoryRepo = mock(CertificateInventoryRepository.class);
        jdbc = mock(JdbcTemplate.class);

        portChecker = mock(PortCheckerService.class);
        portRepo = mock(PortMonitorRepository.class);
        portCheckRepo = mock(PortCheckRepository.class);
        keywordChecker = mock(KeywordCheckerService.class);
        keywordRepo = mock(KeywordMonitorRepository.class);
        keywordResultRepo = mock(KeywordResultRepository.class);
        keywordHeaders = mock(KeywordHeaderSecrets.class);
        httpChecker = mock(HttpCheckerService.class);
        httpRepo = mock(HttpMonitorRepository.class);
        httpCheckRepo = mock(HttpCheckRepository.class);
        pingChecker = mock(PingCheckerService.class);
        pingRepo = mock(PingMonitorRepository.class);
        pingCheckRepo = mock(PingCheckRepository.class);
        dnsChecker = mock(DnsCheckerService.class);
        dnsRepo = mock(DnsMonitorRepository.class);
        dnsRecordRepo = mock(DnsRecordRepository.class);
        pageChecker = mock(PageCheckerService.class);
        pageRepo = mock(PageMonitorRepository.class);
        pageCheckRepo = mock(PageCheckRepository.class);
        pageIssueRepo = mock(PageResourceIssueRepository.class);
        pageSpeedChecker = mock(PageSpeedCheckerService.class);
        pageSpeedRepo = mock(PageSpeedMonitorRepository.class);
        pageSpeedCheckRepo = mock(PageSpeedCheckRepository.class);
        pageSpeedResourceRepo = mock(PageSpeedResourceRepository.class);
        scriptedChecker = mock(ScriptedCheckerService.class);
        scriptedRepo = mock(ScriptedMonitorRepository.class);
        scriptedCheckRepo = mock(ScriptedCheckRepository.class);

        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(MonitoringOutageService.class, outage);
        provided.put(EscalationService.class, escalation);
        provided.put(ActivityLogService.class, activity);
        provided.put(AppSettingsService.class, appSettings);
        provided.put(CertificateInventoryRepository.class, inventoryRepo);
        provided.put(JdbcTemplate.class, jdbc);
        provided.put(PortCheckerService.class, portChecker);
        provided.put(PortMonitorRepository.class, portRepo);
        provided.put(PortCheckRepository.class, portCheckRepo);
        provided.put(KeywordCheckerService.class, keywordChecker);
        provided.put(KeywordMonitorRepository.class, keywordRepo);
        provided.put(KeywordResultRepository.class, keywordResultRepo);
        provided.put(HttpCheckerService.class, httpChecker);
        provided.put(HttpMonitorRepository.class, httpRepo);
        provided.put(HttpCheckRepository.class, httpCheckRepo);
        provided.put(PingCheckerService.class, pingChecker);
        provided.put(PingMonitorRepository.class, pingRepo);
        provided.put(PingCheckRepository.class, pingCheckRepo);
        provided.put(DnsCheckerService.class, dnsChecker);
        provided.put(DnsMonitorRepository.class, dnsRepo);
        provided.put(DnsRecordRepository.class, dnsRecordRepo);
        scheduler = build(SchedulerService.class, provided);
        ReflectionTestUtils.setField(scheduler, "orphanCleanupIntervalMs", 300_000L);
        ReflectionTestUtils.setField(scheduler, "keywordHeaderSecrets", keywordHeaders);
        ReflectionTestUtils.setField(scheduler, "pageCheckerService", pageChecker);
        ReflectionTestUtils.setField(scheduler, "pageMonitorRepo", pageRepo);
        ReflectionTestUtils.setField(scheduler, "pageCheckRepo", pageCheckRepo);
        ReflectionTestUtils.setField(scheduler, "pageResourceIssueRepo", pageIssueRepo);
        ReflectionTestUtils.setField(scheduler, "pageSpeedCheckerService", pageSpeedChecker);
        ReflectionTestUtils.setField(scheduler, "pageSpeedMonitorRepo", pageSpeedRepo);
        ReflectionTestUtils.setField(scheduler, "pageSpeedCheckRepo", pageSpeedCheckRepo);
        ReflectionTestUtils.setField(scheduler, "pageSpeedResourceRepo", pageSpeedResourceRepo);
        ReflectionTestUtils.setField(scheduler, "scriptedCheckerService", scriptedChecker);
        ReflectionTestUtils.setField(scheduler, "scriptedMonitorRepo", scriptedRepo);
        ReflectionTestUtils.setField(scheduler, "scriptedCheckRepo", scriptedCheckRepo);

        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("inv-host.example.com");
        inv.setActive(true);
        lenient().when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Port
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    private static PortMonitor port(long id, String host, int p, boolean standalone) {
        PortMonitor m = new PortMonitor();
        m.setId(id);
        m.setName("Port " + id);
        m.setHost(host);
        m.setPort(p);
        m.setStandalone(standalone);
        m.setTeamId(standalone ? 7L : null);
        m.setIntervalSeconds(60);
        return m;
    }

    @Test
    @DisplayName("port: PORT_DOWN + satır içi PORT_SLOW (ölçüldü / ölçülemedi / kapalı), envanter dışı türev sessiz kapanış, istisna kalem üretmez")
    void portGolden() throws Exception {
        portFixture(500);
        assertGolden("port", "runPortChecksLocked", "PORT_DOWN", "PORT_SLOW", "resolveOpenAlertsSilently",
                "resolveOrphanedPortAlerts", "\"up\": false", "\"up\": true", "mail_disabled", "push_disabled");
    }

    @Test
    @DisplayName("döküm DUYARLI: tek eşik farkı (500 → 501 ms) kaydı değiştirir — karşılaştırma boş değil")
    void goldenDetectsDrift() throws Exception {
        if (WRITE) return;
        portFixture(501);
        Object[] recorded = recordedMocks();
        Mockito.clearInvocations(recorded);
        ReflectionTestUtils.invokeMethod(scheduler, "runPortChecksLocked");
        String drifted = transcript("port", recorded);
        String expected = Files.readString(GOLDEN.resolve("port.json"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(drifted).isNotEqualTo(expected).contains("\"threshold_ms\": 501");
    }

    private void portFixture(int thresholdA) {
        PortMonitor a = port(1, "a.example.com", 443, true);
        a.setSlowResponseEnabled(true);
        a.setSlowThresholdMs(thresholdA);
        PortMonitor b = port(2, "b.example.com", 8443, true);
        b.setSlowResponseEnabled(true);
        b.setSlowThresholdMs(500);
        b.setProtocol("UDP");
        PortMonitor c = port(3, "c.example.com", 22, true);
        c.setSlowResponseEnabled(true);
        PortMonitor d = port(4, "inv-host.example.com", 443, false);     // envanterde → kontrol
        PortMonitor e = port(5, "gone-host.example.com", 443, false);    // envanterde DEĞİL → atla + sessiz kapanış
        PortMonitor f = port(6, "f.example.com", 25, true);
        f.setNotifyEmail(false);
        f.setNotifyWebhook(false);
        f.setAlertLevel("CRITICAL");
        f.setNotificationGroupId(33L);
        f.setConfirmAttempts(5);
        f.setConfirmIntervalSeconds(10);
        f.setRecoveryChecks(2);
        f.setRecoveryIntervalSeconds(20);
        f.setProtocol(null);
        PortMonitor g = port(7, "boom.example.com", 80, true);           // denetleyici istisna
        List<PortMonitor> all = List.of(a, b, c, d, e, f, g);
        when(portRepo.findByActiveTrue()).thenReturn(all);
        when(portRepo.findAll()).thenReturn(all);
        when(portChecker.check(any(PortMonitor.class))).thenAnswer(i -> {
            PortMonitor m = i.getArgument(0);
            return switch (m.getId().intValue()) {
                case 1 -> map("open", true, "response_ms", 120L);
                case 2 -> map("open", true, "response_ms", 900L);
                case 3 -> map("open", false, "error", "Connection refused", "response_ms", 15L);
                case 4 -> map("open", false, "error", "Connect timed out");
                case 6 -> map("open", true, "response_ms", 40L);
                default -> throw new IllegalStateException("soket açılamadı");
            };
        });
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Keyword
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    private static KeywordMonitor keyword(long id, String url, String kw) {
        KeywordMonitor m = new KeywordMonitor();
        m.setId(id);
        m.setName("Keyword " + id);
        m.setUrl(url);
        m.setKeyword(kw);
        m.setTeamId(9L);
        m.setIntervalSeconds(60);
        return m;
    }

    @Test
    @DisplayName("keyword: KEYWORD + satır içi KEYWORD_SLOW (açık / kapalı / ölçülemedi), adet koşulu, yapılandırma hatası, uzun anahtar sözcük, istisna")
    void keywordGolden() throws Exception {
        KeywordMonitor a = keyword(1, "https://a.example.com/", "Hoş geldiniz");
        a.setSlowResponseEnabled(true);
        a.setSlowThresholdMs(500);
        KeywordMonitor b = keyword(2, "https://b.example.com/", "Ödeme");
        b.setSlowResponseEnabled(true);
        b.setSlowThresholdMs(500);
        KeywordMonitor c = keyword(3, "https://c.example.com/", "x");
        c.setSlowResponseEnabled(true);
        KeywordMonitor d = keyword(4, "c-yok", "y");
        KeywordMonitor e = keyword(5, "https://e.example.com/", "z".repeat(55));
        e.setMatchOperator("LTE");
        e.setMatchCount(1);
        e.setNotificationGroupId(12L);
        e.setNotifyWebhook(false);
        e.setAlertLevel("HIGH");
        KeywordMonitor f = keyword(6, "https://boom.example.com/", "q");
        List<KeywordMonitor> all = List.of(a, b, c, d, e, f);
        when(keywordRepo.findByActiveTrue()).thenReturn(all);
        when(keywordRepo.findAll()).thenReturn(all);
        when(keywordHeaders.effectiveHeaders(any(KeywordMonitor.class))).thenReturn(null);
        when(keywordChecker.check(anyString(), anyString(), anyInt(), any(), anyBoolean(), anyBoolean(),
                any(KeywordCheckerService.Expectation.class))).thenAnswer(i -> {
            String url = i.getArgument(0);
            return switch (url) {
                case "https://a.example.com/" -> map("found", true, "count", 3, "http_status", 200, "response_ms", 100L,
                        "snippet", "… Hoş geldiniz …");
                case "https://b.example.com/" -> map("found", false, "count", 0, "http_status", 200, "response_ms", 900L);
                case "https://c.example.com/" -> map("found", false, "http_status", 503, "response_ms", 30L,
                        "error", "HTTP 503");
                case "c-yok" -> map("found", false, "error", "URL'de host yok", "config_error", true);
                case "https://e.example.com/" -> map("found", true, "count", 2, "http_status", 200, "response_ms", 50L);
                default -> throw new IllegalStateException("bağlantı kurulamadı");
            };
        });
        assertGolden("keyword", "runKeywordChecksLocked", "\"KEYWORD\"", "KEYWORD_SLOW", "resolveOrphanedKeywordAlerts",
                "config_error", "\"up\": false", "\"up\": true");
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // HTTP
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    private static HttpMonitor http(long id, String url) {
        HttpMonitor m = new HttpMonitor();
        m.setId(id);
        m.setName("HTTP " + id);
        m.setUrl(url);
        m.setTeamId(4L);
        m.setIntervalSeconds(60);
        return m;
    }

    @Test
    @DisplayName("http: HTTP_DOWN + HTTP_SLOW (yalnız açık izlemede, ölçüldüyse), yapılandırma hatası, JSON doğrulaması, istisna")
    void httpGolden() throws Exception {
        HttpMonitor a = http(1, "https://a.example.com/health");
        a.setSlowResponseEnabled(true);
        a.setSlowThresholdMs(1000);
        HttpMonitor b = http(2, "https://b.example.com/health");
        b.setSlowResponseEnabled(true);
        b.setSlowThresholdMs(1000);
        b.setMethod("HEAD");
        HttpMonitor c = http(3, "https://c.example.com/health");
        c.setSlowResponseEnabled(true);
        HttpMonitor d = http(4, "c-yok-http");
        HttpMonitor e = http(5, "https://e.example.com/api");
        e.setNotifyEmail(false);
        e.setNotificationGroupId(21L);
        HttpMonitor f = http(6, "https://boom.example.com/");
        List<HttpMonitor> all = List.of(a, b, c, d, e, f);
        when(httpRepo.findByActiveTrue()).thenReturn(all);
        when(httpRepo.findAll()).thenReturn(all);
        when(httpChecker.check(anyString(), any(), any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean())).thenAnswer(i -> {
            String url = i.getArgument(0);
            return switch (url) {
                case "https://a.example.com/health" -> map("ok", true, "http_status", 200, "response_ms", 200L);
                case "https://b.example.com/health" -> map("ok", true, "http_status", 200, "response_ms", 4000L);
                case "https://c.example.com/health" -> map("ok", false, "http_status", 503, "response_ms", 80L,
                        "error", "HTTP 503 Service Unavailable");
                case "c-yok-http" -> map("ok", false, "error", "URL'de host yok", "config_error", true);
                case "https://e.example.com/api" -> map("ok", false, "http_status", 200, "response_ms", 60L,
                        "error", "JSON doğrulaması başarısız: $.status", "json_assertion_failed", true);
                default -> throw new IllegalStateException("TLS el sıkışması düştü");
            };
        });
        assertGolden("http", "runHttpChecksLocked", "HTTP_DOWN", "HTTP_SLOW", "resolveOrphanedHttpAlerts",
                "json_assertion_failed", "config_error", "\"up\": false", "\"up\": true");
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Ping
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    private static PingMonitor ping(long id, String host) {
        PingMonitor m = new PingMonitor();
        m.setId(id);
        m.setName("Ping " + id);
        m.setHost(host);
        m.setTeamId(3L);
        m.setIntervalSeconds(60);
        return m;
    }

    @Test
    @DisplayName("ping: PING_DOWN + PING_SLOW (taban çizgisi yeterli / yetersiz, yavaş / hızlı), ICMP yok (na), düşük host, istisna")
    void pingGolden() throws Exception {
        PingMonitor a = ping(1, "10.0.0.1");
        a.setSlowResponseEnabled(true);
        PingMonitor b = ping(2, "10.0.0.2");
        b.setSlowResponseEnabled(true);
        PingMonitor c = ping(3, "10.0.0.3");
        c.setSlowResponseEnabled(true);
        PingMonitor d = ping(4, "10.0.0.4");                             // na, yavaşlık kapalı
        PingMonitor e = ping(5, "10.0.0.5");
        e.setSlowResponseEnabled(true);                                   // taban çizgisi yetersiz
        e.setIpVersion("v6");
        e.setNotifyEmail(false);
        PingMonitor f = ping(6, "10.0.0.6");
        List<PingMonitor> all = List.of(a, b, c, d, e, f);
        when(pingRepo.findByActiveTrue()).thenReturn(all);
        when(pingRepo.findAll()).thenReturn(all);
        when(pingChecker.check(anyString(), any(), anyInt(), anyInt())).thenAnswer(i -> {
            String host = i.getArgument(0);
            return switch (host) {
                case "10.0.0.1" -> map("up", true, "rtt_ms", 20L, "packet_loss", 0);
                case "10.0.0.2" -> map("up", true, "rtt_ms", 11L, "packet_loss", 0);
                case "10.0.0.3" -> map("up", false, "packet_loss", 100, "error", "Yanıt yok (%100 paket kaybı)");
                case "10.0.0.4" -> map("up", false, "na", true, "error", "ICMP bu ortamda kullanılamıyor (yetki/binary)");
                case "10.0.0.5" -> map("up", true, "rtt_ms", 30L, "packet_loss", 25);
                default -> throw new IllegalStateException("alt süreç başlatılamadı");
            };
        });
        when(pingCheckRepo.slowBaseline(eq(1L), anyString(), anyString())).thenReturn(rows(10.0, 5L));
        when(pingCheckRepo.slowBaseline(eq(2L), anyString(), anyString())).thenReturn(rows(10.0, 5L));
        when(pingCheckRepo.slowBaseline(eq(5L), anyString(), anyString())).thenReturn(rows(10.0, 2L));
        assertGolden("ping", "runPingChecksLocked", "PING_DOWN", "PING_SLOW", "resolveOrphanedPingAlerts",
                "baseline_ms", "limit_ms", "\"up\": false", "\"up\": true");
    }

    private static List<Object[]> rows(double avg, long samples) {
        List<Object[]> out = new ArrayList<>();
        out.add(new Object[]{avg, samples});
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // DNS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    private static DnsMonitor dns(long id, String domain, String type, boolean standalone) {
        DnsMonitor m = new DnsMonitor();
        m.setId(id);
        m.setName("DNS " + id);
        m.setDomain(domain);
        m.setRecordType(type);
        m.setStandalone(standalone);
        m.setTeamId(standalone ? 5L : null);
        m.setIntervalSeconds(300);
        return m;
    }

    private static DnsRecord prevRecord(String value) {
        DnsRecord r = new DnsRecord();
        r.setId(99L);
        r.setValue(value);
        return r;
    }

    @Test
    @DisplayName("dns: DNS_FAILURE + DNS_SLOW + DNS_UNEXPECTED + DNS_INCONSISTENT + DnsChange (değişti / döndü / bastırıldı) TEK handleDnsSweep'te; dns_records change_state")
    void dnsGolden() throws Exception {
        DnsMonitor a = dns(1, "a.example.com", "A", true);
        a.setExpectedValue("1.2.3.4");
        DnsMonitor b = dns(2, "b.example.com", "A", true);                 // CHANGED + yavaş
        DnsMonitor c = dns(3, "inv-host.example.com", "MX", false);        // türev, envanterde, başarısız
        DnsMonitor d = dns(4, "gone.example.com", "A", false);             // türev, envanterde değil → atla
        DnsMonitor e = dns(5, "e.example.com", "A", true);                 // yayılım tutarsız + beklenmeyen değer
        e.setPropagationCheck(true);
        e.setExpectedValue("9.9.9.9\n8.8.8.8");
        e.setNotifyWebhook(false);
        e.setAlertLevel("CRITICAL");
        e.setNotificationGroupId(44L);
        DnsMonitor f = dns(6, "f.example.com", "A", true);                 // CHANGED ama değişiklik alarmı kapalı
        f.setDnsChangeAlertEnabled(false);
        DnsMonitor g = dns(7, "g.example.com", "A", true);                 // ROTATED
        g.setSlowThresholdMs(5000);
        DnsMonitor h = dns(8, "boom.example.com", "TXT", true);           // istisna
        List<DnsMonitor> all = List.of(a, b, c, d, e, f, g, h);
        when(dnsRepo.findByActiveTrue()).thenReturn(all);
        when(dnsRepo.findAll()).thenReturn(all);
        when(dnsChecker.check(anyString(), anyString())).thenAnswer(i -> {
            String domain = i.getArgument(0);
            return switch (domain) {
                case "a.example.com" -> map("success", true, "values", List.of("1.2.3.4"), "ttl", 300L, "response_ms", 30L);
                case "b.example.com" -> map("success", true, "values", List.of("5.6.7.8"), "ttl", 60L, "response_ms", 2000L);
                case "inv-host.example.com" -> map("success", false, "values", List.of(), "error", "NXDOMAIN",
                        "response_ms", 12L);
                case "e.example.com" -> map("success", true, "values", List.of("9.9.9.9", "7.7.7.7"), "response_ms", 40L);
                case "f.example.com" -> map("success", true, "values", List.of("3.3.3.3"), "response_ms", 20L);
                case "g.example.com" -> map("success", true, "values", List.of("2.2.2.2", "3.3.3.3"), "response_ms", 25L);
                default -> throw new IllegalStateException("çözümleyici yanıt vermedi");
            };
        });
        Map<String, String> perResolver = new LinkedHashMap<>();
        perResolver.put("8.8.8.8", "9.9.9.9");
        perResolver.put("1.1.1.1", "7.7.7.7");
        when(dnsChecker.checkPropagation(eq("e.example.com"), eq("A"), anyList()))
                .thenAnswer(i -> map("inconsistent", true, "per_resolver", perResolver));
        when(dnsRecordRepo.findLatestScheduledSuccessful(anyLong())).thenAnswer(i -> {
            long id = i.getArgument(0);
            if (id == 2L) return Optional.of(prevRecord("1.2.3.4"));
            if (id == 6L) return Optional.of(prevRecord("4.4.4.4"));
            if (id == 7L) return Optional.of(prevRecord("1.1.1.1\n2.2.2.2"));
            return Optional.empty();
        });
        assertGolden("dns", "runDnsChecksLocked", "handleDnsSweep", "DNS_FAILURE", "DNS_SLOW", "DNS_UNEXPECTED",
                "DNS_INCONSISTENT", "\"detectedAt\": \"<time>\"", "\"changeState\": \"PENDING\"",
                "\"changeState\": \"SUPPRESSED\"", "\"rotated\": true", "resolveOpenAlertsSilently");
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Page (yalnız SINGLE_PAGE sık tarama)
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    private static PageMonitor page(long id, String url) {
        PageMonitor m = new PageMonitor();
        m.setId(id);
        m.setName("Sayfa " + id);
        m.setUrl(url);
        m.setTeamId(6L);
        m.setIntervalSeconds(300);
        return m;
    }

    private static PageCheckerService.PageCheckResult pageResult(String status, boolean main, Integer http, int broken,
                                                                  int timeouts, int mixed, String error,
                                                                  List<PageCheckerService.ResourceIssue> issues) {
        return new PageCheckerService.PageCheckResult(status, main, http, 350L, 12, broken, timeouts, mixed, 1,
                "hash-" + status, 4096L, error, issues);
    }

    @Test
    @DisplayName("page: PAGE_DOWN + PAGE_INTEGRITY (temiz / bulgu + kaynak sayfa / ana sayfa düştü / yapılandırma hatası / derin tarama izlemesinin ana sayfa turu, açık alarm kaynağına göre), zaman aşımı anahtarı, istisna")
    void pageGolden() throws Exception {
        PageMonitor a = page(1, "https://a.example.com/");
        PageMonitor b = page(2, "https://b.example.com/");
        PageMonitor c = page(3, "https://c.example.com/");
        PageMonitor d = page(4, "c-yok-page");
        PageMonitor e = page(5, "https://crawl-home.example.com/");
        e.setMode("SITE_CRAWL");
        PageMonitor f = page(6, "https://crawl-none.example.com/");
        f.setMode("SITE_CRAWL");
        PageMonitor g = page(7, "https://g.example.com/");
        g.setAlertTimeout(false);
        g.setNotifyEmail(false);
        PageMonitor h = page(8, "https://boom.example.com/");
        List<PageMonitor> all = List.of(a, b, c, d, e, f, g, h);
        when(pageRepo.findByActiveTrue()).thenReturn(all);
        when(pageRepo.findAll()).thenReturn(all);
        PageCheckerService.ResourceIssue broken = new PageCheckerService.ResourceIssue(
                "https://b.example.com/app.js", "SCRIPT", "https://b.example.com/urunler", "BROKEN", true, 404, 120L);
        PageCheckerService.ResourceIssue timeout = new PageCheckerService.ResourceIssue(
                "https://cdn.example.net/x.css", "STYLESHEET", "https://g.example.com/", "TIMEOUT", false, null, 4000L);
        when(pageChecker.check(anyString(), anyString(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), anyInt(),
                anyBoolean())).thenAnswer(i -> {
            String url = i.getArgument(0);
            return switch (url) {
                case "https://a.example.com/", "https://crawl-home.example.com/", "https://crawl-none.example.com/" ->
                        pageResult("OK", true, 200, 0, 0, 0, null, List.of());
                case "https://b.example.com/" -> pageResult("DEGRADED", true, 200, 1, 0, 0, null, List.of(broken));
                case "https://c.example.com/" -> pageResult("DOWN", false, 502, 0, 0, 0, "HTTP 502", List.of());
                case "c-yok-page" -> pageResult("CONFIG_ERROR", false, null, 0, 0, 0, "URL'de host yok", List.of());
                case "https://g.example.com/" -> pageResult("DEGRADED", true, 200, 0, 1, 0, null, List.of(timeout));
                default -> throw new IllegalStateException("sayfa alınamadı");
            };
        });
        when(escalation.openAlertContext(eq("https://crawl-home.example.com/"), eq(EscalationService.TYPE_PAGE_INTEGRITY)))
                .thenReturn(map("integrity_scope", "HOME"));
        when(escalation.openAlertContext(eq("https://crawl-none.example.com/"), eq(EscalationService.TYPE_PAGE_INTEGRITY)))
                .thenReturn(null);
        assertGolden("page", "runPageChecksLocked", "PAGE_DOWN", "PAGE_INTEGRITY", "\"integrity_scope\": \"HOME\"",
                "problem_rows", "openAlertContext", "resolveOrphanedPageAlerts", "config_error", "\"up\": false");
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // PageSpeed
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    private static PageSpeedMonitor pageSpeed(long id, String url) {
        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setId(id);
        m.setName("Hız " + id);
        m.setUrl(url);
        m.setTeamId(8L);
        m.setIntervalSeconds(1800);
        m.setMaxLoadMs(3000);
        m.setMaxPageKb(500);
        return m;
    }

    private static PageSpeedCheckerService.Result speed(String status, Integer code, long totalMs, long bytes,
                                                        boolean truncated, List<String> breached, String error,
                                                        List<PageSpeedCheckerService.Measured> resources) {
        return new PageSpeedCheckerService.Result(status, code, 180L, 400L, totalMs, bytes, 14, 0, false, truncated,
                breached, error, resources, new HttpPhaseProbe.Phases(5, 10, 20, 145, null), 0);
    }

    @Test
    @DisplayName("pagespeed: PAGESPEED_DOWN + PAGESPEED_SLOW (eşik içinde / aşıldı + kırpılmış boyut / alınamadı / yapılandırma hatası), ağır kaynak satırları, istisna")
    void pageSpeedGolden() throws Exception {
        PageSpeedMonitor a = pageSpeed(1, "https://a.example.com/");
        PageSpeedMonitor b = pageSpeed(2, "https://b.example.com/");
        PageSpeedMonitor c = pageSpeed(3, "https://c.example.com/");
        PageSpeedMonitor d = pageSpeed(4, "c-yok-speed");
        PageSpeedMonitor e = pageSpeed(5, "https://boom.example.com/");
        e.setNotifyWebhook(false);
        List<PageSpeedMonitor> all = List.of(a, b, c, d, e);
        when(pageSpeedRepo.findByActiveTrue()).thenReturn(all);
        when(pageSpeedRepo.findAll()).thenReturn(all);
        List<PageSpeedCheckerService.Measured> res = List.of(
                new PageSpeedCheckerService.Measured("https://b.example.com/hero.jpg", "image", 900_000L, 300L, 200,
                        false, false, false),
                new PageSpeedCheckerService.Measured("https://cdn.example.net/app.js", "script", 120_000L, 90L, 200,
                        true, false, true));
        when(pageSpeedChecker.check(any(PageSpeedMonitor.class))).thenAnswer(i -> {
            PageSpeedMonitor m = i.getArgument(0);
            return switch (m.getId().intValue()) {
                case 1 -> speed("OK", 200, 1200L, 200_000L, false, List.of(), null, List.of());
                case 2 -> speed("SLOW", 200, 5200L, 1_020_000L, true, List.of("LOAD", "SIZE"), null, res);
                case 3 -> speed("DOWN", 503, 90L, 0L, false, List.of(), "HTTP 503", List.of());
                case 4 -> speed("CONFIG_ERROR", null, 0L, 0L, false, List.of(), "URL'de host yok", List.of());
                default -> throw new IllegalStateException("ölçüm düştü");
            };
        });
        assertGolden("pagespeed", "runPageSpeedChecksLocked", "PAGESPEED_DOWN", "PAGESPEED_SLOW",
                "resolveOrphanedPageSpeedAlerts", "heavy_rows", "breached_metrics", "config_error", "\"up\": false");
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Scripted (k6)
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    private static ScriptedMonitor scripted(long id, String name) {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setId(id);
        m.setName(name);
        m.setScript("export default function () {}");
        m.setScriptVersion("v" + id);
        m.setTeamId(2L);
        m.setIntervalSeconds(300);
        return m;
    }

    private static ScriptedCheckerService.ScriptedResult k6(String status, boolean ok, Long durationMs, Integer passed,
                                                           Integer failed, String checksJson, String tail, String error) {
        return new ScriptedCheckerService.ScriptedResult(status, ok, durationMs, status.equals("SKIPPED") ? -1 : 0,
                passed, failed, durationMs, 80L, 120L, checksJson, tail, error, false,
                ScriptedCheckerService.Phases.EMPTY);
    }

    @Test
    @DisplayName("scripted: SCRIPTED_FAIL + SCRIPTED_SLOW (açık hızlı / yavaş, düşen koşum, kapalı), SKIPPED kalem ve kayıt üretmez, NO_CHECKS, gelecek istisnası")
    void scriptedGolden() throws Exception {
        ScriptedMonitor a = scripted(1, "Giriş akışı");
        a.setSlowResponseEnabled(true);
        a.setSlowThresholdMs(1000);
        ScriptedMonitor b = scripted(2, "Sepet akışı");
        b.setSlowResponseEnabled(true);
        b.setSlowThresholdMs(1000);
        ScriptedMonitor c = scripted(3, "Ödeme akışı");
        c.setSlowResponseEnabled(true);
        ScriptedMonitor d = scripted(4, "Atlanan akış");
        ScriptedMonitor e = scripted(5, "Boş akış");
        e.setNotifyEmail(false);
        ScriptedMonitor f = scripted(6, "Düşen gelecek");
        ScriptedMonitor g = scripted(7, "Düşen kapalı");
        List<ScriptedMonitor> all = List.of(a, b, c, d, e, f, g);
        when(scriptedRepo.findByActiveTrue()).thenReturn(all);
        when(scriptedRepo.findAll()).thenReturn(all);
        String failedJson = "[{\"name\":\"durum 200\",\"passed\":true},{\"name\":\"gövde ok\",\"passed\":false}]";
        when(scriptedChecker.submit(any(ScriptedMonitor.class))).thenAnswer(i -> {
            ScriptedMonitor m = i.getArgument(0);
            return switch (m.getId().intValue()) {
                case 1 -> CompletableFuture.completedFuture(k6("PASS", true, 500L, 3, 0, "[]", null, null));
                case 2 -> CompletableFuture.completedFuture(k6("PASS", true, 3000L, 3, 0, "[]", null, null));
                case 3 -> CompletableFuture.completedFuture(k6("FAIL", false, 800L, 1, 1, failedJson,
                        "level=error msg=\"request timeout\"", "request timeout"));
                case 4 -> CompletableFuture.completedFuture(k6(ScriptedCheckerService.STATUS_SKIPPED, false, null, null,
                        null, null, null, "k6 havuzu dolu — kontrol atlandı (sıra beklemesi aşıldı)"));
                case 5 -> CompletableFuture.completedFuture(k6("NO_CHECKS", false, 300L, 0, 0, "[]", null, null));
                case 6 -> CompletableFuture.failedFuture(new IllegalStateException("k6 süreci çöktü"));
                default -> CompletableFuture.completedFuture(k6("FAIL", false, 900L, 0, 2, failedJson, null, "HTTP 500"));
            };
        });
        assertGolden("scripted", "runScriptedChecksLocked", "SCRIPTED_FAIL", "SCRIPTED_SLOW",
                "resolveOrphanedScriptedAlerts", "failed_checks", "\"up\": false", "\"up\": true");
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Döküm
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Klasik gövdeyi koşturur, sıralı dökümü altın kayıtla karşılaştırır (yazma kipinde yazar). */
    private void assertGolden(String type, String lockedBody, String... mustContain) throws IOException {
        Object[] recorded = recordedMocks();
        Mockito.clearInvocations(recorded);
        ReflectionTestUtils.invokeMethod(scheduler, lockedBody);
        String actual = transcript(type, recorded);
        // Senaryo kapsamı kendi kendini denetler: senaryo bir kalem / kararı artık üretmiyorsa kayıt sessizce zayıflamasın.
        assertThat(actual).as(type + " senaryosu beklenen kalemleri / kararları üretmeli").contains(mustContain)
                .contains("\"recheck\": true");
        Path file = GOLDEN.resolve(type + ".json");
        if (WRITE) {
            Files.createDirectories(GOLDEN);
            Files.writeString(file, actual, StandardCharsets.UTF_8);
            return;
        }
        assertThat(Files.exists(file))
                .as("altın kayıt yok: " + file + " — 20.169.0 kodundan kaydetmek için -Dr33.writeGolden=true")
                .isTrue();
        String expected = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(actual)
                .as("klasik " + type + " taraması 20.169.0'dan SAPTI (K-1 / K-3) — ayrıştırma saf olmalı; bilerek "
                        + "değiştirildiyse gerekçesiyle yeniden kaydedin (-Dr33.writeGolden=true)")
                .isEqualTo(expected);
    }

    private Object[] recordedMocks() {
        return new Object[]{outage, escalation, activity, portChecker, portCheckRepo, keywordChecker, keywordResultRepo,
                httpChecker, httpCheckRepo, pingChecker, pingCheckRepo, dnsChecker, dnsRecordRepo, pageChecker,
                pageCheckRepo, pageIssueRepo, pageSpeedChecker, pageSpeedCheckRepo, pageSpeedResourceRepo, scriptedChecker,
                scriptedCheckRepo};
    }

    private static final Set<String> REPO_WRITES = Set.of("save", "saveAll", "replaceLatest");
    private static final Set<String> CHECKER_CALLS = Set.of("check", "checkPropagation", "submit");

    /** Kaydedilen çağrının adı / sahibi döküme girer mi (okumalar ve tanı çağrıları girmez). */
    private boolean recordable(Object mock, String method) {
        if (mock == outage || mock == escalation) return true;
        if (mock == activity) return "recordCheck".equals(method);
        String cls = Mockito.mockingDetails(mock).getMockCreationSettings().getTypeToMock().getSimpleName();
        if (cls.endsWith("Repository")) return REPO_WRITES.contains(method);
        return CHECKER_CALLS.contains(method);
    }

    private String transcript(String type, Object[] mocks) {
        List<Invocation> all = new ArrayList<>();
        Map<Invocation, Object> owner = new HashMap<>();
        for (Object m : mocks) {
            for (Invocation inv : Mockito.mockingDetails(m).getInvocations()) {
                if (!recordable(m, inv.getMethod().getName())) continue;
                all.add(inv);
                owner.put(inv, m);
            }
        }
        all.sort(Comparator.comparingInt(Invocation::getSequenceNumber));
        // Üç bölüm: KARARLAR (MOS + Escalation, sıralı), YAZIMLAR (geçmiş satırı + etkinlik, sıralı) ve KONTROLLER (denetleyici
        // çağrıları, ÇOKLU KÜME — sıraları üretimde eşzamanlı yürütücüye bağlıdır; burada yürütücü yok, satır içi koşar).
        List<Object> decisions = new ArrayList<>();
        List<Object> writes = new ArrayList<>();
        List<Object> checks = new ArrayList<>();
        for (Invocation inv : all) {
            Object m = owner.get(inv);
            String on = Mockito.mockingDetails(m).getMockCreationSettings().getTypeToMock().getSimpleName();
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("on", on);
            call.put("method", inv.getMethod().getName());
            List<Object> args = new ArrayList<>();
            for (Object a : inv.getArguments()) args.add(norm(a, 0));
            call.put("args", args);
            if (m == outage || m == escalation) decisions.add(call);
            else if (m == activity || on.endsWith("Repository")) writes.add(call);
            else checks.add(call);
        }
        checks.sort(Comparator.comparing(Json::write));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", type);
        root.put("decisions", decisions);
        root.put("writes", writes);
        root.put("checks", checks);
        return Json.write(root) + "\n";
    }

    // ── normalleştirme ───────────────────────────────────────────────────────────────────────────────────────────────

    private static final Pattern ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z?");

    static Object norm(Object v, int depth) {
        if (depth > 40) return "<deep>";
        if (v == null || v instanceof Boolean || v instanceof Number) return v;
        if (v instanceof CharSequence cs) {
            String s = cs.toString();
            return s.length() <= 40 && ISO.matcher(s).matches() ? "<time>" : s;
        }
        if (v instanceof Enum<?> e) return e.name();
        if (v instanceof Supplier<?>) return "<supplier>";
        if (v instanceof MonitoringOutageService.SweepItem it) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("alertType", it.alertType());
            m.put("domain", it.domain());
            m.put("detail", it.detail());
            m.put("up", it.up());
            m.put("error", norm(it.error(), depth + 1));
            m.put("ctx", norm(it.ctxExtra(), depth + 1));
            m.put("recheck", it.recheck() != null);
            return m;
        }
        if (v instanceof MonitoringOutageService.DnsChange ch) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (RecordComponent rc : ch.getClass().getRecordComponents()) {
                Object val = invoke(rc, ch);
                m.put(rc.getName(), "recheck".equals(rc.getName()) ? (Object) (val != null) : norm(val, depth + 1));
            }
            return m;
        }
        if (v instanceof Optional<?> o) return o.map(x -> norm(x, depth + 1)).orElse(null);
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = (map instanceof LinkedHashMap || map instanceof SortedMap)
                    ? new LinkedHashMap<>() : new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), norm(e.getValue(), depth + 1));
            return out;
        }
        if (v instanceof Set<?> set) {
            List<Object> out = new ArrayList<>();
            for (Object o : set) out.add(norm(o, depth + 1));
            out.sort(Comparator.comparing(o -> Json.write(o)));
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object o : c) out.add(norm(o, depth + 1));
            return out;
        }
        if (v.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            int n = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < n; i++) out.add(norm(java.lang.reflect.Array.get(v, i), depth + 1));
            return out;
        }
        Class<?> cls = v.getClass();
        String pkg = cls.getPackageName();
        if (pkg.equals("com.sitemonitor.model")) {
            if (cls.getSimpleName().endsWith("Monitor")) return cls.getSimpleName() + "#" + fieldValue(v, "id");
            Map<String, Object> m = new TreeMap<>();
            for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                    m.put(f.getName(), norm(fieldValue(v, f), depth + 1));
                }
            }
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("entity", cls.getSimpleName());
            wrapped.put("fields", m);
            return wrapped;
        }
        if (cls.isRecord()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (RecordComponent rc : cls.getRecordComponents()) m.put(rc.getName(), norm(invoke(rc, v), depth + 1));
            return m;
        }
        return String.valueOf(v);
    }

    private static Object invoke(RecordComponent rc, Object target) {
        try {
            var acc = rc.getAccessor();
            acc.setAccessible(true);
            return acc.invoke(target);
        } catch (ReflectiveOperationException e) {
            return "<unreadable>";
        }
    }

    private static Object fieldValue(Object target, String name) {
        for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                return fieldValue(target, k.getDeclaredField(name));
            } catch (NoSuchFieldException ignore) {
                // üst sınıfa bak
            }
        }
        return null;
    }

    private static Object fieldValue(Object target, Field f) {
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "<unreadable>";
        }
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** Küçük, kararlı JSON yazıcı (2 boşluk girinti, LF; sayı / mantıksal / metin / liste / harita). */
    static final class Json {
        private Json() {
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
