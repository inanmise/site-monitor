package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.AlertTeamStatsService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.util.TtlMemo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Nöbet rutini görevlerinin CANLI SAYAÇLARI (2026-11-26, paket B). Sabit anahtarlar ({@link #KEYS}, kalıcı, bu sırayla);
 * her anahtar var olan bir kaynağı yeniden kullanır (alarm takım istatistiği, İzleme Panosu özeti, sertifika listesi; olay
 * ailesi için üç gruplu sayım). Yalnız takımın ETKİN görevlerinin başvurduğu sayaçlar hesaplanır. İzin yoksa
 * {@code hidden}, okuma düşerse {@code error:"unavailable"} — bilinmeyen ASLA yeşil (0) değildir. Kişi verisi yok, yalnız sayı.
 */
@Slf4j
@Service
public class OnCallRoutineSignals {

    /** Kalıcı anahtarlar (bu sırayla; ön yüz bu listeyi okur). */
    public static final List<String> KEYS = List.of(
            "open_alerts", "down_monitors", "stale_monitors", "open_incidents", "open_postmortems", "overdue_actions",
            "expiring_certs");

    /** Anahtar → gereken izin (kaynak, eylem). */
    static final Map<String, String[]> PERMS = new LinkedHashMap<>();
    static {
        PERMS.put("open_alerts", new String[] {"alerts.read", "view"});
        PERMS.put("down_monitors", new String[] {"monitoring.read", "view"});
        PERMS.put("stale_monitors", new String[] {"monitoring.read", "view"});
        PERMS.put("open_incidents", new String[] {"incidents.view", "view"});
        PERMS.put("open_postmortems", new String[] {"incidents.view", "view"});
        PERMS.put("overdue_actions", new String[] {"incidents.view", "view"});
        PERMS.put("expiring_certs", new String[] {"inventory.list", "view"});
    }

    private final JdbcTemplate jdbc;
    private final TtlMemo<Map<String, Map<Long, Integer>>> memo = new TtlMemo<>(4);
    /** Kurum geneli takım → 30 gün içinde süresi dolan sertifika sayısı (envanter + son kontroller TEK kez okunur). */
    private final TtlMemo<Map<Long, Integer>> certMemo = new TtlMemo<>(2);

    @Autowired(required = false) AlertTeamStatsService alertStats;
    @Autowired(required = false) MonitoringOverviewService overview;
    @Autowired(required = false) CertificateService certificates;
    @Autowired(required = false) CertificateInventoryRepository inventory;

    @Value("${site.monitor.oncall-routine.signals-cache-ms:30000}")
    long cacheMs = 30_000;

    public OnCallRoutineSignals(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ön yüz için sayaç tanımları ({@code /template} yanıtı). {@code hidden} = bu oturum izinsiz. */
    public static List<Map<String, Object>> defs(BiPredicate<String, String> allows) {
        java.util.ArrayList<Map<String, Object>> out = new java.util.ArrayList<>();
        for (String k : KEYS) {
            Map<String, Object> m = new LinkedHashMap<>();
            String[] p = PERMS.get(k);
            m.put("key", k);
            m.put("perm", p[0]);
            m.put("hidden", !allows.test(p[0], p[1]));
            out.add(m);
        }
        return out;
    }

    /**
     * Takımın istenen sayaçları. {@code allows} = oturumun izin sınaması (kaynak, eylem); {@code globalViewer} alarm
     * istatistiğinin kurum geneli kipi içindir (takım süzgeci yine yalnız bu takım).
     */
    public Map<String, Object> compute(Long teamId, Set<String> wanted, BiPredicate<String, String> allows, boolean globalViewer,
                                       boolean fresh, LocalDate today) {
        Map<String, Object> signals = new LinkedHashMap<>();
        Map<String, Map<Long, Integer>> incidents = null;
        boolean incidentsTried = false;
        Map<String, Object> overviewData = null;
        boolean overviewTried = false;
        for (String key : KEYS) {
            if (!wanted.contains(key)) continue;
            String[] perm = PERMS.get(key);
            if (!allows.test(perm[0], perm[1])) {
                signals.put(key, hidden());
                continue;
            }
            Integer value = null;
            boolean partial = false;
            String error = null;
            try {
                switch (key) {
                    case "open_alerts" -> value = openAlerts(teamId, globalViewer, fresh);
                    case "down_monitors", "stale_monitors" -> {
                        if (!overviewTried) {
                            overviewTried = true;
                            overviewData = overview == null ? null : overview.orgOverview(fresh ? 5_000L : cacheMs);
                        }
                        if (overviewData == null) throw new IllegalStateException("overview");
                        value = monitorCount(overviewData, teamId, "down_monitors".equals(key) ? "down" : "stale");
                        partial = overviewPartial(overviewData);
                    }
                    case "open_incidents", "open_postmortems", "overdue_actions" -> {
                        if (!incidentsTried) {
                            incidentsTried = true;
                            incidents = memo.get("ORG", fresh ? 5_000L : cacheMs, false, () -> incidentCounts(today));
                        }
                        if (incidents == null) throw new IllegalStateException("incidents");
                        value = incidents.getOrDefault(key, Map.of()).getOrDefault(teamId, 0);
                    }
                    case "expiring_certs" -> value = expiringCerts(teamId, fresh);
                    default -> { }
                }
            } catch (RuntimeException e) {
                log.debug("Nöbet rutini sayacı okunamadı ({} · takım {}): {}", key, teamId, e.toString());
                value = null;
                error = "unavailable";
            }
            signals.put(key, signal(key, teamId, value, partial, error));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("team_id", teamId);
        out.put("generated_at", Instant.now().toString());
        out.put("signals", signals);
        return out;
    }

    private Integer openAlerts(Long teamId, boolean globalViewer, boolean fresh) {
        if (alertStats == null) throw new IllegalStateException("alert stats");
        Map<String, Object> data = alertStats.build(t -> teamId.equals(t), globalViewer, fresh);
        Object rows = data.get("teams");
        if (rows instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && teamId.equals(asLong(m.get("team_id")))) {
                    Object v = m.get("open");
                    return v instanceof Number n ? n.intValue() : 0;
                }
            }
        }
        return 0;
    }

    static int monitorCount(Map<String, Object> data, Long teamId, String status) {
        int n = 0;
        if (data.get("monitors") instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> m)) continue;
                if (!teamId.equals(asLong(m.get("team_id")))) continue;
                if (!Boolean.TRUE.equals(m.get("active")) || Boolean.TRUE.equals(m.get("deleted"))) continue;
                if (status.equals(m.get("status"))) n++;
            }
        }
        return n;
    }

    static boolean overviewPartial(Map<String, Object> data) {
        if (data.get("types") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && (Boolean.TRUE.equals(m.get("unavailable")) || Boolean.TRUE.equals(m.get("checks_unavailable")))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Olay ailesinin kurum geneli gruplu sayıları (3 sorgu). Okuma hatası fırlatır (bellek saklamaz). */
    Map<String, Map<Long, Integer>> incidentCounts(LocalDate today) {
        Map<String, Map<Long, Integer>> out = new HashMap<>();
        out.put("open_incidents", grouped("SELECT team_id, COUNT(*) FROM incident_records WHERE team_id IS NOT NULL "
                + "AND (status IS NULL OR status <> 'RESOLVED') GROUP BY team_id"));
        out.put("open_postmortems", grouped("SELECT r.team_id, COUNT(*) FROM incident_postmortems p JOIN incident_records r "
                + "ON r.id = p.incident_id WHERE r.team_id IS NOT NULL AND p.status IN ('DRAFT', 'IN_REVIEW') GROUP BY r.team_id"));
        out.put("overdue_actions", grouped("SELECT r.team_id, COUNT(*) FROM incident_action_items a JOIN incident_records r "
                + "ON r.id = a.incident_id WHERE r.team_id IS NOT NULL AND a.status IN ('OPEN', 'IN_PROGRESS') "
                + "AND a.due_date IS NOT NULL AND a.due_date < '" + today + "' GROUP BY r.team_id"));
        return out;
    }

    private Map<Long, Integer> grouped(String sql) {
        Map<Long, Integer> m = new HashMap<>();
        jdbc.query(sql, (java.sql.ResultSet rs) -> {
            Object t = rs.getObject(1);
            if (t instanceof Number n) m.put(n.longValue(), rs.getInt(2));
        });
        return m;
    }

    /**
     * Takımın 30 gün içinde süresi dolan sertifikaları (SY ya da UG). Kurum geneli takım → sayı haritası {@code cacheMs}
     * boyunca bellekte (inceleme 2026-11-26 #4: her yoklama tüm envanteri okuyordu); takım süzgeci her çağrıda bellekte.
     * Okuma hatası fırlatır (bellek saklamaz).
     */
    private Integer expiringCerts(Long teamId, boolean fresh) {
        if (certificates == null || inventory == null) throw new IllegalStateException("certificates");
        Map<Long, Integer> byTeam = certMemo.get("ORG", fresh ? 5_000L : cacheMs, false, this::expiringByTeam);
        return byTeam.getOrDefault(teamId, 0);
    }

    Map<Long, Integer> expiringByTeam() {
        Map<String, Set<Long>> owners = new HashMap<>();
        for (Object[] r : inventory.findActiveDomainTeams()) {
            if (r == null || r.length < 2 || r[0] == null) continue;
            String domain = String.valueOf(r[0]).toLowerCase(java.util.Locale.ROOT);
            Long sy = asLong(r[1]);
            Long ug = r.length > 2 ? asLong(r[2]) : null;
            Set<Long> t = owners.computeIfAbsent(domain, k -> new HashSet<>(2));
            if (sy != null) t.add(sy);
            if (ug != null) t.add(ug);
        }
        Map<Long, Integer> out = new HashMap<>();
        if (owners.isEmpty()) return out;
        for (CertificateDto c : certificates.getAllLatest()) {
            if (c == null || c.getDomain() == null || c.getDaysRemaining() == null || c.getDaysRemaining() > 30) continue;
            Set<Long> t = owners.get(c.getDomain().toLowerCase(java.util.Locale.ROOT));
            if (t == null) continue;
            for (Long id : t) out.merge(id, 1, Integer::sum);
        }
        return out;
    }

    private static Map<String, Object> hidden() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", null);
        m.put("tone", "neutral");
        m.put("partial", false);
        m.put("hidden", true);
        m.put("error", null);
        m.put("link", null);
        return m;
    }

    static Map<String, Object> signal(String key, Long teamId, Integer value, boolean partial, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        // Kısmi okumada 0 kanıt değildir (okunamayan türde sorun olabilir) → nötr; > 0 ise alt sınır, sorun kanıtlı.
        m.put("tone", value == null ? "neutral" : value == 0 ? (partial ? "neutral" : "ok")
                : "down_monitors".equals(key) ? "bad" : "warn");
        m.put("partial", partial);
        m.put("hidden", false);
        m.put("error", error);
        m.put("link", link(key, teamId));
        return m;
    }

    static Map<String, Object> link(String key, Long teamId) {
        String t = String.valueOf(teamId);
        Map<String, Object> params = new LinkedHashMap<>();
        String tab;
        switch (key) {
            case "open_alerts" -> {
                tab = "alerthistory";
                params.put("view", "open");
                params.put("team", t);
            }
            case "down_monitors" -> {
                tab = "monitoring";
                params.put("mo_team", t);
                params.put("mo_status", "down");
            }
            case "stale_monitors" -> {
                tab = "monitoring";
                params.put("mo_team", t);
                params.put("mo_status", "stale");
            }
            case "open_incidents" -> {
                tab = "incident-history";
                params.put("ih_team", t);
                params.put("ih_open", "1");
            }
            case "open_postmortems" -> {
                tab = "incident-history";
                params.put("ih_team", t);
            }
            case "overdue_actions" -> {
                tab = "incident-history";
                params.put("ih_view", "actions");
                params.put("ih_aover", "1");
            }
            default -> {
                tab = "all";
                params.put("c_win", "30");
            }
        }
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("tab", tab);
        l.put("params", params);
        return l;
    }

    static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
