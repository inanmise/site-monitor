package com.sitemonitor.service.report;

import com.sitemonitor.service.EmailNotificationService.AvailabilityRow;
import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.service.MonitoringWeeklyStatsService.TypeStats;
import com.sitemonitor.service.report.WeeklyOutageReportService.Bucket;
import com.sitemonitor.service.report.WeeklyOutageReportService.CertExpiry;
import com.sitemonitor.service.report.WeeklyOutageReportService.OutageRow;
import com.sitemonitor.service.report.WeeklyOutageReportService.RepeatItem;
import com.sitemonitor.service.report.WeeklyOutageReportService.TypeGroup;
import com.sitemonitor.service.report.WeeklyOutageReportService.WeeklyOutageData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.sitemonitor.service.report.PdfCanvas.*;

/**
 * Haftalık kesinti raporunun ÇİZİM katmanı — {@link WeeklyOutageReportService} verisini PDF'e döker.
 *
 * <p>Bölüm sırası kullanıcı tarafından seçildi: yönetici özeti → geçen haftaya göre → izleme türü
 * özeti → <b>tür bazında kesinti detayı</b> (raporun ana gövdesi) → en uzunlar ve tekrar edenler →
 * gün/saat dağılımı → hâlâ açıklar → bildirim ulaşmayanlar → erişilebilirlik → sertifika bitişleri
 * → kapsam ve yöntem notu.
 *
 * <p><b>Satır sınırı YOK</b> (kullanıcı kararı). Yoğun bir haftada belge yüzlerce sayfa olabilir;
 * bu gizlenmiyor, üretilen boyut ve satır sayısı loglanıyor ve raporun sonunda yazıyor.
 */
class WeeklyOutagePdfWriter implements AutoCloseable {

    /** Tam bir haftanın dakikası — toplam kesinti bunu aşarsa açıklama notu çizilir. */
    private static final long WEEK_MINUTES = 7L * 24 * 60;

    private final PdfCanvas c = new PdfCanvas();

    byte[] write(WeeklyOutageData d) throws IOException {
        c.newPage();
        coverAndSummary(d);
        if (d.quietWeek()) {
            quietWeekNote(d);
        } else {
            weekOverWeek(d);
            typeSummary(d);
            outageDetail(d);
            longestAndRepeats(d);
            distribution(d);
            stillOpen(d);
            notifyGaps(d);
        }
        availability(d);
        certExpiries(d);
        methodNote(d);
        return c.finish();
    }

    // ── 1. Kapak + yönetici özeti ────────────────────────────────────────────

