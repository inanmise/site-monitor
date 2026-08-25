package com.sitemonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yerleşik k6 şablon kataloğu ({@code scripted-templates.json}) yazım kuralları.
 *
 * <p><b>Nereden geldi:</b> bu kurallar 2026-08-20'ye kadar frontend'de
 * {@code src/test/scriptedTemplates.test.js} içinde yaşıyordu. Şablonlar DB'ye taşınırken
 * (K1) katalogun kaynağı backend'e geçti; kuralların da onunla birlikte gelmesi ŞART, yoksa
 * 11 şablon derleme-anı kapısını kaybederdi.
 *
 * <p><b>İki ayrı yerde yaşarlar ve bu bilinçlidir:</b> burada KATALOG (11 küratörlü şablon,
 * derleme anında), {@code ScriptedTemplateRules} içinde ise KULLANICI şablonları (kaydetme
 * anında). Katalog daha sert: küratörlü set kusursuz olmak zorunda, kullanıcı şablonunda
 * bazı kurallar uyarıya iner.
 */
class ScriptedTemplateCatalogTest {

    private static JsonNode catalog;
    private static List<JsonNode> templates;

    /** Şablon başına kod okunabilirliği için: script gövdesini bir kez çıkar. */
    private static String script(JsonNode t) { return t.path("script").asText(""); }
    private static String key(JsonNode t)    { return t.path("builtinKey").asText(""); }

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = ScriptedTemplateCatalogTest.class.getClassLoader()
                .getResourceAsStream("scripted-templates.json")) {
            assertThat(in).as("scripted-templates.json classpath'te bulunmalı").isNotNull();
            catalog = new ObjectMapper().readTree(in);
        }
        templates = new ArrayList<>();
        catalog.path("templates").forEach(templates::add);
    }

    /**
     * Katalog 11'den 100'e büyüdü (10 kategori × 10 şablon). TAM küme pinlemek artık yanlış kapı
     * olurdu: her yeni şablon testi düşürür ama hiçbir şeyi kanıtlamaz. Korunması gereken tek
     * şey ilk 11'in KAYBOLMAMASI — kayıtlı monitörlerin {@code template} kolonunda hâlâ
     * {@code tpl:<builtinKey>} dizeleri duruyor ve o anahtar silinirse monitör şablonunu
     * çözemez hâle gelir.
     */
    private static final List<String> LEGACY_KEYS = List.of(
            "smoke-health", "json-health", "oauth2-client-credentials", "api-chain",
            "multi-step-journey", "sla-threshold", "soap-xml", "oidc-keycloak",
            "form-login", "mtls-client-cert", "graphql");

    @Test
    @DisplayName("Katalog: ilk 11 yerleşik anahtar ASLA kaybolmaz (eski tpl:<key> referansları)")
    void catalog_keepsLegacyKeys() {
        assertThat(templates.stream().map(ScriptedTemplateCatalogTest::key).toList())
                .containsAll(LEGACY_KEYS);
        assertThat(catalog.path("seedVersion").asInt()).isPositive();
    }

    @Test
    @DisplayName("Katalog: her şablonun kategorisi BİLİNEN anahtarlardan biri")
    void catalog_categoriesAreKnown() {
        for (JsonNode t : templates) {
            String c = t.path("category").asText("");
            assertThat(ScriptedTemplateCategories.isKnown(c))
                    .as("%s: bilinmeyen kategori '%s'", key(t), c).isTrue();
        }
    }

    @Test
    @DisplayName("Katalog: 10 kategorinin HER BİRİNDE tam 10 şablon var")
    void catalog_tenPerCategory() {
        java.util.Map<String, Long> byCat = templates.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        t -> t.path("category").asText(""), java.util.stream.Collectors.counting()));
        for (String c : ScriptedTemplateCategories.ORDER) {
            assertThat(byCat.getOrDefault(c, 0L)).as("kategori '%s' şablon sayısı", c).isEqualTo(10L);
        }
        assertThat(templates).as("toplam şablon").hasSize(100);
    }

    @Test
    @DisplayName("Katalog KENDİ güvenlik kurallarımızı geçer (yerleşikler kaydedilemez olamaz)")
    void catalog_passesSafetyRules() {
        // Kullanıcı bir yerleşiği düzenleyip kaydettiğinde ScriptedSafetyRules çalışır. Katalog
        // kendi kapımıza takılırsa kullanıcı o şablonu bir daha kaydedemez — sessiz bir kilit.
        for (JsonNode t : templates) {
            var d = ScriptedSafetyRules.check(script(t));
            assertThat(d.blocked()).as("%s: güvenlik kuralına takıldı → %s", key(t), d.blocking()).isFalse();
        }
    }

    @Test
    @DisplayName("Kural 1: her şablonda ad/açıklama/kullanım metni dolu, env dizi, script'te 'export default function' var")
    void rule1_requiredFields() {
        for (JsonNode t : templates) {
            String k = key(t);
            assertThat(k).as("builtinKey").isNotBlank();
            assertThat(t.path("name").asText("")).as("%s: name", k).isNotBlank();
            assertThat(t.path("description").asText("")).as("%s: description", k).isNotBlank();
            assertThat(t.path("whenToUse").asText("")).as("%s: whenToUse", k).isNotBlank();
            assertThat(t.path("env").isArray()).as("%s: env dizi olmalı", k).isTrue();
            assertThat(script(t)).as("%s: script", k).contains("export default function");
        }
    }

    @Test
    @DisplayName("Kural 2: builtinKey'ler BENZERSİZ ve slug biçiminde (eski tpl:<id> çözümünün dayanağı)")
    void rule2_keysUniqueAndSlug() {
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode t : templates) {
            String k = key(t);
            assertThat(seen.add(k)).as("builtinKey tekrar etti: %s", k).isTrue();
            // Slug şartı KRİTİK: seçici `tpl:<token>` üretiyor ve token ya builtinKey ya sayısal
            // id. Bir builtinKey tamamen rakam olsaydı iki dal çakışır, eski monitörler yanlış
            // şablona çözülürdü. Bu regex iki dalı provably ayrık tutar.
            assertThat(k).as("%s: slug olmalı", k).matches("^[a-z][a-z0-9._-]*$");
            assertThat(k).as("%s: tamamen rakam OLAMAZ", k).doesNotMatch("^[0-9]+$");
        }
    }

    @Test
    @DisplayName("Kural 3: script'teki her __ENV.X şablonun env tanımlarında var")
    void rule3_envReferencesDefined() {
        Pattern env = Pattern.compile("__ENV\\.([A-Za-z_$][\\w$]*)");
        for (JsonNode t : templates) {
            List<String> defined = new ArrayList<>();
            t.path("env").forEach(e -> defined.add(e.path("name").asText()));
            Matcher m = env.matcher(script(t));
            while (m.find()) {
                assertThat(defined).as("%s: __ENV.%s tanımsız", key(t), m.group(1)).contains(m.group(1));
            }
        }
    }

    @Test
    @DisplayName("Kural 4: vus/iterations/stages TANIMLANMAZ — motor --vus 1 --iterations 1 ile eziyor")
    void rule4_noEngineOverrides() {
        for (JsonNode t : templates) {
            assertThat(script(t)).as("%s", key(t))
                    .doesNotMatch("(?s).*\\bvus\\s*:.*")
                    .doesNotMatch("(?s).*\\biterations\\s*:.*")
                    .doesNotMatch("(?s).*\\bstages\\s*:.*");
        }
    }

    @Test
    @DisplayName("Kural 5: her isteğe AÇIK timeout verilir (istek sayısı ≤ timeout sayısı)")
    void rule5_everyRequestHasTimeout() {
        Pattern req = Pattern.compile("http\\.(get|post|put|del|patch)\\(");
        Pattern to  = Pattern.compile("timeout:\\s*'\\d+s'");
        for (JsonNode t : templates) {
            long requests = req.matcher(script(t)).results().count();
            long timeouts = to.matcher(script(t)).results().count();
            assertThat(timeouts).as("%s: %d istek, %d timeout", key(t), requests, timeouts)
                    .isGreaterThanOrEqualTo(requests);
        }
    }

    @Test
    @DisplayName("Kural 8: k6 0.49 (Babel 6) ayrıştıramadığı sözdizimi YOK — ?. / ?? / {...}")
    void rule8_noModernSyntax() {
        for (JsonNode t : templates) {
            assertThat(script(t)).as("%s: optional chaining", key(t)).doesNotContain("?.");
            assertThat(script(t)).as("%s: nullish coalescing", key(t)).doesNotContain("??");
            assertThat(script(t)).as("%s: object spread", key(t)).doesNotMatch("(?s).*\\{\\s*\\.\\.\\..*");
        }
    }

    @Test
    @DisplayName("Kural 6+7: smoke-health kendi kendine yeter (env yok, __ENV yok) ve timeout 60 sn ALTINDA")
    void rule6and7_smokeHealthSpecifics() {
        JsonNode smoke = templates.stream().filter(t -> "smoke-health".equals(key(t))).findFirst().orElseThrow();
        assertThat(smoke.path("env")).as("smoke-health env BOŞ olmalı — kurulum gerektirmeden çalışır").isEmpty();
        assertThat(script(smoke)).doesNotContain("__ENV");
        // Bu iki deger scripted-templates.json'daki CALISAN smoke script'ini yansitir; sablon
        // gercekten o adrese GET atiyor. Kurumsal alan adi burada BILINCLI durur -- hedefi
        // degistirmek urun karari (bkz. kimlik-tarama muafiyet listesi).
        assertThat(script(smoke)).contains("https://www.akbank.com").contains("indexOf('Akbank')");

        // Süreç timeout'u varsayılan 60 sn; istek timeout'u ondan KISA olmalı ki istek kendi
        // kendine düşsün ve k6 sebebi yazabilsin (aksi halde ekranda sebepsiz "Süre aşımı" kalır).
        Matcher m = Pattern.compile("timeout:\\s*'(\\d+)s'").matcher(script(smoke));
        assertThat(m.find()).isTrue();
        assertThat(Integer.parseInt(m.group(1))).isLessThan(60);
    }

    @Test
    @DisplayName("Güvenlik: hiçbir yerleşik şablon sabit-kodlu gizli değer taşımaz")
    void catalog_hasNoHardcodedSecrets() {
        // Kütüphaneye giren her script'e kaydetme anında da uygulanan denetimin katalog karşılığı.
        for (JsonNode t : templates) {
            assertThat(ScriptedCheckerService.scanHardcodedSecrets(script(t)))
                    .as("%s: sabit-kodlu secret", key(t)).isEmpty();
        }
    }

    @Test
    @DisplayName("env tanımları DEĞER taşımaz — şablon yalnız ad/secret bayrağı bildirir")
    void catalog_envCarriesNoValues() {
        for (JsonNode t : templates) {
            for (JsonNode e : t.path("env")) {
                assertThat(e.has("value")).as("%s: env '%s' değer taşıyor", key(t), e.path("name").asText()).isFalse();
                assertThat(e.path("name").asText("")).isNotBlank();
            }
        }
    }
}
