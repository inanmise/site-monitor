package com.sitemonitor.service.report;

import com.sitemonitor.service.EmailNotificationService.AvailabilityRow;
import com.sitemonitor.service.MonitoringWeeklyStatsService.TypeStats;
import com.sitemonitor.service.report.WeeklyOutageReportService.Bucket;
import com.sitemonitor.service.report.WeeklyOutageReportService.CertExpiry;
import com.sitemonitor.service.report.WeeklyOutageReportService.OutageRow;
import com.sitemonitor.service.report.WeeklyOutageReportService.RepeatItem;
import com.sitemonitor.service.report.WeeklyOutageReportService.TimelineRow;
import com.sitemonitor.service.report.WeeklyOutageReportService.TimelineSegment;
import com.sitemonitor.service.report.WeeklyOutageReportService.TypeGroup;
import com.sitemonitor.service.report.WeeklyOutageReportService.WeeklyOutageData;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Haftalık kesinti PDF'inin ÇİZİM katmanı.
 *
 * <p>"Bayt dizisi boş değil" zayıf bir iddiadır — belge üretilip içi bomboş olabilir. Bu yüzden
 * üretilen PDF {@link PDFTextStripper} ile GERİ OKUNUYOR ve bölüm başlıkları, satır içerikleri,
 * Türkçe karakterler metinde aranıyor.
 */
class WeeklyOutagePdfWriterTest {

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static int pageCount(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    private static byte[] render(WeeklyOutageData d) throws IOException {
        try (WeeklyOutagePdfWriter w = new WeeklyOutagePdfWriter()) {
            return w.write(d);
        }
    }

    private static OutageRow row(String target, String type, String alertType, String msg) {
        return new OutageRow(1L, type, alertType, target, "CRITICAL",
                "2026-06-16T09:00:00", "2026-06-16T11:00:00", false, false, 120, 120, 0,
                "ahmet", "2026-06-16T09:05:00", "sistem", 2, 0, false, null, msg);
    }

    /** Tek bir (gün, saat) hücresine değer koyan ısı haritası; kalan 167 hücre sıfır. */
    private static List<WeeklyOutageReportService.HeatRow> heatOf(int day, int hour, int value) {
        String[] days = { "Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar" };
        List<WeeklyOutageReportService.HeatRow> out = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            List<Integer> hours = new ArrayList<>();
            for (int h = 0; h < 24; h++) hours.add(d == day && h == hour ? value : 0);
            out.add(new WeeklyOutageReportService.HeatRow(days[d], hours));
        }
        return out;
    }

    private static List<TimelineRow> timelineOf(List<OutageRow> rows) {
        return rows.stream()
                .map(r -> new TimelineRow(r.target(),
                        List.of(new TimelineSegment(r.weekStartOffsetMin(), r.weekDurationMin(),
                                r.level(), r.stillOpen())),
                        r.weekDurationMin()))
                .toList();
    }

    private static WeeklyOutageData data(List<TypeGroup> groups, int totalAlarms) {
        List<OutageRow> all = groups.stream().flatMap(g -> g.rows().stream()).toList();
        return new WeeklyOutageData(
                "Dijital SY", "15–21 Haziran 2026", "22.06.2026 10:00",
                totalAlarms, 1, 0, all.size(), 240, 99.42, 2,
                totalAlarms, 7, totalAlarms - 7,
                List.of(new TypeStats("http", 12, 4000L, 99.4, 3, 2, 1, -0.3, 1, 210.0, List.of())),
                groups,
                all.stream().limit(10).toList(),
                List.of(new RepeatItem("kronik.com", "HTTP_DOWN", "HTTP/Website", 3)),
                List.of(new Bucket("Pazartesi", 2), new Bucket("Salı", 0), new Bucket("Çarşamba", 1),
                        new Bucket("Perşembe", 0), new Bucket("Cuma", 0), new Bucket("Cumartesi", 0),
                        new Bucket("Pazar", 0)),
                List.of(new Bucket("09:00", 2), new Bucket("14:00", 1)),
                List.of(new Bucket("CRITICAL", 2), new Bucket("HIGH", 1)),
                heatOf(1, 9, 2), timelineOf(all), 7L * 24 * 60,
                all.stream().limit(1).toList(),
                all.stream().limit(1).toList(),
                List.of(new AvailabilityRow("a.com", 99.42, 2, 45, 30, 180L, 320L, 25)),
                List.of(new CertExpiry("yakin.com", 12)),
                0, 0);
    }

