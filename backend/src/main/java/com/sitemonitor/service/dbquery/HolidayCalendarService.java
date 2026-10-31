package com.sitemonitor.service.dbquery;

import com.sitemonitor.model.HolidayDay;
import com.sitemonitor.repository.HolidayDayRepository;
import com.sitemonitor.service.cache.CacheBus;
import com.sitemonitor.service.cache.CacheBusNames;
import com.sitemonitor.util.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tatil / özel gün takvimi (2026-10-31): zamanlayıcı ve zamanlama doğrulaması her vadede {@link #days()} çağırır → 60 sn
 * bellek özeti; okuma hatası önceki özeti korur (ilk okumada hata = boş küme — "tatil yok" sayılır, izleme çalışır).
 *
 * <p><b>Yönetim</b> (Ayarlar → Platform → Tatil takvimi, global yönetici): {@link #replaceAll} listeyi TAMAMEN değiştirir
 * (≤ {@value #MAX_DAYS} gün, tarih tekil, ad ≤ {@value #MAX_NAME}). Değişen gün yeniden yazılır, değişmeyenin künyesi korunur.
 *
 * <p><b>Çok pod.</b> Kayıt bu pod'un özetini bayat işaretler ve önbellek veri yoluna ({@link CacheBusNames#HOLIDAYS})
 * yayımlar — öteki pod'lar bir yoklama aralığında (~2 sn) tazeler (60 sn TTL'i beklemez). Yerel boşaltıcı ucuzdur (veritabanı
 * okumaz), yeniden yayımlamaz ve özeti SİLMEZ: bir sonraki okuma veritabanı hatasıyla karşılaşırsa önceki görüntü kalır.
 * Kuşak sayacı: boşaltmadan ÖNCE başlamış bir okuma özeti "taze" diye saklamaz.
 */
@Service
public class HolidayCalendarService {

    private static final Logger log = LoggerFactory.getLogger(HolidayCalendarService.class);
    static final long CACHE_MS = 60_000L;
    public static final int MAX_DAYS = 500, MAX_NAME = 120;
    /** Kabul edilen yıl aralığı — yazım hatası (ör. 20267) takvimi bozmasın. */
    static final int MIN_YEAR = 2000, MAX_YEAR = 2100;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final HolidayDayRepository repo;
    private volatile Set<LocalDate> snapshot = Set.of();
    private volatile long loadedAtMs = -1;
    private final AtomicLong generation = new AtomicLong();
    private volatile CacheBus cacheBus;

    public HolidayCalendarService(HolidayDayRepository repo) {
        this.repo = repo;
    }

    @Autowired(required = false)
    void setCacheBus(CacheBus bus) {
        this.cacheBus = bus;
        if (bus != null) bus.register(CacheBusNames.HOLIDAYS, this::invalidateLocal);
    }

    public Set<LocalDate> days() {
        long now = System.currentTimeMillis();
        if (loadedAtMs < 0 || now - loadedAtMs > CACHE_MS) {
            long gen = generation.get();
            try {
                Set<LocalDate> out = new HashSet<>();
                for (HolidayDay d : repo.findAll()) {
                    try {
                        out.add(LocalDate.parse(d.getDay()));
                    } catch (RuntimeException ignored) {
                        // bozuk satır atlanır
                    }
                }
                snapshot = Set.copyOf(out);
            } catch (RuntimeException e) {
                log.warn("Tatil takvimi okunamadı — önceki özet kullanılıyor: {}", e.getClass().getSimpleName());
            }
            // Okuma sürerken takvim değiştiyse (boşaltma geldi) özet taze SAYILMAZ: sonraki çağrı yeniden okur.
            loadedAtMs = generation.get() == gen ? now : -1;
        }
        return snapshot;
    }

    /** Takvim değişti — bir sonraki okuma yeniden yükler; öteki pod'lara da yayımlar. */
    public void invalidate() {
        invalidateLocal();
        CacheBus bus = cacheBus;
        if (bus != null) bus.bump(CacheBusNames.HOLIDAYS);
    }

    /** Yalnız bu pod (yeniden yayın YOK — ping-pong olmasın). Özet silinmez, bayat işaretlenir. */
    void invalidateLocal() {
        generation.incrementAndGet();
        loadedAtMs = -1;
    }

    /** Yönetim listesi: gün sırasıyla {@code [{day, name}]}. Okunamazsa istisna (çağıran "okunamadı" der — boş liste DEĞİL). */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (HolidayDay d : repo.findAllByOrderByDayAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", d.getDay());
            m.put("name", d.getName());
            out.add(m);
        }
        return out;
    }

    /** Takvimin "yakında bitiyor" eşiği (r177, H-7): son tatil günü bugünden en çok bu kadar gün sonraysa uyarılır. */
    public static final int ENDS_SOON_DAYS = 60;

    /**
     * Sabit tarihli resmî tatiller (r177): ay-gün + ad. Dini bayramlar (Ramazan / Kurban) her yıl kaydığı için BURADA YOK —
     * elle girilir (ekran ve yanıt bunu söyler).
     */
    public static final List<String[]> FIXED_PUBLIC_HOLIDAYS = List.of(
            new String[]{"01-01", "Yılbaşı"},
            new String[]{"04-23", "Ulusal Egemenlik ve Çocuk Bayramı"},
            new String[]{"05-01", "Emek ve Dayanışma Günü"},
            new String[]{"05-19", "Atatürk'ü Anma, Gençlik ve Spor Bayramı"},
            new String[]{"07-15", "Demokrasi ve Milli Birlik Günü"},
            new String[]{"08-30", "Zafer Bayramı"},
            new String[]{"10-29", "Cumhuriyet Bayramı"});

    /**
     * Takvimin kapsadığı son gün (r177): en geç tatil günü ya da takvim boşsa null. {@code ends_soon} = son gün bugünden
     * ({@code today}, Europe/Istanbul) {@value #ENDS_SOON_DAYS} günden az sonra ya da takvim boş — "tatillerde çalışma"
     * açık izlemeler o tarihten sonra tatillerde de çalışır.
     */
    public static Map<String, Object> coverage(List<Map<String, Object>> days, LocalDate today) {
        LocalDate last = null;
        if (days != null) {
            for (Map<String, Object> d : days) {
                LocalDate v = d == null ? null : parseDay(d.get("day") == null ? null : String.valueOf(d.get("day")));
                if (v != null && (last == null || v.isAfter(last))) last = v;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("coverage_until", last == null ? null : last.toString());
        out.put("ends_soon", last == null || !last.isAfter(today.plusDays(ENDS_SOON_DAYS)));
        out.put("ends_soon_days", ENDS_SOON_DAYS);
        return out;
    }

    /**
     * Bir yılın sabit tarihli resmî tatillerini ekler (r177, H-7): VAR OLAN güne (aynı tarih — adı farklı olsa da) dokunmaz.
     * @return eklenen gün sayısı
     */
    @Transactional
    public int addFixed(int year, String actorName) {
        if (year < MIN_YEAR || year > MAX_YEAR) throw new IllegalArgumentException("yıl aralık dışı");
        Set<String> existing = new HashSet<>();
        for (HolidayDay d : repo.findAll()) existing.add(d.getDay());
        String now = ISO.format(Instant.now());
        List<HolidayDay> write = new ArrayList<>();
        for (String[] f : FIXED_PUBLIC_HOLIDAYS) {
            String day = year + "-" + f[0];
            if (existing.contains(day) || existing.size() + write.size() >= MAX_DAYS) continue;
            HolidayDay e = new HolidayDay();
            e.setDay(day);
            e.setName(f[1]);
            e.setCreatedAt(now);
            e.setCreatedByName(actorName);
            write.add(e);
        }
        if (!write.isEmpty()) {
            repo.saveAll(write);
            invalidateAfterCommit();
        }
        return write.size();
    }

    /** Gönderilen tek gün (doğrulanmış). */
    public record Day(String day, String name) {}

    /** Kaydın sonucu (denetim ayrıntısı): eklenen / silinen / adı değişen gün sayıları + toplam. */
    public record Change(int added, int removed, int renamed, int total) {
        public boolean changed() { return added + removed + renamed > 0; }
    }

    /**
     * Gönderilen listeyi doğrular (ad / tarih / tekillik / sınır). Hata → {@link com.sitemonitor.controller.DbQueryApiException}
     * (400, alan {@code days} ya da {@code days[N].day|name}).
     */
    public static List<Day> parse(Object raw) {
        Map<String, String> errs = new LinkedHashMap<>();
        if (!(raw instanceof List<?> list)) {
            errs.put("days", Msg.t("Gün listesi bekleniyor: [{day, name}, …].", "A list of days is expected: [{day, name}, …]."));
            throw com.sitemonitor.controller.DbQueryApiException.validation(errs.get("days"), errs);
        }
        if (list.size() > MAX_DAYS) {
            errs.put("days", Msg.t("Takvimde en çok " + MAX_DAYS + " gün olabilir.", "The calendar can have at most " + MAX_DAYS + " days."));
            throw com.sitemonitor.controller.DbQueryApiException.validation(errs.get("days"), errs);
        }
        List<Day> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            String key = "days[" + i + "]";
            if (!(o instanceof Map<?, ?> m)) {
                errs.put(key, Msg.t("Her satır {day, name} olmalı.", "Each row must be {day, name}."));
                continue;
            }
            String day = m.get("day") == null ? null : m.get("day").toString().trim();
            String name = m.get("name") == null ? null : m.get("name").toString().trim();
            LocalDate d = parseDay(day);
            if (d == null) {
                errs.put(key + ".day", Msg.t("Tarih yyyy-MM-dd biçiminde geçerli bir gün olmalı (" + MIN_YEAR + "–" + MAX_YEAR + ").",
                        "The date must be a valid day in yyyy-MM-dd format (" + MIN_YEAR + "–" + MAX_YEAR + ")."));
            } else if (!seen.add(d.toString())) {
                errs.put(key + ".day", Msg.t("Bu gün listede birden fazla kez var: " + d + ".", "This day is listed more than once: " + d + "."));
            }
            if (name == null || name.isEmpty()) {
                errs.put(key + ".name", Msg.t("Güne bir ad verin (ör. Cumhuriyet Bayramı).", "Give the day a name (e.g. Republic Day)."));
            } else if (name.length() > MAX_NAME || hasControl(name)) {
                errs.put(key + ".name", Msg.t("Ad en çok " + MAX_NAME + " karakter olabilir ve denetim karakteri içeremez.",
                        "The name can be at most " + MAX_NAME + " characters and can't contain control characters."));
            }
            if (d != null && name != null && !name.isEmpty()) out.add(new Day(d.toString(), name));
        }
        if (!errs.isEmpty()) {
            throw com.sitemonitor.controller.DbQueryApiException.validation(Msg.t(
                    "Tatil takvimi kaydedilemedi: işaretli satırları düzeltin.",
                    "The holiday calendar couldn't be saved: fix the marked rows."), errs);
        }
        return out;
    }

    /** Listeyi TAMAMEN değiştirir; değişmeyen günün künyesi korunur. Kayıttan sonra özet bayatlar (bu pod + veri yolu). */
    @Transactional
    public Change replaceAll(List<Day> days, String actorName) {
        Map<String, HolidayDay> current = new LinkedHashMap<>();
        for (HolidayDay d : repo.findAll()) current.put(d.getDay(), d);
        Map<String, Day> next = new LinkedHashMap<>();
        for (Day d : days) next.put(d.day(), d);
        int added = 0, removed = 0, renamed = 0;
        List<HolidayDay> gone = new ArrayList<>();
        for (HolidayDay d : current.values()) {
            if (!next.containsKey(d.getDay())) { gone.add(d); removed++; }
        }
        if (!gone.isEmpty()) repo.deleteAll(gone);
        String now = ISO.format(Instant.now());
        List<HolidayDay> write = new ArrayList<>();
        for (Day d : next.values()) {
            HolidayDay e = current.get(d.day());
            if (e == null) {
                e = new HolidayDay();
                e.setDay(d.day());
                e.setName(d.name());
                e.setCreatedAt(now);
                e.setCreatedByName(actorName);
                write.add(e);
                added++;
            } else if (!d.name().equals(e.getName())) {
                e.setName(d.name());
                write.add(e);
                renamed++;
            }
        }
        if (!write.isEmpty()) repo.saveAll(write);
        Change c = new Change(added, removed, renamed, next.size());
        if (c.changed()) invalidateAfterCommit();
        return c;
    }

    /**
     * İşlem içinden çağrılırsa bu pod'un özeti İŞLEM SONRASI da bayatlar (işlem sürerken başlamış bir okuma eski listeyi taze
     * diye saklamasın); veri yolu yayını zaten işlem sonuna ertelenir ve geri alınan işlem yayın üretmez.
     */
    private void invalidateAfterCommit() {
        invalidateLocal();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { invalidateLocal(); }
                    });
        }
        CacheBus bus = cacheBus;
        if (bus != null) bus.bump(CacheBusNames.HOLIDAYS);
    }

    static LocalDate parseDay(String day) {
        if (day == null || day.length() != 10) return null;
        try {
            LocalDate d = LocalDate.parse(day, DAY);
            return d.getYear() < MIN_YEAR || d.getYear() > MAX_YEAR ? null : d;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) return true;
        }
        return false;
    }
}
