package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.K6CompatResult;
import com.sitemonitor.model.K6CompatScan;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.DeploymentHistoryService;
import com.sitemonitor.service.K6EngineSettings;
import com.sitemonitor.service.K6Profile;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.ScriptedCheckerService;
import com.sitemonitor.service.k6engine.K6CompatScanService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ayarlar → Platform → k6 motoru (2026-10-29, k6 0.49 → 2.3 geçişi, Yayın A). YALNIZ genel yönetici (kullanıcı kararı:
 * tarama sonuçlarını yalnız genel yönetici görür; ikili / GODEBUG / User-Agent ayarları da GLOBAL_ONLY).
 *
 * <ul>
 *   <li>{@code GET /} — bu pod'daki etkin / yedek / aday ikili profilleri, ayarlar, son tarama özeti, izleme listesi</li>
 *   <li>{@code PUT /settings} — yedek / aday yolu, GODEBUG, User-Agent, tarama dışı izlemeler (yalnız gönderilen + değişen alan)</li>
 *   <li>{@code POST /switch} — adayı etkin yap (etkin → yedek), {@code POST /rollback} — yedeğe dön</li>
 *   <li>{@code POST /reprobe} — ikilileri şimdi yeniden yokla</li>
 *   <li>{@code POST /scans} / {@code GET /scans} / {@code GET /scans/{id}} / {@code POST /scans/{id}/cancel}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/k6-engine")
public class K6EngineController {

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Arayüz alanı → ayar anahtarı. */
    static final Map<String, String> FIELDS = new LinkedHashMap<>();
    static {
        FIELDS.put("rollback_bin", ScriptedCheckerService.K6_BIN_ROLLBACK_KEY);
        FIELDS.put("candidate_bin", ScriptedCheckerService.K6_BIN_CANDIDATE_KEY);
        FIELDS.put("godebug", K6EngineSettings.KEY_GODEBUG);
        FIELDS.put("user_agent", K6EngineSettings.KEY_USER_AGENT);
        FIELDS.put("excluded_ids", K6EngineSettings.KEY_SCAN_EXCLUDED);
    }

    private final ScriptedCheckerService checker;
    private final K6CompatScanService scans;
    private final AppSettingsService settings;
    private final AuditService auditService;
    private final ScriptedMonitorRepository monitors;
    private final TeamRepository teams;

    public K6EngineController(ScriptedCheckerService checker, K6CompatScanService scans, AppSettingsService settings,
                              AuditService auditService, ScriptedMonitorRepository monitors, TeamRepository teams) {
        this.checker = checker;
        this.scans = scans;
        this.settings = settings;
        this.auditService = auditService;
        this.monitors = monitors;
        this.teams = teams;
    }

    /** Sürüm geçmişi (canlı pod sürümleri) — yoksa (dar testler, geçmiş kapalı) karışık sürüm denetimi yapılmaz. */
    private DeploymentHistoryService history;

    @Autowired(required = false)
    void setHistory(DeploymentHistoryService history) {
        this.history = history;
    }

    /** Başka sürüm koşan canlı pod'lar; null = okunamadı. Geçmiş servisi yoksa boş. */
    private List<Map<String, String>> otherVersionPods() {
        return history == null ? List.of() : history.liveOtherVersions();
    }

