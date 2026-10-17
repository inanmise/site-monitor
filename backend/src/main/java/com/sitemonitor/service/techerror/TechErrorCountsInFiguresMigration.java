package com.sitemonitor.service.techerror;

import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.StandaloneMonitorDeletionBackfill;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEK SEFERLİK şablon göçü (2026-10-17, 7. sürüm; kullanıcı kararı: "Yönetici Özeti sayfasındaki kota ve map hataları
 * kısımlarını çıkarıp X Kanalı — Eylül 2026 Verileri kısmında bu değerlerin girişini yapalım"). MEVCUT kanal şablonlarında
 * yönetici özeti kartına GÖMÜLÜ ({@code host}) {@code unmapped} / {@code quota} gösterge bölümleri 4a varsayılanıyla birebir
 * aynıysa (başlık TR / EN, adet "number" + açıklama "textarea" varsayılan adlarıyla; ipucu, bağ, hesap, hedef yok) bölüm
 * ÇÖZÜLÜR: adet ve açıklama alanları aynı kimlik ve türle "Verileri" ({@code summary}) gösterge bölümüne, teknik hata
 * adedinin hemen ardına taşınır (önce map, sonra kota), bölüm silinir. Alan kimlikleri değişmediği için özet cümleleri,
 * kutucuklar, oranlar, trend, geçmiş ay ipuçları ve girilmiş değerler ("şablonun son hâlini uygula" {@code carry}) sürer.
 * Dokunulmaz: ekibin değiştirdiği bölüm (başlık, alan adı, ek alan, ipucu, bağ, hesap), ekibin "Ayrı bölüm olarak"
 * ayırdığı bölüm ({@code host} yok), {@code summary} kimlikli ayrı (gömülü olmayan) gösterge bölümü olmayan şablon, 30 alan
 * sınırını aşacak Verileri bölümü. Gömme özelliği ({@code host}) duruyor — isteyen ekip şablon düzenleyiciden yeniden gömebilir.
 * Açılmış / gönderilmiş raporlar DEĞİŞMEZ (şablonun kopyasını taşırlar). SLA / kutucuk göçü ({@link TechErrorSlaTilesMigration})
 * tamamlanmadan koşmaz (zincir: oran → özet gömme → SLA → bu). Açılış yolunda değil: açılıştan 10 dk sonra (yayılım penceresi —
 * ESKİ bir pod bu arada 4a düzeniyle kanal açabilir; nişan yazıldıktan sonra açılan böyle bir kanal gömülü kalır, ekip
 * "varsayılana dön" ya da düzenleyiciden taşır; inceleme 2026-10-17), kendi kilidi altında; nişan kilit İÇİNDE yeniden okunur ve yalnız TÜM kanallar işlendiyse yazılır (yarıda kalırsa bir saat sonra kalan
 * kanallar yeniden denenir — dönüşüm tekrar uygulanınca değişiklik üretmez). Her kanal {@code saveTemplate} ile yazılır:
 * koşullu güncelleme (ekibin aynı andaki kaydı kaybolmaz) + sürüm geçmişinde "SiteMonitor" adıyla yeni sürüm. Eski bir pod
 * bu düzeni olduğu gibi gösterir (düz gösterge alanları) — veri kaybı yok.
 */
@Slf4j
@Service
public class TechErrorCountsInFiguresMigration {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ (değişirse ekibin bu arada yeniden gömdüğü bölümler yine çözülür). */
    static final String KEY = "2026-10-17-tech-error-counts-in-figures";
    static final String ACTOR = "SiteMonitor";
    static final String LOCK = "tech-error-counts-in-figures-migration";
    static final int MAX_ATTEMPTS = 24;
    /** Taşınan alanlar Verileri'nde bu alanın ARDINA girer; yoksa sona. */
    static final String ANCHOR_FIELD = "error-count";
    static final String TARGET_SECTION = "summary";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TechErrorChannelRepository channels;
    private final TechErrorReportService service;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    private final AtomicInteger failures = new AtomicInteger();

    public TechErrorCountsInFiguresMigration(TechErrorChannelRepository channels, TechErrorReportService service, JdbcTemplate jdbc,
                                             ObjectProvider<SchedulerService> scheduler) {
        this.channels = channels;
        this.service = service;
        this.jdbc = jdbc;
        this.scheduler = scheduler;
    }

    @Scheduled(initialDelayString = "${site.monitor.tech-error.counts-in-figures-migration.initial-delay-ms:600000}", fixedDelay = 3_600_000L)
    public void run() {
        if (failures.get() >= MAX_ATTEMPTS) return;
        // Sıra: önce SLA / kutucuk göçü (4b) — nişanı yoksa ya da okunamıyorsa bu tur beklenir
        if (!Boolean.TRUE.equals(marker(TechErrorSlaTilesMigration.KEY))) return;
        if (done()) return;
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null) return;
        try {
            if (s.callWithSchedulerLock(LOCK, this::migrateLocked).isPresent()) failures.set(0);
        } catch (RuntimeException e) {
            int n = failures.incrementAndGet();
            if (n >= MAX_ATTEMPTS) {
                log.error("Teknik hata raporu adet göçü {} denemede tamamlanamadı — bu pod denemeyi bırakıyor (nişan yazılmadı)", n, e);
            } else {
                log.warn("Teknik hata raporu adet göçü başarısız (bir saat sonra yeniden denenecek): {}", e.toString());
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
            log.debug("Adet göçü nişanı okunamadı ({}): {}", key, e.getMessage());
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
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                KEY, ISO.format(Instant.now()), changed);
        log.info("Teknik hata raporu adet göçü tamamlandı: {} kanal şablonu güncellendi", changed);
        return changed;
    }

    // ── Saf dönüşüm ──────────────────────────────────────────────────────────────────────────────────

    /** Çözülecek gömülü bölüm: kimlik, 4a başlıkları (TR / EN), adet ve açıklama alanlarının kimlik + varsayılan adları. */
    record Move(String id, Set<String> titles, String countId, Set<String> countLabels, String noteId, Set<String> noteLabels) {}

    static final List<Move> MOVES = List.of(
            new Move("unmapped", Set.of("Map Yapılmamış Hatalar", "Unmapped Errors"),
                    "unmapped-count", Set.of("Map yapılmamış hata adedi", "Unmapped errors"),
                    "unmapped-note", Set.of("Map yapılmamış hatalar hakkında açıklama", "About the unmapped errors")),
            new Move("quota", Set.of("Kota Hataları", "Quota Errors"),
                    "quota-count", Set.of("Kota hatası adedi", "Quota errors"),
                    "quota-note", Set.of("Kota hataları hakkında açıklama", "About the quota errors")));

    /**
     * Şablonu dönüştürür; değişecek bir şey yoksa boş. Saf: sorgu yok, girdi değişmez. Tekrar uygulanınca boş döner
     * (çözülen bölüm artık yok; ekibin yeniden gömdüğü bölüm yalnız 4a şekliyle birebir aynıysa çözülür — nişan bunu önler).
     */
    static Optional<Template> migrate(Template t) {
        List<Section> sections = new ArrayList<>(t.sections());
        int cursor = -1;                                                    // Verileri alan listesinde ekleme yeri
        for (Move m : MOVES) {
            int at = indexOf(sections, m.id());
            if (at < 0) continue;
            Section s = sections.get(at);
            if (!"metrics".equals(s.kind()) || s.host() == null || !m.titles().contains(s.title())) continue;
            List<Field> fs = s.fields();
            if (fs.size() != 2 || !m.countId().equals(fs.get(0).id()) || !plain(fs.get(0), "number", m.countLabels())
                    || !m.noteId().equals(fs.get(1).id()) || !plain(fs.get(1), "textarea", m.noteLabels())) continue;
            int target = indexOf(sections, TARGET_SECTION);
            // Hedef: ayrı (gömülü olmayan) bir gösterge bölümü — ekibin özete gömdüğü bir Verileri bölümüne taşınmaz (inceleme 2026-10-17)
            if (target < 0 || target == at || !"metrics".equals(sections.get(target).kind()) || sections.get(target).host() != null) continue;
            Section dst = sections.get(target);
            if (dst.fields().size() + 2 > TechErrorTemplate.MAX_FIELDS) {
                log.warn("Teknik hata raporu adet göçü: '{}' bölümü Verileri'ne taşınamadı — {} alan sınırı aşılırdı", m.id(),
                        TechErrorTemplate.MAX_FIELDS);
                continue;
            }
            List<Field> out = new ArrayList<>(dst.fields());
            if (cursor < 0) {
                cursor = out.size();
                for (int i = 0; i < out.size(); i++) if (ANCHOR_FIELD.equals(out.get(i).id())) cursor = i + 1;
            }
            out.add(cursor++, fs.get(0));
            out.add(cursor++, fs.get(1));
            sections.set(target, dst.withFields(List.copyOf(out)));
            sections.remove(at);
        }
        Template next = new Template(t.lang(), t.title(), List.copyOf(sections));
        String json = TechErrorTemplate.toJson(next);
        if (json.equals(TechErrorTemplate.toJson(t))) return Optional.empty();
        if (json.length() > TechErrorTemplate.MAX_JSON_CHARS) {
            log.warn("Teknik hata raporu adet göçü: şablon 64 KB sınırını aşacağı için dönüştürülmedi ({} bölüm)", t.sections().size());
            return Optional.empty();
        }
        return Optional.of(next);
    }

    /** Alan 4a varsayılanının "girilen" alanı mı: ad kümede, tür beklenen; ipucu, "Hayır ise sorma" bağı, hesap, hedef, yön yok. */
    private static boolean plain(Field f, String type, Set<String> labels) {
        return f != null && type.equals(f.type()) && labels.contains(f.label()) && f.hint() == null && f.skipIfNo() == null
                && f.calc() == null && f.target() == null && f.better() == null;
    }

    private static int indexOf(List<Section> sections, String id) {
        for (int i = 0; i < sections.size(); i++) if (id.equals(sections.get(i).id())) return i;
        return -1;
    }
}
