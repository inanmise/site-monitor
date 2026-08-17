package com.sitemonitor.service.report;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.MaintenanceWindow;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.MaintenanceWindowRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.EmailNotificationService.AvailabilityRow;
import com.sitemonitor.service.MaintenanceService;
import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.service.MonitoringWeeklyStatsService;
import com.sitemonitor.service.WeeklyAvailabilityReportService.Window;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Haftalık kesinti raporunun VERİ katmanı — bir takımın geçen haftaki bütün alarmlarını toplar,
 * türetilmiş alanları hesaplar ve {@link WeeklyOutagePdfWriter}'ın çizeceği hâle getirir.
 *
 * <p><b>Kapsam.</b> Haftalık e-postanın GÖVDESİ yalnız sertifika envanterindeki domainlerin HTTP
 * uptime'ını raporlar. Bu ek ise takımın portföyündeki <b>dokuz izleme türünün tamamının</b>
 * alarmlarını kapsar. İki farklı evren olduğu için PDF'in sonundaki kapsam notu zorunludur;
 * yoksa e-postadaki sayılarla ekteki sayılar çelişkili görünür.
 *
 * <p><b>Alarm ≠ erişilebilirlik düşüşü.</b> Alarm üretimi ardışık doğrulama ister; iki dakikalık
 * bir kesinti uptime yüzdesini düşürür ama hiç alarm üretmez. "0 alarm ama %99.7 uptime" tutarlı
 * bir tablodur ve PDF bunu açıkça yazar.
 *
 * <p><b>Hafta içinde AÇIK OLAN alarmlar</b> raporlanır, yalnız "hafta içinde açılanlar" değil.
 * Önceki haftadan devreden ve hâlâ süren bir kesinti o haftayı da etkilemiştir; dışarıda
 * bırakmak en uzun kesintileri raporun tam da dışında bırakırdı. Üç kapsamlı sorgunun birleşimiyle
 * elde edilir (yeni SQL yok): hafta içinde açılanlar + önceden açılıp hâlâ açık olanlar +
 * önceden açılıp hafta içinde/sonrasında çözülenler.
 */
@Slf4j
@Service
public class WeeklyOutageReportService {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final int PAGE_SIZE = 500;
    /** Kaçak döngüye karşı emniyet — gerçek bir haftada asla ulaşılmaz, ulaşılırsa loglanır. */
    private static final int MAX_PAGES = 400;
    private static final int CERT_EXPIRY_DAYS = 60;
    private static final String[] DAY_TR =
            { "Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar" };

    private final AlertEventRepository alertEventRepo;
    private final NotificationLogRepository notificationLogRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final MaintenanceWindowRepository maintenanceRepo;
    private final MaintenanceService maintenanceService;
    private final MonitoringWeeklyStatsService weeklyStatsService;

    /**
     * Yapıcı ELLE yazıldı ({@code @RequiredArgsConstructor} değil) çünkü tek bir parametrenin
     * {@code @Lazy} olması gerekiyor ve Lombok bu ek açıklamayı yapıcı parametresine kopyalamıyor.
     *
     * <p><b>Kırılan döngü:</b> WeeklyAvailability → WeeklyOutage → MonitoringWeeklyStats →
     * WeeklyAvailability. Bu döngü yüzünden uygulama HİÇ AÇILMIYORDU (Spring dairesel referansları
     * varsayılan olarak reddediyor); süit yakaladı. {@code @Lazy} proxy'si zinciri kurulum anında
     * kesiyor, ilk çağrıda gerçek bean çözülüyor — projedeki mevcut desenin aynısı
     * (bkz. {@code MonitoringController}, {@code CertificateService}).
     *
     * <p>Alternatif olan "windowForMonday'i static yap" kökten çözerdi ama sekiz çağrı noktasına
     * ve iki test sınıfının Mockito kurgusuna dokunmayı gerektiriyordu; bu işin kapsamı dışında.
     */
    public WeeklyOutageReportService(
            AlertEventRepository alertEventRepo,
            NotificationLogRepository notificationLogRepo,
            CertificateInventoryRepository inventoryRepo,
            LatestCheckRepository latestCheckRepo,
            MaintenanceWindowRepository maintenanceRepo,
            MaintenanceService maintenanceService,
            @org.springframework.context.annotation.Lazy MonitoringWeeklyStatsService weeklyStatsService) {
        this.alertEventRepo = alertEventRepo;
        this.notificationLogRepo = notificationLogRepo;
        this.inventoryRepo = inventoryRepo;
        this.latestCheckRepo = latestCheckRepo;
        this.maintenanceRepo = maintenanceRepo;
        this.maintenanceService = maintenanceService;
        this.weeklyStatsService = weeklyStatsService;
    }

