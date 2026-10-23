package com.sitemonitor.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.it.MultiPodTestSupport.Pod;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.service.health.ClusterHealthSchemaPatch;
import com.sitemonitor.service.health.ClusterHealthStore;
import com.sitemonitor.service.retention.RetentionService;
import com.sitemonitor.service.schedule.ClusterLockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sistem Sağlığı KÜME doğruluğu (2026-10-23, çok pod R2b, paket C1) — iki {@link SchedulerService} aynı H2 veritabanını ve
 * ortak saati paylaşır ({@link MultiPodTestSupport}):
 * <ul>
 *   <li>MP-34: taramayı hiç koşturmamış pod kümenin son koşusunu gösterir, sahte "tarama 2 saattir yok" bandı yok; canlı
 *       {@code cert-check} kilidi "çalışıyor" + hangi pod; süresi dolmuş satır kilitli sayılmaz; bayat tur saatlik / tam turun
 *       satırını ezmez; düşen tur hatası görünür, son başarılı sayılar korunur.</li>
 *   <li>N3: yapılandırma sağlığının "zamanlayıcı" denetimi takipçide "okunamadı" uyarısı vermez.</li>
 *   <li>Gece temizliği kilidi (R2a isteği): yöneticinin koşusu kilidi tutarken yeniden dener; o gecenin turu sahiplenildiyse
 *       beklemez; süre ve kapanış sınırı.</li>
 *   <li>MP-37d: her pod kendi izleme vade önbelleğini budar; liderlik bitince önbellek boşalır.</li>
 *   <li>Günlük hacmi: "başka pod'da, atlanıyor" satırı saatte bir kez DEBUG, aradakiler TRACE.</li>
 * </ul>
 */
class SystemHealthClusterMultiPodTest {

    private static final long SEC = 1_000L;
    private static final long MIN = 60 * SEC;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    final MultiPodTestSupport mp = MultiPodTestSupport.create();

    @BeforeEach
    void schema() {
        new ClusterHealthSchemaPatch(mp.jdbc()).apply();
    }

    @AfterEach
    void close() {
        mp.close();
    }

    Pod pod(int n) {
        return mp.pod(n, s -> {
            SchedulerMultiPodCoordinationTest.tune(s);
            // alan enjeksiyonlu depolar (üretimde dolu) — günlük budama tüm izleme türlerini okur
            ReflectionTestUtils.setField(s, "pageMonitorRepo", mp.mock(com.sitemonitor.repository.PageMonitorRepository.class));
            ReflectionTestUtils.setField(s, "pageSpeedMonitorRepo", mp.mock(com.sitemonitor.repository.PageSpeedMonitorRepository.class));
            ReflectionTestUtils.setField(s, "scriptedMonitorRepo", mp.mock(com.sitemonitor.repository.ScriptedMonitorRepository.class));
        });
    }

    void inventory(String... domains) {
        List<CertificateInventory> list = new ArrayList<>();
        for (String d : domains) {
            CertificateInventory i = new CertificateInventory();
            i.setDomain(d);
            i.setPort(443);
            i.setActive(true);
            list.add(i);
        }
        when(mp.mock(CertificateInventoryRepository.class).findByActiveTrueOrderByDomainAsc()).thenReturn(list);
    }