    private void coverAndSummary(WeeklyOutageData d) throws IOException {
        c.rect(0, PAGE.getHeight() - 96, PAGE.getWidth(), 96, new float[]{ 15 / 255f, 23 / 255f, 42 / 255f });
        c.text(c.bold, 17f, MARGIN, PAGE.getHeight() - 44, "Haftalık Kesinti Raporu", WHITE);
        c.text(c.regular, 10f, MARGIN, PAGE.getHeight() - 62,
                d.teamName() + "  ·  " + d.weekLabel(), new float[]{ 203 / 255f, 213 / 255f, 225 / 255f });
        c.text(c.regular, 7.5f, MARGIN, PAGE.getHeight() - 78,
                "Üretim: " + d.generatedAt() + " (Europe/Istanbul)", new float[]{ 148 / 255f, 163 / 255f, 184 / 255f });
        c.y = PAGE.getHeight() - 118;

        sectionHeader("Yönetici Özeti");

        List<String[]> cards = new ArrayList<>();
        cards.add(new String[]{ "Toplam alarm", String.valueOf(d.totalAlarms()) });
        cards.add(new String[]{ "Hâlâ açık", String.valueOf(d.stillOpenCount()) });
        cards.add(new String[]{ "Etkilenen hedef", String.valueOf(d.affectedTargets()) });
        cards.add(new String[]{ "Kesinti süresi (bu hafta)", WeeklyOutageReportService.humanDuration(d.totalDowntimeMin()) });
        cards.add(new String[]{ "Ortalama erişilebilirlik", pct(d.avgAvailabilityPct()) });
        cards.add(new String[]{ "Kesinti yaşayan domain", d.domainsWithOutage() + " / " + d.availability().size() });
        statCards(cards);

        List<String> notes = new ArrayList<>();
        if (d.totalDowntimeMin() > WEEK_MINUTES) {
            // Bir hafta 7 gündür; "78 gün kesinti" ilk bakışta imkânsız görünür. Sayı alarm
            // BAŞINA sürelerin toplamıdır ve eşzamanlı kesintilerde haftayı aşar — bu söylenmezse
            // rakam hatalı sanılır.
            notes.add("Kesinti süresi alarm BAŞINA sürelerin toplamıdır; aynı anda birden fazla "
                    + "izleme kesintideyken toplam, haftanın 7 gününü aşabilir.");
        }
        if (d.carriedOverCount() > 0) {
            notes.add(d.carriedOverCount() + " alarm önceki haftadan devretti. Yukarıdaki «kesinti süresi» "
                    + "yalnız BU HAFTAYA düşen payı toplar; detay tablosundaki süre ise alarmın gerçek "
                    + "açılışından itibaren geçen TOPLAM süredir. Ham süreler toplansaydı bir haftalık "
                    + "rapor aylardır süren bir kesinti yüzünden yüzlerce gün gösterirdi.");
        }
        if (d.maintenanceOverlapCount() > 0) {
            notes.add(d.maintenanceOverlapCount() + " alarm bir bakım penceresine denk geliyor (bugünkü "
                    + "pencere tanımına göre) — planlı kesinti olabilir.");
        }
        if (d.stormCount() > 0) {
            notes.add(d.stormCount() + " alarm fırtınası tespit edildi; aynı fırtınaya ait alarmlar "
                    + "genelde tek bir kök nedenden kaynaklanır.");
        }
        for (String n : notes) {
            c.ensureSpace(14);
            bullet(n);
        }
        c.y -= 6;
    }

    private void quietWeekNote(WeeklyOutageData d) throws IOException {
        c.ensureSpace(46);
        c.rect(MARGIN, c.y - 30, CONTENT_W, 38, new float[]{ 236 / 255f, 253 / 255f, 245 / 255f });
        c.text(c.bold, 10f, MARGIN + 12, c.y - 4, "Bu hafta kesinti yaşanmadı.", GREEN);
        c.text(c.regular, 8f, MARGIN + 12, c.y - 18,
                "Takımın portföyündeki izlemelerden hiçbir alarm açılmadı ve önceki haftalardan devreden "
                + "açık alarm da yok.", INK);
        c.y -= 48;
    }

    // ── 2. Geçen haftaya göre ────────────────────────────────────────────────

    private void weekOverWeek(WeeklyOutageData d) throws IOException {
        sectionHeader("Geçen Haftaya Göre");
        int delta = d.alarmsDelta();
        String dir = delta > 0 ? "▲ " + delta + " artış" : delta < 0 ? "▼ " + Math.abs(delta) + " azalış" : "değişim yok";
        float[] color = delta > 0 ? RED : delta < 0 ? GREEN : LABEL;
        c.ensureSpace(18);
        c.text(c.regular, 8.5f, MARGIN, c.y,
                "Bu hafta " + d.totalAlarms() + " alarm, geçen hafta " + d.alarmsPrevWeek() + " alarm — ", INK);
        float x = MARGIN + c.width("Bu hafta " + d.totalAlarms() + " alarm, geçen hafta "
                + d.alarmsPrevWeek() + " alarm — ", c.regular, 8.5f);
        c.text(c.bold, 8.5f, x, c.y, dir, color);
        c.y -= 14;
        c.text(c.regular, 7f, MARGIN, c.y,
                "Karşılaştırma yalnız o hafta AÇILAN alarmları sayar; devreden açık alarmlar iki tarafta da "
                + "hesaba katılmaz.", MUTED);
        c.y -= 16;
    }

    // ── 3. İzleme türü bazında özet ──────────────────────────────────────────