    // ── Dışa açılan veri şekli ───────────────────────────────────────────────

    /** Tek bir alarmın rapordaki tam satırı (türetilmiş alanlar dahil). */
    public record OutageRow(
            Long alertId, String monitorType, String alertType, String target, String level,
            String startedAt, String endedAt, boolean stillOpen, boolean carriedOver,
            long durationMin, long weekDurationMin, long weekStartOffsetMin,
            String acknowledgedBy, String acknowledgedAt, String resolvedBy,
            long notifySent, long notifyFailed, boolean maintenanceOverlap, Long stormId,
            String message) {

        /** Hiç bildirim gitmemiş ya da gönderimi başarısız olmuş — kimseye ulaşmamış olabilir. */
        public boolean notifyGap() { return notifySent == 0; }
    }

    /** İzleme türü başına gruplanmış kesinti satırları. */
    public record TypeGroup(String type, String label, List<OutageRow> rows) {}

    /** Tekrar sayimi icin bilesik anahtar — dize paketleme YOK (hedef adlarında boşluk olabiliyor). */
    public record RepeatKey(String target, String alertType) {}

    /** Aynı (hedef, alarm tipi) için hafta içinde birden fazla alarm — kronik sorun işareti. */
    public record RepeatItem(String target, String alertType, String typeLabel, int count) {}

    /** Gün/saat/seviye dağılımı kovası. */
    public record Bucket(String label, int count) {}

    /** Süresi yaklaşan sertifika. */
    public record CertExpiry(String domain, Integer daysRemaining) {}

    /** Zaman çizelgesindeki tek bir kesinti aralığı — pencere başına göre konum + uzunluk. */
    public record TimelineSegment(long startOffsetMin, long durationMin, String level, boolean stillOpen) {}

    /**
     * Zaman çizelgesinin bir satırı: bir hedef ve o hedefin haftaya düşen bütün kesinti aralıkları.
     *
     * <p>Alarm başına değil HEDEF başına satır: aynı hedefin üç alarmı tek çizgide yan yana
     * görününce "bu hedef haftanın ne kadarında kesintideydi" tek bakışta okunur, ayrıca farklı
     * hedeflerin çubukları alt alta hizalandığı için eşzamanlı kesintiler (ortak kök neden)
     * gözle fark edilir.
     */
    public record TimelineRow(String target, List<TimelineSegment> segments, long totalMin) {}

    /** Isı haritasının bir günü: 24 saatlik alarm sayıları (indeks = saat). */
    public record HeatRow(String day, List<Integer> hours) {}

    /** PDF'in ihtiyaç duyduğu her şey. */
    public record WeeklyOutageData(
            String teamName, String weekLabel, String generatedAt,
            int totalAlarms, int stillOpenCount, int carriedOverCount, int affectedTargets,
            long totalDowntimeMin, Double avgAvailabilityPct, int domainsWithOutage,
            int alarmsPrevWeek, int alarmsDelta,
            List<MonitoringWeeklyStatsService.TypeStats> typeStats,
            List<TypeGroup> groups, List<OutageRow> longest, List<RepeatItem> repeats,
            List<Bucket> byDay, List<Bucket> byHour, List<Bucket> byLevel,
            List<HeatRow> heat, List<TimelineRow> timeline, long windowMinutes,
            List<OutageRow> openNow, List<OutageRow> notifyGaps,
            List<AvailabilityRow> availability, List<CertExpiry> certExpiries,
            int maintenanceOverlapCount, int stormCount) {

        /** Hiç alarm yoksa PDF kısa "kesintisiz hafta" hâlini çizer (ek yine de gönderilir). */
        public boolean quietWeek() { return totalAlarms == 0; }
    }

