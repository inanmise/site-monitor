package com.sitemonitor.service.activity;

import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.util.Csv;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Aktivite TAM dışa aktarımı (2026-11-05, aktivite zenginleştirmesi E): {@code GET /api/activity/export?format=csv|json
 * &<liste süzgeçleri>}. Ekrandaki süzgeçle ({@link ActivityFilter}) AYNI satırlar — yalnız açık sayfa değil, tamamı:
 * CSV en çok {@link #CSV_MAX_ROWS}, JSON en çok {@link #JSON_MAX_ROWS} satır (ön yüz Excel'i JSON'dan kurar).
 *
 * <p><b>Okuma anahtar kümeli</b> ({@code activity_time DESC, id DESC}, {@link #CHUNK}'lık parçalar) ve <b>yazım akışla</b>:
 * hiçbir an dosyanın tamamı bellekte tutulmaz. Tavan aşılırsa bu, gövdeden ÖNCE başlıkta ({@code X-Export-Truncated: true})
 * ve JSON'da {@code truncated} ile söylenir (sınırlı sayım: en çok tavan + 1 satır sayılır).
 *
 * <p><b>Sınırlar</b> (teknik hata raporu dışa aktarımı emsali): kişi başına aynı anda TEK dışa aktarım (ikincisi beklemeden
 * 429 {@code EXPORT_BUSY}, dakikalık hakkı yemez), kişi başına dakikada {@link #EXPORT_PER_MINUTE} (429 {@code RATE_LIMITED}
 * + {@code Retry-After}), pod genelinde aynı anda {@link #EXPORT_CONCURRENCY} dışa aktarım (dolu → en çok
 * {@link #EXPORT_WAIT_MS} beklenir, sonra 429 {@code EXPORT_BUSY}). İzin dışa aktarım boyunca tutulur — okuma ve yazma iç
 * içe ilerler. Yanıtlar {@code no-store}. Her dışa aktarım {@code ACTIVITY_EXPORT} denetim satırı yazar (yalnız süzgeç +
 * satır sayısı; satır içeriği yok). CSV: UTF-8 BOM, CRLF, metin hücreleri {@link Csv#cell} (formül koruması).
 */
@Slf4j
@Service
public class ActivityExportService {

    /** Sütunlar (sıra KALICI — CSV başlığı ve JSON {@code columns}). Zaman İstanbul yerel saatidir. */
    public static final List<String> COLUMNS = List.of("time", "monitor_type", "monitor_name", "target", "team_name",
            "action", "result_status", "result_summary", "error_message", "failure_reason", "http_status", "response_ms",
            "actor", "request_id", "alert_event_id", "id");

    static final int CSV_MAX_ROWS = 50_000, JSON_MAX_ROWS = 20_000, CHUNK = 1_000;
    static final int EXPORT_PER_MINUTE = 6, EXPORT_CONCURRENCY = 3;
    static final long EXPORT_WAIT_MS = 5_000L, EXPORT_BUSY_RETRY_SECONDS = 10L;
    private static final long EXPORT_WINDOW_MS = 60_000L;
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ActivityFilter.TZ);

    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    private final ObjectMapper json;

    /** Kullanıcı → son dışa aktarım zamanları (ms); pod başına, sınırlı. */
    private final Map<String, Deque<Long>> calls = new HashMap<>();
    /** Şu an dışa aktarımı süren kullanıcılar (kişi başına tek). */
    final Set<String> running = ConcurrentHashMap.newKeySet();
    final Semaphore slots = new Semaphore(EXPORT_CONCURRENCY, true);
    long waitMs = EXPORT_WAIT_MS;
    /** Okuma parçası (testlerde küçültülür). */
    int chunk = CHUNK;
    LongSupplier clock = System::currentTimeMillis;

    public ActivityExportService(JdbcTemplate jdbc, AuditService auditService, ObjectMapper json) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.json = json;
    }

    /**
     * Dışa aktarımı yazar. Akış başlarsa {@code null} döner (gövde doğrudan yazıldı); sınırda / hatalı istekte hata gövdesi.
     * {@code format}: {@code csv} | {@code json} (boş = csv). {@code emptyScope}: kapsamsız oturum — yalnız başlık / boş liste.
     */
    public ResponseEntity<?> export(ActivityFilter f, String format, boolean emptyScope, HttpSession session,
                                    HttpServletRequest request, HttpServletResponse response) throws IOException {
        String fmt = format == null || format.isBlank() ? "csv" : format.trim().toLowerCase(Locale.ROOT);
        if (!"csv".equals(fmt) && !"json".equals(fmt)) {
            throw new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("format",
                    Msg.t("Biçim csv ya da json olmalı.", "The format must be csv or json."));
        }
        String key = rateKey(session);
        if (!running.add(key)) {
            return busy(Msg.t("Önceki dışa aktarımınız hâlâ hazırlanıyor ya da indiriliyor; kişi başına aynı anda tek dışa aktarım yapılabilir. O bitince yeniden deneyin; bu deneme dakikalık hakkınızdan düşülmedi.",
                    "Your previous export is still being prepared or downloaded; one export per person can run at a time. Try again when it has finished; this attempt did not count against your per-minute limit."));
        }
        try {
            Long retry = reserve(key);
            if (retry != null) {
                Map<String, Object> body = errorBody("RATE_LIMITED", Msg.t(
                        "Dışa aktarım sınırına ulaşıldı: kişi başına dakikada en çok " + EXPORT_PER_MINUTE + " dışa aktarım. " + retry + " saniye sonra yeniden deneyin.",
                        "Export limit reached: at most " + EXPORT_PER_MINUTE + " exports per person per minute. Try again in " + retry + " seconds."));
                body.put("retry_after_seconds", retry);
                return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(retry)).body(body);
            }
            if (!acquire()) {
                cancel(key);
                return busy(Msg.t("Şu anda başka dışa aktarımlar hazırlanıyor (aynı anda en çok " + EXPORT_CONCURRENCY + "). Birkaç saniye sonra yeniden deneyin; bu deneme dakikalık hakkınızdan düşülmedi.",
                        "Other exports are being prepared right now (at most " + EXPORT_CONCURRENCY + " at a time). Try again in a few seconds; this attempt did not count against your per-minute limit."));
            }
            int cap = "json".equals(fmt) ? JSON_MAX_ROWS : CSV_MAX_ROWS;
            int written = 0;
            boolean truncated = false, completed = false;
            try {
                truncated = !emptyScope && countCapped(f, cap) > cap;
                Map<Long, String> teams = emptyScope ? Map.of() : teamNames();
                response.setStatus(200);
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                response.setHeader("X-Export-Truncated", String.valueOf(truncated));
                response.setHeader("X-Export-Max-Rows", String.valueOf(cap));
                if ("csv".equals(fmt)) {
                    response.setContentType("text/csv;charset=UTF-8");
                    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(fileName(Instant.now())).build().toString());
                    Writer w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(response.getOutputStream(),
                            StandardCharsets.UTF_8), 16 * 1024);
                    w.write(0xFEFF);
                    w.write(Csv.row(COLUMNS.toArray()));
                    if (!emptyScope) written = stream(f, cap, teams, row -> w.write(csvLine(row)));
                    w.flush();
                } else {
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    final boolean trunc = truncated;
                    try (JsonGenerator gen = json.writer().without(StreamWriteFeature.AUTO_CLOSE_CONTENT)
                            .createGenerator(StreamUtils.nonClosing(response.getOutputStream()), JsonEncoding.UTF8)) {
                        gen.writeStartObject();
                        gen.writeBooleanProperty("success", true);
                        gen.writeName("data");
                        gen.writeStartObject();
                        gen.writePOJOProperty("columns", COLUMNS);
                        gen.writeStringProperty("time_zone", ActivityFilter.TZ.getId());
                        gen.writeName("rows");
                        gen.writeStartArray();
                        if (!emptyScope) written = stream(f, cap, teams, row -> writeJsonRow(gen, row));
                        gen.writeEndArray();
                        gen.writeNumberProperty("row_count", written);
                        gen.writeNumberProperty("max_rows", cap);
                        gen.writeBooleanProperty("truncated", trunc);
                        gen.writePOJOProperty("filter", filterEcho(f));
                        gen.writeEndObject();
                        gen.writeEndObject();
                    }
                }
                completed = true;
                return null;
            } finally {
                slots.release();
                try {
                    auditService.recordAction("ACTIVITY_EXPORT", session, request, "ACTIVITY", "export",
                            AuditDetail.of("format", fmt, "rows", written, "truncated", truncated, "completed", completed,
                                    "filter", filterEcho(f)));
                } catch (Exception e) {
                    log.debug("ACTIVITY_EXPORT denetimi yazılamadı: {}", e.toString());
                }
            }
        } finally {
            running.remove(key);
        }
    }

    // ── Okuma (anahtar kümeli) ──────────────────────────────────────────────────────────────────────────

    /** Bir dışa aktarım satırı — {@link #COLUMNS} sırasıyla. */
    record ExportRow(String time, String type, String name, String target, String teamName, String action,
                     String status, String summary, String error, String failureReason, Integer httpStatus,
                     Long responseMs, String actor, String requestId, Long alertEventId, long id, String rawTime) {}

    @FunctionalInterface
    interface RowSink { void accept(ExportRow row) throws IOException; }

    /** En çok {@code cap + 1} satırı sayar (tavan aşıldı mı) — tüm tabloyu saymaz. */
    long countCapped(ActivityFilter f, int cap) {
        ActivityFilter.Sql w = f.where();
        List<Object> a = new ArrayList<>(w.args());
        a.add(cap + 1);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT id FROM activity_log " + w.where()
                + " ORDER BY activity_time DESC, id DESC LIMIT ?) capped", Long.class, a.toArray());
        return n == null ? 0 : n;
    }

    /** Satırları en yeniden eskiye, {@link #CHUNK}'lık anahtar kümeli parçalarla akıtır; yazılan satır sayısını döner. */
    int stream(ActivityFilter f, int cap, Map<Long, String> teams, RowSink sink) throws IOException {
        int written = 0;
        String lastTime = null;
        long lastId = 0;
        while (written < cap) {
            ActivityFilter.Sql w = f.where();
            if (lastTime != null) w = w.and("(activity_time < ? OR (activity_time = ? AND id < ?))", lastTime, lastTime, lastId);
            int limit = Math.min(chunk, cap - written);
            List<Object> a = new ArrayList<>(w.args());
            a.add(limit);
            List<ExportRow> part = jdbc.query("SELECT * FROM activity_log " + w.where()
                    + " ORDER BY activity_time DESC, id DESC LIMIT ?", rs -> {
                List<ExportRow> out = new ArrayList<>(limit);
                Set<String> cols = columns(rs.getMetaData());
                while (rs.next()) out.add(read(rs, cols, teams));
                return out;
            }, a.toArray());
            if (part == null || part.isEmpty()) break;
            for (ExportRow r : part) {
                sink.accept(r);
                written++;
            }
            ExportRow last = part.get(part.size() - 1);
            lastTime = last.rawTime();
            lastId = last.id();
            if (part.size() < limit) break;
        }
        return written;
    }

    private static ExportRow read(ResultSet rs, Set<String> cols, Map<Long, String> teams) throws SQLException {
        String raw = rs.getString("activity_time");
        Long team = rs.getObject("team_id", Long.class);
        return new ExportRow(localTime(raw), rs.getString("monitor_type"), rs.getString("monitor_name"),
                rs.getString("target"), team == null ? null : teams.get(team), rs.getString("action"),
                rs.getString("result_status"), rs.getString("result_summary"), rs.getString("error_message"),
                cols.contains("failure_reason") ? rs.getString("failure_reason") : null,
                cols.contains("http_status") ? rs.getObject("http_status", Integer.class) : null,
                rs.getObject("response_ms", Long.class), rs.getString("actor"),
                cols.contains("request_id") ? rs.getString("request_id") : null,
                cols.contains("alert_event_id") ? rs.getObject("alert_event_id", Long.class) : null,
                rs.getLong("id"), raw);
    }

    /** Sonuç kolonları (küçük harf) — aktivite satırına sonradan eklenen kolonlar eski şemada yoksa boş yazılır. */
    private static Set<String> columns(ResultSetMetaData md) throws SQLException {
        Set<String> s = new HashSet<>();
        for (int i = 1; i <= md.getColumnCount(); i++) s.add(md.getColumnLabel(i).toLowerCase(Locale.ROOT));
        return s;
    }

    static String localTime(String rawUtc) {
        if (rawUtc == null) return null;
        try {
            return LOCAL.format(ActivityFilter.instantOf(rawUtc.length() > 19 ? rawUtc.substring(0, 19) : rawUtc));
        } catch (Exception e) {
            return rawUtc;
        }
    }

    private Map<Long, String> teamNames() {
        Map<Long, String> out = new HashMap<>();
        jdbc.query("SELECT id, name FROM teams", rs -> { out.put(rs.getLong(1), rs.getString(2)); });
        return out;
    }

    // ── Yazım ────────────────────────────────────────────────────────────────────────────────────────────

    static String csvLine(ExportRow r) {
        StringBuilder sb = new StringBuilder(256);
        Object[] v = values(r);
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            Object x = v[i];
            if (x == null) continue;
            if (x instanceof Number) sb.append(x);            // sunucunun ürettiği sayı — formül olamaz
            else sb.append(Csv.cell(x));
        }
        return sb.append("\r\n").toString();
    }

    private static void writeJsonRow(JsonGenerator gen, ExportRow r) {
        gen.writeStartArray();
        for (Object x : values(r)) {
            if (x == null) gen.writeNull();
            else if (x instanceof Long l) gen.writeNumber(l);
            else if (x instanceof Integer i) gen.writeNumber(i);
            else gen.writeString(String.valueOf(x));
        }
        gen.writeEndArray();
    }

    private static Object[] values(ExportRow r) {
        return new Object[]{r.time(), r.type(), r.name(), r.target(), r.teamName(), r.action(), r.status(), r.summary(),
                r.error(), r.failureReason(), r.httpStatus(), r.responseMs(), r.actor(), r.requestId(), r.alertEventId(), r.id()};
    }

    /** Süzgecin okunur özeti (denetim kaydı + JSON {@code filter}) — kapsam listesi yazılmaz. */
    static Map<String, Object> filterEcho(ActivityFilter f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("types", f.types());
        m.put("monitor", f.monitorType() == null && f.monitorId() == null ? null
                : (f.monitorType() == null ? "" : f.monitorType()) + ":" + (f.monitorId() == null ? "" : f.monitorId()));
        m.put("statuses", f.statuses());
        m.put("from", f.from());
        m.put("to", f.to());
        m.put("team_id", f.teamId());
        m.put("actor", f.actor());
        m.put("groups", f.groups());
        List<String> reasons = new ArrayList<>(f.reasons());
        for (String c : f.reasonClasses()) reasons.add(ActivityFilter.CLASS_PREFIX + c);
        m.put("reasons", reasons);
        m.put("q", f.q() == null ? null : f.q().substring(1, f.q().length() - 1));
        return m;
    }

    static String fileName(Instant at) {
        return "aktivite_" + LOCAL.format(at).substring(0, 10) + ".csv";
    }

    // ── Sınırlar ─────────────────────────────────────────────────────────────────────────────────────────

    /** Dakikalık hak: içindeyse kaydeder ve {@code null}; doluysa en eskinin düşmesine kalan saniye (≥ 1). */
    Long reserve(String key) {
        long now = clock.getAsLong();
        synchronized (calls) {
            if (calls.size() > 1_000) {
                calls.values().removeIf(q -> {
                    while (!q.isEmpty() && now - q.peekFirst() >= EXPORT_WINDOW_MS) q.pollFirst();
                    return q.isEmpty();
                });
                if (calls.size() > 10_000) calls.clear();
            }
            Deque<Long> q = calls.computeIfAbsent(key, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() >= EXPORT_WINDOW_MS) q.pollFirst();
            if (q.size() >= EXPORT_PER_MINUTE) {
                long ms = q.peekFirst() + EXPORT_WINDOW_MS - now;
                return Math.max(1L, (ms + 999) / 1000);
            }
            q.addLast(now);
            return null;
        }
    }

    /** Az önce ayrılan hakkı geri verir (pod dolu: deneme sayılmaz). */
    void cancel(String key) {
        synchronized (calls) {
            Deque<Long> q = calls.get(key);
            if (q != null && !q.isEmpty()) q.pollLast();
        }
    }

    private boolean acquire() {
        try {
            return slots.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static ResponseEntity<?> busy(String message) {
        Map<String, Object> body = errorBody("EXPORT_BUSY", message);
        body.put("retry_after_seconds", EXPORT_BUSY_RETRY_SECONDS);
        return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(EXPORT_BUSY_RETRY_SECONDS)).body(body);
    }

    static String rateKey(HttpSession session) {
        if (session == null) return "s:-";
        Object v = session.getAttribute("userId");
        if (v != null) return "u:" + v;
        Object u = session.getAttribute("username");
        return u != null ? "n:" + u : "s:" + session.getId();
    }

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("error", message);
        String rid = MDC.get("requestId");
        if (rid != null) body.put("request_id", rid);
        return body;
    }
}
