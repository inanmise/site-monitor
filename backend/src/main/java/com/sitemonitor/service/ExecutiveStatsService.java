package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Predicate;

/**
 * İstatistik sayfası yönetici özeti (2026-09-12, zenginleştirme #20): dört KPI (filo sağlığı %,
 * 30 gün altı, açık alarm, SLA ihlali) + takım karşılaştırması + son 30 gün / önceki 30 gün delta
 * (açılan alarm). Kapsam çağıranın predicate'i. Her blok try/catch'li.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutiveStatsService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final int WINDOW_DAYS = 30;

    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final AlertEventRepository alertEventRepo;
    private final TeamRepository teamRepo;
    private final MonitorSparklineService sparklineService;
    private final AppSettingsService appSettings;
    private final com.sitemonitor.repository.CertificateCheckRepository certificateCheckRepo;

    public Map<String, Object> build(Predicate<Long> canViewTeam) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<Long, String> teamNames = new LinkedHashMap<>();
        try { teamRepo.findAll().forEach(t -> teamNames.put(t.getId(), t.getName())); } catch (Exception ignored) { }

        // ── Sertifikalar ─────────────────────────────────────────────────────────────────────
        Map<String, Object> certs = new LinkedHashMap<>();
        Map<Long, int[]> perTeam = new LinkedHashMap<>();   // [total, ok, under30, expired, error, openAlerts]
        Set<String> visibleDomains = new HashSet<>();
        Map<String, Long> domainTeam = new HashMap<>();
        try {
            List<CertificateInventory> active = inventoryRepo.findByActiveTrueOrderByDomainAsc();
            Map<String, LatestCheck> lc = new HashMap<>();
            latestCheckRepo.findAll().forEach(c -> { if (c.getDomain() != null) lc.put(c.getDomain(), c); });
            int total = 0, ok = 0, under30 = 0, under7 = 0, expired = 0, error = 0, unchecked = 0;
            for (CertificateInventory i : active) {
                if (!canViewTeam.test(i.getTeamId())) continue;
                visibleDomains.add(i.getDomain()); domainTeam.put(i.getDomain(), i.getTeamId());
                int[] tc = perTeam.computeIfAbsent(i.getTeamId() == null ? -1L : i.getTeamId(), k -> new int[6]);
                total++; tc[0]++;
                LatestCheck c = lc.get(i.getDomain());
                if (c == null || c.getDaysRemaining() == null) {
                    if (c != null && "error".equals(c.getStatus())) { error++; tc[4]++; } else unchecked++;
                    continue;
                }
                if ("error".equals(c.getStatus())) { error++; tc[4]++; continue; }
                int d = c.getDaysRemaining();
                if (d < 0) { expired++; tc[3]++; }
                else if (d <= WINDOW_DAYS) { under30++; tc[2]++; if (d <= 7) under7++; }
                else { ok++; tc[1]++; }
            }
            int evaluated = total - unchecked;
            certs.put("total", total); certs.put("ok", ok); certs.put("under30", under30); certs.put("under7", under7);
            certs.put("expired", expired); certs.put("error", error); certs.put("unchecked", unchecked);
            certs.put("health_pct", evaluated == 0 ? null : Math.round(1000.0 * ok / evaluated) / 10.0);
        } catch (Exception e) { log.debug("executive: sertifika bloğu düştü: {}", e.toString()); certs.put("error_msg", e.getClass().getSimpleName()); }
        out.put("certs", certs);

        // ── Alarmlar: açık + son 30 gün / önceki 30 gün açılan ───────────────────────────────
        Map<String, Object> alerts = new LinkedHashMap<>();
        try {
            int open = 0, critical = 0;
            for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
                if (!visible(e, canViewTeam, visibleDomains)) continue;
                open++;
                if ("CRITICAL".equalsIgnoreCase(e.getAlertLevel())) critical++;
                Long tid = e.getTeamId() != null ? e.getTeamId() : domainTeam.get(e.getDomain());
                int[] tc = perTeam.get(tid == null ? -1L : tid);
                if (tc != null) tc[5]++;
            }
            Instant now = Instant.now();
            String since60 = ISO.format(now.minus(2L * WINDOW_DAYS, ChronoUnit.DAYS));
            String since30 = ISO.format(now.minus(WINDOW_DAYS, ChronoUnit.DAYS));
            int last30 = 0, prev30 = 0;
            for (AlertEvent e : alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(since60)) {
                if (!visible(e, canViewTeam, visibleDomains) || e.getCreatedAt() == null) continue;
                if (e.getCreatedAt().compareTo(since30) >= 0) last30++; else prev30++;
            }
            alerts.put("open", open); alerts.put("critical", critical);
            alerts.put("opened_last30", last30); alerts.put("opened_prev30", prev30);
            alerts.put("delta", last30 - prev30);
        } catch (Exception e) { log.debug("executive: alarm bloğu düştü: {}", e.toString()); alerts.put("error_msg", e.getClass().getSimpleName()); }
        out.put("alerts", alerts);

        // ── SLA: hedef altı monitör sayısı (8 tür) ───────────────────────────────────────────
        Map<String, Object> sla = new LinkedHashMap<>();
        try {
            double target = appSettings.getDouble("site.monitor.sla.target-pct", 99.9);
            int monitors = 0, breaches = 0;
            List<Map<String, Object>> worst = new ArrayList<>();
            for (String type : MonitorSparklineService.KINDS.keySet()) {
                Map<Long, Long> teams = sparklineService.monitorTeams(type);
                Set<Long> ids = new HashSet<>();
                teams.forEach((id, tid) -> { if (canViewTeam.test(tid)) ids.add(id); });
                for (Map.Entry<Long, Map<String, Object>> en : sparklineService.availability(type, WINDOW_DAYS, ids).entrySet()) {
                    Object up = en.getValue().get("up_pct");
                    if (!(up instanceof Double d)) continue;
                    monitors++;
                    if (d < target) {
                        breaches++;
                        Map<String, Object> w = new LinkedHashMap<>();
                        w.put("type", type); w.put("id", en.getKey()); w.put("up_pct", d); w.put("bad_hours", en.getValue().get("bad_hours"));
                        worst.add(w);
                    }
                }
            }
            worst.sort(Comparator.comparingDouble(m -> (Double) m.get("up_pct")));
            sla.put("target_pct", target); sla.put("monitors", monitors); sla.put("breaches", breaches);
            sla.put("worst", worst.subList(0, Math.min(5, worst.size())));
        } catch (Exception e) { log.debug("executive: SLA bloğu düştü: {}", e.toString()); sla.put("error_msg", e.getClass().getSimpleName()); }
        out.put("sla", sla);

        // ── Takım karşılaştırması ─────────────────────────────────────────────────────────────
        List<Map<String, Object>> teams = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : perTeam.entrySet()) {
            int[] c = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", e.getKey() == -1L ? null : e.getKey());
            m.put("team_name", e.getKey() == -1L ? null : teamNames.getOrDefault(e.getKey(), "#" + e.getKey()));
            m.put("total", c[0]); m.put("ok", c[1]); m.put("under30", c[2]); m.put("expired", c[3]); m.put("error", c[4]); m.put("open_alerts", c[5]);
            int evaluated = c[1] + c[2] + c[3] + c[4];
            m.put("health_pct", evaluated == 0 ? null : Math.round(1000.0 * c[1] / evaluated) / 10.0);
            teams.add(m);
        }
        teams.sort((a, b) -> {
            Double ha = (Double) a.get("health_pct"), hb = (Double) b.get("health_pct");
            if (ha == null || hb == null) return ha == null ? (hb == null ? 0 : 1) : -1;
            return Double.compare(ha, hb);   // en kötü üstte
        });
        out.put("teams", teams);
        out.put("window_days", WINDOW_DAYS);
        out.put("generated_at", ISO.format(Instant.now()));
        return out;
    }

    /**
     * "Son N günde ne değişti" satırı (2026-09-12, #7): yeni alan, silinen alan, yenilenen sertifika (parmak izi
     * değişimi), açılan / çözülen alarm. Dashboard istatistik şeridinin altında tek satır.
     */
    public Map<String, Object> recentChanges(int days, Predicate<Long> canViewTeam) {
        int d = Math.max(1, Math.min(90, days));
        String since = ISO.format(Instant.now().minus(d, ChronoUnit.DAYS));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        Set<String> visibleDomains = new HashSet<>();
        int added = 0, removed = 0;
        try {
            for (CertificateInventory i : inventoryRepo.findAllByOrderByDomainAsc()) {
                if (!canViewTeam.test(i.getTeamId())) continue;
                if (i.getDeletedAt() != null) { if (i.getDeletedAt().compareTo(since) >= 0) removed++; continue; }
                visibleDomains.add(i.getDomain());
                if (i.getCreatedAt() != null && i.getCreatedAt().compareTo(since) >= 0) added++;
            }
        } catch (Exception e) { log.debug("changes: envanter düştü: {}", e.toString()); }
        int renewed = 0;
        try { for (String dom : certificateCheckRepo.domainsWithFingerprintChangeSince(since)) if (visibleDomains.contains(dom)) renewed++; }
        catch (Exception e) { log.debug("changes: parmak izi sorgusu düştü: {}", e.toString()); }
        int opened = 0, resolved = 0;
        try {
            for (AlertEvent e : alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(since)) {
                if (!visible(e, canViewTeam, visibleDomains) || e.getCreatedAt() == null || e.getCreatedAt().compareTo(since) < 0) continue;
                opened++;
                if (Boolean.TRUE.equals(e.getResolved()) && e.getResolvedAt() != null && e.getResolvedAt().compareTo(since) >= 0) resolved++;
            }
        } catch (Exception e) { log.debug("changes: alarm sorgusu düştü: {}", e.toString()); }
        out.put("added", added); out.put("removed", removed); out.put("renewed", renewed);
        out.put("alerts_opened", opened); out.put("alerts_resolved", resolved);
        return out;
    }

    private static boolean visible(AlertEvent e, Predicate<Long> canViewTeam, Set<String> domains) {
        return (e.getTeamId() != null && canViewTeam.test(e.getTeamId())) || (e.getDomain() != null && domains.contains(e.getDomain()));
    }
}