    // ── Durum ────────────────────────────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Map<String, Object>> status(HttpSession session) {
        requireGlobal(session);
        checker.ensureProbed();
        return ok(view());
    }

    @PostMapping("/reprobe")
    public ResponseEntity<Map<String, Object>> reprobe(HttpSession session) {
        requireGlobal(session);
        checker.probeK6();
        return ok(view());
    }

    Map<String, Object> view() {
        ScriptedCheckerService.Profiles p = checker.profiles();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pod", SchedulerService.hostname());
        Map<String, Object> engines = new LinkedHashMap<>();
        engines.put("active", profileDto(p.active()));
        engines.put("rollback", profileDto(p.rollback()));
        engines.put("candidate", profileDto(p.candidate()));
        out.put("engines", engines);
        out.put("syntax_version", p.syntaxVersion());
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("rollback_bin", K6EngineSettings.shown(current(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY)));
        s.put("candidate_bin", K6EngineSettings.shown(current(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY)));
        s.put("godebug", K6EngineSettings.shown(current(K6EngineSettings.KEY_GODEBUG)));
        s.put("user_agent", K6EngineSettings.shown(current(K6EngineSettings.KEY_USER_AGENT)));
        s.put("excluded_ids", new ArrayList<>(K6EngineSettings.effectiveExcludedIds(current(K6EngineSettings.KEY_SCAN_EXCLUDED))));
        out.put("settings", s);
        List<Map<String, Object>> keys = new ArrayList<>();
        K6EngineSettings.godebugKeys().forEach((k, d) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", k);
            row.put("description", d);
            keys.add(row);
        });
        out.put("godebug_keys", keys);
        out.put("scripted_enabled", settings.getBoolean("site.monitor.scripted.enabled", true));
        K6Profile a = p.active();
        K6Profile c = p.candidate();
        K6Profile r = p.rollback();
        out.put("can_switch", c != null && c.available() && (a == null || !c.bin().equals(a.bin())));
        out.put("can_rollback", r != null && r.available() && (a == null || !r.bin().equals(a.bin())));
        out.put("other_version_pods", otherVersionPods());
        Map<Long, String> teamNames = teamNames();
        Set<Long> excluded = K6EngineSettings.effectiveExcludedIds(current(K6EngineSettings.KEY_SCAN_EXCLUDED));
        List<Map<String, Object>> mons = new ArrayList<>();
        for (ScriptedMonitor m : monitors.findAllByOrderByNameAsc()) {
            if (mons.size() >= 2000) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("name", m.getName());
            row.put("team_id", m.getTeamId());
            row.put("team_name", m.getTeamId() == null ? null : teamNames.get(m.getTeamId()));
            row.put("active", Boolean.TRUE.equals(m.getActive()));
            row.put("excluded", excluded.contains(m.getId()));
            mons.add(row);
        }
        out.put("monitors", mons);
        List<K6CompatScan> recent = scans.recent();
        out.put("scan", recent.isEmpty() ? null : scanDto(recent.get(0), null));
        return out;
    }

    static Map<String, Object> profileDto(K6Profile p) {
        if (p == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bin", p.bin());
        m.put("available", p.available());
        m.put("version", p.version());
        m.put("modern_syntax", p.modernSyntax());
        m.put("api_address", p.needsApiAddress());
        m.put("probed_at", p.probedAtMs() <= 0 ? null : ISO.format(Instant.ofEpochMilli(p.probedAtMs())));
        return m;
    }

    // ── Ayarlar ──────────────────────────────────────────────────────────────────────────────────────

    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> save(@RequestBody(required = false) Map<String, Object> body,
                                                    HttpSession session, HttpServletRequest request) {
        requireGlobal(session);
        Map<String, Object> in = body == null ? Map.of() : body;
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        for (Map.Entry<String, String> f : FIELDS.entrySet()) {
            if (!in.containsKey(f.getKey())) continue;
            String next = normalize(f.getKey(), in.get(f.getKey()));
            boolean offable = !"excluded_ids".equals(f.getKey());
            String cur = offable ? K6EngineSettings.shown(current(f.getValue())) : current(f.getValue());
            if (next.equals(cur)) continue;   // değişmeyen değer yazılmaz ve yeniden doğrulanmaz
            // Boşaltılan alan "none" yazılır: boş değer geçersiz kılmayı siler ve ortam varsayılanı geri gelirdi (L1).
            values.put(f.getValue(), offable && next.isEmpty() ? K6EngineSettings.OFF : next);
            before.put(f.getKey(), cur);
            after.put(f.getKey(), next);
        }
        if (values.isEmpty()) return ok(view());
        settings.save(Map.of("values", values), actor(session));
        auditService.recordAction("K6_ENGINE_SETTINGS_SAVE", session, request, "SETTINGS", "k6-engine",
                AuditDetail.of("fields", new ArrayList<>(after.keySet())), AuditDiff.diff(before, after));
        checker.probeK6();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("data", view());
        out.put("message", Msg.t("k6 motoru ayarları kaydedildi (yeniden başlatma gerekmez).",
                "k6 engine settings saved (no restart needed)."));
        return ResponseEntity.ok(out);
    }

    /** Gelen değeri ayar metnine çevirir; GODEBUG normalize edilir (sıra korunur, anahtar küçük harf). */
    static String normalize(String field, Object raw) {
        if (raw == null) return "";
        if ("excluded_ids".equals(field)) {
            if (raw instanceof Collection<?> c) {
                List<Long> ids = new ArrayList<>();
                for (Object o : c) {
                    if (o == null) continue;
                    try {
                        ids.add(Long.parseLong(String.valueOf(o).trim()));
                    } catch (NumberFormatException e) {
                        throw new FieldValidationException("excluded_ids", Msg.t(
                                "Tarama dışı izleme listesi yalnız izleme kimlikleri içerebilir.",
                                "The excluded monitor list may contain monitor ids only."));
                    }
                }
                return K6EngineSettings.joinIds(new java.util.LinkedHashSet<>(ids));
            }
            return String.valueOf(raw).trim();
        }
        String v = String.valueOf(raw).trim();
        if ("godebug".equals(field) && !v.isEmpty()) return K6EngineSettings.normalizeGodebug(v);
        return v;
    }

    // ── Geçiş / geri dönüş ───────────────────────────────────────────────────────────────────────────

    @PostMapping("/switch")
    public ResponseEntity<Map<String, Object>> switchToCandidate(@RequestBody(required = false) Map<String, Object> body,
                                                                 HttpSession session, HttpServletRequest request) {
        requireGlobal(session);
        requireConfirm(body);
        requireNoOpenScan();
        requireSingleVersion();
        checker.probeK6();
        ScriptedCheckerService.Profiles p = checker.profiles();
        K6Profile cand = p.candidate();
        K6Profile active = p.active();
        if (cand == null || !cand.available()) {
            throw new CodedConflictException("K6_CANDIDATE_UNAVAILABLE", Msg.t(
                    "Aday k6 ikilisi bu pod'da çalıştırılamıyor — geçiş yapılmadı.",
                    "The candidate k6 binary can't be run on this pod — nothing was switched."));
        }
        if (active != null && cand.bin().equals(active.bin())) {
            throw new CodedConflictException("K6_ALREADY_ACTIVE", Msg.t(
                    "Aday ikili zaten etkin.", "The candidate binary is already active."));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(ScriptedCheckerService.K6_BIN_KEY, cand.bin());
        values.put(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY, active == null ? K6EngineSettings.OFF : active.bin());
        settings.save(Map.of("values", values), actor(session));
        auditService.recordAction("K6_ENGINE_SWITCH", session, request, "SETTINGS", "k6-engine",
                AuditDetail.of("from_bin", active == null ? null : active.bin(),
                        "from_version", active == null ? null : active.version(),
                        "to_bin", cand.bin(), "to_version", cand.version()));
        checker.probeK6();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("data", view());
        out.put("message", Msg.t(
                "Aday k6 etkin yapıldı (" + cand.version() + "). Senaryo kontrolleri bundan sonra bu sürümle koşar; "
                + "diğer pod'lar 10 sn içinde geçer. Sorun görürseniz 'Yedeğe geri dön'.",
                "The candidate k6 is now active (" + cand.version() + "). Scripted checks run with this version from now on; "
                + "other pods follow within 10 s. If you see problems, use 'Roll back'."));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/rollback")
    public ResponseEntity<Map<String, Object>> rollback(@RequestBody(required = false) Map<String, Object> body,
                                                        HttpSession session, HttpServletRequest request) {
        requireGlobal(session);
        requireConfirm(body);
        checker.probeK6();
        ScriptedCheckerService.Profiles p = checker.profiles();
        K6Profile rb = p.rollback();
        K6Profile active = p.active();
        if (rb == null || !rb.available()) {
            throw new CodedConflictException("K6_ROLLBACK_UNAVAILABLE", Msg.t(
                    "Yedek k6 ikilisi tanımlı değil ya da bu pod'da çalıştırılamıyor — geri dönüş yapılmadı.",
                    "No rollback k6 binary is set, or it can't be run on this pod — nothing was rolled back."));
        }
        if (active != null && rb.bin().equals(active.bin())) {
            throw new CodedConflictException("K6_ALREADY_ACTIVE", Msg.t(
                    "Yedek ikili zaten etkin.", "The rollback binary is already active."));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(ScriptedCheckerService.K6_BIN_KEY, rb.bin());
        values.put(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY, K6EngineSettings.OFF);
        settings.save(Map.of("values", values), actor(session));
        auditService.recordAction("K6_ENGINE_ROLLBACK", session, request, "SETTINGS", "k6-engine",
                AuditDetail.of("from_bin", active == null ? null : active.bin(),
                        "from_version", active == null ? null : active.version(),
                        "to_bin", rb.bin(), "to_version", rb.version()));
        checker.probeK6();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("data", view());
        out.put("message", Msg.t(
                "Yedek k6'ya dönüldü (" + rb.version() + "). Diğer pod'lar 10 sn içinde geçer.",
                "Rolled back to the rollback k6 (" + rb.version() + "). Other pods follow within 10 s."));
        return ResponseEntity.ok(out);
    }

    private void requireNoOpenScan() {
        scans.settleStale();
        for (K6CompatScan s : scans.recent()) {
            if (K6CompatScanService.OPEN_STATES.contains(s.getState())) {
                throw new CodedConflictException("K6_SCAN_RUNNING", Msg.t(
                        "Uyumluluk taraması sürerken motor değiştirilemez — taramanın bitmesini bekleyin ya da iptal edin.",
                        "The engine can't be switched while a compatibility scan runs — wait for it to finish or cancel it."));
            }
        }
    }

    /**
     * Geçiş, BÜTÜN pod'lar bu sürümü koşarken yapılır (inceleme 2026-10-30, M2): ayar her pod'a gider; dağıtım sürerken
     * eski sürüm pod'larında aday ikili yoktur ya da eski kod onu doğru koşamaz (her senaryo kontrolü "komut çalıştırılamadı"
     * ile hata verir, alarmlar açılır). Pod sürümleri okunamazsa da geçiş yapılmaz.
     */
    private void requireSingleVersion() {
        List<Map<String, String>> others = otherVersionPods();
        if (others == null) {
            throw new CodedConflictException("K6_POD_VERSIONS_UNKNOWN", Msg.t(
                    "Pod sürümleri okunamadı — bütün pod'ların aynı sürümü koştuğu doğrulanamadı, geçiş yapılmadı. Biraz sonra yeniden deneyin.",
                    "Pod versions could not be read — it can't be confirmed that every pod runs the same version, so nothing was switched. Try again shortly."));
        }
        if (!others.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map<String, String> o : others) parts.add(o.get("pod") + " (" + o.get("version") + ")");
            throw new CodedConflictException("K6_MIXED_VERSIONS", Msg.t(
                    "Başka sürüm koşan pod var: " + String.join(", ", parts)
                            + ". Dağıtım bitip bütün pod'lar bu sürümü koşunca yeniden deneyin.",
                    "Some pods run another version: " + String.join(", ", parts)
                            + ". Try again once the rollout has finished and every pod runs this version."),
                    Map.of("other_version_pods", others));
        }
    }

    private static void requireConfirm(Map<String, Object> body) {
        if (body == null || !Boolean.TRUE.equals(body.get("confirm"))) {
            throw new FieldValidationException("confirm", Msg.t(
                    "Bu işlem onay ister (confirm: true).", "This action needs confirmation (confirm: true)."));
        }
    }

    // ── Tarama ──────────────────────────────────────────────────────────────────────────────────────

    @PostMapping("/scans")
    public ResponseEntity<Map<String, Object>> startScan(@RequestBody(required = false) Map<String, Object> body,
                                                         HttpSession session, HttpServletRequest request) {
        requireGlobal(session);
        List<Long> ids = null;
        Object raw = body == null ? null : body.get("monitor_ids");
        if (raw instanceof Collection<?> c && !c.isEmpty()) {
            ids = new ArrayList<>();
            for (Object o : c) {
                try {
                    ids.add(Long.parseLong(String.valueOf(o).trim()));
                } catch (NumberFormatException e) {
                    throw new FieldValidationException("monitor_ids", Msg.t(
                            "İzleme listesi yalnız izleme kimlikleri içerebilir.", "The monitor list may contain monitor ids only."));
                }
                if (ids.size() > 2000) break;
            }
        }
        K6CompatScan s = scans.start(ids, actor(session), displayName(session));
        auditService.recordAction("K6_COMPAT_SCAN_START", session, request, "SETTINGS", "k6-compat-scan:" + s.getId(),
                AuditDetail.of("scan_id", s.getId(), "scope", s.getScope(), "monitors", s.getTotal(),
                        "candidate_version", s.getCandidateVersion()));
        return ok(scanDto(s, null));
    }

    @GetMapping("/scans")
    public ResponseEntity<Map<String, Object>> listScans(HttpSession session) {
        requireGlobal(session);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (K6CompatScan s : scans.recent()) rows.add(scanDto(s, null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scans", rows);
        return ok(out);
    }

    @GetMapping("/scans/{id}")
    public ResponseEntity<Map<String, Object>> scan(@PathVariable("id") Long id, HttpSession session) {
        requireGlobal(session);
        scans.settleStale();
        K6CompatScan s = scans.find(id).orElseThrow(() -> new java.util.NoSuchElementException(Msg.t(
                "Uyumluluk taraması bulunamadı (#" + id + ").", "Compatibility scan not found (#" + id + ").")));
        List<K6CompatResult> rows = scans.resultsFor(id);
        Map<String, Object> out = scanDto(s, rows);
        Map<Long, String> teamNames = teamNames();
        List<Map<String, Object>> list = new ArrayList<>();
        for (K6CompatResult r : rows) list.add(resultDto(r, teamNames));
        out.put("results", list);
        return ok(out);
    }

    @PostMapping("/scans/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable("id") Long id, HttpSession session,
                                                      HttpServletRequest request) {
        requireGlobal(session);
        boolean done = scans.cancel(id);
        if (!done) {
            throw new CodedConflictException("K6_SCAN_NOT_RUNNING", Msg.t(
                    "Bu tarama zaten bitmiş ya da iptal edilmiş.", "This scan has already finished or been cancelled."));
        }
        auditService.recordAction("K6_COMPAT_SCAN_CANCEL", session, request, "SETTINGS", "k6-compat-scan:" + id,
                AuditDetail.of("scan_id", id));
        K6CompatScan s = scans.find(id).orElse(null);
        return ok(s == null ? Map.of() : scanDto(s, null));
    }

    static Map<String, Object> scanDto(K6CompatScan s, List<K6CompatResult> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("state", s.getState());
        m.put("started_at", s.getStartedAt());
        m.put("finished_at", s.getFinishedAt());
        m.put("heartbeat_at", s.getHeartbeatAt());
        m.put("started_by_name", s.getStartedByName() == null ? s.getStartedBy() : s.getStartedByName());
        m.put("owner_pod", podOf(s.getOwner()));
        m.put("active_bin", s.getActiveBin());
        m.put("active_version", s.getActiveVersion());
        m.put("candidate_bin", s.getCandidateBin());
        m.put("candidate_version", s.getCandidateVersion());
        m.put("scope", s.getScope());
        m.put("total", s.getTotal());
        m.put("done", s.getDoneCount());
        m.put("regressions", s.getRegressions());
        m.put("improvements", s.getImprovements());
        m.put("unchanged", s.getUnchanged());
        m.put("no_baseline", s.getNoBaseline());
        m.put("changed", s.getChangedCount());
        m.put("not_run", s.getNotRun());
        m.put("new_fail", s.getNewFail());
        m.put("failure", s.getFailure());
        if (rows != null) m.put("counts", K6CompatScanService.countByDiff(rows));
        return m;
    }

    static Map<String, Object> resultDto(K6CompatResult r, Map<Long, String> teamNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("monitor_id", r.getMonitorId());
        m.put("monitor_name", r.getMonitorName());
        m.put("team_id", r.getTeamId());
        m.put("team_name", r.getTeamId() == null ? null : teamNames.get(r.getTeamId()));
        m.put("diff", r.getDiff());
        m.put("status", r.getStatus());
        m.put("exit_code", r.getExitCode());
        m.put("checks_passed", r.getChecksPassed());
        m.put("checks_failed", r.getChecksFailed());
        m.put("duration_ms", r.getDurationMs());
        m.put("error_class", r.getErrorClass());
        m.put("error", r.getError());
        m.put("archive", r.getArchive());
        m.put("archive_error", r.getArchiveError());
        m.put("via_proxy", r.getViaProxy());
        m.put("retried", r.getRetried());
        m.put("baseline_status", r.getBaselineStatus());
        m.put("baseline_exit_code", r.getBaselineExitCode());
        m.put("baseline_checks_passed", r.getBaselineChecksPassed());
        m.put("baseline_checks_failed", r.getBaselineChecksFailed());
        m.put("baseline_duration_ms", r.getBaselineDurationMs());
        m.put("baseline_checked_at", r.getBaselineCheckedAt());
        m.put("baseline_k6_version", r.getBaselineK6Version());
        m.put("baseline_error_class", r.getBaselineErrorClass());
        m.put("checked_at", r.getCheckedAt());
        return m;
    }

    /** {@code <pod>-<8 hex>} örnek kimliğinden pod adı. */
    static String podOf(String owner) {
        if (owner == null) return null;
        int i = owner.lastIndexOf('-');
        return i > 0 ? owner.substring(0, i) : owner;
    }

    private Map<Long, String> teamNames() {
        Map<Long, String> out = new HashMap<>();
        try {
            for (Team t : teams.findAll()) out.put(t.getId(), t.getName());
        } catch (Exception ignored) {
            // takım adı okunamazsa liste adsız gösterir
        }
        return out;
    }

    private String current(String key) {
        String v = settings.getString(key, "");
        return v == null ? "" : v.trim();
    }

    private static void requireGlobal(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t("k6 motoru ayarlarını yalnız global yöneticiler görebilir ve değiştirebilir.",
                    "Only global administrators can view and change the k6 engine settings."));
        }
    }

    private static String actor(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        return u == null ? "system" : String.valueOf(u);
    }

    private static String displayName(HttpSession session) {
        Object d = session == null ? null : session.getAttribute("displayName");
        return d == null ? actor(session) : String.valueOf(d);
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("data", data);
        return ResponseEntity.ok(out);
    }
}