    private void typeSummary(WeeklyOutageData d) throws IOException {
        if (d.typeStats().isEmpty()) return;
        sectionHeader("İzleme Türü Bazında Özet");
        float[] w = { 92, 46, 58, 52, 52, 46, 46 };
        String[] head = { "Tür", "İzleme", "Kontrol", "Başarı %", "Δ Başarı", "Açılan", "Açık" };
        table(head, w, () -> {
            List<String[]> rows = new ArrayList<>();
            for (TypeStats t : d.typeStats()) {
                if (t.activeMonitors() == 0 && t.alarmsOpened() == 0 && t.alarmsOpen() == 0) continue;
                rows.add(new String[]{
                        MonitorTypeCatalog.label(t.type()),
                        String.valueOf(t.activeMonitors()),
                        String.valueOf(t.totalChecks()),
                        t.successRate() == null ? "—" : fmt(t.successRate()),
                        t.successRateDelta() == null ? "—" : signed(t.successRateDelta()),
                        String.valueOf(t.alarmsOpened()),
                        String.valueOf(t.alarmsOpen()) });
            }
            return rows;
        });
        note("İzlemesi ve alarmı olmayan türler gizlendi.");
    }

    // ── 4. Kesinti detayı — tür bazında (ana gövde) ──────────────────────────

    private void outageDetail(WeeklyOutageData d) throws IOException {
        sectionHeader("Kesinti Detayı — İzleme Türü Bazında");
        note("Her satır bir alarmdır. Hafta içinde açık olan TÜM alarmlar listelenir; "
                + "önceki haftadan devredenler «devreden» ile işaretlidir.");

        for (TypeGroup g : d.groups()) {
            c.ensureSpace(40);
            groupHeader(g.label(), g.rows().size());
            float[] w = { 118, 66, 52, 52, 42, 58, 34, 62 };
            String[] head = { "Hedef", "Alarm", "Başlangıç", "Bitiş", "Süre", "Sahiplenen", "Bild.", "Durum" };
            table(head, w, () -> {
                List<String[]> rows = new ArrayList<>();
                for (OutageRow r : g.rows()) {
                    rows.add(new String[]{
                            nz(r.target()),
                            nz(r.alertType()),
                            WeeklyOutageReportService.shortStamp(r.startedAt()),
                            r.stillOpen() ? "sürüyor" : WeeklyOutageReportService.shortStamp(r.endedAt()),
                            WeeklyOutageReportService.humanDuration(r.durationMin()),
                            nz(r.acknowledgedBy()),
                            notifyCell(r),
                            statusCell(r) });
                }
                return rows;
            });
            messages(g.rows());
            c.y -= 6;
        }
    }

    /**
     * Alarm mesajları tablo hücresine sığmaz (uzak sunucudan gelen hata dizeleri uzundur ve
     * kesildiğinde asıl bilgi kaybolur) — tablonun altında hedef+mesaj olarak tam yazılır.
     * "Her kesintinin en ince ayrıntısı" isteğinin karşılığı budur.
     */
    private void messages(List<OutageRow> rows) throws IOException {
        List<OutageRow> withMsg = rows.stream().filter(r -> r.message() != null && !r.message().isBlank()).toList();
        if (withMsg.isEmpty()) return;
        c.y -= 4;
        c.ensureSpace(14);
        c.text(c.bold, 7f, MARGIN, c.y, "Mesajlar", LABEL);
        c.y -= 11;
        for (OutageRow r : withMsg) {
            String head = WeeklyOutageReportService.shortStamp(r.startedAt()) + "  " + nz(r.target()) + " — ";
            List<String> lines = c.wrap(r.message(), CONTENT_W - 12, c.regular, 6.8f, 4);
            c.ensureSpace(10 + lines.size() * 8.5f);
            c.text(c.bold, 6.8f, MARGIN + 6, c.y, head, INK);
            float x = MARGIN + 6 + c.width(head, c.bold, 6.8f);
            boolean first = true;
            for (String ln : lines) {
                if (first) { c.text(c.regular, 6.8f, x, c.y, c.clip(ln, CONTENT_W - (x - MARGIN) - 6, c.regular, 6.8f), INK); first = false; }
                else { c.text(c.regular, 6.8f, MARGIN + 12, c.y, ln, INK); }
                c.y -= 8.5f;
            }
        }
    }

