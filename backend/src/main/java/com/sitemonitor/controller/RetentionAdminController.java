package com.sitemonitor.controller;

import com.sitemonitor.util.Csv;
import com.sitemonitor.util.Msg;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.RetentionRun;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.RetentionRunItemRepository;
import com.sitemonitor.repository.RetentionRunRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.retention.RetentionCatalog;
import com.sitemonitor.service.retention.RetentionPolicy;
import com.sitemonitor.service.retention.RetentionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    /** Gece temizliğinin CANLI zamanlaması — SchedulerService ile aynı anahtar/varsayılan. */
    @Value("${" + RetentionCatalog.CLEANUP_CRON_KEY + ":" + RetentionCatalog.CLEANUP_CRON_DEFAULT + "}")
    private String cleanupCron;

    /** Uyum onayı (kim/ne zaman onayladı) ayar anahtarı öneki — politika id'siyle birleşir. */
    private static final String APPROVAL_PREFIX = com.sitemonitor.service.AppSettingsCatalog.RETENTION_APPROVAL_PREFIX;

    private final RetentionService retentionService;
    private final RetentionRunRepository runRepo;
    private final RetentionRunItemRepository itemRepo;
    /** Değişiklik geçmişi audit_log'dan okunur (hash-zinciri korumalı, arşivlenen kaynak). */
    private final AuditLogRepository auditLogRepo;
    private final AppSettingsService settingsService;
    private final AuditService auditService;
    private final PermissionService permissionService;
    /** Saatlik özetin geriye doldurulması için — ham seri kısaltılmadan ÖNCE çalıştırılır. */
    private final com.sitemonitor.service.SchedulerService schedulerService;

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
        data.put("inventory_auto_purge_days", settingsService.getInt(com.sitemonitor.service.InventoryAutoPurgeService.KEY, 0));   // envanter #10
        // Sabit metin DEĞİL: env/config ile ezilirse arayüz gerçek zamanlamayı göstersin.
        data.put("cleanup_cron", cleanupCron);
        data.put("cleanup_zone", RetentionCatalog.CLEANUP_ZONE);
        data.put("totals", totals(policies));
        data.put("last_run", runRepo.findFirstByDryRunFalseOrderByStartedAtDesc()
                .map(this::runToMap).orElse(null));
        data.put("approvals", approvals());
        return ok(Map.of("data", data));
    }

    /** Sıralama beyaz-listesi: istemci anahtarı → entity alanı. Bilinmeyen anahtar → started_at. */
    private static final Map<String, String> RUN_SORTS = Map.of(
            "started_at", "startedAt", "total_deleted", "totalDeleted",
            "duration_ms", "durationMs", "failed_count", "failedCount");
    private static final int RUNS_MAX_SIZE = 100;
    private static final int RUNS_CSV_MAX_ROWS = 5000;

    /**
     * Son çalışmalar (dry-run'lar dahil) + tablo bazında detay — sunucu-taraflı sayfalama,
     * süzgeç (tür / hatalı / politika / tarih / metin) ve sıralama. Eski {@code limit} param'ı
     * geriye uyumlu (sayfa boyutu sayılır). Yanıt: data/total/page/size/total_pages.
     */
    @GetMapping("/runs")
    public ResponseEntity<Map<String, Object>> runs(
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(defaultValue = "all") String kind,
            @RequestParam(defaultValue = "false") boolean failed,
            @RequestParam(required = false) String policyId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "started_at") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            HttpSession session) {
        requireAccess(session);
        int pageSize = size != null ? size : (limit != null ? limit : 20);
        pageSize = Math.max(1, Math.min(pageSize, RUNS_MAX_SIZE));
        var pg = runRepo.search(runKind(kind), failed, blank(since), blank(until), likeTerm(q), blank(policyId),
                PageRequest.of(Math.max(0, page), pageSize, runSort(sort, dir)));
        List<RetentionRun> list = pg.getContent();
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", out);
        body.put("total", pg.getTotalElements());
        body.put("page", pg.getNumber());
        body.put("size", pg.getSize());
        body.put("total_pages", pg.getTotalPages());
        return ok(body);
    }

    /** Ekranla AYNI süzgeçle CSV — en fazla {@value #RUNS_CSV_MAX_ROWS} koşum; kalemler tek hücrede. */
    @GetMapping("/runs/export")
    public ResponseEntity<Void> exportRuns(
            @RequestParam(defaultValue = "all") String kind,
            @RequestParam(defaultValue = "false") boolean failed,
            @RequestParam(required = false) String policyId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "started_at") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            HttpSession session, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        requireAccess(session);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"retention-runs.csv\"");
        java.io.Writer w = response.getWriter();
        w.write('\uFEFF');   // Excel UTF-8 BOM
        w.write(Csv.row("id", "started_at", "finished_at", "kind", "total_deleted", "failed_count",
                "duration_ms", "triggered_by", "instance_id", "items"));
        int rows = 0;
        for (int p = 0; rows < RUNS_CSV_MAX_ROWS; p++) {
            var chunk = runRepo.search(runKind(kind), failed, blank(since), blank(until), likeTerm(q), blank(policyId),
                    PageRequest.of(p, RUNS_MAX_SIZE, runSort(sort, dir))).getContent();
            if (chunk.isEmpty()) break;
            List<Long> ids = chunk.stream().map(RetentionRun::getId).toList();
            Map<Long, StringBuilder> itemsByRun = new LinkedHashMap<>();
            for (var it : itemRepo.findByRunIdInOrderByIdAsc(ids)) {
                StringBuilder sb = itemsByRun.computeIfAbsent(it.getRunId(), k -> new StringBuilder());
                if (sb.length() > 0) sb.append("; ");
                sb.append(it.getPolicyId()).append('=').append(it.getRowsDeleted() == null ? 0 : it.getRowsDeleted());
                if (it.getSkipped() != null) sb.append(" [").append(it.getSkipped()).append(']');
                if (it.getError() != null) sb.append(" !").append(it.getError());
            }
            for (RetentionRun r : chunk) {
                w.write(Csv.row(
                        String.valueOf(r.getId()), r.getStartedAt(), r.getFinishedAt(),
                        Boolean.TRUE.equals(r.getHoldActive()) ? "hold" : Boolean.TRUE.equals(r.getDryRun()) ? "dry" : "real",
                        String.valueOf(r.getTotalDeleted()), String.valueOf(r.getFailedCount()),
                        r.getDurationMs() == null ? "" : String.valueOf(r.getDurationMs()),
                        r.getTriggeredBy(), r.getInstanceId(),
                        itemsByRun.getOrDefault(r.getId(), new StringBuilder()).toString()));
                rows++;
            }
            if (chunk.size() < RUNS_MAX_SIZE) break;
        }
        w.flush();
        return null;
    }

    static String runKind(String kind) {
        String k = kind == null ? "all" : kind.trim().toLowerCase(Locale.ROOT);
        return switch (k) { case "real", "dry", "hold" -> k; default -> "all"; };
    }

    static org.springframework.data.domain.Sort runSort(String sort, String dir) {
        String prop = RUN_SORTS.getOrDefault(sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT), "startedAt");
        var d = "asc".equalsIgnoreCase(dir) ? org.springframework.data.domain.Sort.Direction.ASC
                                            : org.springframework.data.domain.Sort.Direction.DESC;
        return org.springframework.data.domain.Sort.by(d, prop)
                .and(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id"));
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    /** LIKE terimi: küçük harf + boş → null (sorgu %…% sarar). */
    static String likeTerm(String q) {
        String s = blank(q);
        return s == null ? null : s.toLowerCase(Locale.ROOT);
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
                "message", Msg.t("Dry-run tamamlandı — hiçbir kayıt silinmedi", "Dry run complete — no records were deleted")));
    }

    /** Elle temizlik — YIKICI. Legal hold açıkken reddedilir. */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runNow(HttpSession session, HttpServletRequest request) {
        requireAccess(session);
        if (retentionService.holdActive()) {
            throw new IllegalStateException(
                    Msg.t("Yasal saklama (legal hold) açıkken temizlik çalıştırılamaz. Önce ayarı kapatın.", "Cleanup cannot run while legal hold is on. Turn the setting off first."));
        }
        RetentionService.RunResult run = retentionService.execute(false, actor(session));
        auditService.recordAction("RETENTION_RUN_MANUAL", session, request, "RETENTION", "run",
                "{\"rows\":" + run.totalRows() + ",\"failed\":" + run.failedCount() + "}");
        return ok(Map.of("data", runResultToMap(run),
                "message", run.totalRows() + Msg.t(" satır silindi", " rows deleted")));
    }

    /**
     * Saatlik özeti GERİYE DÖNÜK doldurur. Ham seri kısaltılmadan önce çalıştırılmalıdır:
     * çalıştıktan sonra "olay hangi saatte oldu" bilgisi ham satırlar silinse de kalır.
     * Yalnız yazar/günceller (idempotent upsert), hiçbir satır SİLMEZ — tekrar çalıştırmak güvenlidir.
     *
     * @param days kaç gün geriye doldurulacak (varsayılan: en uzun ham seri saklama süresi)
     */
    @PostMapping("/backfill-hourly")
    public ResponseEntity<Map<String, Object>> backfillHourly(
            @RequestParam(defaultValue = "0") int days, HttpSession session, HttpServletRequest request) {
        requireAccess(session);
        int d = days > 0 ? days : maxRawSeriesDays();
        long t0 = System.currentTimeMillis();
        int buckets = schedulerService.backfillHourlyRollup(d);
        long ms = System.currentTimeMillis() - t0;
        auditService.recordAction("RETENTION_ROLLUP_BACKFILL", session, request, "RETENTION", "backfill-hourly",
                "{\"days\":" + d + ",\"buckets\":" + buckets + ",\"ms\":" + ms + "}");
        return ok(Map.of("data", Map.of("days", d, "buckets", buckets, "duration_ms", ms),
                "message", buckets + Msg.t(" saatlik kova dolduruldu (", " hourly buckets filled (") + d + Msg.t(" gün)", " days)")));
    }

    /** Ham kontrol serilerinin EN UZUN saklama süresi — geriye doldurmanın doğal üst sınırı. */
    private int maxRawSeriesDays() {
        return RetentionCatalog.ALL.stream()
                .filter(p -> p.id().startsWith("series-"))
                .mapToInt(retentionService::effectiveDays)
                .max().orElse(180);
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
        // Politika id → [eski gün, yeni gün]. Eski değer settingsService.save ÇAĞRILMADAN ÖNCE
        // hesaplanmalı — kayıt sonrası eski değere ulaşmanın yolu yok (app_settings yalnız son
        // yazan damgasını tutar, geçmiş tutmaz).
        Map<String, int[]> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            RetentionPolicy p = RetentionCatalog.configurable().stream()
                    .filter(x -> x.settingKey().equals(e.getKey())).findFirst().orElse(null);
            if (p == null) continue;                       // hold/batch gibi anahtarlar → taban kuralı yok
            int current = retentionService.effectiveDays(p);
            String raw = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
            int v;
            if (raw.isEmpty()) {
                // Boş = override kaldır → kod varsayılanına dön. Bu da GERÇEK bir değişikliktir;
                // eskiden sessizce atlanıyor ve denetim kaydına hiç düşmüyordu.
                v = Math.max(p.minDays(), p.defaultDays());
                if (p.zeroMeansNever() && p.defaultDays() == 0) v = 0;
            } else {
                try { v = Integer.parseInt(raw); }
                catch (NumberFormatException ex) { throw new IllegalArgumentException(p.settingKey() + Msg.t(": sayı bekleniyor", ": a number is expected")); }
                boolean zeroOk = p.zeroMeansNever() && v == 0;
                if (!zeroOk && v < p.minDays()) {
                    throw new IllegalArgumentException(
                            Msg.t(p.table() + " için en az " + p.minDays() + " gün girilmelidir (girilen: " + v + ")", p.table() + " requires at least " + p.minDays() + " days (entered: " + v + ")"));
                }
            }
            if (v != current) {
                changed.put(p.id(), new int[]{ current, v });
                if (v < current) shortened.add(p.id() + ":" + current + "→" + v);
            }
        }

        settingsService.save(Map.of("values", values), actor(session));

        // Politika BAŞINA denetim olayı: resource_id = politika id → "bu politikanın tüm geçmişi"
        // sorgulanabilir; changes alanı {"days":{"from":X,"to":Y}} taşır ve hash-zincirine girer.
        // Aynı kaydetmedeki satırlar correlation_id ile kendiliğinden gruplanır.
        for (Map.Entry<String, int[]> c : changed.entrySet()) {
            RetentionPolicy p = RetentionCatalog.byId(c.getKey()).orElseThrow();
            int from = c.getValue()[0], to = c.getValue()[1];
            auditService.recordAction("RETENTION_POLICY_CHANGE", session, request,
                    "RETENTION_POLICY", p.id(),
                    p.table() + " · " + from + "g → " + to + "g",
                    AuditDiff.diff(Map.of("days", from), Map.of("days", to)));
        }
        auditService.recordAction("RETENTION_SETTINGS_SAVE", session, request, "RETENTION", "settings",
                "{\"keys\":" + values.size() + ",\"changed\":" + changed.size() + "}");
        if (!shortened.isEmpty()) {
            // Kısaltma geri alınamaz veri kaybı üretir → ayrı, aranabilir bir denetim olayı.
            auditService.recordAction("RETENTION_SETTINGS_SHORTENED", session, request, "RETENTION", "settings",
                    "{\"changes\":\"" + String.join(", ", shortened).replace("\"", "'") + "\"}");
            log.warn("Saklama süresi KISALTILDI ({}): {}", actor(session), String.join(", ", shortened));
        }
        return ok(Map.of("data", retentionService.overview(false),
                "changed", changed.size(),
                "message", Msg.t("Saklama ayarları kaydedildi (anında geçerli)", "Retention settings saved (effective immediately)")));
    }

    /**
     * Saklama süresi değişiklik geçmişi — "kim, ne zaman, hangi politikayı, hangi değerden hangi
     * değere çekti". Kaynak audit_log'dur (hash-zinciri korumalı, silinmeden önce arşivlenir).
     *
     * <p>Neden ayrı uç nokta: denetim uçları {@code audit_log.read} + global-admin/AUDIT rolü
     * ister; bu sayfa {@code settings.retention} (ve bootstrap-admin bypass) ile açılır. İkisi
     * ayrık olduğundan, ekranın doğrudan /api/admin/audit çağırması meşru bir retention adminine
     * 403 döndürürdü.
     */
    @GetMapping("/changes")
    public ResponseEntity<Map<String, Object>> changes(
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String policyId, HttpSession session) {
        requireAccess(session);
        int n = Math.max(1, Math.min(limit, 200));
        List<AuditLog> rows = (policyId == null || policyId.isBlank())
                ? auditLogRepo.findByResourceTypeOrderByEventTimeDesc("RETENTION_POLICY", PageRequest.of(0, n))
                : auditLogRepo.findByResourceTypeAndResourceIdOrderByEventTimeDesc(
                        "RETENTION_POLICY", policyId, PageRequest.of(0, n));

        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditLog a : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("policy_id", a.getResourceId());
            m.put("table", RetentionCatalog.byId(a.getResourceId()).map(RetentionPolicy::table).orElse(null));
            m.put("actor", a.getActor());
            m.put("at", a.getEventTime());
            m.put("ip", a.getIpAddress());
            m.put("correlation_id", a.getCorrelationId());
            m.put("detail", a.getDetail());
            int[] fromTo = parseDaysDiff(a.getChanges());
            m.put("from", fromTo == null ? null : fromTo[0]);
            m.put("to", fromTo == null ? null : fromTo[1]);
            out.add(m);
        }
        return ok(Map.of("data", out));
    }

    /** {@code {"days":{"from":90,"to":365}}} → [90, 365]; ayrıştırılamazsa null. */
    static int[] parseDaysDiff(String changes) {
        if (changes == null || changes.isBlank()) return null;
        var m = DAYS_DIFF.matcher(changes);
        if (!m.find()) return null;
        try { return new int[]{ Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) }; }
        catch (NumberFormatException e) { return null; }
    }

    private static final java.util.regex.Pattern DAYS_DIFF = java.util.regex.Pattern.compile(
            "\"days\"\\s*:\\s*\\{\\s*\"from\"\\s*:\\s*(-?\\d+)\\s*,\\s*\"to\"\\s*:\\s*(-?\\d+)");

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
        String before = settingsService.getString(APPROVAL_PREFIX + policyId, "");
        settingsService.save(Map.of("values", Map.of(APPROVAL_PREFIX + policyId, value)), actor(session));
        // Onay da denetlenir: dokümandaki "kim ne zaman onayladı" satırının kaynağı burasıdır.
        auditService.recordAction("RETENTION_APPROVAL_SAVE", session, request, "RETENTION_POLICY", policyId,
                note.isEmpty() ? "onay kaldırıldı" : note,
                AuditDiff.diff(Map.of("approval", before == null ? "" : before), Map.of("approval", value)));
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
