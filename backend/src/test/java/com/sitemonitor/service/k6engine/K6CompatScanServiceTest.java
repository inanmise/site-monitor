package com.sitemonitor.service.k6engine;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.model.K6CompatResult;
import com.sitemonitor.model.K6CompatScan;
import com.sitemonitor.model.ScriptedCheck;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.K6CompatResultRepository;
import com.sitemonitor.repository.K6CompatScanRepository;
import com.sitemonitor.repository.ScriptedCheckRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.K6Profile;
import com.sitemonitor.service.ScriptedCheckerService;
import com.sitemonitor.service.ScriptedCheckerService.ArchiveKind;
import com.sitemonitor.service.ScriptedCheckerService.CompatRun;
import com.sitemonitor.service.ScriptedCheckerService.Phases;
import com.sitemonitor.service.ScriptedCheckerService.ScriptedResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** k6 uyumluluk taraması — gölge koşu (2026-10-29). */
class K6CompatScanServiceTest {

    private ScriptedCheckerService checker;
    private ScriptedMonitorRepository monitors;
    private ScriptedCheckRepository checks;
    private K6CompatScanRepository scans;
    private K6CompatResultRepository results;
    private AppSettingsService settings;
    private K6CompatScanService svc;
    private final K6Profile candidate = profile("/opt/k6-next/k6", "v2.3.0");
    private final Deque<CompatRun> runs = new ArrayDeque<>();

    static K6Profile profile(String bin, String version) {
        return K6Profile.fromVersionOutput(bin, "k6 " + version, 1L);
    }

    static ScriptedResult result(String status, Long ms, String error) {
        return new ScriptedResult(status, "PASS".equals(status), ms, "PASS".equals(status) ? 0 : 107, 1, 0, ms, ms, ms,
                null, null, error, false, Phases.EMPTY, "v2.3.0");
    }

    static ScriptedMonitor monitor(long id, String name) {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setId(id);
        m.setName(name);
        m.setActive(true);
        m.setTeamId(4L);
        m.setScript("export default function () {}");
        return m;
    }

    static ScriptedCheck check(String status, long ms) {
        ScriptedCheck c = new ScriptedCheck();
        c.setStatus(status);
        c.setDurationMs(ms);
        c.setCheckedAt("2026-10-29T20:00:00");
        c.setK6Version("v0.49.0");
        return c;
    }

    @BeforeEach
    void setUp() throws Exception {
        checker = mock(ScriptedCheckerService.class);
        monitors = mock(ScriptedMonitorRepository.class);
        checks = mock(ScriptedCheckRepository.class);
        scans = mock(K6CompatScanRepository.class);
        results = mock(K6CompatResultRepository.class);
        settings = mock(AppSettingsService.class);
        when(settings.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        when(scans.stateOf(anyLong())).thenReturn(Optional.of("RUNNING"));
        when(checker.compatRun(any(), any())).thenAnswer(i -> runs.isEmpty()
                ? new CompatRun(ArchiveKind.OK, null, result("PASS", 1000L, null)) : runs.poll());
        svc = new K6CompatScanService(checker, monitors, checks, scans, results, settings);
        svc.retryDelayMs = 0;
    }

    private List<K6CompatResult> runScan(List<ScriptedMonitor> ms, Map<Long, ScriptedCheck> base) {
        for (ScriptedMonitor m : ms) {
            when(monitors.findById(m.getId())).thenReturn(Optional.of(m));
            ScriptedCheck b = base.get(m.getId());
            when(checks.findRecentScheduledByMonitorId(m.getId(), 1)).thenReturn(b == null ? List.of() : List.of(b));
        }
        svc.runScan(7L, ms.stream().map(ScriptedMonitor::getId).toList(), candidate);
        ArgumentCaptor<K6CompatResult> cap = ArgumentCaptor.forClass(K6CompatResult.class);
        verify(results, atLeastOnce()).save(cap.capture());
        return cap.getAllValues();
    }

    @Test
    @DisplayName("Tarama gerçek kontrol geçmişine HİÇBİR ŞEY yazmaz; sonuç satırı + bitiş durumu DONE")
    void noSideEffects() {
        List<K6CompatResult> rows = runScan(List.of(monitor(1, "Giriş")), Map.of(1L, check("PASS", 900)));
        verify(checks, never()).save(any());
        verify(checks, never()).saveAll(any());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDiff()).isEqualTo("SAME_PASS");
        assertThat(rows.get(0).getBaselineK6Version()).isEqualTo("v0.49.0");
        verify(scans).finish(eq(7L), eq("DONE"), anyString(), eq(null));
    }

