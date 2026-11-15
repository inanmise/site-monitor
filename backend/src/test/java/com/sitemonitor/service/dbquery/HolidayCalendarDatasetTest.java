package com.sitemonitor.service.dbquery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tatil takvimi veri kümesi 2026–2035 (2026-11-15): biçim (sıralı, tekil gün, ad kuralı), her yılın sabit tatilleri, dini
 * bayramların gün sayısı ve arifesi, Diyanet tablosundaki 1. günler, aynı güne düşen tatillerin birleşmesi ve 2026–2027'nin
 * ilk tohumla (2026-10-31) BİREBİR aynı kalması.
 */
class HolidayCalendarDatasetTest {

    /** İlk tohumun (2026-10-31) 2026–2027 satırları — sözlü kopya (kâhin): veri kümesi bunları değiştirmemeli. */
    static final List<String[]> FIRST_SEED_ORACLE = List.of(
            new String[]{"2026-01-01", "Yılbaşı"},
            new String[]{"2026-03-19", "Ramazan Bayramı arifesi — arife (yarım gün)"},
            new String[]{"2026-03-20", "Ramazan Bayramı 1. gün"},
            new String[]{"2026-03-21", "Ramazan Bayramı 2. gün"},
            new String[]{"2026-03-22", "Ramazan Bayramı 3. gün"},
            new String[]{"2026-04-23", "Ulusal Egemenlik ve Çocuk Bayramı"},
            new String[]{"2026-05-01", "Emek ve Dayanışma Günü"},
            new String[]{"2026-05-19", "Atatürk'ü Anma, Gençlik ve Spor Bayramı"},
            new String[]{"2026-05-26", "Kurban Bayramı arifesi — arife (yarım gün)"},
            new String[]{"2026-05-27", "Kurban Bayramı 1. gün"},
            new String[]{"2026-05-28", "Kurban Bayramı 2. gün"},
            new String[]{"2026-05-29", "Kurban Bayramı 3. gün"},
            new String[]{"2026-05-30", "Kurban Bayramı 4. gün"},
            new String[]{"2026-07-15", "Demokrasi ve Milli Birlik Günü"},
            new String[]{"2026-08-30", "Zafer Bayramı"},
            new String[]{"2026-10-28", "Cumhuriyet Bayramı arifesi — arife (yarım gün)"},
            new String[]{"2026-10-29", "Cumhuriyet Bayramı"},
            new String[]{"2027-01-01", "Yılbaşı"},
            new String[]{"2027-03-08", "Ramazan Bayramı arifesi — arife (yarım gün)"},
            new String[]{"2027-03-09", "Ramazan Bayramı 1. gün"},
            new String[]{"2027-03-10", "Ramazan Bayramı 2. gün"},
            new String[]{"2027-03-11", "Ramazan Bayramı 3. gün"},
            new String[]{"2027-04-23", "Ulusal Egemenlik ve Çocuk Bayramı"},
            new String[]{"2027-05-01", "Emek ve Dayanışma Günü"},
            new String[]{"2027-05-15", "Kurban Bayramı arifesi — arife (yarım gün)"},
            new String[]{"2027-05-16", "Kurban Bayramı 1. gün"},
            new String[]{"2027-05-17", "Kurban Bayramı 2. gün"},
            new String[]{"2027-05-18", "Kurban Bayramı 3. gün"},
            new String[]{"2027-05-19", "Kurban Bayramı 4. gün · Atatürk'ü Anma, Gençlik ve Spor Bayramı"},
            new String[]{"2027-07-15", "Demokrasi ve Milli Birlik Günü"},
            new String[]{"2027-08-30", "Zafer Bayramı"},
            new String[]{"2027-10-28", "Cumhuriyet Bayramı arifesi — arife (yarım gün)"},
            new String[]{"2027-10-29", "Cumhuriyet Bayramı"});