    // ── 5. En uzunlar + tekrar edenler ───────────────────────────────────────

    private void longestAndRepeats(WeeklyOutageData d) throws IOException {
        if (!d.longest().isEmpty()) {
            sectionHeader("En Uzun Kesintiler");
            float[] w = { 140, 78, 62, 62, 62, 80 };
            String[] head = { "Hedef", "Alarm", "Süre", "Başlangıç", "Bitiş", "Tür" };
            table(head, w, () -> {
                List<String[]> rows = new ArrayList<>();
                for (OutageRow r : d.longest()) {
                    rows.add(new String[]{
                            nz(r.target()), nz(r.alertType()),
                            WeeklyOutageReportService.humanDuration(r.durationMin()),
                            WeeklyOutageReportService.shortStamp(r.startedAt()),
                            r.stillOpen() ? "sürüyor" : WeeklyOutageReportService.shortStamp(r.endedAt()),
                            MonitorTypeCatalog.label(r.monitorType()) });
                }
                return rows;
            });
        }

        if (!d.repeats().isEmpty()) {
            sectionHeader("Tekrar Eden Sorunlar");
            note("Aynı hedef ve alarm tipi için hafta içinde birden fazla alarm açıldı — "
                    + "tekil bir olaydan çok kronik bir sorun işaretidir.");
            float[] w = { 170, 100, 96, 58 };
            String[] head = { "Hedef", "Alarm", "Tür", "Tekrar" };
            table(head, w, () -> {
                List<String[]> rows = new ArrayList<>();
                for (RepeatItem r : d.repeats()) {
                    rows.add(new String[]{ nz(r.target()), nz(r.alertType()), nz(r.typeLabel()),
                            r.count() + " kez" });
                }
                return rows;
            });
        }
    }

    // ── 6. Gün / saat dağılımı ───────────────────────────────────────────────

    private void distribution(WeeklyOutageData d) throws IOException {
        sectionHeader("Gün ve Saat Dağılımı");
        int maxDay = d.byDay().stream().mapToInt(Bucket::count).max().orElse(0);
        for (Bucket b : d.byDay()) {
            c.ensureSpace(13);
            c.text(c.regular, 7.5f, MARGIN, c.y, b.label(), INK);
            barAt(MARGIN + 66, b.count(), maxDay, 170);
            c.y -= 12;
        }
        c.y -= 6;

        if (!d.byHour().isEmpty()) {
            c.ensureSpace(16);
            c.text(c.bold, 7.5f, MARGIN, c.y, "Alarm düşen saatler (Europe/Istanbul)", LABEL);
            c.y -= 12;
            int maxHour = d.byHour().stream().mapToInt(Bucket::count).max().orElse(0);
            for (Bucket b : d.byHour()) {
                c.ensureSpace(13);
                c.text(c.regular, 7.5f, MARGIN, c.y, b.label(), INK);
                barAt(MARGIN + 66, b.count(), maxHour, 170);
                c.y -= 12;
            }
            note("Hiç alarm düşmeyen saatler listelenmez — 24 satırın çoğu sıfır olsaydı desen okunmazdı.");
        }
    }

    private void barAt(float x, int value, int max, float maxWidth) throws IOException {
        float w = max <= 0 ? 0 : Math.max(value > 0 ? 2f : 0f, maxWidth * value / max);
        if (w > 0) c.rect(x, c.y - 1.5f, w, 7f, BLUE);
        c.text(c.regular, 7.5f, x + w + 5, c.y, String.valueOf(value), LABEL);
    }

    // ── 7. Hâlâ açık alarmlar ────────────────────────────────────────────────