    @Test
    @DisplayName("Gerileme: gerçekte geçen, adayla geçmeyen izleme BİR kez yeniden denenir; ikinci deneme geçerse FLAKY")
    void regressionRetriedOnce() throws Exception {
        runs.add(new CompatRun(ArchiveKind.OK, null, result("ERROR", 300L, "tls: handshake failure")));
        runs.add(new CompatRun(ArchiveKind.OK, null, result("PASS", 800L, null)));
        List<K6CompatResult> rows = runScan(List.of(monitor(1, "A")), Map.of(1L, check("PASS", 900)));
        assertThat(rows.get(0).getDiff()).isEqualTo("FLAKY");
        assertThat(rows.get(0).getRetried()).isTrue();
        verify(checker, times(2)).compatRun(any(), any());
    }

    @Test
    @DisplayName("Kalıcı gerileme: ikinci deneme de geçmezse REGRESSION + hata sınıfı (TLS)")
    void persistentRegression() throws Exception {
        runs.add(new CompatRun(ArchiveKind.OK, null, result("ERROR", 300L, "tls: handshake failure")));
        runs.add(new CompatRun(ArchiveKind.OK, null, result("ERROR", 310L, "remote error: tls: handshake failure")));
        List<K6CompatResult> rows = runScan(List.of(monitor(1, "A")), Map.of(1L, check("PASS", 900)));
        assertThat(rows.get(0).getDiff()).isEqualTo("REGRESSION");
        assertThat(rows.get(0).getErrorClass()).isEqualTo("TLS");
        assertThat(rows.get(0).getError()).contains("remote error");
    }

    @Test
    @DisplayName("Derleme KESİN hatası: koşulmaz, durum ERROR + sınıf SYNTAX; zaten başarısızsa yeniden denenmez")
    void definiteArchiveError() throws Exception {
        runs.add(new CompatRun(ArchiveKind.DEFINITE, "script: Line 4:1 Unexpected token ;", null));
        List<K6CompatResult> rows = runScan(List.of(monitor(1, "A")), Map.of(1L, check("FAIL", 900)));
        assertThat(rows.get(0).getStatus()).isEqualTo("ERROR");
        assertThat(rows.get(0).getErrorClass()).isEqualTo("SYNTAX");
        assertThat(rows.get(0).getArchiveError()).contains("Line 4:1");
        assertThat(rows.get(0).getDiff()).isEqualTo("CHANGED");
        verify(checker, times(1)).compatRun(any(), any());
    }

    @Test
    @DisplayName("Karşılaştırılacak gerçek kontrol yok → NO_BASELINE; aday koşulamadı → NOT_RUN")
    void noBaselineAndNotRun() {
        runs.add(new CompatRun(ArchiveKind.OK, null, result("PASS", 500L, null)));
        runs.add(new CompatRun(ArchiveKind.NOT_RUN, null, new ScriptedResult("SKIPPED", false, null, -1, null, null,
                null, null, null, null, null, "aday k6 ikilisi bulunamadı", false, Phases.EMPTY)));
        List<K6CompatResult> rows = runScan(List.of(monitor(1, "A"), monitor(2, "B")), Map.of(2L, check("PASS", 900)));
        assertThat(rows).extracting(K6CompatResult::getDiff).containsExactly("NO_BASELINE", "NOT_RUN");
    }

    @Test
    @DisplayName("İptal isteği bir sonraki izlemeden önce görülür → CANCELLED, kalanlar koşulmaz")
    void cancelStopsBeforeNext() throws Exception {
        when(scans.stateOf(7L)).thenReturn(Optional.of("RUNNING"), Optional.of("CANCELLING"));
        when(monitors.findById(anyLong())).thenAnswer(i -> Optional.of(monitor(i.getArgument(0), "M")));
        when(checks.findRecentScheduledByMonitorId(anyLong(), anyInt())).thenReturn(List.of());
        svc.runScan(7L, List.of(1L, 2L, 3L), candidate);
        verify(checker, times(1)).compatRun(any(), any());
        verify(scans).finish(eq(7L), eq("CANCELLED"), anyString(), eq(null));
    }

