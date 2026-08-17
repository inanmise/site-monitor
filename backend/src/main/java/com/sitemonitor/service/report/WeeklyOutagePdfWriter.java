package com.sitemonitor.service.report;

import com.sitemonitor.service.EmailNotificationService.AvailabilityRow;
import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.service.MonitoringWeeklyStatsService.TypeStats;
import com.sitemonitor.service.report.WeeklyOutageReportService.Bucket;
import com.sitemonitor.service.report.WeeklyOutageReportService.CertExpiry;
import com.sitemonitor.service.report.WeeklyOutageReportService.OutageRow;
import com.sitemonitor.service.report.WeeklyOutageReportService.RepeatItem;
import com.sitemonitor.service.report.WeeklyOutageReportService.TimelineRow;
import com.sitemonitor.service.report.WeeklyOutageReportService.TimelineSegment;
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
            timeline(d);
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

        // Kartların rengi DURUMU anlatır: sorun varsa kırmızı, temizse yeşil, nötr bilgi gri.
        // Renk tek başına taşıyıcı değil — sayı ve etiket her hâlükârda yazılı (renk körlüğü /
        // siyah-beyaz çıktı).
        List<Card> cards = new ArrayList<>();
        cards.add(new Card("Toplam alarm", String.valueOf(d.totalAlarms()),
                d.totalAlarms() > 0 ? Tone.WARN : Tone.GOOD));
        cards.add(new Card("Hâlâ açık", String.valueOf(d.stillOpenCount()),
                d.stillOpenCount() > 0 ? Tone.BAD : Tone.GOOD));
        cards.add(new Card("Etkilenen hedef", String.valueOf(d.affectedTargets()),
                d.affectedTargets() > 0 ? Tone.WARN : Tone.GOOD));
        cards.add(new Card("Kesinti süresi (bu hafta)",
                WeeklyOutageReportService.humanDuration(d.totalDowntimeMin()),
                d.totalDowntimeMin() > 0 ? Tone.WARN : Tone.GOOD));
        cards.add(new Card("Ortalama erişilebilirlik", pct(d.avgAvailabilityPct()),
                availabilityTone(d.avgAvailabilityPct())));
        cards.add(new Card("Kesinti yaşayan domain",
                d.domainsWithOutage() + " / " + d.availability().size(),
                d.domainsWithOutage() > 0 ? Tone.BAD : Tone.GOOD));
        statCards(cards);

        levelDonut(d);

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
        String lead = "Bu hafta " + d.alarmsOpenedThisWeek() + " alarm AÇILDI, geçen hafta "
                + d.alarmsPrevWeek() + " — ";
        c.ensureSpace(18);
        c.text(c.regular, 8.5f, MARGIN, c.y, lead, INK);
        c.text(c.bold, 8.5f, MARGIN + c.width(lead, c.regular, 8.5f), c.y, dir, color);
        c.y -= 14;
        // Karşılaştırma İKİ TARAFTA DA yalnız o hafta açılanı sayar. Yönetici özetindeki toplam
        // ise devreden alarmları da içerir; iki sayının neden farklı olduğu burada söylenmezse
        // okuyucu birini diğerinin yerine koyar.
        String note = "Karşılaştırma iki tarafta da yalnız o hafta AÇILAN alarmları sayar."
                + (d.carriedOverCount() > 0
                   ? " Yönetici özetindeki " + d.totalAlarms() + " alarm ise önceki haftalardan devreden "
                     + d.carriedOverCount() + " alarmı da içerir; onlar bu hafta açılmadıkları için "
                     + "karşılaştırmaya girmez."
                   : "");
        for (String line : c.wrap(note, CONTENT_W, c.regular, 7f, 3)) {
            c.ensureSpace(10);
            c.text(c.regular, 7f, MARGIN, c.y, line, MUTED);
            c.y -= 9;
        }
        c.y -= 8;
    }

    // ── 3. İzleme türü bazında özet ──────────────────────────────────────────

    private void typeSummary(WeeklyOutageData d) throws IOException {
        if (d.typeStats().isEmpty()) return;
        sectionHeader("İzleme Türü Bazında Özet");
        typeBars(d);
        float[] w = { 92, 46, 58, 52, 52, 46, 46 };
        String[] head = { "Tür", "İzleme", "Kontrol", "Başarı %", "Δ Başarı", "Açılan", "Açık" };
        table(head, w, () -> {
            List<Cell[]> rows = new ArrayList<>();
            for (TypeStats t : d.typeStats()) {
                if (t.activeMonitors() == 0 && t.alarmsOpened() == 0 && t.alarmsOpen() == 0) continue;
                rows.add(p(
                        MonitorTypeCatalog.label(t.type()),
                        String.valueOf(t.activeMonitors()),
                        String.valueOf(t.totalChecks()),
                        t.successRate() == null ? "—" : fmt(t.successRate()),
                        t.successRateDelta() == null ? "—" : signed(t.successRateDelta()),
                        String.valueOf(t.alarmsOpened()),
                        String.valueOf(t.alarmsOpen()) ));
            }
            return rows;
        });
        note("İzlemesi ve alarmı olmayan türler gizlendi.");
    }

    /**
     * Tür başına açılan/açık alarm çubukları — "bu hafta hangi tür sorunluydu" tek bakışta.
     *
     * <p>Yalnız alarmı olan türler çizilir; sıfırlı dokuz satır grafiği okunmaz hâle getirirdi.
     * Sayılar çubukların yanında AYRICA yazılı — çubuk uzunluğunu gözle ölçmek gerekmez ve
     * siyah-beyaz çıktıda bilgi kaybolmaz.
     */
    private void typeBars(WeeklyOutageData d) throws IOException {
        List<TypeStats> withAlarms = d.typeStats().stream()
                .filter(t -> t.alarmsOpened() > 0 || t.alarmsOpen() > 0).toList();
        if (withAlarms.isEmpty()) {
            note("Bu hafta hiçbir izleme türünde alarm açılmadı.");
            return;
        }
        int max = withAlarms.stream().mapToInt(t -> Math.max(t.alarmsOpened(), t.alarmsOpen())).max().orElse(1);
        float labelW = 92, barMax = 190;

        c.ensureSpace(16);
        c.text(c.regular, 6.5f, MARGIN + labelW, c.y, "açılan", BLUE);
        c.text(c.regular, 6.5f, MARGIN + labelW + 30, c.y, "· açık", AMBER);
        c.y -= 10;

        for (TypeStats t : withAlarms) {
            c.ensureSpace(20);
            c.text(c.regular, 7.5f, MARGIN, c.y - 3,
                    c.clip(MonitorTypeCatalog.label(t.type()), labelW - 6, c.regular, 7.5f), INK);
            c.hBar(MARGIN + labelW, c.y + 1, barMax, t.alarmsOpened(), max, BLUE);
            c.hBar(MARGIN + labelW, c.y - 8, barMax, t.alarmsOpen(), max, AMBER);
            c.text(c.regular, 6.8f, MARGIN + labelW + barMax + 6, c.y + 1,
                    t.alarmsOpened() + " açılan", LABEL);
            c.text(c.regular, 6.8f, MARGIN + labelW + barMax + 6, c.y - 8,
                    t.alarmsOpen() + " açık", LABEL);
            c.y -= 20;
        }
        c.y -= 4;
    }

    // ── 3b. Kesinti zaman çizelgesi ──────────────────────────────────────────

    /**
     * Hafta boyunca hedef başına kesinti aralıkları (Gantt).
     *
     * <p>Raporun en çok bilgi taşıyan görseli: "hangi izleme ne zaman ne kadar kesinti yaşadı"
     * sorusunun doğrudan cevabı. Satırlar alt alta hizalı olduğu için AYNI ANDA düşen hedefler
     * (ortak kök neden / alarm fırtınası) tabloda hiç görünmeyecek şekilde gözle fark edilir.
     *
     * <p>Çubuk rengi alarm SEVİYESİNDEN gelir ve tablo rozetleriyle aynı paleti kullanır; sağda
     * toplam süre yazılı olduğu için renk kaybolsa da bilgi durur.
     */
    private void timeline(WeeklyOutageData d) throws IOException {
        if (d.timeline().isEmpty() || d.windowMinutes() <= 0) return;
        sectionHeader("Kesinti Zaman Çizelgesi");
        note("Her satır bir hedef, her çubuk o hedefin bir kesinti aralığı. Renk alarm seviyesini "
                + "gösterir. Sağdaki süre, hedefin haftanın NE KADARINDA kesintide olduğudur: "
                + "aynı anda süren alarmlar iki kez sayılmaz, bu yüzden bu sayı 7 günü aşamaz "
                + "(yönetici özetindeki toplam ise alarm başına sürelerin toplamıdır ve aşabilir). "
                + "Alt alta hizalanan çubuklar eşzamanlı kesintiyi (ortak kök neden olabilir) işaret eder.");

        float labelW = 132, chartW = 300, durW = 60;
        Runnable axis = () -> {
            try { drawTimelineAxis(labelW, chartW); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        };
        c.ensureSpace(30);
        axis.run();
        c.setPageHeaderHook(axis);
        try {
            for (TimelineRow row : d.timeline()) {
                c.ensureSpace(13);
                c.text(c.regular, 7f, MARGIN, c.y,
                        c.clip(nz(row.target()), labelW - 6, c.regular, 7f), INK);
                // Zemin: haftanın tamamı açık gri — çubuk yoksa "veri yok" değil "kesinti yok" demek.
                c.rect(MARGIN + labelW, c.y - 1, chartW, 7f, new float[]{ 241 / 255f, 245 / 255f, 249 / 255f });
                for (TimelineSegment s : row.segments()) {
                    float x = MARGIN + labelW + (float) (chartW * s.startOffsetMin() / d.windowMinutes());
                    float w = (float) (chartW * s.durationMin() / d.windowMinutes());
                    // Çok kısa kesinti 0.1pt olur ve hiç çizilmez → "kesinti yaşanmamış" gibi okunur.
                    w = Math.max(w, 1.2f);
                    if (x + w > MARGIN + labelW + chartW) w = MARGIN + labelW + chartW - x;
                    if (w > 0) c.rect(x, c.y - 1, w, 7f, PdfCanvas.levelSolid(s.level()));
                }
                c.text(c.regular, 6.8f, MARGIN + labelW + chartW + 6, c.y,
                        c.clip(WeeklyOutageReportService.humanDuration(row.totalMin()), durW, c.regular, 6.8f), LABEL);
                c.y -= 12;
            }
        } finally {
            c.setPageHeaderHook(null);
        }
        c.y -= 6;
        levelLegend();
    }

    /** Çizelgenin gün ekseni — sayfa kırılımında yeniden çizilir, yoksa alttaki çubuklar okunmaz. */
    private void drawTimelineAxis(float labelW, float chartW) throws IOException {
        float x0 = MARGIN + labelW;
        String[] days = { "Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz" };
        for (int i = 0; i < 7; i++) {
            float x = x0 + chartW * i / 7f;
            c.text(c.regular, 6f, x + 1, c.y, days[i], MUTED);
            c.line(x, c.y - 3, x, RULE);
        }
        c.line(x0, c.y - 3, x0 + chartW, RULE);
        c.y -= 13;
    }

    /** Seviye renk göstergesi — renk körlüğü/siyah-beyaz için ad ve renk birlikte. */
    private void levelLegend() throws IOException {
        c.ensureSpace(14);
        float x = MARGIN;
        c.text(c.regular, 6.5f, x, c.y, "Seviye:", LABEL);
        x += 32;
        for (String lvl : List.of("CRITICAL", "HIGH", "WARNING")) {
            c.rect(x, c.y - 1, 7, 7, PdfCanvas.levelSolid(lvl));
            c.text(c.regular, 6.5f, x + 10, c.y, PdfCanvas.levelLabel(lvl), INK);
            x += 12 + c.width(PdfCanvas.levelLabel(lvl), c.regular, 6.5f) + 12;
        }
        c.y -= 14;
    }

    // ── 4. Kesinti detayı — tür bazında (ana gövde) ──────────────────────────

    private void outageDetail(WeeklyOutageData d) throws IOException {
        sectionHeader("Kesinti Detayı — İzleme Türü Bazında");
        note("Her satır bir alarmdır. Hafta içinde açık olan TÜM alarmlar listelenir; "
                + "önceki haftadan devredenler «devreden» ile işaretlidir.");

        for (TypeGroup g : d.groups()) {
            c.ensureSpace(40);
            groupHeader(g.label(), g.rows().size());
            float[] w = { 108, 62, 46, 50, 48, 40, 52, 30, 62 };
            String[] head = { "Hedef", "Alarm", "Seviye", "Başlangıç", "Bitiş", "Süre", "Sahiplenen", "Bild.", "Durum" };
            table(head, w, () -> {
                List<Cell[]> rows = new ArrayList<>();
                for (OutageRow r : g.rows()) {
                    rows.add(new Cell[]{
                            plain(nz(r.target())),
                            plain(nz(r.alertType())),
                            levelBadge(r.level()),
                            plain(WeeklyOutageReportService.shortStamp(r.startedAt())),
                            plain(r.stillOpen() ? "sürüyor" : WeeklyOutageReportService.shortStamp(r.endedAt())),
                            plain(WeeklyOutageReportService.humanDuration(r.durationMin())),
                            plain(nz(r.acknowledgedBy())),
                            notifyCellColored(r),
                            statusBadge(r) });
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
            // Sıralama HAFTAYA DÜŞEN süreye göre. Ham toplam süreye göre sıralandığında liste
            // aylardır süren devreden alarmlarla doluyor ve bu haftanın en kötüleri ilk ona hiç
            // giremiyordu. İki sütun birden yazılıyor ki gerçek boy da kaybolmasın.
            note("«Bu hafta» sütunu kesintinin bu haftaya düşen payı, «Toplam» ise alarmın açılışından "
                    + "bu yana geçen gerçek süredir. Sıralama bu haftaya düşen süreye göredir.");
            float[] w = { 126, 70, 56, 56, 54, 54, 66 };
            String[] head = { "Hedef", "Alarm", "Bu hafta", "Toplam", "Başlangıç", "Bitiş", "Tür" };
            table(head, w, () -> {
                List<Cell[]> rows = new ArrayList<>();
                for (OutageRow r : d.longest()) {
                    boolean differs = r.weekDurationMin() != r.durationMin();
                    rows.add(new Cell[]{
                            plain(nz(r.target())), plain(nz(r.alertType())),
                            plain(WeeklyOutageReportService.humanDuration(r.weekDurationMin())),
                            // Devreden alarmda toplam çok daha uzundur; vurgulanır ki gözden kaçmasın.
                            differs ? new Cell(WeeklyOutageReportService.humanDuration(r.durationMin()),
                                               null, AMBER, null, null)
                                    : plain(WeeklyOutageReportService.humanDuration(r.durationMin())),
                            plain(WeeklyOutageReportService.shortStamp(r.startedAt())),
                            plain(r.stillOpen() ? "sürüyor" : WeeklyOutageReportService.shortStamp(r.endedAt())),
                            plain(MonitorTypeCatalog.label(r.monitorType())) });
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
                List<Cell[]> rows = new ArrayList<>();
                for (RepeatItem r : d.repeats()) {
                    rows.add(p( nz(r.target()), nz(r.alertType()), nz(r.typeLabel()),
                            r.count() + " kez" ));
                }
                return rows;
            });
        }
    }

    // ── 6. Gün / saat dağılımı ───────────────────────────────────────────────

    private void distribution(WeeklyOutageData d) throws IOException {
        // Devreden alarmlar bu bölümde sayılmıyor; hiç alarm açılmamışsa çizilecek bir şey yok.
        // Boş bir ızgara "veri yok"la "alarm yok"u ayırt ettirmez.
        if (d.alarmsOpenedThisWeek() == 0) return;

        sectionHeader("Gün ve Saat Dağılımı");
        note("Bu hafta AÇILAN " + d.alarmsOpenedThisWeek() + " alarmın dağılımı. Önceki haftalardan "
                + "devreden alarmlar burada sayılmaz — başlangıçları bu haftanın dışında kalır ve "
                + "sayılsalardı başka bir haftanın günü/saati bu haftanın kutusuna yazılmış olurdu.");

        int maxDay = d.byDay().stream().mapToInt(Bucket::count).max().orElse(0);
        for (Bucket b : d.byDay()) {
            c.ensureSpace(13);
            c.text(c.regular, 7.5f, MARGIN, c.y, b.label(), INK);
            c.hBar(MARGIN + 66, c.y - 1.5f, 170, b.count(), maxDay, BLUE);
            float w = maxDay <= 0 ? 0 : Math.max(b.count() > 0 ? 1.5f : 0f, 170f * b.count() / maxDay);
            c.text(c.regular, 7.5f, MARGIN + 66 + w + 5, c.y, String.valueOf(b.count()), LABEL);
            c.y -= 12;
        }
        c.y -= 8;

        heatmap(d);
    }

    /**
     * Gün × saat ısı haritası.
     *
     * <p>İki ayrı liste (günler ve saatler) her boyutu tek başına gösteriyordu; "her Cumartesi
     * gece 03:00" gibi bir desen ancak iki boyut BİRLİKTE çizilince görünür. Yoğunluk koyulukla
     * verilir ama sıfır olmayan her hücreye SAYI da yazılır — renk tek başına taşıyıcı değil.
     */
    private void heatmap(WeeklyOutageData d) throws IOException {
        int max = d.heat().stream().flatMap(r -> r.hours().stream()).mapToInt(Integer::intValue).max().orElse(0);
        if (max == 0) return;

        c.ensureSpace(24 + 7 * 13);
        c.text(c.bold, 7.5f, MARGIN, c.y, "Gün × saat yoğunluğu (Europe/Istanbul)", LABEL);
        c.y -= 12;

        float labelW = 52, cellW = (CONTENT_W - labelW - 26) / 24f, cellH = 11f;

        // Saat başlıkları — her saati yazmak sığmaz, 3 saatte bir yeter.
        for (int h = 0; h < 24; h += 3) {
            c.text(c.regular, 5.5f, MARGIN + labelW + h * cellW, c.y, String.format("%02d", h), MUTED);
        }
        c.y -= 9;

        for (WeeklyOutageReportService.HeatRow row : d.heat()) {
            c.ensureSpace(cellH + 2);
            c.text(c.regular, 6.8f, MARGIN, c.y + 2, row.day(), INK);
            for (int h = 0; h < 24; h++) {
                int v = row.hours().get(h);
                float x = MARGIN + labelW + h * cellW;
                c.heatCell(x, c.y, cellW - 1f, cellH - 1f, (double) v / max, RED);
                if (v > 0) {
                    String s = String.valueOf(v);
                    // Koyu hücrede beyaz, açık hücrede koyu yazı — ikisi de okunur kalsın.
                    float[] fg = ((double) v / max) > 0.55 ? WHITE : INK;
                    c.text(c.bold, 5.5f, x + (cellW - 1f - c.width(s, c.bold, 5.5f)) / 2, c.y + 3, s, fg);
                }
            }
            c.y -= cellH + 1;
        }
        c.y -= 6;
        note("Koyuluk o saatte açılan alarm sayısını gösterir; sayı hücrenin içinde de yazılıdır. "
                + "Boş hücre o saatte alarm açılmadığı anlamına gelir.");
    }

    // ── 7. Hâlâ açık alarmlar ────────────────────────────────────────────────

    private void stillOpen(WeeklyOutageData d) throws IOException {
        if (d.openNow().isEmpty()) return;
        sectionHeader("Hâlâ Açık Alarmlar");
        note("Hafta kapandı ama bu alarmlar çözülmedi — pazartesi sabahının iş listesi.");
        float[] w = { 132, 74, 58, 56, 58, 46, 52 };
        String[] head = { "Hedef", "Alarm", "Seviye", "Başlangıç", "Süre", "Bild.", "Sahiplenen" };
        table(head, w, () -> {
            List<Cell[]> rows = new ArrayList<>();
            for (OutageRow r : d.openNow()) {
                rows.add(new Cell[]{
                        plain(nz(r.target())), plain(nz(r.alertType())), levelBadge(r.level()),
                        plain(WeeklyOutageReportService.shortStamp(r.startedAt())),
                        plain(WeeklyOutageReportService.humanDuration(r.durationMin())),
                        notifyCellColored(r), plain(nz(r.acknowledgedBy())) });
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
            List<Cell[]> rows = new ArrayList<>();
            for (OutageRow r : d.notifyGaps()) {
                rows.add(new Cell[]{
                        plain(nz(r.target())), plain(nz(r.alertType())), levelBadge(r.level()),
                        plain(WeeklyOutageReportService.shortStamp(r.startedAt())),
                        new Cell(r.notifyFailed() > 0 ? r.notifyFailed() + " deneme" : "kayıt yok",
                                null, RED, null, null) });
            }
            return rows;
        });
    }

    // ── 9. Erişilebilirlik tablosu ───────────────────────────────────────────

    private void availability(WeeklyOutageData d) throws IOException {
        if (d.availability().isEmpty()) return;
        sectionHeader("Erişilebilirlik (HTTP · sertifika envanteri domainleri)");
        float[] w = { 142, 76, 42, 62, 62, 46, 46 };
        String[] head = { "Domain", "Uptime %", "Kesinti", "Toplam süre", "En uzun", "Ort. ms", "p95 ms" };
        table(head, w, () -> {
            List<Cell[]> rows = new ArrayList<>();
            for (AvailabilityRow r : d.availability()) {
                rows.add(new Cell[]{
                        plain(nz(r.domain())),
                        availabilityCell(r.availabilityPct()),
                        r.outageCount() > 0 ? new Cell(String.valueOf(r.outageCount()), null, AMBER, null, null)
                                            : plain("0"),
                        plain(WeeklyOutageReportService.humanDuration(r.downtimeMinutes())),
                        plain(WeeklyOutageReportService.humanDuration(r.longestOutageMinutes())),
                        plain(r.avgMs() == null ? "—" : String.valueOf(r.avgMs())),
                        plain(r.p95Ms() == null ? "—" : String.valueOf(r.p95Ms())) });
            }
            return rows;
        });
        note("Uptime sütunundaki çubuk yüzdeyi görselleştirir: %99,9 ve üzeri yeşil, %99–99,9 turuncu, "
                + "altı kırmızı. Yüzde ayrıca yazılıdır. Bu tablo e-posta gövdesiyle AYNI kaynaktan "
                + "gelir (uptime_checks) ve bakım pencerelerini hariç tutar; kapsamı yalnız sertifika "
                + "envanterindeki domainlerdir.");
    }

    // ── 10. Sertifika bitişleri ──────────────────────────────────────────────

    private void certExpiries(WeeklyOutageData d) throws IOException {
        if (d.certExpiries().isEmpty()) return;
        sectionHeader("Süresi Yaklaşan Sertifikalar (≤ 60 gün)");
        float[] w = { 220, 80, 80 };
        String[] head = { "Domain", "Kalan gün", "Durum" };
        table(head, w, () -> {
            List<Cell[]> rows = new ArrayList<>();
            for (CertExpiry e : d.certExpiries()) {
                Integer days = e.daysRemaining();
                String state = days == null ? "—" : days < 0 ? "SÜRESİ DOLDU" : days <= 15 ? "kritik"
                        : days <= 30 ? "yakın" : "izlemede";
                // Renk kalan güne göre; durum metni her hâlükârda yazılı kalır.
                String lvl = days == null ? "" : days < 0 || days <= 15 ? "CRITICAL"
                        : days <= 30 ? "HIGH" : "WARNING";
                float[][] col = PdfCanvas.levelColors(lvl);
                rows.add(new Cell[]{
                        plain(nz(e.domain())),
                        plain(days == null ? "—" : String.valueOf(days)),
                        days == null ? plain(state) : new Cell(state, col[0], col[1], null, null) });
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
        bullet("Raporda İKİ ayrı süre kavramı var ve karıştırılmamalı: detay tablolarındaki süre "
                + "alarmın açılışından çözülüşüne kadar geçen GERÇEK süredir; yönetici özetindeki "
                + "toplam ile zaman çizelgesindeki süreler ise yalnız BU HAFTAYA düşen payı sayar. "
                + "Ayrım olmasaydı aylardır süren tek bir kesinti haftalık toplamı yüzlerce güne "
                + "çıkarırdı.");
        bullet("Hâlâ açık alarmlarda süre hafta sonuna kadar sayılır (şu ana kadar değil) — böylece "
                + "geçmiş bir haftanın raporu ne zaman üretilirse üretilsin aynı sonucu verir.");
        bullet("Gün/saat dağılımı ve ısı haritası yalnız BU HAFTA AÇILAN alarmları sayar; devreden "
                + "alarmların başlangıcı bu haftanın dışında olduğu için oraya yazılmaları başka bir "
                + "haftanın gününü bu haftaya mal etmek olurdu. «Geçen haftaya göre» karşılaştırması "
                + "da aynı sebeple iki tarafta da yalnız açılan alarmları sayar.");
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

    /** Özet kartının durumu — rengi belirler; metin her zaman ayrıca yazılı kalır. */
    private enum Tone { GOOD, WARN, BAD, NEUTRAL }

    private record Card(String label, String value, Tone tone) {}

    private static Tone availabilityTone(Double pct) {
        if (pct == null) return Tone.NEUTRAL;
        if (pct >= 99.9) return Tone.GOOD;
        return pct >= 99.0 ? Tone.WARN : Tone.BAD;
    }

    private void statCards(List<Card> cards) throws IOException {
        float gap = 8f;
        int perRow = 3;
        float cw = (CONTENT_W - gap * (perRow - 1)) / perRow;
        for (int i = 0; i < cards.size(); i += perRow) {
            c.ensureSpace(42);
            for (int j = 0; j < perRow && i + j < cards.size(); j++) {
                Card card = cards.get(i + j);
                float x = MARGIN + j * (cw + gap);
                float[] bg = switch (card.tone()) {
                    case GOOD -> new float[]{ 236 / 255f, 253 / 255f, 245 / 255f };
                    case WARN -> new float[]{ 255 / 255f, 247 / 255f, 237 / 255f };
                    case BAD  -> new float[]{ 254 / 255f, 242 / 255f, 242 / 255f };
                    case NEUTRAL -> new float[]{ 248 / 255f, 250 / 255f, 252 / 255f };
                };
                float[] fg = switch (card.tone()) {
                    case GOOD -> GREEN;
                    case WARN -> AMBER;
                    case BAD  -> RED;
                    case NEUTRAL -> INK;
                };
                c.rect(x, c.y - 24, cw, 34, bg);
                // Sol kenarda ince renk şeridi — zemin rengi soluk yazdırıldığında bile ayırt edilir.
                c.rect(x, c.y - 24, 2.5f, 34, fg);
                c.text(c.regular, 6.8f, x + 10, c.y - 1, c.clip(card.label(), cw - 18, c.regular, 6.8f), LABEL);
                c.text(c.bold, 13f, x + 10, c.y - 18, c.clip(card.value(), cw - 18, c.bold, 13f), fg);
            }
            c.y -= 42;
        }
    }

    /**
     * Seviye dağılımı halkası + sayılı gösterge.
     *
     * <p>Gösterge zorunlu: dilim renkleri tek başına hangi seviyenin ne kadar olduğunu söylemez
     * ve siyah-beyaz çıktıda tamamen kaybolur. Her satırda seviye ADI ve SAYISI yazılı.
     */
    private void levelDonut(WeeklyOutageData d) throws IOException {
        int total = d.byLevel().stream().mapToInt(Bucket::count).sum();
        if (total == 0) return;

        c.ensureSpace(78);
        float cx = MARGIN + 40, cy = c.y - 34;
        double angle = 90;   // saat 12'den başla, saat yönünde ilerle
        for (Bucket b : d.byLevel()) {
            double sweep = 360.0 * b.count() / total;
            c.donutSlice(cx, cy, 30, 17, angle - sweep, sweep, PdfCanvas.levelSolid(b.label()));
            angle -= sweep;
        }
        // Ortada toplam — halkanın kendisi bir sayı vermez.
        String totalStr = String.valueOf(total);
        c.text(c.bold, 12f, cx - c.width(totalStr, c.bold, 12f) / 2, cy - 4, totalStr, INK);
        c.text(c.regular, 5.5f, cx - c.width("alarm", c.regular, 5.5f) / 2, cy - 13, "alarm", LABEL);

        float lx = MARGIN + 92;
        float ly = c.y - 12;
        for (Bucket b : d.byLevel()) {
            c.rect(lx, ly - 1, 7, 7, PdfCanvas.levelSolid(b.label()));
            String pctStr = String.format(Locale.of("tr", "TR"), "%.0f%%", 100.0 * b.count() / total);
            c.text(c.bold, 7.5f, lx + 11, ly, PdfCanvas.levelLabel(b.label()), INK);
            c.text(c.regular, 7.5f, lx + 62, ly, b.count() + " alarm  ·  " + pctStr, LABEL);
            ly -= 12;
        }
        c.y -= 78;
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
            for (Cell[] row : supplier.rows()) {
                c.ensureSpace(12);
                if (i % 2 == 1) c.rect(MARGIN, c.y - 3, CONTENT_W, 11, ZEBRA);
                float x = MARGIN;
                for (int col = 0; col < row.length && col < widths.length; col++) {
                    drawCell(row[col], x, widths[col]);
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

    /**
     * Tablo hücresi: düz metin, renkli rozet ya da metin + oran çubuğu.
     *
     * <p>Rozet ve çubuk METNİ ASLA GİZLEMEZ — seviye adı ve yüzde her hâlükârda yazılı kalır.
     * Renk yalnız hızlı taramaya yardım eder; siyah-beyaz yazdırıldığında ya da kırmızı-yeşil
     * ayırt edilemediğinde hiçbir bilgi kaybolmaz (kullanıcı kararı).
     */
    private void drawCell(Cell cell, float x, float width) throws IOException {
        if (cell == null) return;
        String txt = c.clip(cell.text(), width - (cell.barPct() != null ? 34 : 6), c.regular, 7f);
        if (cell.bg() != null) {
            float w = Math.min(c.width(txt, c.bold, 6.5f) + 8, width - 4);
            c.rect(x + 2, c.y - 2.5f, w, 10.5f, cell.bg());
            c.text(c.bold, 6.5f, x + 6, c.y, txt, cell.fg());
            return;
        }
        c.text(c.regular, 7f, x + 3, c.y, txt, cell.fg() == null ? INK : cell.fg());
        if (cell.barPct() != null) {
            c.ratioBar(x + width - 30, c.y + 0.5f, 26, cell.barPct(), cell.barColor());
        }
    }

    /** Tablo hücresi — bg dolu ise rozet, barPct dolu ise metnin sağında oran çubuğu. */
    private record Cell(String text, float[] bg, float[] fg, Double barPct, float[] barColor) {}

    private static Cell[] p(String... values) {
        Cell[] out = new Cell[values.length];
        for (int i = 0; i < values.length; i++) out[i] = new Cell(values[i], null, null, null, null);
        return out;
    }

    private static Cell plain(String s) { return new Cell(s, null, null, null, null); }

    /** Seviye rozeti — 400 satırlık bir tabloda kritik olanı gözle bulmak aksi hâlde imkânsız. */
    private static Cell levelBadge(String level) {
        float[][] col = PdfCanvas.levelColors(level);
        return new Cell(PdfCanvas.levelLabel(level), col[0], col[1], null, null);
    }

    /** Erişilebilirlik hücresi: yüzde yazılı + yanında eşiğe göre renklenen oran çubuğu. */
    private static Cell availabilityCell(Double pct) {
        if (pct == null) return plain("veri yok");
        float[] color = pct >= 99.9 ? GREEN : pct >= 99.0 ? AMBER : RED;
        return new Cell(fmt(pct), null, color, pct, color);
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
    private interface RowSupplier { List<Cell[]> rows() throws IOException; }

    // ── Hücre biçimleyiciler ─────────────────────────────────────────────────

    private static String notifyCell(OutageRow r) {
        if (r.notifySent() > 0) return r.notifySent() + (r.notifyFailed() > 0 ? "/" + r.notifyFailed() + "✗" : "");
        return r.notifyFailed() > 0 ? r.notifyFailed() + "✗" : "yok";
    }

    /** Bildirim hücresi — hiç ulaşmamışsa kırmızı. Metin ("yok") zaten bilgiyi taşıyor. */
    private static Cell notifyCellColored(OutageRow r) {
        String s = notifyCell(r);
        return r.notifySent() == 0 ? new Cell(s, null, RED, null, null) : plain(s);
    }

    /**
     * Durum rozeti — en BASKIN işaret renklendirilir.
     *
     * <p>Dört işaret (açık/devreden/bakım?/fırtına) tek hücreye sığmıyor; hepsi metin olarak
     * yazılırken renk yalnız en önemlisini vurgular: hâlâ açık olmak, planlı bakım olmaktan
     * daha acildir. Metnin tamamı korunduğu için renk kaybolsa da bilgi durur.
     */
    private static Cell statusBadge(OutageRow r) {
        String txt = statusCell(r);
        if (r.stillOpen()) {
            float[][] col = PdfCanvas.levelColors("CRITICAL");
            return new Cell(txt, col[0], col[1], null, null);
        }
        if (r.maintenanceOverlap()) {
            float[][] col = PdfCanvas.levelColors("INFO");
            return new Cell(txt, col[0], col[1], null, null);
        }
        if (r.stormId() != null) {
            float[][] col = PdfCanvas.levelColors("HIGH");
            return new Cell(txt, col[0], col[1], null, null);
        }
        return new Cell(txt, null, GREEN, null, null);   // çözüldü
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
