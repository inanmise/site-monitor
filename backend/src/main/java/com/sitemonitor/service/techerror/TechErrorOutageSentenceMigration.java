package com.sitemonitor.service.techerror;

import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorTeamTemplate;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorTeamTemplateRepository;
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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEK SEFERLİK şablon göçü (2026-10-18; kullanıcı kararı: "Verileri bölümünde yalnız 'Dönemde kesinti oldu mu?' sorusu
 * kalıyor — bunu özete bir cümle olarak ekleyip bölümü tamamen kaldıralım"). MEVCUT kanal şablonlarında ve takım şablon
 * kütüphanesi kayıtlarında yönetici özetinin cümleleri bugünkü (bu sürüm öncesi) varsayılanla BİREBİR aynıysa (TR ya da EN)
 * ve şablonda hesaplanmayan bir {@code had-outage} evet/hayır alanı varsa kesinti cümlesi
 * ({@link TechErrorTemplate#OUTAGE_LINE_TR} / {@link TechErrorTemplate#OUTAGE_LINE_EN}) kesinti kaynaklı adet cümlesinin
 * hemen önüne eklenir. Böylece kesinti cevabı her durumda özette yazılır; PDF ve e-posta "Verileri" bölümünü (bütün
 * değerleri özette) hiç basmaz, notları özetin sonuna taşır ({@link TechErrorSummary#plan}).
 *
 * <p>Dokunulmaz: ekibin değiştirdiği cümle listesi, {@code had-outage} alanı olmayan ya da türü / hesabı değişmiş şablon.
 * Açılmış / gönderilmiş raporlar DEĞİŞMEZ (şablonun kopyasını taşırlar; taslak "şablonun son hâlini uygula" ile alır). Adet
 * göçü ({@link TechErrorCountsInFiguresMigration}) tamamlanmadan koşmaz (zincir: oran → özet gömme → SLA → adetler → bu).
 * Açılış yolunda değil: açılıştan 12 dk sonra, saatte bir, kendi kilidi altında; nişan kilit İÇİNDE yeniden okunur ve yalnız
 * TÜM kanallar ve kütüphane kayıtları işlendiyse yazılır (dönüşüm tekrar uygulanınca değişiklik üretmez). Kanal
 * {@code saveTemplate(…, null, "SiteMonitor")} (koşullu, sürüm geçmişinde yeni sürüm), kütüphane kaydı
 * {@link TechErrorTeamTemplateService#systemUpdateTemplate} (okunan sürümle koşullu) ile yazılır; aynı anda kaydeden ekibe
 * karşı en çok 3 deneme; okuma ile kayıt arasında silinen kanal / kayıt atlanır. Eski bir pod yeni cümleyi olduğu gibi basar
 * ve Verileri bölümünü yine basar (çift değer) — veri kaybı yok.
 */
@Slf4j
@Service
public class TechErrorOutageSentenceMigration {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ. */
    static final String KEY = "2026-10-18-tech-error-outage-sentence";
    static final String ACTOR = "SiteMonitor";
    static final String LOCK = "tech-error-outage-sentence-migration";
    static final int MAX_ATTEMPTS = 24;
    static final String OUTAGE_FIELD = "had-outage";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TechErrorChannelRepository channels;
    private final TechErrorReportService service;
    private final TechErrorTeamTemplateRepository library;
    private final TechErrorTeamTemplateService libraryService;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    private final AtomicInteger failures = new AtomicInteger();

    public TechErrorOutageSentenceMigration(TechErrorChannelRepository channels, TechErrorReportService service,
                                            TechErrorTeamTemplateRepository library, TechErrorTeamTemplateService libraryService,
                                            JdbcTemplate jdbc, ObjectProvider<SchedulerService> scheduler) {
        this.channels = channels;
        this.service = service;
        this.library = library;
        this.libraryService = libraryService;
        this.jdbc = jdbc;
        this.scheduler = scheduler;
    }

    @Scheduled(initialDelayString = "${site.monitor.tech-error.outage-sentence-migration.initial-delay-ms:720000}", fixedDelay = 3_600_000L)
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
                log.error("Teknik hata raporu kesinti cümlesi göçü {} denemede tamamlanamadı — bu pod denemeyi bırakıyor (nişan yazılmadı)", n, e);
            } else {
                log.warn("Teknik hata raporu kesinti cümlesi göçü başarısız (bir saat sonra yeniden denenecek): {}", e.toString());
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
            log.debug("Kesinti cümlesi göçü nişanı okunamadı ({}): {}", key, e.getMessage());
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
                } catch (NoSuchElementException e) {
                    break;                                                // okuma ile kayıt arasında silindi
                } catch (OptimisticLockingFailureException e) {
                    if (attempt >= 3) throw e;
                }
            }
        }
        int entries = 0;
        for (TechErrorTeamTemplate e : library.findAll()) {
            for (int attempt = 1; ; attempt++) {
                TechErrorTeamTemplate cur = library.findById(e.getId()).orElse(null);
                if (cur == null) break;
                // Okunamayan kayıt varsayılana DÜŞÜRÜLMEZ (readStored): dokunulmaz — ekip kütüphaneden siler
                Optional<Template> stored = TechErrorTemplate.readStored(cur.getTemplateJson());
                Optional<Template> next = stored.flatMap(TechErrorOutageSentenceMigration::migrate);
                if (next.isEmpty()) break;
                try {
                    if (libraryService.systemUpdateTemplate(cur.getTeamId(), cur.getId(), cur.getVersion(), next.get(), ACTOR)) entries++;
                    break;
                } catch (NoSuchElementException ex) {
                    break;                                                // kayıt ya da takım silindi
                } catch (OptimisticLockingFailureException ex) {
                    if (attempt >= 3) throw ex;
                }
            }
        }
        jdbc.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                KEY, ISO.format(Instant.now()), changed + entries);
        log.info("Teknik hata raporu kesinti cümlesi göçü tamamlandı: {} kanal şablonu, {} kütüphane kaydı güncellendi", changed, entries);
        return changed + entries;
    }

    // ── Saf dönüşüm ──────────────────────────────────────────────────────────────────────────────────

    /** Bu sürüm öncesi varsayılan özet cümleleri (TR) — birebir kopya (kehanet: bugünkü varsayılandan TÜRETİLMEZ). */
    static final List<String> OLD_LINES_TR = List.of(
            "{kanal} Kanalı {ay} {yil} verilerine göre teknik hata oranı {f:error-rate} olarak gerçekleşmiştir.",
            "Kesintiler hariç teknik hata oranı {f:error-rate-excl} olarak hesaplanmaktadır.",
            "{kanal} Kanalından yapılan toplam işlem adedi {f:total-tx} olarak gerçekleşmiştir.",
            "Bir önceki aya göre işlem adedinde {f:tx-increase-pct} {f:tx-increase?artış|azalış} görülmüştür.",
            "Ay içindeki teknik hata adedi {f:error-count} olarak gerçekleşmiştir.",
            "Önceki aya göre teknik hata adedi {d:error-count}, teknik hata oranı {d:error-rate} değişmiştir.",
            "Map yapılmamış hata adedi {f:unmapped-count} olup teknik hataların {f:unmapped-rate} kadarını oluşturmaktadır.",
            "Kota hatası adedi {f:quota-count} olup teknik hataların {f:quota-rate} kadarını oluşturmaktadır.",
            "Teknik hataların {f:outage-errors} adedi kesinti kaynaklı olup teknik hataların {f:outage-share} kadarını oluşturmaktadır.",
            "SLA taahhüdü kapsamında kritik fonksiyonların SLA oranı {f:sla-rate} (hedef {t:sla-rate}, fark {dt:sla-rate}) ile "
                    + "{f:sla-above?hedef başarıyla tutturulmuştur|hedefin altında kalmıştır}.");

    /** Bu sürüm öncesi varsayılan özet cümleleri (EN) — birebir kopya. */
    static final List<String> OLD_LINES_EN = List.of(
            "Based on {month} {year} figures, the {channel} channel's technical error rate was {f:error-rate}.",
            "Excluding outage days, the technical error rate is {f:error-rate-excl}.",
            "Total transactions through {channel} were {f:total-tx}.",
            "Compared with the previous month, transactions show a {f:tx-increase-pct} {f:tx-increase?increase|decrease}.",
            "There were {f:error-count} technical errors in the month.",
            "Compared with the previous month, technical errors changed by {d:error-count} and the technical error rate by {d:error-rate}.",
            "There were {f:unmapped-count} unmapped errors, {f:unmapped-rate} of the technical errors.",
            "There were {f:quota-count} quota errors, {f:quota-rate} of the technical errors.",
            "{f:outage-errors} technical errors were caused by outages, {f:outage-share} of the technical errors.",
            "Under the SLA commitment the critical functions' SLA rate was {f:sla-rate} (target {t:sla-rate}, difference {dt:sla-rate}), "
                    + "so {f:sla-above?the target was met|the target was missed}.");

    /** Kesinti cümlesinin önüne girdiği kesinti kaynaklı adet cümlesinin {@code OLD_LINES_*} içindeki yeri. */
    static final int OUTAGE_ERRORS_LINE = 8;

    /**
     * Şablonu dönüştürür; değişecek bir şey yoksa boş. Saf: sorgu yok, girdi değişmez. Tekrar uygulanınca boş döner (cümle
     * listesi artık eski varsayılan değil). Yalnız İLK özet bölümü (varsayılan düzende tek özet).
     */
    static Optional<Template> migrate(Template t) {
        if (t == null) return Optional.empty();
        Field outage = null;
        for (Field f : t.allFields()) if (OUTAGE_FIELD.equals(f.id())) outage = f;
        if (outage == null || !"yesno".equals(outage.type()) || outage.calc() != null) return Optional.empty();
        List<Section> sections = new ArrayList<>(t.sections());
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            if (!"summary".equals(s.kind())) continue;
            String line = s.lines().equals(OLD_LINES_TR) ? TechErrorTemplate.OUTAGE_LINE_TR
                    : s.lines().equals(OLD_LINES_EN) ? TechErrorTemplate.OUTAGE_LINE_EN : null;
            if (line == null) return Optional.empty();
            List<String> lines = new ArrayList<>(s.lines());
            lines.add(OUTAGE_ERRORS_LINE, line);
            sections.set(i, s.withLines(List.copyOf(lines)));
            Template next = new Template(t.lang(), t.title(), List.copyOf(sections));
            if (TechErrorTemplate.toJson(next).length() > TechErrorTemplate.MAX_JSON_CHARS) {
                log.warn("Teknik hata raporu kesinti cümlesi göçü: şablon 64 KB sınırını aşacağı için dönüştürülmedi ({} bölüm)", t.sections().size());
                return Optional.empty();
            }
            return Optional.of(next);
        }
        return Optional.empty();
    }
}
