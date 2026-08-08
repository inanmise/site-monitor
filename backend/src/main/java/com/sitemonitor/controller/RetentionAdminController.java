package com.sitemonitor.controller;

import com.sitemonitor.model.RetentionRun;
import com.sitemonitor.repository.RetentionRunItemRepository;
import com.sitemonitor.repository.RetentionRunRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.retention.RetentionCatalog;
import com.sitemonitor.service.retention.RetentionPolicy;
import com.sitemonitor.service.retention.RetentionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Ayarlar → Veri Saklama. Saklama politikalarını tek noktadan görüntüler ve yönetir:
 * hangi veri nerede ne kadar duruyor, ne zaman silinecek, bu gece kaç satır gidecek.
 *
 * <p>Ayar değişiklikleri {@link AppSettingsService} üzerinden CANLI yansır (yeniden başlatma yok):
 * Kontrol Geçmişi kırpması ve dry-run tahmini anında değişir, fiziksel silme ilk gece koşusunda
 * ya da {@code POST /run} ile olur.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/retention")
@RequiredArgsConstructor
public class RetentionAdminController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Uyum onayı (kim/ne zaman onayladı) ayar anahtarı öneki — politika id'siyle birleşir. */
    private static final String APPROVAL_PREFIX = "site.monitor.retention.approval.";

    private final RetentionService retentionService;
    private final RetentionRunRepository runRepo;
    private final RetentionRunItemRepository itemRepo;
    private final AppSettingsService settingsService;
    private final AuditService auditService;
    private final PermissionService permissionService;

    // ── Görüntüleme ──────────────────────────────────────────────────────────────

    /**
     * Politika matrisi + canlı istatistikler.
     * @param estimate true → her politika için "şu an kaç satır silinirdi" hesaplanır (biraz yavaş)
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(
            @RequestParam(defaultValue = "false") boolean estimate, HttpSession session) {
        requireAccess(session);
        List<Map<String, Object>> policies = retentionService.overview(estimate);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policies", policies);
        data.put("hold_active", retentionService.holdActive());
        data.put("hold_key", RetentionCatalog.HOLD_KEY);
        data.put("batch_size", settingsService.getInt(RetentionCatalog.BATCH_KEY, 10000));
        data.put("cleanup_cron", "0 30 3 * * *");
        data.put("totals", totals(policies));
        data.put("last_run", runRepo.findFirstByDryRunFalseOrderByStartedAtDesc()
                .map(this::runToMap).orElse(null));
        data.put("approvals", approvals());
        return ok(Map.of("data", data));
    }

    /** Son çalışmalar (dry-run'lar dahil) + tablo bazında detay. */
    @GetMapping("/runs")
    public ResponseEntity<Map<String, Object>> runs(
            @RequestParam(defaultValue = "10") int limit, HttpSession session) {
        requireAccess(session);
        int n = Math.max(1, Math.min(limit, 50));
        List<RetentionRun> list = runRepo.findAllByOrderByStartedAtDesc(PageRequest.of(0, n)).getContent();
        List<Long> ids = list.stream().map(RetentionRun::getId).toList();
        Map<Long, List<Map<String, Object>>> itemsByRun = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (var it : itemRepo.findByRunIdInOrderByIdAsc(ids)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("policy_id", it.getPolicyId());
                m.put("table", it.getTableName());
                m.put("cutoff", it.getCutoff());
                m.put("rows", it.getRowsDeleted());
                m.put("elapsed_ms", it.getElapsedMs());
                m.put("error", it.getError());
                m.put("skipped", it.getSkipped());
                itemsByRun.computeIfAbsent(it.getRunId(), k -> new ArrayList<>()).add(m);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (RetentionRun r : list) {
            Map<String, Object> m = runToMap(r);
            m.put("items", itemsByRun.getOrDefault(r.getId(), List.of()));
            out.add(m);
        }
        return ok(Map.of("data", out));
    }

    // ── Eylemler ─────────────────────────────────────────────────────────────────

    /** Dry-run: hiçbir satır silinmez, yalnız sayım yapılır. */
    @PostMapping("/dry-run")
    public ResponseEntity<Map<String, Object>> dryRun(HttpSession session, HttpServletRequest request) {
        requireAccess(session);
        RetentionService.RunResult run = retentionService.execute(true, actor(session));
        auditService.recordAction("RETENTION_DRY_RUN", session, request, "RETENTION", "dry-run",
                "{\"rows\":" + run.totalRows() + "}");
        return ok(Map.of("data", runResultToMap(run),
                "message", "Dry-run tamamlandı — hiçbir kayıt silinmedi"));
    }

    /** Elle temizlik — YIKICI. Legal hold açıkken reddedilir. */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runNow(HttpSession session, HttpServletRequest request) {
        requireAccess(session);
        if (retentionService.holdActive()) {
            throw new IllegalStateException(
                    "Yasal saklama (legal hold) açıkken temizlik çalıştırılamaz. Önce ayarı kapatın.");
        }
        RetentionService.RunResult run = retentionService.execute(false, actor(session));
        auditService.recordAction("RETENTION_RUN_MANUAL", session, request, "RETENTION", "run",
                "{\"rows\":" + run.totalRows() + ",\"failed\":" + run.failedCount() + "}");
        return ok(Map.of("data", runResultToMap(run),
                "message", run.totalRows() + " satır silindi"));
    }

    /**
     * Saklama sürelerini kaydeder. Taban sınırın altına inen değer REDDEDİLİR; mevcut değerin
     * altına inen her değişiklik ayrıca denetim kaydına yazılır (geri alınamaz veri kaybı riski).
     */
    @PutMapping("/settings")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> saveSettings(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireAccess(session);
        Map<String, Object> values = body.get("values") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : new LinkedHashMap<>();

        List<String> shortened = new ArrayList<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            RetentionPolicy p = RetentionCatalog.configurable().stream()
                    .filter(x -> x.settingKey().equals(e.getKey())).findFirst().orElse(null);
            if (p == null) continue;                       // hold/batch gibi anahtarlar → taban kuralı yok
            String raw = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
            if (raw.isEmpty()) continue;                   // boş = override kaldır (varsayılana dön)
            int v;
            try { v = Integer.parseInt(raw); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException(p.settingKey() + ": sayı bekleniyor"); }
            boolean zeroOk = p.zeroMeansNever() && v == 0;
            if (!zeroOk && v < p.minDays()) {
                throw new IllegalArgumentException(
                        p.table() + " için en az " + p.minDays() + " gün girilmelidir (girilen: " + v + ")");
            }
            int current = retentionService.effectiveDays(p);
            if (v < current) shortened.add(p.id() + ":" + current + "→" + v);
        }

        settingsService.save(Map.of("values", values), actor(session));
        auditService.recordAction("RETENTION_SETTINGS_SAVE", session, request, "RETENTION", "settings",
                "{\"keys\":" + values.size() + "}");
        if (!shortened.isEmpty()) {
            // Kısaltma geri alınamaz veri kaybı üretir → ayrı, aranabilir bir denetim olayı.
            auditService.recordAction("RETENTION_SETTINGS_SHORTENED", session, request, "RETENTION", "settings",
                    "{\"changes\":\"" + String.join(", ", shortened).replace("\"", "'") + "\"}");
            log.warn("Saklama süresi KISALTILDI ({}): {}", actor(session), String.join(", ", shortened));
        }
        return ok(Map.of("data", retentionService.overview(false),
                "message", "Saklama ayarları kaydedildi (anında geçerli)"));
    }

    /** Uyum/onay bilgisi: kişisel veri içeren politikalarda süreyi kim/ne zaman onayladı. */
    @PutMapping("/approval")
    public ResponseEntity<Map<String, Object>> saveApproval(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireAccess(session);
        String policyId = String.valueOf(body.getOrDefault("policy_id", "")).trim();
        if (RetentionCatalog.byId(policyId).isEmpty()) {
            throw new IllegalArgumentException("Bilinmeyen politika: " + policyId);
        }
        String note = String.valueOf(body.getOrDefault("note", "")).trim();
        String value = note.isEmpty() ? "" : (actor(session) + "|" + ISO.format(Instant.now()) + "|" + note);
        settingsService.save(Map.of("values", Map.of(APPROVAL_PREFIX + policyId, value)), actor(session));
        auditService.recordAction("RETENTION_APPROVAL_SAVE", session, request, "RETENTION", policyId, "{}");
        return ok(Map.of("data", approvals(), "message", "Onay kaydedildi"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Politika id → {by, at, note}. Boş onaylar listede yer almaz. */
    private Map<String, Object> approvals() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RetentionPolicy p : RetentionCatalog.ALL) {
            String raw = settingsService.getString(APPROVAL_PREFIX + p.id(), "");
            if (raw == null || raw.isBlank()) continue;
            String[] parts = raw.split("\\|", 3);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("by", parts.length > 0 ? parts[0] : null);
            m.put("at", parts.length > 1 ? parts[1] : null);
            m.put("note", parts.length > 2 ? parts[2] : null);
            out.put(p.id(), m);
        }
        return out;
    }

    private Map<String, Object> totals(List<Map<String, Object>> policies) {
        long rows = 0, bytes = 0, purgeable = 0;
        Set<String> counted = new HashSet<>();
        for (Map<String, Object> p : policies) {
            String table = String.valueOf(p.get("table"));
            if (counted.add(table)) {           // aynı tablonun birden çok kuralı boyutu iki kez saymasın
                if (p.get("rows") instanceof Number n) rows += n.longValue();
                if (p.get("bytes") instanceof Number n) bytes += n.longValue();
            }
            if (p.get("purgeable") instanceof Number n) purgeable += n.longValue();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows);
        m.put("bytes", bytes);
        m.put("purgeable", purgeable);
        m.put("policies", policies.size());
        m.put("tables", counted.size());
        return m;
    }

    private Map<String, Object> runToMap(RetentionRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("started_at", r.getStartedAt());
        m.put("finished_at", r.getFinishedAt());
        m.put("dry_run", r.getDryRun());
        m.put("hold_active", r.getHoldActive());
        m.put("triggered_by", r.getTriggeredBy());
        m.put("total_deleted", r.getTotalDeleted());
        m.put("failed_count", r.getFailedCount());
        m.put("duration_ms", r.getDurationMs());
        return m;
    }

    private Map<String, Object> runResultToMap(RetentionService.RunResult run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", run.runId());
        m.put("started_at", run.startedAt());
        m.put("finished_at", run.finishedAt());
        m.put("dry_run", run.dryRun());
        m.put("hold_active", run.holdActive());
        m.put("total_rows", run.totalRows());
        m.put("failed_count", run.failedCount());
        m.put("duration_ms", run.durationMs());
        List<Map<String, Object>> items = new ArrayList<>();
        for (RetentionService.ItemResult it : run.items()) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("policy_id", it.policyId());
            i.put("table", it.table());
            i.put("cutoff", it.cutoff());
            i.put("rows", it.rows());
            i.put("elapsed_ms", it.elapsedMs());
            i.put("error", it.error());
            i.put("skipped", it.skipped());
            items.add(i);
        }
        m.put("items", items);
        return m;
    }

    /** Bootstrap admin her zaman erişir (kilitlenme-güvenli); aksi halde matris izni. */
    private void requireAccess(HttpSession session) {
        if (Boolean.TRUE.equals(session != null ? session.getAttribute("bootstrapAdmin") : null)) return;
        permissionService.require(session, "settings.retention", "edit");
    }

    private String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "anonymous";
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(response);
    }
}
