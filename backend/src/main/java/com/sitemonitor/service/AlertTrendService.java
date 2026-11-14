package com.sitemonitor.service;

import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.util.TtlMemo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kişisel Panom "Alarm eğilimi" kutusu (2026-11-14, zaman serisi paketi): görüş kapsamındaki alarmların zaman içinde AÇILAN
 * ve KAPANAN sayısı — son 24 saat (saatlik kovalar) ya da son 7 gün (Europe/Istanbul günleri, bugün dahil).
 *
 * <p><b>Neden yeni uç.</b> Gürültü analizi ({@code /api/admin/alerts/noise}) yalnız günlük AÇILAN sayısını verir (kapanan ve
 * saatlik 24 sa yok), belleksizdir ve hedef / ısı haritası / öneri hesabı da yapar — dakikada bir yoklanan bir kutu için ağır.
 *
 * <p><b>Maliyet.</b> Kurum geneli görüntü ({@link OrgTrend}) pod başına {@code site.monitor.alert-trend.cache-ms} (30 sn)
 * paylaşılır: SABİT 3 dar sorgu — açılanlar ve kapananlar (sahip + UTC saat kovası başına gruplu sayım, ham satır yok) ve
 * envanterin alan adı → SY / UG takımı eşlemesi. Kapsam süzgeci ve kovalama HER çağrıda bellekte uygulanır (kullanıcıya özgü
 * sonuç bellekte tutulmaz). İki aralık aynı görüntüden hesaplanır (görüntü 7 günlük pencereyi kapsar).
 *
 * <p><b>Kapsam</b> Alarm Geçmişi listesiyle ({@code AlertEventRepository.findFiltered}) AYNI: tümünü gören (global görüntüleyici
 * / 7-24 operatörü) her alarmı; diğerleri damgalı takımı ya da hedefinin envanter kaydının SY / UG takımı görüş kapsamında
 * olan alarmı sayar — kutudaki sayı, tıklanınca açılan listeyle tutar.
 *
 * <p><b>Okunamayan veri sıfır DEĞİLDİR:</b> sorgulardan biri düşerse {@link UnavailableException} fırlar (denetleyici 503
 * döner) ve görüntü saklanmaz. Kovalar: saatlik kova UTC saat başıdır (İstanbul saat başıyla aynı an); günlük kova İstanbul
 * takvim günüdür — sunucunun / veritabanının saat dilimine bağlı değildir.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertTrendService {

    public static final String RANGE_DAY = "24h";
    public static final String RANGE_WEEK = "7d";
    public static final List<String> RANGES = List.of(RANGE_DAY, RANGE_WEEK);
    static final int HOURS = 24;
    static final int DAYS = 7;
    static final ZoneId IST = ZoneId.of("Europe/Istanbul");

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String ORG_KEY = "ORG";

    private final AlertEventRepository alertEventRepo;
    private final CertificateInventoryRepository inventoryRepo;

    /** Kurum geneli görüntünün ömrü (ms); 0 → kapalı (birim testinde {@code new} ile kurulunca varsayılan). */
    @Value("${site.monitor.alert-trend.cache-ms:30000}")
    long cacheMs;

    /** Final değil: birim testi saat enjekte edilmiş bellekle değiştirir. */
    private TtlMemo<OrgTrend> orgMemo = new TtlMemo<>(2);
    /** Final değil: birim testi sabit saat verir (kova sınırları). */
    private Clock clock = Clock.systemUTC();

    /** Alarmlar ya da envanter okunamadı — denetleyici 503 döner (kutu "okunamadı" der, asla 0). */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException(Throwable cause) { super("alarm eğilimi okunamadı", cause); }
    }

    /**
     * Gruplu sayım satırı: sahip (damgalı takım + hedef) × UTC saati. {@code hourEpoch} = dönemden beri saat, {@code istDay} =
     * o saatin İstanbul takvim günü (epoch günü) — görüntü kurulurken bir kez hesaplanır.
     */
    record Cell(Long teamId, String domain, long hourEpoch, long istDay, long count, long extra) { }

    /** Kurum geneli görüntü — kullanıcıdan bağımsız; kapsam süzgeci çağrı başına uygulanır. */
    record OrgTrend(List<Cell> opened, List<Cell> closed, Map<String, long[]> owners, String builtAt) { }

    /**
     * @param range       {@link #RANGE_DAY} | {@link #RANGE_WEEK} (çağıran doğrular; bilinmeyen değer 24 saat sayılır)
     * @param seesAll     global görüntüleyici / 7-24 operatörü — kapsam süzgeci yok (takımsız alarmlar dahil)
     * @param scopeTeams  kapsamlı kullanıcının görüş takımları (paylaşımlar dahil); null / boş → hiçbir alarm (sorgusuz)
     */
    public Map<String, Object> build(String range, boolean seesAll, Collection<Long> scopeTeams) {
        boolean week = RANGE_WEEK.equals(range);
        Instant now = clock.instant();

        // Kovalar — saatlik: içinde bulunulan saat + önceki 23 saat; günlük: İstanbul'da bugün + önceki 6 gün
        long firstHour = now.truncatedTo(ChronoUnit.HOURS).getEpochSecond() / 3600 - (HOURS - 1);
        LocalDate firstDay = LocalDate.ofInstant(now, IST).minusDays(DAYS - 1L);
        int size = week ? DAYS : HOURS;
        long[] opened = new long[size], closed = new long[size];
        long critical = 0, silent = 0;

        Set<Long> scope = new HashSet<>();
        if (scopeTeams != null) for (Long id : scopeTeams) if (id != null) scope.add(id);
        OrgTrend org = null;
        if (seesAll || !scope.isEmpty()) {
            org = orgMemo.get(ORG_KEY, cacheMs, false, this::load);
            long firstDayEpoch = firstDay.toEpochDay();
            for (Cell c : org.opened()) {
                int i = index(c, week, firstHour, firstDayEpoch, size);
                if (i < 0 || !visible(c, seesAll, scope, org.owners())) continue;
                opened[i] += c.count();
                critical += c.extra();
            }
            for (Cell c : org.closed()) {
                int i = index(c, week, firstHour, firstDayEpoch, size);
                if (i < 0 || !visible(c, seesAll, scope, org.owners())) continue;
                closed[i] += c.count();
                silent += c.extra();
            }
        }

        List<Map<String, Object>> buckets = new ArrayList<>(size);
        long openedTotal = 0, closedTotal = 0;
        for (int i = 0; i < size; i++) {
            Map<String, Object> b = new LinkedHashMap<>();
            if (week) {
                LocalDate day = firstDay.plusDays(i);
                b.put("t", ISO.format(day.atStartOfDay(IST).toInstant()));
                b.put("day", day.toString());
            } else {
                b.put("t", ISO.format(Instant.ofEpochSecond((firstHour + i) * 3600)));
            }
            b.put("opened", opened[i]);
            b.put("closed", closed[i]);
            buckets.add(b);
            openedTotal += opened[i];
            closedTotal += closed[i];
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("opened", openedTotal);
        totals.put("closed", closedTotal);
        totals.put("critical", critical);
        totals.put("closed_silent", silent);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", week ? RANGE_WEEK : RANGE_DAY);
        out.put("bucket", week ? "day" : "hour");
        out.put("from", String.valueOf(buckets.get(0).get("t")));
        out.put("to", ISO.format(now));
        out.put("buckets", buckets);
        out.put("totals", totals);
        out.put("generated_at", org != null ? org.builtAt() : ISO.format(now));
        return out;
    }

    private static int index(Cell c, boolean week, long firstHour, long firstDayEpoch, int size) {
        long i = week ? c.istDay() - firstDayEpoch : c.hourEpoch() - firstHour;
        return i < 0 || i >= size ? -1 : (int) i;
    }

    /** Alarm Geçmişi kuralı: damgalı takım kapsamda YA DA hedefin envanter kaydının SY / UG takımı kapsamda. */
    private static boolean visible(Cell c, boolean seesAll, Set<Long> scope, Map<String, long[]> owners) {
        if (seesAll) return true;
        if (c.teamId() != null && scope.contains(c.teamId())) return true;
        long[] own = c.domain() == null ? null : owners.get(c.domain());
        if (own == null) return false;
        for (long t : own) if (scope.contains(t)) return true;
        return false;
    }

    /** Kurum geneli görüntü: sabit 3 dar sorgu. Biri düşerse görüntü KURULMAZ (kısmi veri sıfır gibi okunmasın). */
    private OrgTrend load() {
        Instant now = clock.instant();
        // Görüntü 7 günlük pencereyi kapsar (24 saatlik pencere onun içindedir) ve BİR GÜN önceden başlar: çağıran penceresini
        // kendi saatinden kurar; gece yarısında saatini görüntüden hemen önce okuyan (ya da süren hesabı bekleyen) çağrının ilk
        // günü görüntünün dışında kalıp "0" görünmesin. Fazlası kovalamada atılır.
        String since = ISO.format(LocalDate.ofInstant(now, IST).minusDays(DAYS).atStartOfDay(IST).toInstant());
        try {
            List<Cell> opened = cells(alertEventRepo.countOpenedByOwnerAndHour(since));
            List<Cell> closed = cells(alertEventRepo.countClosedByOwnerAndHour(since));
            Map<String, Set<Long>> acc = new HashMap<>();
            for (Object[] r : inventoryRepo.findDomainOwners()) {
                if (r == null || r.length < 2 || r[0] == null) continue;
                Set<Long> ids = acc.computeIfAbsent(String.valueOf(r[0]), k -> new HashSet<>());
                if (r[1] instanceof Number sy) ids.add(sy.longValue());
                if (r.length > 2 && r[2] instanceof Number ug) ids.add(ug.longValue());
            }
            Map<String, long[]> owners = new HashMap<>(acc.size() * 2);
            acc.forEach((domain, ids) -> { if (!ids.isEmpty()) owners.put(domain, ids.stream().mapToLong(Long::longValue).toArray()); });
            return new OrgTrend(opened, closed, owners, ISO.format(now));
        } catch (RuntimeException e) {
            log.debug("alarm eğilimi okunamadı: {}", e.toString());
            throw new UnavailableException(e);
        }
    }

    private static List<Cell> cells(List<Object[]> rows) {
        List<Cell> out = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows == null) return out;
        for (Object[] r : rows) {
            if (r == null || r.length < 4 || r[2] == null) continue;
            Instant hour = hourOf(String.valueOf(r[2]));
            if (hour == null) continue;   // biçimi bozuk damga (eski satır) — kovaya girmez
            long count = r[3] instanceof Number n ? n.longValue() : 0L;
            if (count <= 0) continue;
            long extra = r.length > 4 && r[4] instanceof Number n ? n.longValue() : 0L;
            out.add(new Cell(r[0] instanceof Number t ? Long.valueOf(t.longValue()) : null,
                    r[1] == null ? null : String.valueOf(r[1]),
                    hour.getEpochSecond() / 3600, LocalDate.ofInstant(hour, IST).toEpochDay(), count, extra));
        }
        return out;
    }

    /** {@code yyyy-MM-ddTHH} (UTC) → o saatin başı; çözümlenemezse null. */
    static Instant hourOf(String bucket) {
        if (bucket == null || bucket.length() != 13) return null;
        try {
            return LocalDateTime.parse(bucket + ":00:00").toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