    @Test
    @DisplayName("Belge üretilir ve TÜM bölüm başlıkları içinde yer alır")
    void producesDocumentWithAllSections() throws IOException {
        byte[] pdf = render(data(List.of(
                new TypeGroup("http", "HTTP/Website", List.of(row("a.com", "http", "HTTP_DOWN", "Bağlantı zaman aşımı")))), 12));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        String text = textOf(pdf);
        assertThat(text)
                .contains("Haftalık Kesinti Raporu")
                .contains("Dijital SY")
                .contains("15–21 Haziran 2026")
                .contains("Yönetici Özeti")
                .contains("Geçen Haftaya Göre")
                .contains("İzleme Türü Bazında Özet")
                .contains("Kesinti Detayı")
                .contains("En Uzun Kesintiler")
                .contains("Tekrar Eden Sorunlar")
                .contains("Gün ve Saat Dağılımı")
                .contains("Hâlâ Açık Alarmlar")
                .contains("Bildirim Ulaşmayan Alarmlar")
                .contains("Erişilebilirlik")
                .contains("Süresi Yaklaşan Sertifikalar")
                .contains("Kapsam ve Yöntem");
    }

    @Test
    @DisplayName("Kesinti satırının HER ayrıntısı belgede: hedef, süre, sahiplenen ve tam mesaj")
    void everyOutageDetailIsWritten() throws IOException {
        String longMessage = "Uzak sunucuya bağlanılamadı: connect timed out (10.20.30.40:443) — "
                + "üç ardışık denemede de yanıt alınamadı";
        byte[] pdf = render(data(List.of(new TypeGroup("http", "HTTP/Website",
                List.of(row("kritik.example.com", "http", "HTTP_DOWN", longMessage)))), 1));

        String text = textOf(pdf);
        assertThat(text).contains("kritik.example.com");
        assertThat(text).contains("HTTP_DOWN");
        assertThat(text).contains("2sa");                 // süre okunur biçimde
        assertThat(text).contains("ahmet");               // sahiplenen
        // Mesaj tabloda kesilse bile ALTINDA tam yazılır — "en ince ayrıntı" isteğinin karşılığı.
        assertThat(text).contains("connect timed out");
        assertThat(text).contains("üç ardışık denemede");
    }

    @Test
    @DisplayName("Türkçe karakterler ve fontta OLMAYAN karakterler belgeyi ÇÖKERTMEZ")
    void turkishAndExoticCharactersDoNotCrash() throws IOException {
        String nasty = "ĞÜŞİÖÇğüşıöç · em—dash ✓ ok 🐛 emoji \u0000 kontrol ➤ ok";
        byte[] pdf = render(data(List.of(new TypeGroup("dns", "DNS",
                List.of(row("çğıöşü.example.com", "dns", "DNS_FAILURE", nasty)))), 1));

        assertThat(pdf).isNotEmpty();
        String text = textOf(pdf);
        // Türkçe glyph'ler GERÇEKTEN çizilmiş olmalı (Roboto gömülü); çizilemeyenler '?'e iner.
        assertThat(text).contains("ĞÜŞİÖÇğüşıöç");
        assertThat(text).contains("çğıöşü.example.com");
    }

    @Test
    @DisplayName("Kesintisiz hafta: kısa ama GEÇERLİ bir belge üretilir (ek yine gönderilir)")
    void quietWeekProducesShortValidDocument() throws IOException {
        WeeklyOutageData quiet = new WeeklyOutageData(
                "Sessiz Takım", "15–21 Haziran 2026", "22.06.2026 10:00",
                0, 0, 0, 0, 0, 100.0, 0, 0, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), 7L * 24 * 60,
                List.of(), List.of(), List.of(), List.of(), 0, 0);