    void instantValidChecks() {
        when(mp.mock(CertificateCheckerService.class).checkAsync(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenAnswer(i -> CompletableFuture.completedFuture(Map.<String, Object>of("domain", i.getArgument(0), "status", "valid")));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sub(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    String isoAt(long ms) {
        return ISO.format(Instant.ofEpochMilli(ms));
    }

    // ── MP-34: tarama durumu kümeden ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MP-34: A tam taramayı koşturdu → hiç koşturmamış B kümenin son koşusunu gösterir, scan_alarm yok; 2 sa sonra ikisinde de alarm")
    void podThatNeverRan_showsClusterLastRun_noScanAlarm() {
        inventory("a.example.com", "b.example.com");
        instantValidChecks();
        Pod a = pod(1);
        Pod b = pod(2);
        long ranAt = mp.now();

        a.scheduler().runCheck();

        Map<String, Object> hb = b.scheduler().getSystemHealth();
        Map<String, Object> scan = sub(hb, "scan");
        // HATA ÖNCESİ: B'nin belleği boştu → last_run null (ekran "Hiç"), A'nın ilk koşusundan 2 sa sonra B "sağlıklı" derken A kırmızı
        assertThat(scan).containsEntry("last_run", isoAt(ranAt))
                .containsEntry("last_run_kind", "full")
                .containsEntry("last_run_by_pod", "pod1")
                .containsEntry("total", 2)
                .containsEntry("source", "cluster");
        assertThat(hb).containsEntry("scan_alarm", false);
        assertThat(sub(hb, "scheduler")).containsEntry("last_run", isoAt(ranAt)).containsEntry("running", false)
                .containsEntry("last_run_by_pod", "pod1");
        assertThat(b.scheduler().getStatus()).containsEntry("last_run", isoAt(ranAt)).containsEntry("source", "cluster");
        assertThat(sub(sub(scan, "runs"), "full")).containsEntry("by_pod", "pod1").containsEntry("total", 2);

        mp.advance(2 * 60 * MIN + MIN);
        assertThat(b.scheduler().getSystemHealth()).containsEntry("scan_alarm", true);
        assertThat(a.scheduler().getSystemHealth()).containsEntry("scan_alarm", true);
    }

    @Test
    @DisplayName("MP-34: A'nın canlı cert-check kilidi → B 'çalışıyor, pod1'de' der; süresi dolmuş satır kilitli / çalışıyor sayılmaz")
    void liveLockElsewhere_isRunning_expiredIsNot() throws Exception {
        inventory("a.example.com");
        CompletableFuture<Map<String, Object>> slow = new CompletableFuture<>();
        when(mp.mock(CertificateCheckerService.class).checkAsync(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenAnswer(i -> slow.thenApply(r -> Map.<String, Object>of("domain", i.getArgument(0), "status", "valid")));
        Pod a = pod(1);
        Pod b = pod(2);

        Thread run = new Thread(a.scheduler()::runCheck, "c1-run");
        run.setDaemon(true);
        run.start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (!a.owner().equals(mp.schedulerLockHolder("cert-check")) && System.currentTimeMillis() < deadline) Thread.sleep(5);

        Map<String, Object> hb = b.scheduler().getSystemHealth();
        // HATA ÖNCESİ: running = B'nin kendi bayrağı → false ("Boşta"), "Şimdi çalıştır" yoklaması ilk yanıtta bitiyordu
        assertThat(sub(hb, "scheduler")).containsEntry("running", true)
                .containsEntry("running_on", a.owner())
                .containsEntry("running_on_pod", "pod1")
                .containsEntry("running_here", false);
        assertThat(sub(hb, "lock")).containsEntry("held", true).containsEntry("held_by_me", false).containsEntry("expired", false)
                .containsEntry("locked_by_pod", "pod1");
        assertThat(sub(a.scheduler().getSystemHealth(), "lock")).containsEntry("held_by_me", true);
        assertThat(b.scheduler().clusterScanStatus().running()).isTrue();

        slow.complete(Map.of());
        run.join(10_000);
        assertThat(sub(b.scheduler().getSystemHealth(), "scheduler")).containsEntry("running", false);

        // Sahibi ölmüş, süresi dolmuş satır: HATA ÖNCESİ "Kilitli" + zorla bırak düğmesi
        mp.jdbc().update("INSERT INTO scheduler_lock(name, locked_by, locked_until) VALUES (?, ?, ?)",
                "cert-check", "pod9-000000ab", isoAt(mp.now() - 5 * MIN));
        Map<String, Object> after = b.scheduler().getSystemHealth();
        assertThat(sub(after, "lock")).containsEntry("held", false).containsEntry("expired", true).containsEntry("locked_by", "pod9-000000ab");
        assertThat(sub(after, "scheduler")).containsEntry("running", false);
    }

    @Test
    @DisplayName("MP-34: bayat tur (1 alan) saatlik / tam turun satırını EZMEZ — iki satır ayrı; en yeni koşu üstte")
    void staleRunDoesNotOverwriteFullRow() {
        inventory("a.example.com", "b.example.com", "c.example.com");
        instantValidChecks();
        Pod a = pod(1);
        Pod b = pod(2);
        a.scheduler().runCheck();
        mp.advance(10 * MIN);

        List<Map<String, Object>> one = List.of(new LinkedHashMap<>(Map.of("domain", "a.example.com", "port", 443, "use_proxy", false)));
        ReflectionTestUtils.invokeMethod(b.scheduler(), "runCheckForDomains", one, false);

        Map<String, Object> scan = sub(a.scheduler().getSystemHealth(), "scan");
        Map<String, Object> runs = sub(scan, "runs");
        assertThat(sub(runs, "full")).containsEntry("total", 3);
        assertThat(sub(runs, "stale")).containsEntry("total", 1);
        assertThat(scan).containsEntry("last_run_kind", "stale").containsEntry("last_run", isoAt(mp.now()));
    }

    @Test
    @DisplayName("MP-34: sonuç kaydedemeden düşen tur hatası kümede görünür; son başarılı sayılar ve koşu zamanı korunur; temiz tur hatayı siler")
    void failedRunShowsFailure_keepsLastGoodTotals() {
        inventory("a.example.com", "b.example.com");
        instantValidChecks();
        Pod a = pod(1);
        Pod b = pod(2);
        long okAt = mp.now();
        a.scheduler().runCheck();

        mp.advance(MIN);
        doThrow(new IllegalStateException("db yok")).when(mp.mock(CertificateService.class)).saveResult(any());
        b.scheduler().runCheck();

        Map<String, Object> scan = sub(a.scheduler().getSystemHealth(), "scan");
        assertThat((String) scan.get("last_failure")).contains("IllegalStateException");
        assertThat(scan).containsEntry("last_failure_by_pod", "pod2")
                .containsEntry("last_run", isoAt(okAt))          // son SONUÇLU koşu A'nınki
                .containsEntry("last_run_by_pod", "pod1")
                .containsEntry("total", 2);

        reset(mp.mock(CertificateService.class));
        mp.advance(MIN);
        a.scheduler().runCheck();
        assertThat(sub(b.scheduler().getSystemHealth(), "scan")).containsEntry("last_failure", null).containsEntry("last_run", isoAt(mp.now()));
    }

    @Test
    @DisplayName("MP-34: tablo okunamazsa (yok) bu pod'un belleği — source=local, sahte küme verisi yok")
    void missingTable_fallsBackToLocal() {
        mp.jdbc().execute("DROP TABLE scan_runs");
        inventory("a.example.com");
        instantValidChecks();
        Pod a = pod(1);
        Pod b = pod(2);
        a.scheduler().runCheck();

        assertThat(sub(a.scheduler().getSystemHealth(), "scan")).containsEntry("source", "local").containsEntry("total", 1);
        assertThat(sub(b.scheduler().getSystemHealth(), "scan")).containsEntry("source", "local").containsEntry("last_run", null);
    }

    // ── N3: yapılandırma sağlığı "zamanlayıcı" ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static Map<String, Object> schedulerCheck(SchedulerService s) {
        ConfigHealthService svc = new ConfigHealthService(mock(SmtpSettingsService.class), mock(LdapSettingsService.class),
                mock(UserPushService.class), mock(AppSettingsService.class), mock(com.sitemonitor.repository.AuditLogRepository.class),
                mock(CertificateInventoryRepository.class), mock(LatestCheckRepository.class), s);
        return ((List<Map<String, Object>>) svc.build().get("checks")).stream()
                .filter(c -> "scheduler".equals(c.get("key"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("N3: hiç koşturmamış takipçi 'okunamadı' uyarısı vermez — kümenin son koşusu; koşu sürerken 'çalışıyor'; kümede hiç yoksa 'never'")
    void configHealthScheduler_usesClusterStatus() throws Exception {
        Pod a = pod(1);
        Pod b = pod(2);
        // Kümede hiç koşu yok → warn never (HATA ÖNCESİ: "Not yet run" ≠ "never" → warn unparsable)
        assertThat(schedulerCheck(b.scheduler())).containsEntry("status", "warn").containsEntry("detail", "never");

        inventory("a.example.com");
        instantValidChecks();
        a.scheduler().runCheck();
        Map<String, Object> c = schedulerCheck(b.scheduler());
        assertThat(c).containsEntry("status", "ok");
        assertThat((String) c.get("detail")).startsWith("last_run:");

        mp.jdbc().update("INSERT INTO scheduler_lock(name, locked_by, locked_until) VALUES (?, ?, ?)",
                "cert-check", a.owner(), isoAt(mp.now() + 3 * MIN));
        assertThat(schedulerCheck(b.scheduler())).containsEntry("status", "ok").containsEntry("detail", "running:pod1");
    }

    // ── Gece temizliği kilidi (R2a / D2 isteği) ─────────────────────────────────────────────────────────────────

    void tuneNightly(Pod p, long retryMs, long waitMs) {
        ReflectionTestUtils.setField(p.scheduler(), "nightlyLockRetryMs", retryMs);
        ReflectionTestUtils.setField(p.scheduler(), "nightlyLockWaitMs", waitMs);
        ReflectionTestUtils.setField(p.scheduler(), "nightlyLockSliceMs", 2L);
    }

    @Test
    @DisplayName("Gece temizliği: yöneticinin koşusu kilidi tutarken yeniden dener, kilit boşalınca o gecenin işi koşar ve tutuşunu bırakır")
    void nightlyCleanup_retriesWhileAdminRunHolds() throws Exception {
        Pod a = pod(1);
        tuneNightly(a, 20L, 10_000L);
        ClusterLockService admin = mp.locks("admin-0000000a");
        var hold = admin.tryAcquire("nightly-cleanup").orElseThrow();

        Thread t = new Thread(a.scheduler()::cleanupOldLogs, "c1-nightly");
        t.setDaemon(true);
        t.start();
        Thread.sleep(150);
        verify(mp.mock(RetentionService.class), never()).runCleanup();   // kilit hâlâ yöneticide
        admin.release(hold);
        t.join(10_000);

        // HATA ÖNCESİ: ilk denemede "kilit başka pod'da" → bu gece hiç koşmuyordu
        verify(mp.mock(RetentionService.class), times(1)).runCleanup();
        assertThat(mp.schedulerLockHolder("nightly-cleanup")).as("tutuşunu bıraktı").isNull();
    }

    @Test
    @DisplayName("Gece temizliği: bu gecenin turu başka pod'da sahiplenilmişse (normal gece) beklemeden vazgeçer")
    void nightlyCleanup_roundClaimedElsewhere_givesUpAtOnce() {
        Pod a = pod(1);
        Pod b = pod(2);
        tuneNightly(a, 60_000L, 15 * MIN);
        tuneNightly(b, 60_000L, 15 * MIN);
        ClusterLockService other = mp.locks("pod9-00000009");
        var hold = other.tryAcquire("nightly-cleanup").orElseThrow();
        mp.jdbc().update("INSERT INTO monitor_check_schedule(monitor_key, next_due_at, claimed_by, claimed_at) VALUES (?, ?, ?, ?)",
                "round:nightly-cleanup", mp.now() + 20 * 60 * MIN, "pod9-00000009", mp.now());

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> a.scheduler().cleanupOldLogs());
        verify(mp.mock(RetentionService.class), never()).runCleanup();
        other.release(hold);
    }

    @Test
    @DisplayName("Gece temizliği: bekleme süresi dolarsa vazgeçer (sınırlı); kapanış başladıysa hemen")
    void nightlyCleanup_boundedAndStopsAtShutdown() {
        Pod a = pod(1);
        tuneNightly(a, 10L, 80L);
        ClusterLockService admin = mp.locks("admin-0000000a");
        var hold = admin.tryAcquire("nightly-cleanup").orElseThrow();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> a.scheduler().cleanupOldLogs());
        verify(mp.mock(RetentionService.class), never()).runCleanup();

        Pod b = pod(2);
        tuneNightly(b, 60_000L, 15 * MIN);
        b.locks().stopAcquiring();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> b.scheduler().cleanupOldLogs());
        verify(mp.mock(RetentionService.class), never()).runCleanup();
        admin.release(hold);
    }

    // ── MP-37d: pod başına budama ───────────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static ConcurrentHashMap<String, Long> monitorState(Pod p) {
        return (ConcurrentHashMap<String, Long>) ReflectionTestUtils.getField(p.scheduler(), "lastMonitorCheckAt");
    }

    @Test
    @DisplayName("MP-37d: gece temizliğini hiç kazanmayan lider pod kendi önbelleğini günlük işiyle budar (silinen izleme anahtarı gider)")
    void ownDailyPrune_prunesWithoutNightly() {
        PortMonitor live = new PortMonitor();
        live.setId(1L);
        when(mp.mock(PortMonitorRepository.class).findAll()).thenReturn(List.of(live));
        Pod b = pod(2);
        ConcurrentHashMap<String, Long> map = monitorState(b);
        map.put("port:1", 1L);
        map.put("port:99", 1L);   // silinmiş izleme
        map.put("round:uptime", 1L);

        b.scheduler().pruneOwnMonitorCheckState();

        assertThat(map).containsOnlyKeys("port:1", "round:uptime");
    }

    @Test
    @DisplayName("MP-37d: izleme anahtarı yoksa (takipçi) günlük budama veritabanına gitmez")
    void ownDailyPrune_followerSkipsDb() {
        Pod b = pod(2);
        monitorState(b).put("round:uptime", 1L);
        b.scheduler().pruneOwnMonitorCheckState();
        verify(mp.mock(PortMonitorRepository.class), never()).findAll();
    }

    @Test
    @DisplayName("MP-37d: liderlik bırakılınca izleme vade önbelleği boşalır (tur anahtarları kalır)")
    void leadershipReleased_clearsMonitorKeys() {
        Pod b = pod(2);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(b.scheduler(), "isSweepLeader")).isTrue();
        ConcurrentHashMap<String, Long> map = monitorState(b);
        map.put("port:1", 1L);
        map.put("round:uptime", 1L);

        b.scheduler().releaseSweepLeadership();

        assertThat(map).containsOnlyKeys("round:uptime");
    }

    // ── Günlük hacmi ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Günlük: 'başka pod'da, atlanıyor' saatte bir kez DEBUG, aradakiler TRACE; farklı iş ayrı sayılır")
    void skipLogIsRateLimited() {
        Pod a = pod(1);
        Logger logger = (Logger) LoggerFactory.getLogger(SchedulerService.class);
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> app = new ListAppender<>();
        app.start();
        logger.addAppender(app);
        logger.setLevel(Level.TRACE);
        try {
            a.scheduler().logSkip("Port sweep — lock başka instance'da, atlanıyor");
            a.scheduler().logSkip("Port sweep — lock başka instance'da, atlanıyor");
            a.scheduler().logSkip("Port sweep — lock başka instance'da, atlanıyor");
            a.scheduler().logSkip("{} — lock başka pod'da, atlanıyor", "quiet-digest");
            a.scheduler().logSkip("{} — lock başka pod'da, atlanıyor", "escalation-steps");
        } finally {
            logger.setLevel(before);
            logger.detachAppender(app);
        }
        long debug = app.list.stream().filter(e -> e.getLevel() == Level.DEBUG).count();
        long trace = app.list.stream().filter(e -> e.getLevel() == Level.TRACE).count();
        assertThat(debug).isEqualTo(3);
        assertThat(trace).isEqualTo(2);
    }

    // ── İnceleme H7 / H8 ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("H7: küme durumu yazımı patlasa da (istisna ya da Error) bayrak ve cert-check kilidi HER ZAMAN bırakılır")
    void scanRunWriteFailure_neverSkipsRelease() {
        inventory("a.example.com");
        instantValidChecks();
        Pod a = pod(1);
        ClusterHealthStore broken = mock(ClusterHealthStore.class);
        when(broken.recordScanRun(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("store patladı"));
        ReflectionTestUtils.setField(a.scheduler(), "clusterHealthStore", broken);

        a.scheduler().runCheck();   // HATA ÖNCESİ: istisna finally'den kaçar, running=true kalır, kilit bırakılmazdı
        assertThat(a.scheduler().isRunning()).isFalse();
        assertThat(mp.schedulerLockHolder("cert-check")).isNull();

        doThrow(new AssertionError("Error türü")).when(broken).recordScanRun(any(), any(), any(), any(), any(), any(), any(), any(), any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> a.scheduler().runCheck()).isInstanceOf(AssertionError.class);
        assertThat(a.scheduler().isRunning()).isFalse();
        assertThat(mp.schedulerLockHolder("cert-check")).as("Error'da da kilit bırakıldı").isNull();
    }

    @Test
    @DisplayName("H8: haftalık erişilebilirlik 'çalışıyor' = canlı kilit satırı; ölmüş pod'un süresi dolmuş satırı çalışıyor sayılmaz")
    void weeklyAvailabilityRunningNeedsLiveLock() {
        when(mp.mock(WeeklyAvailabilityReportService.class).schedulerHealth()).thenReturn(Map.of("enabled", true));
        Pod a = pod(1);
        mp.jdbc().update("INSERT INTO scheduler_lock(name, locked_by, locked_until) VALUES (?, ?, ?)",
                "weekly-availability", "pod9-000000ab", isoAt(mp.now() - 10 * MIN));
        assertThat(sub(a.scheduler().getSystemHealth(), "weekly_availability")).containsEntry("running", false);
        mp.jdbc().update("UPDATE scheduler_lock SET locked_until = ? WHERE name = 'weekly-availability'", isoAt(mp.now() + 2 * MIN));
        assertThat(sub(a.scheduler().getSystemHealth(), "weekly_availability")).containsEntry("running", true);
    }

    @Test
    @DisplayName("Yanıtlayan pod: System Health kendi pod'unu taşır (bellek / havuz / yürütücü kartı etiketi)")
    void servedByIsTheAnsweringPod() {
        Pod a = pod(1);
        assertThat(a.scheduler().getSystemHealth()).containsEntry("served_by", a.owner()).containsEntry("served_by_pod", "pod1");
    }
}
