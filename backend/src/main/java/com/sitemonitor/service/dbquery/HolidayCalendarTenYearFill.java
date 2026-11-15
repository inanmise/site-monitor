package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.evalstate.EvalStateSql;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEK SEFERLİK tatil takvimi genişletmesi (2026-11-15, kullanıcı isteği: "Tatil takvimi ayarlarını 10 yıllık girelim"):
 * var olan kurulumlarda {@link HolidayCalendarDataset} (2026–2035) yıllarından {@code holiday_days}'te HİÇ satırı olmayan
 * her yılın günlerini ekler. Satırı olan yıla (yöneticinin düzenlediği, gün sildiği ya da kendi günlerini girdiği yıl)
 * DOKUNULMAZ — silinmiş gün geri gelmez, ad ezilmez.
 *
 * <p><b>İlk tohumla sıra.</b> Boş tabloyu {@link HolidayCalendarSeed} doldurur (aynı veri kümesi). Bu iş onun nişanı
 * yazılmadan başlamaz (okunamazsa ya da yoksa bekler, deneme sayılmaz) — yeni kurulumda tohum on yılı yazar, bu iş eklenecek
 * yıl bulamaz ve yalnız nişanını yazar; nişanı önceki sürümde yazılmış kurulumda 2028–2035 (ve günü olmayan öteki yıllar)
 * eklenir.
 *
 * <p>CLAUDE.md "One-time DATA fixes" kuralı: açılış yolunda DEĞİL (açılıştan 4 dk sonra, sonra saatte bir, nişan yazılana
 * dek), kendi {@code scheduler_lock} kilidi ({@value #LOCK}) altında; nişan ({@value #KEY}) kilit İÇİNDE yeniden okunur ve
 * YALNIZ başarıda yazılır; okunamayan nişan "bitti" sayılmaz; pod başına en çok {@value #MAX_ATTEMPTS} başarısız deneme.
 * Her yıl TEK deyimle ve "o yılın hiç satırı yoksa" koşuluyla yazılır (yarışan elle kayıt yılı korur; yarıda kesilen iş
 * yarım yıl bırakmaz). Takvim {@link HolidayCalendarService#MAX_DAYS} günü aşmaz (aşacak yıl atlanır, WARN) — aşarsa
 * yönetici ekranı listeyi kaydedemezdi.
 */
@Slf4j
@Service
public class HolidayCalendarTenYearFill {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ (değişirse iş yeniden koşar ve yöneticinin sildiği bir yılı geri ekleyebilir). */
    static final String KEY = "2026-11-15-holiday-calendar-10y";
    static final String LOCK = "holiday-calendar-10y";
    static final int MAX_ATTEMPTS = 24;
    /** {@link #fillLocked()} dönüşü: ilk tohumun nişanı henüz yok — beklenir (deneme sayılmaz, nişan yazılmaz). */
    static final int WAITING = -1;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    private final HolidayCalendarService calendar;
    private final AtomicInteger failures = new AtomicInteger();
    private volatile boolean doneSeen;

    public HolidayCalendarTenYearFill(JdbcTemplate jdbc, ObjectProvider<SchedulerService> scheduler,
                                      HolidayCalendarService calendar) {
        this.jdbc = EvalStateSql.withQueryTimeout(jdbc, HolidayCalendarSeed.QUERY_TIMEOUT_SECONDS);
        this.scheduler = scheduler;
        this.calendar = calendar;
    }

    @Scheduled(initialDelayString = "${site.monitor.dbquery.holiday-10y.initial-delay-ms:240000}", fixedDelay = 3_600_000L)
    public void run() {
        if (doneSeen || failures.get() >= MAX_ATTEMPTS) return;
        Boolean mine = HolidayCalendarSeed.markerState(jdbc, KEY);
        if (mine == null) return;                       // bilinmiyor → bu tur atlanır
        if (mine) { doneSeen = true; return; }
        // İlk tohum boş tabloyu doldurmadan başlanmaz (yarış yok); nişanı okunamadı / yok → saat sonra yeniden bakılır
        if (!Boolean.TRUE.equals(HolidayCalendarSeed.markerState(jdbc, HolidayCalendarSeed.KEY))) return;
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null) return;
        try {
            Optional<Integer> r = s.callWithSchedulerLock(LOCK, this::fillLocked);
            if (r.isPresent() && r.get() >= 0) failures.set(0);
        } catch (RuntimeException e) {
            int n = failures.incrementAndGet();
            if (n >= MAX_ATTEMPTS) {
                log.error("Tatil takvimi 10 yıla tamamlanamadı ({} deneme) — bu pod denemeyi bırakıyor (nişan yazılmadı)", n, e);
            } else {
                log.warn("Tatil takvimi 10 yıla tamamlanamadı (bir saat sonra yeniden denenecek): {}", e.toString());
            }
        }
    }

    /**
     * Kilit altında: günü olmayan veri kümesi yıllarını yazar, sonra nişanı. Dönüş: yazılan gün sayısı (0 = eklenecek yıl
     * yoktu), {@link #WAITING} = ilk tohumun nişanı yok (hiçbir şey yazılmadı). Okunamayan nişan → istisna (deneme sayılır).
     */
    Integer fillLocked() {
        Boolean mine = HolidayCalendarSeed.markerState(jdbc, KEY);
        if (mine == null) throw new IllegalStateException("tatil takvimi 10 yıl nişanı okunamadı");
        if (mine) { doneSeen = true; return 0; }
        Boolean seeded = HolidayCalendarSeed.markerState(jdbc, HolidayCalendarSeed.KEY);
        if (seeded == null) throw new IllegalStateException("tatil takvimi tohum nişanı okunamadı");
        if (!seeded) return WAITING;

        Set<Integer> yearsWithRows = new HashSet<>();
        List<String> existing = jdbc.queryForList("SELECT holiday_date FROM holiday_days", String.class);
        for (String d : existing) {
            if (d != null && d.length() >= 4) {
                try {
                    yearsWithRows.add(Integer.parseInt(d.substring(0, 4)));
                } catch (NumberFormatException ignored) {
                    // bozuk satır yıl saymaz
                }
            }
        }
        int total = existing.size();
        String now = ISO.format(Instant.now());
        int written = 0;
        List<Integer> added = new ArrayList<>();
        List<Integer> skippedForCap = new ArrayList<>();
        for (Map.Entry<Integer, List<String[]>> year : HolidayCalendarDataset.byYear().entrySet()) {
            int y = year.getKey();
            if (yearsWithRows.contains(y)) continue;
            List<String[]> rows = year.getValue();
            if (total + rows.size() > HolidayCalendarService.MAX_DAYS) {
                skippedForCap.add(y);
                continue;
            }
            // Tek deyim, koşul içinde: bu arada yönetici o yıla gün kaydettiyse yıla dokunulmaz; yıl ya tam ya hiç yazılır
            int inYear = HolidayCalendarSeed.insertYearIfEmpty(jdbc, y, rows, now);
            if (inYear > 0) {
                written += inYear;
                total += inYear;
                added.add(y);
            }
        }
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                KEY, ISO.format(Instant.now()), written);
        doneSeen = true;
        if (!skippedForCap.isEmpty()) {
            log.warn("Tatil takvimi {} gün sınırına ulaştı — şu yıllar eklenmedi, Ayarlar → Platform → Tatil takviminden elle "
                    + "girin: {}", HolidayCalendarService.MAX_DAYS, skippedForCap);
        }
        if (written > 0) {
            HolidayCalendarSeed.announceCalendarChange(calendar, scheduler.getIfAvailable());
            log.info("Tatil takvimi 10 yıla tamamlandı: {} gün eklendi (yıllar: {}; Diyanet takvimi — resmî karar farklıysa "
                    + "Ayarlar → Platform → Tatil takviminden düzeltin)", written, added);
        } else {
            log.info("Tatil takvimi 10 yıl genişletmesi: eklenecek yıl yok (günü olan yıllara dokunulmadı)");
        }
        return written;
    }
}
