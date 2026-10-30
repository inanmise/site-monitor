package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.AlarmFeedCaller;
import com.sitemonitor.model.AlarmFeedRequest;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlarmFeedCallerRepository;
import com.sitemonitor.repository.AlarmFeedRequestRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.alarmfeed.AlarmFeedRequestLog;
import com.sitemonitor.service.alarmfeed.AlarmFeedRows;
import com.sitemonitor.service.alarmfeed.AlarmFeedService;
import com.sitemonitor.service.alarmfeed.AlarmFeedSettings;
import com.sitemonitor.util.Csv;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ayarlar → Platform → Alarm akışı (2026-10-30). YALNIZ genel yönetici: akış kurum geneli alarmları dışarı açar, çağrı günlüğü
 * dış IP / istemci bilgisi taşır.
 *
 * <ul>
 *   <li>{@code GET /} — ayarlar, uç adresi, son 24 saat özeti, çağıranlar</li>
 *   <li>{@code PUT /settings} — yalnız gönderilen + DEĞİŞEN alanlar (denetim {@code ALARM_FEED_SETTINGS_SAVE})</li>
 *   <li>{@code GET /preview} — akışın ŞU AN döndüreceği satırlar (durum yazılmaz, günlüğe girmez; akış kapalıyken de)</li>
 *   <li>{@code GET /requests}, {@code GET /requests/{id}} (yanıtın birebir kopyasıyla), {@code GET /requests/export} (CSV)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/alarm-feed")
public class AlarmFeedAdminController {

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    static final int EXPORT_MAX = 10_000;
    static final List<String> OUTCOMES = List.of(AlarmFeedRequestLog.OUTCOME_OK, AlarmFeedRequestLog.OUTCOME_DISABLED,
            AlarmFeedRequestLog.OUTCOME_IP_DENIED, AlarmFeedRequestLog.OUTCOME_RATE_LIMITED, AlarmFeedRequestLog.OUTCOME_ERROR);

    private final AlarmFeedService feed;
    private final AlarmFeedRequestLog requestLog;
    private final AlarmFeedRequestRepository requests;
    private final AlarmFeedCallerRepository callers;
    private final TeamRepository teams;
    private final AppSettingsService settings;
    private final AuditService auditService;

    public AlarmFeedAdminController(AlarmFeedService feed, AlarmFeedRequestLog requestLog, AlarmFeedRequestRepository requests,
                                    AlarmFeedCallerRepository callers, TeamRepository teams, AppSettingsService settings,
                                    AuditService auditService) {
        this.feed = feed;
        this.requestLog = requestLog;
        this.requests = requests;
        this.callers = callers;
        this.teams = teams;
        this.settings = settings;
        this.auditService = auditService;
    }