    private void stillOpen(WeeklyOutageData d) throws IOException {
        if (d.openNow().isEmpty()) return;
        sectionHeader("Hâlâ Açık Alarmlar");
        note("Hafta kapandı ama bu alarmlar çözülmedi — pazartesi sabahının iş listesi.");
        float[] w = { 132, 74, 58, 56, 58, 46, 52 };
        String[] head = { "Hedef", "Alarm", "Seviye", "Başlangıç", "Süre", "Bild.", "Sahiplenen" };
        table(head, w, () -> {
            List<String[]> rows = new ArrayList<>();
            for (OutageRow r : d.openNow()) {
                rows.add(new String[]{
                        nz(r.target()), nz(r.alertType()), nz(r.level()),
                        WeeklyOutageReportService.shortStamp(r.startedAt()),
                        WeeklyOutageReportService.humanDuration(r.durationMin()),
                        notifyCell(r), nz(r.acknowledgedBy()) });
            }
            return rows;
        });
    }

    // ── 8. Bildirim ulaşmayanlar ─────────────────────────────────────────────

    private void notifyGaps(WeeklyOutageData d) throws IOException {
        if (d.notifyGaps().isEmpty()) return;
        sectionHeader("Bildirim Ulaşmayan Alarmlar");
        note("Bu alarmlar için hiç başarılı bildirim kaydı yok — kimseye ulaşmamış olabilirler. "
                + "En tehlikeli durum budur ve haftalık raporda bugüne kadar hiç görünmüyordu.");
        float[] w = { 150, 88, 62, 66, 62 };
        String[] head = { "Hedef", "Alarm", "Seviye", "Başlangıç", "Başarısız" };
        table(head, w, () -> {
            List<String[]> rows = new ArrayList<>();
            for (OutageRow r : d.notifyGaps()) {
                rows.add(new String[]{
                        nz(r.target()), nz(r.alertType()), nz(r.level()),
                        WeeklyOutageReportService.shortStamp(r.startedAt()),
                        r.notifyFailed() > 0 ? r.notifyFailed() + " deneme" : "kayıt yok" });
            }
            return rows;
        });
    }

    // ── 9. Erişilebilirlik tablosu ───────────────────────────────────────────

    private void availability(WeeklyOutageData d) throws IOException {
        if (d.availability().isEmpty()) return;
        sectionHeader("Erişilebilirlik (HTTP · sertifika envanteri domainleri)");
        float[] w = { 150, 52, 42, 62, 62, 46, 46 };
        String[] head = { "Domain", "Uptime %", "Kesinti", "Toplam süre", "En uzun", "Ort. ms", "p95 ms" };
        table(head, w, () -> {
            List<String[]> rows = new ArrayList<>();
            for (AvailabilityRow r : d.availability()) {
                rows.add(new String[]{
                        nz(r.domain()),
                        r.availabilityPct() == null ? "veri yok" : fmt(r.availabilityPct()),
                        String.valueOf(r.outageCount()),
                        WeeklyOutageReportService.humanDuration(r.downtimeMinutes()),
                        WeeklyOutageReportService.humanDuration(r.longestOutageMinutes()),
                        r.avgMs() == null ? "—" : String.valueOf(r.avgMs()),
                        r.p95Ms() == null ? "—" : String.valueOf(r.p95Ms()) });
            }
            return rows;
        });
        note("Bu tablo e-posta gövdesiyle AYNI kaynaktan gelir (uptime_checks) ve bakım pencerelerini "
                + "hariç tutar; kapsamı yalnız sertifika envanterindeki domainlerdir.");
    }

    // ── 10. Sertifika bitişleri ──────────────────────────────────────────────

    private void certExpiries(WeeklyOutageData d) throws IOException {
        if (d.certExpiries().isEmpty()) return;
        sectionHeader("Süresi Yaklaşan Sertifikalar (≤ 60 gün)");
        float[] w = { 220, 80, 80 };
        String[] head = { "Domain", "Kalan gün", "Durum" };
        table(head, w, () -> {
            List<String[]> rows = new ArrayList<>();
            for (CertExpiry e : d.certExpiries()) {
                Integer days = e.daysRemaining();
                String state = days == null ? "—" : days < 0 ? "SÜRESİ DOLDU" : days <= 15 ? "kritik"
                        : days <= 30 ? "yakın" : "izlemede";
                rows.add(new String[]{ nz(e.domain()), days == null ? "—" : String.valueOf(days), state });
            }
            return rows;
        });
    }