        byte[] pdf = render(quiet);
        String text = textOf(pdf);

        assertThat(text).contains("Bu hafta kesinti yaşanmadı");
        assertThat(text).contains("Kapsam ve Yöntem");
        // Kesinti bölümleri çizilmez — boş tablo başlıkları gürültüdür.
        assertThat(text).doesNotContain("En Uzun Kesintiler");
        assertThat(pageCount(pdf)).isEqualTo(1);
    }

    @Test
    @DisplayName("SATIR SINIRI YOK: yüzlerce alarm da yazılır ve belge çok sayfaya taşar")
    void noRowCapManyAlarmsSpanManyPages() throws IOException {
        List<OutageRow> many = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            many.add(row("host-" + i + ".example.com", "http", "HTTP_DOWN", "hata " + i));
        }
        byte[] pdf = render(data(List.of(new TypeGroup("http", "HTTP/Website", many)), 400));

        String text = textOf(pdf);
        assertThat(pageCount(pdf)).isGreaterThan(5);
        // İlki de sonuncusu da yazılmış olmalı — sessiz bir kesme olsaydı sonuncusu kaybolurdu.
        assertThat(text).contains("host-0.example.com");
        assertThat(text).contains("host-399.example.com");
        assertThat(text).contains("400 alarm listelendi");
    }

    @Test
    @DisplayName("Toplam kesinti haftayı aşınca NEDEN aştığı yazılır — yoksa rakam hatalı sanılır")
    void downtimeExceedingAWeekIsExplained() throws IOException {
        WeeklyOutageData d = new WeeklyOutageData(
                "Dijital SY", "15–21 Haziran 2026", "22.06.2026 10:00",
                12, 12, 8, 7, 113_000, 85.71, 3, 4, 3, 1,      // 113000 dk ≈ 78 gün
                List.of(), List.of(new TypeGroup("http", "HTTP/Website",
                        List.of(row("a.com", "http", "HTTP_DOWN", "hata")))),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), 7L * 24 * 60,
                List.of(), List.of(), List.of(), List.of(), 0, 1);

        String text = textOf(render(d));
        assertThat(text).contains("alarm BAŞINA sürelerin toplamıdır");
        assertThat(text).contains("haftanın 7 gününü aşabilir");
        // Devreden alarmlar varsa kırpma kuralı da açıklanmalı.
        assertThat(text).contains("BU HAFTAYA düşen payı");
    }

    // ── Görselleştirme ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Seviye halkası: dilimler ÇİZİLİR ve göstergede ad + sayı + yüzde YAZILI")
    void levelDonutHasTextualLegend() throws IOException {
        String text = textOf(render(data(List.of(new TypeGroup("http", "HTTP/Website",
                List.of(row("a.com", "http", "HTTP_DOWN", "hata")))), 3)));

        // Renk tek başına taşıyıcı olamaz (renk körlüğü / siyah-beyaz çıktı) — seviye ADI,
        // sayısı ve yüzdesi metin olarak da bulunmalı.
        assertThat(text).contains("KRİTİK");
        assertThat(text).contains("YÜKSEK");
        assertThat(text).contains("2 alarm");
        assertThat(text).contains("67%");     // 2/3
        assertThat(text).contains("33%");     // 1/3
    }

    @Test
    @DisplayName("Zaman çizelgesi: gün ekseni, hedef adı, süre ve seviye göstergesi YAZILI")
    void timelineHasAxisAndTextualValues() throws IOException {
        String text = textOf(render(data(List.of(new TypeGroup("http", "HTTP/Website",
                List.of(row("cizelge.example.com", "http", "HTTP_DOWN", "hata")))), 1)));

        assertThat(text).contains("Kesinti Zaman Çizelgesi");
        // Gün ekseni olmadan cubuklarin nereye denk geldigi okunamaz.
        assertThat(text).contains("Pzt").contains("Cmt").contains("Paz");
        assertThat(text).contains("cizelge.example.com");
        assertThat(text).contains("2sa");                 // sagdaki toplam sure
        assertThat(text).contains("Seviye:");             // renk gostergesi
    }

    @Test
    @DisplayName("Isı haritası: sıfır olmayan hücrenin SAYISI yazılı — renk tek başına yetmez")
    void heatmapWritesCountsNotJustColor() throws IOException {
        // Salı (indeks 1) saat 09:00'da 2 alarm.
        String text = textOf(render(data(List.of(new TypeGroup("http", "HTTP/Website",
                List.of(row("a.com", "http", "HTTP_DOWN", "hata")))), 3)));

        assertThat(text).contains("Gün × saat yoğunluğu");
        assertThat(text).contains("Salı");
        // Hucre icindeki sayi: koyulugu gozle olcmek gerekmesin.
        assertThat(text).contains("2");
        assertThat(text).contains("hücrenin içinde de yazılıdır");
    }

    @Test
    @DisplayName("Tür çubukları: her türün açılan/açık sayısı çubuğun YANINDA yazılı")
    void typeBarsWriteCountsBesideBars() throws IOException {
        String text = textOf(render(data(List.of(new TypeGroup("http", "HTTP/Website",
                List.of(row("a.com", "http", "HTTP_DOWN", "hata")))), 1)));

        assertThat(text).contains("HTTP/Website");
        assertThat(text).contains("3 açılan");   // TypeStats(alarmsOpened=3)
        assertThat(text).contains("1 açık");     // TypeStats(alarmsOpen=1)
    }

    @Test
    @DisplayName("Seviye rozeti tabloda METİN olarak durur — renk kaybolsa da seviye okunur")
    void levelBadgeKeepsItsText() throws IOException {
        String text = textOf(render(data(List.of(new TypeGroup("dns", "DNS",
                List.of(row("rozet.example.com", "dns", "DNS_FAILURE", "hata")))), 1)));

        // Rozet zemini renkli ama etiket her zaman yazili; siyah-beyaz ciktida da okunur.
        assertThat(text).contains("KRİTİK");
        assertThat(text).contains("Seviye");     // sutun basligi
    }

    @Test
    @DisplayName("Erişilebilirlik çubuğu yüzdenin YERİNE geçmez — yüzde yazılı kalır")
    void availabilityBarDoesNotReplaceThePercentage() throws IOException {
        String text = textOf(render(data(List.of(new TypeGroup("http", "HTTP/Website",
                List.of(row("a.com", "http", "HTTP_DOWN", "hata")))), 1)));

        assertThat(text).contains("99,42%");
        assertThat(text).contains("Uptime sütunundaki çubuk yüzdeyi görselleştirir");
    }

    @Test
    @DisplayName("Kesintisiz haftada grafik ÇİZİLMEZ — boş eksen ve sıfır ızgara gürültüdür")
    void quietWeekDrawsNoCharts() throws IOException {
        WeeklyOutageData quiet = new WeeklyOutageData(
                "Sessiz Takım", "15–21 Haziran 2026", "22.06.2026 10:00",
                0, 0, 0, 0, 0, 100.0, 0, 0, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), 7L * 24 * 60,
                List.of(), List.of(), List.of(), List.of(), 0, 0);

        String text = textOf(render(quiet));
        assertThat(text).doesNotContain("Kesinti Zaman Çizelgesi");
        assertThat(text).doesNotContain("Gün × saat yoğunluğu");
        assertThat(text).contains("Bu hafta kesinti yaşanmadı");
    }

    @Test
    @DisplayName("«Geçen haftaya göre» AÇILAN alarmı yazar ve toplamdan farkını açıklar")
    void weekOverWeekReportsOpenedNotTotal() throws IOException {
        // totalAlarms=12 (8'i devreden), açılan=4, geçen hafta=3 → +1 artış.
        WeeklyOutageData d = new WeeklyOutageData(
                "Dijital SY", "15–21 Haziran 2026", "22.06.2026 10:00",
                12, 12, 8, 7, 5000, 85.71, 3, 4, 3, 1,
                List.of(), List.of(new TypeGroup("http", "HTTP/Website",
                        List.of(row("a.com", "http", "HTTP_DOWN", "hata")))),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), 7L * 24 * 60,
                List.of(), List.of(), List.of(), List.of(), 0, 0);

        String text = textOf(render(d));

        assertThat(text).contains("Bu hafta 4 alarm AÇILDI, geçen hafta 3");
        // Toplamın neden 12 olduğu söylenmezse okuyucu 4 ile 12'yi birbirinin yerine koyar.
        assertThat(text).contains("devreden");
        assertThat(text).contains("karşılaştırmaya girmez");
    }

    @Test
    @DisplayName("«En Uzun Kesintiler» İKİ süre sütunu taşır — gerçek boy kaybolmaz")
    void longestTableShowsBothDurations() throws IOException {
        String text = textOf(render(data(List.of(new TypeGroup("http", "HTTP/Website",
                List.of(row("a.com", "http", "HTTP_DOWN", "hata")))), 5)));

        assertThat(text).contains("Bu hafta").contains("Toplam");
        assertThat(text).contains("Sıralama bu haftaya düşen süreye göredir");
    }

    @Test
    @DisplayName("Dağılım bölümü paydayı YAZAR; bu hafta hiç alarm açılmadıysa hiç çizilmez")
    void distributionStatesItsDenominatorAndHidesWhenEmpty() throws IOException {
        String withOpened = textOf(render(data(List.of(new TypeGroup("http", "HTTP/Website",
                List.of(row("a.com", "http", "HTTP_DOWN", "hata")))), 5)));
        assertThat(withOpened).contains("Gün ve Saat Dağılımı");
        assertThat(withOpened).contains("alarmın dağılımı");
        assertThat(withOpened).contains("devreden alarmlar burada sayılmaz");

        // Yalnız devreden alarm varsa (bu hafta hiçbir şey açılmadı) bölüm çizilmez:
        // boş bir ızgara "veri yok" ile "alarm yok"u ayırt ettirmez.
        WeeklyOutageData onlyCarried = new WeeklyOutageData(
                "Dijital SY", "15–21 Haziran 2026", "22.06.2026 10:00",
                3, 3, 3, 2, 5000, 99.0, 1, 0, 2, -2,
                List.of(), List.of(new TypeGroup("http", "HTTP/Website",
                        List.of(row("a.com", "http", "HTTP_DOWN", "hata")))),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), 7L * 24 * 60,
                List.of(), List.of(), List.of(), List.of(), 0, 0);
        assertThat(textOf(render(onlyCarried))).doesNotContain("Gün ve Saat Dağılımı");
    }

    @Test
    @DisplayName("Kapsam notu: alarm ile erişilebilirlik farkı ve bakım işaretinin sınırı YAZILI")
    void methodNoteStatesTheCaveats() throws IOException {
        byte[] pdf = render(data(List.of(new TypeGroup("http", "HTTP/Website",
                List.of(row("a.com", "http", "HTTP_DOWN", "hata")))), 1));

        String text = textOf(pdf);
        // Bu iki cümle olmadan raporun sayıları kendi içinde çelişkili GÖRÜNÜR.
        assertThat(text).contains("AYNI ŞEY DEĞİLDİR");
        assertThat(text).contains("GERİYE DÖNÜK");
        assertThat(text).contains("Sayfa Bütünlüğü");     // kapsam listesi katalogdan türer
    }

    @Test
    @DisplayName("Sayfa numaraları basılır ve toplam sayfa sayısını gösterir")
    void pageNumbersAreStamped() throws IOException {
        List<OutageRow> many = new ArrayList<>();
        for (int i = 0; i < 120; i++) many.add(row("h" + i + ".com", "http", "HTTP_DOWN", "x"));
        byte[] pdf = render(data(List.of(new TypeGroup("http", "HTTP/Website", many)), 120));

        int pages = pageCount(pdf);
        assertThat(textOf(pdf)).contains("Sayfa 1 / " + pages);
    }
}
