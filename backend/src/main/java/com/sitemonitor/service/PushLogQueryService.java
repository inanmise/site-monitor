package com.sitemonitor.service;

import com.sitemonitor.model.Team;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Webhook Push Gönderim Logu (2026-09-19, kullanıcı isteği: SMTP sayfasının push karşılığı) — user_push_deliveries
 * üstünde sunucu taraflı arama / süzgeç / sıralama / sayfalama + özet (KPI, zaman çizelgesi, takım, alıcı, izleme,
 * seviye, hata sınıfı, tetikleyici) + satır detayı + yeniden kuyruğa alma.
 *
 * <p>Pencere modeli {@link SmtpLogQueryService} ile aynı: gövdesiz projeksiyon ({@code message}/{@code raw_response}
 * hariç) bir kez okunur, 60 sn önbellek ({@code push-log-window}); kapsam satır bazında ({@link Scope}). Push satırı
 * alan adı taşımaz; kapsam takım ID'si + kullanıcının KENDİ satırları (username) ile çözülür.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PushLogQueryService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final int MAX_WINDOW_DAYS = 365, MAX_PAGE_SIZE = 200, TOP_N = 10;
    static final long HOURLY_MAX_HOURS = 72;

    private final UserPushDeliveryRepository deliveryRepo;
    private final TeamRepository teamRepo;
    private final UserPushService userPushService;
    private final @Nullable CacheManager cacheManager;

    /** Global görücü her şeyi; kapsamlı kullanıcı görüş takımlarının satırlarını + kendi satırlarını. */
    public record Scope(boolean global, Set<Long> teamIds, String username) {
        public static Scope all() { return new Scope(true, Set.of(), null); }
        boolean allows(Long teamId, String user) {
            if (global) return true;
            if (teamId != null && teamIds.contains(teamId)) return true;
            return username != null && user != null && username.equalsIgnoreCase(user);
        }
    }

    public record Filter(String from, String to, String status, String trigger, Long teamId, String username,
                         String monitorType, String level, String errorClass, String q, String sort) { }

    /** Gövdesiz, sınıflandırılmış satır. */
    public record Row(long id, Long alertEventId, String trigger, String monitorType, Long monitorId, String monitorName,
                      Long teamId, String teamName, String alertLevel, String username, String displayName, String title,
                      String status, Integer httpStatus, String error, Integer attempts, String createdAt, String sentAt,
                      String batchId, String notificationId, String kind, String errorClass) {
        /** Zaman: gönderildiyse sentAt, yoksa createdAt (kuyruk/başarısız). */
        String at() { return sentAt != null ? sentAt : createdAt; }
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("alert_event_id", alertEventId); m.put("trigger", trigger);
            m.put("monitor_type", monitorType); m.put("monitor_id", monitorId); m.put("monitor_name", monitorName);
            m.put("team_id", teamId); m.put("team_name", teamName); m.put("alert_level", alertLevel);
            m.put("username", username); m.put("display_name", displayName); m.put("title", title);
            m.put("status", status); m.put("http_status", httpStatus); m.put("error", error); m.put("attempts", attempts);
            m.put("created_at", createdAt); m.put("sent_at", sentAt); m.put("at", at());
            m.put("batch_id", batchId); m.put("notification_id", notificationId);
            m.put("kind", kind); m.put("error_class", errorClass);
            return m;
        }
    }

    // ── Sınıflandırma ─────────────────────────────────────────────────────────────────────────

    /** SENT / FAILED / PENDING / BLOCKED (devre açık, hız tavanı) / SKIPPED (opt-out vb.) / UNKNOWN. */
    static String kindOf(String status) {
        if (status == null || status.isBlank()) return "UNKNOWN";
        String s = status.toUpperCase(Locale.ROOT);
        return switch (s) {
            case "SENT" -> "SENT";
            case "FAILED" -> "FAILED";
            case "PENDING" -> "PENDING";
            case "CIRCUIT_OPEN", "RATE_LIMITED" -> "BLOCKED";
            default -> s.startsWith("SKIPPED") ? "SKIPPED" : "UNKNOWN";
        };
    }

    /** Hata sınıfı (yalnız FAILED): HTTP koduna göre AUTH(401/403) · NOT_FOUND(404) · RATE(429) · SERVER(5xx) · CLIENT(4xx);
     *  kodsuz: CONFIG (URL ayarlanmamış / gövde kurulamadı) · TIMEOUT · CONNECT · OTHER. */
    static String classifyError(String status, Integer httpStatus, String error) {
        if (!"FAILED".equals(kindOf(status))) return null;
        if (httpStatus != null) {
            int c = httpStatus;
            if (c == 401 || c == 403) return "AUTH";
            if (c == 404) return "NOT_FOUND";
            if (c == 429) return "RATE";
            if (c >= 500) return "SERVER";
            if (c >= 400) return "CLIENT";
        }
        String s = error == null ? "" : error.toLowerCase(Locale.ROOT);
        if (s.contains("ayarlanmamış") || s.contains("kurulamadı") || s.contains("not configured")) return "CONFIG";   // URL/gövde yapılandırması
        if (s.contains("timed out") || s.contains("timeout")) return "TIMEOUT";
        if (s.contains("connect") || s.contains("refused") || s.contains("unknown host") || s.contains("ssl") || s.contains("tls")
                || s.contains("handshake") || s.contains("unreachable") || s.contains("socket") || s.contains("network")) return "CONNECT";
        return "OTHER";
    }

    // ── Pencere ───────────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<Row> window(String from, String to) {
        String key = from + "|" + (to == null ? "" : to);
        Cache cache = cacheManager == null ? null : cacheManager.getCache("push-log-window");
        if (cache == null) return loadWindow(from, to);
        List<Row> cached = cache.get(key, () -> loadWindow(from, to));
        return cached == null ? List.of() : cached;
    }

    private List<Row> loadWindow(String from, String to) {
        String upper = to == null || to.isBlank() ? "9999-12-31T23:59:59" : to;
        return enrich(deliveryRepo.findWindowRows(from, upper));
    }

    /** Sıra (findWindowRows): id, alertEventId, trigger, monitorType, monitorId, monitorName, teamId, alertLevel, username,
     *  displayName, title, status, httpStatus, error, attempts, createdAt, sentAt, batchId, notificationId. */
    private List<Row> enrich(List<Object[]> raw) {
        Map<Long, String> teamNames = new HashMap<>();
        try { for (Team t : teamRepo.findAll()) teamNames.put(t.getId(), t.getName()); } catch (Exception ignored) { }
        List<Row> out = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            Long teamId = r[6] == null ? null : ((Number) r[6]).longValue();
            String status = (String) r[11];
            Integer http = r[12] == null ? null : ((Number) r[12]).intValue();
            out.add(new Row(((Number) r[0]).longValue(), r[1] == null ? null : ((Number) r[1]).longValue(), (String) r[2], (String) r[3],
                    r[4] == null ? null : ((Number) r[4]).longValue(), (String) r[5], teamId, teamId == null ? null : teamNames.get(teamId),
                    (String) r[7], (String) r[8], (String) r[9], (String) r[10], status, http, (String) r[13],
                    r[14] == null ? null : ((Number) r[14]).intValue(), (String) r[15], (String) r[16], (String) r[17], (String) r[18],
                    kindOf(status), classifyError(status, http, (String) r[13])));
        }
        return out;
    }

    static Object[] rawOf(UserPushDelivery d) {
        return new Object[]{d.getId(), d.getAlertEventId(), d.getTrigger(), d.getMonitorType(), d.getMonitorId(), d.getMonitorName(),
                d.getTeamId(), d.getAlertLevel(), d.getUsername(), d.getDisplayName(), d.getTitle(), d.getStatus(), d.getHttpStatus(),
                d.getError(), d.getAttempts(), d.getCreatedAt(), d.getSentAt(), d.getBatchId(), d.getNotificationId()};
    }

    // ── Süzgeç / sıralama ─────────────────────────────────────────────────────────────────────

    private static Predicate<Row> predicate(Filter f, Scope scope) {
        String status = blankToNull(f.status()), trigger = blankToNull(f.trigger()), cls = blankToNull(f.errorClass());
        String mtype = blankToNull(f.monitorType()), level = blankToNull(f.level());
        String user = lower(f.username()), q = lower(f.q());
        return r -> {
            if (!scope.allows(r.teamId(), r.username())) return false;
            if (status != null && !status.equalsIgnoreCase(r.kind())) return false;
            if (trigger != null && !trigger.equalsIgnoreCase(r.trigger())) return false;
            if (f.teamId() != null && !f.teamId().equals(r.teamId())) return false;
            if (mtype != null && !mtype.equalsIgnoreCase(r.monitorType())) return false;
            if (level != null && !level.equalsIgnoreCase(r.alertLevel())) return false;
            if (cls != null && !cls.equalsIgnoreCase(r.errorClass())) return false;
            if (user != null && !contains(r.username(), user) && !contains(r.displayName(), user)) return false;
            if (q != null && !(contains(r.username(), q) || contains(r.displayName(), q) || contains(r.monitorName(), q)
                    || contains(r.title(), q) || contains(r.error(), q) || contains(r.teamName(), q) || contains(r.notificationId(), q)
                    || contains(r.batchId(), q))) return false;
            return true;
        };
    }

    private static Comparator<Row> comparator(String sort) {
        String s = sort == null ? "" : sort.trim();
        boolean asc = s.endsWith(",asc");
        String field = s.contains(",") ? s.substring(0, s.indexOf(',')) : s;
        Comparator<Row> c = switch (field) {
            case "user" -> Comparator.comparing((Row r) -> nz(r.displayName() != null ? r.displayName() : r.username()), String.CASE_INSENSITIVE_ORDER);
            case "monitor" -> Comparator.comparing((Row r) -> nz(r.monitorName()), String.CASE_INSENSITIVE_ORDER);
            case "status" -> Comparator.comparing((Row r) -> nz(r.kind()));
            case "trigger" -> Comparator.comparing((Row r) -> nz(r.trigger()));
            case "team" -> Comparator.comparing((Row r) -> nz(r.teamName()), String.CASE_INSENSITIVE_ORDER);
            case "level" -> Comparator.comparing((Row r) -> nz(r.alertLevel()));
            case "attempts" -> Comparator.comparingInt((Row r) -> r.attempts() == null ? 0 : r.attempts());
            default -> Comparator.comparing((Row r) -> nz(r.at())).thenComparingLong(Row::id);
        };
        boolean timeField = field.isEmpty() || field.equals("at");
        boolean desc = timeField ? !asc : s.endsWith(",desc");
        if (desc) c = c.reversed();
        return timeField ? c : c.thenComparing(Comparator.comparing((Row r) -> nz(r.at())).reversed());
    }

    static String[] bounds(Filter f, Instant now) {
        String to = blankToNull(f.to());
        String from = blankToNull(f.from());
        Instant floor = now.minus(Duration.ofDays(MAX_WINDOW_DAYS));
        if (from == null) from = ISO.format(now.minus(Duration.ofDays(7)));
        if (from.compareTo(ISO.format(floor)) < 0) from = ISO.format(floor);
        return new String[]{from, to};
    }

    // ── Uçlar ─────────────────────────────────────────────────────────────────────────────────

    public Map<String, Object> search(Filter f, Scope scope, int page, int size, Instant now) {
        String[] b = bounds(f, now);
        List<Row> rows = window(b[0], b[1]).stream().filter(predicate(f, scope)).sorted(comparator(f.sort())).toList();
        int sz = Math.max(1, Math.min(size, MAX_PAGE_SIZE)), pg = Math.max(0, page);
        int fromIdx = Math.min(pg * sz, rows.size()), toIdx = Math.min(fromIdx + sz, rows.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size()); out.put("page", pg); out.put("size", sz); out.put("from", b[0]); out.put("to", b[1]);
        out.put("items", rows.subList(fromIdx, toIdx).stream().map(Row::toMap).toList());
        return out;
    }

    public List<Map<String, Object>> exportRows(Filter f, Scope scope, int cap, Instant now) {
        String[] b = bounds(f, now);
        return window(b[0], b[1]).stream().filter(predicate(f, scope)).sorted(comparator(f.sort())).limit(cap).map(Row::toMap).toList();
    }

    public Map<String, Object> summary(Filter f, Scope scope, Instant now) {
        String[] b = bounds(f, now);
        List<Row> rows = window(b[0], b[1]).stream().filter(predicate(f, scope)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", b[0]); out.put("to", b[1]);

        int sent = 0, failed = 0, pending = 0, blocked = 0, skipped = 0;
        String lastSent = null, lastFailed = null;
        Set<String> failedUsers = new HashSet<>();
        for (Row r : rows) {
            switch (r.kind()) {
                case "SENT" -> { sent++; if (lastSent == null || r.at().compareTo(lastSent) > 0) lastSent = r.at(); }
                case "FAILED" -> { failed++; if (lastFailed == null || r.at().compareTo(lastFailed) > 0) lastFailed = r.at(); if (r.username() != null) failedUsers.add(r.username().toLowerCase(Locale.ROOT)); }
                case "PENDING" -> pending++;
                case "BLOCKED" -> blocked++;
                case "SKIPPED" -> skipped++;
                default -> { }
            }
        }
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("total", rows.size()); kpi.put("sent", sent); kpi.put("failed", failed); kpi.put("pending", pending);
        kpi.put("blocked", blocked); kpi.put("skipped", skipped);
        kpi.put("success_rate", sent + failed == 0 ? null : Math.round(sent * 1000.0 / (sent + failed)) / 10.0);
        kpi.put("last_sent_at", lastSent); kpi.put("last_failed_at", lastFailed); kpi.put("failed_users", failedUsers.size());
        out.put("kpi", kpi);

        Instant fromAt = SmtpLogQueryService.parse(b[0]), toAt = b[1] == null ? now : SmtpLogQueryService.parse(b[1]);
        boolean hourly = fromAt != null && toAt != null && Duration.between(fromAt, toAt).toHours() <= HOURLY_MAX_HOURS;
        out.put("granularity", hourly ? "hour" : "day");
        TreeMap<String, int[]> buckets = new TreeMap<>();
        if (fromAt != null && toAt != null && !toAt.isBefore(fromAt)) {
            Instant cur = truncate(fromAt, hourly);
            int guard = 0;
            while (!cur.isAfter(toAt) && guard++ < 400) { buckets.put(bucketKey(cur, hourly), new int[4]); cur = cur.plus(hourly ? Duration.ofHours(1) : Duration.ofDays(1)); }
        }
        for (Row r : rows) {
            Instant at = SmtpLogQueryService.parse(r.at());
            if (at == null) continue;
            int[] c = buckets.computeIfAbsent(bucketKey(truncate(at, hourly), hourly), k -> new int[4]);
            switch (r.kind()) { case "SENT" -> c[0]++; case "FAILED" -> c[1]++; case "PENDING", "BLOCKED" -> c[2]++; case "SKIPPED" -> c[3]++; default -> { } }
        }
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (var e : buckets.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bucket", e.getKey()); m.put("sent", e.getValue()[0]); m.put("failed", e.getValue()[1]); m.put("pending", e.getValue()[2]); m.put("skipped", e.getValue()[3]);
            timeline.add(m);
        }
        out.put("timeline", timeline);

        out.put("teams", breakdown(rows, r -> r.teamId() == null ? "-" : String.valueOf(r.teamId()),
                r -> new Object[]{"team_id", r.teamId(), "team_name", r.teamName()}, Integer.MAX_VALUE));
        out.put("top_users", breakdown(rows, r -> r.username() == null ? null : r.username().toLowerCase(Locale.ROOT),
                r -> new Object[]{"username", r.username(), "display_name", r.displayName()}, TOP_N));
        out.put("top_monitors", breakdown(rows, r -> r.monitorName() == null ? null : r.monitorType() + ":" + r.monitorName(),
                r -> new Object[]{"monitor_type", r.monitorType(), "monitor_id", r.monitorId(), "monitor_name", r.monitorName()}, TOP_N));
        out.put("levels", breakdown(rows, r -> r.alertLevel() == null ? "-" : r.alertLevel(), r -> new Object[]{"level", r.alertLevel()}, Integer.MAX_VALUE));
        Map<String, Integer> classes = new LinkedHashMap<>();
        for (Row r : rows) if (r.errorClass() != null) classes.merge(r.errorClass(), 1, Integer::sum);
        List<Map<String, Object>> errorClasses = new ArrayList<>();
        classes.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).forEach(e -> {
            Map<String, Object> m = new LinkedHashMap<>(); m.put("error_class", e.getKey()); m.put("count", e.getValue()); errorClasses.add(m);
        });
        out.put("error_classes", errorClasses);
        Map<String, Integer> triggers = new LinkedHashMap<>();
        for (Row r : rows) triggers.merge(r.trigger() == null ? "-" : r.trigger(), 1, Integer::sum);
        List<Map<String, Object>> triggerList = new ArrayList<>();
        triggers.forEach((k, v) -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("trigger", k); m.put("count", v); triggerList.add(m); });
        out.put("triggers", triggerList);
        return out;
    }

    /** anahtar → {total, sent, failed, pending+blocked, last_at, last_failed_at, success_rate}; başarısız üstte, sonra toplam. */
    private static List<Map<String, Object>> breakdown(List<Row> rows, Function<Row, String> keyFn, Function<Row, Object[]> metaFn, int limit) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Object[]> meta = new HashMap<>();
        Map<String, String> lastAt = new HashMap<>(), lastFailedAt = new HashMap<>();
        for (Row r : rows) {
            String k = keyFn.apply(r);
            if (k == null || k.isBlank()) continue;
            meta.putIfAbsent(k, metaFn.apply(r));
            int[] c = counts.computeIfAbsent(k, x -> new int[4]);
            c[0]++;
            switch (r.kind()) { case "SENT" -> c[1]++; case "FAILED" -> { c[2]++; lastFailedAt.merge(k, r.at(), (a, b) -> a.compareTo(b) >= 0 ? a : b); } case "PENDING", "BLOCKED" -> c[3]++; default -> { } }
            lastAt.merge(k, r.at(), (a, b) -> a.compareTo(b) >= 0 ? a : b);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue()[2] != a.getValue()[2] ? b.getValue()[2] - a.getValue()[2] : b.getValue()[0] - a.getValue()[0])
                .limit(limit)
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    Object[] md = meta.get(e.getKey());
                    for (int i = 0; i + 1 < md.length; i += 2) m.put((String) md[i], md[i + 1]);
                    int[] c = e.getValue();
                    m.put("total", c[0]); m.put("sent", c[1]); m.put("failed", c[2]); m.put("pending", c[3]);
                    m.put("success_rate", c[1] + c[2] == 0 ? null : Math.round(c[1] * 1000.0 / (c[1] + c[2])) / 10.0);
                    m.put("last_at", lastAt.get(e.getKey())); m.put("last_failed_at", lastFailedAt.get(e.getKey()));
                    out.add(m);
                });
        return out;
    }

    /** Satır detayı: mesaj gövdesi + ham yanıt + aynı batch'in satırları (kime gitti / kim düştü). Kapsam dışı → null. */
    public Map<String, Object> detail(long id, Scope scope) {
        UserPushDelivery d = deliveryRepo.findById(id).orElse(null);
        if (d == null) return null;
        Row row = enrich(java.util.Collections.singletonList(rawOf(d))).get(0);
        if (!scope.allows(row.teamId(), row.username())) return null;
        Map<String, Object> out = row.toMap();
        out.put("message", d.getMessage()); out.put("raw_response", d.getRawResponse());
        List<Map<String, Object>> batch = new ArrayList<>();
        if (d.getBatchId() != null) for (UserPushDelivery b : deliveryRepo.findByBatchIdOrderByIdAsc(d.getBatchId()))
            if (scope.allows(b.getTeamId(), b.getUsername())) batch.add(enrich(java.util.Collections.singletonList(rawOf(b))).get(0).toMap());
        out.put("batch", batch);
        return out;
    }

    public record RequeueResult(boolean ok, String reason) { }

    /** FAILED / BLOCKED satırı yeniden kuyruğa alır (PENDING, tek başına batch); worker hemen dener. */
    public RequeueResult requeue(long id, Scope scope, String actor) {
        UserPushDelivery d = deliveryRepo.findById(id).orElse(null);
        if (d == null) return new RequeueResult(false, "NOT_FOUND");
        if (!scope.allows(d.getTeamId(), d.getUsername())) return new RequeueResult(false, "NOT_FOUND");
        String kind = kindOf(d.getStatus());
        if (!"FAILED".equals(kind) && !"BLOCKED".equals(kind)) return new RequeueResult(false, "NOT_RETRYABLE");
        if (!userPushService.enabled()) return new RequeueResult(false, "CHANNEL_DISABLED");
        userPushService.requeue(d);
        log.info("push yeniden kuyruğa: #{} kullanıcı={} aktör={}", id, d.getUsername(), actor);
        return new RequeueResult(true, null);
    }

    // ── yardımcılar ───────────────────────────────────────────────────────────────────────────
    private static Instant truncate(Instant t, boolean hourly) {
        return hourly ? t.truncatedTo(java.time.temporal.ChronoUnit.HOURS) : t.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
    }
    private static String bucketKey(Instant t, boolean hourly) {
        String s = ISO.format(t);
        return hourly ? s.substring(0, 13) + ":00:00" : s.substring(0, 10);
    }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String lower(String s) { String b = blankToNull(s); return b == null ? null : b.toLowerCase(Locale.ROOT); }
    private static boolean contains(String hay, String needle) { return hay != null && hay.toLowerCase(Locale.ROOT).contains(needle); }
    private static String nz(String s) { return s == null ? "" : s; }
}
