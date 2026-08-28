package com.sitemonitor.service;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final WeeklyScoreCalculator scoreCalculator;
    private final TeamRepository teamRepo;

    /** Rapor haftası bu kadar haftadan eskiyse executive özet gizlenir (canlı veri o hafta için yanıltıcı olur). */
    private static final int SUMMARY_MAX_WEEKS_AGO = 8;

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

    /** Executive özet: yönetici paragrafı (tr/en) + sağlık skoru + Aksiyon Gerektirenler + 30 gün ileriye bakış. */
    public record SummaryBlock(Map<String, String> managerText, ScoreBlock score,
                               List<ActionItem> actions, List<ActionItem> lookahead) {}

    /** Haftalık sağlık skoru + önceki haftaya delta + bant (green/amber/red). */
    public record ScoreBlock(int value, Integer delta, String band) {}

    /** Aksiyon/ileriye-bakış satırı — cert veya domain (registrar) bitişi. */
    public record ActionItem(String name, String type, Integer tier, Integer daysLeft, String team, boolean hasOpenAlarm) {}

    /** KPI yanıtı: bu hafta + önceki hafta (delta için) + son 8 hafta trendi + executive özet (eski/veri-yok → null). */
    public record WeeklyReportKpis(KpiSet current, KpiSet previous, List<WeekPoint> trend8w, SummaryBlock summary) {}

    /**
     * CACHE: tek bir çağrı weeklyUptime'ı DÖRT kez koşuyor (bu hafta + önceki hafta + iki skor
     * girdisi) ve her koşum takımın tüm domainleri için bir haftalık uptime_checks satırlarını
     * belleğe alıyor (5 dk kadans → domain başına ~2016 satır/hafta). 100 domain'lik bir takımda
     * tek istek yüz binlerce entity demek; aynı transaction'da tutulduğu için heap'te birikiyor.
     * Geçmiş haftalar STATİK olduğundan sonuç cache'lenebilir — aynı raporu açan her kullanıcı
     * (ve aynı kullanıcının her yenilemesi) artık tek hesaba biner.
     * NOT: ham veriyi rollup tablolarından (monitor_check_daily/hourly) okumak asıl çözümdür;
     * bu, o iş yapılana kadar yükü düşüren düşük riskli adımdır.
     */
    @org.springframework.cache.annotation.Cacheable(value = "weekly-kpis", sync = true)
    @Transactional(readOnly = true)
    public WeeklyReportKpis compute(Long teamId, int isoYear, int weekNo) {
        LocalDate baseMonday = WeeklyAvailabilityReportService.mondayOfIsoWeek(isoYear, weekNo);
        if (teamId == null) {
            KpiSet empty = new KpiSet(0, 0, 0, 0, 0, 0, null,
                    availabilityService.windowForMonday(baseMonday).weekLabel());
            return new WeeklyReportKpis(empty, empty, List.of(), null);
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

        SummaryBlock summary = buildSummary(teamId, baseMonday, inv, current);
        return new WeeklyReportKpis(current, previous, trend, summary);
    }

    /** Executive özet — yönetici paragrafı (tr/en) + sağlık skoru(+delta) + aksiyonlar + 30 gün ileriye bakış.
     *  Veri yoksa (envanter boş) veya rapor çok eskiyse null (frontend gizler; backfill yapılmaz). */
    private SummaryBlock buildSummary(Long teamId, LocalDate baseMonday, List<CertificateInventory> inv, KpiSet current) {
        LocalDate currentMonday = LocalDate.now(IST).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long weeksAgo = ChronoUnit.WEEKS.between(baseMonday, currentMonday);
        if (inv.isEmpty() || weeksAgo > SUMMARY_MAX_WEEKS_AGO) return null;

        String teamName = teamRepo.findById(teamId).map(Team::getName).orElse(null);
        List<CertificateDto> teamCerts = certificateService.getAllLatestForTeams(List.of(teamId));

        // Açık alarmlı domain kümesi — tek sorgu (hasOpenAlarm işareti için).
        Set<String> openAlarmDomains = new HashSet<>();
        for (AlertEvent e : alertEventRepo.findFiltered(false, null, null, null, null, null, null,
                true, List.of(teamId), PageRequest.of(0, 1000)).getContent()) {
            if (e.getDomain() != null) openAlarmDomains.add(e.getDomain());
        }

        // Zayıf-algoritma cert'i (takım domainlerine kırpılı) — current-state (delta'yı etkilemez).
        Set<String> domainSet = new HashSet<>(inv.stream().map(CertificateInventory::getDomain).toList());
        int weakAlgo = 0;
        for (LatestCheck lc : latestCheckRepo.findWeakAlgorithmCandidates()) {
            if (lc.getDomain() != null && domainSet.contains(lc.getDomain())) weakAlgo++;
        }

        List<ActionItem> actions   = buildActions(inv, teamCerts, teamName, openAlarmDomains);
        List<ActionItem> lookahead = buildLookahead(baseMonday, inv, teamCerts, teamName, openAlarmDomains);

        int scoreCur  = scoreCalculator.score(scoreInputsFor(teamId, baseMonday, teamCerts, weakAlgo));
        int scorePrev = scoreCalculator.score(scoreInputsFor(teamId, baseMonday.minusWeeks(1), teamCerts, weakAlgo));
        ScoreBlock score = new ScoreBlock(scoreCur, scoreCur - scorePrev, WeeklyScoreCalculator.band(scoreCur));

        WeeklyAvailabilityReportService.Window w = availabilityService.windowForMonday(baseMonday);
        int opened = (int) alertEventRepo.countByLevelOpenedBetween("CRITICAL", w.fromUtc(), w.toUtc(), true, List.of(teamId));
        int closed = (int) alertEventRepo.countByLevelResolvedBetween("CRITICAL", w.fromUtc(), w.toUtc(), true, List.of(teamId));
        int openCrit = (int) alertEventRepo.countOpenByLevelAsOf("CRITICAL", w.toUtc(), true, List.of(teamId));
        ActionItem top = actions.isEmpty() ? null : actions.get(0);
        Map<String, String> managerText = managerParagraph(
                teamName, current.weekLabel(), current.renewedInWindow(), opened, closed, openCrit, top, score);

        return new SummaryBlock(managerText, score, actions, lookahead);
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
            // D6: alt sınır — çoktan dolmuş domainler "bu hafta kritik" KPI'sını şişirmesin.
            long dd = exp == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(today, exp);
            if (dd >= 0 && dd <= 7) n++;
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

    // ── Executive özet yardımcıları ──────────────────────────────────────────

    /** Skor girdileri, {@code monday}'nin hafta-sonu itibarıyla (as-of): açık-kritik + tier1-2 ≤30g + tier1 uptime + zayıf-algo. */
    private WeeklyScoreCalculator.ScoreInputs scoreInputsFor(Long teamId, LocalDate monday,
                                                             List<CertificateDto> teamCerts, int weakAlgo) {
        WeeklyAvailabilityReportService.Window w = availabilityService.windowForMonday(monday);
        Instant weekEnd = w.windowEnd();
        Instant plus30 = weekEnd.plus(30, ChronoUnit.DAYS);
        int openCritical = (int) alertEventRepo.countOpenByLevelAsOf("CRITICAL", w.toUtc(), true, List.of(teamId));
        int tier12Exp = 0;
        for (CertificateDto c : teamCerts) {
            Integer t = c.getTier();
            if (t == null || (t != 1 && t != 2)) continue;
            Instant na = parseInstant(c.getNotAfter());
            if (na != null && !na.isBefore(weekEnd) && !na.isAfter(plus30)) tier12Exp++;
        }
        Double tier1Uptime;
        try { var s = availabilityService.weeklyUptime(teamId, w, 1); tier1Uptime = s != null ? s.avgAvailabilityPct() : null; }
        catch (Exception e) { tier1Uptime = null; }
        return new WeeklyScoreCalculator.ScoreInputs(openCritical, tier12Exp, tier1Uptime, weakAlgo);
    }

    private static final Comparator<ActionItem> ACTION_ORDER =
            Comparator.comparingInt((ActionItem a) -> a.tier() == null ? Integer.MAX_VALUE : a.tier())
                      .thenComparingInt(a -> a.daysLeft() == null ? Integer.MAX_VALUE : a.daysLeft());

    /** Aksiyon Gerektirenler top-5: cert (TLS) + domain (registrar) birleşik; tier ASC → kalan gün ASC. */
    private List<ActionItem> buildActions(List<CertificateInventory> inv, List<CertificateDto> teamCerts,
                                          String teamName, Set<String> openAlarmDomains) {
        List<ActionItem> items = new ArrayList<>();
        for (CertificateDto c : teamCerts) {
            if (c.getDaysRemaining() != null)
                items.add(new ActionItem(c.getDomain(), "cert", c.getTier(), c.getDaysRemaining(),
                        teamName, openAlarmDomains.contains(c.getDomain())));
        }
        LocalDate today = LocalDate.now(IST);
        for (CertificateInventory ci : inv) {
            LocalDate exp = parseDate(ci.getDomainExpiry());
            if (exp != null)
                items.add(new ActionItem(ci.getDomain(), "domain", ci.getTier(),
                        (int) ChronoUnit.DAYS.between(today, exp), teamName, openAlarmDomains.contains(ci.getDomain())));
        }
        return items.stream().sorted(ACTION_ORDER).limit(5).toList();
    }

    /** İleriye bakış: rapor haftasının bitiminden itibaren 30 gün içinde dolan cert + domain (aynı satır/sıralama). */
    private List<ActionItem> buildLookahead(LocalDate baseMonday, List<CertificateInventory> inv,
                                            List<CertificateDto> teamCerts, String teamName, Set<String> openAlarmDomains) {
        Instant weekEnd = availabilityService.windowForMonday(baseMonday).windowEnd();
        Instant plus30 = weekEnd.plus(30, ChronoUnit.DAYS);
        LocalDate today = LocalDate.now(IST);
        List<ActionItem> items = new ArrayList<>();
        for (CertificateDto c : teamCerts) {
            Instant na = parseInstant(c.getNotAfter());
            if (na != null && !na.isBefore(weekEnd) && !na.isAfter(plus30))
                items.add(new ActionItem(c.getDomain(), "cert", c.getTier(), c.getDaysRemaining(),
                        teamName, openAlarmDomains.contains(c.getDomain())));
        }
        for (CertificateInventory ci : inv) {
            LocalDate exp = parseDate(ci.getDomainExpiry());
            if (exp == null) continue;
            Instant expI = exp.atStartOfDay(ZoneOffset.UTC).toInstant();
            if (!expI.isBefore(weekEnd) && !expI.isAfter(plus30))
                items.add(new ActionItem(ci.getDomain(), "domain", ci.getTier(),
                        (int) ChronoUnit.DAYS.between(today, exp), teamName, openAlarmDomains.contains(ci.getDomain())));
        }
        return items.stream().sorted(ACTION_ORDER).limit(10).toList();
    }

    /** Deterministik yönetici paragrafı (tr + en) — şablon + veri (LLM yok; raporun UI diline göre frontend seçer). */
    private Map<String, String> managerParagraph(String teamName, String weekLabel, int renewed,
                                                 int opened, int closed, int openCrit, ActionItem top, ScoreBlock score) {
        String team = teamName != null ? teamName : "Takım";
        String topTr = top != null ? "En acil bekleyen: " + top.name() + " — " + top.daysLeft() + " gün. " : "";
        String topEn = top != null ? "Most urgent: " + top.name() + " — " + top.daysLeft() + " days. " : "";
        String dTr = score.delta() == null ? "" : score.delta() == 0 ? " (geçen haftayla aynı)"
                : " (geçen haftaya göre " + (score.delta() > 0 ? "+" : "") + score.delta() + ")";
        String dEn = score.delta() == null ? "" : score.delta() == 0 ? " (unchanged vs last week)"
                : " (" + (score.delta() > 0 ? "+" : "") + score.delta() + " vs last week)";
        String tr = team + " ekibi " + weekLabel + " haftasında " + renewed + " sertifika yeniledi; "
                + opened + " kritik alarm açıldı, " + closed + " tanesi çözüldü. "
                + (openCrit > 0 ? "Şu an " + openCrit + " kritik alarm açık. " : "Açık kritik alarm yok. ")
                + topTr + "Haftalık sağlık skoru " + score.value() + "/100" + dTr + ".";
        String en = team + " renewed " + renewed + " certificate(s) in " + weekLabel + "; "
                + opened + " critical alarm(s) opened and " + closed + " resolved. "
                + (openCrit > 0 ? openCrit + " critical alarm(s) currently open. " : "No open critical alarms. ")
                + topEn + "Weekly health score " + score.value() + "/100" + dEn + ".";
        Map<String, String> m = new LinkedHashMap<>();
        m.put("tr", tr);
        m.put("en", en);
        return m;
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
