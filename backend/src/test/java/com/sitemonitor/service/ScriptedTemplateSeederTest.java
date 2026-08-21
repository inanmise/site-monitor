package com.sitemonitor.service;

import com.sitemonitor.model.ScriptedTemplate;
import com.sitemonitor.model.ScriptedTemplateVersion;
import com.sitemonitor.repository.ScriptedTemplateRepository;
import com.sitemonitor.repository.ScriptedTemplateVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Yerleşik şablonların DB'ye taşınması.
 *
 * <p>En kritik sözleşme İDEMPOTENS ve YIKICI OLMAMA: seeder her açılışta koşuyor. Üzerine yazsaydı,
 * admin'in düzenlediği bir yerleşik her yeniden başlatmada sessizce geri döner ve kullanıcı
 * "düzenlemem kayboldu" derdi — geri dönüşü olmayan, fark edilmesi zor bir veri kaybı.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScriptedTemplateSeederTest {

    @Mock ScriptedTemplateRepository templateRepo;
    @Mock ScriptedTemplateVersionRepository versionRepo;

    private ScriptedTemplateSeeder seeder;

    /** builtinKey → kaydedilmiş satır; gerçek bir tablo gibi davranır. */
    private Map<String, ScriptedTemplate> table;

    @BeforeEach
    void setUp() {
        seeder = new ScriptedTemplateSeeder(templateRepo, versionRepo);
        table = new HashMap<>();
        AtomicLong ids = new AtomicLong(1);

        when(templateRepo.findByBuiltinKey(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(table.get(inv.getArgument(0, String.class))));
        when(templateRepo.save(any(ScriptedTemplate.class))).thenAnswer(inv -> {
            ScriptedTemplate t = inv.getArgument(0);
            if (t.getId() == null) t.setId(ids.getAndIncrement());
            table.put(t.getBuiltinKey(), t);
            return t;
        });
    }

    @Test
    @DisplayName("İlk seed: katalogdaki 11 yerleşik GENEL şablon olarak eklenir")
    void firstSeed_insertsCatalog() {
        int added = seeder.seed();

        assertThat(added).isEqualTo(11);
        assertThat(table).hasSize(11).containsKeys("smoke-health", "graphql", "oidc-keycloak");
        // Seed edilen her şablon GENEL'dir ve ilk sürümle doğar.
        for (ScriptedTemplate t : table.values()) {
            assertThat(t.getTeamId()).as("%s: seed edilen şablon GENEL olmalı", t.getBuiltinKey()).isNull();
            assertThat(t.getActive()).isTrue();
            assertThat(t.getCurrentVersion()).isEqualTo("1.0.0");
            assertThat(t.getBuiltinSeedVersion()).isNotNull();
            assertThat(t.getScript()).contains("export default function");
            assertThat(t.getCreatedBy()).isEqualTo("system");
        }
    }

    @Test
    @DisplayName("İKİNCİ seed hiçbir şey eklemez — her açılışta çoğalmaz")
    void secondSeed_isIdempotent() {
        assertThat(seeder.seed()).isEqualTo(11);

        int addedAgain = seeder.seed();

        assertThat(addedAgain).isZero();
        assertThat(table).hasSize(11);
        verify(templateRepo, times(11)).save(any(ScriptedTemplate.class));   // ilk turdakiler, fazlası yok
    }

    /**
     * K1'in bedeli burada pinlenir: kullanıcının düzenlemesi kutsaldır, yukarı akış artık o
     * satırın sahibi değildir. Bu davranış BİLİNÇLİ — otomatik propagasyon yok.
     */
    @Test
    @DisplayName("Admin'in DÜZENLEDİĞİ yerleşik, yeniden seed'de geri EZİLMEZ")
    void seed_doesNotOverwriteEditedBuiltin() {
        seeder.seed();
        ScriptedTemplate edited = table.get("smoke-health");
        edited.setName("Kurumsal duman testi (elle düzenlendi)");
        edited.setScript("export default function () { /* takimin kendi surumu */ }");
        edited.setCurrentVersion("2.1.0");

        seeder.seed();

        ScriptedTemplate after = table.get("smoke-health");
        assertThat(after.getName()).isEqualTo("Kurumsal duman testi (elle düzenlendi)");
        assertThat(after.getScript()).contains("takimin kendi surumu");
        assertThat(after.getCurrentVersion()).isEqualTo("2.1.0");
    }

    @Test
    @DisplayName("Her seed edilen şablon SEED olaylı bir sürüm satırı üretir (nereden geldiği zaman çizelgesinde görünür)")
    void seed_writesSeedVersionRow() {
        seeder.seed();

        ArgumentCaptor<ScriptedTemplateVersion> cap = ArgumentCaptor.forClass(ScriptedTemplateVersion.class);
        verify(versionRepo, times(11)).save(cap.capture());

        for (ScriptedTemplateVersion v : cap.getAllValues()) {
            assertThat(v.getEventType()).isEqualTo("SEED");
            assertThat(v.getSequenceNo()).isZero();
            assertThat(v.getVersion()).isEqualTo("1.0.0");
            assertThat(v.getTeamId()).isNull();
            assertThat(v.getTemplateId()).isNotNull();
            assertThat(v.getScript()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Şablon env TANIMLARI taşınır, DEĞER taşınmaz")
    void seed_carriesEnvDefinitionsWithoutValues() {
        seeder.seed();

        ScriptedTemplate withEnv = table.get("json-health");
        assertThat(withEnv.getEnvJson()).contains("BASE_URL").doesNotContain("\"value\"");
        // Env'siz şablon boş dizi taşır (null değil) — tüketici dallanma yapmasın.
        assertThat(table.get("smoke-health").getEnvJson()).isEqualTo("[]");
    }

    @Test
    @DisplayName("Tek şablonun kaydı patlarsa kalanlar yine eklenir (eşzamanlı pod yarışı)")
    void seed_survivesSingleRowFailure() {
        when(templateRepo.save(any(ScriptedTemplate.class))).thenAnswer(inv -> {
            ScriptedTemplate t = inv.getArgument(0);
            if ("api-chain".equals(t.getBuiltinKey())) throw new RuntimeException("unique index yarisi");
            t.setId(99L);
            table.put(t.getBuiltinKey(), t);
            return t;
        });

        int added = seeder.seed();

        assertThat(added).isEqualTo(10);                    // 11 - 1
        assertThat(table).doesNotContainKey("api-chain");
    }

    @Test
    @DisplayName("Sürüm satırı yazılamazsa şablonun KENDİSİ yine kaydedilir (geçmiş yardımcı kayıttır)")
    void seed_versionFailureDoesNotLoseTemplate() {
        when(versionRepo.save(any(ScriptedTemplateVersion.class))).thenThrow(new RuntimeException("db down"));

        int added = seeder.seed();

        assertThat(added).isEqualTo(11);
        assertThat(table).hasSize(11);
    }
}