    // ── Görünüm ─────────────────────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Map<String, Object>> view(HttpSession session) {
        requireGlobal(session);
        return ok(view());
    }

    Map<String, Object> view() {
        AlarmFeedSettings.Config c = feed.config();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endpoint", AlarmFeedController.PATH);
        out.put("base_url", feed.baseUrl());
        out.put("settings", AlarmFeedSettings.view(c));
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("resolved_minutes", List.of(AlarmFeedSettings.MINUTES_MIN, AlarmFeedSettings.MINUTES_MAX));
        limits.put("rate_per_minute", List.of(AlarmFeedSettings.RATE_MIN, AlarmFeedSettings.RATE_MAX));
        limits.put("max_rows", List.of(AlarmFeedSettings.ROWS_MIN, AlarmFeedSettings.ROWS_MAX));
        limits.put("text_max", AlarmFeedSettings.TEXT_MAX);
        limits.put("allowed_ips_max", AlarmFeedSettings.IPS_MAX);
        out.put("limits", limits);
        out.put("log_retention_days", settings.getInt(AlarmFeedSettings.KEY_LOG_RETENTION, 90));
        out.put("body_retention_days", settings.getInt(AlarmFeedSettings.KEY_BODY_RETENTION, 30));
        out.put("columns", AlarmFeedRows.COLUMNS);
        List<Map<String, Object>> teamRows = new ArrayList<>();
        try {
            List<Team> all = new ArrayList<>(teams.findAll());
            all.sort(Comparator.comparing(t -> t.getName() == null ? "" : t.getName().toLowerCase(Locale.forLanguageTag("tr"))));
            for (Team t : all) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.getId());
                m.put("name", t.getName());
                teamRows.add(m);
            }
            out.put("teams", teamRows);
        } catch (Exception e) {
            out.put("teams", null);
        }
        List<Map<String, Object>> types = new ArrayList<>();
        for (String t : MonitorTypeCatalog.ORDER) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", t);
            m.put("label", MonitorTypeCatalog.label(t));
            types.add(m);
        }
        out.put("types", types);
        out.put("status", status());
        List<Map<String, Object>> callerRows = null;
        try {
            callerRows = new ArrayList<>();
            for (AlarmFeedCaller a : callers.findTop50ByOrderByLastSeenAtDesc()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ip", a.getClientIp());
                m.put("first_seen_at", a.getFirstSeenAt());
                m.put("first_ok_at", a.getFirstOkAt());
                m.put("last_seen_at", a.getLastSeenAt());
                m.put("call_count", a.getCallCount());
                m.put("last_user_agent", a.getLastUserAgent());
                m.put("last_outcome", a.getLastOutcome());
                callerRows.add(m);
            }
        } catch (Exception e) {
            callerRows = null;
        }
        out.put("callers", callerRows);
        return out;
    }

    /** Son 24 saat özeti; okunamayan değer null (asla uydurma 0). */
    Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("served_by", SchedulerService.hostname());
        s.put("log_write_failures", requestLog.writeFailures());
        s.put("suppressed_refusals", requestLog.suppressedRefusals());
        try {
            AlarmFeedRequest last = requests.findTopByOrderByRequestedAtDescIdDesc();
            s.put("last_call_at", last == null ? null : trimMs(last.getRequestedAt()));
            s.put("last_call_ip", last == null ? null : last.getClientIp());
            s.put("last_outcome", last == null ? null : last.getOutcome());
            s.put("last_row_count", last == null ? null : last.getRowCount());
            String since = ISO.format(Instant.now().minusSeconds(24 * 3600));
            long calls = 0, ok = 0, refused = 0, errors = 0;
            double durSum = 0;
            long durN = 0;
            for (Object[] r : requests.outcomeStatsSince(since)) {
                String o = r[0] == null ? "" : r[0].toString();
                long n = r[1] instanceof Number x ? x.longValue() : 0L;
                calls += n;
                if (AlarmFeedRequestLog.OUTCOME_OK.equals(o)) ok += n;
                else if (AlarmFeedRequestLog.OUTCOME_ERROR.equals(o)) errors += n;
                else refused += n;
                if (r[2] instanceof Number avg) {
                    durSum += avg.doubleValue() * n;
                    durN += n;
                }
            }
            s.put("calls_24h", calls);
            s.put("ok_24h", ok);
            s.put("refused_24h", refused);
            s.put("errors_24h", errors);
            s.put("avg_duration_ms_24h", durN == 0 ? null : Math.round(durSum / durN));
            s.put("distinct_ips_24h", requests.distinctIpsSince(since));
        } catch (Exception e) {
            for (String k : List.of("last_call_at", "last_call_ip", "last_outcome", "last_row_count", "calls_24h", "ok_24h",
                    "refused_24h", "errors_24h", "avg_duration_ms_24h", "distinct_ips_24h")) {
                s.put(k, null);
            }
            s.put("error", e.getClass().getSimpleName());
        }
        return s;
    }

    // ── Ayarlar ─────────────────────────────────────────────────────────────────────────────────

    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> save(@RequestBody(required = false) Map<String, Object> body,
                                                    HttpSession session, HttpServletRequest request) {
        requireGlobal(session);
        Map<String, Object> in = body == null ? Map.of() : body;
        AlarmFeedSettings.Config cur = feed.config();
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        for (Map.Entry<String, String> f : AlarmFeedSettings.FIELDS.entrySet()) {
            if (!in.containsKey(f.getKey())) continue;
            String next = AlarmFeedSettings.normalize(f.getKey(), in.get(f.getKey()));
            String now = AlarmFeedSettings.stored(cur, f.getKey());
            if (next.equals(now)) continue;   // değişmeyen değer yazılmaz ve denetime girmez
            values.put(f.getValue(), next);
            before.put(f.getKey(), now);
            after.put(f.getKey(), next);
        }
        if (values.isEmpty()) return ok(view());
        requireDistinctSeverityTexts(cur, after);
        settings.save(Map.of("values", values), actor(session));
        feed.invalidate();
        auditService.recordAction("ALARM_FEED_SETTINGS_SAVE", session, request, "SETTINGS", "alarm-feed",
                AuditDetail.of("fields", new ArrayList<>(after.keySet())), AuditDiff.diff(before, after));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("data", view());
        out.put("message", Msg.t("Alarm akışı ayarları kaydedildi (yeniden başlatma gerekmez; diğer pod'lar 10 sn içinde alır).",
                "Alarm feed settings saved (no restart needed; other pods pick them up within 10 s)."));
        return ResponseEntity.ok(out);
    }

    /**
     * Seviye metinleri (ve Clear kipinde Clear metni) birbirinden FARKLI olmalı: merkezi sistem SEVERITY'yi olay anahtarına
     * katar; iki seviye aynı metne eşlenirse seviye değişiminde aynı anahtar hem açık hem çözüldü görünürdü.
     */
    static void requireDistinctSeverityTexts(AlarmFeedSettings.Config cur, Map<String, Object> changed) {
        List<String> fields = new ArrayList<>(List.of("severity_critical", "severity_high", "severity_warning"));
        String mode = String.valueOf(changed.getOrDefault("resolved_mode", cur.resolvedMode()));
        if ("clear".equals(mode)) fields.add("clear_text");
        Map<String, String> seen = new LinkedHashMap<>();
        for (String f : fields) {
            String v = String.valueOf(changed.containsKey(f) ? changed.get(f) : AlarmFeedSettings.stored(cur, f))
                    .trim().toLowerCase(Locale.ROOT);
            String other = seen.putIfAbsent(v, f);
            if (other != null) {
                String blame = changed.containsKey(f) ? f : other;
                throw new FieldValidationException(blame, Msg.t(
                        "Seviye metinleri (ve Clear metni) birbirinden farklı olmalı — merkezi sistem SEVERITY'yi olay "
                                + "anahtarına katar; aynı metin farklı olayları birleştirir.",
                        "Severity texts (and the Clear text) must differ from each other — the central system uses SEVERITY "
                                + "in its event key; the same text would merge different events."));
            }
        }
    }

    // ── Önizleme ────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(HttpSession session) {
        requireGlobal(session);
        AlarmFeedService.Feed f;
        try {
            f = feed.preview(feed.config());
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("code", "ALARM_FEED_UNAVAILABLE");
            err.put("error", Msg.t("Alarm verisi şu an okunamadı — önizleme kurulamadı. Birazdan yeniden deneyin.",
                    "Alarm data can't be read right now — the preview could not be built. Try again shortly."));
            return ResponseEntity.status(503).body(err);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", AlarmFeedRows.COLUMNS);
        out.put("rows", f.rows());
        out.put("count", f.rows().size());
        out.put("open", f.open());
        out.put("resolved", f.resolved());
        out.put("truncated", f.truncated());
        out.put("generated_at", f.generatedAt());
        return ok(out);
    }

    // ── Çağrı günlüğü ───────────────────────────────────────────────────────────────────────────

    @GetMapping("/requests")
    public ResponseEntity<Map<String, Object>> requests(@RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "25") int size,
                                                        @RequestParam(required = false) String outcome,
                                                        @RequestParam(required = false) String ip,
                                                        @RequestParam(required = false) String from,
                                                        @RequestParam(required = false) String to,
                                                        HttpSession session) {
        requireGlobal(session);
        Filter flt = filter(outcome, ip, from, to);
        int sz = Math.max(1, Math.min(100, size));
        int pg = Math.max(0, page);
        Page<AlarmFeedRequest> p = requests.search(flt.outcome, flt.ipLike, flt.from, flt.to, PageRequest.of(pg, sz));
        if (pg > 0 && p.getTotalPages() > 0 && pg >= p.getTotalPages()) {
            pg = p.getTotalPages() - 1;   // son sayfaya kıstır (silinen satırlar sonrası boş sayfa yok)
            p = requests.search(flt.outcome, flt.ipLike, flt.from, flt.to, PageRequest.of(pg, sz));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (AlarmFeedRequest r : p.getContent()) items.add(dto(r));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", p.getTotalElements());
        out.put("page", pg);
        out.put("size", sz);
        return ok(out);
    }

    @GetMapping("/requests/{id}")
    public ResponseEntity<Map<String, Object>> request(@PathVariable long id, HttpSession session) {
        requireGlobal(session);
        AlarmFeedRequest r = requests.findById(id).orElseThrow(() -> new java.util.NoSuchElementException(
                Msg.t("Çağrı kaydı bulunamadı (saklama süresi dolmuş olabilir).",
                        "The call record was not found (its retention period may have ended).")));
        Map<String, Object> m = dto(r);
        String body = null;
        String type = null;
        if (r.getResponseHash() != null) {
            body = requestLog.body(r.getResponseHash());
            type = body == null ? null : requestLog.contentType(r.getResponseHash());
        }
        m.put("body", body);
        m.put("body_available", body != null);
        m.put("body_content_type", type);
        return ok(m);
    }

    @GetMapping("/requests/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String outcome,
                                         @RequestParam(required = false) String ip,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         HttpSession session, HttpServletRequest request) {
        requireGlobal(session);
        Filter flt = filter(outcome, ip, from, to);
        StringBuilder b = new StringBuilder().append((char) 0xFEFF);   // Excel için UTF-8 BOM
        // Csv.row her satırı CRLF ile bitirir.
        b.append(Csv.row("requested_at_utc", "client_ip", "remote_addr", "forwarded_for", "user_agent", "method", "query",
                "status", "outcome", "row_count", "open_count", "resolved_count", "truncated", "duration_ms",
                "response_bytes", "response_hash", "format", "served_by", "request_id", "error"));
        int n = 0;
        Long beforeId = null;
        while (n < EXPORT_MAX) {
            // Anahtar sayfalama (id < son görülen): dakikada bir eklenen satırlar ofseti kaydırmaz.
            List<AlarmFeedRequest> page = requests.exportPage(flt.outcome, flt.ipLike, flt.from, flt.to, beforeId,
                    PageRequest.of(0, 500));
            for (AlarmFeedRequest r : page) {
                if (n >= EXPORT_MAX) break;
                b.append(Csv.row(r.getRequestedAt(), r.getClientIp(), r.getRemoteAddr(), r.getForwardedFor(),
                        r.getUserAgent(), r.getMethod(), r.getQuery(), r.getStatus(), r.getOutcome(), r.getRowCount(),
                        r.getOpenCount(), r.getResolvedCount(), r.getTruncated(), r.getDurationMs(), r.getResponseBytes(),
                        r.getResponseHash(), r.getFormat(), r.getServedBy(), r.getRequestId(), r.getError()));
                n++;
                beforeId = r.getId();
            }
            if (page.size() < 500) break;
        }
        auditService.recordAction("ALARM_FEED_LOG_EXPORT", session, request, "ALARM_FEED", "requests",
                AuditDetail.of("rows", n, "outcome", flt.outcome, "ip", ip, "from", from, "to", to));
        String name = "alarm-akisi-cagri-gunlugu-" + LocalDate.now(ISTANBUL) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(b.toString().getBytes(StandardCharsets.UTF_8));
    }

    record Filter(String outcome, String ipLike, String from, String to) {}

    static Filter filter(String outcome, String ip, String from, String to) {
        String o = outcome == null || outcome.isBlank() ? null : outcome.trim().toUpperCase(Locale.ROOT);
        if (o != null && !OUTCOMES.contains(o)) {
            throw new FieldValidationException("outcome", Msg.t("Bilinmeyen sonuç süzgeci.", "Unknown outcome filter."));
        }
        String ipLike = null;
        if (ip != null && !ip.isBlank()) {
            String t = ip.trim();
            if (t.length() > 64 || !t.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '.' || ch == ':')) {
                throw new FieldValidationException("ip", Msg.t("IP süzgeci yalnız adres karakterleri içerebilir.",
                        "The IP filter may contain address characters only."));
            }
            ipLike = t + "%";
        }
        return new Filter(o, ipLike, dayStart(from, "from", 0), dayStart(to, "to", 1));
    }

    /** {@code yyyy-MM-dd} (Türkiye günü) → UTC ISO gün başı (+{@code plusDays}); boş → null. */
    static String dayStart(String day, String field, int plusDays) {
        if (day == null || day.isBlank()) return null;
        try {
            LocalDate d = LocalDate.parse(day.trim()).plusDays(plusDays);
            return ISO.format(d.atStartOfDay(ISTANBUL).toInstant());
        } catch (Exception e) {
            throw new FieldValidationException(field, Msg.t("Tarih yyyy-AA-gg biçiminde olmalı.",
                    "The date must be in yyyy-MM-dd format."));
        }
    }

    static Map<String, Object> dto(AlarmFeedRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("requested_at", trimMs(r.getRequestedAt()));
        m.put("client_ip", r.getClientIp());
        m.put("remote_addr", r.getRemoteAddr());
        m.put("forwarded_for", r.getForwardedFor());
        m.put("user_agent", r.getUserAgent());
        m.put("method", r.getMethod());
        m.put("query", r.getQuery());
        m.put("status", r.getStatus());
        m.put("outcome", r.getOutcome());
        m.put("row_count", r.getRowCount());
        m.put("open_count", r.getOpenCount());
        m.put("resolved_count", r.getResolvedCount());
        m.put("truncated", r.getTruncated());
        m.put("duration_ms", r.getDurationMs());
        m.put("response_bytes", r.getResponseBytes());
        m.put("response_hash", r.getResponseHash());
        m.put("served_by", r.getServedBy());
        m.put("request_id", r.getRequestId());
        m.put("format", r.getFormat());
        m.put("error", r.getError());
        return m;
    }

    /** Ekran sözleşmesi saniye hassasiyetli UTC ISO bekler; milisaniye günlükte (ve CSV'de) kalır. */
    static String trimMs(String iso) {
        return iso != null && iso.length() > 19 ? iso.substring(0, 19) : iso;
    }

    // ── yardımcılar ─────────────────────────────────────────────────────────────────────────────

    private static void requireGlobal(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t("Alarm akışı ayarlarını yalnız global yöneticiler görebilir ve değiştirebilir.",
                    "Only global administrators can view and change the alarm feed settings."));
        }
    }

    private static String actor(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        return u == null ? "system" : String.valueOf(u);
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("data", data);
        return ResponseEntity.ok(out);
    }
}
