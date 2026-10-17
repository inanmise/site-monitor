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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEK SEFERLİK şablon göçü (2026-10-17, kullanıcı kararları: "map yapılmamış ve kota hatalarının girişi yönetici özeti
 * bölümünde olsun, ayrı bölüm olmasın" ve "anomali konularının isimlendirmesi daha iyi olsun"). MEVCUT kanal şablonlarında:
 * <ul>
 *   <li>{@code unmapped} / {@code quota} gösterge bölümleri ESKİ VARSAYILANLA birebir aynıysa — iki üretim şekli: adetler
 *       "Verileri" bölümünde + yalnız açıklamalı bölümler (20.138) ya da adet + açıklama bölümleri (öncesi); TR ve EN —
 *       yönetici özetinin kartına GÖMÜLÜR ({@code host}): adet bölüme taşınır, açıklamanın adı yenilenir, bölüm özetin hemen
 *       ardına alınır. Ev sahibi: adede başvuran ilk özet bölümü, yoksa ilk özet; özet yoksa bölüme dokunulmaz. Ekibin
 *       değiştirdiği bölüm (başlık, alan adı, ipucu, bağ, hesap, alan sayısı) olduğu gibi kalır.</li>
 *   <li>Anomali bölümlerinin başlığı, konu başlığı kalıbı ve alan adları NİTELİK NİTELİK, yalnız eski varsayılan değerse
 *       yenisiyle değişir ("Anormal Artış Gösteren Hatalar", "{konu}: Hata Artışı", "Ne oldu? (zaman, yer, etki)",
 *       "Bu konudan kaynaklı teknik hata adedi"); ekibin yazdığı ad korunur.</li>
 * </ul>
 * Açılmış / gönderilmiş raporlar DEĞİŞMEZ (şablonun kopyasını taşırlar; taslak "şablonun son hâlini uygula" ile alır).
 * Oran göçü ({@link TechErrorCalcMigration}) tamamlanmadan koşmaz (nişanı yoksa bu tur beklenir). Açılış yolunda değil:
 * açılıştan 3 dk sonra, kendi kilidi altında; nişan kilit İÇİNDE yeniden okunur ve yalnız TÜM kanallar işlendiyse yazılır
 * (yarıda kalırsa bir saat sonra kalan kanallar yeniden denenir — dönüşüm tekrar uygulanınca değişiklik üretmez). Her kanal
 * {@code saveTemplate} ile yazılır: koşullu güncelleme (ekibin aynı andaki kaydı kaybolmaz) + sürüm geçmişinde "SiteMonitor"
 * adıyla yeni sürüm. Eski bir pod {@code host} anahtarını bilmez: bölüm ayrı bölüm olarak görünür, veri kaybolmaz (alanlar
 * bu yüzden gösterge bölümünde kalır).
 */
@Slf4j
@Service
public class TechErrorSummaryEmbedMigration {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ (değişirse ekibin bu arada ayırdığı bölümler yeniden gömülür). */
    static final String KEY = "2026-10-17-tech-error-summary-embed";
    static final String ACTOR = "SiteMonitor";
    static final String LOCK = "tech-error-summary-embed-migration";
    static final int MAX_ATTEMPTS = 24;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TechErrorChannelRepository channels;
    private final TechErrorReportService service;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    private final AtomicInteger failures = new AtomicInteger();

    public TechErrorSummaryEmbedMigration(TechErrorChannelRepository channels, TechErrorReportService service, JdbcTemplate jdbc,
                                          ObjectProvider<SchedulerService> scheduler) {
        this.channels = channels;
        this.service = service;
        this.jdbc = jdbc;
        this.scheduler = scheduler;
    }

    @Scheduled(initialDelayString = "${site.monitor.tech-error.summary-embed-migration.initial-delay-ms:180000}", fixedDelay = 3_600_000L)
    public void run() {
        if (failures.get() >= MAX_ATTEMPTS) return;           // bu pod denemeyi bıraktı (bir kez ERROR yazıldı)
        // Sıra: önce oran göçü (20.138) — nişanı yoksa ya da okunamıyorsa bu tur beklenir
        if (!Boolean.TRUE.equals(marker(TechErrorCalcMigration.KEY))) return;
        if (done()) return;
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null) return;
        try {
            if (s.callWithSchedulerLock(LOCK, this::migrateLocked).isPresent()) failures.set(0);
        } catch (RuntimeException e) {
            int n = failures.incrementAndGet();
            if (n >= MAX_ATTEMPTS) {
                log.error("Teknik hata raporu özet göçü {} denemede tamamlanamadı — bu pod denemeyi bırakıyor (nişan yazılmadı)", n, e);
            } else {
                log.warn("Teknik hata raporu özet göçü başarısız (bir saat sonra yeniden denenecek): {}", e.toString());
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
            log.debug("Özet göçü nişanı okunamadı ({}): {}", key, e.getMessage());
            return null;
        }
    }

    /** Nişan yazılmış mı; okunamazsa "evet" (bu tur koşmaz, sonraki tur yeniden bakar). */
    boolean done() {
        return !Boolean.FALSE.equals(marker(KEY));
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
        log.info("Teknik hata raporu özet göçü tamamlandı: {} kanal şablonu güncellendi (map / kota özet kartında, anomali adları)",
                changed);
        return changed;
    }

    // ── Saf dönüşüm ──────────────────────────────────────────────────────────────────────────────────

    /** Eski varsayılan → yeni (TR ve EN), yalnız birebir aynıysa. */
    private static final Map<String, String> ISSUE_TITLE = Map.of(
            "Teknik hataların anomali olarak arttığı konular", "Anormal Artış Gösteren Hatalar",
            "Topics where technical errors rose abnormally", "Errors With an Abnormal Increase");
    private static final Map<String, String> ISSUE_ITEM = Map.of(
            "{konu} Konusu ile yaşanan problem", "{konu}: Hata Artışı",
            "{topic} issue", "{topic}: Error Increase");
    private static final Map<String, String> ISSUE_DESCRIPTION = Map.of(
            "Ne zaman, nerede, nasıl gerçekleşti?", "Ne oldu? (zaman, yer, etki)",
            "When, where and how did it happen?", "What happened? (when, where, impact)");
    private static final Map<String, String> ISSUE_ERRORS = Map.of(
            "Bu sorundan kaynaklı teknik hata adedi", "Bu konudan kaynaklı teknik hata adedi",
            "Technical errors caused by this issue", "Technical errors caused by this topic");

    /** Eski varsayılan açıklama alanı adları (her iki şekil). */
    private static final Set<String> OLD_NOTE_LABELS = Set.of("Açıklama", "Notes");

    /**
     * Gömülecek bölüm: kimlik, adet ve açıklama alanları; eski başlıklar (şekil B adet + açıklama, şekil A yalnız açıklama;
     * TR / EN); adetin varsayılan adları; yeni başlık ve açıklama adı (eski başlığın diline göre).
     */
    private record Embed(String id, String countId, String noteId, String oldBTr, String oldBEn, String oldATr, String oldAEn,
                         Set<String> countLabels, String titleTr, String titleEn, String noteTr, String noteEn) {
        boolean shapeB(String title) { return oldBTr.equals(title) || oldBEn.equals(title); }

        boolean shapeA(String title) { return oldATr.equals(title) || oldAEn.equals(title); }

        boolean english(String title) { return oldBEn.equals(title) || oldAEn.equals(title); }
    }

    private static final List<Embed> EMBEDS = List.of(
            new Embed("unmapped", "unmapped-count", "unmapped-note",
                    "Map Yapılmamış Hata Adedi", "Unmapped Error Count", "Map Yapılmamış Hatalar", "Unmapped Errors",
                    Set.of("Map yapılmamış hata adedi", "Unmapped errors"),
                    "Map Yapılmamış Hatalar", "Unmapped Errors", "Map yapılmamış hatalar hakkında açıklama", "About the unmapped errors"),
            new Embed("quota", "quota-count", "quota-note",
                    "Kota Hatası Adedi", "Quota Error Count", "Kota Hataları", "Quota Errors",
                    Set.of("Kota hatası adedi", "Quota errors"),
                    "Kota Hataları", "Quota Errors", "Kota hataları hakkında açıklama", "About the quota errors"));

    /**
     * Şablonu dönüştürür; değişecek bir şey yoksa boş. Saf: sorgu yok, girdi değişmez. Tekrar uygulanınca boş döner
     * (gömülü bölüm yeniden eşleşmez, yeni adlar eski adlarla eşleşmez).
     */
    static Optional<Template> migrate(Template t) {
        List<Section> sections = new ArrayList<>(t.sections());
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            if (!"issues".equals(s.kind())) continue;
            sections.set(i, new Section(s.id(), s.kind(), swap(s.title(), ISSUE_TITLE), s.fields(), s.slots(),
                    swap(s.itemTitle(), ISSUE_ITEM), swap(s.descriptionLabel(), ISSUE_DESCRIPTION), s.customersLabel(),
                    swap(s.errorsLabel(), ISSUE_ERRORS), s.text(), s.lines(), s.columns(), s.rows(), s.host()));
        }
        for (Embed e : EMBEDS) embed(sections, e);
        Template next = new Template(t.lang(), t.title(), List.copyOf(sections));
        String json = TechErrorTemplate.toJson(next);
        if (json.equals(TechErrorTemplate.toJson(t))) return Optional.empty();
        if (json.length() > TechErrorTemplate.MAX_JSON_CHARS) {
            log.warn("Teknik hata raporu özet göçü: şablon 64 KB sınırını aşacağı için dönüştürülmedi ({} bölüm)", t.sections().size());
            return Optional.empty();
        }
        return Optional.of(next);
    }

    private static String swap(String v, Map<String, String> m) {
        return v != null && m.containsKey(v) ? m.get(v) : v;
    }

    /** Alan varsayılan "girilen" alan mı: ad kümede, tür beklenen, ipucu / "Hayır ise sorma" bağı / hesap yok. */
    private static boolean plain(Field f, String type, Set<String> labels) {
        return f != null && type.equals(f.type()) && labels.contains(f.label()) && f.hint() == null && f.skipIfNo() == null
                && f.calc() == null;
    }

    private static int indexOf(List<Section> sections, String id) {
        for (int i = 0; i < sections.size(); i++) if (id.equals(sections.get(i).id())) return i;
        return -1;
    }

    /** Eski varsayılanla birebir aynı bölümü özet kartına gömer; eşleşmiyorsa liste değişmez. */
    private static void embed(List<Section> sections, Embed e) {
        int at = indexOf(sections, e.id());
        if (at < 0) return;
        Section s = sections.get(at);
        if (!"metrics".equals(s.kind()) || s.host() != null) return;
        // Ev sahibi: adede başvuran ilk yönetici özeti, yoksa ilk özet; özet yoksa dokunulmaz
        String host = null;
        for (Section x : sections) {
            if (host != null || !"summary".equals(x.kind())) continue;
            for (String line : x.lines()) {
                for (TechErrorSummary.Ref r : TechErrorSummary.refs(line)) if (e.countId().equals(r.id())) host = x.id();
            }
        }
        if (host == null) {
            for (Section x : sections) {
                if ("summary".equals(x.kind())) {
                    host = x.id();
                    break;
                }
            }
        }
        if (host == null) return;
        List<Field> fs = s.fields();
        Field count;
        Field note;
        int countSection = -1;
        if (fs.size() == 2 && e.shapeB(s.title()) && e.countId().equals(fs.get(0).id()) && plain(fs.get(0), "number", e.countLabels())
                && e.noteId().equals(fs.get(1).id()) && plain(fs.get(1), "textarea", OLD_NOTE_LABELS)) {
            count = fs.get(0);
            note = fs.get(1);
        } else if (fs.size() == 1 && e.shapeA(s.title()) && e.noteId().equals(fs.get(0).id())
                && plain(fs.get(0), "textarea", OLD_NOTE_LABELS)) {
            note = fs.get(0);
            count = null;
            for (int i = 0; i < sections.size(); i++) {
                Section x = sections.get(i);
                if (i == at || !"metrics".equals(x.kind()) || x.host() != null) continue;
                for (Field f : x.fields()) {
                    if (e.countId().equals(f.id())) {
                        count = f;
                        countSection = i;
                    }
                }
            }
            if (!plain(count, "number", e.countLabels())) return;            // adet yok ya da ekip değiştirdi
            if (sections.get(countSection).fields().size() == 1) return;     // adet tek alansa o bölüm boş kalırdı
        } else {
            return;
        }
        boolean en = e.english(s.title());
        Section embedded = new Section(s.id(), "metrics", en ? e.titleEn() : e.titleTr(),
                List.of(count, new Field(note.id(), en ? e.noteEn() : e.noteTr(), "textarea", null, null, null)),
                List.of(), null, null, null, null, null, List.of(), List.of(), List.of(), host);
        if (countSection >= 0) {
            Section cs = sections.get(countSection);
            List<Field> rest = new ArrayList<>(cs.fields());
            rest.removeIf(f -> e.countId().equals(f.id()));
            sections.set(countSection, cs.withFields(List.copyOf(rest)));
        }
        // Bölüm özetin hemen ardına — ondan önce gömülmüş bölümlerin arkasına (map, sonra kota)
        sections.remove(at);
        int insert = indexOf(sections, host) + 1;
        while (insert < sections.size() && host.equals(sections.get(insert).host())) insert++;
        sections.add(insert, embedded);
    }
}
