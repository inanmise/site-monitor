package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.SecretMask;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.SqlPlaygroundService;
import com.sitemonitor.service.sqlplayground.SensitiveColumnException;
import com.sitemonitor.service.sqlplayground.SqlDataDictionary;
import com.sitemonitor.service.sqlplayground.SqlExportWriter;
import com.sitemonitor.service.sqlplayground.SqlParams;
import com.sitemonitor.service.sqlplayground.SqlRunRegistry;
import com.sitemonitor.service.sqlplayground.SqlSavedQueryService;
import com.sitemonitor.util.Msg;
import com.sitemonitor.util.SqlSamples;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * SQL Playground (yalnız global yönetici + {@code sql_playground.execute}). 2026-11-13 zenginleştirmesi: hassas kolon
 * maskeleme / reddi (A1), çalışan sorguyu durdurma (A2), sayfalı geçmiş (A4), adlandırılmış parametre (B4), sorgu planı
 * (C1), akışlı büyük dışa aktarım (C3), kayıtlı sorgular (D1), kategorili örnekler (D2), veri sözlüğü (D3). Sözleşme ve
 * kurallar: CLAUDE.md "Other services → SqlPlaygroundService".
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/sql")
@RequiredArgsConstructor
public class SqlPlaygroundController {

    /** Dışa aktarım: kişi başına dakikada (pod belleği — yük sınırı). */
    static final int EXPORT_PER_MINUTE = 6;
    /** Dışa aktarım: pod başına aynı anda (yük sınırı); dolu → kısa bekleme, sonra 429 EXPORT_BUSY. */
    static final int EXPORT_POD_CONCURRENCY = 2;
    static final long EXPORT_WAIT_MS = 5_000L;
    static final long EXPORT_BUSY_RETRY_SECONDS = 5L;
    private static final ZoneId TZ = ZoneId.of("Europe/Istanbul");

    private final SqlPlaygroundService service;
    private final AuditService auditService;
    private final PermissionService permissionService;

    /** Kayıtlı sorgular (D1) — isteğe bağlı bean (dilim testleri). */
    @Autowired(required = false)
    private SqlSavedQueryService savedQueries;

    private final Set<String> exportRunning = ConcurrentHashMap.newKeySet();
    private final Map<String, Deque<Long>> exportCalls = new HashMap<>();
    private final Semaphore exportSlots = new Semaphore(EXPORT_POD_CONCURRENCY);

    // ── Şema keşfi ────────────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/tables")
    public ResponseEntity<Map<String, Object>> tables(HttpSession session) {
        gate(session);
        return ok(Map.of("data", service.listTables()));
    }

    @GetMapping("/tables/{name}/columns")
    public ResponseEntity<Map<String, Object>> columns(
            @PathVariable String name, HttpSession session) {
        gate(session);
        return ok(Map.of("data", service.listColumns(name)));
    }

    /** Tablo şema detayları: kolon+tip-sınırı, constraint (data integrity), index, trigger. */
    @GetMapping("/tables/{name}/details")
    public ResponseEntity<Map<String, Object>> tableDetails(
            @PathVariable String name, HttpSession session) {
        gate(session);
        return ok(Map.of("data", service.tableDetails(name)));
    }

    /** Tablolar arası ilişki (hiyerarşi) grafiği — gerçek FK + *_id çıkarımı. */
    @GetMapping("/relationships")
    public ResponseEntity<Map<String, Object>> relationships(HttpSession session) {
        gate(session);
        return ok(Map.of("data", service.relationships()));
    }

