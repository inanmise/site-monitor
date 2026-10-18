package com.sitemonitor.service.techerror;

import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorTeamTemplate;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorTeamTemplateRepository;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.StandaloneMonitorDeletionBackfill;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.IssueOptions;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEK SEFERLİK şablon göçü (2026-10-18, kullanıcı isteği: "anomali konularında ne zaman, kaç teknik hata ve payı, kesinti
 * kaynaklı mı + Kesinti ID, müşteri etkisi var mı, görseller sorulsun"). MEVCUT kanal şablonlarındaki anomali bölümü 2026-10-17
 * VARSAYILANIYLA birebir aynıysa (başlık kalıbı + açıklama, müşteri ve hata adedi etiketleri TR ya da EN takımı; bölüm başlığı
 * önemsiz) ve henüz hiçbir soru açık değilse, bölüme bugünkü varsayılan sorular eklenir ({@link IssueOptions#defaults}: ne zaman,
 * kesinti, müşteri etkisi — yoksa müşteri sayısı gizlenir, görseller; pay "Ay içindeki teknik hata adedi"ne göre — o alan
 * şablonda SAYI alanı değilse pay sorulmaz). Ekibin değiştirdiği bölüme (başka etiket / kalıp, ya da bir soru açılmış)
 * dokunulmaz. Açılmış / gönderilmiş raporlar DEĞİŞMEZ (şablonun kopyasını taşırlar; taslak "şablonun son hâlini uygula" ile
 * soruları alır, eski konu değerleri {@code carry} ile kalır). Adet göçü ({@link TechErrorCountsInFiguresMigration})
 * tamamlanmadan koşmaz (zincir: oran → özet gömme → SLA → adetler → bu). Açılış yolunda değil: açılıştan 12 dk sonra
 * (yayılım penceresi), kendi kilidi altında; nişan kilit İÇİNDE yeniden okunur ve yalnız TÜM kanallar işlendiyse yazılır
 * (yarıda kalırsa bir saat sonra kalan kanallar yeniden denenir — dönüşüm tekrar uygulanınca değişiklik üretmez); pod başına
 * en çok {@link #MAX_ATTEMPTS} başarısız deneme. Her kanal {@code saveTemplate} ile yazılır: koşullu güncelleme (ekibin aynı
 * andaki kaydı kaybolmaz, ≤ 3 yeniden deneme) + sürüm geçmişinde "SiteMonitor" adıyla yeni sürüm. ESKİ bir pod soruların
 * anahtarlarını tanımaz (yok sayar): şablonu o pod kaydederse sorular düşer.
 *
 * <p>Takım ŞABLON KÜTÜPHANESİ kayıtları (R21) da aynı kuralla göçer ({@link #migrateLibrary}, inceleme 2026-10-18): yeni kanal
 * penceresi takım varsayılanını seçer — göçmeyen kayıttan açılan kanal soruları sessizce almazdı. "Hiç ayarlanmamış" bölüm =
 * anomali anahtarlarından HİÇBİRİ yazılmamış ({@code Section.options == null}); ekibin bütün soruları kapattığı bölüm
 * ayarlanmış sayılır (anahtarlar {@code false} yazılır) ve göç onu yeniden AÇMAZ. Nişan yazıldıktan SONRA eski bir pod'da
 * açılan kanal ya da kaydedilen kütüphane şablonu göçmez (tek geçiş): "şablonun son hâlini uygula" / şablon düzenleyicisi
 * ("Varsayılana dön" ya da anomali anahtarları) düzeltir.
 */
@Slf4j
@Service
public class TechErrorAnomalyFieldsMigration {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ (değişirse ekibin bu arada kapattığı sorular yeniden açılır). */
    static final String KEY = "2026-10-18-tech-error-anomaly-fields";
    static final String ACTOR = "SiteMonitor";
    static final String LOCK = "tech-error-anomaly-fields-migration";
    static final int MAX_ATTEMPTS = 24;

    /** 2026-10-17 varsayılanının anomali etiketleri: başlık kalıbı, açıklama, müşteri, hata adedi (TR ve EN takımı). */
    record Labels(String item, String description, String customers, String errors, boolean en) {}

    static final List<Labels> DEFAULT_LABELS = List.of(
            new Labels("{konu}: Hata Artışı", "Ne oldu? (zaman, yer, etki)", "Etkilenen müşteri sayısı",
                    "Bu konudan kaynaklı teknik hata adedi", false),
            new Labels("{topic}: Error Increase", "What happened? (when, where, impact)", "Affected customers",
                    "Technical errors caused by this topic", true));

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TechErrorChannelRepository channels;
    private final TechErrorReportService service;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    /** Takım şablon kütüphanesi (R21) — yeni kanal penceresi takım varsayılanını seçer; null = kütüphane yok (birim testi). */
    private final TechErrorTeamTemplateRepository library;
    private final AtomicInteger failures = new AtomicInteger();

    /** Kütüphanesiz (birim testleri): yalnız kanallar göçer. */
    public TechErrorAnomalyFieldsMigration(TechErrorChannelRepository channels, TechErrorReportService service, JdbcTemplate jdbc,
                                           ObjectProvider<SchedulerService> scheduler) {
        this(channels, service, jdbc, scheduler, null);
    }

    @Autowired
    public TechErrorAnomalyFieldsMigration(TechErrorChannelRepository channels, TechErrorReportService service, JdbcTemplate jdbc,
                                           ObjectProvider<SchedulerService> scheduler, TechErrorTeamTemplateRepository library) {
        this.channels = channels;
        this.service = service;
        this.jdbc = jdbc;
        this.scheduler = scheduler;
        this.library = library;
    }

    @Scheduled(initialDelayString = "${site.monitor.tech-error.anomaly-fields-migration.initial-delay-ms:720000}", fixedDelay = 3_600_000L)
    public void run() {
        if (failures.get() >= MAX_ATTEMPTS) return;
        // Sıra: önce adet göçü (7. sürüm) — nişanı yoksa ya da okunamıyorsa bu tur beklenir
        if (!Boolean.TRUE.equals(marker(TechErrorCountsInFiguresMigration.KEY))) return;
        if (done()) return;
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null) return;
        try {
            if (s.callWithSchedulerLock(LOCK, this::migrateLocked).isPresent()) failures.set(0);
        } catch (RuntimeException e) {
            int n = failures.incrementAndGet();
            if (n >= MAX_ATTEMPTS) {
                log.error("Teknik hata raporu anomali soruları göçü {} denemede tamamlanamadı — bu pod denemeyi bırakıyor (nişan yazılmadı)", n, e);
            } else {
                log.warn("Teknik hata raporu anomali soruları göçü başarısız (bir saat sonra yeniden denenecek): {}", e.toString());
            }
        }
    }

    /** Nişan var mı: true / false; okunamazsa null. */
    Boolean marker(String key) {
        try {
            jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class, key);
            return n != null && n > 0;
        } catch (RuntimeException e) {
            log.debug("Anomali soruları göçü nişanı okunamadı ({}): {}", key, e.getMessage());
            return null;
        }
    }

    boolean done() {
        return !Boolean.FALSE.equals(marker(KEY));
    }

    Integer migrateLocked() {
        if (done()) return 0;
        int changed = 0;
        for (TechErrorChannel ch : channels.findAll()) {
            for (int attempt = 1; ; attempt++) {
                TechErrorChannel cur = channels.findById(ch.getId()).orElse(null);
                if (cur == null) break;
                Optional<Template> next = migrate(TechErrorTemplate.fromStored(cur.getTemplateJson()));
                if (next.isEmpty()) break;
                try {
                    service.saveTemplate(cur.getId(), cur.getTemplateVersion(), next.get(), null, ACTOR);
                    changed++;
                    break;
                } catch (java.util.NoSuchElementException e) {
                    break;                                                // okuma ile kayıt arasında silindi
                } catch (OptimisticLockingFailureException e) {
                    if (attempt >= 3) throw e;
                }
            }
        }
        int libraryChanged = migrateLibrary();
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                KEY, ISO.format(Instant.now()), changed + libraryChanged);
        log.info("Teknik hata raporu anomali soruları göçü tamamlandı: {} kanal şablonu, {} kütüphane şablonu güncellendi",
                changed, libraryChanged);
        return changed + libraryChanged;
    }

    /**
     * Takım şablon kütüphanesi (R21, inceleme 2026-10-18): yeni kanal penceresi takım varsayılanını ilk kaynak olarak seçer —
     * kütüphanedeki eski anomali bölümü göçmezse yeni kanallar soruları sessizce almazdı. Kanallarla AYNI kural ({@link #migrate}:
     * yalnız 2026-10-17 varsayılan etiketli, hiç ayarlanmamış bölüm). Okunamayan kayıt atlanır (sistem şablonuna düşülmez —
     * {@link TechErrorTemplate#readStored}). Yazım KOŞULLU ({@code migrateTemplate}: okunan sürümle, sürüm +1, "son güncelleyen"
     * {@value #ACTOR}); arada ekip kaydettiyse yeniden okunur (≤ 3), sonra hata (nişan yazılmaz, bir saat sonra yeniden).
     * Denetim satırı yazılmaz (kanal göçleri gibi tek özet log satırı).
     */
    int migrateLibrary() {
        if (library == null) return 0;
        int changed = 0;
        for (TechErrorTeamTemplateRepository.MigrationRow row : library.findMigrationRows()) {
            for (int attempt = 1; ; attempt++) {
                TechErrorTeamTemplate cur = library.findById(row.getId()).orElse(null);
                if (cur == null) break;                                   // okuma ile yazım arasında silindi
                Optional<Template> stored = TechErrorTemplate.readStored(cur.getTemplateJson());
                if (stored.isEmpty()) {
                    log.warn("Teknik hata raporu anomali soruları göçü: kütüphane şablonu #{} okunamadı, atlandı", cur.getId());
                    break;
                }
                Optional<Template> next = migrate(stored.get());
                if (next.isEmpty()) break;
                if (library.migrateTemplate(cur.getId(), cur.getVersion(), TechErrorTemplate.toJson(next.get()),
                        Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), ACTOR) > 0) {
                    changed++;
                    break;
                }
                if (attempt >= 3) throw new OptimisticLockingFailureException("tech_error_team_template migration race #" + cur.getId());
            }
        }
        return changed;
    }

    // ── Saf dönüşüm ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Şablonu dönüştürür; değişecek bir şey yoksa boş. Saf: sorgu yok, girdi değişmez. Tekrar uygulanınca boş döner (soruları
     * açılmış bölüm artık "sorusuz" değil).
     */
    static Optional<Template> migrate(Template t) {
        boolean total = false;
        for (Field f : t.allFields()) if (TechErrorTemplate.DEFAULT_RATIO_OF.equals(f.id()) && "number".equals(f.type())) total = true;
        List<Section> out = new ArrayList<>(t.sections().size());
        boolean changed = false;
        for (Section s : t.sections()) {
            Labels l = "issues".equals(s.kind()) && s.options() == null ? defaultLabels(s) : null;
            if (l == null) {
                out.add(s);
                continue;
            }
            IssueOptions d = IssueOptions.defaults(l.en());
            out.add(s.withOptions(total ? d : new IssueOptions(d.askTime(), d.timeLabel(), d.askOutage(), d.outageLabel(),
                    d.outageIdLabel(), d.askImpact(), d.impactLabel(), d.noImpactDisplay(), d.allowImages(), null)));
            changed = true;
        }
        if (!changed) return Optional.empty();
        Template next = new Template(t.lang(), t.title(), List.copyOf(out));
        if (TechErrorTemplate.toJson(next).length() > TechErrorTemplate.MAX_JSON_CHARS) {
            log.warn("Teknik hata raporu anomali soruları göçü: şablon 64 KB sınırını aşacağı için dönüştürülmedi ({} bölüm)",
                    t.sections().size());
            return Optional.empty();
        }
        return Optional.of(next);
    }

    /** Bölümün etiketleri 2026-10-17 varsayılanının (TR ya da EN) takımıyla birebir aynıysa o takım; değilse {@code null}. */
    private static Labels defaultLabels(Section s) {
        for (Labels l : DEFAULT_LABELS) {
            if (l.item().equals(s.itemTitle()) && l.description().equals(s.descriptionLabel())
                    && l.customers().equals(s.customersLabel()) && l.errors().equals(s.errorsLabel())) return l;
        }
        return null;
    }
}