    /**
     * Diyanet "Dini Günler" (vakithesaplama.diyanet.gov.tr, erişim 2026-11-15) — bayramların 1. günleri. Veri kümesi
     * değişirse bu tablo da kaynağa bakılarak değişmeli (kopya kâhin).
     */
    static final Map<String, String> DIYANET_FIRST_DAYS = new LinkedHashMap<>();
    static {
        DIYANET_FIRST_DAYS.put("2026-03-20", "Ramazan");
        DIYANET_FIRST_DAYS.put("2026-05-27", "Kurban");
        DIYANET_FIRST_DAYS.put("2027-03-09", "Ramazan");
        DIYANET_FIRST_DAYS.put("2027-05-16", "Kurban");
        DIYANET_FIRST_DAYS.put("2028-02-26", "Ramazan");
        DIYANET_FIRST_DAYS.put("2028-05-05", "Kurban");
        DIYANET_FIRST_DAYS.put("2029-02-14", "Ramazan");
        DIYANET_FIRST_DAYS.put("2029-04-24", "Kurban");
        DIYANET_FIRST_DAYS.put("2030-02-04", "Ramazan");
        DIYANET_FIRST_DAYS.put("2030-04-13", "Kurban");
        DIYANET_FIRST_DAYS.put("2031-01-24", "Ramazan");
        DIYANET_FIRST_DAYS.put("2031-04-02", "Kurban");
        DIYANET_FIRST_DAYS.put("2032-01-14", "Ramazan");
        DIYANET_FIRST_DAYS.put("2032-03-22", "Kurban");
        DIYANET_FIRST_DAYS.put("2033-01-02", "Ramazan");
        DIYANET_FIRST_DAYS.put("2033-03-11", "Kurban");
        DIYANET_FIRST_DAYS.put("2033-12-23", "Ramazan");
        DIYANET_FIRST_DAYS.put("2034-03-01", "Kurban");
        DIYANET_FIRST_DAYS.put("2034-12-12", "Ramazan");
        DIYANET_FIRST_DAYS.put("2035-02-18", "Kurban");
        DIYANET_FIRST_DAYS.put("2035-12-01", "Ramazan");
    }

    private static final Set<String> FIXED_NAMES = new HashSet<>();
    static {
        for (String[] f : HolidayCalendarService.FIXED_PUBLIC_HOLIDAYS) FIXED_NAMES.add(f[1]);
    }

    private static final Pattern PART = Pattern.compile(
            "(Ramazan Bayramı [1-3]\\. gün|Kurban Bayramı [1-4]\\. gün"
                    + "|(Ramazan|Kurban|Cumhuriyet) Bayramı arifesi — arife \\(yarım gün\\))");

