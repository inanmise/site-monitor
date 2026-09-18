package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.WeakAlgorithmException;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeakAlgorithmExceptionRepository;
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
 * ilgilenmesi gerekenler. Dört kart: 30 gün altı sertifika · açık alarm · süresi dolan/dolmak üzere
 * zayıf-algoritma istisnası · bu haftanın haftalık raporu. Kapsam çağıranın predicate'i (takım görünürlüğü).
 * Her kart try/catch'li — biri düşerse panel yine çizilir.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TodayPanelService {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    static final int CERT_DAYS = 30, EXCEPTION_SOON_DAYS = 14;
    public static final int TOP = 5;   // kart başına satır (pop-up tavansız: build(..., limit))

    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final AlertEventRepository alertEventRepo;
    private final WeakAlgorithmExceptionRepository exceptionRepo;
    private final WeeklyReportRepository weeklyReportRepo;
    private final TeamRepository teamRepo;

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
        out.put("exceptions", safe(() -> exceptions(visibleDomains, domainTeam, teamNames, limit)));
        out.put("weekly", safe(() -> weekly(ownTeamIds, teamNames)));
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

    /** Zayıf-algoritma istisnaları: süresi dolmuş ya da 14 gün içinde dolacak. */
    private Map<String, Object> exceptions(Set<String> domains, Map<String, Long> domainTeam, Map<Long, String> teamNames, int limit) {
        LocalDate today = LocalDate.now(IST);
        List<Map<String, Object>> items = new ArrayList<>();
        int expired = 0;
        for (WeakAlgorithmException ex : exceptionRepo.findAll()) {
            if (ex.getDomain() == null || !domains.contains(ex.getDomain()) || ex.getUntil() == null) continue;
            LocalDate until;
            try { until = LocalDate.parse(ex.getUntil()); } catch (Exception e) { continue; }
            long left = java.time.temporal.ChronoUnit.DAYS.between(today, until);
            if (left > EXCEPTION_SOON_DAYS) continue;
            if (left < 0) expired++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("domain", ex.getDomain()); m.put("until", ex.getUntil()); m.put("days", left); m.put("reason", ex.getReason());
            Long tid = domainTeam.get(ex.getDomain());
            m.put("team_id", tid); m.put("team_name", tid == null ? null : teamNames.get(tid));
            items.add(m);
        }
        items.sort(Comparator.comparingLong(m -> (Long) m.get("days")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size()); out.put("expired", expired); out.put("items", items.subList(0, Math.min(limit, items.size())));
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
