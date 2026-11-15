package com.sitemonitor.service.dbquery;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Türkiye resmî tatil takvimi 2026–2035 (2026-11-15, kullanıcı isteği: "Tatil takvimi ayarlarını 10 yıllık girelim") — TEK
 * veri kümesi: {@link HolidayCalendarSeed} boş tabloya bunu yazar, {@link HolidayCalendarTenYearFill} var olan kurulumlarda
 * hiç günü olmayan yılları bundan tamamlar.
 *
 * <p><b>Kaynak (erişim 2026-11-15):</b> Diyanet İşleri Başkanlığı, Vakit Hesaplama — "Dini Günler" listeleri
 * (https://vakithesaplama.diyanet.gov.tr/dinigunler.php?yil=2026, icerik.php?icerik=154 (2027), 185–192 (2028–2035)) ve
 * "Resmi Tatiller" listeleri (icerik.php?icerik=193–200; 2429 sayılı Kanun + 6752 sayılı Kanun değişikliği). Dini bayram
 * günleri ikinci kaynakla karşılaştırıldı: timeanddate.com Türkiye tatilleri (2028–2035 Kurban, 2030–2035 Ramazan — sitede
 * "tentative") ve Umm al-Qura takvimi (Wikipedia "Eid al-Fitr" tablosu — 2028 ve 2029 Ramazan; timeanddate bu iki yılda
 * bir gün sonrasını gösterir, Diyanet'in hesaplı takvimi Umm al-Qura ile örtüşür). 2026 ve 2027 ilk tohumun (2026-10-31)
 * tarihleriyle birebir aynıdır.
 *
 * <p><b>Resmî karar farklı olabilir.</b> Bayram tarihleri Diyanet'in önceden yayımlanmış hesaplı takvimidir; Cumhurbaşkanlığı
 * genelgesi / kararname farklı ilan ederse (ya da köprü gün eklerse) yönetici Ayarlar → Platform → Tatil takviminden düzeltir.
 * KÖPRÜ günleri (idari izin) her yıl ayrıca kararlaştırıldığı için burada YOK.
 *
 * <p><b>Biçim</b> (ilk tohumla aynı): satır = {@code {yyyy-MM-dd, ad}}, gün sırasıyla, gün tekil. Arife yarım günü TAM gün
 * sayılır ve adı {@value #ARIFE} ile biter. Aynı güne iki tatil düşerse TEK satır, adlar {@value #JOIN} ile birleşir: önce
 * dini bayram günü, sonra sabit tarihli tatil, en son arife (2027-05-19, 2029-04-23, 2033-01-01).
 */
public final class HolidayCalendarDataset {

    public static final int FIRST_YEAR = 2026;
    public static final int LAST_YEAR = 2035;

    static final String ARIFE = " — arife (yarım gün)";
    static final String JOIN = " · ";
    static final String RAMAZAN = "Ramazan Bayramı";
    static final String KURBAN = "Kurban Bayramı";
    static final int RAMAZAN_DAYS = 3;
    static final int KURBAN_DAYS = 4;

    /**
     * Ramazan Bayramı 1. günleri (Diyanet "Dini Günler"). 2033'te iki Ramazan Bayramı var (Hicri yıl ~11 gün kayar: 2 Ocak
     * ve 23 Aralık); arifesi bir önceki güne yazılır (2033-01-01 Yılbaşı ile aynı gün).
     */
    static final List<LocalDate> RAMAZAN_FIRST_DAYS = List.of(
            LocalDate.of(2026, 3, 20),    // 1 Şevval 1447
            LocalDate.of(2027, 3, 9),     // 1 Şevval 1448
            LocalDate.of(2028, 2, 26),    // 1 Şevval 1449
            LocalDate.of(2029, 2, 14),    // 1 Şevval 1450
            LocalDate.of(2030, 2, 4),     // 1 Şevval 1451
            LocalDate.of(2031, 1, 24),    // 1 Şevval 1452
            LocalDate.of(2032, 1, 14),    // 1 Şevval 1453
            LocalDate.of(2033, 1, 2),     // 1 Şevval 1454
            LocalDate.of(2033, 12, 23),   // 1 Şevval 1455
            LocalDate.of(2034, 12, 12),   // 1 Şevval 1456
            LocalDate.of(2035, 12, 1));   // 1 Şevval 1457

    /** Kurban Bayramı 1. günleri (Diyanet "Dini Günler"). 2029'da arifesi 23 Nisan ile aynı gün. */
    static final List<LocalDate> KURBAN_FIRST_DAYS = List.of(
            LocalDate.of(2026, 5, 27),    // 10 Zilhicce 1447
            LocalDate.of(2027, 5, 16),    // 10 Zilhicce 1448
            LocalDate.of(2028, 5, 5),     // 10 Zilhicce 1449
            LocalDate.of(2029, 4, 24),    // 10 Zilhicce 1450
            LocalDate.of(2030, 4, 13),    // 10 Zilhicce 1451
            LocalDate.of(2031, 4, 2),     // 10 Zilhicce 1452
            LocalDate.of(2032, 3, 22),    // 10 Zilhicce 1453
            LocalDate.of(2033, 3, 11),    // 10 Zilhicce 1454
            LocalDate.of(2034, 3, 1),     // 10 Zilhicce 1455
            LocalDate.of(2035, 2, 18));   // 10 Zilhicce 1456

    /** Cumhuriyet Bayramı arifesi (28 Ekim öğleden sonra — "1,5 gün"); öteki sabit günler {@link HolidayCalendarService#FIXED_PUBLIC_HOLIDAYS}. */
    static final String[] REPUBLIC_EVE = {"10-28", "Cumhuriyet Bayramı arifesi" + ARIFE};

    /** Gün sırasıyla {@code {yyyy-MM-dd, ad}} — 2026-01-01 … 2035-12-31 arası; değiştirilemez. */
    public static final List<String[]> DAYS = build();

    private HolidayCalendarDataset() {
    }

    /** Yıla göre gruplanmış satırlar (yıl artan sırada; yıl içinde gün sırası). */
    public static Map<Integer, List<String[]>> byYear() {
        Map<Integer, List<String[]>> out = new LinkedHashMap<>();
        for (String[] d : DAYS) out.computeIfAbsent(Integer.parseInt(d[0].substring(0, 4)), y -> new ArrayList<>()).add(d);
        return out;
    }

    /** Ad parçası: sıra 0 = dini bayram günü, 1 = sabit tarihli tatil, 2 = arife (yarım gün). */
    private record Part(int rank, String name) {}

    private static List<String[]> build() {
        TreeMap<LocalDate, List<Part>> days = new TreeMap<>();
        for (LocalDate first : RAMAZAN_FIRST_DAYS) addFeast(days, first, RAMAZAN, RAMAZAN_DAYS);
        for (LocalDate first : KURBAN_FIRST_DAYS) addFeast(days, first, KURBAN, KURBAN_DAYS);
        for (int y = FIRST_YEAR; y <= LAST_YEAR; y++) {
            for (String[] f : HolidayCalendarService.FIXED_PUBLIC_HOLIDAYS) put(days, LocalDate.parse(y + "-" + f[0]), 1, f[1]);
            put(days, LocalDate.parse(y + "-" + REPUBLIC_EVE[0]), 2, REPUBLIC_EVE[1]);
        }
        LocalDate from = LocalDate.of(FIRST_YEAR, 1, 1);
        LocalDate to = LocalDate.of(LAST_YEAR, 12, 31);
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Part>> e : days.entrySet()) {
            if (e.getKey().isBefore(from) || e.getKey().isAfter(to)) continue;
            List<Part> parts = new ArrayList<>(e.getValue());
            parts.sort((a, b) -> Integer.compare(a.rank(), b.rank()));   // kararlı: aynı sırada ekleme düzeni
            List<String> names = new ArrayList<>();
            for (Part p : parts) names.add(p.name());
            out.add(new String[]{e.getKey().toString(), String.join(JOIN, names)});
        }
        return Collections.unmodifiableList(out);
    }

    private static void addFeast(TreeMap<LocalDate, List<Part>> days, LocalDate first, String feast, int length) {
        put(days, first.minusDays(1), 2, feast + " arifesi" + ARIFE);
        for (int i = 0; i < length; i++) put(days, first.plusDays(i), 0, feast + " " + (i + 1) + ". gün");
    }

    private static void put(TreeMap<LocalDate, List<Part>> days, LocalDate day, int rank, String name) {
        days.computeIfAbsent(day, k -> new ArrayList<>()).add(new Part(rank, name));
    }
}
