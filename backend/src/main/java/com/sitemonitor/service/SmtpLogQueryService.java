package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
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
import java.util.function.Predicate;

/**
 * SMTP Gönderim Logu (2026-09-19, kullanıcı isteği: "sistemi yalnız bu sayfadan izleyebiliriz") —
 * sunucu taraflı arama / süzgeç / sıralama / sayfalama + özet (KPI, zaman çizelgesi, takım kırılımı,
 * hata sınıfları, alıcı ve alan özeti) + satır detayı (zincir) + yeniden gönderim.
 *
 * <p><b>Pencere modeli.</b> notification_logs satırı tam HTML gövde taşır (onlarca KB); eski uç 30
 * günlük pencereyi varlık olarak yüklüyordu. Burada pencere GÖVDESİZ projeksiyonla bir kez okunur,
 * alarm→takım/alan zenginleştirmesi yapılır ve 60 sn önbelleğe alınır ({@code smtp-log-window}) —
 * aynı pencere için arama + özet + 60 sn otomatik yenileme tek taramaya iner. Takım kapsamı satır
 * bazında {@link Scope} ile uygulanır (önbellek kullanıcıdan bağımsız).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmtpLogQueryService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final int MAX_WINDOW_DAYS = 365, MAX_PAGE_SIZE = 200, TOP_N = 10;
    /** Saatlik kova: pencere bu kadar saat ve altındaysa; üstü günlük. */
    static final long HOURLY_MAX_HOURS = 72;

    private final NotificationLogRepository notificationLogRepo;
    private final AlertEventRepository alertEventRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final TeamRepository teamRepo;
    private final EmailNotificationService emailService;
    /** Bean içi çağrı proxy'yi atlar (@Cacheable işlemezdi) → önbellek ELLE; testte null = önbellek yok. */
    private final @Nullable CacheManager cacheManager;

    /** Kullanıcının görüş kapsamı: global görücü her şeyi; kapsamlı kullanıcı takımlarını + o takımların envanter alanlarını. */
    public record Scope(boolean global, Set<Long> teamIds, Set<String> domains) {
        public static Scope all() { return new Scope(true, Set.of(), Set.of()); }
        boolean allows(Long teamId, String domain) {
            if (global) return true;
            if (teamId != null && teamIds.contains(teamId)) return true;
            return domain != null && domains.contains(domain.toLowerCase(Locale.ROOT));
        }
    }

    /** Arama/özet süzgeci — hepsi isteğe bağlı; {@code to} boş → şimdi (açık uçlu, canlı izleme). */
    public record Filter(String from, String to, String status, String trigger, Long teamId, String domain,
                         String recipient, String errorClass, String q, String sort) { }

    // ── Pencere ─────────────────────────────────────────────────────────────────────────────

    /** Zenginleştirilmiş, gövdesiz satır. */
    public record Row(long id, Long alertEventId, String sentAt, String recipientName, String recipientEmail, String recipientRole,
                      String subject, String emailStatus, String webhookStatus, String trigger, String emailFrom, String cc,
                      String kind, String error, String errorClass, String domain, Long teamId, String teamName,
                      String alertType, String alertLevel) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("alert_event_id", alertEventId); m.put("sent_at", sentAt);
            m.put("recipient_name", recipientName); m.put("recipient_email", recipientEmail); m.put("recipient_role", recipientRole);
            m.put("subject", subject); m.put("email_status", emailStatus); m.put("webhook_status", webhookStatus);
            m.put("trigger", trigger); m.put("sender_email", emailFrom); m.put("cc", cc);
            m.put("kind", kind); m.put("error", error); m.put("error_class", errorClass);
            m.put("domain", domain); m.put("team_id", teamId); m.put("team_name", teamName);
            m.put("alert_type", alertType); m.put("alert_level", alertLevel);
            return m;
        }
    }

    /** Pencere satırları (yeni üstte) — 60 sn önbellek ({@code smtp-log-window}); anahtar from|to ("" = açık uçlu). */
    @SuppressWarnings("unchecked")
    public List<Row> window(String from, String to) {
        String key = from + "|" + (to == null ? "" : to);
        Cache cache = cacheManager == null ? null : cacheManager.getCache("smtp-log-window");
        if (cache == null) return loadWindow(from, to);
        List<Row> cached = cache.get(key, () -> loadWindow(from, to));
        return cached == null ? List.of() : cached;
    }

    private List<Row> loadWindow(String from, String to) {
        String upper = to == null || to.isBlank() ? "9999-12-31T23:59:59" : to;
        return enrich(notificationLogRepo.findWindowRows(from, upper));
    }

    private List<Row> enrich(List<Object[]> raw) {
        Set<Long> eventIds = new HashSet<>();
        for (Object[] r : raw) if (r[1] != null) eventIds.add(((Number) r[1]).longValue());
        Map<Long, AlertEvent> events = new HashMap<>();
        if (!eventIds.isEmpty()) {
            try { for (AlertEvent e : alertEventRepo.findAllById(eventIds)) events.put(e.getId(), e); }
            catch (Exception e) { log.debug("smtp-log: alarm olayları okunamadı: {}", e.toString()); }
        }
        Map<String, Long> domainTeam = new HashMap<>();
        try { for (CertificateInventory i : inventoryRepo.findByActiveTrueOrderByDomainAsc()) if (i.getDomain() != null) domainTeam.put(i.getDomain().toLowerCase(Locale.ROOT), i.getTeamId()); }
        catch (Exception e) { log.debug("smtp-log: envanter okunamadı: {}", e.toString()); }
        Map<Long, String> teamNames = new HashMap<>();
        try { for (Team t : teamRepo.findAll()) teamNames.put(t.getId(), t.getName()); } catch (Exception ignored) { }

        List<Row> out = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            Long eid = r[1] == null ? null : ((Number) r[1]).longValue();
            AlertEvent e = eid == null ? null : events.get(eid);
            String domain = e == null ? null : e.getDomain();
            Long teamId = e == null ? null : e.getTeamId();
            if (teamId == null && domain != null) teamId = domainTeam.get(domain.toLowerCase(Locale.ROOT));   // envanter-türevi alarm
            String status = (String) r[7];
            out.add(new Row(((Number) r[0]).longValue(), eid, (String) r[2], (String) r[3], (String) r[4], (String) r[5], (String) r[6],
                    status, (String) r[8], (String) r[9], (String) r[10], (String) r[11],
                    kindOf(status), errorOf(status), classifyError(status), domain, teamId,
                    teamId == null ? null : teamNames.get(teamId),
                    e == null ? null : e.getAlertType(), e == null ? null : e.getAlertLevel()));
        }
        return out;
    }

    /** SENT / FAILED / SKIPPED / QUEUED (async yeniden deneme bekliyor) / UNKNOWN. */
    static String kindOf(String status) {
        if (status == null || status.isBlank()) return "UNKNOWN";
        String s = status.toUpperCase(Locale.ROOT);
        if (s.equals("SENT")) return "SENT";
        if (s.startsWith("FAILED")) return "FAILED";
        if (s.startsWith("SKIPPED")) return "SKIPPED";
        if (s.startsWith("QUEUED")) return "QUEUED";
        return "UNKNOWN";
    }

    /** "FAILED: x" / "SKIPPED_DISABLED" → "x" / "DISABLED"; SENT → "". */
    static String errorOf(String status) {
        if (status == null) return "";
        String k = kindOf(status);
        if (k.equals("SENT") || k.equals("UNKNOWN")) return "";
        return status.replaceFirst("(?i)^(FAILED|SKIPPED|QUEUED)[_:]?\\s*", "").trim();
    }

    /**
     * Hata sınıfı (yalnız FAILED): AUTH · TIMEOUT · CONNECT · RECIPIENT · RATE · OTHER. Sınıflar SMTP yanıt
     * kodları + JavaMail mesaj kalıplarından; sıra önemli (ör. "connection timed out" → TIMEOUT).
     */
    static String classifyError(String status) {
        if (!"FAILED".equals(kindOf(status))) return null;
        String s = errorOf(status).toLowerCase(Locale.ROOT);
        if (s.contains("timed out") || s.contains("timeout")) return "TIMEOUT";
        if (s.contains("535") || s.contains("534") || s.contains("authentication") || s.contains("auth ")
                || s.contains("credential") || s.contains("password") || s.contains("username")) return "AUTH";
        if (s.contains("452") || s.contains("quota") || s.contains("too many") || s.contains("rate limit")
                || s.contains("throttl") || s.contains("421")) return "RATE";
        if (s.contains("550") || s.contains("551") || s.contains("553") || s.contains("554") || s.contains("recipient")
                || s.contains("mailbox") || s.contains("user unknown") || s.contains("rejected") || s.contains("invalid address")
                || s.contains("addressexception") || s.contains("no such user")) return "RECIPIENT";
        if (s.contains("connect") || s.contains("refused") || s.contains("unknown host") || s.contains("unknownhost")
                || s.contains("network") || s.contains("ssl") || s.contains("tls") || s.contains("handshake")
                || s.contains("unreachable") || s.contains("socket")) return "CONNECT";
        return "OTHER";
    }

    // ── Süzgeç ──────────────────────────────────────────────────────────────────────────────

    private static Predicate<Row> predicate(Filter f, Scope scope) {
        String status = blankToNull(f.status()), trigger = blankToNull(f.trigger()), cls = blankToNull(f.errorClass());
        String domain = lower(f.domain()), recipient = lower(f.recipient()), q = lower(f.q());
        return r -> {
            if (!scope.allows(r.teamId(), r.domain())) return false;
            if (status != null && !status.equalsIgnoreCase(r.kind())) return false;
            if (trigger != null && !trigger.equalsIgnoreCase(r.trigger())) return false;
            if (f.teamId() != null && !f.teamId().equals(r.teamId())) return false;
            if (cls != null && !cls.equalsIgnoreCase(r.errorClass())) return false;
            if (domain != null && !contains(r.domain(), domain)) return false;
            if (recipient != null && !contains(r.recipientEmail(), recipient) && !contains(r.recipientName(), recipient)) return false;
            if (q != null && !(contains(r.recipientEmail(), q) || contains(r.recipientName(), q) || contains(r.subject(), q)
                    || contains(r.domain(), q) || contains(r.error(), q) || contains(r.cc(), q) || contains(r.teamName(), q))) return false;
            return true;
        };
    }

    private static Comparator<Row> comparator(String sort) {
        String s = sort == null ? "" : sort.trim();
        boolean asc = s.endsWith(",asc");
        String field = s.contains(",") ? s.substring(0, s.indexOf(',')) : s;
        Comparator<Row> c = switch (field) {
            case "recipient" -> Comparator.comparing((Row r) -> nz(r.recipientEmail()), String.CASE_INSENSITIVE_ORDER);
            case "domain" -> Comparator.comparing((Row r) -> nz(r.domain()), String.CASE_INSENSITIVE_ORDER);
            case "status" -> Comparator.comparing((Row r) -> nz(r.kind()));
            case "trigger" -> Comparator.comparing((Row r) -> nz(r.trigger()));
            case "team" -> Comparator.comparing((Row r) -> nz(r.teamName()), String.CASE_INSENSITIVE_ORDER);
            case "subject" -> Comparator.comparing((Row r) -> nz(r.subject()), String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing((Row r) -> nz(r.sentAt())).thenComparingLong(Row::id);
        };
        // Varsayılan (sent_at) YENİ üstte; diğer alanlar açık verilmedikçe artan; eşitlikte zaman yeni üstte.
        boolean timeField = field.isEmpty() || field.equals("sent_at");
        boolean desc = timeField ? !asc : s.endsWith(",desc");
        if (desc) c = c.reversed();
        return timeField ? c : c.thenComparing(Comparator.comparing((Row r) -> nz(r.sentAt())).reversed());
    }

    // ── Uçlar ───────────────────────────────────────────────────────────────────────────────

    /** Pencere sınırları: from yoksa 7 gün, tavan 365 gün; to boşsa açık uçlu. */
    static String[] bounds(Filter f, Instant now) {
        String to = blankToNull(f.to());
        String from = blankToNull(f.from());
        Instant floor = now.minus(Duration.ofDays(MAX_WINDOW_DAYS));
        if (from == null) from = ISO.format(now.minus(Duration.ofDays(7)));
        if (from.compareTo(ISO.format(floor)) < 0) from = ISO.format(floor);
        return new String[]{from, to};
    }

    public Map<String, Object> search(Filter f, Scope scope, int page, int size, Instant now) {
        String[] b = bounds(f, now);
        List<Row> rows = window(b[0], b[1]).stream().filter(predicate(f, scope)).sorted(comparator(f.sort())).toList();
        int sz = Math.max(1, Math.min(size, MAX_PAGE_SIZE)), pg = Math.max(0, page);
        int fromIdx = Math.min(pg * sz, rows.size()), toIdx = Math.min(fromIdx + sz, rows.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size()); out.put("page", pg); out.put("size", sz);
        out.put("from", b[0]); out.put("to", b[1]);
        out.put("items", rows.subList(fromIdx, toIdx).stream().map(Row::toMap).toList());
        return out;
    }

    /** Süzgeçle eşleşen TÜM satırlar (CSV dışa aktarma) — tavan {@code cap}. */
    public List<Map<String, Object>> exportRows(Filter f, Scope scope, int cap, Instant now) {
        String[] b = bounds(f, now);
        return window(b[0], b[1]).stream().filter(predicate(f, scope)).sorted(comparator(f.sort())).limit(cap).map(Row::toMap).toList();
    }

    public Map<String, Object> summary(Filter f, Scope scope, Instant now) {
        String[] b = bounds(f, now);
        List<Row> rows = window(b[0], b[1]).stream().filter(predicate(f, scope)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", b[0]); out.put("to", b[1]);

        // KPI
        int sent = 0, failed = 0, skipped = 0, queued = 0;
        String lastSent = null, lastFailed = null;
        Set<String> failedRecipients = new HashSet<>();
        for (Row r : rows) {
            switch (r.kind()) {
                case "SENT" -> { sent++; if (lastSent == null || r.sentAt().compareTo(lastSent) > 0) lastSent = r.sentAt(); }
                case "FAILED" -> { failed++; if (lastFailed == null || r.sentAt().compareTo(lastFailed) > 0) lastFailed = r.sentAt(); if (r.recipientEmail() != null) failedRecipients.add(r.recipientEmail().toLowerCase(Locale.ROOT)); }
                case "SKIPPED" -> skipped++;
                case "QUEUED" -> queued++;
                default -> { }
            }
        }
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("total", rows.size()); kpi.put("sent", sent); kpi.put("failed", failed); kpi.put("skipped", skipped); kpi.put("queued", queued);
        kpi.put("success_rate", sent + failed == 0 ? null : Math.round(sent * 1000.0 / (sent + failed)) / 10.0);
        kpi.put("last_sent_at", lastSent); kpi.put("last_failed_at", lastFailed); kpi.put("failed_recipients", failedRecipients.size());
        out.put("kpi", kpi);

        // Zaman çizelgesi: pencere ≤72 saat → saatlik, üstü günlük; boş kovalar da yazılır (grafik sürekli).
        Instant fromAt = parse(b[0]), toAt = b[1] == null ? now : parse(b[1]);
        boolean hourly = fromAt != null && toAt != null && Duration.between(fromAt, toAt).toHours() <= HOURLY_MAX_HOURS;
        out.put("granularity", hourly ? "hour" : "day");
        TreeMap<String, int[]> buckets = new TreeMap<>();
        if (fromAt != null && toAt != null && !toAt.isBefore(fromAt)) {
            Instant cur = truncate(fromAt, hourly);
            int guard = 0;
            while (!cur.isAfter(toAt) && guard++ < 400) { buckets.put(bucketKey(cur, hourly), new int[3]); cur = cur.plus(hourly ? Duration.ofHours(1) : Duration.ofDays(1)); }
        }
        for (Row r : rows) {
            Instant at = parse(r.sentAt());
            if (at == null) continue;
            int[] c = buckets.computeIfAbsent(bucketKey(truncate(at, hourly), hourly), k -> new int[3]);
            switch (r.kind()) { case "SENT" -> c[0]++; case "FAILED" -> c[1]++; case "SKIPPED" -> c[2]++; default -> { } }
        }
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (var e : buckets.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bucket", e.getKey()); m.put("sent", e.getValue()[0]); m.put("failed", e.getValue()[1]); m.put("skipped", e.getValue()[2]);
            timeline.add(m);
        }
        out.put("timeline", timeline);

        // Takım kırılımı
        Map<String, int[]> byTeam = new LinkedHashMap<>();
        Map<String, Object[]> teamMeta = new HashMap<>();
        for (Row r : rows) {
            String key = r.teamId() == null ? "-" : String.valueOf(r.teamId());
            teamMeta.putIfAbsent(key, new Object[]{r.teamId(), r.teamName()});
            int[] c = byTeam.computeIfAbsent(key, k -> new int[4]);
            c[0]++;
            switch (r.kind()) { case "SENT" -> c[1]++; case "FAILED" -> c[2]++; case "SKIPPED" -> c[3]++; default -> { } }
        }
        List<Map<String, Object>> teams = new ArrayList<>();
        for (var e : byTeam.entrySet()) {
            int[] c = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", teamMeta.get(e.getKey())[0]); m.put("team_name", teamMeta.get(e.getKey())[1]);
            m.put("total", c[0]); m.put("sent", c[1]); m.put("failed", c[2]); m.put("skipped", c[3]);
            m.put("success_rate", c[1] + c[2] == 0 ? null : Math.round(c[1] * 1000.0 / (c[1] + c[2])) / 10.0);
            teams.add(m);
        }
        teams.sort(Comparator.<Map<String, Object>>comparingInt(m -> -(Integer) m.get("failed")).thenComparingInt(m -> -(Integer) m.get("total")));
        out.put("teams", teams);

        // Hata sınıfları
        Map<String, Integer> classes = new LinkedHashMap<>();
        for (Row r : rows) if (r.errorClass() != null) classes.merge(r.errorClass(), 1, Integer::sum);
        List<Map<String, Object>> errorClasses = new ArrayList<>();
        classes.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).forEach(e -> {
            Map<String, Object> m = new LinkedHashMap<>(); m.put("error_class", e.getKey()); m.put("count", e.getValue()); errorClasses.add(m);
        });
        out.put("error_classes", errorClasses);

        // Tetikleyici dağılımı
        Map<String, Integer> triggers = new LinkedHashMap<>();
        for (Row r : rows) triggers.merge(r.trigger() == null ? "-" : r.trigger(), 1, Integer::sum);
        List<Map<String, Object>> triggerList = new ArrayList<>();
        triggers.forEach((k, v) -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("trigger", k); m.put("count", v); triggerList.add(m); });
        out.put("triggers", triggerList);

        // Alıcı ve alan özeti (en çok başarısız üstte, sonra toplam)
        out.put("top_recipients", topBy(rows, r -> r.recipientEmail() == null ? null : r.recipientEmail().toLowerCase(Locale.ROOT),
                r -> new Object[]{"recipient_email", r.recipientEmail(), "recipient_name", r.recipientName()}));
        out.put("top_domains", topBy(rows, Row::domain, r -> new Object[]{"domain", r.domain(), "team_id", r.teamId(), "team_name", r.teamName()}));
        return out;
    }

    private static List<Map<String, Object>> topBy(List<Row> rows, java.util.function.Function<Row, String> keyFn,
                                                   java.util.function.Function<Row, Object[]> metaFn) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Object[]> meta = new HashMap<>();
        Map<String, String> lastAt = new HashMap<>(), lastFailedAt = new HashMap<>();
        for (Row r : rows) {
            String k = keyFn.apply(r);
            if (k == null || k.isBlank()) continue;
            meta.putIfAbsent(k, metaFn.apply(r));
            int[] c = counts.computeIfAbsent(k, x -> new int[2]);
            c[0]++;
            if ("FAILED".equals(r.kind())) { c[1]++; lastFailedAt.merge(k, r.sentAt(), (a, b) -> a.compareTo(b) >= 0 ? a : b); }
            lastAt.merge(k, r.sentAt(), (a, b) -> a.compareTo(b) >= 0 ? a : b);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue()[1] != a.getValue()[1] ? b.getValue()[1] - a.getValue()[1] : b.getValue()[0] - a.getValue()[0])
                .limit(TOP_N)
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    Object[] md = meta.get(e.getKey());
                    for (int i = 0; i + 1 < md.length; i += 2) m.put((String) md[i], md[i + 1]);
                    m.put("total", e.getValue()[0]); m.put("failed", e.getValue()[1]);
                    m.put("last_at", lastAt.get(e.getKey())); m.put("last_failed_at", lastFailedAt.get(e.getKey()));
                    out.add(m);
                });
        return out;
    }

    /** Satır detayı: tam gövde + alarm özeti + aynı alarmın zinciri. Kapsam dışı → null. */
    public Map<String, Object> detail(long id, Scope scope) {
        NotificationLog n = notificationLogRepo.findById(id).orElse(null);
        if (n == null) return null;
        Row row = enrich(java.util.Collections.singletonList(rawOf(n))).get(0);
        if (!scope.allows(row.teamId(), row.domain())) return null;
        Map<String, Object> out = row.toMap();
        out.put("message", n.getMessage());
        if (n.getAlertEventId() != null) {
            AlertEvent e = alertEventRepo.findById(n.getAlertEventId()).orElse(null);
            if (e != null) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("id", e.getId()); ev.put("domain", e.getDomain()); ev.put("type", e.getAlertType()); ev.put("level", e.getAlertLevel());
                ev.put("created_at", e.getCreatedAt()); ev.put("resolved", Boolean.TRUE.equals(e.getResolved())); ev.put("resolved_at", e.getResolvedAt());
                ev.put("acknowledged", Boolean.TRUE.equals(e.getAcknowledged()));
                out.put("alert", ev);
            }
            List<Map<String, Object>> chain = new ArrayList<>();
            for (Row c : enrich(notificationLogRepo.findChainRows(n.getAlertEventId()))) chain.add(c.toMap());
            out.put("chain", chain);
        } else {
            out.put("chain", List.of());
        }
        return out;
    }

    /** Yeniden gönderim sonucu. */
    public record ResendResult(boolean ok, String status, Long newLogId, String reason) { }

    /**
     * FAILED satırı aynı alıcıya, aynı konu ve gövdeyle yeniden gönderir; sonuç YENİ bir log satırı olur
     * (trigger MANUAL, kaynağa referans konu değiştirilmeden). Çözülmüş alarmın maili yeniden gönderilmez
     * (bayat uyarı üretmesin); yalnız FAILED yeniden gönderilebilir.
     */
    public ResendResult resend(long id, Scope scope, String actor) {
        NotificationLog n = notificationLogRepo.findById(id).orElse(null);
        if (n == null) return new ResendResult(false, null, null, "NOT_FOUND");
        Row row = enrich(java.util.Collections.singletonList(rawOf(n))).get(0);
        if (!scope.allows(row.teamId(), row.domain())) return new ResendResult(false, null, null, "NOT_FOUND");
        if (!"FAILED".equals(row.kind())) return new ResendResult(false, null, null, "NOT_FAILED");
        if (n.getRecipientEmail() == null || n.getRecipientEmail().isBlank()) return new ResendResult(false, null, null, "NO_RECIPIENT");
        if (n.getMessage() == null || n.getMessage().isBlank()) return new ResendResult(false, null, null, "NO_BODY");
        if (n.getAlertEventId() != null) {
            AlertEvent e = alertEventRepo.findById(n.getAlertEventId()).orElse(null);
            if (e != null && Boolean.TRUE.equals(e.getResolved())) return new ResendResult(false, null, null, "ALERT_RESOLVED");
        }
        String variant = "RESOLUTION".equalsIgnoreCase(n.getTrigger()) ? "ok" : BrandMailAssets.variantForLevel(row.alertLevel());
        String status = emailService.resendStoredHtml(n.getRecipientEmail().trim(), n.getSubject(), n.getMessage(), variant);
        NotificationLog copy = new NotificationLog();
        copy.setAlertEventId(n.getAlertEventId()); copy.setSentAt(ISO.format(Instant.now()));
        copy.setRecipientName(n.getRecipientName()); copy.setRecipientEmail(n.getRecipientEmail()); copy.setRecipientRole(n.getRecipientRole());
        copy.setSubject(n.getSubject()); copy.setMessage(n.getMessage()); copy.setEmailStatus(status); copy.setWebhookStatus("SKIPPED");
        copy.setTrigger("MANUAL"); copy.setEmailFrom(emailService.getEmailFrom()); copy.setCc(n.getCc());
        Long newId = null;
        try { newId = notificationLogRepo.save(copy).getId(); } catch (Exception ex) { log.warn("smtp-log: yeniden gönderim logu yazılamadı: {}", ex.toString()); }
        log.info("SMTP yeniden gönderim: log #{} → #{} TO={} durum={} aktör={}", id, newId, n.getRecipientEmail(), status, actor);
        return new ResendResult("SENT".equals(status), status, newId, null);
    }

    // ── yardımcılar ─────────────────────────────────────────────────────────────────────────

    /** Varlık → projeksiyon satırı (findWindowRows ile AYNI sütun sırası). */
    private static Object[] rawOf(NotificationLog n) {
        return new Object[]{n.getId(), n.getAlertEventId(), n.getSentAt(), n.getRecipientName(), n.getRecipientEmail(), n.getRecipientRole(),
                n.getSubject(), n.getEmailStatus(), n.getWebhookStatus(), n.getTrigger(), n.getEmailFrom(), n.getCc()};
    }

    static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return LocalDateTime.parse(iso.length() > 19 ? iso.substring(0, 19) : iso).toInstant(ZoneOffset.UTC); }
        catch (Exception e) { return null; }
    }
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
