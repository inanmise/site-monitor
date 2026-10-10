package com.sitemonitor.service.report.executive;

import com.sitemonitor.service.crypto.CryptoInventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.report.executive.ExecTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kripto hazırlığı bölümü — KURUM GENELİ kaynak (kapsam null), durum eşikleri (doğruluk tablosu), paydalar (verisi olmayan
 * payı sulandırmaz), tablolar ve rapor anı damgası.
 */
class CryptoReadinessSectionTest {

    private final CryptoInventoryService crypto = mock(CryptoInventoryService.class);
    private final CryptoReadinessSection section = new CryptoReadinessSection(crypto);

    /** Kategori sayılarıyla özet ({@code CryptoInventoryService.summary} biçimi). */
    static Map<String, Object> summary(int broken, int legacy, int modern, int pqcReady, int unknown, int checked) {
        int total = broken + legacy + modern + pqcReady + unknown;
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total", total);
        s.put("checked", checked);
        s.put("unchecked", total - checked);
        Map<String, Object> cat = new LinkedHashMap<>();
        cat.put("BROKEN", broken);
        cat.put("LEGACY", legacy);
        cat.put("MODERN", modern);
        cat.put("PQC_READY", pqcReady);
        cat.put("UNKNOWN", unknown);
        s.put("by_category", cat);
        s.put("by_pqc", Map.of("VULNERABLE", broken + legacy + modern, "HYBRID", pqcReady, "PQC", 0, "UNKNOWN", unknown));
        s.put("by_band", Map.of("P1", broken, "P2", legacy, "P3", modern, "P4", 0, "DONE", pqcReady));
        s.put("remnants", Map.of("md5_leaf", 0, "sha1_leaf", broken > 0 ? 1 : 0, "md5_intermediate", 0,
                "sha1_intermediate", 0, "sha1_root", 0, "chains_examined", checked, "affected", broken > 0 ? 1 : 0));
        s.put("legacy_reissue", legacy > 0 ? 1 : 0);
        s.put("vulnerable_expiring_90d", 0);
        s.put("generated_at", "2026-10-10T08:58:30");
        s.put("top", List.of());
        return s;
    }

    @ParameterizedTest(name = "BROKEN={0} LEGACY={1} MODERN={2} UNKNOWN={3} checked={4} → {5}")
    @CsvSource({
            "0, 0, 0, 0, 0, no_data",       // envanter boş
            "0, 0, 0, 3, 0, no_data",       // anahtar bilgisi hiç okunmadı
            "1, 0, 9, 0, 10, critical",     // bugün zayıf
            "0, 1, 3, 0, 4, attention",     // LEGACY payı %25 (eşik)
            "0, 1, 3, 4, 4, attention",     // UNKNOWN paydaya girmez → yine %25
            "0, 1, 4, 0, 5, ok",            // %20
            "0, 0, 5, 0, 5, ok",
    })
    @DisplayName("DURUM EŞİKLERİ: BROKEN → kritik; LEGACY payı ≥ %25 (veri olmayan hariç) → takip; veri yok → VERİ YOK")
    void statusTruthTable(int broken, int legacy, int modern, int unknown, int checked, String expected) {
        SectionResult r = section.evaluate(ctx(), summary(broken, legacy, modern, 0, unknown, checked));
        assertThat(r.status()).isEqualTo(expected);
    }

    @Test
    @DisplayName("compute kurum geneli özeti ister (kapsam null, ilk 10); rapor anı = özetin üretim anı")
    void computeUsesGlobalScope() {
        when(crypto.summary(isNull(), eq(CryptoReadinessSection.TOP_N))).thenReturn(summary(0, 0, 2, 0, 0, 2));
        SectionResult r = section.compute(ctx());
        verify(crypto).summary(isNull(), eq(10));
        assertThat(r.snapshot()).isTrue();
        assertThat(r.asOf()).isEqualTo("2026-10-10T08:58:30");
        assertThat(r.status()).isEqualTo(SectionResult.OK);
        assertThat(verdictCodes(r)).containsExactly("NO_BROKEN", "QUANTUM");
    }

