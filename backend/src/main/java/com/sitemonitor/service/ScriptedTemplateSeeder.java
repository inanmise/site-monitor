package com.sitemonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.ScriptedTemplate;
import com.sitemonitor.model.ScriptedTemplateVersion;
import com.sitemonitor.repository.ScriptedTemplateRepository;
import com.sitemonitor.repository.ScriptedTemplateVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Küratörlü k6 şablonlarını ({@code scripted-templates.json}) veritabanına taşır.
 *
 * <p><b>İdempotent ve YIKICI DEĞİL.</b> Anahtar {@code builtinKey}: satır zaten varsa
 * <b>hiç dokunulmaz</b>. Bu bilinçli bir karar ve bedeli var — admin bir yerleşiği düzenlediyse
 * uygulama yükseltmesi onu geri EZMEZ; buna karşılık JSON'daki bir iyileştirme de o kuruluma
 * <b>ULAŞMAZ</b>. İkisi aynı madalyonun yüzleri: "kullanıcının düzenlemesi kutsaldır" demek,
 * "yukarı akış artık o satırın sahibi değildir" demektir.
 *
 * <p>{@code builtinSeedVersion} bu bedeli ileride ölçülebilir kılmak için yazılır: katalog
 * sürümü ilerlediğinde "şu 3 yerleşiğin daha yeni hâli var" raporu üretilebilir. Otomatik
 * propagasyon BİLİNÇLİ olarak YOK — sessizce üzerine yazmak, kullanıcının düzenlemesini
 * kaybettirmenin en hızlı yoludur.
 *
 * <p>Seed edilen satır GENEL şablondur ({@code teamId = null}) ve {@code SEED} olaylı bir sürüm
 * satırı üretir; böylece "bu şablon nereden geldi" sorusu sürüm zaman çizelgesinden okunur.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptedTemplateSeeder {

    private static final String RESOURCE = "scripted-templates.json";
    private static final String ACTOR    = "system";
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final ScriptedTemplateRepository templateRepo;
    private final ScriptedTemplateVersionRepository versionRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Seed KENDİ yaşam döngüsüne sahiptir; {@code SchedulerService.runOnStartup()} zincirine
     * eklenmedi. Gerekçe: o sınıfın yapıcısı 40'tan fazla bağımlılık alıyor ve testinde elle
     * kuruluyor — oraya bir parametre eklemek, bu özellikle hiç ilgisi olmayan bir testi kırar
     * ve bağımlılık grafiğini gereksiz büyütürdü. Sıralama riski YOK: tablolar
     * {@code ddl-auto=update} ile EntityManagerFactory kurulurken, yani bu olaydan çok önce doğar
     * ({@code applySchemaPatches} yalnız eski kurulumların yükseltmesi içindir).
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void seedOnStartup() {
        try { seed(); }
        catch (Exception e) { log.warn("Şablon seed başarısız: {}", e.toString()); }
    }

    /** @return eklenen satır sayısı (mevcutlar atlanır). Asla istisna fırlatmaz. */
    public int seed() {
        List<JsonNode> nodes;
        int seedVersion;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.warn("Şablon kataloğu bulunamadı ({}) — seed atlandı", RESOURCE);
                return 0;
            }
            JsonNode root = mapper.readTree(in);
            seedVersion = root.path("seedVersion").asInt(1);
            nodes = new ArrayList<>();
            root.path("templates").forEach(nodes::add);
        } catch (Exception e) {
            // Seed, uygulamanın açılışını ASLA engellememeli: şablon kütüphanesi bir kolaylıktır,
            // izlemenin kendisi ona bağlı değildir.
            log.warn("Şablon kataloğu okunamadı — seed atlandı: {}", e.toString());
            return 0;
        }

        int added = 0;
        for (JsonNode n : nodes) {
            String key = n.path("builtinKey").asText(null);
            if (key == null || key.isBlank()) continue;
            try {
                if (templateRepo.findByBuiltinKey(key).isPresent()) continue;   // ASLA üzerine yazma
                ScriptedTemplate t = toEntity(n, seedVersion);
                ScriptedTemplate saved = templateRepo.save(t);
                writeSeedVersion(saved);
                added++;
            } catch (Exception e) {
                // Tek bir şablonun düşmesi kalanları götürmesin (ör. eşzamanlı iki pod'un
                // unique index yarışı — kaybeden taraf burada sessizce atlar).
                log.debug("Şablon seed edilemedi ({}): {}", key, e.toString());
            }
        }
        if (added > 0) log.info("Şablon kütüphanesi: {} yerleşik şablon eklendi (katalog v{})", added, seedVersion);
        return added;
    }

    private ScriptedTemplate toEntity(JsonNode n, int seedVersion) {
        String now = ISO.format(Instant.now());
        ScriptedTemplate t = new ScriptedTemplate();
        t.setBuiltinKey(n.path("builtinKey").asText());
        t.setBuiltinSeedVersion(seedVersion);
        t.setName(n.path("name").asText());
        t.setNameEn(text(n, "nameEn"));
        t.setDescription(text(n, "description"));
        t.setDescriptionEn(text(n, "descriptionEn"));
        t.setWhenToUse(text(n, "whenToUse"));
        t.setWhenToUseEn(text(n, "whenToUseEn"));
        t.setScript(n.path("script").asText());
        t.setEnvJson(n.path("env").isMissingNode() ? "[]" : n.path("env").toString());
        t.setTeamId(null);                       // seed edilen her şablon GENEL'dir
        t.setActive(true);
        t.setCurrentVersion(VersionLabels.FIRST_VERSION);
        t.setCreatedAt(now);
        t.setCreatedBy(ACTOR);
        t.setCreatedByName(ACTOR);
        return t;
    }

    /** SEED olaylı ilk sürüm satırı — "bu şablon nereden geldi" zaman çizelgesinde görünsün. */
    private void writeSeedVersion(ScriptedTemplate t) {
        try {
            ScriptedTemplateVersion v = new ScriptedTemplateVersion();
            v.setTemplateId(t.getId());
            v.setSequenceNo(0);
            v.setVersion(VersionLabels.FIRST_VERSION);
            v.setEventType("SEED");
            v.setScript(t.getScript());
            v.setEnvJson(t.getEnvJson());
            v.setTeamId(null);
            v.setNote("Yerleşik katalogdan eklendi (v" + t.getBuiltinSeedVersion() + ")");
            v.setCreatedAt(t.getCreatedAt());
            v.setCreatedBy(ACTOR);
            v.setCreatedByName(ACTOR);
            versionRepo.save(v);
        } catch (Exception e) {
            // Geçmiş yardımcı bir kayıttır; yazılamaması şablonun kendisini götürmemeli
            // (ScriptedScriptVersion'daki aynı gerekçe).
            log.debug("Şablon seed sürümü yazılamadı ({}): {}", t.getBuiltinKey(), e.toString());
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }
}
