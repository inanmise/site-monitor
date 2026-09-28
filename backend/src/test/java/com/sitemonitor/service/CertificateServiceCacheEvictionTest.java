package com.sitemonitor.service;

import com.sitemonitor.controller.AdminController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI — sertifika/envanter değişikliğinden sonra {@link CertificateService#evictAllCaches()} envanterden TÜRETİLEN
 * her kullanıcı önbelleğini boşaltır (2026-09-27).
 *
 * <p>Vaka: Genel Bakış kartından yenileme planı kaydedilince "planlandı" toast'ı çıkıyor ama kart hâlâ "Yenilemeyi
 * planla" gösteriyordu — {@code card-extras} (plan çipinin kaynağı, 60 sn) bu listede YOKTU; arayüzün kayıttan hemen
 * sonraki yeniden çekmesi eski yanıtı alıyordu. Kural: eşik değişikliğinde boşaltılan türetilmiş sertifika
 * önbellekleri ({@link ThresholdPreviewService#DERIVED_CACHES}) envanter değişikliğinde de boşaltılır —
 * {@code today-monitors} hariç (izlemelerden türer, sertifika envanterinden değil).
 */
class CertificateServiceCacheEvictionTest {

    @Test
    @DisplayName("evictAllCaches türetilmiş her sertifika önbelleğini (card-extras dâhil) boşaltır")
    void evictAllCachesCoversDerivedCertificateCaches() throws Exception {
        Caching caching = CertificateService.class.getMethod("evictAllCaches").getAnnotation(Caching.class);
        assertThat(caching).as("evictAllCaches @Caching ile işaretli olmalı").isNotNull();
        Set<String> evicted = new HashSet<>();
        for (CacheEvict e : caching.evict()) {
            assertThat(e.allEntries()).as(Arrays.toString(e.value()) + " allEntries=true olmalı").isTrue();
            evicted.addAll(Arrays.asList(e.value()));
            evicted.addAll(Arrays.asList(e.cacheNames()));
        }
        List<String> required = ThresholdPreviewService.DERIVED_CACHES.stream()
                .filter(n -> !n.equals("today-monitors"))
                .toList();
        assertThat(required).as("kaynak liste boş olmamalı (desen bozuk)").contains("card-extras");
        assertThat(evicted).as("envanter değişince boşalmayan türetilmiş önbellek — ekran 60–300 sn eski veri gösterir")
                .containsAll(required);
    }

    /**
     * Sınıf kapısı (2026-09-28, kullanıcı: "yeni eklenen alan adının kartında sağlık/açık alarm/sorumlu kişi bir süre
     * boş kalıyor"): envanter uçları ({@code AdminController} ekle/güncelle/sil/geri yükle, içgörü, otomatik boşaltma)
     * kendi sabit {@code @CacheEvict} listelerini taşıyordu ve HİÇBİRİ {@code card-extras}'ı içermiyordu. Kural: kaynakta
     * {@code "cert-latest"}'i boşaltan HER {@code @CacheEvict} listesi {@code "card-extras"}'ı da boşaltır.
     *
     * <p>F3 (bug regresyon 2026-09-28): aynı listeler {@code "domain-team-names"}'i de boşaltır — domain→SY takım adı
     * haritası envanterden türer ({@link CertificateService#domainTeamNameMap}) ve {@code evictAllCaches} onu boşaltsa da
     * uçların sabit listelerinin HİÇBİRİ içermiyordu: aktarım/ekleme/içe aktarma sonrası izleme ekranlarında takım adı bayat.
     */
    @Test
    @DisplayName("cert-latest'i boşaltan her @CacheEvict listesi card-extras'ı ve domain-team-names'i de boşaltır (kaynak taraması)")
    void everyCertLatestEvictAlsoEvictsCardExtras() throws Exception {
        java.nio.file.Path root = mainRoot();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("@CacheEvict\\(value = \\{([^}]*)\\}");
        List<String> offenders = new java.util.ArrayList<>();
        List<String> teamNameOffenders = new java.util.ArrayList<>();
        int seen = 0;
        try (var files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String src = java.nio.file.Files.readString(f);
                var m = p.matcher(src);
                while (m.find()) {
                    if (!m.group(1).contains("\"cert-latest\"")) continue;
                    seen++;
                    if (!m.group(1).contains("\"card-extras\"")) offenders.add(f.getFileName() + ": " + m.group(0));
                    if (!m.group(1).contains("\"domain-team-names\"")) teamNameOffenders.add(f.getFileName() + ": " + m.group(0));
                }
            }
        }
        assertThat(seen).as("tarama hiçbir @CacheEvict bulmadı — desen bozuk").isGreaterThan(3);
        assertThat(offenders).as("card-extras eksik — Genel Bakış kart ekleri 60 sn eski kalır").isEmpty();
        assertThat(teamNameOffenders).as("domain-team-names eksik — izleme ekranlarında SY takım adı 300 sn eski kalır")
                .isEmpty();
    }

    /**
     * Uç kapısı (F3, bug regresyon 2026-09-28): yukarıdaki kural yalnız VAR OLAN listeleri denetler — hiç
     * {@code @CacheEvict} taşımayan envanter uçları tarama dışında kalıyordu. Yukarıdaki javadoc "geri yükle" dese de
     * {@code restoreInventory}'de eviction YOKTU; SY/UG aktarımı ve takım varlık taşıma da öyle. Sonuç: 409
     * DOMAIN_EXISTS "çöp kutusundan geri yükle" akışından sonra alan adı Genel Bakış'ta cert-latest TTL'i (300 sn)
     * boyunca görünmüyordu. Kural: {@code AdminController}'da gövdesi envanteri YAZAN her public uç sertifika
     * önbelleklerini ({@code domain-team-names} dâhil) boşaltır; grup yeniden adlandırma
     * ({@code MonitoringController#renameGroup} — "cert" türü envanteri yeniden yazar) {@code evictAllCaches()} çağırır.
     */
    @Test
    @DisplayName("envanteri yazan her uç önbellek boşaltır: geri yükle / SY-UG aktarımı / takım taşıma / grup yeniden adlandırma")
    void inventoryMutatingEndpointsEvict() throws Exception {
        Map<String, String> admin = publicEndpointBodies(sourceOf("controller/AdminController.java"));
        java.util.regex.Pattern write = java.util.regex.Pattern.compile(
                "inventoryRepo\\.(save|saveAll|delete|deleteById|deleteAll)\\(|teamAdminService\\.moveAll\\(");
        List<String> writers = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (var e : admin.entrySet()) {
            if (!write.matcher(e.getValue()).find()) continue;
            writers.add(e.getKey());
            boolean found = false;
            for (Method m : AdminController.class.getMethods()) {
                if (!m.getName().equals(e.getKey())) continue;
                found = true;
                CacheEvict ce = m.getAnnotation(CacheEvict.class);
                Set<String> names = ce == null ? Set.of() : new HashSet<>(Arrays.asList(ce.value()));
                if (ce == null || !ce.allEntries()
                        || !names.containsAll(List.of("cert-latest", "card-extras", "domain-team-names"))) {
                    offenders.add(e.getKey());
                }
            }
            if (!found) offenders.add(e.getKey() + " (public metot yansımada yok)");
        }
        // Tarama boş geçmesin: bilinen yazarların HEPSİ bulunmalı (desen bozulursa kapı sessizce yeşil kalmasın).
        assertThat(writers).as("envanteri yazan uç taraması — desen bozuk").contains(
                "addInventory", "updateInventory", "deleteInventory", "bulkInventoryAction", "restoreInventory",
                "purgeInventory", "purgeAllDeleted", "transferInventory", "transferInventoryUg", "teamMove");
        assertThat(offenders).as("envanteri yazıp önbellek boşaltmayan uç — Genel Bakış 300 sn'ye kadar eski kalır")
                .isEmpty();

        String rename = publicEndpointBodies(sourceOf("controller/MonitoringController.java")).get("renameGroup");
        assertThat(rename).as("MonitoringController#renameGroup bulunamadı — desen bozuk").isNotNull();
        assertThat(rename).as("grup yeniden adlandırma (cert türü envanteri yazar) evictAllCaches() çağırmalı")
                .contains("certificateService.evictAllCaches();");
    }

    private static java.nio.file.Path mainRoot() {
        return java.nio.file.Files.isDirectory(java.nio.file.Path.of("src/main/java"))
                ? java.nio.file.Path.of("src/main/java") : java.nio.file.Path.of("backend/src/main/java");
    }

    private static String sourceOf(String relative) throws java.io.IOException {
        return java.nio.file.Files.readString(mainRoot().resolve("com/sitemonitor/" + relative));
    }

    /** {@code public ResponseEntity<…> ad(} bildirimi → gövdesi (4 boşluk girintili ilk kapanış {@code }}'e kadar). */
    private static Map<String, String> publicEndpointBodies(String src) {
        Map<String, String> out = new LinkedHashMap<>();
        var decl = java.util.regex.Pattern.compile("public\\s+ResponseEntity<.*?>\\s+(\\w+)\\s*\\(").matcher(src);
        var end = java.util.regex.Pattern.compile("\\R    \\}\\R");
        while (decl.find()) {
            var e = end.matcher(src);
            int stop = e.find(decl.end()) ? e.end() : src.length();
            out.putIfAbsent(decl.group(1), src.substring(decl.start(), stop));
        }
        return out;
    }
}
