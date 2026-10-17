package com.sitemonitor.service.techerror;

import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.StandaloneMonitorDeletionBackfill;
import com.sitemonitor.service.techerror.TechErrorTemplate.Calc;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Slot;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEK SEFERLİK şablon göçü (2026-10-16, kullanıcı kararı: "mevcut kanalların şablonları otomatik güncellensin"). Oranlar
 * artık adetlerden hesaplanıyor ({@link TechErrorCalc}); bu iş MEVCUT kanal şablonlarında varsayılandan gelen alanları
 * hesaplanır hâle getirir:
 * <ul>
 *   <li>{@code error-rate}, {@code error-rate-excl}, {@code outage-share} → oran; {@code tx-increase-pct} → önceki aya göre
 *       değişim; {@code tx-increase} → artış (alan var, türü beklenen, henüz hesaplanmıyor ve girdileri şablonda varsa);</li>
 *   <li>{@code unmapped-rate} ve {@code quota-rate} yoksa eklenir (girdileri varsa; {@code error-rate}'in bölümüne);</li>
 *   <li>değişen bölümlerde girilen alanlar üstte, hesaplananlar altta (kullanıcı isteği: "üstte adetler, altta oranlar");</li>
 *   <li>yalnız ESKİ VARSAYILANLA birebir aynı ipucu / ad / özet cümlesi yenisiyle değişir — ekibin yazdığına dokunulmaz;</li>
 *   <li>varsayılandan gelen otomatik trend bölümü ({@code auto-trend}) kaldırılır (kullanıcı kararı 2026-10-16: grafikleri
 *       görsel olarak kendileri sağlıyor); ekibin kendi eklediği trend bölümü kalır;</li>
 *   <li>grafik bölümleri ESKİ VARSAYILANLA birebir aynıysa (ilk sürümün tek "Grafikler" bölümü ya da 2026-10-15'in dört
 *       bölümlük akışı — bölüm kimliği, başlığı ve yuvaları) yeni iki bölümle değişir: yıllık ve günlük göstergeler
 *       (kullanıcı kararı 2026-10-16, {@link TechErrorTemplate#defaultCharts}); ekibin değiştirdiği bölüme dokunulmaz,
 *       SLA ekran görüntüsü bölümü kalır.</li>
 * </ul>
 * Açılmış / gönderilmiş raporlar DEĞİŞMEZ (şablonun kopyasını taşırlar; taslak "şablonun son hâlini uygula" ile alır).
 * Açılış yolunda değil: açılıştan 2 dk sonra, kendi kilidi altında; nişan kilit İÇİNDE yeniden okunur ve yalnız TÜM kanallar
 * işlendiyse yazılır (yarıda kalırsa bir saat sonra kalan kanallar yeniden denenir — dönüşüm tekrar uygulanınca değişiklik
 * üretmez). Her kanal {@code saveTemplate} ile yazılır: koşullu güncelleme (ekibin aynı andaki kaydı kaybolmaz) + sürüm
 * geçmişinde "SiteMonitor" adıyla yeni sürüm.
 */
@Slf4j
@Service
public class TechErrorCalcMigration {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ (değişirse iş, ekibin bu arada geri aldığı hesapları yeniden ekler). */
    static final String KEY = "2026-10-16-tech-error-calc-and-charts";
    static final String ACTOR = "SiteMonitor";
    static final String LOCK = "tech-error-calc-migration";
    static final int MAX_ATTEMPTS = 24;
    /** Varsayılan şablonun otomatik trend bölümünün kimliği (göçte kaldırılır). */
    static final String DEFAULT_TREND_ID = "auto-trend";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TechErrorChannelRepository channels;
    private final TechErrorReportService service;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    private final AtomicInteger failures = new AtomicInteger();

    public TechErrorCalcMigration(TechErrorChannelRepository channels, TechErrorReportService service, JdbcTemplate jdbc,
                                  ObjectProvider<SchedulerService> scheduler) {
        this.channels = channels;
        this.service = service;
        this.jdbc = jdbc;
        this.scheduler = scheduler;
    }

    @Scheduled(initialDelayString = "${site.monitor.tech-error.calc-migration.initial-delay-ms:120000}", fixedDelay = 3_600_000L)
    public void run() {
        if (failures.get() >= MAX_ATTEMPTS) return;           // bu pod denemeyi bıraktı (bir kez ERROR yazıldı)
        if (done()) return;
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null) return;
        try {
            if (s.callWithSchedulerLock(LOCK, this::migrateLocked).isPresent()) failures.set(0);
        } catch (RuntimeException e) {
            int n = failures.incrementAndGet();
            if (n >= MAX_ATTEMPTS) {
                log.error("Teknik hata raporu oran göçü {} denemede tamamlanamadı — bu pod denemeyi bırakıyor (nişan yazılmadı)", n, e);
            } else {
                log.warn("Teknik hata raporu oran göçü başarısız (bir saat sonra yeniden denenecek): {}", e.toString());
            }
        }
    }

    /** Nişan yazılmış mı; okunamazsa "evet" (bu tur koşmaz, sonraki tur yeniden bakar). */
    boolean done() {
        try {
            jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, KEY);
            return n != null && n > 0;
        } catch (RuntimeException e) {
            log.debug("Oran göçü nişanı okunamadı: {}", e.getMessage());
            return true;
        }
    }

    /** Kilit altında: tüm kanalları dönüştürür, sonra nişanı yazar. Dönüş: değiştirilen kanal sayısı. */
    Integer migrateLocked() {
        if (done()) return 0;
        int changed = 0;
        for (TechErrorChannel ch : channels.findAll()) {
            for (int attempt = 1; ; attempt++) {
                TechErrorChannel cur = channels.findById(ch.getId()).orElse(null);
                if (cur == null) break;                                   // arada silindi
                Optional<Template> next = migrate(TechErrorTemplate.fromStored(cur.getTemplateJson()));
                if (next.isEmpty()) break;                                // değişecek bir şey yok (ya da zaten göçmüş)
                try {
                    service.saveTemplate(cur.getId(), cur.getTemplateVersion(), next.get(), null, ACTOR);
                    changed++;
                    break;
                } catch (java.util.NoSuchElementException e) {
                    break;                                                // okuma ile kayıt arasında silindi (inceleme 2026-10-17)
                } catch (OptimisticLockingFailureException e) {
                    if (attempt >= 3) throw e;                            // ekip o anda kaydediyor: sonraki turda yeniden
                }
            }
        }
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                KEY, ISO.format(Instant.now()), changed);
        log.info("Teknik hata raporu şablon göçü tamamlandı: {} kanal şablonu güncellendi (oranlar adetlerden hesaplanır, "
                + "varsayılan grafik bölümleri yıllık + günlük göstergeler)", changed);
        return changed;
    }

    // ── Saf dönüşüm ──────────────────────────────────────────────────────────────────────────────────

    private record Conversion(String id, String type, Calc calc, Set<String> oldHints, String hintTr, String hintEn,
                              Map<String, String> labels) { }

    private static final String NO_HINT = "\u0000";   // "ipucu yoktu" (null Set içinde tutulamaz)

    private static final List<Conversion> CONVERSIONS = List.of(
            new Conversion("error-rate", "percent", Calc.ratio("error-count", null, "total-tx"),
                    Set.of("İlgili ay/yıl verilerine göre", "Based on this month's figures"), null, null, Map.of()),
            new Conversion("error-rate-excl", "percent", Calc.ratio("error-count", "outage-errors", "total-tx"),
                    Set.of("Kesintilerin olduğu günlerde alınan hatalar çıkarıldığında", "With errors from outage days left out"),
                    null, null, Map.of()),
            new Conversion("outage-share", "percent", Calc.ratio("outage-errors", null, "error-count"), Set.of(), null, null,
                    Map.of("Kesinti kaynaklı hataların toplam hataya oranı", "Kesinti kaynaklı hataların teknik hatalara oranı",
                            "Share of outage-caused errors in all errors", "Share of outage-caused errors in technical errors")),
            new Conversion("tx-increase-pct", "percent", Calc.change("total-tx"),
                    Set.of("Artış ya da azalış oranını işaretsiz yazın (ör. 3,5); yönü yukarıdaki soru belirler",
                            "Enter the size of the increase or decrease without a sign (e.g. 3.5); the question above sets the direction"),
                    TechErrorTemplate.DEFAULT_HINT_CHANGE_TR, TechErrorTemplate.DEFAULT_HINT_CHANGE_EN, Map.of()),
            new Conversion("tx-increase", "yesno", Calc.increase("total-tx"), Set.of(NO_HINT),
                    TechErrorTemplate.DEFAULT_HINT_INCREASE_TR, TechErrorTemplate.DEFAULT_HINT_INCREASE_EN, Map.of()));

    /** Eski varsayılan özet cümlesi → yenisi (yalnız birebir aynıysa; cümlenin dayandığı oran alanı şablonda varsa). */
    private record LineSwap(String oldLine, String newLine, String needs) { }

    private static final List<LineSwap> LINES = List.of(
            new LineSwap("Map yapılmamış hata adedi {f:unmapped-count} olarak kaydedilmiştir.",
                    "Map yapılmamış hata adedi {f:unmapped-count} olup teknik hataların {f:unmapped-rate} kadarını oluşturmaktadır.",
                    "unmapped-rate"),
            new LineSwap("Kota hatası adedi {f:quota-count} olarak kaydedilmiştir.",
                    "Kota hatası adedi {f:quota-count} olup teknik hataların {f:quota-rate} kadarını oluşturmaktadır.", "quota-rate"),
            new LineSwap("Teknik hataların {f:outage-errors} adedi kesinti kaynaklı olup toplam hataların {f:outage-share} kadarını oluşturmaktadır.",
                    "Teknik hataların {f:outage-errors} adedi kesinti kaynaklı olup teknik hataların {f:outage-share} kadarını oluşturmaktadır.",
                    "outage-share"),
            new LineSwap("The number of unmapped errors was {f:unmapped-count}.",
                    "There were {f:unmapped-count} unmapped errors, {f:unmapped-rate} of the technical errors.", "unmapped-rate"),
            new LineSwap("The number of quota errors was {f:quota-count}.",
                    "There were {f:quota-count} quota errors, {f:quota-rate} of the technical errors.", "quota-rate"),
            new LineSwap("{f:outage-errors} technical errors were caused by outages, {f:outage-share} of all errors.",
                    "{f:outage-errors} technical errors were caused by outages, {f:outage-share} of the technical errors.",
                    "outage-share"));

    /** Göçte eklenen yeni oranlar: kimlik → (pay, ad TR, ad EN). Bölen her ikisinde teknik hata adedi (kullanıcı kararı). */
    private record Addition(String id, String num, String labelTr, String labelEn) { }

    private static final List<Addition> ADDITIONS = List.of(
            new Addition("unmapped-rate", "unmapped-count", "Map yapılmamış hataların teknik hatalara oranı",
                    "Share of unmapped errors in technical errors"),
            new Addition("quota-rate", "quota-count", "Kota hatalarının teknik hatalara oranı", "Share of quota errors in technical errors"));

    /**
     * Şablonu dönüştürür; değişecek bir şey yoksa boş. Saf: sorgu yok, girdi değişmez. Tekrar uygulanınca boş döner
     * (hesaplanan alan yeniden dönüştürülmez, var olan kimlik yeniden eklenmez).
     */
    static Optional<Template> migrate(Template t) {
        boolean en = t.en();
        Map<String, Field> byId = new LinkedHashMap<>();
        for (Field f : t.allFields()) byId.put(f.id(), f);
        Map<String, Field> replaced = new HashMap<>();
        Set<String> skipTargets = new java.util.HashSet<>();
        for (Field f : byId.values()) if (f.skipIfNo() != null) skipTargets.add(f.skipIfNo());
        for (Conversion c : CONVERSIONS) {
            Field f = byId.get(c.id());
            if (f == null || f.calc() != null || !c.type().equals(f.type())) continue;
            // Başka alanların "Hayır ise sorma" sorusu olan evet/hayır alanı hesaplanır hâle gelmez (bağ düşerdi)
            if (skipTargets.contains(f.id())) continue;
            if (!c.calc().refs().stream().allMatch(r -> TechErrorCalc.input(byId.get(r)))) continue;
            String hint = f.hint();
            if (c.oldHints().contains(hint == null ? NO_HINT : hint)) hint = en ? c.hintEn() : c.hintTr();
            String label = c.labels().getOrDefault(f.label(), f.label());
            replaced.put(f.id(), new Field(f.id(), label, f.type(), hint, f.skipIfNo(), c.calc()));
        }
        // Yeni oranlar: hedef bölüm = teknik hata oranının bölümü, yoksa payın bölümü
        String rateSection = sectionOf(t, "error-rate");
        Map<String, List<Field>> added = new LinkedHashMap<>();
        for (Addition a : ADDITIONS) {
            if (byId.containsKey(a.id())) continue;
            if (!TechErrorCalc.input(byId.get(a.num())) || !TechErrorCalc.input(byId.get("error-count"))) continue;
            String target = rateSection != null ? rateSection : sectionOf(t, a.num());
            if (target == null) continue;
            added.computeIfAbsent(target, k -> new ArrayList<>()).add(new Field(a.id(), en ? a.labelEn() : a.labelTr(),
                    "percent", null, null, Calc.ratio(a.num(), null, "error-count")));
        }
        // Sığmayan ekleme ÖNCEDEN düşer (sığan kadarı eklenir): özet bölümü gösterge bölümlerinden önce gelir, cümle
        // eklenmemiş orana bağlanmasın
        for (Section s : t.sections()) {
            List<Field> extra = added.get(s.id());
            if (extra == null) continue;
            int room = Math.max(0, TechErrorTemplate.MAX_FIELDS - s.fields().size());
            if (room == 0) added.remove(s.id());
            else if (extra.size() > room) added.put(s.id(), new ArrayList<>(extra.subList(0, room)));
        }
        Set<String> present = new java.util.HashSet<>(byId.keySet());
        for (List<Field> l : added.values()) for (Field f : l) present.add(f.id());

        List<Section> sections = new ArrayList<>(t.sections().size());
        for (Section s : t.sections()) {
            if ("metrics".equals(s.kind())) {
                List<Field> fs = new ArrayList<>();
                boolean touched = false;
                for (Field f : s.fields()) {
                    Field r = replaced.get(f.id());
                    touched |= r != null;
                    fs.add(r != null ? r : f);
                }
                List<Field> extra = added.getOrDefault(s.id(), List.of());
                if (!extra.isEmpty()) {
                    fs.addAll(extra);
                    touched = true;
                }
                if (touched) {
                    // Girilen alanlar üstte, hesaplananlar altta (sıra kendi içinde korunur)
                    List<Field> ordered = new ArrayList<>(fs.size());
                    for (Field f : fs) if (f.calc() == null) ordered.add(f);
                    for (Field f : fs) if (f.calc() != null) ordered.add(f);
                    fs = ordered;
                }
                sections.add(s.withFields(List.copyOf(fs)));     // gömülü bölümün özet bağı (host) korunur
            } else if ("summary".equals(s.kind())) {
                List<String> lines = new ArrayList<>(s.lines().size());
                for (String line : s.lines()) {
                    String out = line;
                    for (LineSwap w : LINES) {
                        if (w.oldLine().equals(line) && present.contains(w.needs()) && computed(replaced, added, byId, w.needs())) {
                            out = w.newLine();
                        }
                    }
                    lines.add(out);
                }
                sections.add(s.withLines(List.copyOf(lines)));
            } else if ("trend".equals(s.kind()) && DEFAULT_TREND_ID.equals(s.id())) {
                // varsayılan otomatik trend bölümü düşer (yalnız tek bölüm değilse — şablon boş kalmasın)
                if (t.sections().size() == 1) sections.add(s);
            } else {
                sections.add(s);
            }
        }
        Template next = new Template(t.lang(), t.title(), List.copyOf(replaceDefaultCharts(sections)));
        String json = TechErrorTemplate.toJson(next);
        if (json.equals(TechErrorTemplate.toJson(t))) return Optional.empty();
        if (json.length() > TechErrorTemplate.MAX_JSON_CHARS) {
            log.warn("Teknik hata raporu oran göçü: şablon 64 KB sınırını aşacağı için dönüştürülmedi ({} bölüm)", t.sections().size());
            return Optional.empty();
        }
        return Optional.of(next);
    }

    // ── Grafik bölümleri ─────────────────────────────────────────────────────────────────────────────

    /** Eski varsayılanın grafik bölümleri (dil + bölümler) — ŞABLONDA birebir böyleyse yeni iki bölümle değişir. */
    private record OldCharts(boolean en, List<Section> sections) { }

    private static Section img(String id, String title, String... idLabelWidth) {
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i + 2 < idLabelWidth.length; i += 3) slots.add(new Slot(idLabelWidth[i], idLabelWidth[i + 1], idLabelWidth[i + 2]));
        return new Section(id, "images", title, List.of(), List.copyOf(slots), null, null, null, null, null);
    }

    /** 2026-10-13 – 2026-10-14 varsayılanı: tek "Grafikler" bölümü, 14 yuva. */
    private static final OldCharts V1_TR = new OldCharts(false, List.of(img("charts", "Grafikler",
            "monthly-tx", "{kanal} Kanalı Ay Bazlı Toplam İşlem Adetleri", "half",
            "tech-errors", "{kanal} Kanalı Teknik Hata Grafiği", "half",
            "unmapped-monthly", "{kanal} Kanalı Maplenmemiş Hata Grafiği (Aylık)", "half",
            "quota-monthly", "Kota Hata Grafiği (Aylık)", "half",
            "daily-errors", "{kanal} Kanalı Teknik Hata (Günlük)", "full",
            "summary-monthly", "{kanal} Kanalı Özet (Aylık)", "full",
            "month-tech-counts", "{ay} {yil} Teknik Hata Adetleri", "full",
            "month-business-counts", "{ay} {yil} Business Hata Adetleri", "full",
            "quota-unmapped-monthly", "Kota ve Maplenmemiş Hata Adetleri (Ay Bazlı)", "full",
            "daily-tech-business", "Gün Bazlı Teknik ve Business Hata Adetleri", "full",
            "month-quota-counts", "{ay} {yil} Kota Hata Adetleri", "full",
            "month-unmapped-daily", "{ay} {yil} Maplenmemiş Hata Adetleri (Gün Bazlı)", "full",
            "tech-ratio", "{ay} {yil} Teknik Hataların Tüm Operasyonlara Oranı", "full",
            "business-ratio", "{ay} {yil} Business Hataların Tüm Operasyonlara Oranı", "full")));

    private static final OldCharts V1_EN = new OldCharts(true, List.of(img("charts", "Charts",
            "monthly-tx", "{channel} Channel Monthly Total Transactions", "half",
            "tech-errors", "{channel} Channel Technical Errors", "half",
            "unmapped-monthly", "{channel} Channel Unmapped Errors (Monthly)", "half",
            "quota-monthly", "Quota Errors (Monthly)", "half",
            "daily-errors", "{channel} Channel Technical Errors (Daily)", "full",
            "summary-monthly", "{channel} Channel Summary (Monthly)", "full",
            "month-tech-counts", "{month} {year} Technical Error Counts", "full",
            "month-business-counts", "{month} {year} Business Error Counts", "full",
            "quota-unmapped-monthly", "Quota and Unmapped Error Counts (Monthly)", "full",
            "daily-tech-business", "Daily Technical and Business Error Counts", "full",
            "month-quota-counts", "{month} {year} Quota Error Counts", "full",
            "month-unmapped-daily", "{month} {year} Unmapped Error Counts (Daily)", "full",
            "tech-ratio", "{month} {year} Technical Errors as a Share of All Operations", "full",
            "business-ratio", "{month} {year} Business Errors as a Share of All Operations", "full")));

    /** 2026-10-15 varsayılanı: 12 aylık özetler → ay bazlı → birim dağılımı → gün bazlı (22 yuva). */
    private static final OldCharts V2_TR = new OldCharts(false, List.of(
            img("charts", "12 Aylık Özetler",
                    "annual-summary", "Yıllık Operasyon ve Hata Dağılım Özeti (Son 12 Ay)", "half",
                    "annual-tech-rate", "Yıllık Teknik Hata Oranı Trendi (Son 12 Ay)", "half",
                    "annual-table", "Son 12 Ay Operasyon ve Hata Özet Tablosu", "full"),
            img("charts-monthly", "Ay Bazlı Hata ve İşlem Adetleri (Son 12 Ay)",
                    "monthly-tx", "Ay Bazlı Toplam İşlem Adetleri (Son 12 Ay)", "half",
                    "monthly-failed", "Ay Bazlı Toplam Hata (Başarısız İşlem) Adetleri (Son 12 Ay)", "half",
                    "tech-errors", "Ay Bazlı Teknik Hata Adetleri (Son 12 Ay)", "half",
                    "business-monthly", "Ay Bazlı Business Hata Adetleri (Son 12 Ay)", "half",
                    "unmapped-monthly", "Ay Bazlı Maplenmemiş Hata Adetleri (Son 12 Ay)", "half",
                    "quota-monthly", "Ay Bazlı Kota Hata Adetleri (Son 12 Ay)", "half",
                    "business-tech-monthly", "Ay Bazlı Business ve Teknik Hata Adetleri (Son 12 Ay)", "full",
                    "quota-unmapped-monthly", "Ay Bazlı Kota ve Maplenmemiş Hata Adetleri (Son 12 Ay)", "full"),
            img("charts-distribution", "{ay} {yil} Birim Bazlı Hata Dağılımı",
                    "month-tech-counts", "{ay} {yil} Teknik Hata Dağılımı (Birim Bazlı)", "half",
                    "month-business-counts", "{ay} {yil} Business Hata Dağılımı (Birim Bazlı)", "half"),
            img("charts-daily", "Gün Bazlı Hata ve İşlem Adetleri ({ay} {yil})",
                    "daily-tx", "Gün Bazlı Toplam İşlem Adetleri ({ay} {yil})", "half",
                    "daily-failed", "Gün Bazlı Toplam Hata (Başarısız İşlem) Adetleri ({ay} {yil})", "half",
                    "daily-errors", "Gün Bazlı Teknik Hata Adetleri ({ay} {yil})", "half",
                    "daily-business", "Gün Bazlı Business Hata Adetleri ({ay} {yil})", "half",
                    "month-unmapped-daily", "Gün Bazlı Maplenmemiş Hata Adetleri ({ay} {yil})", "half",
                    "month-quota-counts", "Gün Bazlı Kota Hata Adetleri ({ay} {yil})", "half",
                    "daily-tech-business", "Gün Bazlı Business ve Teknik Hata Adetleri ({ay} {yil})", "full",
                    "tech-ratio", "Gün Bazlı Teknik Hataların Tüm Operasyonlara Oranı ({ay} {yil})", "full",
                    "business-ratio", "Gün Bazlı Business Hataların Tüm Operasyonlara Oranı ({ay} {yil})", "full")));

    private static final OldCharts V2_EN = new OldCharts(true, List.of(
            img("charts", "12-Month Summaries",
                    "annual-summary", "Annual Operations and Error Distribution Summary (Last 12 Months)", "half",
                    "annual-tech-rate", "Annual Technical Error Rate Trend (Last 12 Months)", "half",
                    "annual-table", "Last 12 Months Operations and Error Summary Table", "full"),
            img("charts-monthly", "Monthly Errors and Transactions (Last 12 Months)",
                    "monthly-tx", "Monthly Total Transactions (Last 12 Months)", "half",
                    "monthly-failed", "Monthly Total Errors (Failed Transactions) (Last 12 Months)", "half",
                    "tech-errors", "Monthly Technical Errors (Last 12 Months)", "half",
                    "business-monthly", "Monthly Business Errors (Last 12 Months)", "half",
                    "unmapped-monthly", "Monthly Unmapped Errors (Last 12 Months)", "half",
                    "quota-monthly", "Monthly Quota Errors (Last 12 Months)", "half",
                    "business-tech-monthly", "Monthly Business and Technical Errors (Last 12 Months)", "full",
                    "quota-unmapped-monthly", "Monthly Quota and Unmapped Errors (Last 12 Months)", "full"),
            img("charts-distribution", "{month} {year} Error Distribution by Unit",
                    "month-tech-counts", "{month} {year} Technical Error Distribution (by Unit)", "half",
                    "month-business-counts", "{month} {year} Business Error Distribution (by Unit)", "half"),
            img("charts-daily", "Daily Errors and Transactions ({month} {year})",
                    "daily-tx", "Daily Total Transactions ({month} {year})", "half",
                    "daily-failed", "Daily Total Errors (Failed Transactions) ({month} {year})", "half",
                    "daily-errors", "Daily Technical Errors ({month} {year})", "half",
                    "daily-business", "Daily Business Errors ({month} {year})", "half",
                    "month-unmapped-daily", "Daily Unmapped Errors ({month} {year})", "half",
                    "month-quota-counts", "Daily Quota Errors ({month} {year})", "half",
                    "daily-tech-business", "Daily Business and Technical Errors ({month} {year})", "full",
                    "tech-ratio", "Daily Technical Errors as a Share of All Operations ({month} {year})", "full",
                    "business-ratio", "Daily Business Errors as a Share of All Operations ({month} {year})", "full")));

    private static final List<OldCharts> OLD_CHARTS = List.of(V2_TR, V2_EN, V1_TR, V1_EN);

    /** Eski varsayılanın grafik bölümleri (sürüm 1: ilk varsayılan, 2: 2026-10-15 akışı) — testler eski şablonu kurar. */
    static List<Section> oldDefaultCharts(int version, boolean en) {
        return (version == 1 ? (en ? V1_EN : V1_TR) : (en ? V2_EN : V2_TR)).sections();
    }

    /**
     * Eski varsayılan grafik bölümleri ŞABLONDA birebir varsa (kimlik, tür, başlık, yuvalar — bölümlerin yeri önemsiz) ilkinin
     * yerine yeni iki bölümü koyar, diğerlerini kaldırır. Eşleşme yok, kimlik çakışıyor ya da sınır aşılıyorsa aynı liste döner
     * (kanala dokunulmaz). Tekrar uygulanınca değişiklik üretmez (yeni bölümler hiçbir eski kümeyle eşleşmez).
     */
    static List<Section> replaceDefaultCharts(List<Section> sections) {
        Map<String, Section> byId = new HashMap<>();
        for (Section s : sections) byId.put(s.id(), s);
        for (OldCharts old : OLD_CHARTS) {
            boolean all = true;
            for (Section o : old.sections()) {
                Section s = byId.get(o.id());
                if (s == null || !"images".equals(s.kind()) || !o.title().equals(s.title()) || !o.slots().equals(s.slots())) {
                    all = false;
                    break;
                }
            }
            if (!all) continue;
            Set<String> oldIds = new HashSet<>();
            for (Section o : old.sections()) oldIds.add(o.id());
            List<Section> out = new ArrayList<>(sections.size() + 1);
            boolean inserted = false;
            for (Section s : sections) {
                if (!oldIds.contains(s.id())) {
                    out.add(s);
                } else if (!inserted) {
                    out.addAll(TechErrorTemplate.defaultCharts(old.en()));
                    inserted = true;
                }
            }
            return fits(out) ? out : sections;
        }
        return sections;
    }

    /** Bölüm ve yuva kimlikleri tekil, bölüm / yuva sayısı sınırların içinde mi (katı doğrulama ne isterse). */
    private static boolean fits(List<Section> sections) {
        if (sections.size() > TechErrorTemplate.MAX_SECTIONS) return false;
        Set<String> sectionIds = new HashSet<>();
        Set<String> slotIds = new LinkedHashSet<>();
        int slots = 0;
        for (Section s : sections) {
            if (!sectionIds.add(s.id())) return false;
            for (Slot sl : s.slots()) {
                slots++;
                if (!slotIds.add(sl.id())) return false;
            }
        }
        return slots <= TechErrorTemplate.MAX_SLOTS;
    }

    /** Cümlenin dayandığı oran alanı göçten sonra hesaplanıyor mu (dönüştürüldü, eklendi ya da zaten hesaplanıyordu). */
    private static boolean computed(Map<String, Field> replaced, Map<String, List<Field>> added, Map<String, Field> byId, String id) {
        if (replaced.containsKey(id)) return true;
        for (List<Field> l : added.values()) for (Field f : l) if (f.id().equals(id)) return true;
        Field f = byId.get(id);
        return f != null && f.calc() != null;
    }

    private static String sectionOf(Template t, String fieldId) {
        for (Section s : t.sections()) {
            if (!"metrics".equals(s.kind())) continue;
            for (Field f : s.fields()) if (f.id().equals(fieldId)) return s.id();
        }
        return null;
    }
}
