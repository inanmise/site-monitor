package com.certmonitor.service;

import com.certmonitor.model.CertificateInventory;
import com.certmonitor.model.LatestCheck;
import com.certmonitor.repository.AlertEventRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.LatestCheckRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Haftalık rapor executive KPI şeridi — takım-kapsamlı, ISO-hafta-pencereli, YALNIZ-OKUMA toplama.
 *
 * <p>Rapor durum makinesine (DRAFT→PENDING→APPROVED) ve token onay akışına DOKUNMAZ; canlı cert/alarm/uptime
 * verisini mevcut servislerden derler:
 * <ul>
 *   <li>cert sayıları → {@link CertificateService#getStatsForTeams}</li>
 *   <li>bu hafta açılan alarm → {@link AlertEventRepository#countFilteredByType} (kapsam + pencere sorgu içinde)</li>
 *   <li>uptime % → {@link WeeklyAvailabilityReportService#weeklyUptime} (computeRow/summarize çekirdeği)</li>
 * </ul>
 * Kapsam = raporun SY {@code team_id}'si (UG hariç — uptime servisiyle aynı payda). Delta, ÖNCEKİ ISO haftanın
 * canlı metriğinden gelir; snapshot kartların (toplam cert, kritik≤7) geçmiş değeri olmadığından delta'sız gösterilir.
 * {@code open-in-view=false} → toplama tek {@code @Transactional(readOnly=true)} içinde materialize edilir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyReportKpiService {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");

    private final CertificateService certificateService;
    private final AlertEventRepository alertEventRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final WeeklyAvailabilityReportService availabilityService;

    /** Tek haftanın KPI kümesi. Windowed alanlar (expiring/renewed/alarms/uptime) delta taşır; snapshot
     *  alanlar (totalCerts/criticalCerts/criticalDomains) haftadan bağımsızdır → frontend'de delta'sız gösterilir. */
    public record KpiSet(
            int totalCerts,
            int expiringInWindow,
            int renewedInWindow,
            int alarmsOpened,
            int criticalCerts,
            int criticalDomains,
            Double uptimePct,
            String weekLabel) {}

    /** Sparkline noktası (son 8 hafta). */
    public record WeekPoint(int year, int week, String weekLabel, int alarmsOpened, int expiring) {}

    /** KPI yanıtı: bu hafta + önceki hafta (delta için) + son 8 hafta trendi (eskiden yeniye). */
    public record WeeklyReportKpis(KpiSet current, KpiSet previous, List<WeekPoint> trend8w) {}

    @Transactional(readOnly = true)
    public WeeklyReportKpis compute(Long teamId, int isoYear, int weekNo) {
        LocalDate baseMonday = WeeklyAvailabilityReportService.mondayOfIsoWeek(isoYear, weekNo);
        if (teamId == null) {
            KpiSet empty = new KpiSet(0, 0, 0, 0, 0, 0, null,
                    availabilityService.windowForMonday(baseMonday).weekLabel());
            return new WeeklyReportKpis(empty, empty, List.of());
        }

        // Tek sefer çek: aktif takım envanteri + son cert kontrolleri + (cached) cert istatistikleri.
        List<CertificateInventory> inv = inventoryRepo.findByTeamIdInAndActiveTrueOrderByDomainAsc(List.of(teamId));
        List<String> domains = inv.stream().map(CertificateInventory::getDomain).toList();
        List<LatestCheck> checks = domains.isEmpty() ? List.of() : latestCheckRepo.findByDomainIn(domains);
        Map<String, Object> stats = certificateService.getStatsForTeams(List.of(teamId));

        KpiSet current  = kpiSetFor(teamId, baseMonday, inv, checks, stats);
        KpiSet previous = kpiSetFor(teamId, baseMonday.minusWeeks(1), inv, checks, stats);

        // Son 8 hafta trendi — yalnız ucuz windowed sayılar (uptime tekrar hesaplanmaz; checks bellekte filtrelenir).
        List<WeekPoint> trend = new ArrayList<>();
        for (int i = 7; i >= 0; i--) {
            LocalDate wkMon = baseMonday.minusWeeks(i);
            WeeklyAvailabilityReportService.Window w = availabilityService.windowForMonday(wkMon);
            Instant[] range = weekRange(wkMon);
            trend.add(new WeekPoint(w.year(), w.week(), w.weekLabel(),
                    alarmsOpened(teamId, w), expiringInWindow(checks, range[0], range[1])));
        }
        return new WeeklyReportKpis(current, previous, trend);
    }

    private KpiSet kpiSetFor(Long teamId, LocalDate monday, List<CertificateInventory> inv,
                             List<LatestCheck> checks, Map<String, Object> stats) {
        WeeklyAvailabilityReportService.Window w = availabilityService.windowForMonday(monday);
        Instant[] range = weekRange(monday);
        return new KpiSet(
                intVal(stats.get("total_certificates")),                  // snapshot
                expiringInWindow(checks, range[0], range[1]),             // windowed
                renewedInWindow(checks, range[0], range[1]),              // windowed
                alarmsOpened(teamId, w),                                  // windowed
                intVal(stats.get("expiring_in_7_days")),                  // snapshot (cert ≤7)
                criticalDomainCount(inv),                                 // snapshot (registrar ≤7)
                uptimePct(teamId, w),                                     // windowed
                w.weekLabel());
    }

    /** notAfter (cert bitişi) verilen ISO-hafta penceresine düşen cert sayısı = "bu hafta süresi dolan". */
    private int expiringInWindow(List<LatestCheck> checks, Instant start, Instant end) {
        int n = 0;
        for (LatestCheck lc : checks) {
            Instant na = parseInstant(lc.getNotAfter());
            if (na != null && !na.isBefore(start) && !na.isAfter(end)) n++;
        }
        return n;
    }

    /** notBefore (cert düzenlenme) verilen pencereye düşen = "bu hafta yenilenen/yeniden basılan". */
    private int renewedInWindow(List<LatestCheck> checks, Instant start, Instant end) {
        int n = 0;
        for (LatestCheck lc : checks) {
            Instant nb = parseInstant(lc.getNotBefore());
            if (nb != null && !nb.isBefore(start) && !nb.isAfter(end)) n++;
        }
        return n;
    }

    /** Bu hafta AÇILAN alarm (resolved=null → çözülmüş/açık farketmez; createdAt penceresi). Kapsam sorgu içinde. */
    private int alarmsOpened(Long teamId, WeeklyAvailabilityReportService.Window w) {
        return alertEventRepo.countFilteredByType(null, w.fromUtc(), w.toUtc(), null, null, null, true, List.of(teamId))
                .stream().mapToInt(row -> ((Number) row[1]).intValue()).sum();
    }

    /** Registrar (domain) bitişi ≤7 gün olan takım cert'i sayısı (CertificateInventory.domainExpiry parse). */
    private int criticalDomainCount(List<CertificateInventory> inv) {
        LocalDate today = LocalDate.now(IST);
        int n = 0;
        for (CertificateInventory ci : inv) {
            LocalDate exp = parseDate(ci.getDomainExpiry());
            if (exp != null && ChronoUnit.DAYS.between(today, exp) <= 7) n++;
        }
        return n;
    }

    private Double uptimePct(Long teamId, WeeklyAvailabilityReportService.Window w) {
        try {
            var s = availabilityService.weeklyUptime(teamId, w);
            return s != null ? s.avgAvailabilityPct() : null;
        } catch (Exception e) {
            log.debug("KPI uptime hesabı başarısız team={} week={}: {}", teamId, w.week(), e.getMessage());
            return null;
        }
    }

    private Instant[] weekRange(LocalDate monday) {
        return new Instant[]{
                monday.atStartOfDay(IST).toInstant(),
                monday.plusDays(6).atTime(23, 59, 59).atZone(IST).toInstant()};
    }

    private static int intVal(Object o) { return o instanceof Number n ? n.intValue() : 0; }

    /** Cert notAfter/notBefore → Instant (genelde "...Z"). Parse edilemezse null (atla). */
    static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception ignore) { /* not an instant */ }
        try { return LocalDateTime.parse(s).atZone(ZoneOffset.UTC).toInstant(); } catch (Exception ignore) { /* not a datetime */ }
        try { return LocalDate.parse(s.substring(0, 10)).atStartOfDay(ZoneOffset.UTC).toInstant(); } catch (Exception ignore) { /* not a date */ }
        return null;
    }

    /** domainExpiry (ISO tarih/tarih-saat) → LocalDate (ilk 10 karakter). */
    static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.substring(0, Math.min(10, s.length()))); } catch (Exception ignore) { return null; }
    }
}
