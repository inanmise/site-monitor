package com.certmonitor.service;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.AuditLog;
import com.certmonitor.model.Team;
import com.certmonitor.repository.AppUserRepository;
import com.certmonitor.repository.AuditLogRepository;
import com.certmonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final long DAY_SECONDS = 86_400L;

    private enum Gran { DAY, HOUR, MINUTE }

    /** Tek payload — frontend tek çağrı yapar, mevcut 30 sn yenilemeye bağlanır. */
    public Map<String, Object> getOverview() {
        String since7d = ISO.format(Instant.now().minusSeconds(7 * DAY_SECONDS));
        List<AuditLog> window = auditLogRepo.findLoginEventsSince(LOGIN_TYPES, since7d);
        Map<Long, String> teamNames = teamNameMap();

        List<Map<String, Object>> activeUsers = buildActiveUsers(teamNames);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary",      buildSummary(window, activeUsers.size()));
        out.put("active_users", activeUsers);
        out.put("series",       buildSeries(window));
        out.put("top_users",    buildTopUsers(window));
        out.put("top_sources",  buildTopSources(window));
        out.put("anomalies",    buildAnomalies(window));
        out.put("role_team",    buildRoleTeam(window, teamNames));
        out.put("heatmap",      buildHeatmap(window));
        return out;
    }

    // ── Summary ────────────────────────────────────────────────────────────────
    private Map<String, Object> buildSummary(List<AuditLog> window, int activeCount) {
        String s24 = ISO.format(Instant.now().minusSeconds(DAY_SECONDS));
        long s24Logins = 0, s24Failed = 0, s24Anom = 0;
        long d7Logins = 0, d7Failed = 0, d7Anom = 0;
        java.util.Set<String> users24 = new java.util.HashSet<>();
        java.util.Set<String> users7d = new java.util.HashSet<>();

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
            rows.add(m);
        }
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
    private List<Map<String, Object>> buildTopUsers(List<AuditLog> window) {
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
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("username",   e.getKey());
                    m.put("logins",     e.getValue()[0]);
                    m.put("last_login", last.get(e.getKey()));
                    return m;
                })
                .toList();
    }

    private List<Map<String, Object>> buildTopSources(List<AuditLog> window) {
        Map<String, long[]> agg = new LinkedHashMap<>();    // ip → [total, success, failed]
        Map<String, String[]> geo = new LinkedHashMap<>();  // ip → [country, city]
        for (AuditLog a : window) {
            String ip = a.getIpAddress();
            if (ip == null || ip.isBlank()) continue;
            long[] c = agg.computeIfAbsent(ip, k -> new long[3]);
            c[0]++;
            if ("SUCCESS".equals(a.getOutcome())) c[1]++; else c[2]++;
            geo.computeIfAbsent(ip, k -> new String[]{a.getIpCountry(), a.getIpCity()});
        }
        return agg.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]))
                .limit(TOP_N)
                .map(e -> {
                    long[] c = e.getValue();
                    String[] g = geo.getOrDefault(e.getKey(), new String[]{null, null});
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ip",       e.getKey());
                    m.put("total",    c[0]);
                    m.put("success",  c[1]);
                    m.put("failed",   c[2]);
                    m.put("country",  g[0]);
                    m.put("city",     g[1]);
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

    // ── Rol / Takım kırılımı ─────────────────────────────────────────────────────
    private Map<String, Object> buildRoleTeam(List<AuditLog> window, Map<Long, String> teamNames) {
        Map<String, Long> byRole = new LinkedHashMap<>();
        Map<Long, Long> byTeam = new LinkedHashMap<>();
        boolean[] hasNullTeam = {false};
        long[] nullTeamCount = {0};
        for (AuditLog a : window) {
            if (!"SUCCESS".equals(a.getOutcome())) continue;
            String role = a.getActorRole() != null ? a.getActorRole() : "—";
            byRole.merge(role, 1L, Long::sum);
            if (a.getActorTeamId() != null) byTeam.merge(a.getActorTeamId(), 1L, Long::sum);
            else { hasNullTeam[0] = true; nullTeamCount[0]++; }
        }
        List<Map<String, Object>> roles = byRole.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                .map(e -> entryMap("role", e.getKey(), e.getValue()))
                .toList();
        List<Map<String, Object>> teams = new ArrayList<>(byTeam.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("team_id",   e.getKey());
                    m.put("team_name", teamNames.get(e.getKey()));
                    m.put("count",     e.getValue());
                    return m;
                })
                .toList());
        if (hasNullTeam[0]) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", null);
            m.put("team_name", null);
            m.put("count", nullTeamCount[0]);
            teams.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("by_role", roles);
        out.put("by_team", teams);
        return out;
    }

    // ── Peak ısı haritası (hafta-günü × saat) ────────────────────────────────────
    private Map<String, Object> buildHeatmap(List<AuditLog> window) {
        long[][] grid = new long[7][24]; // [weekday 0=Pzt..6=Paz][hour 0..23]
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
            List<Map<String, Object>> lst = cells.computeIfAbsent(dow + "-" + hour, k -> new ArrayList<>());
            if (lst.size() < HEATMAP_CELL_CAP) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time",    a.getEventTime());
                m.put("actor",   a.getActor());
                m.put("ip",      a.getIpAddress());
                m.put("country", a.getIpCountry());
                m.put("city",    a.getIpCity());
                m.put("outcome", a.getOutcome());
                lst.add(m);
            }
        }
        List<List<Long>> matrix = new ArrayList<>(7);
        for (long[] row : grid) {
            List<Long> r = new ArrayList<>(24);
            for (long v : row) r.add(v);
            matrix.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matrix", matrix);
        out.put("max", max);
        out.put("cells", cells);
        return out;
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