    // ── Çalıştırma ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Sorgu çalıştırma. Gövde {@code {sql, params?, run_id?}} — eski {@code {sql}} gövdesi aynen çalışır. Hassas kolon
     * reddi 400 {@code SQL_SENSITIVE_COLUMN} (geçmişe ve denetime başarısız kayıt).
     */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(
            @RequestBody Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        gate(session);
        String sql = sqlOf(body);
        String actor = actor(session);
        SqlPlaygroundService.QueryRequest req = request(body, sql);
        Map<String, Object> result;
        try {
            result = service.execute(req, actor);
        } catch (SensitiveColumnException e) {
            auditService.recordAction("SQL_EXECUTE", session, request, "QUERY", fingerprint(sql), AuditDetail.of(
                    "ok", false, "reason", e.getReason(), "column", e.getColumn(), "code", e.getCode(),
                    "sql_len", sql == null ? 0 : sql.length(), "excerpt", SecretMask.maskUrlQuery(excerpt(sql))));
            throw e;
        }

        // resource_id NULL'du: bu satırlar "kaynak geçmişi" sorgusuna HİÇ düşmüyordu, yani
        // "bu sorgu kaç kez, kim tarafından koşturuldu" sorusu cevaplanamıyordu. Normalize
        // edilmiş sorgunun kararlı parmak izi kimlik olur.
        @SuppressWarnings("unchecked")
        List<String> masked = result.get("masked_columns") instanceof List<?> l ? (List<String>) l : List.of();
        String detail = AuditDetail.of(
            "ok", result.get("ok"),
            "rows", result.get("rowCount"),
            "ms", result.get("durationMs"),
            "sql_len", sql == null ? 0 : sql.length(),
            "masked_columns", masked.size(),
            "params", req.params().size(),
            "cancelled", result.get("cancelled"),
            "run_id", result.get("run_id"),
            // Alıntı, "bir admin sorgu koşturdu" ile "bir admin app_users'a sorgu koşturdu"
            // arasındaki farktır; gövde SecretMask'ten geçer.
            "excerpt", SecretMask.maskUrlQuery(excerpt(sql)));
        auditService.recordAction("SQL_EXECUTE", session, request, "QUERY", fingerprint(sql), detail);

        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("success", true);
        return ResponseEntity.ok(response);
    }

