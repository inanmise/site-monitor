package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Geri doldurma KAPSAMI, denetim kaydına yazılan izleme türleriyle senkron olmalı.
 *
 * <p>Bu tam olarak kaçtı: {@code PAGESPEED} izlemesi denetime {@code PAGESPEED_MONITOR} olarak
 * yazılıyordu ama {@code MonitorHistoryBackfillService.KIND_BY_RESOURCE} haritasına hiç
 * eklenmemişti. Sonuç sessizdi — özellik ÖNCESİ tüm PageSpeed değişiklikleri {@code audit_log}'da
 * kaldı, geçmiş ekranında o türün eski kaydı hiç görünmedi. Üstelik "koştu" nişanı ikinci koşuyu
 * engellediği için kendiliğinden de düzelemezdi.
 *
 * <p><b>Kural:</b> controller denetime bir {@code X_MONITOR} kaynağı yazıyorsa, o kaynağın geri
 * doldurma karşılığı da olmalı. {@code INVENTORY/GROUP/MAINTENANCE} bu kuralın DIŞINDA: onlar
 * {@code *_MONITOR} kaynağı yazmıyor, dolayısıyla tarama onları zaten görmez.
 *
 * <p><b>Neden KAYNAK taraması:</b> her iki harita da {@code private static} ve türetilecek bir
 * çalışma-zamanı yüzeyi yok; kapının işi küçük ve net.
 */
class BackfillScopeTest {

    private static final Path CONTROLLER =
            Path.of("src/main/java/com/sitemonitor/controller/MonitoringController.java");
    private static final Path BACKFILL =
            Path.of("src/main/java/com/sitemonitor/service/MonitorHistoryBackfillService.java");

    /** Denetime GERÇEKTEN yazılan `X_MONITOR` kaynak türleri. */
    private static Set<String> auditedResources() throws Exception {
        String src = Files.readString(CONTROLLER);
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"([A-Z]+_MONITOR)\"").matcher(src);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Bir haritanın içindeki string anahtarlar. */
    private static Set<String> mapKeys(String field) throws Exception {
        String src = Files.readString(BACKFILL);
        int i = src.indexOf(field + " = Map.of(");
        if (i < 0) return Set.of();
        String body = src.substring(i, src.indexOf(");", i));
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"([A-Za-z_]+)\"").matcher(body);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    @DisplayName("Denetime yazılan HER izleme türünün geri doldurma karşılığı var")
    void everyAuditedMonitorKindIsBackfilled() throws Exception {
        Set<String> audited = auditedResources();
        Set<String> mapped = mapKeys("KIND_BY_RESOURCE");

        assertThat(audited).as("controller taraması boş — dosya yolu ya da desen değişmiş")
                .hasSizeGreaterThanOrEqualTo(9);
        assertThat(mapped).as("KIND_BY_RESOURCE okunamadı").hasSizeGreaterThanOrEqualTo(9);

        assertThat(audited.stream().filter(r -> !mapped.contains(r)).toList())
                .as("geri doldurma kapsamı dışında kalan tür: o türün ESKİ değişiklikleri "
                        + "audit_log'da kilitli kalır ve geçmiş ekranında hiç görünmez")
                .isEmpty();
    }

    /**
     * Kapsam büyüdüğünde nişan sürümü de artmalı — yoksa "koştu" nişanı ikinci koşuyu engeller
     * ve yeni tür kapsama girmiş olmasına rağmen satırları hiç taşınmaz.
     */
    @Test
    @DisplayName("Kapsam sürümü v1'den ileride (PAGESPEED genişlemesi işlendi)")
    void scopeVersionAdvancedWithScope() throws Exception {
        String src = Files.readString(BACKFILL);
        Matcher m = Pattern.compile("BACKFILL_VERSION = ([0-9]+)").matcher(src);

        assertThat(m.find()).as("BACKFILL_VERSION bulunamadı").isTrue();
        assertThat(Integer.parseInt(m.group(1))).isGreaterThanOrEqualTo(2);
    }

    /** Künye tablosu eşlemesi de aynı listeyi izlemeli; eksikse "oluşturan" kolonu boş kalır. */
    @Test
    @DisplayName("Taşınan her türün künye tablosu eşlemesi var")
    void everyBackfilledKindHasTable() throws Exception {
        Set<String> kinds = mapKeys("KIND_BY_RESOURCE").stream()
                .map(r -> r.replace("_MONITOR", ""))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String src = Files.readString(BACKFILL);
        int i = src.indexOf("TABLE_BY_KIND = Map.of(");
        String body = src.substring(i, src.indexOf(");", i));

        assertThat(kinds.stream().filter(k -> !body.contains("MonitorHistoryService." + k + ",")).toList())
                .as("künye tablosu eşlemesi eksik tür")
                .isEmpty();
    }
}
