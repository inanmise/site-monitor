package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.function.Predicate;

/**
 * "Sizin için — bugün" paneli (2026-09-12, zenginleştirme #3): giriş sonrası ilk ekranda takımın
 * ilgilenmesi gerekenler. Kartlar: 30 gün altı sertifika · açık alarm · bu haftanın haftalık raporu ·
 * ve dört İZLEME kartı (2026-09-19, zayıf-algoritma istisnası kartının yerine, kullanıcı seçimi):
 * kararsız · yavaşlayan · sessiz/bayat · alan adı kaydı dolan ({@link TodayMonitorInsightsService},
 * takım-bağımsız 60 sn önbellek; görünürlük BURADA satır bazında süzülür).
 * Kapsam çağıranın predicate'i (takım görünürlüğü). Her kart try/catch'li — biri düşerse panel yine çizilir.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TodayPanelService {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    static final int CERT_DAYS = 30;
    public static final int TOP = 5;   // kart başına satır (pop-up tavansız: build(..., limit))

    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final AlertEventRepository alertEventRepo;
    private final WeeklyReportRepository weeklyReportRepo;
    private final TeamRepository teamRepo;
    private final TodayMonitorInsightsService monitorInsights;

    public Map<String, Object> build(Predicate<Long> canViewTeam, List<Long> ownTeamIds) {
        return build(canViewTeam, ownTeamIds, TOP);
    }

    /**
     * {@code limit}: kart başına satır tavanı — panel {@link #TOP}, "Tümünü gör" pop-up'ı {@code Integer.MAX_VALUE}
     * (2026-09-18: kullanıcı listenin tamamını sayfada değil pop-up'ta, sayfalı görmek istedi).
     */
    public Map<String, Object> build(Predicate<Long> canViewTeam, List<Long> ownTeamIds, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<CertificateInventory> visible = new ArrayList<>();
        try {
            for (CertificateInventory i : inventoryRepo.findByActiveTrueOrderByDomainAsc())
                if (canViewTeam.test(i.getTeamId())) visible.add(i);
        } catch (Exception e) { log.debug("today: envanter okunamadı: {}", e.toString()); }
        Set<String> visibleDomains = new HashSet<>();
        Map<String, Long> domainTeam = new HashMap<>();
        for (CertificateInventory i : visible) { visibleDomains.add(i.getDomain()); domainTeam.put(i.getDomain(), i.getTeamId()); }
        Map<Long, String> teamNames = new HashMap<>();
        try { teamRepo.findAll().forEach(t -> teamNames.put(t.getId(), t.getName())); } catch (Exception ignored) { }

        out.put("certs", safe(() -> certs(visibleDomains, domainTeam, teamNames, limit)));
        out.put("alerts", safe(() -> alerts(canViewTeam, visibleDomains, teamNames, limit)));
        out.put("weekly", safe(() -> weekly(ownTeamIds, teamNames)));
        // İzleme kartları: tüm-takım anlık görüntü (önbellekli) → görünürlük süzgeci → tavan.
        TodayMonitorInsightsService.Snapshot snap;
        try { snap = monitorInsights.snapshot(); }
        catch (Exception e) { log.debug("today: izleme anlık görüntüsü alınamadı: {}", e.toString()); snap = null; }
        final TodayMonitorInsightsService.Snapshot sn = snap;
        out.put("flapping", safe(() -> monitorBlock(visibleRows(sn == null ? List.of() : sn.flapping(), canViewTeam, visibleDomains, teamNames), limit, Map.of())));
        out.put("slow", safe(() -> monitorBlock(visibleRows(sn == null ? List.of() : sn.slow(), canViewTeam, visibleDomains, teamNames), limit, Map.of())));
        out.put("stale", safe(() -> monitorBlock(visibleRows(sn == null ? List.of() : sn.stale(), canViewTeam, visibleDomains, teamNames), limit,
                Map.of("paused", sn == null ? 0 : sn.paused()))));
        out.put("domains", safe(() -> {
            List<Map<String, Object>> rows = visibleRows(sn == null ? List.of() : sn.domains(), canViewTeam, visibleDomains, teamNames);
            long expired = rows.stream().filter(r -> ((Number) r.get("days")).intValue() < 0).count();
            return monitorBlock(rows, limit, Map.of("expired", expired));
        }));
        out.put("scope_domains", visibleDomains.size());
        return out;
    }

    private interface Block { Map<String, Object> run(); }
    private static Map<String, Object> safe(Block b) {
        try { return b.run(); }
        catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>(); m.put("error", e.getClass().getSimpleName()); m.put("count", 0); m.put("items", List.of());
            return m;
        }
    }

    /** 30 gün altı (ve dolmuş) sertifikalar — en az gün üstte. */
    private Map<String, Object> certs(Set<String> domains, Map<String, Long> domainTeam, Map<Long, String> teamNames, int limit) {
        List<Map<String, Object>> items = new ArrayList<>();
        int expired = 0;
        for (LatestCheck lc : latestCheckRepo.findAll()) {
            if (lc.getDomain() == null || !domains.contains(lc.getDomain()) || lc.getDaysRemaining() == null) continue;
            if (lc.getDaysRemaining() > CERT_DAYS) continue;
            if (lc.getDaysRemaining() < 0) expired++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("domain", lc.getDomain()); m.put("days", lc.getDaysRemaining()); m.put("not_after", lc.getNotAfter());
            Long tid = domainTeam.get(lc.getDomain());
            m.put("team_id", tid); m.put("team_name", tid == null ? null : teamNames.get(tid));
            items.add(m);
        }
        items.sort(Comparator.comparingInt(m -> (Integer) m.get("days")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size()); out.put("expired", expired); out.put("items", items.subList(0, Math.min(limit, items.size())));
        return out;
    }

    /** Açık alarmlar — takımı görünür olan ya da alanı görünür envanterde olan. */
    private Map<String, Object> alerts(Predicate<Long> canViewTeam, Set<String> domains, Map<Long, String> teamNames, int limit) {
        List<Map<String, Object>> items = new ArrayList<>();
        int critical = 0;
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            boolean vis = (e.getTeamId() != null && canViewTeam.test(e.getTeamId())) || (e.getDomain() != null && domains.contains(e.getDomain()));
            if (!vis) continue;
            if ("CRITICAL".equalsIgnoreCase(e.getAlertLevel())) critical++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId()); m.put("domain", e.getDomain()); m.put("type", e.getAlertType()); m.put("level", e.getAlertLevel());
            m.put("since", e.getCreatedAt()); m.put("acknowledged", Boolean.TRUE.equals(e.getAcknowledged()));
            m.put("team_id", e.getTeamId()); m.put("team_name", e.getTeamId() == null ? null : teamNames.get(e.getTeamId()));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size()); out.put("critical", critical); out.put("items", items.subList(0, Math.min(limit, items.size())));
        return out;
    }

    /**
     * İzleme kartı süzgeci — alarm kartıyla AYNI görünürlük kuralı: takımı görünür OLAN ya da (takımsız,
     * envanter-türevi DNS/Port izlemelerinde) hedefi görünür envanterde olan satır. Önbellekteki satır
     * DEĞİŞTİRİLMEZ (kopyalanır; team_name burada eklenir).
     */
    static List<Map<String, Object>> visibleRows(List<Map<String, Object>> all, Predicate<Long> canViewTeam,
                                                 Set<String> domains, Map<Long, String> teamNames) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : all) {
            Long tid = r.get("team_id") == null ? null : ((Number) r.get("team_id")).longValue();
            String domain = (String) r.get("domain");
            boolean vis = (tid != null && canViewTeam.test(tid)) || (domain != null && domains.contains(domain));
            if (!vis) continue;
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("team_name", tid == null ? null : teamNames.get(tid));
            items.add(m);
        }
        return items;
    }

    /** count + ek sayaçlar + tavanlı items (sıralama anlık görüntüden gelir). */
    static Map<String, Object> monitorBlock(List<Map<String, Object>> items, int limit, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size()); out.putAll(extra);
        out.put("items", items.subList(0, Math.min(limit, items.size())));
        return out;
    }

    /** Bu ISO haftasının raporu — kullanıcının KENDİ takımları için (görüntüleme kapsamı değil; rapor girme sorumluluğu). */
    private Map<String, Object> weekly(List<Long> ownTeamIds, Map<Long, String> teamNames) {
        LocalDate today = LocalDate.now(IST);
        int year = today.get(WeekFields.ISO.weekBasedYear()), week = today.get(WeekFields.ISO.weekOfWeekBasedYear());
        List<Map<String, Object>> items = new ArrayList<>();
        int missing = 0;
        for (Long tid : ownTeamIds == null ? List.<Long>of() : ownTeamIds) {
            if (tid == null) continue;
            // Modül o takımda kapalıysa kart da yok (2026-09-16): takım Haftalık Raporlar sayfasını
            // görmüyorsa "bu hafta raporun eksik" demenin karşılığı da yok.
            if (!teamRepo.findById(tid).map(t -> Boolean.TRUE.equals(t.getWeeklyReportsEnabled())).orElse(false)) continue;
            Optional<WeeklyReport> r = weeklyReportRepo.findByTeamIdAndReportYearAndWeekNo(tid, year, week);
            String status = r.map(WeeklyReport::getStatus).orElse("MISSING");
            if ("MISSING".equals(status) || "DRAFT".equals(status) || "REJECTED".equals(status)) missing++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", tid); m.put("team_name", teamNames.get(tid)); m.put("status", status); m.put("report_id", r.map(WeeklyReport::getId).orElse(null));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year); out.put("week", week); out.put("count", items.size()); out.put("missing", missing); out.put("items", items);
        return out;
    }
}
