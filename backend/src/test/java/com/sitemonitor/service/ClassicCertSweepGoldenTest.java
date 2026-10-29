package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod Sürüm 3-4 P0 — KLASİK SERTİFİKA TARAMASI ALTIN KAYITLARI (tasarım R3-4 §9.1; L-1 "kapalı ≡ 20.170.0", L-3 "aynı
 * girdi"). {@link CertSweepGoldenHarness} DEĞİŞMEMİŞ {@code SchedulerService.runCheckForDomains} gövdesini dokuz senaryoyla
 * (sağlıklı saatlik, şüpheli toplu kesinti, açık kesintide küçük bayat tur, bitiş uyarısı kapalı, kilit kaybı, toplama
 * sırasında kapanış, kayıtta istisna, kontrolde istisna, tam tur) koşturur; toplamadan sonraki her şey —
 * {@code saveResult} haritaları sırasıyla, önbellek boşaltma, {@code onRun} bağımsız değişkenleri, {@code processResults} /
 * {@code resolveVerifiedStaleCertAlerts} listeleri, {@code recordScanRun} bağımsız değişkenleri, kilit alma / bırakma,
 * {@code lastRun*} ve günlük satırları — {@code src/test/resources/r34-golden/<senaryo>.json} ile karşılaştırılır.
 *
 * <p><b>Neden:</b> paket B toplamayı {@code gatherCertResults} olarak AYIRIR ve önüne parçalı yolu koyar. Ayırma SAF olmalıdır:
 * bu kayıtlar 20.170.0'ın ({@code b78e5932}) DEĞİŞMEMİŞ kodundan alındı; B'nin dalında anahtar kapalıyken (ve koordinatör yokken)
 * birebir aynı kalmalıdır. B'nin {@code CertChunkRoundTripTest}'i aynı senaryoları parçalı yoldan koşturup
 * {@code check_order} dışındaki bölümleri bu dosyalarla karşılaştırır.
 *
 * <p><b>Yeniden kayıt YASAK</b> (yalnız bilerek ve gerekçesiyle davranış değişikliğinde):
 * {@code mvn -o test -Dtest=ClassicCertSweepGoldenTest -Dr34.writeGolden=true}.
 */
class ClassicCertSweepGoldenTest {

    static final boolean WRITE = Boolean.getBoolean("r34.writeGolden");

    static Stream<String> scenarioNames() {
        return CertSweepGoldenHarness.scenarios().stream().map(CertSweepGoldenHarness.Scenario::name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarioNames")
    @DisplayName("klasik tur dökümü altın kayıtla birebir aynı")
    void golden(String name) throws Exception {
        CertSweepGoldenHarness h = new CertSweepGoldenHarness(CertSweepGoldenHarness.scenario(name));
        h.run();
        String actual = h.transcript();
        Path file = CertSweepGoldenHarness.GOLDEN.resolve(name + ".json");
        if (WRITE) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, actual, StandardCharsets.UTF_8);
            return;
        }
        assertThat(file).as("altın kayıt yok — yalnız ilk kayıtta -Dr34.writeGolden=true").exists();
        String expected = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("kayıtlar boş değil ve beklenen olayları taşıyor (her senaryonun kanıtı dökümde)")
    void goldensCarryTheirEvidence() throws Exception {
        if (WRITE) return;
        Map<String, List<String>> must = Map.of(
                "hourly", List.of("\"call\": \"processResults\"", "\"call\": \"evictAllCaches\"", "\"suspected\": false",
                        "\"networkErrors\": 2", "SIM-OK", "\"unresolvable_host\": true", "\"total\": 10",
                        "\"elapsed_ms\": 412", "2019-11-30T00:00:00", "Certificate check started"),
                "suspected-outage", List.of("\"suspected\": true", "\"networkErrors\": 4",
                        "\"call\": \"resolveVerifiedStaleCertAlerts\"", "rate=0.67"),
                "ongoing-outage-stale", List.of("\"call\": \"resolveVerifiedStaleCertAlerts\"", "\"kind\": \"stale\"",
                        "\"call\": \"lock.acquire\""),
                "expiry-alerts-off", List.of("\"call\": \"resolveVerifiedStaleCertAlerts\"", "\"call\": \"evictAllCaches\""),
                "lock-lost", List.of("cert-check kilidi kaybedildi", "\"lock_held_at_end\": false"),
                "shutdown-during-gather", List.of("Kapanış başladı", "runId=<run>, 2 / 5 sonuç",
                        "\"startup_check_stopped\": true"),
                "save-throws", List.of("\"threw\": true", "IllegalStateException: kayıt yazılamadı: w3.example.com",
                        "\"call\": \"recordScanRun\""),
                "check-throws", List.of("CompletionException", "denetleyici çöktü: t3", "\"call\": \"recordScanRun\""),
                "full", List.of("\"networkErrors\": 3", "\"suspected\": false", "\"total\": 12",
                        "\"call\": \"processResults\""));
        for (String name : scenarioNames().toList()) {
            String g = Files.readString(CertSweepGoldenHarness.GOLDEN.resolve(name + ".json"), StandardCharsets.UTF_8);
            assertThat(must).as("kanıt listesinde: " + name).containsKey(name);
            for (String s : must.get(name)) assertThat(g).as(name + " → " + s).contains(s);
        }
        // Erken dönüşler hiçbir şey kaydetmez / yazmaz (= klasik)
        for (String name : List.of("lock-lost", "shutdown-during-gather", "check-throws")) {
            String g = Files.readString(CertSweepGoldenHarness.GOLDEN.resolve(name + ".json"), StandardCharsets.UTF_8);
            assertThat(g).as(name).doesNotContain("\"call\": \"saveResult\"").doesNotContain("\"call\": \"onRun\"");
        }
        for (String name : List.of("lock-lost", "shutdown-during-gather")) {
            String g = Files.readString(CertSweepGoldenHarness.GOLDEN.resolve(name + ".json"), StandardCharsets.UTF_8);
            assertThat(g).as(name + ": tarama durumu yazılmaz").doesNotContain("\"call\": \"recordScanRun\"");
        }
    }

    @Test
    @DisplayName("döküm DUYARLI: tek sonuç alanındaki fark (90 → 91 gün) ve sıra farkı kaydı değiştirir — karşılaştırma boş değil")
    void goldenDetectsDrift() throws Exception {
        if (WRITE) return;
        String expected = Files.readString(CertSweepGoldenHarness.GOLDEN.resolve("hourly.json"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");

        CertSweepGoldenHarness.Scenario base = CertSweepGoldenHarness.scenario("hourly");
        java.util.Map<String, CertSweepGoldenHarness.Outcome> outcomes = new java.util.LinkedHashMap<>(base.outcomes());
        outcomes.put("a.example.com", CertSweepGoldenHarness.Outcome.ok(
                CertSweepGoldenHarness.valid("a.example.com", 91, true, 412L)));
        CertSweepGoldenHarness.Scenario drifted = new CertSweepGoldenHarness.Scenario(base.name(), base.kind(),
                base.evictCaches(), base.preHeld(), base.rows(), outcomes, base.ongoingOutage(),
                base.expiryAlertsEnabled(), base.saveThrowsFor());
        CertSweepGoldenHarness h = new CertSweepGoldenHarness(drifted);
        h.run();
        assertThat(h.transcript()).isNotEqualTo(expected).contains("\"days_remaining\": 91");

        // Karar listesinin SIRASI kanıttır: iki alan adının yeri değişirse (aynı içerik) karar dökümü değişir
        CertSweepGoldenHarness same = new CertSweepGoldenHarness(base);
        same.run();
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>(base.rows());
        java.util.Collections.swap(rows, 0, 1);
        CertSweepGoldenHarness.Scenario reordered = new CertSweepGoldenHarness.Scenario(base.name(), base.kind(),
                base.evictCaches(), base.preHeld(), rows, base.outcomes(), base.ongoingOutage(),
                base.expiryAlertsEnabled(), base.saveThrowsFor());
        CertSweepGoldenHarness r = new CertSweepGoldenHarness(reordered);
        r.run();
        assertThat(r.transcript("calls")).isNotEqualTo(same.transcript("calls"));
        assertThat(same.transcript()).as("aynı senaryo iki kez → aynı döküm (kararlı)").isEqualTo(expected);
    }
}