    // ── 11. Kapsam ve yöntem notu ────────────────────────────────────────────

    private void methodNote(WeeklyOutageData d) throws IOException {
        c.ensureSpace(120);
        sectionHeader("Kapsam ve Yöntem");
        bullet("Kapsam: " + WeeklyOutageReportService.coveredTypesSentence() + " — toplam "
                + WeeklyOutageReportService.coveredAlertTypeCount() + " alarm tipi. E-postanın GÖVDESİ "
                + "ise yalnız sertifika envanterindeki domainlerin HTTP erişilebilirliğini raporlar; "
                + "bu ek daha geniş bir evreni kapsar, bu yüzden iki yerdeki sayılar birbirini tutmaz.");
        bullet("Alarm sayısı ile erişilebilirlik düşüşü AYNI ŞEY DEĞİLDİR. Alarm üretimi ardışık "
                + "doğrulama ister; kısa süreli bir kesinti uptime yüzdesini düşürür ama alarm üretmez. "
                + "«0 alarm ama %99,7 erişilebilirlik» tutarlı bir tablodur.");
        bullet("Kesinti süresi, alarmın açılışından çözülüşüne kadar geçen süredir. Hâlâ açık alarmlarda "
                + "hafta sonuna kadar sayılır — böylece geçmiş bir haftanın raporu her üretildiğinde "
                + "aynı sonucu verir.");
        bullet("Bakım işareti GERİYE DÖNÜK hesaplanır: bakım pencerelerinin BUGÜNKÜ tanımı alarmın "
                + "geçmişteki anına uygulanır. Pencere o tarihten sonra değiştirildiyse ya da silindiyse "
                + "işaret yanılabilir. Erişilebilirlik tablosundaki bakım bilgisi ise kontrol anında "
                + "kaydedildiği için tarihseldir ve kesindir.");
        bullet("Bu raporda " + d.totalAlarms() + " alarm listelendi; satır sınırı UYGULANMADI, "
                + "haftanın tüm alarmları yer alıyor.");
    }

    // ── Ortak çizim parçaları ────────────────────────────────────────────────

    private void sectionHeader(String label) throws IOException {
        c.ensureSpace(30);
        c.y -= 6;
        c.text(c.bold, 10f, MARGIN, c.y, label, BLUE);
        c.y -= 5;
        c.line(MARGIN, c.y, PAGE.getWidth() - MARGIN, RULE);
        c.y -= 13;
    }

    private void groupHeader(String label, int count) throws IOException {
        c.ensureSpace(20);
        c.rect(MARGIN, c.y - 4, CONTENT_W, 14, new float[]{ 241 / 255f, 245 / 255f, 249 / 255f });
        c.text(c.bold, 8.5f, MARGIN + 6, c.y, label, INK);
        c.text(c.regular, 8f, MARGIN + 12 + c.width(label, c.bold, 8.5f), c.y, count + " alarm", LABEL);
        c.y -= 20;
    }

    private void note(String s) throws IOException {
        for (String line : c.wrap(s, CONTENT_W, c.regular, 6.8f, 4)) {
            c.ensureSpace(11);
            c.text(c.regular, 6.8f, MARGIN, c.y, line, MUTED);
            c.y -= 9;
        }
        c.y -= 4;
    }

    private void bullet(String s) throws IOException {
        List<String> lines = c.wrap(s, CONTENT_W - 12, c.regular, 7.5f, 8);
        c.ensureSpace(lines.size() * 10f + 4);
        boolean first = true;
        for (String line : lines) {
            if (first) { c.text(c.bold, 7.5f, MARGIN, c.y, "•", BLUE); first = false; }
            c.text(c.regular, 7.5f, MARGIN + 10, c.y, line, INK);
            c.y -= 9.5f;
        }
        c.y -= 3;
    }