    /** A2: çalışan sorguyu durdurur — yalnız başlatan kullanıcı (başkasının kimliği "bulunamadı"). */
    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@RequestBody Map<String, Object> body,
                                                      HttpSession session, HttpServletRequest request) {
        gate(session);
        Object raw = body == null ? null : body.get("run_id");
        String runId = raw == null ? null : String.valueOf(raw);
        SqlRunRegistry.CancelResult r = service.cancel(runId, actor(session));
        auditService.recordAction("SQL_CANCEL", session, request, "QUERY", SqlRunRegistry.validRunId(runId) ? runId : null,
                AuditDetail.of("cancelled", r.cancelled(), "reason", r.reason()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cancelled", r.cancelled());
        if (r.reason() != null) out.put("reason", r.reason());
        return ok(out);
    }

    /** C1: {@code EXPLAIN (FORMAT JSON[, ANALYZE, BUFFERS])}; yalnız PostgreSQL (aksi 409 SQL_EXPLAIN_UNSUPPORTED). */
    @PostMapping("/explain")
    public ResponseEntity<Map<String, Object>> explain(@RequestBody Map<String, Object> body,
                                                       HttpSession session, HttpServletRequest request) {
        gate(session);
        String sql = sqlOf(body);
        boolean analyze = Boolean.TRUE.equals(body.get("analyze"));
        SqlPlaygroundService.QueryRequest req = request(body, sql);
        Map<String, Object> result;
        try {
            result = service.explain(req, analyze, actor(session));
        } catch (SensitiveColumnException e) {
            auditService.recordAction("SQL_EXPLAIN", session, request, "QUERY", fingerprint(sql), AuditDetail.of(
                    "ok", false, "analyze", analyze, "reason", e.getReason(), "column", e.getColumn(),
                    "sql_len", sql == null ? 0 : sql.length(), "excerpt", SecretMask.maskUrlQuery(excerpt(sql))));
            throw e;
        }
        auditService.recordAction("SQL_EXPLAIN", session, request, "QUERY", fingerprint(sql), AuditDetail.of(
                "ok", result.get("ok"), "analyze", analyze, "cancelled", result.get("cancelled"),
                "params", req.params().size(), "run_id", result.get("run_id"),
                "sql_len", sql == null ? 0 : sql.length(), "excerpt", SecretMask.maskUrlQuery(excerpt(sql))));
        return ok(result);
    }

    /**
     * C3: büyük dışa aktarım (CSV ≤ 50 000 / JSON ≤ 20 000 satır, 120 sn). Satırlar sürücü imleciyle okunur ve geçici bir
     * dosyaya akıtılır (bellekte tutulmaz); bitince başlıklar ({@code X-Export-Rows}, {@code X-Export-Truncated},
     * {@code X-Export-Max-Rows}, {@code X-Masked-Columns}) ve dosya gönderilir. Kişi başına aynı anda tek + dakikada
     * {@value #EXPORT_PER_MINUTE}; pod başına {@value #EXPORT_POD_CONCURRENCY}. Sorgu hatası 400 {@code SQL_EXPORT_FAILED},
     * durdurma 409 {@code SQL_CANCELLED}.
     */
    @PostMapping("/export")
    public ResponseEntity<?> export(@RequestBody Map<String, Object> body, HttpSession session,
                                    HttpServletRequest request, HttpServletResponse response) throws IOException {
        gate(session);
        String sql = sqlOf(body);
        Object f = body.get("format");
        String fmt = f == null || String.valueOf(f).isBlank() ? "csv" : String.valueOf(f).trim().toLowerCase(Locale.ROOT);
        if (!"csv".equals(fmt) && !"json".equals(fmt)) {
            throw new FieldValidationException("format", Msg.t("Biçim csv ya da json olmalı.", "The format must be csv or json."));
        }
        SqlPlaygroundService.QueryRequest req = request(body, sql);
        String actor = actor(session);
        String key = "u:" + actor;
        if (!exportRunning.add(key)) {
            return tooMany("EXPORT_BUSY", EXPORT_BUSY_RETRY_SECONDS, Msg.t(
                    "Önceki dışa aktarımınız hâlâ hazırlanıyor; kişi başına aynı anda tek dışa aktarım yapılabilir. Bitince yeniden deneyin; bu deneme dakikalık hakkınızdan düşülmedi.",
                    "Your previous export is still being prepared; one export per person can run at a time. Try again when it has finished; this attempt did not count against your per-minute limit."));
        }
        Path tmp = null;
        boolean slot = false;
        int rows = 0;
        boolean truncated = false, ok = false, cancelled = false;
        int maskedCount = 0;
        String runIdOut = null;
        String rejectReason = null, rejectColumn = null;
        boolean attempted = false;
        try {
            Long retry = reserveExport(key);
            if (retry != null) {
                return tooMany("RATE_LIMITED", retry, Msg.t(
                        "Dışa aktarım sınırına ulaşıldı: kişi başına dakikada en çok " + EXPORT_PER_MINUTE + " dışa aktarım. " + retry + " saniye sonra yeniden deneyin.",
                        "Export limit reached: at most " + EXPORT_PER_MINUTE + " exports per person per minute. Try again in " + retry + " seconds."));
            }
            try {
                slot = exportSlots.tryAcquire(EXPORT_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (!slot) {
                refundExport(key);
                return tooMany("EXPORT_BUSY", EXPORT_BUSY_RETRY_SECONDS, Msg.t(
                        "Şu anda başka dışa aktarımlar hazırlanıyor (aynı anda en çok " + EXPORT_POD_CONCURRENCY + "). Birkaç saniye sonra yeniden deneyin; bu deneme dakikalık hakkınızdan düşülmedi.",
                        "Other exports are being prepared right now (at most " + EXPORT_POD_CONCURRENCY + " at a time). Try again in a few seconds; this attempt did not count against your per-minute limit."));
            }
            int cap = "json".equals(fmt) ? SqlPlaygroundService.EXPORT_JSON_MAX_ROWS : SqlPlaygroundService.EXPORT_CSV_MAX_ROWS;
            attempted = true;
            tmp = Files.createTempFile("sqlpg-export-", "." + fmt);
            SqlPlaygroundService.RunOutcome o;
            boolean charLimit;
            try (OutputStream os = Files.newOutputStream(tmp); SqlExportWriter w = new SqlExportWriter(fmt, os)) {
                try {
                    o = service.export(req, cap, actor, w);
                } catch (SensitiveColumnException e) {
                    rejectReason = e.getReason();
                    rejectColumn = e.getColumn();
                    throw e;
                }
                charLimit = w.charLimitHit();
                runIdOut = o.runId();
                if (o.ok()) w.finish(o.rows(), o.truncated() || charLimit, cap, o.maskedColumns());
            }
            ok = o.ok();
            cancelled = o.cancelled();
            rows = o.rows();
            truncated = o.truncated() || charLimit;
            maskedCount = o.maskedColumns().size();
            if (!o.ok()) {
                if (o.cancelled()) {
                    throw new com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException("SQL_CANCELLED",
                            Msg.t("Dışa aktarım durduruldu; dosya oluşturulmadı.", "The export was stopped; no file was created."),
                            Map.of("cancelled", true, "run_id", o.runId()));
                }
                throw new com.sitemonitor.config.GlobalExceptionHandler.CodedBadRequestException("SQL_EXPORT_FAILED",
                        Msg.t("Sorgu çalıştırılamadı, dosya oluşturulmadı: ", "The query failed, no file was created: ") + o.error());
            }
            response.setStatus(200);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setContentType("json".equals(fmt) ? MediaType.APPLICATION_JSON_VALUE : "text/csv;charset=UTF-8");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename("sql-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(TZ).format(Instant.now()) + "." + fmt)
                    .build().toString());
            response.setHeader("X-Export-Rows", String.valueOf(rows));
            response.setHeader("X-Export-Truncated", String.valueOf(truncated));
            if (truncated) response.setHeader("X-Export-Truncated-Reason", charLimit ? "bytes" : "rows");
            response.setHeader("X-Export-Max-Rows", String.valueOf(cap));
            response.setHeader("X-Masked-Columns", headerList(o.maskedColumns()));
            response.setHeader("X-Run-Id", o.runId());
            response.setContentLengthLong(Files.size(tmp));
            Files.copy(tmp, response.getOutputStream());
            response.flushBuffer();
            return null;
        } finally {
            if (slot) exportSlots.release();
            exportRunning.remove(key);
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException e) { log.debug("Dışa aktarım geçici dosyası silinemedi: {}", e.toString()); }
            }
            if (attempted) try {
                auditService.recordAction("SQL_EXPORT", session, request, "QUERY", fingerprint(sql), AuditDetail.of(
                        "ok", ok, "format", fmt, "rows", rows, "truncated", truncated, "cancelled", cancelled,
                        "masked_columns", maskedCount, "params", req.params().size(), "run_id", runIdOut,
                        "reason", rejectReason, "column", rejectColumn,
                        "sql_len", sql == null ? 0 : sql.length(), "excerpt", SecretMask.maskUrlQuery(excerpt(sql))));
            } catch (Exception e) {
                log.debug("SQL_EXPORT denetimi yazılamadı: {}", e.toString());
            }
        }
    }

    // ── Geçmiş / örnekler / sözlük ────────────────────────────────────────────────────────────────────────────

    /**
     * Parametresiz: bugünkü yanıt (kendi son 50). Herhangi bir parametre verilirse sayfalı mod (A4):
     * {@code q, status, scope, from, to, page, size} → {@code {data, total, page, size, slow_ms}}.
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpSession session) {
        gate(session);
        String actor = actor(session);
        boolean paged = q != null || status != null || scope != null || from != null || to != null || page != null || size != null;
        if (!paged) return ok(Map.of("data", service.recentHistory(actor)));
        return ok(service.historyPage(actor, q, status, scope, from, to, page == null ? 0 : page, size == null ? 25 : size));
    }

    @GetMapping("/samples")
    public ResponseEntity<Map<String, Object>> samples(HttpSession session) {
        gate(session);
        return ok(Map.of("data", SqlSamples.list()));
    }

    /** D3: veri sözlüğü — ana tablolar ve önemli kolonlar (istek dilinde). */
    @GetMapping("/dictionary")
    public ResponseEntity<Map<String, Object>> dictionary(HttpSession session) {
        gate(session);
        return ok(SqlDataDictionary.render());
    }

    // ── Kayıtlı sorgular (D1) ─────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/saved")
    public ResponseEntity<Map<String, Object>> savedList(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            HttpSession session) {
        gate(session);
        return ok(saved().list(actor(session), scope, q, tag, page, size));
    }

    @GetMapping("/saved/{id}")
    public ResponseEntity<Map<String, Object>> savedGet(@PathVariable long id, HttpSession session) {
        gate(session);
        String actor = actor(session);
        return ok(Map.of("data", saved().row(saved().get(id, actor), actor)));
    }

    @PostMapping("/saved")
    public ResponseEntity<Map<String, Object>> savedCreate(@RequestBody Map<String, Object> body,
                                                           HttpSession session, HttpServletRequest request) {
        gate(session);
        String actor = actor(session);
        String display = (String) session.getAttribute("displayName");
        SqlSavedQueryService.Saved s = saved().create(body == null ? Map.of() : body, actor, display);
        auditService.recordAction("SQL_SAVED_QUERY_CREATE", session, request, "SQL_SAVED_QUERY",
                String.valueOf(s.entity().getId()), AuditDetail.of("title", s.entity().getTitle(),
                        "shared", s.entity().getShared(), "sql_len", s.entity().getSqlText().length()));
        return ok(Map.of("data", saved().row(s.entity(), actor)));
    }

    @PutMapping("/saved/{id}")
    public ResponseEntity<Map<String, Object>> savedUpdate(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                           HttpSession session, HttpServletRequest request) {
        gate(session);
        String actor = actor(session);
        SqlSavedQueryService.Saved s = saved().update(id, body == null ? Map.of() : body, actor);
        if (s.changed()) {
            auditService.recordAction("SQL_SAVED_QUERY_UPDATE", session, request, "SQL_SAVED_QUERY", String.valueOf(id),
                    AuditDetail.of("title", s.entity().getTitle(), "fields", s.fields(), "shared", s.entity().getShared()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", saved().row(s.entity(), actor));
        out.put("changed", s.changed());
        return ok(out);
    }

    @DeleteMapping("/saved/{id}")
    public ResponseEntity<Map<String, Object>> savedDelete(@PathVariable long id, HttpSession session,
                                                           HttpServletRequest request) {
        gate(session);
        var s = saved().delete(id, actor(session));
        auditService.recordAction("SQL_SAVED_QUERY_DELETE", session, request, "SQL_SAVED_QUERY", String.valueOf(id),
                AuditDetail.of("title", s.getTitle(), "shared", s.getShared()));
        return ok(Map.of("deleted", true));
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────────────────────────────────────

    private SqlSavedQueryService saved() {
        if (savedQueries == null) {
            throw new IllegalStateException(Msg.t("Kayıtlı sorgular bu ortamda kullanılamıyor.",
                    "Saved queries aren't available in this environment."));
        }
        return savedQueries;
    }

    private void gate(HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "sql_playground.execute", "execute");
    }

    private static String actor(HttpSession session) {
        String a = (String) session.getAttribute("username");
        return a != null ? a : "anonymous";
    }

    private static String sqlOf(Map<String, Object> body) {
        Object s = body == null ? null : body.get("sql");
        if (s != null && !(s instanceof String)) {
            throw new FieldValidationException("sql", Msg.t("SQL metin olmalı.", "The SQL must be text."));
        }
        return (String) s;
    }

    private static SqlPlaygroundService.QueryRequest request(Map<String, Object> body, String sql) {
        Object rid = body == null ? null : body.get("run_id");
        return new SqlPlaygroundService.QueryRequest(sql, SqlParams.parse(body == null ? null : body.get("params")),
                rid == null ? null : String.valueOf(rid));
    }

    /** Sorgunun kararlı kimliği: boşluk/satır sonu normalize edilir, SHA-256'nın ilk 16 hanesi. */
    private static String fingerprint(String sql) {
        return SqlPlaygroundService.fingerprint(sql);
    }

    /** Denetimde saklanan sorgu alıntısı — tam metin DEĞİL (gövde kişisel veri içerebilir). */
    private static String excerpt(String sql) {
        if (sql == null) return null;
        String one = sql.trim().replaceAll("\\s+", " ");
        return one.length() <= 200 ? one : one.substring(0, 200) + "…";
    }

    /** Başlık değeri: adlar virgülle; ASCII dışı / ayırıcı karakterler yüzde kodlu (UTF-8). */
    static String headerList(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) sb.append(',');
            byte[] b = (n == null ? "" : n).getBytes(StandardCharsets.UTF_8);
            for (byte x : b) {
                int c = x & 0xFF;
                boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                        || c == '_' || c == '-' || c == '.';
                if (safe) sb.append((char) c);
                else sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    /** Dakikalık hak — dolu ise kalan saniye, yoksa null (hak düşülür). */
    private Long reserveExport(String who) {
        long now = System.currentTimeMillis();
        synchronized (exportCalls) {
            if (exportCalls.size() > 1000) exportCalls.clear();
            Deque<Long> q = exportCalls.computeIfAbsent(who, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() >= 60_000L) q.pollFirst();
            if (q.size() >= EXPORT_PER_MINUTE) return Math.max(1L, (60_000L - (now - q.peekFirst()) + 999) / 1000);
            q.addLast(now);
            return null;
        }
    }

    /** Meşgul reddi: az önce düşülen hak geri verilir. */
    private void refundExport(String who) {
        synchronized (exportCalls) {
            Deque<Long> q = exportCalls.get(who);
            if (q != null && !q.isEmpty()) q.pollLast();
        }
    }

    private static ResponseEntity<Map<String, Object>> tooMany(String code, long retry, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("error", message);
        body.put("retry_after_seconds", retry);
        String rid = org.slf4j.MDC.get("requestId");
        if (rid != null) body.put("request_id", rid);
        return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(retry)).body(body);
    }

    private boolean isAdmin(HttpSession s) {
        return SessionScope.isGlobalAdmin(s);
    }

    private void requireAdmin(HttpSession s) {
        if (!isAdmin(s)) {
            throw new SecurityException("Admin access required");
        }
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> r = new LinkedHashMap<>(body);
        r.put("success", true);
        return ResponseEntity.ok(r);
    }
}
