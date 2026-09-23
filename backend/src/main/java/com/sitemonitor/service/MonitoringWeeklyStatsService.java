package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DomainCheck;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.WeeklyAvailabilityReportService.Window;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Haftalık İZLEME GÖSTERGELERİ — izleme türü bazında (Sertifika, Alan Adı, HTTP/Website, Ping, Port, DNS, Keyword)
 * takım-kapsamlı haftalık istatistik. YALNIZ-OKUMA; durum makinesine dokunmaz. Yeni tablo yok — mevcut check
 * (grup COUNT, checked_at indeksli) + alarm tablolarından. Ağır olduğundan AYRI lazy endpoint'ten (akordeon açılınca).
 * teamId null / rapor 8 haftadan eski → null (frontend gizler, backfill yok).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringWeeklyStatsService {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final int MAX_WEEKS_AGO = 8;

    /** İzleme türü → alarm tipleri: artık KANONİK katalogdan (bkz. {@link MonitorTypeCatalog}).
     *  Buradaki yerel kopyada "page" HİÇ YOKTU — sayfa bütünlüğü alarmları haftalık göstergelerde
     *  sessizce sayılmıyordu. Kopya kaldırıldı; kataloğu bir kapı testi EscalationService'e bağlıyor. */
    private static final Map<String, Set<String>> TYPE_ALERTS = MonitorTypeCatalog.ALERT_TYPES;

    private final HttpMonitorRepository httpMonitorRepo;
    private final PortMonitorRepository portMonitorRepo;
    private final DnsMonitorRepository dnsMonitorRepo;
    private final KeywordMonitorRepository keywordMonitorRepo;
    private final PingMonitorRepository pingMonitorRepo;
    private final DomainMonitorRepository domainMonitorRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final ScriptedMonitorRepository scriptedMonitorRepo;
    private final PageMonitorRepository pageMonitorRepo;
    private final PageSpeedMonitorRepository pageSpeedMonitorRepo;

    private final HttpCheckRepository httpCheckRepo;
    private final PortCheckRepository portCheckRepo;
    private final DnsRecordRepository dnsRecordRepo;
    private final KeywordResultRepository keywordResultRepo;
    private final PingCheckRepository pingCheckRepo;
    private final DomainCheckRepository domainCheckRepo;
    private final CertificateCheckRepository certCheckRepo;
    private final ScriptedCheckRepository scriptedCheckRepo;
    private final PageCheckRepository pageCheckRepo;
    private final PageSpeedCheckRepository pageSpeedCheckRepo;

    private final AlertEventRepository alertEventRepo;
    private final CertificateService certificateService;
    private final WeeklyAvailabilityReportService availabilityService;

    public record TopTarget(String name, Double successRate, int alarms) {}

    public record TypeStats(String type, int activeMonitors, long totalChecks, Double successRate,
                            int alarmsOpened, int alarmsResolved, int alarmsOpen,
                            Double successRateDelta, Integer alarmsOpenedDelta,
                            Double extra, List<TopTarget> top3) {}

    public record MonitoringStats(List<TypeStats> types) {}

    private enum ExtraMode { NONE, AVG_MS, CHANGED_SUM, CLOSED_COUNT, EXTERNAL }

    /** F6 (CPU denetimi): her istekte ~15 gruplu/scan sorgu (domain_checks GROUP-BY dahil) koşuyordu —
     *  Caffeine 300 sn cache (LONG_TTL_CACHES): geçmiş haftalar statik, güncel hafta 5 dk bayatlık tolere eder.
     *  Kardinalite: takım × ≤MAX_WEEKS_AGO hafta → küçük. */
    @org.springframework.cache.annotation.Cacheable(cacheNames = "monitoring-weekly-stats",
            key = "#teamId + ':' + #isoYear + ':' + #weekNo", unless = "#result == null")
    @Transactional(readOnly = true)
    public MonitoringStats compute(Long teamId, int isoYear, int weekNo) {
        LocalDate baseMonday = WeeklyAvailabilityReportService.mondayOfIsoWeek(isoYear, weekNo);
        if (teamId == null) return null;
        LocalDate currentMonday = LocalDate.now(IST).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (ChronoUnit.WEEKS.between(baseMonday, currentMonday) > MAX_WEEKS_AGO) return null;

        Window w = availabilityService.windowForMonday(baseMonday);
        Window wPrev = availabilityService.windowForMonday(baseMonday.minusWeeks(1));
        Instant weekEnd = w.windowEnd();
        List<Long> scope = List.of(teamId);

        // Alarm kovaları (alertType→sayı) — takım-kapsamlı, tek gruplu sorgu × 4 (açılan/çözülen/açık/önceki-açılan)
        Map<String, Integer> opened     = alarmBucket(alertEventRepo.countFilteredByType(null, w.fromUtc(), w.toUtc(), null, null, null, true, scope));
        Map<String, Integer> resolved   = alarmBucket(alertEventRepo.countFilteredByType(true, null, null, w.fromUtc(), w.toUtc(), null, true, scope));
        Map<String, Integer> openNow    = alarmBucket(alertEventRepo.countFilteredByType(false, null, null, null, null, null, true, scope));
        Map<String, Integer> openedPrev = alarmBucket(alertEventRepo.countFilteredByType(null, wPrev.fromUtc(), wPrev.toUtc(), null, null, null, true, scope));
        Map<String, Integer> perTarget  = perDomainOpened(teamId, w);

        Ctx c = new Ctx(teamId, w, wPrev, weekEnd, opened, resolved, openNow, openedPrev, perTarget);

        List<TypeStats> types = new ArrayList<>();
        types.add(certType(c));
        types.add(domainType(c));
        types.add(httpType(c));
        types.add(pingType(c));
        types.add(portType(c));
        types.add(dnsType(c));
        types.add(keywordType(c));
        types.add(pageType(c));
        types.add(pageSpeedType(c));
        types.add(scriptedType(c));
        return new MonitoringStats(types);
    }

    private record Ctx(Long teamId, Window w, Window wPrev, Instant weekEnd,
                       Map<String, Integer> opened, Map<String, Integer> resolved, Map<String, Integer> openNow,
                       Map<String, Integer> openedPrev, Map<String, Integer> perTarget) {}

    private record MonRef(long id, String name, String createdAt) {}

    // ── Tür metotları ────────────────────────────────────────────────────────

    private TypeStats certType(Ctx c) {
        List<CertificateInventory> inv = inventoryRepo.findByTeamIdInAndActiveTrueOrderByDomainAsc(List.of(c.teamId()));
        List<String> domains = inv.stream().map(CertificateInventory::getDomain).toList();
        int active = (int) inv.stream().filter(i -> activeAsOf(i.getCreatedAt(), c.weekEnd())).count();
        List<Object[]> cur  = domains.isEmpty() ? List.of() : certCheckRepo.weeklyStatsByDomain(domains, c.w().fromUtc(), c.w().toUtc());
        List<Object[]> prev = domains.isEmpty() ? List.of() : certCheckRepo.weeklyStatsByDomain(domains, c.wPrev().fromUtc(), c.wPrev().toUtc());
        Double extra = (double) intVal(certificateService.getStatsForTeams(List.of(c.teamId())).get("expiring_in_30_days"));
        return buildStats("cert", active, cur, prev, k -> String.valueOf(k), c, ExtraMode.EXTERNAL, extra);
    }

    private TypeStats domainType(Ctx c) {
        List<MonRef> mons = domainMonitorRepo.findByActiveTrue().stream()
                .filter(m -> c.teamId().equals(m.getTeamId()))
                .map(m -> new MonRef(m.getId(), nz(m.getName(), m.getDomain()), m.getCreatedAt())).toList();
        // ≤30 gün kalan (registrar) — takım domain monitörlerinin son kontrolünden
        Set<Long> ids = mons.stream().map(MonRef::id).collect(java.util.stream.Collectors.toSet());
        long expiring30 = ids.isEmpty() ? 0 : domainCheckRepo.findLatestPerMonitor().stream()
                .filter(dc -> ids.contains(dc.getMonitorId()))
                .filter(dc -> dc.getDaysRemaining() != null && dc.getDaysRemaining() <= 30).count();
        return monitorIdType("domain", mons, c, (idl, from, to) -> domainCheckRepo.weeklyStatsByMonitor(idl, from, to),
                ExtraMode.EXTERNAL, (double) expiring30);
    }

    private TypeStats httpType(Ctx c) {
        List<MonRef> mons = httpMonitorRepo.findByActiveTrue().stream()
                .filter(m -> c.teamId().equals(m.getTeamId()))
                .map(m -> new MonRef(m.getId(), nz(m.getName(), m.getUrl()), m.getCreatedAt())).toList();
        return monitorIdType("http", mons, c, (idl, from, to) -> httpCheckRepo.weeklyStatsByMonitor(idl, from, to), ExtraMode.AVG_MS, null);
    }

    private TypeStats pingType(Ctx c) {
        List<MonRef> mons = pingMonitorRepo.findByActiveTrue().stream()
                .filter(m -> c.teamId().equals(m.getTeamId()))
                .map(m -> new MonRef(m.getId(), nz(m.getName(), m.getHost()), m.getCreatedAt())).toList();
        return monitorIdType("ping", mons, c, (idl, from, to) -> pingCheckRepo.weeklyStatsByMonitor(idl, from, to), ExtraMode.AVG_MS, null);
    }

    private TypeStats portType(Ctx c) {
        // Port monitörleri DNS gibi çift-kaynaklı: STANDALONE (teamId) + ENVANTER-TÜREVİ (teamId null, host=envanter domain'i).
        // Envanter-türevi olanlar takıma domain→envanter eşlemesinden bağlıdır; host:port başına tek (ekranın merge'i gibi).
        Set<String> teamDomains = teamInventoryDomains(c.teamId());
        Map<String, PortMonitor> invByKey = new HashMap<>();
        List<MonRef> mons = new ArrayList<>();
        for (PortMonitor m : portMonitorRepo.findByActiveTrue()) {
            if (Boolean.TRUE.equals(m.getStandalone())) {
                if (c.teamId().equals(m.getTeamId())) mons.add(new MonRef(m.getId(), nz(m.getName(), m.getHost() + ":" + m.getPort()), m.getCreatedAt()));
            } else if (teamDomains.contains(m.getHost())) {
                invByKey.merge(m.getHost() + ":" + m.getPort(), m, (a, b) -> a.getId() <= b.getId() ? a : b);
            }
        }
        for (PortMonitor m : invByKey.values()) mons.add(new MonRef(m.getId(), nz(m.getName(), m.getHost() + ":" + m.getPort()), m.getCreatedAt()));
        return monitorIdType("port", mons, c, (idl, from, to) -> portCheckRepo.weeklyStatsByMonitor(idl, from, to), ExtraMode.CLOSED_COUNT, null);
    }

    private TypeStats dnsType(Ctx c) {
        // DNS monitörleri çift-kaynaklı: STANDALONE (teamId) + ENVANTER-TÜREVİ (teamId null, domain=envanter domain'i).
        // Envanter-türevi olanlar takıma domain→envanter eşlemesinden bağlıdır; domain başına tek (ekranın merge'i gibi).
        Set<String> teamDomains = teamInventoryDomains(c.teamId());
        Map<String, DnsMonitor> invByDomain = new HashMap<>();
        List<MonRef> mons = new ArrayList<>();
        for (DnsMonitor m : dnsMonitorRepo.findByActiveTrue()) {
            if (Boolean.TRUE.equals(m.getStandalone())) {
                if (c.teamId().equals(m.getTeamId())) mons.add(new MonRef(m.getId(), nz(m.getName(), m.getDomain()), m.getCreatedAt()));
            } else if (teamDomains.contains(m.getDomain())) {
                invByDomain.merge(m.getDomain(), m, (a, b) -> a.getId() <= b.getId() ? a : b);
            }
        }
        for (DnsMonitor m : invByDomain.values()) mons.add(new MonRef(m.getId(), nz(m.getName(), m.getDomain()), m.getCreatedAt()));
        return monitorIdType("dns", mons, c, (idl, from, to) -> dnsRecordRepo.weeklyStatsByMonitor(idl, from, to), ExtraMode.CHANGED_SUM, null);
    }

    private TypeStats keywordType(Ctx c) {
        List<MonRef> mons = keywordMonitorRepo.findByActiveTrue().stream()
                .filter(m -> c.teamId().equals(m.getTeamId()))
                .map(m -> new MonRef(m.getId(), nz(m.getName(), m.getUrl()), m.getCreatedAt())).toList();
        return monitorIdType("keyword", mons, c, (idl, from, to) -> keywordResultRepo.weeklyStatsByMonitor(idl, from, to), ExtraMode.NONE, null);
    }

    /**
     * Sentetik (k6) izleme — haftalık raporda HİÇ sayılmıyordu.
     *
     * <p>Ne `TYPE_ALERTS`'te `SCRIPTED_FAIL` vardı ne de bir `scriptedType()` metodu; dolayısıyla
     * haftalık rapor ekranı, PDF ve e-postası sentetik monitörleri yok sayıyordu. Sorgu
     * ({@code ScriptedCheckRepository.weeklyStatsByMonitor}) zaten YAZILMIŞTI ama hiç çağrılmıyordu
     * — bağlanmamış halka.
     */
    /**
     * Sayfa bütünlüğü (page) izleme — sentetikle BİREBİR aynı bağlanmamış halka.
     *
     * <p>Kendi modeli, deposu, alarmları (PAGE_DOWN / PAGE_INTEGRITY), fırtına ve kesinti işleme
     * mantığı olan tam bir izleme türü; {@code PageCheckRepository.weeklyStatsByMonitor} sorgusu
     * da yazılmıştı — ama hiç çağrılmıyordu ve {@code TYPE_ALERTS}'te karşılığı yoktu. Sonuç:
     * haftalık göstergelerde bu tür hiç görünmüyor, alarmları hiçbir kovaya düşmüyordu.
     * Frontend {@code WeeklyMonitoringStrip.ORDER} 'page'i ZATEN bekliyordu — satır boş geliyordu.
     */
    private TypeStats pageType(Ctx c) {
        List<MonRef> mons = pageMonitorRepo.findByActiveTrue().stream()
                .filter(m -> c.teamId().equals(m.getTeamId()))
                .map(m -> new MonRef(m.getId(), nz(m.getName(), m.getUrl()), m.getCreatedAt())).toList();
        return monitorIdType("page", mons, c,
                (idl, from, to) -> pageCheckRepo.weeklyStatsByMonitor(idl, from, to), ExtraMode.AVG_MS, null);
    }

    /**
     * Sayfa Hızı — sentetik ve sayfa bütünlüğüyle BİREBİR aynı bağlanmamış halka.
     *
     * <p>{@code MonitorTypeCatalog.ALERT_TYPES}'ta {@code pagespeed} VARDI ama {@code compute()}
     * onun için bir {@code TypeStats} ÜRETMİYORDU: haftalık izleme özeti ekranı, PDF'i ve e-postası
     * Sayfa Hızı izlemelerini tamamen yok sayıyordu (aktif sayı yok, kontrol yok, başarı oranı yok,
     * alarm kovası yok). Kapı: {@code MonitoringWeeklyStatsServiceTest} katalog sözleşmesi.
     */
    private TypeStats pageSpeedType(Ctx c) {
        List<MonRef> mons = pageSpeedMonitorRepo.findByActiveTrue().stream()
                .filter(m -> c.teamId().equals(m.getTeamId()))
                .map(m -> new MonRef(m.getId(), nz(m.getName(), m.getUrl()), m.getCreatedAt())).toList();
        return monitorIdType("pagespeed", mons, c,
                (idl, from, to) -> pageSpeedCheckRepo.weeklyStatsByMonitor(idl, from, to), ExtraMode.AVG_MS, null);
    }

    private TypeStats scriptedType(Ctx c) {
        List<MonRef> mons = scriptedMonitorRepo.findByActiveTrue().stream()
                .filter(m -> c.teamId().equals(m.getTeamId()))
                .map(m -> new MonRef(m.getId(), m.getName(), m.getCreatedAt())).toList();
        return monitorIdType("scripted", mons, c,
                (idl, from, to) -> scriptedCheckRepo.weeklyStatsByMonitor(idl, from, to), ExtraMode.NONE, null);
    }

    /** monitorId-anahtarlı türler için ortak: aktif-sayı + id→ad + haftalık grup sorgusu (cur/prev). */
    private TypeStats monitorIdType(String type, List<MonRef> mons, Ctx c, GroupedQuery query, ExtraMode extraMode, Double externalExtra) {
        Map<Long, String> idName = new HashMap<>();
        for (MonRef m : mons) idName.putIfAbsent(m.id(), m.name());
        int active = (int) mons.stream().filter(m -> activeAsOf(m.createdAt(), c.weekEnd())).count();
        List<Long> ids = new ArrayList<>(idName.keySet());
        List<Object[]> cur  = ids.isEmpty() ? List.of() : query.run(ids, c.w().fromUtc(), c.w().toUtc());
        List<Object[]> prev = ids.isEmpty() ? List.of() : query.run(ids, c.wPrev().fromUtc(), c.wPrev().toUtc());
        Function<Object, String> nameOf = k -> idName.getOrDefault(((Number) k).longValue(), String.valueOf(k));
        return buildStats(type, active, cur, prev, nameOf, c, extraMode, externalExtra);
    }

    @FunctionalInterface
    private interface GroupedQuery { List<Object[]> run(List<Long> ids, String from, String to); }

    /** Gruplu satırlardan ([key,total,success,(extra)]) tür istatistiğini derler. */
    private TypeStats buildStats(String type, int active, List<Object[]> cur, List<Object[]> prev,
                                 Function<Object, String> nameOf, Ctx c, ExtraMode extraMode, Double externalExtra) {
        Set<String> alerts = TYPE_ALERTS.getOrDefault(type, Set.of());
        int aOpened = sumBucket(c.opened(), alerts), aResolved = sumBucket(c.resolved(), alerts),
            aOpen = sumBucket(c.openNow(), alerts), aOpenedPrev = sumBucket(c.openedPrev(), alerts);

        long total = 0, success = 0, changedSum = 0;
        // Ağırlıklı ortalama ÖLÇÜMLÜ satırlar üzerinden: AVG NULL'ları atladığı, COUNT saymadığı için
        // payda `total` olursa hafta boyu down duran bir monitör (AVG=NULL, COUNT=1000) paydayı şişirip
        // ortalamayı aşağı çekiyordu — rapor, kesinti arttıkça yanıt süresini İYİ gösteriyordu.
        // Sorgular beşinci kolon olarak ölçümlü satır sayısını döndürüyor (kardeş emsal:
        // ActivityLogRepository:103 aynı hesabı `responseMs IS NOT NULL` filtresiyle yapıyor).
        long measured = 0;
        double weightedMs = 0;
        int closed = 0;
        List<TopTarget> targets = new ArrayList<>();
        for (Object[] r : cur) {
            long t = lng(r[1]), s = lng(r[2]);
            total += t; success += s;
            if (extraMode == ExtraMode.AVG_MS && r.length > 3 && r[3] != null) {
                // Beşinci kolon yoksa (eski/başka şekilli sorgu) `t`'ye düş — davranış en kötü
                // ihtimalle eski hâline döner, NPE üretmez.
                long w = r.length > 4 && r[4] != null ? ((Number) r[4]).longValue() : t;
                weightedMs += ((Number) r[3]).doubleValue() * w;
                measured += w;
            }
            if (extraMode == ExtraMode.CHANGED_SUM && r.length > 3 && r[3] != null) changedSum += ((Number) r[3]).longValue();
            if (extraMode == ExtraMode.CLOSED_COUNT && s < t) closed++;
            String name = nameOf.apply(r[0]);
            Double rate = t > 0 ? round1(100.0 * s / t) : null;
            targets.add(new TopTarget(name, rate, c.perTarget().getOrDefault(name, 0)));
        }
        Double rate = total > 0 ? round1(100.0 * success / total) : null;

        long pt = 0, ps = 0;
        for (Object[] r : prev) { pt += lng(r[1]); ps += lng(r[2]); }
        Double prevRate = pt > 0 ? round1(100.0 * ps / pt) : null;
        Double rateDelta = (rate != null && prevRate != null) ? round1(rate - prevRate) : null;
        Integer openedDelta = aOpened - aOpenedPrev;

        Double extra = switch (extraMode) {
            case AVG_MS       -> measured > 0 ? round1(weightedMs / measured) : null;
            case CHANGED_SUM  -> (double) changedSum;
            case CLOSED_COUNT -> (double) closed;
            case EXTERNAL     -> externalExtra;
            default           -> null;
        };

        List<TopTarget> top3 = targets.stream()
                .filter(tt -> (tt.successRate() != null && tt.successRate() < 100.0) || tt.alarms() > 0)
                .sorted(Comparator.comparingDouble((TopTarget tt) -> tt.successRate() == null ? 101.0 : tt.successRate())
                        .thenComparingInt(tt -> -tt.alarms()))
                .limit(3).toList();

        return new TypeStats(type, active, total, rate, aOpened, aResolved, aOpen, rateDelta, openedDelta, extra, top3);
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    /** Takımın aktif envanter domain'leri — envanter-türevi DNS/Port monitörlerinin takım aidiyeti bu kümeden. */
    private Set<String> teamInventoryDomains(Long teamId) {
        Set<String> s = new java.util.HashSet<>();
        for (CertificateInventory inv : inventoryRepo.findByTeamIdInAndActiveTrueOrderByDomainAsc(List.of(teamId)))
            if (inv.getDomain() != null) s.add(inv.getDomain());
        return s;
    }

    private Map<String, Integer> perDomainOpened(Long teamId, Window w) {
        Map<String, Integer> m = new HashMap<>();
        for (AlertEvent e : alertEventRepo.findFiltered(null, w.fromUtc(), w.toUtc(), null, null, null, null,
                true, List.of(teamId), PageRequest.of(0, 2000)).getContent()) {
            if (e.getDomain() != null) m.merge(e.getDomain(), 1, Integer::sum);
        }
        return m;
    }

    private static Map<String, Integer> alarmBucket(List<Object[]> rows) {
        Map<String, Integer> m = new HashMap<>();
        for (Object[] r : rows) if (r[0] != null) m.put(String.valueOf(r[0]), ((Number) r[1]).intValue());
        return m;
    }

    private static int sumBucket(Map<String, Integer> m, Set<String> types) {
        int n = 0;
        for (String t : types) n += m.getOrDefault(t, 0);
        return n;
    }

    /** createdAt ≤ weekEnd → o hafta sonu itibarıyla mevcut (aktiflik yalnız current-state; soft-delete geçmişi yok). */
    private static boolean activeAsOf(String createdAt, Instant weekEnd) {
        Instant i = WeeklyReportKpiService.parseInstant(createdAt);
        return i == null || !i.isAfter(weekEnd);
    }

    private static long lng(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
    private static int intVal(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static Double round1(double x) { return Math.round(x * 10.0) / 10.0; }
    private static String nz(String a, String b) { return a != null && !a.isBlank() ? a : b; }
}