    // ── Toplama ──────────────────────────────────────────────────────────────

    /**
     * Takımın verilen haftadaki tüm kesinti verisini toplar.
     *
     * @param availabilityRows haftalık e-postanın ZATEN hesapladığı erişilebilirlik satırları —
     *                         yeniden hesaplamak tüm uptime sorgularını ikinci kez koşturmak olurdu
     *                         (tek pod, 200-1000+ domain).
     */
    @Transactional(readOnly = true)
    public WeeklyOutageData collect(Team team, Window w, List<AvailabilityRow> availabilityRows) {
        List<AlertEvent> alarms = loadWeekAlarms(team.getId(), w);
        Map<Long, long[]> notify = notifyCounts(alarms);
        List<MaintenanceWindow> windows = loadMaintenanceWindows();

        List<OutageRow> rows = new ArrayList<>(alarms.size());
        for (AlertEvent e : alarms) rows.add(toRow(e, w, notify, windows));
        rows.sort(Comparator.comparing(OutageRow::startedAt, Comparator.nullsLast(Comparator.naturalOrder())));

        return assemble(team, w, rows, availabilityRows);
    }

    /**
     * Hafta içinde AÇIK OLAN tüm alarmlar — üç kapsamlı sorgunun birleşimi, id ile tekilleştirilir.
     *
     * <p>findFiltered'ın createdAt aralığı ile resolvedAt aralığı VE'lendiği için "pencereyle
     * kesişen" koşulu tek sorguda yazılamıyor; yeni bir JPQL eklemek yerine mevcut, kapsamı
     * (IDOR) zaten doğrulanmış sorgu üç kez farklı parametrelerle çağrılıyor.
     */
    private List<AlertEvent> loadWeekAlarms(Long teamId, Window w) {
        Map<Long, AlertEvent> byId = new LinkedHashMap<>();
        List<Long> scope = List.of(teamId);
        // 1) hafta içinde açılanlar
        collectPages(byId, p -> alertEventRepo.findFiltered(
                null, w.fromUtc(), w.toUtc(), null, null, null, null, true, scope, p));
        // 2) hafta başından ÖNCE açılmış ve hâlâ açık olanlar (devreden kesinti)
        collectPages(byId, p -> alertEventRepo.findFiltered(
                false, null, w.fromUtc(), null, null, null, null, true, scope, p));
        // 3) hafta başından önce açılmış ama hafta İÇİNDE ya da sonrasında çözülenler
        collectPages(byId, p -> alertEventRepo.findFiltered(
                true, null, w.fromUtc(), w.fromUtc(), null, null, null, true, scope, p));
        return new ArrayList<>(byId.values());
    }

    @FunctionalInterface
    private interface PagedQuery { Page<AlertEvent> run(PageRequest page); }

    private void collectPages(Map<Long, AlertEvent> sink, PagedQuery query) {
        for (int p = 0; p < MAX_PAGES; p++) {
            Page<AlertEvent> page = query.run(PageRequest.of(p, PAGE_SIZE));
            for (AlertEvent e : page.getContent()) sink.putIfAbsent(e.getId(), e);
            if (!page.hasNext()) return;
            if (p == MAX_PAGES - 1) {
                log.warn("Haftalık kesinti raporu: sayfa emniyet sınırına ({}×{}) ulaşıldı, "
                        + "kalan alarmlar okunmadı", MAX_PAGES, PAGE_SIZE);
            }
        }
    }

    /** alertId → [gönderilen, başarısız] — TEK toplu sorgu (alarm başına sorgu N+1 olurdu). */
    private Map<Long, long[]> notifyCounts(List<AlertEvent> alarms) {
        Map<Long, long[]> m = new java.util.HashMap<>();
        if (alarms.isEmpty()) return m;
        List<Long> ids = alarms.stream().map(AlertEvent::getId).filter(Objects::nonNull).toList();
        for (int i = 0; i < ids.size(); i += PAGE_SIZE) {
            List<Long> slice = ids.subList(i, Math.min(i + PAGE_SIZE, ids.size()));
            for (Object[] r : notificationLogRepo.countByAlertIds(slice)) {
                m.put(((Number) r[0]).longValue(),
                        new long[]{ num(r[1]), num(r[2]) });
            }
        }
        return m;
    }