    @Test
    @DisplayName("Kapanış başlarsa tarama INTERRUPTED biter, yeni izleme koşulmaz")
    void shutdownInterrupts() throws Exception {
        svc.onClosed();
        svc.runScan(7L, List.of(1L), candidate);
        verify(checker, never()).compatRun(any(), any());
        verify(scans).finish(eq(7L), eq("INTERRUPTED"), anyString(), anyString());
    }

    @Test
    @DisplayName("Silinmiş / duraklatılmış izleme sayılmaz (toplamdan düşer, satır yazılmaz)")
    void pausedMonitorSkipped() throws Exception {
        ScriptedMonitor paused = monitor(2, "B");
        paused.setActive(false);
        when(monitors.findById(1L)).thenReturn(Optional.of(monitor(1, "A")));
        when(monitors.findById(2L)).thenReturn(Optional.of(paused));
        when(checks.findRecentScheduledByMonitorId(anyLong(), anyInt())).thenReturn(List.of());
        svc.runScan(7L, List.of(1L, 2L), candidate);
        verify(results, times(1)).save(any());
        verify(scans, atLeastOnce()).progress(eq(7L), anyString(), eq(1), eq(1), anyInt(), anyInt(), anyInt(), anyInt(),
                anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Sayaçlar: CHANGED / NOT_RUN / NEW_FAIL tarama satırına yazılır (inceleme L2 — 'temiz' sanılmasın)")
    void countersCarryChangedNotRunAndNewFail() {
        runs.add(new CompatRun(ArchiveKind.OK, null, result("ERROR", 300L, "x")));     // 1: FAIL → ERROR = CHANGED
        runs.add(new CompatRun(ArchiveKind.NOT_RUN, null, new ScriptedResult("SKIPPED", false, null, -1, null, null,
                null, null, null, null, null, "aday k6 ikilisi bulunamadı", false, Phases.EMPTY)));   // 2: NOT_RUN
        runs.add(new CompatRun(ArchiveKind.OK, null, result("FAIL", 300L, "check")));   // 3: gerçek kontrol yok + FAIL
        List<K6CompatResult> rows = runScan(List.of(monitor(1, "A"), monitor(2, "B"), monitor(3, "C")),
                Map.of(1L, check("FAIL", 900), 2L, check("PASS", 900)));
        assertThat(rows).extracting(K6CompatResult::getDiff).containsExactly("CHANGED", "NOT_RUN", "NEW_FAIL");
        // son ilerleme yazımı: changed 1, notRun 1, newFail 1
        verify(scans, atLeastOnce()).progress(eq(7L), anyString(), eq(3), eq(3), eq(0), eq(0), eq(0), eq(0),
                eq(1), eq(1), eq(1));
    }

    @Test
    @DisplayName("Başka okuyucu taramayı kapattı (INTERRUPTED): döngü durur, yeni izleme koşulmaz (inceleme L6)")
    void closedElsewhereStops() throws Exception {
        when(scans.stateOf(7L)).thenReturn(Optional.of("RUNNING"), Optional.of("INTERRUPTED"));
        when(monitors.findById(anyLong())).thenAnswer(i -> Optional.of(monitor(i.getArgument(0), "M")));
        when(checks.findRecentScheduledByMonitorId(anyLong(), anyInt())).thenReturn(List.of());
        svc.runScan(7L, List.of(1L, 2L, 3L), candidate);
        verify(checker, times(1)).compatRun(any(), any());
        verify(scans).finish(eq(7L), eq("INTERRUPTED"), anyString(), anyString());
    }

    @Test
    @DisplayName("Süre bütçesi dolunca tarama 'süre sınırı' nedeniyle durur — 'kilit kaybedildi' değil (inceleme L5)")
    void budgetStopsWithItsOwnReason() throws Exception {
        svc.budgetMs = -1;
        svc.runScan(7L, List.of(1L), candidate);
        verify(checker, never()).compatRun(any(), any());
        ArgumentCaptor<String> why = ArgumentCaptor.forClass(String.class);
        verify(scans).finish(eq(7L), eq("INTERRUPTED"), anyString(), why.capture());
        assertThat(why.getValue()).contains("süre sınırı");
        assertThat(K6CompatScanService.SCAN_BUDGET_MS)
                .isLessThan(com.sitemonitor.service.schedule.ClusterLockService.DEFAULT_HARD_CAP.toMillis());
    }

    @Test
    @DisplayName("Uzun AD adı: başlatan kolonlarına sığacak biçimde kırpılır (inceleme L8)")
    void longActorNameIsFitted() {
        when(checker.candidate()).thenReturn(candidate);
        when(scans.findTop20ByOrderByIdDesc()).thenReturn(List.of());
        when(scans.findFirstByStateInOrderByIdDesc(any())).thenReturn(Optional.empty());
        when(monitors.findByActiveTrue()).thenReturn(List.of(monitor(1, "A")));
        when(checker.profiles()).thenReturn(new ScriptedCheckerService.Profiles(null, null, candidate));
        when(scans.save(any())).thenAnswer(i -> {
            K6CompatScan s = i.getArgument(0);
            s.setId(9L);
            return s;
        });
        when(scans.stateOf(9L)).thenReturn(Optional.of("CANCELLING"));   // koşum iş parçacığı hemen durur
        String longName = "Ad Soyad (Çok Uzun Bölüm Adı / Unvan) ".repeat(10);
        K6CompatScan s = svc.start(null, "u".repeat(150), longName);
        assertThat(s.getStartedBy()).hasSize(K6CompatScanService.STARTED_BY_MAX);
        assertThat(s.getStartedByName()).hasSize(K6CompatScanService.STARTED_BY_NAME_MAX);
    }

    @Test
    @DisplayName("Başlatma: aday yok / çalışmıyor / açık tarama var / izleme yok → 409 kodları")
    void startConflicts() {
        when(checker.candidate()).thenReturn(null);
        assertThatThrownBy(() -> svc.start(null, "admin", "Admin"))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> assertThat(e.getCode()).isEqualTo("K6_CANDIDATE_NOT_SET"));
        when(checker.candidate()).thenReturn(unavailable());
        assertThatThrownBy(() -> svc.start(null, "admin", "Admin"))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> assertThat(e.getCode()).isEqualTo("K6_CANDIDATE_UNAVAILABLE"));
        // 2026-10-30: 2.0'dan eski aday "desteklenmiyor" der ve sürümü adlandırır — "yolu denetleyin" değil.
        when(checker.candidate()).thenReturn(K6Profile.fromVersionOutput("/opt/k6-old/k6", "k6 v0.49.0", 1L));
        assertThatThrownBy(() -> svc.start(null, "admin", "Admin"))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("K6_CANDIDATE_UNAVAILABLE");
                    assertThat(e.getMessage()).contains("v0.49.0").contains("v2.0.0");
                });
        when(checker.candidate()).thenReturn(candidate);
        K6CompatScan open = new K6CompatScan();
        open.setId(3L);
        open.setState("RUNNING");
        open.setHeartbeatAt(K6CompatScanService.ISO.format(java.time.Instant.now()));
        when(scans.findTop20ByOrderByIdDesc()).thenReturn(List.of(open));
        when(scans.findFirstByStateInOrderByIdDesc(any())).thenReturn(Optional.of(open));
        assertThatThrownBy(() -> svc.start(null, "admin", "Admin"))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> assertThat(e.getCode()).isEqualTo("K6_SCAN_RUNNING"));
        when(scans.findFirstByStateInOrderByIdDesc(any())).thenReturn(Optional.empty());
        when(monitors.findByActiveTrue()).thenReturn(List.of());
        assertThatThrownBy(() -> svc.start(null, "admin", "Admin"))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> assertThat(e.getCode()).isEqualTo("K6_SCAN_NO_MONITORS"));
    }

    private static K6Profile unavailable() {
        return K6Profile.unavailable("/opt/k6-next/k6", 1L);
    }

    @Test
    @DisplayName("Hedef listesi: tüm etkinler eksi dışarıda bırakılanlar, ada göre; seçim verilirse yalnız onlar")
    void targetIds() {
        ScriptedMonitor b = monitor(2, "beta");
        ScriptedMonitor a = monitor(1, "Alfa");
        ScriptedMonitor c = monitor(3, "Gama");
        when(monitors.findByActiveTrue()).thenReturn(new ArrayList<>(List.of(b, a, c)));
        assertThat(svc.targetIds(null, java.util.Set.of(3L))).containsExactly(1L, 2L);
        assertThat(svc.targetIds(List.of(3L, 99L), java.util.Set.of(3L))).containsExactly(3L);
    }

    @Test
    @DisplayName("Sinyali 10 dk'dan eski açık tarama INTERRUPTED kapatılır; tazesi dokunulmaz")
    void settleStale() {
        K6CompatScan old = new K6CompatScan();
        old.setId(1L);
        old.setState("RUNNING");
        old.setHeartbeatAt("2020-01-01T00:00:00");
        K6CompatScan fresh = new K6CompatScan();
        fresh.setId(2L);
        fresh.setState("RUNNING");
        fresh.setHeartbeatAt(K6CompatScanService.ISO.format(java.time.Instant.now()));
        when(scans.findTop20ByOrderByIdDesc()).thenReturn(List.of(fresh, old));
        svc.settleStale();
        verify(scans).finish(eq(1L), eq("INTERRUPTED"), anyString(), anyString());
        verify(scans, never()).finish(eq(2L), anyString(), anyString(), any());
    }

    // ── Saf kurallar ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Fark tablosu")
    void diffTable() {
        assertThat(K6CompatScanService.diff("PASS", 1000L, "PASS", 1100L, false)).isEqualTo("SAME_PASS");
        assertThat(K6CompatScanService.diff("PASS", 1000L, "PASS", 3500L, false)).isEqualTo("SLOWER");
        assertThat(K6CompatScanService.diff("PASS", 100L, "PASS", 1900L, false)).isEqualTo("SAME_PASS");   // +1,8 sn < 2 sn
        assertThat(K6CompatScanService.diff("PASS", 1000L, "FAIL", 900L, false)).isEqualTo("REGRESSION");
        assertThat(K6CompatScanService.diff("PASS", 1000L, "ERROR", 900L, true)).isEqualTo("FLAKY");
        assertThat(K6CompatScanService.diff("FAIL", 1000L, "PASS", 900L, false)).isEqualTo("IMPROVED");
        assertThat(K6CompatScanService.diff("FAIL", 1000L, "FAIL", 900L, false)).isEqualTo("SAME_FAIL");
        assertThat(K6CompatScanService.diff("FAIL", 1000L, "ERROR", 900L, false)).isEqualTo("CHANGED");
        assertThat(K6CompatScanService.diff(null, null, "PASS", 900L, false)).isEqualTo("NO_BASELINE");
        assertThat(K6CompatScanService.diff(null, null, "FAIL", 900L, false)).isEqualTo("NEW_FAIL");
        assertThat(K6CompatScanService.diff(null, null, "ERROR", 900L, false)).isEqualTo("NEW_FAIL");
        assertThat(K6CompatScanService.diff("PASS", 1000L, "SKIPPED", null, false)).isEqualTo("NOT_RUN");
        assertThat(K6CompatScanService.diff("PASS", 1000L, null, null, false)).isEqualTo("NOT_RUN");
    }

    @Test
    @DisplayName("Hata sınıfı: gerçek k6 metinlerinden")
    void classifyTable() {
        assertThat(K6CompatScanService.classify("ERROR", "x", ArchiveKind.DEFINITE)).isEqualTo("SYNTAX");
        assertThat(K6CompatScanService.classify("ERROR", "unknown modules [\"k6/x/sql\"] were tried to be loaded", null)).isEqualTo("MODULE");
        assertThat(K6CompatScanService.classify("ERROR", "Get \"https://x\": tls: failed to verify certificate: x509: …", null)).isEqualTo("TLS");
        assertThat(K6CompatScanService.classify("FAIL", "Request Failed — dial tcp: lookup x: no such host", null)).isEqualTo("DNS");
        assertThat(K6CompatScanService.classify("FAIL", "dial tcp 127.0.0.1:1: connectex: … actively refused it.", null)).isEqualTo("CONNECT");
        assertThat(K6CompatScanService.classify("TIMEOUT", "Süre aşımı — süreç sonlandırıldı", null)).isEqualTo("TIMEOUT");
        assertThat(K6CompatScanService.classify("FAIL", "k6 check/threshold başarısız", null)).isEqualTo("CHECK");
        assertThat(K6CompatScanService.classify("ERROR", "ReferenceError: x is not defined", null)).isEqualTo("SCRIPT");
        assertThat(K6CompatScanService.classify("PASS", "irrelevant", null)).isNull();
        assertThat(K6CompatScanService.classify(null, null, null)).isNull();
    }

    @Test
    @DisplayName("Hata metni 2000 karakterde kırpılır")
    void clip() {
        assertThat(K6CompatScanService.clip("x".repeat(5000))).hasSize(2001);
        assertThat(K6CompatScanService.clip(null)).isNull();
    }
}
