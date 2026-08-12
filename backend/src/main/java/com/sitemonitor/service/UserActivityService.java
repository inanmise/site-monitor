package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * System Health "Kullanıcı / Oturum İzleme" akordiyonu için toplulaştırma servisi.
 *
 * <p>Veri kaynağı: {@code audit_log} (LOGIN / LOGIN_FAILED; outcome SUCCESS/FAILURE/BLOCKED) +
 * {@code app_users.active_session_id} (tek-oturum). Şema değişikliği YOK — tek 7 günlük pencere
 * çekilip zaman kümeleme Java'da {@link #ZONE} (Europe/Istanbul) saatine göre yapılır. event_time
 * UTC ISO string olduğundan grafik {@code ts}'leri yerel kova başlangıcının UTC ISO karşılığıdır
 * (MiniChart {@code new Date(ts+'Z')} ile tarayıcı yerel saatine çevirir → tutarlı).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserActivityService {

    private final AuditLogRepository auditLogRepo;
    private final AppUserRepository  userRepo;
    private final TeamRepository     teamRepo;
    private final UserService        userService;

    /** Proje TZ'si: Europe/Istanbul (UTC+3, DST yok). */
    private static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final List<String> LOGIN_TYPES = List.of("LOGIN", "LOGIN_FAILED");
    private static final List<String> ANOMALY_KINDS =
            List.of("OFF_HOURS", "UNUSUAL_IP", "GEO_VELOCITY", "BRUTE_FORCE", "RATE_LIMITED");
    private static final int TOP_N = 10;
    private static final int RECENT_ANOMALIES = 20;
    private static final int HEATMAP_CELL_CAP = 200;   // hücre başına en fazla login detayı
    private static final int DETAIL_CAP = 500;         // KPI drill-down liste üst sınırı
    private static final long MAX_RANGE_DAYS = 31;     // esnek seri sorgusu üst sınırı
    private static final long DAY_SECONDS = 86_400L;

    private enum Gran { DAY, HOUR, MINUTE }

    /** Tek payload — frontend tek çağrı yapar, mevcut 30 sn yenilemeye bağlanır.
     *  60 sn cache: 7 günlük audit login penceresini + Java-tarafı agregasyonu her ~30sn açılışta
     *  yeniden çalıştırmaz (audit_log ölçeklenince önemli). Auth SystemController'da kalır. */
    @org.springframework.cache.annotation.Cacheable("user-activity-overview")
    public Map<String, Object> getOverview() {
        String since7d = ISO.format(Instant.now().minusSeconds(7 * DAY_SECONDS));
        List<AuditLog> window = auditLogRepo.findLoginEventsSince(LOGIN_TYPES, since7d);
        Map<Long, String> teamNames = teamNameMap();
        Map<String, AppUser> usersByName = usersByName();   // username → AppUser (ad/soyad/resim drill-down için)

        List<Map<String, Object>> activeUsers = buildActiveUsers(teamNames);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary",      buildSummary(window, activeUsers.size()));
        out.put("active_users", activeUsers);
        out.put("login_status", buildLoginStatus(usersByName, teamNames));   // ek sorgu yok (usersByName zaten yüklü)
        out.put("series",       buildSeries(window));
        out.put("top_users",    buildTopUsers(window, usersByName));
        out.put("top_sources",  buildTopSources(window));
        out.put("anomalies",    buildAnomalies(window));
        out.put("role_team",    buildRoleTeam(window, teamNames, usersByName));
        out.put("heatmaps",     buildWeeklyHeatmaps());   // bu hafta + 1 önceki + 2 önceki (her biri from/to'lu)
        out.put("details",      buildKpiDetails(window)); // KPI kartlarına tıklayınca 24s drill-down listeleri
        return out;
    }

    /** Esnek aralık + granülarite login serisi (grafik aralık seçimi / gün-navigasyonu / zoom).
     *  from/to UTC ISO; granularity = day|hour|minute. Aralık MAX_RANGE_DAYS ile sınırlanır. */
    public Map<String, Object> getLoginSeries(String fromIso, String toIso, String granularity) {
        Instant from = parse(fromIso);
        Instant to   = parse(toIso);
        if (from == null || to == null || !from.isBefore(to)) {
            return Map.of("buckets", List.of(), "granularity", "day");
        }
        if (Duration.between(from, to).toDays() > MAX_RANGE_DAYS) {
            from = to.minusSeconds(MAX_RANGE_DAYS * DAY_SECONDS);   // aralığı kırp
        }
        Gran g = switch (granularity == null ? "" : granularity.toLowerCase()) {
            case "minute" -> Gran.MINUTE;
            case "hour"   -> Gran.HOUR;
            default       -> Gran.DAY;
        };
        String fromKey = ISO.format(from), toKey = ISO.format(to);
        List<AuditLog> window = auditLogRepo.findLoginEventsBetween(LOGIN_TYPES, fromKey, toKey);
        List<Map<String, Object>> buckets = fillSeries(bucketRange(from, to, g), g, window);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buckets", buckets);
        out.put("granularity", g.name().toLowerCase());
        out.put("from", fromKey);
        out.put("to", toKey);
        return out;
    }

    /** Verilen [from,to] aralığını granülariteye göre kova başlangıçlarına böler (Europe/Istanbul). */
    private List<ZonedDateTime> bucketRange(Instant from, Instant to, Gran g) {
        List<ZonedDateTime> list = new ArrayList<>();
        ZonedDateTime cur = bucketStart(from.atZone(ZONE), g);
        int guard = 0;
        while (!cur.toInstant().isAfter(to) && guard < 100_000) {
            list.add(cur);
            cur = switch (g) {
                case DAY    -> cur.plusDays(1);
                case HOUR   -> cur.plusHours(1);
                case MINUTE -> cur.plusMinutes(1);
            };
            guard++;
        }
        return list;
    }

    // ── Summary ────────────────────────────────────────────────────────────────
    private Map<String, Object> buildSummary(List<AuditLog> window, int activeCount) {
        String s24 = ISO.format(Instant.now().minusSeconds(DAY_SECONDS));
        long s24Logins = 0, s24Failed = 0, s24Anom = 0;
        long d7Logins = 0, d7Failed = 0, d7Anom = 0;
        Set<String> users24 = new HashSet<>();
        Set<String> users7d = new HashSet<>();

        for (AuditLog a : window) {
            boolean success = "SUCCESS".equals(a.getOutcome());
            boolean anom = a.getAnomalyFlags() != null && !a.getAnomalyFlags().isBlank();
            if (success) { d7Logins++; if (a.getActor() != null) users7d.add(a.getActor()); }
            else d7Failed++;
            if (anom) d7Anom++;

            if (a.getEventTime() != null && a.getEventTime().compareTo(s24) >= 0) {
                if (success) { s24Logins++; if (a.getActor() != null) users24.add(a.getActor()); }
                else s24Failed++;
                if (anom) s24Anom++;
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active_count",      activeCount);
        m.put("logins_24h",        s24Logins);
        m.put("failed_24h",        s24Failed);
        m.put("anomalies_24h",     s24Anom);
        m.put("unique_users_24h",  users24.size());
        m.put("logins_7d",         d7Logins);
        m.put("failed_7d",         d7Failed);
        m.put("anomalies_7d",      d7Anom);
        m.put("unique_users_7d",   users7d.size());
        return m;
    }

    // ── KPI drill-down (son 24 saat) — kartlara tıklayınca detay listeleri ──────
    private Map<String, Object> buildKpiDetails(List<AuditLog> window) {
        String s24 = ISO.format(Instant.now().minusSeconds(DAY_SECONDS));
        List<AuditLog> logins = new ArrayList<>();
        List<AuditLog> failed = new ArrayList<>();
        List<AuditLog> anoms  = new ArrayList<>();
        Map<String, long[]> userAgg = new LinkedHashMap<>();
        Map<String, String> userLast = new LinkedHashMap<>();
        for (AuditLog a : window) {
            if (a.getEventTime() == null || a.getEventTime().compareTo(s24) < 0) continue;
            if ("SUCCESS".equals(a.getOutcome())) {
                logins.add(a);
                if (a.getActor() != null) {
                    userAgg.computeIfAbsent(a.getActor(), k -> new long[1])[0]++;
                    userLast.merge(a.getActor(), a.getEventTime(), (c, n) -> n.compareTo(c) > 0 ? n : c);
                }
            } else {
                failed.add(a);
            }
            if (a.getAnomalyFlags() != null && !a.getAnomalyFlags().isBlank()) anoms.add(a);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("logins", eventRows(logins));
        out.put("failed", eventRows(failed));
        out.put("anomalies", eventRows(anoms));
        out.put("unique_users", userAgg.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("username", e.getKey());
                    m.put("logins", e.getValue()[0]);
                    m.put("last_login", userLast.get(e.getKey()));
                    return m;
                }).toList());
        return out;
    }

    /** Olay listesini yeni→eski sırada, cap'li UI satırlarına çevirir (ortak satır şekli). */
    private List<Map<String, Object>> eventRows(List<AuditLog> events) {
        return events.stream()
                .sorted((x, y) -> nullSafe(y.getEventTime()).compareTo(nullSafe(x.getEventTime())))
                .limit(DETAIL_CAP)
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("time", a.getEventTime());
                    m.put("actor", a.getActor());
                    m.put("ip", a.getIpAddress());
                    m.put("country", a.getIpCountry());
                    m.put("city", a.getIpCity());
                    m.put("outcome", a.getOutcome());
                    m.put("flags", a.getAnomalyFlags());
                    m.put("reason", a.getFailureReason());
                    return m;
                }).toList();
    }

    // ── Aktif kullanıcılar ───────────────────────────────────────────────────────
    private List<Map<String, Object>> buildActiveUsers(Map<Long, String> teamNames) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Instant now = Instant.now();
        for (AppUser u : userRepo.findAllWithActiveSession()) {
            // Yalnız CANLI oturumlar: son ping tazelik penceresinde olmalı. Logout'suz kapatılan/ölen
            // oturumlar (ping durmuş) sayımdan ve listeden otomatik düşer.
            if (!userService.hasLiveSession(u)) continue;
            // Oturuma ait audit satırı → login zamanı + IP/konum/tarayıcı. Fallback: son başarılı LOGIN.
            AuditLog ev = auditLogRepo
                    .findTopByActorAndSessionIdOrderByEventTimeDesc(u.getUsername(), u.getActiveSessionId())
                    .orElseGet(() -> auditLogRepo
                            .findTopByActorAndEventTypeAndOutcomeOrderByEventTimeDesc(
                                    u.getUsername(), "LOGIN", "SUCCESS")
                            .orElse(null));
            String loginAt = ev != null ? ev.getEventTime() : null;
            Instant loginInstant = parse(loginAt);
            long durationMin = loginInstant != null
                    ? Math.max(0, java.time.Duration.between(loginInstant, now).toMinutes()) : 0;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username",     u.getUsername());
            m.put("display_name", u.getDisplayName());
            m.put("email",        u.getEmail());
            m.put("employee_id",  u.getEmployeeId());
            m.put("system_role",  u.getSystemRole());
            m.put("org_role",     u.getOrgRole());
            m.put("team_id",      u.getTeamId());
            m.put("team_name",    u.getTeamId() != null ? teamNames.get(u.getTeamId()) : null);
            m.put("login_at",     loginAt);
            m.put("last_seen",    u.getLastSeenAt());
            m.put("duration_min", durationMin);
            m.put("ip",           ev != null ? ev.getIpAddress() : null);
            m.put("country",      ev != null ? ev.getIpCountry() : null);
            m.put("city",         ev != null ? ev.getIpCity() : null);
            m.put("org",          ev != null ? ev.getIpOrg() : null);
            m.put("user_agent",   ev != null ? ev.getUserAgent() : null);
            putLoginStamp(m, u);
            rows.add(m);
        }
        return rows;
    }

    /**
     * Giriş damgası alanları — kullanıcı satırından, audit'ten DEĞİL.
     *
     * <p>Neden ayrı: yukarıdaki {@code login_at} audit'ten gelir ve 180 günlük budamaya tabidir;
     * ayrıca oturum süresi hesabı ona bağlı olduğu için dokunulmadı. Buradaki alanlar kalıcıdır ve
     * "hiç girmemiş" / "şu anda deneniyor" gibi soruları audit penceresinden bağımsız cevaplar.
     * Alan adları {@code login_status} listesiyle BİREBİR aynıdır — aynı detay modalı ikisini de
     * render edebilsin.
     */
    private static void putLoginStamp(Map<String, Object> m, AppUser u) {
        m.put("last_login_at",       u.getLastLoginAt());
        m.put("last_login_ip",       u.getLastLoginIp());
        m.put("last_login_method",   u.getLastLoginMethod());
        m.put("prev_login_at",       u.getPrevLoginAt());
        m.put("prev_login_ip",       u.getPrevLoginIp());
        m.put("last_failed_at",      u.getLastFailedLoginAt());
        m.put("last_failed_ip",      u.getLastFailedLoginIp());
        m.put("last_failed_reason",  u.getLastFailedLoginReason());
        m.put("failed_since_login",  u.getFailedSinceLogin() == null ? 0 : u.getFailedSinceLogin());
        m.put("failed_before_login", u.getFailedBeforeLogin() == null ? 0 : u.getFailedBeforeLogin());
    }

    /**
     * TÜM kullanıcıların giriş durumu (yalnız o an oturumu açık olanlar değil).
     *
     * <p>Ek sorgu YOK: {@code usersByName()} zaten {@code findAll()} yapıyor. Sıralama son girişe
     * göre azalan, hiç girmemişler sonda — "atıl hesap" ve "parola denenen hesap" aynı ekranda
     * görünür.
     */
    private List<Map<String, Object>> buildLoginStatus(Map<String, AppUser> usersByName,
                                                       Map<Long, String> teamNames) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AppUser u : usersByName.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username",     u.getUsername());
            m.put("user_id",      u.getId());
            m.put("display_name", u.getDisplayName());
            m.put("system_role",  u.getSystemRole());
            m.put("team_name",    u.getTeamId() != null ? teamNames.get(u.getTeamId()) : null);
            m.put("active",       Boolean.TRUE.equals(u.getActive()));
            m.put("auth_source",  u.getAuthSource());
            putLoginStamp(m, u);
            rows.add(m);
        }
        rows.sort((a, b) -> {
            String x = (String) a.get("last_login_at");
            String y = (String) b.get("last_login_at");
            if (x == null && y == null) return 0;
            if (x == null) return 1;      // hiç girmemişler sonda
            if (y == null) return -1;
            return y.compareTo(x);        // ISO-UTC sabit genişlikte → leksikografik = kronolojik
        });
        return rows;
    }

    // ── Zaman serileri (gün/saat/dakika) ─────────────────────────────────────────
    private Map<String, Object> buildSeries(List<AuditLog> window) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day",    fillSeries(bucketStarts(Gran.DAY),    Gran.DAY,    window));
        m.put("hour",   fillSeries(bucketStarts(Gran.HOUR),   Gran.HOUR,   window));
        m.put("minute", fillSeries(bucketStarts(Gran.MINUTE), Gran.MINUTE, window));
        return m;
    }

    private List<ZonedDateTime> bucketStarts(Gran g) {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        List<ZonedDateTime> list = new ArrayList<>();
        switch (g) {
            case DAY -> {
                ZonedDateTime base = now.toLocalDate().atStartOfDay(ZONE);
                for (int i = 6; i >= 0; i--) list.add(base.minusDays(i));
            }
            case HOUR -> {
                ZonedDateTime base = now.withMinute(0).withSecond(0).withNano(0);
                for (int i = 23; i >= 0; i--) list.add(base.minusHours(i));
            }
            case MINUTE -> {
                ZonedDateTime base = now.withSecond(0).withNano(0);
                for (int i = 59; i >= 0; i--) list.add(base.minusMinutes(i));
            }
        }
        return list;
    }

    private List<Map<String, Object>> fillSeries(List<ZonedDateTime> starts, Gran g, List<AuditLog> window) {
        LinkedHashMap<String, long[]> buckets = new LinkedHashMap<>();
        for (ZonedDateTime z : starts) buckets.put(tsOf(z), new long[3]); // [success, failed, blocked]
        for (AuditLog a : window) {
            Instant t = parse(a.getEventTime());
            if (t == null) continue;
            long[] c = buckets.get(tsOf(bucketStart(t.atZone(ZONE), g)));
            if (c != null) c[outcomeIdx(a)]++;
        }
        List<Map<String, Object>> out = new ArrayList<>(buckets.size());
        for (var e : buckets.entrySet()) {
            long[] c = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", e.getKey());
            m.put("success", c[0]);
            m.put("failed",  c[1]);
            m.put("blocked", c[2]);
            m.put("total",   c[0] + c[1] + c[2]);
            out.add(m);
        }
        return out;
    }

    private ZonedDateTime bucketStart(ZonedDateTime z, Gran g) {
        return switch (g) {
            case DAY    -> z.toLocalDate().atStartOfDay(ZONE);
            case HOUR   -> z.withMinute(0).withSecond(0).withNano(0);
            case MINUTE -> z.withSecond(0).withNano(0);
        };
    }

    // ── Top kullanıcılar / kaynaklar ─────────────────────────────────────────────
    private List<Map<String, Object>> buildTopUsers(List<AuditLog> window, Map<String, AppUser> usersByName) {
        Map<String, long[]> agg = new LinkedHashMap<>();   // actor → [logins]
        Map<String, String> last = new LinkedHashMap<>();  // actor → last login ts
        for (AuditLog a : window) {
            if (!"SUCCESS".equals(a.getOutcome()) || a.getActor() == null) continue;
            agg.computeIfAbsent(a.getActor(), k -> new long[1])[0]++;
            last.merge(a.getActor(), a.getEventTime(),
                    (cur, nw) -> nw != null && nw.compareTo(cur) > 0 ? nw : cur);
        }
        return agg.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]))
                .limit(TOP_N)
                .map(e -> {
                    AppUser u = usersByName.get(lc(e.getKey()));   // case-insensitive (actor küçük/büyük harf olabilir)
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("username",     e.getKey());
                    m.put("user_id",      u != null ? u.getId() : null);   // /api/admin/users/{id}/photo için
                    m.put("display_name", displayName(u));
                    m.put("logins",       e.getValue()[0]);
                    m.put("last_login",   last.get(e.getKey()));
                    return m;
                })
                .toList();
    }

    private List<Map<String, Object>> buildTopSources(List<AuditLog> window) {
        Map<String, long[]> agg = new LinkedHashMap<>();    // ip → [total, success, failed]
        Map<String, String[]> geo = new LinkedHashMap<>();  // ip → [country, city]
        Map<String, String> hosts = new LinkedHashMap<>();  // ip → reverse-DNS host (login anında kaydedilmiş)
        for (AuditLog a : window) {
            String ip = a.getIpAddress();
            if (ip == null || ip.isBlank()) continue;
            long[] c = agg.computeIfAbsent(ip, k -> new long[3]);
            c[0]++;
            if ("SUCCESS".equals(a.getOutcome())) c[1]++; else c[2]++;
            geo.computeIfAbsent(ip, k -> new String[]{a.getIpCountry(), a.getIpCity()});
            if (a.getIpReverseHost() != null && !hosts.containsKey(ip)) hosts.put(ip, a.getIpReverseHost());
        }
        return agg.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]))
                .limit(TOP_N)
                .map(e -> {
                    long[] c = e.getValue();
                    String[] g = geo.getOrDefault(e.getKey(), new String[]{null, null});
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ip",          e.getKey());
                    m.put("total",       c[0]);
                    m.put("success",     c[1]);
                    m.put("failed",      c[2]);
                    m.put("country",     g[0]);
                    m.put("city",        g[1]);
                    m.put("reverse_dns", hosts.get(e.getKey()));
                    return m;
                })
                .toList();
    }

    // ── Anomaliler ───────────────────────────────────────────────────────────────
    private Map<String, Object> buildAnomalies(List<AuditLog> window) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String k : ANOMALY_KINDS) counts.put(k, 0L);
        List<AuditLog> flagged = new ArrayList<>();
        for (AuditLog a : window) {
            if (a.getAnomalyFlags() == null || a.getAnomalyFlags().isBlank()) continue;
            flagged.add(a);
            for (String f : a.getAnomalyFlags().split(",")) {
                String key = f.trim();
                if (counts.containsKey(key)) counts.merge(key, 1L, Long::sum);
            }
        }
        List<Map<String, Object>> recent = flagged.stream()
                .sorted((x, y) -> nullSafe(y.getEventTime()).compareTo(nullSafe(x.getEventTime())))
                .limit(RECENT_ANOMALIES)
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("time",    a.getEventTime());
                    m.put("actor",   a.getActor());
                    m.put("ip",      a.getIpAddress());
                    m.put("country", a.getIpCountry());
                    m.put("city",    a.getIpCity());
                    m.put("outcome", a.getOutcome());
                    m.put("flags",   a.getAnomalyFlags());
                    m.put("reason",  a.getFailureReason());
                    return m;
                })
                .toList();
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", counts);
        out.put("total",  total);
        out.put("recent", recent);
        return out;
    }

    // ── Rol / Takım kırılımı (her rol/takım altında kimler login oldu — drill-down) ──
    private Map<String, Object> buildRoleTeam(List<AuditLog> window, Map<Long, String> teamNames,
                                              Map<String, AppUser> usersByName) {
        Map<String, Long> byRole = new LinkedHashMap<>();
        Map<Long, Long> byTeam = new LinkedHashMap<>();
        Map<String, Map<String, Long>> roleActors = new LinkedHashMap<>();  // role → actor → count
        Map<Long, Map<String, Long>> teamActors = new LinkedHashMap<>();    // teamId → actor → count
        Map<String, Long> nullTeamActors = new LinkedHashMap<>();
        long[] nullTeamCount = {0};
        for (AuditLog a : window) {
            if (!"SUCCESS".equals(a.getOutcome())) continue;
            String role = a.getActorRole() != null ? a.getActorRole() : "—";
            byRole.merge(role, 1L, Long::sum);
            if (a.getActor() != null)
                roleActors.computeIfAbsent(role, k -> new LinkedHashMap<>()).merge(a.getActor(), 1L, Long::sum);
            if (a.getActorTeamId() != null) {
                byTeam.merge(a.getActorTeamId(), 1L, Long::sum);
                if (a.getActor() != null)
                    teamActors.computeIfAbsent(a.getActorTeamId(), k -> new LinkedHashMap<>()).merge(a.getActor(), 1L, Long::sum);
            } else {
                nullTeamCount[0]++;
                if (a.getActor() != null) nullTeamActors.merge(a.getActor(), 1L, Long::sum);
            }
        }
        List<Map<String, Object>> roles = byRole.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role",  e.getKey());
                    m.put("count", e.getValue());
                    m.put("users", actorList(roleActors.get(e.getKey()), usersByName));
                    return m;
                })
                .toList();
        List<Map<String, Object>> teams = new ArrayList<>(byTeam.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("team_id",   e.getKey());
                    m.put("team_name", teamNames.get(e.getKey()));
                    m.put("count",     e.getValue());
                    m.put("users",     actorList(teamActors.get(e.getKey()), usersByName));
                    return m;
                })
                .toList());
        if (nullTeamCount[0] > 0) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", null);
            m.put("team_name", null);
            m.put("count", nullTeamCount[0]);
            m.put("users", actorList(nullTeamActors, usersByName));
            teams.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("by_role", roles);
        out.put("by_team", teams);
        return out;
    }

    /** username(küçük harf) → AppUser. Audit actor login'de yazıldığı gibi saklanır (örn. küçük harf
     *  "n64954"); AppUser.username ise kanonik olabilir (büyük harf "N64954"). Login case-insensitive
     *  eşleştiği için her iki yazım da giriş yapabilir → eşleştirmeyi de case-insensitive yapmalıyız,
     *  aksi halde küçük-harf login eden kullanıcının resmi/adı top-user & rol/takım listesinde çıkmaz. */
    private Map<String, AppUser> usersByName() {
        Map<String, AppUser> m = new HashMap<>();
        for (AppUser u : userRepo.findAll()) if (u.getUsername() != null) m.put(lc(u.getUsername()), u);
        return m;
    }

    /** Case-insensitive eşleştirme anahtarı (Locale.ROOT — Türkçe i/ı tuzağından kaçınmak için). */
    private static String lc(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }

    /** displayName → yoksa "Ad Soyad" → yoksa null. */
    private static String displayName(AppUser u) {
        if (u == null) return null;
        if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) return u.getDisplayName();
        String full = ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                     + (u.getLastName() != null ? u.getLastName() : "")).trim();
        return full.isEmpty() ? null : full;
    }

    /** actor→count haritasını [{username, user_id, display_name, count}] listesine (azalan) çevirir. */
    private List<Map<String, Object>> actorList(Map<String, Long> actors, Map<String, AppUser> usersByName) {
        if (actors == null || actors.isEmpty()) return List.of();
        return actors.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                .map(e -> {
                    AppUser u = usersByName.get(lc(e.getKey()));   // case-insensitive (actor küçük/büyük harf olabilir)
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("username",     e.getKey());
                    m.put("user_id",      u != null ? u.getId() : null);
                    m.put("display_name", displayName(u));
                    m.put("count",        e.getValue());
                    return m;
                })
                .toList();
    }

    // ── Peak ısı haritaları: bu hafta + 1 önceki + 2 önceki (her biri from/to + matrix/max/cells) ──
    // Pencereler YEREL gün sınırına hizalı (her biri 7 ayrı gün) — rolling olsa sınır günü aynı
    // hafta-gününü iki kez sayardı. week0 = bugün + önceki 6 gün; week1/2 = ondan önceki 7'şer gün.
    private List<Map<String, Object>> buildWeeklyHeatmaps() {
        ZonedDateTime todayStart = ZonedDateTime.now(ZONE).toLocalDate().atStartOfDay(ZONE);
        // ISO hafta: Pazartesi başlangıç → bu haftanın Pazartesi'si (bugün dahil geriye en yakın Pzt).
        ZonedDateTime mondayThisWeek = todayStart.with(
                java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        int todayDow = todayStart.getDayOfWeek().getValue() - 1;   // bugünün hafta-günü 0(Pzt)..6(Paz)
        String sinceK = ISO.format(mondayThisWeek.minusWeeks(3).toInstant());
        List<AuditLog> all = auditLogRepo.findLoginEventsSince(LOGIN_TYPES, sinceK);
        List<Map<String, Object>> out = new ArrayList<>(4);
        for (int w = 0; w < 4; w++) {   // bu hafta + 3 önceki = 4 hafta
            ZonedDateTime fromZ = mondayThisWeek.minusWeeks(w);    // o haftanın Pazartesi 00:00
            ZonedDateTime toZ   = fromZ.plusWeeks(1);              // sonraki Pazartesi (exclusive)
            String fromK = ISO.format(fromZ.toInstant());
            String toK   = ISO.format(toZ.toInstant());
            List<AuditLog> wk = new ArrayList<>();
            for (AuditLog a : all) {
                String t = a.getEventTime();
                if (t != null && t.compareTo(fromK) >= 0 && t.compareTo(toK) < 0) wk.add(a);
            }
            Map<String, Object> hm = buildHeatmap(wk);   // matrix / max / cells / totals
            hm.put("from", fromK);
            hm.put("to", toK);
            hm.put("today_dow", w == 0 ? todayDow : -1);   // yalnız bu hafta bugünü vurgula
            out.add(hm);
        }
        return out;
    }

    // ── Peak ısı haritası (hafta-günü × saat) ────────────────────────────────────
    private Map<String, Object> buildHeatmap(List<AuditLog> window) {
        long[][] grid   = new long[7][24]; // toplam login [weekday 0=Pzt..6=Paz][hour 0..23]
        long[][] failed = new long[7][24]; // başarısız (SUCCESS olmayan) login sayısı — hücreyi kırmızı yapar
        // Hücre detayları: "dow-hour" → o kovadaki login olayları (tıklayınca kullanıcıları göster).
        Map<String, List<Map<String, Object>>> cells = new LinkedHashMap<>();
        long max = 0;
        for (AuditLog a : window) {
            Instant t = parse(a.getEventTime());
            if (t == null) continue;
            ZonedDateTime z = t.atZone(ZONE);
            int dow = z.getDayOfWeek().getValue() - 1; // 1(Mon)..7(Sun) → 0..6
            int hour = z.getHour();
            long v = ++grid[dow][hour];
            if (v > max) max = v;
            if (!"SUCCESS".equals(a.getOutcome())) failed[dow][hour]++;
            List<Map<String, Object>> lst = cells.computeIfAbsent(dow + "-" + hour, k -> new ArrayList<>());
            if (lst.size() < HEATMAP_CELL_CAP) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time",    a.getEventTime());
                m.put("actor",   a.getActor());
                m.put("ip",      a.getIpAddress());
                m.put("country", a.getIpCountry());
                m.put("city",    a.getIpCity());
                m.put("outcome", a.getOutcome());
                m.put("reason",  a.getFailureReason());
                lst.add(m);
            }
        }
        long[] rowTotals = new long[7];    // gün başına toplam
        long[] colTotals = new long[24];   // saat başına toplam
        long total = 0;
        for (int d = 0; d < 7; d++)
            for (int h = 0; h < 24; h++) { rowTotals[d] += grid[d][h]; colTotals[h] += grid[d][h]; total += grid[d][h]; }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matrix", toMatrix(grid));
        out.put("failed", toMatrix(failed));
        out.put("max", max);
        out.put("cells", cells);
        out.put("row_totals", toLongList(rowTotals));   // gün başına toplam login (gün sonu)
        out.put("col_totals", toLongList(colTotals));   // saat başına toplam login
        out.put("total", total);                        // hafta toplamı
        return out;
    }

    private static List<Long> toLongList(long[] arr) {
        List<Long> l = new ArrayList<>(arr.length);
        for (long v : arr) l.add(v);
        return l;
    }

    private List<List<Long>> toMatrix(long[][] grid) {
        List<List<Long>> matrix = new ArrayList<>(grid.length);
        for (long[] row : grid) {
            List<Long> r = new ArrayList<>(row.length);
            for (long v : row) r.add(v);
            matrix.add(r);
        }
        return matrix;
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────────
    private Map<Long, String> teamNameMap() {
        Map<Long, String> m = new LinkedHashMap<>();
        for (Team t : teamRepo.findAll()) m.put(t.getId(), t.getName());
        return m;
    }

    private Map<String, Object> entryMap(String keyName, String keyVal, long count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(keyName, keyVal);
        m.put("count", count);
        return m;
    }

    /** SUCCESS=0, BLOCKED=2, diğer (FAILURE/null)=1. */
    private int outcomeIdx(AuditLog a) {
        String o = a.getOutcome();
        if ("SUCCESS".equals(o)) return 0;
        if ("BLOCKED".equals(o)) return 2;
        return 1;
    }

    private String tsOf(ZonedDateTime z) {
        return ISO.format(z.toInstant());
    }

    private String nullSafe(String s) {
        return s != null ? s : "";
    }

    private Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso.endsWith("Z") ? iso : iso + "Z");
        } catch (Exception e) {
            return null;
        }
    }
}