    private List<MaintenanceWindow> loadMaintenanceWindows() {
        try {
            return maintenanceRepo.findAll();
        } catch (Exception e) {
            log.warn("Bakım pencereleri okunamadı, kesinti raporunda bakım işareti çizilmeyecek: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Satır türetme ────────────────────────────────────────────────────────

    OutageRow toRow(AlertEvent e, Window w, Map<Long, long[]> notify, List<MaintenanceWindow> windows) {
        Instant start = parse(e.getCreatedAt());
        Instant end = parse(e.getResolvedAt());
        boolean stillOpen = !Boolean.TRUE.equals(e.getResolved()) || end == null;
        // Açık alarmın süresi PENCERE SONUNA kadar sayılır — "şu ana kadar" deseydik geçmiş bir
        // haftanın raporu her açılışta farklı süre gösterirdi (rapor yeniden üretilebilir olmalı).
        Instant effectiveEnd = stillOpen ? w.windowEnd() : end;
        long minutes = durationMin(start, effectiveEnd);

        // HAFTA İÇİNE DÜŞEN pay — pencereye kırpılmış. Toplam kesinti süresi bunların toplamıdır;
        // ham süreler toplanırsa devreden alarmlar tabloyu anlamsızlaştırır: gerçek veride bir
        // haftalık rapor "254 gün kesinti" diyordu, çünkü 8 alarm ~29 gündür açıktı.
        Instant windowStart = parseOrEpoch(w.fromUtc());
        Instant clippedStart = (start == null || start.isBefore(windowStart)) ? windowStart : start;
        Instant clippedEnd = effectiveEnd == null || effectiveEnd.isAfter(w.windowEnd())
                ? w.windowEnd() : effectiveEnd;
        long weekMinutes = start == null ? 0 : durationMin(clippedStart, clippedEnd);

        long[] n = notify.getOrDefault(e.getId(), new long[]{ 0, 0 });
        String type = MonitorTypeCatalog.typeOfAlert(e.getAlertType());

        return new OutageRow(
                e.getId(),
                type,
                e.getAlertType(),
                e.getDomain(),
                e.getAlertLevel(),
                e.getCreatedAt(),
                stillOpen ? null : e.getResolvedAt(),
                stillOpen,
                start != null && start.isBefore(windowStart),
                minutes,
                weekMinutes,
                start == null ? 0 : durationMin(windowStart, clippedStart),
                e.getAcknowledgedBy(),
                e.getAcknowledgedAt(),
                e.getResolvedBy(),
                n[0], n[1],
                overlapsMaintenance(e.getDomain(), start, windows),
                e.getStormId(),
                e.getMessage());
    }

    /**
     * Alarm başlangıcı bir bakım penceresine denk geliyor mu.
     *
     * <p><b>KESİNLİK SINIRI — rapora da yazılır.</b> {@code AlertEvent}'te bakım alanı yok;
     * {@code UptimeCheck.maintenance} ise kontrol anında kalıcı yazılıyor. Yani erişilebilirlik
     * tarafındaki bakım bilgisi tarihseldir ve kesindir, buradaki ise pencerelerin BUGÜNKÜ
     * tanımının geçmiş bir ana uygulanmasıyla YENİDEN HESAPLANIR. Pencere o tarihten sonra
     * değiştirilmiş ya da silinmişse işaret yanılır — bu yüzden PDF'te rozet "bakımdaydı" değil,
     * "bakım penceresine denk geliyor (bugünkü tanıma göre)" diye etiketlenir.
     */
    boolean overlapsMaintenance(String target, Instant start, List<MaintenanceWindow> windows) {
        if (start == null || windows.isEmpty()) return false;
        for (MaintenanceWindow w : windows) {
            boolean covers = Boolean.TRUE.equals(w.getAllMonitors())
                    || (target != null && maintenanceService.targetsOf(w).contains(target));
            if (covers && maintenanceService.isActiveAt(w, start)) return true;
        }
        return false;
    }

    // ── Derleme ──────────────────────────────────────────────────────────────

    private WeeklyOutageData assemble(Team team, Window w, List<OutageRow> rows,
                                      List<AvailabilityRow> availabilityRows) {
        // Tür grupları — katalog sırasında; bilinmeyen tip "Diğer"e düşer, satır kaybolmaz.
        Map<String, List<OutageRow>> byType = new LinkedHashMap<>();
        for (String t : MonitorTypeCatalog.ORDER) byType.put(t, new ArrayList<>());
        byType.put(null, new ArrayList<>());
        for (OutageRow r : rows) byType.get(byType.containsKey(r.monitorType()) ? r.monitorType() : null).add(r);

        List<TypeGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<OutageRow>> en : byType.entrySet()) {
            if (en.getValue().isEmpty()) continue;
            groups.add(new TypeGroup(en.getKey(),
                    en.getKey() == null ? "Diğer" : MonitorTypeCatalog.label(en.getKey()),
                    List.copyOf(en.getValue())));
        }

        List<OutageRow> longest = rows.stream()
                .sorted(Comparator.comparingLong(OutageRow::durationMin).reversed())
                .limit(10).toList();

        // Tekrar edenler: aynı (hedef, alarm tipi) ≥2 → kronik sorun; tekil olaydan ayrılır.
        //
        // Anahtar bir KAYIT; iki alan tek dizeye paketlenip sonra ayrıştırılmıyor. Ayırıcı yaklaşımı
        // burada sessizce YANLIŞ olurdu: hedef adlarında boşluk bulunabiliyor (canlı veride
        // "http://localhost:8080/health- Orjinal" gibi), yani boşlukla bölmek hedefi ikiye keser ve
        // alarm tipini yanlış okurdu. Kayıt anahtarında ayırıcı diye bir sorun yoktur.
        Map<RepeatKey, Integer> repeatCounts = new LinkedHashMap<>();
        for (OutageRow r : rows) repeatCounts.merge(new RepeatKey(r.target(), r.alertType()), 1, Integer::sum);
        List<RepeatItem> repeats = repeatCounts.entrySet().stream()
                .filter(en -> en.getValue() >= 2)
                .map(en -> {
                    String at = en.getKey().alertType();
                    return new RepeatItem(en.getKey().target(), at,
                            MonitorTypeCatalog.label(MonitorTypeCatalog.typeOfAlert(at)), en.getValue());
                })
                .sorted(Comparator.comparingInt(RepeatItem::count).reversed())
                .toList();

        int prevAlarms = prevWeekAlarmCount(team.getId(), w);

        return new WeeklyOutageData(
                team.getName(), w.weekLabel(), ZonedDateTime.now(IST).format(STAMP),
                rows.size(),
                (int) rows.stream().filter(OutageRow::stillOpen).count(),
                (int) rows.stream().filter(OutageRow::carriedOver).count(),
                (int) rows.stream().map(OutageRow::target).filter(Objects::nonNull).distinct().count(),
                rows.stream().mapToLong(OutageRow::weekDurationMin).sum(),
                avgAvailability(availabilityRows),
                (int) availabilityRows.stream().filter(r -> r.outageCount() > 0).count(),
                prevAlarms, rows.size() - prevAlarms,
                typeStats(team.getId(), w),
                groups, longest, repeats,
                dayBuckets(rows), hourBuckets(rows), levelBuckets(rows),
                heatRows(rows), timelineRows(rows), durationMin(parseOrEpoch(w.fromUtc()), w.windowEnd()),
                rows.stream().filter(OutageRow::stillOpen).toList(),
                rows.stream().filter(OutageRow::notifyGap).toList(),
                availabilityRows, certExpiries(team.getId()),
                (int) rows.stream().filter(OutageRow::maintenanceOverlap).count(),
                (int) rows.stream().map(OutageRow::stormId).filter(Objects::nonNull).distinct().count());
    }

    private List<MonitoringWeeklyStatsService.TypeStats> typeStats(Long teamId, Window w) {
        try {
            MonitoringWeeklyStatsService.MonitoringStats s =
                    weeklyStatsService.compute(teamId, w.year(), w.week());
            return s == null ? List.of() : s.types();
        } catch (Exception e) {
            // Tür özeti raporun tamamlayıcı bölümü; alınamazsa kesinti detayı yine de gitmeli.
            log.warn("Haftalık tür istatistikleri alınamadı (team={} {}-W{}): {}",
                    teamId, w.year(), w.week(), e.getMessage());
            return List.of();
        }
    }

    private int prevWeekAlarmCount(Long teamId, Window w) {
        try {
            LocalDate prevMonday = com.sitemonitor.service.WeeklyAvailabilityReportService
                    .mondayOfIsoWeek(w.year(), w.week()).minusWeeks(1);
            String from = isoStart(prevMonday);
            String to = isoEnd(prevMonday.plusDays(6));
            int n = 0;
            for (Object[] r : alertEventRepo.countFilteredByType(
                    null, from, to, null, null, null, true, List.of(teamId))) {
                n += ((Number) r[1]).intValue();
            }
            return n;
        } catch (Exception e) {
            log.warn("Önceki hafta alarm sayısı alınamadı (team={}): {}", teamId, e.getMessage());
            return 0;
        }
    }

    private List<Bucket> dayBuckets(List<OutageRow> rows) {
        int[] counts = new int[7];
        for (OutageRow r : rows) {
            Instant i = parse(r.startedAt());
            if (i == null) continue;
            counts[i.atZone(IST).getDayOfWeek().getValue() - 1]++;
        }
        List<Bucket> out = new ArrayList<>(7);
        for (int d = 0; d < 7; d++) out.add(new Bucket(DAY_TR[d], counts[d]));
        return out;
    }

    /** Yalnız alarm DÜŞEN saatler yazılır — 24 satırın 19'u sıfır olsaydı desen okunmazdı. */
    private List<Bucket> hourBuckets(List<OutageRow> rows) {
        Map<Integer, Integer> m = new TreeMap<>();
        for (OutageRow r : rows) {
            Instant i = parse(r.startedAt());
            if (i == null) continue;
            m.merge(i.atZone(IST).getHour(), 1, Integer::sum);
        }
        return m.entrySet().stream()
                .map(en -> new Bucket(String.format("%02d:00", en.getKey()), en.getValue()))
                .toList();
    }

    /**
     * Seviye dağılımı — halka grafiği için. Sıra SABİT (kritik → yüksek → uyarı → diğer);
     * veri sırasına bırakılsaydı iki haftanın grafiğinde aynı renk farklı seviyeyi gösterirdi.
     */
    private List<Bucket> levelBuckets(List<OutageRow> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String lvl : LEVEL_ORDER) counts.put(lvl, 0);
        for (OutageRow r : rows) {
            String lvl = r.level() == null ? "OTHER" : r.level().toUpperCase(Locale.ROOT);
            counts.merge(LEVEL_ORDER.contains(lvl) ? lvl : "OTHER", 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .filter(en -> en.getValue() > 0)
                .map(en -> new Bucket(en.getKey(), en.getValue()))
                .toList();
    }

    /** Sabit seviye sırası — grafik renkleri hafta hafta kaymasın. */
    static final List<String> LEVEL_ORDER = List.of("CRITICAL", "HIGH", "WARNING", "INFO", "OTHER");

    /** Gün × saat ısı haritası (satır = gün, sütun = saat, Europe/Istanbul). */
    private List<HeatRow> heatRows(List<OutageRow> rows) {
        int[][] grid = new int[7][24];
        for (OutageRow r : rows) {
            Instant i = parse(r.startedAt());
            if (i == null) continue;
            ZonedDateTime z = i.atZone(IST);
            grid[z.getDayOfWeek().getValue() - 1][z.getHour()]++;
        }
        List<HeatRow> out = new ArrayList<>(7);
        for (int d = 0; d < 7; d++) {
            List<Integer> hours = new ArrayList<>(24);
            for (int h = 0; h < 24; h++) hours.add(grid[d][h]);
            out.add(new HeatRow(DAY_TR[d], List.copyOf(hours)));
        }
        return out;
    }

    /**
     * Zaman çizelgesi satırları — hedef başına, kesinti süresi en uzun olan üstte.
     *
     * <p>Sıfır uzunluklu (haftaya hiç düşmeyen) aralıklar atılır: çizilemeyecek bir çubuk için
     * satır ayırmak çizelgeyi seyreltir ve asıl deseni gizler.
     */
    private List<TimelineRow> timelineRows(List<OutageRow> rows) {
        Map<String, List<TimelineSegment>> byTarget = new LinkedHashMap<>();
        for (OutageRow r : rows) {
            if (r.weekDurationMin() <= 0 || r.target() == null) continue;
            byTarget.computeIfAbsent(r.target(), k -> new ArrayList<>())
                    .add(new TimelineSegment(r.weekStartOffsetMin(), r.weekDurationMin(),
                            r.level(), r.stillOpen()));
        }
        return byTarget.entrySet().stream()
                .map(en -> new TimelineRow(en.getKey(), List.copyOf(en.getValue()),
                        unionMinutes(en.getValue())))
                .sorted(Comparator.comparingLong(TimelineRow::totalMin).reversed())
                .toList();
    }

    /**
     * Aralıkların BİRLEŞİMİ — çakışan alarmlar iki kez sayılmaz.
     *
     * <p>Çizelgedeki satır süresi "bu hedef haftanın ne kadarında kesintideydi" diye okunur ve
     * 7 günü aşamaz. Ham toplam alınsaydı aynı anda düşen sertifika + HTTP + port alarmları
     * üst üste sayılır ve canlı veride görüldüğü gibi <b>yedi günlük bir çizelgede 21 gün</b>
     * yazardı. Çizilen çubuklar da zaten üst üste bindiği için sayı ile resim çelişirdi.
     *
     * <p>Alarm başına ham süreler kaybolmuyor: detay tablosu ve özet onları ayrıca gösteriyor.
     */
    static long unionMinutes(List<TimelineSegment> segments) {
        List<long[]> spans = segments.stream()
                .map(s -> new long[]{ s.startOffsetMin(), s.startOffsetMin() + s.durationMin() })
                .sorted(Comparator.comparingLong(a -> a[0]))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        long total = 0, curStart = -1, curEnd = -1;
        for (long[] s : spans) {
            if (curEnd < 0) { curStart = s[0]; curEnd = s[1]; continue; }
            if (s[0] <= curEnd) {                      // çakışıyor ya da bitişik → birleştir
                curEnd = Math.max(curEnd, s[1]);
            } else {
                total += curEnd - curStart;
                curStart = s[0]; curEnd = s[1];
            }
        }
        if (curEnd >= 0) total += curEnd - curStart;
        return total;
    }

    private List<CertExpiry> certExpiries(Long teamId) {
        List<CertExpiry> out = new ArrayList<>();
        for (CertificateInventory inv :
                inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(teamId)) {
            Integer days = latestCheckRepo.findById(inv.getDomain())
                    .map(LatestCheck::getDaysRemaining).orElse(null);
            if (days != null && days <= CERT_EXPIRY_DAYS) out.add(new CertExpiry(inv.getDomain(), days));
        }
        out.sort(Comparator.comparingInt(CertExpiry::daysRemaining));
        return out;
    }

    private static Double avgAvailability(List<AvailabilityRow> rows) {
        List<AvailabilityRow> withData = rows.stream().filter(r -> r.availabilityPct() != null).toList();
        if (withData.isEmpty()) return null;
        double avg = withData.stream().mapToDouble(AvailabilityRow::availabilityPct).average().orElse(0);
        double rounded = Math.round(avg * 100.0) / 100.0;
        // Bir domain bile %100 değilse ortalama yanıltıcı şekilde 100.00 gösterilmesin
        // (haftalık e-postanın summarize'ı ile aynı kural — iki yerde farklı yuvarlarsak çelişirler).
        if (rounded >= 100.0 && withData.stream().anyMatch(r -> r.availabilityPct() < 100.0)) return 99.99;
        return rounded;
    }

    // ── PDF üretimi ──────────────────────────────────────────────────────────

    /**
     * Veriyi PDF'e döker. Üretim patlarsa {@code byte[0]} döner ve haftalık rapor EK OLMADAN
     * yine gider — aylık envanter raporundaki dayanıklılık deseninin aynısı. Haftalık rapor bir
     * PDF hatası yüzünden hiç gitmemezlik etmemeli.
     *
     * <p>Satır sınırı uygulanmadığı için (kullanıcı kararı) belge yoğun bir haftada büyüyebilir;
     * boyut ve satır sayısı bu yüzden loglanır — kurumsal SMTP ek sınırına takılırsa sebebi ilk
     * bakışta görünsün.
     */
    public byte[] pdf(WeeklyOutageData d) {
        try (WeeklyOutagePdfWriter writer = new WeeklyOutagePdfWriter()) {
            byte[] bytes = writer.write(d);
            log.info("Haftalık kesinti PDF'i: takım={} hafta={} alarm={} boyut={} KB",
                    d.teamName(), d.weekLabel(), d.totalAlarms(), bytes.length / 1024);
            return bytes;
        } catch (Exception e) {
            log.warn("Haftalık kesinti PDF'i üretilemedi (takım={} hafta={}) — mail EK OLMADAN gönderilecek: {}",
                    d.teamName(), d.weekLabel(), e.toString(), e);
            return new byte[0];
        }
    }

    /** Ek dosya adı — takım adı dosya adına güvenli hâle getirilir. */
    public static String fileName(String teamName, Window w) {
        String slug = (teamName == null ? "takim" : teamName)
                .replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("(^-|-$)", "")
                .toLowerCase(Locale.of("tr", "TR"));
        return "haftalik-kesinti-raporu_" + slug + "_" + w.year() + "-W"
                + String.format("%02d", w.week()) + ".pdf";
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    /** Süre dakika cinsinden; negatif fark 0'a kırpılır (saat kayması/bozuk damga). */
    static long durationMin(Instant from, Instant to) {
        if (from == null || to == null) return 0;
        long ms = to.toEpochMilli() - from.toEpochMilli();
        return ms <= 0 ? 0 : Math.round(ms / 60000.0);
    }

    /** "2g 4s 12d" — ham dakika sayısı raporda okunmaz. */
    public static String humanDuration(long minutes) {
        if (minutes <= 0) return "< 1 dk";
        long d = minutes / 1440, h = (minutes % 1440) / 60, m = minutes % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("g ");
        if (h > 0) sb.append(h).append("sa ");
        if (m > 0 || sb.isEmpty()) sb.append(m).append("dk");
        return sb.toString().trim();
    }

    /** Backend zaman damgaları saat dilimi EKİ OLMADAN yazılıyor → UTC olarak ayrıştır. */
    static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return Instant.parse(iso.endsWith("Z") ? iso : iso + "Z"); }
        catch (Exception e) { return null; }
    }

    private static Instant parseOrEpoch(String iso) {
        Instant i = parse(iso);
        return i == null ? Instant.EPOCH : i;
    }

    private static String isoStart(LocalDate d) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(java.time.ZoneOffset.UTC).format(d.atStartOfDay(IST).toInstant());
    }

    private static String isoEnd(LocalDate d) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(java.time.ZoneOffset.UTC).format(d.atTime(23, 59, 59).atZone(IST).toInstant());
    }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0L; }

    /** Türkçe saat dilimine çevrilmiş kısa damga — "16.08 14:23". Ham UTC ISO raporda okunmaz. */
    public static String shortStamp(String iso) {
        Instant i = parse(iso);
        return i == null ? "—" : DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(IST).format(i);
    }

    /** Kapsam notunda kullanılan tür listesi — katalogdan türer, elle yazılmaz. */
    public static String coveredTypesSentence() {
        List<String> labels = MonitorTypeCatalog.ORDER.stream().map(MonitorTypeCatalog::label).toList();
        return String.join(", ", labels);
    }

    /** Katalogda bağlı alarm tipi sayısı — kapsam notunda "N alarm tipi" olarak yazılır. */
    public static int coveredAlertTypeCount() {
        Set<String> all = MonitorTypeCatalog.allAlertTypes();
        return all.size();
    }
}