    private void statCards(List<String[]> cards) throws IOException {
        float gap = 8f;
        int perRow = 3;
        float cw = (CONTENT_W - gap * (perRow - 1)) / perRow;
        for (int i = 0; i < cards.size(); i += perRow) {
            c.ensureSpace(42);
            for (int j = 0; j < perRow && i + j < cards.size(); j++) {
                String[] card = cards.get(i + j);
                float x = MARGIN + j * (cw + gap);
                c.rect(x, c.y - 24, cw, 34, new float[]{ 248 / 255f, 250 / 255f, 252 / 255f });
                c.text(c.regular, 6.8f, x + 8, c.y - 1, c.clip(card[0], cw - 16, c.regular, 6.8f), LABEL);
                c.text(c.bold, 13f, x + 8, c.y - 18, c.clip(card[1], cw - 16, c.bold, 13f), INK);
            }
            c.y -= 42;
        }
    }

    /**
     * Tablo çizer; sayfa kırılımında başlık satırı YENİDEN çizilir.
     *
     * <p>Satırlar bir tedarikçiden alınır çünkü {@code PdfCanvas.newPage()} kırılım anında
     * başlık kancasını çağırıyor; kancanın tablo başlığını bilmesi gerekiyor.
     */
    private void table(String[] head, float[] widths, RowSupplier supplier) throws IOException {
        Runnable header = () -> {
            try { drawHeadRow(head, widths); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        };
        c.ensureSpace(30);
        header.run();
        c.setPageHeaderHook(header);
        try {
            int i = 0;
            for (String[] row : supplier.rows()) {
                c.ensureSpace(12);
                if (i % 2 == 1) c.rect(MARGIN, c.y - 3, CONTENT_W, 11, ZEBRA);
                float x = MARGIN;
                for (int col = 0; col < row.length && col < widths.length; col++) {
                    c.text(c.regular, 7f, x + 3, c.y, c.clip(row[col], widths[col] - 6, c.regular, 7f), INK);
                    x += widths[col];
                }
                c.y -= 11;
                i++;
            }
        } finally {
            c.setPageHeaderHook(null);
        }
        c.y -= 8;
    }

    private void drawHeadRow(String[] head, float[] widths) throws IOException {
        c.rect(MARGIN, c.y - 3, CONTENT_W, 12, new float[]{ 226 / 255f, 232 / 255f, 240 / 255f });
        float x = MARGIN;
        for (int i = 0; i < head.length && i < widths.length; i++) {
            c.text(c.bold, 6.8f, x + 3, c.y, c.clip(head[i], widths[i] - 6, c.bold, 6.8f), INK);
            x += widths[i];
        }
        c.y -= 14;
    }

    @FunctionalInterface
    private interface RowSupplier { List<String[]> rows() throws IOException; }

    // ── Hücre biçimleyiciler ─────────────────────────────────────────────────

    private static String notifyCell(OutageRow r) {
        if (r.notifySent() > 0) return r.notifySent() + (r.notifyFailed() > 0 ? "/" + r.notifyFailed() + "✗" : "");
        return r.notifyFailed() > 0 ? r.notifyFailed() + "✗" : "yok";
    }

    /** Durum hücresi kesintinin "neyle işaretli" olduğunu tek yerde toplar. */
    private static String statusCell(OutageRow r) {
        List<String> parts = new ArrayList<>();
        if (r.stillOpen()) parts.add("açık");
        if (r.carriedOver()) parts.add("devreden");
        if (r.maintenanceOverlap()) parts.add("bakım?");
        if (r.stormId() != null) parts.add("fırtına");
        if (parts.isEmpty()) parts.add(r.resolvedBy() == null ? "çözüldü" : "çözüldü: " + r.resolvedBy());
        return String.join(" · ", parts);
    }

    private static String nz(String s) { return s == null || s.isBlank() ? "—" : s; }
    private static String pct(Double v) { return v == null ? "—" : fmt(v); }
    private static String fmt(double v) { return String.format(Locale.of("tr", "TR"), "%.2f%%", v); }
    private static String signed(double v) {
        return (v > 0 ? "+" : "") + String.format(Locale.of("tr", "TR"), "%.1f", v);
    }

    @Override
    public void close() throws IOException {
        c.close();
    }
}
