package com.sitemonitor.service.techerror;

import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.StandaloneMonitorDeletionBackfill;
import com.sitemonitor.service.techerror.TechErrorTemplate.Calc;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEK SEFERLİK şablon göçü (2026-10-17, 4b — kullanıcı kararları: "şablonda SLA hedef değeri; 'SLA hedefin üstünde mi?'
 * otomatik; başta 4–6 renkli kutucuk; önceki aya göre fark cümleleri"). MEVCUT kanal şablonlarında:
 * <ul>
 *   <li>{@code sla-rate} hedefsiz bir yüzde alanıysa hedef {@code 99,95} + yön "yüksek iyi" eklenir;</li>
 *   <li>{@code sla-above} hesaplanmayan bir evet/hayır alanıysa (ve başka alanların "Hayır ise sorma" sorusu değilse)
 *       {@code compare(sla-rate)} hesabı eklenir — {@code sla-rate} hedefli yüzde alanı olduğunda;</li>
 *   <li>özet cümleleri: ESKİ varsayılan SLA cümlesi (TR / EN) yenisiyle değişir; özet cümleleri 4b öncesi varsayılanla
 *       BİREBİR aynıysa bugünkü varsayılan cümleler alınır (fark cümlesi eklenir); ekibin yazdığı cümlelere dokunulmaz;</li>
 *   <li>ilk özetin kutucukları boşsa varsayılan kutucuklar (şablonda var olan sayı / yüzde alanları, ≤ 6) eklenir.</li>
 * </ul>
 * Açılmış / gönderilmiş raporlar DEĞİŞMEZ (kopyalarını taşırlar; taslak "şablonun son hâlini uygula" ile alır). Özet
 * göçü ({@link TechErrorSummaryEmbedMigration}) tamamlanmadan koşmaz. Açılış yolunda değil: açılıştan 4 dk sonra, kendi
 * kilidi altında; nişan kilit İÇİNDE yeniden okunur ve yalnız TÜM kanallar işlendiyse yazılır. Her kanal {@code saveTemplate}
 * ile yazılır (koşullu güncelleme + sürüm geçmişinde "SiteMonitor"). Eski bir pod {@code target} / {@code better} /
 * {@code tiles} / {@code compare}'i bilmez: hoşgörülü okuma onları düşürür (alanlar ve değerler kalır, SLA sorusu elle girilir).
 */
@Slf4j
@Service
public class TechErrorSlaTilesMigration {

    /** Nişan anahtarı — yayından sonra DEĞİŞTİRİLMEZ. */
    static final String KEY = "2026-10-17-tech-error-sla-target-tiles";
    static final String ACTOR = "SiteMonitor";
    static final String LOCK = "tech-error-sla-tiles-migration";
    static final int MAX_ATTEMPTS = 24;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TechErrorChannelRepository channels;
    private final TechErrorReportService service;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<SchedulerService> scheduler;
    private final AtomicInteger failures = new AtomicInteger();

    public TechErrorSlaTilesMigration(TechErrorChannelRepository channels, TechErrorReportService service, JdbcTemplate jdbc,
                                      ObjectProvider<SchedulerService> scheduler) {
        this.channels = channels;
        this.service = service;
        this.jdbc = jdbc;
        this.scheduler = scheduler;
    }

    @Scheduled(initialDelayString = "${site.monitor.tech-error.sla-tiles-migration.initial-delay-ms:240000}", fixedDelay = 3_600_000L)
    public void run() {
        if (failures.get() >= MAX_ATTEMPTS) return;
        // Sıra: önce özet göçü (4a) — nişanı yoksa ya da okunamıyorsa bu tur beklenir
        if (!Boolean.TRUE.equals(marker(TechErrorSummaryEmbedMigration.KEY))) return;
        if (done()) return;
        SchedulerService s = scheduler.getIfAvailable();
        if (s == null) return;
        try {
            if (s.callWithSchedulerLock(LOCK, this::migrateLocked).isPresent()) failures.set(0);
        } catch (RuntimeException e) {
            int n = failures.incrementAndGet();
            if (n >= MAX_ATTEMPTS) {
                log.error("Teknik hata raporu SLA / kutucuk göçü {} denemede tamamlanamadı — bu pod denemeyi bırakıyor (nişan yazılmadı)", n, e);
            } else {
                log.warn("Teknik hata raporu SLA / kutucuk göçü başarısız (bir saat sonra yeniden denenecek): {}", e.toString());
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
            log.debug("SLA / kutucuk göçü nişanı okunamadı ({}): {}", key, e.getMessage());
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
        log.info("Teknik hata raporu SLA / kutucuk göçü tamamlandı: {} kanal şablonu güncellendi", changed);
        return changed;
    }

    // ── Saf dönüşüm ──────────────────────────────────────────────────────────────────────────────────

    /** 4b öncesi varsayılan SLA cümlesi → yenisi (yalnız birebir aynıysa; TR ve EN). */
    static final Map<String, String> SLA_LINE = Map.of(
            "SLA taahhüdü kapsamında kritik fonksiyonların SLA oranı {f:sla-rate} ile {f:sla-above?hedefin üzerinde|hedefin altında} gerçekleşmiştir.",
            "SLA taahhüdü kapsamında kritik fonksiyonların SLA oranı {f:sla-rate} (hedef {t:sla-rate}, fark {dt:sla-rate}) ile "
                    + "{f:sla-above?hedef başarıyla tutturulmuştur|hedefin altında kalmıştır}.",
            "Under the SLA commitment the critical functions' SLA rate was {f:sla-rate}, {f:sla-above?above|below} the target.",
            "Under the SLA commitment the critical functions' SLA rate was {f:sla-rate} (target {t:sla-rate}, difference {dt:sla-rate}), "
                    + "so {f:sla-above?the target was met|the target was missed}.");

    /**
     * Bugünkü varsayılan özet cümleleri; SLA hedefi kullanılamıyorsa (alan yok / tür değişti / ekip kendi hesabını koymuş) yeni
     * SLA cümlesi ({t:} / {dt:}) yerine eskisi kalır — fark cümlesi ({d:}) yine eklenir (inceleme 2026-10-17: SLA'sız kanal
     * fark cümlesini hiç almıyordu).
     */
    static List<String> freshDefaultLines(String lang, boolean slaOk) {
        List<String> fresh = TechErrorTemplate.defaults(lang).sections().get(0).lines();
        if (slaOk) return fresh;
        Map<String, String> oldByNew = new java.util.HashMap<>();
        for (var e : SLA_LINE.entrySet()) oldByNew.put(e.getValue(), e.getKey());
        List<String> out = new ArrayList<>(fresh.size());
        for (String l : fresh) out.add(oldByNew.getOrDefault(l, l));
        return List.copyOf(out);
    }

    /** 4b öncesi varsayılan özet cümleleri (TR / EN): birebir aynıysa bugünkü varsayılan cümleler alınır (fark cümlesi eklenir). */
    static final List<String> OLD_LINES_TR = List.of(
            "{kanal} Kanalı {ay} {yil} verilerine göre teknik hata oranı {f:error-rate} olarak gerçekleşmiştir.",
            "Kesintiler hariç teknik hata oranı {f:error-rate-excl} olarak hesaplanmaktadır.",
            "{kanal} Kanalından yapılan toplam işlem adedi {f:total-tx} olarak gerçekleşmiştir.",
            "Bir önceki aya göre işlem adedinde {f:tx-increase-pct} {f:tx-increase?artış|azalış} görülmüştür.",
            "Ay içindeki teknik hata adedi {f:error-count} olarak gerçekleşmiştir.",
            "Map yapılmamış hata adedi {f:unmapped-count} olup teknik hataların {f:unmapped-rate} kadarını oluşturmaktadır.",
            "Kota hatası adedi {f:quota-count} olup teknik hataların {f:quota-rate} kadarını oluşturmaktadır.",
            "Teknik hataların {f:outage-errors} adedi kesinti kaynaklı olup teknik hataların {f:outage-share} kadarını oluşturmaktadır.",
            "SLA taahhüdü kapsamında kritik fonksiyonların SLA oranı {f:sla-rate} ile {f:sla-above?hedefin üzerinde|hedefin altında} gerçekleşmiştir.");
    static final List<String> OLD_LINES_EN = List.of(
            "Based on {month} {year} figures, the {channel} channel's technical error rate was {f:error-rate}.",
            "Excluding outage days, the technical error rate is {f:error-rate-excl}.",
            "Total transactions through {channel} were {f:total-tx}.",
            "Compared with the previous month, transactions show a {f:tx-increase-pct} {f:tx-increase?increase|decrease}.",
            "There were {f:error-count} technical errors in the month.",
            "There were {f:unmapped-count} unmapped errors, {f:unmapped-rate} of the technical errors.",
            "There were {f:quota-count} quota errors, {f:quota-rate} of the technical errors.",
            "{f:outage-errors} technical errors were caused by outages, {f:outage-share} of the technical errors.",
            "Under the SLA commitment the critical functions' SLA rate was {f:sla-rate}, {f:sla-above?above|below} the target.");

    /**
     * Şablonu dönüştürür; değişecek bir şey yoksa boş. Saf: sorgu yok, girdi değişmez. Tekrar uygulanınca boş döner
     * (hedefli alan yeniden hedeflenmez, hesaplı soru yeniden hesaplanmaz, dolu kutucuk listesi değişmez).
     */
    static Optional<Template> migrate(Template t) {
        List<Section> sections = new ArrayList<>(t.sections());
        // 1) SLA oranı: hedef + yön
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            if (!"metrics".equals(s.kind())) continue;
            List<Field> fs = new ArrayList<>(s.fields());
            boolean touched = false;
            for (int k = 0; k < fs.size(); k++) {
                Field f = fs.get(k);
                if ("sla-rate".equals(f.id()) && "percent".equals(f.type()) && f.target() == null) {
                    fs.set(k, f.withTarget(TechErrorTemplate.DEFAULT_SLA_TARGET, "higher"));
                    touched = true;
                }
            }
            if (touched) sections.set(i, s.withFields(List.copyOf(fs)));
        }
        Map<String, Field> byId = new HashMap<>();
        Set<String> skipTargets = new HashSet<>();
        for (Section s : sections) {
            if (!"metrics".equals(s.kind())) continue;
            for (Field f : s.fields()) {
                byId.put(f.id(), f);
                if (f.skipIfNo() != null) skipTargets.add(f.skipIfNo());
            }
        }
        Field sla = byId.get("sla-rate");
        boolean slaOk = sla != null && "percent".equals(sla.type()) && sla.target() != null;
        // 2) "SLA hedefin üstünde mi?": hesaplanmayan evet/hayır → hedef karşılaştırması
        if (slaOk) {
            for (int i = 0; i < sections.size(); i++) {
                Section s = sections.get(i);
                if (!"metrics".equals(s.kind())) continue;
                List<Field> fs = new ArrayList<>(s.fields());
                boolean touched = false;
                for (int k = 0; k < fs.size(); k++) {
                    Field f = fs.get(k);
                    if ("sla-above".equals(f.id()) && "yesno".equals(f.type()) && f.calc() == null && !skipTargets.contains(f.id())) {
                        String hint = f.hint() == null ? (t.en() ? TechErrorTemplate.DEFAULT_HINT_SLA_EN : TechErrorTemplate.DEFAULT_HINT_SLA_TR) : f.hint();
                        fs.set(k, f.withCalc(Calc.compare("sla-rate")).withHint(hint));
                        touched = true;
                    }
                }
                if (touched) sections.set(i, s.withFields(List.copyOf(fs)));
            }
        }
        // 3) Özet cümleleri ve 4) kutucuklar — ilk özette
        boolean firstSummary = true;
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            if (!"summary".equals(s.kind())) continue;
            List<String> lines = s.lines();
            if (lines.equals(OLD_LINES_TR)) lines = freshDefaultLines("tr", slaOk);
            else if (lines.equals(OLD_LINES_EN)) lines = freshDefaultLines("en", slaOk);
            else {
                // Ekibin kendi listesi: yalnız eski SLA cümlesi yenilenir (hedef kullanılabiliyorsa); fark cümlesi eklenmez
                List<String> swapped = new ArrayList<>(lines.size());
                for (String line : lines) swapped.add(slaOk && SLA_LINE.containsKey(line) ? SLA_LINE.get(line) : line);
                lines = List.copyOf(swapped);
            }
            Section next = s.withLines(lines);
            if (firstSummary && s.tiles().isEmpty()) {
                List<String> tiles = new ArrayList<>();
                for (String id : TechErrorTemplate.DEFAULT_TILES) {
                    Field f = byId.get(id);
                    if (f != null && ("number".equals(f.type()) || "percent".equals(f.type()))) tiles.add(id);
                }
                if (!tiles.isEmpty()) next = next.withTiles(List.copyOf(tiles));
            }
            firstSummary = false;
            sections.set(i, next);
        }
        Template next = new Template(t.lang(), t.title(), List.copyOf(sections));
        String json = TechErrorTemplate.toJson(next);
        if (json.equals(TechErrorTemplate.toJson(t))) return Optional.empty();
        if (json.length() > TechErrorTemplate.MAX_JSON_CHARS) {
            log.warn("Teknik hata raporu SLA / kutucuk göçü: şablon 64 KB sınırını aşacağı için dönüştürülmedi ({} bölüm)", t.sections().size());
            return Optional.empty();
        }
        return Optional.of(next);
    }
}
