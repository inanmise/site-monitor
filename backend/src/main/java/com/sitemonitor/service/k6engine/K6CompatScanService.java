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
import com.sitemonitor.service.K6EngineSettings;
import com.sitemonitor.service.K6Profile;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.ScriptedCheckerService;
import com.sitemonitor.service.schedule.ClusterLockService;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * k6 uyumluluk taraması — GÖLGE KOŞU (2026-10-29, k6 0.49 → 2.3 geçişi, Yayın A; kullanıcı kararları: tüm etkin izlemeler,
 * yönetici yan etkili olanları dışarıda bırakabilir, sonuçları yalnız genel yönetici görür).
 *
 * <p>Her etkin senaryo izlemesi ADAY ikiliyle bir kez derlenir ({@code k6 archive}) ve koşulur; sonuç izlemenin SON GERÇEK
 * (zamanlanmış) kontrolüyle karşılaştırılır. Gerçek koşumda geçen bir izleme adayla geçmezse 5 sn sonra BİR kez yeniden
 * denenir (geçici bir dalgalanma gerilemeyi gizlemesin, ama gürültü de gerileme sayılmasın: FLAKY).
 *
 * <p><b>Hiçbir yan etki yok:</b> {@code scripted_checks}'e satır, alarm değerlendirmesi, anomali koruması, bildirim, aktivite
 * kaydı YOK. Betik bir kez fazladan koşar — bir "Şimdi kontrol et" kadar trafik. Aynı anda tek betik koşar ve izleme
 * havuzunu kullanmaz ({@link ScriptedCheckerService#compatRun}).
 *
 * <p><b>Çok pod:</b> tarama başlatıldığı pod'da, {@code k6-compat-scan} kilidi altında koşar (aynı anda kümede tek tarama);
 * iptal isteği veritabanından okunur (her pod iptal edebilir). Kapanışta ya da kilit kaybında tarama INTERRUPTED biter;
 * sinyali 10 dk'dan eski açık bir tarama okunurken ya da yeni tarama başlatılırken INTERRUPTED işaretlenir.
 */
@Slf4j
@Service
public class K6CompatScanService {

    public static final String LOCK = "k6-compat-scan";

    static final long STALE_MS = 10 * 60_000L;

    /**
     * Taramanın süre bütçesi (inceleme 2026-10-30, L5): kilit yenilemesi {@link ClusterLockService#DEFAULT_HARD_CAP}
     * (3 sa) sonra durur; tarama bundan 10 dk önce kendisi durur ve "süre doldu" der ("kilit kaybedildi" yanıltıcıydı).
     */
    static final long SCAN_BUDGET_MS = ClusterLockService.DEFAULT_HARD_CAP.toMillis() - 10 * 60_000L;

    /** Başlatan kullanıcı kolonları (100 / 200) — uzun AD adı INSERT'ü düşürmesin (L8). */
    static final int STARTED_BY_MAX = 100;
    static final int STARTED_BY_NAME_MAX = 200;
    static final int MAX_MONITORS = 2000;
    static final long RETRY_DELAY_MS = 5_000L;
    static final int ERROR_MAX_CHARS = 2000;
    /** SLOWER: aday süresi gerçeğin 1,5 katından VE 2 sn fazlasından büyük. */
    static final double SLOWER_FACTOR = 1.5;
    static final long SLOWER_MIN_DELTA_MS = 2_000L;

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    public static final List<String> OPEN_STATES = List.of("RUNNING", "CANCELLING");

    private final ScriptedCheckerService checker;
    private final ScriptedMonitorRepository monitors;
    private final ScriptedCheckRepository checks;
    private final K6CompatScanRepository scans;
    private final K6CompatResultRepository results;
    private final AppSettingsService settings;

    private SchedulerService scheduler;
    private ClusterLockService lockService;
    /** Test için: yeniden deneme beklemesi (ms). */
    long retryDelayMs = RETRY_DELAY_MS;
    /** Test kancası — {@link #SCAN_BUDGET_MS}. */
    long budgetMs = SCAN_BUDGET_MS;

    private volatile boolean closing;

    public K6CompatScanService(ScriptedCheckerService checker, ScriptedMonitorRepository monitors,
                               ScriptedCheckRepository checks, K6CompatScanRepository scans,
                               K6CompatResultRepository results, AppSettingsService settings) {
        this.checker = checker;
        this.monitors = monitors;
        this.checks = checks;
        this.scans = scans;
        this.results = results;
        this.settings = settings;
    }

    @Autowired(required = false)
    public void setScheduler(SchedulerService scheduler) { this.scheduler = scheduler; }

    @Autowired(required = false)
    public void setLockService(ClusterLockService lockService) { this.lockService = lockService; }

    @EventListener(ContextClosedEvent.class)
    public void onClosed() {
        closing = true;
    }

    // ── Başlatma / iptal ─────────────────────────────────────────────────────────────────────────────

    /**
     * Taramayı başlatır (arka planda koşar) ve oluşturulan tarama satırını döner.
     *
     * @param monitorIds null / boş = tüm etkin izlemeler (dışarıda bırakılanlar hariç); dolu = yalnız bunlar (dışarıda
     *                   bırakma uygulanmaz — yönetici bilerek seçti)
     * @throws CodedConflictException K6_CANDIDATE_NOT_SET / K6_CANDIDATE_UNAVAILABLE / K6_SCAN_RUNNING / K6_SCAN_NO_MONITORS /
     *                                K6_SCAN_CLOSING
     */
    public K6CompatScan start(List<Long> monitorIds, String actor, String actorName) {
        if (closing || (scheduler != null && scheduler.sideJobsStopping())) {
            throw new CodedConflictException("K6_SCAN_CLOSING", Msg.t(
                    "Uygulama kapanıyor — tarama başlatılamadı. Birkaç dakika sonra yeniden deneyin.",
                    "The application is shutting down — the scan could not start. Try again in a few minutes."));
        }
        K6Profile candidate = checker.candidate();
        if (candidate == null) {
            throw new CodedConflictException("K6_CANDIDATE_NOT_SET", Msg.t(
                    "Aday k6 ikilisi tanımlı değil. Önce 'Aday ikili' yolunu kaydedin (imajda /opt/k6-next/k6).",
                    "No candidate k6 binary is set. Save the 'Candidate binary' path first (/opt/k6-next/k6 in the image)."));
        }
        if (!candidate.available()) {
            throw new CodedConflictException("K6_CANDIDATE_UNAVAILABLE", Msg.t(
                    "Aday k6 ikilisi bu pod'da çalıştırılamadı (" + candidate.bin() + "). Yolu denetleyin ve 'Yeniden yokla'yı deneyin.",
                    "The candidate k6 binary could not be run on this pod (" + candidate.bin() + "). Check the path and try 'Probe again'."));
        }
        settleStale();
        Optional<K6CompatScan> open = scans.findFirstByStateInOrderByIdDesc(OPEN_STATES);
        if (open.isPresent()) {
            throw new CodedConflictException("K6_SCAN_RUNNING", Msg.t(
                    "Bir uyumluluk taraması zaten sürüyor (#" + open.get().getId() + "). Bitmesini bekleyin ya da iptal edin.",
                    "A compatibility scan is already running (#" + open.get().getId() + "). Wait for it to finish or cancel it."),
                    Map.of("scan_id", open.get().getId()));
        }
        boolean selected = monitorIds != null && !monitorIds.isEmpty();
        Set<Long> excluded = selected ? Set.of()
                : K6EngineSettings.effectiveExcludedIds(settings.getString(K6EngineSettings.KEY_SCAN_EXCLUDED, ""));
        List<Long> ids = targetIds(selected ? monitorIds : null, excluded);
        if (ids.isEmpty()) {
            throw new CodedConflictException("K6_SCAN_NO_MONITORS", Msg.t(
                    "Taranacak etkin senaryo izlemesi yok (hepsi duraklatılmış ya da dışarıda bırakılmış).",
                    "There is no active scripted monitor to scan (all are paused or excluded)."));
        }
        K6Profile active = checker.profiles().active();
        String now = ISO.format(Instant.now());
        K6CompatScan s = new K6CompatScan();
        s.setState("RUNNING");
        s.setStartedAt(now);
        s.setHeartbeatAt(now);
        s.setStartedBy(fit(actor, STARTED_BY_MAX));
        s.setStartedByName(fit(actorName, STARTED_BY_NAME_MAX));
        s.setOwner(SchedulerService.instanceId());
        s.setActiveBin(active == null ? null : active.bin());
        s.setActiveVersion(active == null ? null : active.version());
        s.setCandidateBin(candidate.bin());
        s.setCandidateVersion(candidate.version());
        s.setScope(selected ? "SELECTED" : "ALL");
        s.setExcludedIds(selected ? null : K6EngineSettings.joinIds(excluded));
        s.setTotal(ids.size());
        K6CompatScan saved = scans.save(s);
        Thread.ofPlatform().daemon().name("k6-compat-scan").start(() -> runLocked(saved.getId(), ids, candidate));
        log.info("[K6] Uyumluluk taraması #{} başladı — {} izleme, aday {} ({}), başlatan {}",
                saved.getId(), ids.size(), candidate.version(), candidate.bin(), actor);
        return saved;
    }

    /** İptal isteği — taramayı koşan pod bir sonraki izlemeden önce görür. Döner: istek kaydedildi mi. */
    public boolean cancel(Long scanId) {
        return scans.requestCancel(scanId) > 0;
    }

    /** Taranacak izlemeler: seçilenler ya da tüm etkin izlemeler (dışarıda bırakılanlar hariç), ada göre, en çok 2000. */
    List<Long> targetIds(List<Long> selected, Set<Long> excluded) {
        List<ScriptedMonitor> active = monitors.findByActiveTrue();
        java.util.Set<Long> wanted = selected == null ? null : new java.util.HashSet<>(selected);
        List<ScriptedMonitor> pick = new ArrayList<>();
        for (ScriptedMonitor m : active) {
            if (m.getId() == null) continue;
            if (wanted != null && !wanted.contains(m.getId())) continue;
            if (wanted == null && excluded.contains(m.getId())) continue;
            pick.add(m);
        }
        pick.sort(Comparator.comparing((ScriptedMonitor m) -> m.getName() == null ? "" : m.getName().toLowerCase(Locale.ROOT))
                .thenComparing(ScriptedMonitor::getId));
        List<Long> ids = new ArrayList<>();
        for (ScriptedMonitor m : pick) {
            if (ids.size() >= MAX_MONITORS) break;
            ids.add(m.getId());
        }
        return ids;
    }

    /** Sinyali {@link #STALE_MS}'den eski açık taramaları INTERRUPTED kapatır (koşan pod öldü / yeniden başladı). */
    public void settleStale() {
        try {
            for (K6CompatScan s : scans.findTop20ByOrderByIdDesc()) {
                if (!OPEN_STATES.contains(s.getState())) continue;
                if (!stale(s.getHeartbeatAt())) continue;
                if (scans.finish(s.getId(), "INTERRUPTED", ISO.format(Instant.now()),
                        "Taramayı koşan pod 10 dakikadır sinyal vermiyor (yeniden başlatma ya da kapanış) — tarama yarıda kaldı.") > 0) {
                    log.warn("[K6] Uyumluluk taraması #{} yarıda kalmış (sahibi {}) — INTERRUPTED", s.getId(), s.getOwner());
                }
            }
        } catch (Exception e) {
            log.warn("[K6] Yarıda kalan taramalar denetlenemedi: {}", e.toString());
        }
    }

    static boolean stale(String heartbeatAt) {
        if (heartbeatAt == null || heartbeatAt.isBlank()) return true;
        try {
            Instant hb = java.time.LocalDateTime.parse(heartbeatAt).toInstant(ZoneOffset.UTC);
            return Instant.now().toEpochMilli() - hb.toEpochMilli() > STALE_MS;
        } catch (Exception e) {
            return true;
        }
    }

    // ── Koşum ─────────────────────────────────────────────────────────────────────────────────────

    private void runLocked(Long scanId, List<Long> ids, K6Profile candidate) {
        try {
            if (scheduler == null) {
                runScan(scanId, ids, candidate);
                return;
            }
            Optional<Boolean> ran = scheduler.callWithSchedulerLock(LOCK, Duration.ofMinutes(3), () -> {
                runScan(scanId, ids, candidate);
                return Boolean.TRUE;
            });
            if (ran.isEmpty()) {
                scans.finish(scanId, "FAILED", ISO.format(Instant.now()),
                        "Aynı anda başka bir pod'da uyumluluk taraması başladı — bu tarama koşmadı.");
            }
        } catch (Throwable t) {
            log.error("[K6] Uyumluluk taraması #{} beklenmeyen hatayla bitti", scanId, t);
            try {
                scans.finish(scanId, "FAILED", ISO.format(Instant.now()),
                        "Tarama beklenmeyen bir hatayla durdu (" + t.getClass().getSimpleName() + "). Uygulama günlüğüne bakın.");
            } catch (Exception ignored) {
                // durum yazılamazsa sinyal eskiyip INTERRUPTED olur
            }
        }
    }

    /** Taramanın gövdesi — paket-özel: testler eşzamanlı çağırır. */
    void runScan(Long scanId, List<Long> ids, K6Profile candidate) {
        Counters c = new Counters(ids.size());
        String endState = "DONE";
        String failure = null;
        long startedMs = System.currentTimeMillis();
        for (Long id : ids) {
            if (System.currentTimeMillis() - startedMs > budgetMs) {
                endState = "INTERRUPTED";
                failure = "Tarama süre sınırına (" + (budgetMs / 60_000L) + " dk) ulaştı — kalan izlemeler taranmadı. "
                        + "Kalanları seçerek yeniden tarayın ya da yan etkisiz olmayanları tarama dışına alın.";
                break;
            }
            if (closing || (scheduler != null && scheduler.sideJobsStopping())) {
                endState = "INTERRUPTED";
                failure = "Uygulama kapanırken tarama durdu — kalan izlemeler taranmadı.";
                break;
            }
            if (lockService != null && !lockService.held(LOCK) && scheduler != null) {
                endState = "INTERRUPTED";
                failure = "Tarama kilidi kaybedildi — kalan izlemeler taranmadı.";
                break;
            }
            String state = scans.stateOf(scanId).orElse(null);
            if ("CANCELLING".equals(state)) {
                endState = "CANCELLED";
                break;
            }
            if (!"RUNNING".equals(state)) {
                // Başka bir okuyucu sinyali eski bulup taramayı kapattı (INTERRUPTED) ya da satır yok: durulur, kalan
                // izlemeler koşulmaz ve kilit bırakılır (inceleme L6 — kapatılmış tarama arka planda koşmaya devam ediyordu).
                endState = "INTERRUPTED";
                failure = "Tarama başka bir yoldan kapatıldı (" + state + ") — kalan izlemeler taranmadı.";
                log.warn("[K6] Uyumluluk taraması #{} durumu {} — tarama durduruluyor", scanId, state);
                break;
            }
            try {
                K6CompatResult r = scanOne(scanId, id, candidate, c);
                if (r == null) {            // izleme silinmiş / duraklatılmış: toplamdan düşüldü
                    progress(scanId, c);
                    continue;
                }
                results.save(r);
                c.count(r.getDiff());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                endState = "INTERRUPTED";
                failure = "Tarama kesintiye uğradı.";
                break;
            } catch (Exception e) {
                log.warn("[K6] Uyumluluk taraması #{}: izleme {} taranamadı: {}", scanId, id, e.toString());
                K6CompatResult r = new K6CompatResult();
                r.setScanId(scanId);
                r.setMonitorId(id);
                r.setDiff("NOT_RUN");
                r.setError("Taranamadı: " + e.getClass().getSimpleName());
                r.setCheckedAt(ISO.format(Instant.now()));
                safeSave(r);
                c.count("NOT_RUN");
            }
            c.done++;
            progress(scanId, c);
        }
        progress(scanId, c);
        if ("CANCELLED".equals(endState)) {
            scans.finish(scanId, "CANCELLED", ISO.format(Instant.now()), null);
        } else {
            scans.finish(scanId, endState, ISO.format(Instant.now()), failure);
        }
        log.info("[K6] Uyumluluk taraması #{} bitti ({}) — {}/{} izleme, gerileme {}, iyileşme {}",
                scanId, endState, c.done, c.total, c.regressions, c.improvements);
    }

    private void safeSave(K6CompatResult r) {
        try {
            results.save(r);
        } catch (Exception e) {
            log.warn("[K6] Uyumluluk sonucu yazılamadı: {}", e.toString());
        }
    }

    private void progress(Long scanId, Counters c) {
        try {
            scans.progress(scanId, ISO.format(Instant.now()), c.total, c.done, c.regressions, c.improvements,
                    c.unchanged, c.noBaseline, c.changed, c.notRun, c.newFail);
        } catch (Exception e) {
            log.warn("[K6] Tarama ilerlemesi yazılamadı: {}", e.toString());
        }
    }

    /** Tek izleme: derle + koş (+ gerilemede bir yeniden deneme) ve sonucu karşılaştır. İzleme yoksa / pasifse null. */
    K6CompatResult scanOne(Long scanId, Long monitorId, K6Profile candidate, Counters c) throws InterruptedException {
        ScriptedMonitor m = monitors.findById(monitorId).orElse(null);
        if (m == null || !Boolean.TRUE.equals(m.getActive())) {
            c.total = Math.max(0, c.total - 1);
            return null;
        }
        ScriptedCheck base = checks.findRecentScheduledByMonitorId(monitorId, 1).stream().findFirst().orElse(null);

        ScriptedCheckerService.CompatRun run = checker.compatRun(m, candidate);
        ScriptedCheckerService.ScriptedResult res = run.result();
        boolean retried = false;
        boolean retryPassed = false;
        if (base != null && "PASS".equals(base.getStatus()) && res != null
                && !"PASS".equals(res.status()) && !ScriptedCheckerService.isSkipped(res)) {
            progress(scanId, c);   // uzun koşumda sinyal eskimesin
            Thread.sleep(retryDelayMs);
            ScriptedCheckerService.CompatRun again = checker.compatRun(m, candidate);
            retried = true;
            if (again.result() != null && "PASS".equals(again.result().status())) {
                retryPassed = true;
            } else if (again.result() != null) {
                res = again.result();   // ikinci denemenin hatası (daha güncel)
                run = again;
            }
        }

        K6CompatResult r = new K6CompatResult();
        r.setScanId(scanId);
        r.setMonitorId(m.getId());
        r.setMonitorName(m.getName());
        r.setTeamId(m.getTeamId());
        r.setArchive(run.archive() == null ? null : run.archive().name());
        r.setArchiveError(clip(run.archiveDetail()));
        String candStatus = run.archive() == ScriptedCheckerService.ArchiveKind.DEFINITE ? "ERROR"
                : res == null ? null : res.status();
        r.setStatus(candStatus);
        if (res != null) {
            r.setViaProxy(res.viaProxy());
            r.setExitCode(res.exitCode());
            r.setChecksPassed(res.checksPassed());
            r.setChecksFailed(res.checksFailed());
            r.setDurationMs(res.durationMs());
            r.setError(clip(res.error()));
        }
        r.setErrorClass(classify(candStatus, run.archive() == ScriptedCheckerService.ArchiveKind.DEFINITE
                ? run.archiveDetail() : res == null ? null : res.error(), run.archive()));
        r.setRetried(retried);
        if (base != null) {
            r.setBaselineStatus(base.getStatus());
            r.setBaselineExitCode(base.getExitCode());
            r.setBaselineChecksPassed(base.getChecksPassed());
            r.setBaselineChecksFailed(base.getChecksFailed());
            r.setBaselineDurationMs(base.getDurationMs());
            r.setBaselineCheckedAt(base.getCheckedAt());
            r.setBaselineK6Version(base.getK6Version());
            r.setBaselineErrorClass(classify(base.getStatus(), base.getError(), null));
        }
        boolean skipped = res != null && ScriptedCheckerService.isSkipped(res);
        r.setDiff(diff(base == null ? null : base.getStatus(), base == null ? null : base.getDurationMs(),
                skipped ? "SKIPPED" : candStatus,
                res == null ? null : res.durationMs(), retryPassed));
        r.setCheckedAt(ISO.format(Instant.now()));
        return r;
    }

    // ── Saf kurallar (birim test edilir) ─────────────────────────────────────────────────────────────

    /**
     * Fark türü — bkz. {@link K6CompatResult} sınıf açıklaması.
     *
     * @param base          son gerçek kontrolün durumu (yoksa null)
     * @param cand          adayın durumu ({@code SKIPPED} = koşulamadı; null = koşulamadı)
     * @param retryPassed   gerileme yeniden denemede geçti mi
     */
    static String diff(String base, Long baseDurationMs, String cand, Long candDurationMs, boolean retryPassed) {
        if (cand == null || "SKIPPED".equals(cand)) return "NOT_RUN";
        // Gerçek kontrol yok: aday geçtiyse karşılaştırma yok; düştüyse NEW_FAIL — "temiz" sayılmaz (inceleme L2).
        if (base == null) return "PASS".equals(cand) ? "NO_BASELINE" : "NEW_FAIL";
        boolean basePass = "PASS".equals(base);
        boolean candPass = "PASS".equals(cand);
        if (basePass && candPass) return slower(baseDurationMs, candDurationMs) ? "SLOWER" : "SAME_PASS";
        if (basePass) return retryPassed ? "FLAKY" : "REGRESSION";
        if (candPass) return "IMPROVED";
        return base.equals(cand) ? "SAME_FAIL" : "CHANGED";
    }

    static boolean slower(Long base, Long cand) {
        if (base == null || cand == null || base <= 0) return false;
        return cand > base * SLOWER_FACTOR && cand - base > SLOWER_MIN_DELTA_MS;
    }

    /** Hata sınıfı — metin taraması (Türkçe ileti + k6'nın İngilizce hata satırı). PASS / hata yok ⇒ null. */
    static String classify(String status, String error, ScriptedCheckerService.ArchiveKind archive) {
        if (archive == ScriptedCheckerService.ArchiveKind.DEFINITE) return "SYNTAX";
        if (status == null || "PASS".equals(status) || "SKIPPED".equals(status)) return null;
        String e = error == null ? "" : error.toLowerCase(Locale.ROOT);
        if (e.contains("unknown module") || e.contains("k6/x/") || e.contains("eklenti")) return "MODULE";
        if (e.contains("syntaxerror") || e.contains("unexpected token") || e.contains("derlenemedi")) return "SYNTAX";
        if (e.contains("tls:") || e.contains("x509:") || e.contains("certificate") || e.contains("handshake")
                || e.contains("remote error")) return "TLS";
        if (e.contains("no such host") || e.contains("server misbehaving")) return "DNS";
        if (e.contains("connection refused") || e.contains("actively refused") || e.contains("connection reset")
                || e.contains("proxyconnect") || e.contains("i/o timeout") || e.contains("dial tcp")) return "CONNECT";
        if ("TIMEOUT".equals(status) || e.contains("request timeout")) return "TIMEOUT";
        if ("FAIL".equals(status)) return "CHECK";
        if ("NO_CHECKS".equals(status)) return "CHECK";
        if ("ERROR".equals(status)) return "SCRIPT";
        return "OTHER";
    }

    private static String fit(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    static String clip(String s) {
        if (s == null) return null;
        return s.length() <= ERROR_MAX_CHARS ? s : s.substring(0, ERROR_MAX_CHARS) + "…";
    }

    /** Sonuç satırları (en çok {@link #MAX_MONITORS}). */
    public List<K6CompatResult> resultsFor(Long scanId) {
        return results.findForScan(scanId, PageRequest.of(0, MAX_MONITORS));
    }

    public List<K6CompatScan> recent() {
        settleStale();
        return scans.findTop20ByOrderByIdDesc();
    }

    public Optional<K6CompatScan> find(Long id) {
        return scans.findById(id);
    }

    /** Fark türü sayımı — liste ekranının kutucukları. */
    public static Map<String, Integer> countByDiff(List<K6CompatResult> rows) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String k : DIFFS) out.put(k, 0);
        for (K6CompatResult r : rows) out.merge(r.getDiff() == null ? "NOT_RUN" : r.getDiff(), 1, Integer::sum);
        return out;
    }

    public static final List<String> DIFFS = List.of("REGRESSION", "FLAKY", "CHANGED", "NEW_FAIL", "SLOWER", "IMPROVED",
            "SAME_FAIL", "SAME_PASS", "NO_BASELINE", "NOT_RUN");

    static final class Counters {
        int total;
        int done;
        int regressions;
        int improvements;
        int unchanged;
        int noBaseline;
        int changed;
        int notRun;
        int newFail;

        Counters(int total) { this.total = total; }

        void count(String diff) {
            if (diff == null) return;
            switch (diff) {
                case "REGRESSION" -> regressions++;
                case "IMPROVED" -> improvements++;
                case "SAME_PASS", "SAME_FAIL", "SLOWER", "FLAKY" -> unchanged++;
                case "NO_BASELINE" -> noBaseline++;
                case "CHANGED" -> changed++;
                case "NOT_RUN" -> notRun++;
                case "NEW_FAIL" -> newFail++;
                default -> { }
            }
        }
    }
}