    private static Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] d : HolidayCalendarDataset.DAYS) m.put(d[0], d[1]);
        return m;
    }

    @Test
    @DisplayName("biçim: gün sırasıyla, tekil, 2026–2035 içinde, geçerli tarih; adlar boş değil, ≤ 120, ayrıştırıcıdan geçer")
    void formatAndOrder() {
        List<String[]> days = HolidayCalendarDataset.DAYS;
        assertThat(days).hasSize(171);
        Set<String> seen = new HashSet<>();
        LocalDate prev = null;
        List<Map<String, Object>> raw = new ArrayList<>();
        for (String[] d : days) {
            assertThat(d).hasSize(2);
            LocalDate day = LocalDate.parse(d[0]);
            assertThat(d[0]).isEqualTo(day.toString());
            assertThat(day.getYear()).isBetween(HolidayCalendarDataset.FIRST_YEAR, HolidayCalendarDataset.LAST_YEAR);
            assertThat(seen.add(d[0])).as("gün tekil: %s", d[0]).isTrue();
            if (prev != null) assertThat(day).as("sıralı").isAfter(prev);
            prev = day;
            assertThat(d[1]).isNotBlank().hasSizeLessThanOrEqualTo(HolidayCalendarService.MAX_NAME);
            for (String part : d[1].split(" · ")) {
                assertThat(FIXED_NAMES.contains(part) || PART.matcher(part).matches())
                        .as("ad parçası tanınmalı: '%s' (%s)", part, d[0]).isTrue();
            }
            raw.add(Map.of("day", d[0], "name", d[1]));
        }
        assertThat(HolidayCalendarService.parse(raw)).hasSize(days.size());
        assertThat(days.size()).isLessThan(HolidayCalendarService.MAX_DAYS);
        // Yıl başına satır sayısı (aynı güne düşen tatiller tek satır)
        Map<Integer, Integer> perYear = new LinkedHashMap<>();
        HolidayCalendarDataset.byYear().forEach((y, rows) -> perYear.put(y, rows.size()));
        assertThat(perYear).containsExactly(
                Map.entry(2026, 17), Map.entry(2027, 16), Map.entry(2028, 17), Map.entry(2029, 16), Map.entry(2030, 17),
                Map.entry(2031, 17), Map.entry(2032, 17), Map.entry(2033, 20), Map.entry(2034, 17), Map.entry(2035, 17));
    }

    @Test
    @DisplayName("her yıl: 7 sabit resmî tatil + Cumhuriyet Bayramı arifesi (28 Ekim); köprü gün yok")
    void everyYearHasFixedHolidays() {
        Map<String, String> m = asMap();
        for (int y = 2026; y <= 2035; y++) {
            for (String[] f : HolidayCalendarService.FIXED_PUBLIC_HOLIDAYS) {
                String day = y + "-" + f[0];
                assertThat(m).containsKey(day);
                assertThat(List.of(m.get(day).split(" · "))).as(day).contains(f[1]);
            }
            assertThat(List.of(m.get(y + "-10-28").split(" · "))).contains("Cumhuriyet Bayramı arifesi — arife (yarım gün)");
        }
        // Yalnız resmî tatil ve bayram günleri — "köprü" / idari izin yok
        assertThat(HolidayCalendarDataset.DAYS).noneMatch(d -> d[1].toLowerCase().contains("köprü"));
    }

    @Test
    @DisplayName("dini bayramlar: 1. günler Diyanet tablosu; Ramazan 3 gün + arife, Kurban 4 gün + arife (önceki gün); 2033'te iki Ramazan")
    void religiousFeasts() {
        Map<String, String> m = asMap();
        Map<String, Integer> firstDaysPerYear = new HashMap<>();
        List<String> foundFirstDays = new ArrayList<>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            for (String part : e.getValue().split(" · ")) {
                if (part.endsWith("Bayramı 1. gün")) foundFirstDays.add(e.getKey());
            }
        }
        assertThat(foundFirstDays).containsExactlyElementsOf(DIYANET_FIRST_DAYS.keySet());

        for (Map.Entry<String, String> f : DIYANET_FIRST_DAYS.entrySet()) {
            LocalDate first = LocalDate.parse(f.getKey());
            String feast = f.getValue() + " Bayramı";
            int length = f.getValue().equals("Ramazan") ? 3 : 4;
            assertThat(List.of(m.get(first.minusDays(1).toString()).split(" · ")))
                    .as("arife bir gün önce: %s", first).contains(feast + " arifesi — arife (yarım gün)");
            for (int i = 0; i < length; i++) {
                assertThat(List.of(m.get(first.plusDays(i).toString()).split(" · ")))
                        .as("%s %d. gün", feast, i + 1).contains(feast + " " + (i + 1) + ". gün");
            }
            assertThat(m.get(first.plusDays(length).toString()) == null
                    || !m.get(first.plusDays(length).toString()).contains(feast + " " + (length + 1) + ". gün")).isTrue();
            firstDaysPerYear.merge(first.getYear() + ":" + f.getValue(), 1, Integer::sum);
        }
        for (int y = 2026; y <= 2035; y++) {
            assertThat(firstDaysPerYear.get(y + ":Kurban")).as("Kurban %d", y).isEqualTo(1);
            assertThat(firstDaysPerYear.get(y + ":Ramazan")).as("Ramazan %d", y).isEqualTo(y == 2033 ? 2 : 1);
        }
        // Gün sayısı: her bayram günü tam bir kez
        long ramazanDays = HolidayCalendarDataset.DAYS.stream().filter(d -> d[1].matches(".*Ramazan Bayramı [1-3]\\. gün.*")).count();
        long kurbanDays = HolidayCalendarDataset.DAYS.stream().filter(d -> d[1].matches(".*Kurban Bayramı [1-4]\\. gün.*")).count();
        assertThat(ramazanDays).isEqualTo(11 * 3);
        assertThat(kurbanDays).isEqualTo(10 * 4);
        assertThat(HolidayCalendarDataset.DAYS.stream().filter(d -> d[1].contains("arife (yarım gün)")).count())
                .as("arife satırı: 11 Ramazan + 10 Kurban + 10 Cumhuriyet").isEqualTo(31);
    }

    @Test
    @DisplayName("aynı güne iki tatil: tek satır, önce bayram günü, sonra sabit tatil, en son arife")
    void collisionsAreMerged() {
        Map<String, String> m = asMap();
        assertThat(m.get("2027-05-19")).isEqualTo("Kurban Bayramı 4. gün · Atatürk'ü Anma, Gençlik ve Spor Bayramı");
        assertThat(m.get("2029-04-23")).isEqualTo("Ulusal Egemenlik ve Çocuk Bayramı · Kurban Bayramı arifesi — arife (yarım gün)");
        assertThat(m.get("2033-01-01")).isEqualTo("Yılbaşı · Ramazan Bayramı arifesi — arife (yarım gün)");
        assertThat(HolidayCalendarDataset.DAYS.stream().filter(d -> d[1].contains(" · ")).count()).isEqualTo(3);
    }

    @Test
    @DisplayName("2026–2027: ilk tohumla (2026-10-31) birebir aynı satırlar; tohum da bu veri kümesini kullanır")
    void firstTwoYearsUnchanged() {
        List<String[]> first = HolidayCalendarDataset.DAYS.stream().filter(d -> d[0].compareTo("2028") < 0).toList();
        assertThat(first).hasSameSizeAs(FIRST_SEED_ORACLE);
        for (int i = 0; i < first.size(); i++) {
            assertThat(first.get(i)).as("satır %d", i).containsExactly(FIRST_SEED_ORACLE.get(i));
        }
        assertThat(HolidayCalendarSeed.DAYS).isSameAs(HolidayCalendarDataset.DAYS);
        // Kapsamın son günü: 2035 Ramazan Bayramı 3. gün
        assertThat(HolidayCalendarDataset.DAYS.get(HolidayCalendarDataset.DAYS.size() - 1)).containsExactly("2035-12-03", "Ramazan Bayramı 3. gün");
    }
}
