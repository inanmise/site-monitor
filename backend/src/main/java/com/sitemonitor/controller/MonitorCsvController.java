package com.sitemonitor.controller;

import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.monitorcsv.MonitorCsvLimiter;
import com.sitemonitor.service.monitorcsv.MonitorCsvParser;
import com.sitemonitor.service.monitorcsv.MonitorCsvService;
import com.sitemonitor.service.monitorcsv.MonitorCsvSpec;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * İzleme CSV dışa / içe aktarımı (2026-11-19, kullanıcı isteği: "UAT'tan prod'a izleme taşıma, toplu kurulum") — on izleme
 * türü, tür başına SABİT sütunlu bir CSV ({@link MonitorCsvSpec}).
 *
 * <ul>
 *   <li>{@code GET /api/monitoring/csv/{type}/export[?team=<id>&ids=1,2]} — {@code text/csv; charset=UTF-8} (BOM'lu), ek olarak
 *       indirilir, {@code no-store}. Yalnız oturumun GÖRDÜĞÜ izlemeler ({@code monitoring.read/view} + takım görünürlüğü). Sır
 *       YOK. Kişi başına dakikada {@value MonitorCsvLimiter#EXPORTS_PER_MINUTE} → 429 {@code RATE_LIMITED}. Denetim
 *       {@code MONITOR_CSV_EXPORT} (yalnız sayılar).</li>
 *   <li>{@code POST /api/monitoring/csv/{type}/import?dry_run=true|false} — gövde ham CSV ({@code text/csv}, ≤ 5 MB; aşan
 *       413 {@code PAYLOAD_TOO_LARGE}). {@code monitoring.crud/edit} (k6 için ayrıca {@code monitoring.scripted/edit}). Kişi
 *       başına aynı anda tek içe aktarım → 429 {@code RATE_LIMITED}. Önizleme hiçbir şey yazmaz (denetim de yazmaz); gerçek
 *       koşum satır sonuçlarıyla döner, denetim {@code MONITOR_CSV_IMPORT} (yalnız sayılar — hücre değeri ASLA).</li>
 * </ul>
 * Gövdeler hiçbir günlüğe yazılmaz ({@code RequestLoggingFilter.BODY_NEVER_LOGGED}: betik / SQL / CSV).
 */
@RestController
@RequestMapping("/api/monitoring/csv")
public class MonitorCsvController {

    /** İçe aktarım gövdesinin üst sınırı (bayt). */
    public static final int MAX_BODY_BYTES = 5 * 1024 * 1024;

    private final MonitorCsvService service;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final MonitorCsvLimiter limiter;

    @Autowired
    public MonitorCsvController(MonitorCsvService service, PermissionService permissionService, AuditService auditService) {
        this(service, permissionService, auditService, new MonitorCsvLimiter());
    }

    MonitorCsvController(MonitorCsvService service, PermissionService permissionService, AuditService auditService,
                         MonitorCsvLimiter limiter) {
        this.service = service;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.limiter = limiter;
    }

    @GetMapping("/{type}/export")
    public ResponseEntity<?> export(@PathVariable String type, @RequestParam(value = "team", required = false) Long team,
                                    @RequestParam(value = "ids", required = false) String ids, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        if (MonitorCsvSpec.of(type) == null) return unknownType();
        Set<Long> idFilter;
        try {
            if (ids != null && ids.length() > 120_000) throw new IllegalArgumentException("ids");
            idFilter = MonitorCsvService.parseIds(ids);
        } catch (IllegalArgumentException e) {
            return validation("ids", Msg.t("Kimlik listesi geçersiz — virgülle ayrılmış sayılar olmalı.",
                    "The id list is invalid — it must be comma-separated numbers."), null);
        }
        long wait = limiter.tryExport(user(session));
        if (wait > 0) return rateLimited(wait, Msg.t("Dakikada en çok " + MonitorCsvLimiter.EXPORTS_PER_MINUTE
                        + " dışa aktarım yapılabilir. " + wait + " sn sonra yeniden deneyin.",
                "At most " + MonitorCsvLimiter.EXPORTS_PER_MINUTE + " exports per minute are allowed. Try again in " + wait + " s."));
        MonitorCsvService.Export out = service.export(type, session, team, idFilter);
        auditService.recordAction("MONITOR_CSV_EXPORT", session, "MONITOR_CSV", type, AuditDetail.of(
                "type", type, "rows", out.rows(), "truncated", out.truncated(), "team", team), null);
        byte[] bytes = out.csv().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + out.fileName() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Export-Rows", String.valueOf(out.rows()))
                .header("X-Export-Truncated", String.valueOf(out.truncated()))
                .header("X-Export-Max-Rows", String.valueOf(MonitorCsvService.EXPORT_MAX_ROWS))
                .body(bytes);
    }

    @PostMapping("/{type}/import")
    public ResponseEntity<Map<String, Object>> importCsv(@PathVariable String type,
                                                         @RequestParam(value = "dry_run", defaultValue = "true") boolean dryRun,
                                                         HttpServletRequest request, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        MonitorCsvSpec.TypeSpec spec = MonitorCsvSpec.of(type);
        if (spec == null) return unknownType();
        if (!"monitoring.crud".equals(spec.permission())) permissionService.require(session, spec.permission(), "edit");
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) return tooLarge();
        String user = user(session);
        if (!limiter.tryStartImport(user)) {
            return rateLimited(5, Msg.t("Bir içe aktarma zaten sürüyor. Bitmesini bekleyip yeniden deneyin.",
                    "An import is already running. Wait for it to finish and try again."));
        }
        try {
            byte[] raw;
            try {
                raw = readBounded(request);
            } catch (IOException e) {
                return validation("csv", Msg.t("Dosya okunamadı — bağlantı kesilmiş olabilir. Yeniden deneyin.",
                        "The file couldn't be read — the connection may have dropped. Try again."), null);
            }
            if (raw == null) return tooLarge();
            String text = new String(raw, StandardCharsets.UTF_8);
            MonitorCsvService.ImportResult r;
            try {
                r = service.importCsv(type, text, dryRun, session);
            } catch (MonitorCsvService.CsvRequestException e) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("reason", e.reason());
                if (e.column() != null) extra.put("column", e.column());
                if (e.line() > 0) extra.put("line", e.line());
                return validation("csv", e.getMessage(), extra);
            }
            if (!dryRun) {
                auditService.recordAction("MONITOR_CSV_IMPORT", session, "MONITOR_CSV", type, AuditDetail.of(
                        "type", type, "rows", r.rows().size(), "created", count(r, MonitorCsvService.A_CREATE, MonitorCsvService.S_DONE),
                        "updated", count(r, MonitorCsvService.A_UPDATE, MonitorCsvService.S_DONE),
                        "failed", r.counts().getOrDefault(MonitorCsvService.S_FAILED, 0),
                        "skipped", r.counts().getOrDefault(MonitorCsvService.S_SKIPPED, 0),
                        "budget_exceeded", r.budgetExceeded()), null);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", r.type());
            data.put("dry_run", r.dryRun());
            data.put("columns", r.columns());
            data.put("counts", r.counts());
            data.put("budget_exceeded", r.budgetExceeded());
            List<Map<String, Object>> rows = new ArrayList<>(r.rows().size());
            for (MonitorCsvService.RowResult row : r.rows()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("row", row.row());
                m.put("name", row.name());
                m.put("team", row.team());
                m.put("action", row.action());
                m.put("changed", row.changed());
                m.put("secrets", row.secrets());
                m.put("status", row.status());
                m.put("message", row.message());
                m.put("field", row.field());
                rows.add(m);
            }
            data.put("rows", rows);
            data.put("limits", Map.of("max_rows", MonitorCsvParser.MAX_ROWS, "max_bytes", MAX_BODY_BYTES));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("data", data);
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
        } finally {
            limiter.endImport(user);
        }
    }

    private static int count(MonitorCsvService.ImportResult r, String action, String status) {
        int n = 0;
        for (MonitorCsvService.RowResult row : r.rows()) if (action.equals(row.action()) && status.equals(row.status())) n++;
        return n;
    }

    /** Gövdeyi en çok {@link #MAX_BODY_BYTES} baytla okur; aşarsa null (çağıran 413 döner). */
    static byte[] readBounded(HttpServletRequest request) throws IOException {
        InputStream in = request.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[64 * 1024];
        int total = 0;
        while (true) {
            int n = in.read(buf);
            if (n < 0) break;
            total += n;
            if (total > MAX_BODY_BYTES) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String user(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        return u == null ? (session == null ? "-" : session.getId()) : u.toString();
    }

    private static ResponseEntity<Map<String, Object>> unknownType() {
        return validation("type", Msg.t("Bilinmeyen izleme türü. Desteklenen türler: " + String.join(", ", MonitorCsvSpec.TYPES) + ".",
                "Unknown monitor type. Supported types: " + String.join(", ", MonitorCsvSpec.TYPES) + "."), null);
    }

    private static ResponseEntity<Map<String, Object>> validation(String field, String message, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "VALIDATION_FAILED");
        body.put("error", message);
        body.put("field", field);
        body.put("fields", Map.of(field, message));
        if (extra != null) body.putAll(extra);
        return ResponseEntity.badRequest().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    private static ResponseEntity<Map<String, Object>> tooLarge() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "PAYLOAD_TOO_LARGE");
        body.put("error", Msg.t("Dosya çok büyük (en çok 5 MB). Dosyayı bölüp parça parça içe aktarın.",
                "The file is too large (at most 5 MB). Split it and import the parts one by one."));
        body.put("max_mb", 5);
        return ResponseEntity.status(413).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    private static ResponseEntity<Map<String, Object>> rateLimited(long retryAfter, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "RATE_LIMITED");
        body.put("error", message);
        body.put("retry_after_seconds", retryAfter);
        return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