    @Test
    @DisplayName("göstergeler, kategori / bant tabloları ve öncelik listesi (≤ 10 satır, toplam = geçiş bekleyen)")
    void kpisAndTables() {
        Map<String, Object> s = summary(2, 5, 3, 1, 2, 11);
        List<Map<String, Object>> top = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("domain", "d" + i + ".example.com");
            t.put("team_name", "Takım A");
            t.put("team_id", 1L);
            t.put("tier", i == 0 ? 1 : null);
            t.put("category", i < 2 ? "BROKEN" : "LEGACY");
            t.put("band", i < 2 ? "P1" : "P2");
            t.put("score", 90 - i);
            t.put("days_remaining", 10 + i);
            top.add(t);
        }
        s.put("top", top);
        SectionResult r = section.evaluate(ctx(), s);

        assertThat(r.status()).isEqualTo(SectionResult.CRITICAL);
        assertThat(verdictCodes(r)).containsExactly("BROKEN", "QUANTUM", "LEGACY", "REMNANTS", "P1");
        assertThat(kpi(r, "broken").value()).isEqualTo(2);
        // kuantuma açık: (2 + 5 + 3) / (13 − 2 bilinmeyen) = %90,9
        assertThat(kpi(r, "vulnerable_share").value()).isEqualTo(90.9);
        assertThat(kpi(r, "vulnerable_share").hintParams()).containsExactly(11, 10, 1);
        assertThat(kpi(r, "legacy").value()).isEqualTo(5);
        assertThat(kpi(r, "legacy").tone()).isEqualTo(SectionResult.T_WARN);         // 5 / 11 = %45,5
        assertThat(kpi(r, "remnants").value()).isEqualTo(1);
        assertThat(kpi(r, "p1").value()).isEqualTo(2);
        assertThat(kpi(r, "p1").hintParams()).containsExactly(5, 3, 0);
        assertThat(kpi(r, "unknown").value()).isEqualTo(2);

        assertThat(table(r, "categories").rows()).extracting(m -> m.get("category"))
                .containsExactly("BROKEN", "LEGACY", "MODERN", "PQC_READY", "UNKNOWN");
        assertThat(table(r, "categories").rows().get(0)).containsEntry("endpoints", 2).containsEntry("state", SectionResult.T_BAD);
        assertThat(table(r, "bands").rows()).extracting(m -> m.get("band")).containsExactly("P1", "P2", "P3", "P4", "DONE");

        SectionResult.Table mig = table(r, "migration");
        assertThat(mig.rows()).hasSize(CryptoReadinessSection.TOP_N);
        assertThat(mig.total()).isEqualTo(10);                                         // P1 + P2 + P3 + P4
        assertThat(mig.rows().get(0)).containsEntry("domain", "d0.example.com").containsEntry("team", "Takım A")
                .containsEntry("tier", 1).containsEntry("category", "BROKEN").containsEntry("band", "P1")
                .containsEntry("score", 90).containsEntry("days", 10);
        assertThat(r.notes()).extracting(SectionResult.Note::code).containsExactly("ASOF", "METHOD", "KEX");
    }

    @Test
    @DisplayName("veri yok: göstergeler boş değer (sıfır değil), kategori tablosu boş; boş özet nesnesi de çökmez")
    void noDataShapes() {
        SectionResult r = section.evaluate(ctx(), summary(0, 0, 0, 0, 0, 0));
        assertThat(verdictCodes(r)).containsExactly("NO_DATA");
        assertThat(kpi(r, "broken").value()).isNull();
        assertThat(table(r, "categories").rows()).isEmpty();
        SectionResult n = section.evaluate(ctx(), null);
        assertThat(n.status()).isEqualTo(SectionResult.NO_DATA);
        assertThat(n.asOf()).isEqualTo("2026-10-10T09:00:00");
    }
}
