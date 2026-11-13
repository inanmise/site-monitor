package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sağlık yüzdesi kuralı (hata taraması 2026-11-13) — temiz / değerlendirilen, bir ondalığa AŞAĞI kesilir: %100 yalnız
 * gerçekten hepsi temizken (yuvarlama 1999 / 2000'i "%100" yazıyor, tek sorunlu sertifikayı saklıyordu). İstemcinin takım
 * karnesi ({@code statsInsights.healthPctOf}) AYNI tabloyu okur ({@code certHealthPct.test.js}).
 */
class CertHealthCountsTest {

    /** { temiz, değerlendirilen, beklenen % (null = bilinmiyor) }. */
    static final String[][] HEALTH_TABLE = {
            { "2000", "2000", "100.0" },
            { "1999", "2000", "99.9" },
            { "19999", "20000", "99.9" },
            { "3", "7", "42.8" },
            { "1", "3", "33.3" },
            { "2", "3", "66.6" },
            { "7", "10", "70.0" },
            { "0", "5", "0.0" },
            { "0", "0", null },
            { "999", "1000", "99.9" },
            { "1", "1", "100.0" },
    };

    @Test
    @DisplayName("tablo: aşağı kesme, değerlendirilen 0 → null, tam bölünen oran tam")
    void table() {
        for (String[] r : HEALTH_TABLE) {
            Double got = CertHealthCounts.healthPct(Integer.parseInt(r[0]), Integer.parseInt(r[1]));
            if (r[2] == null) assertThat(got).as("%s/%s", r[0], r[1]).isNull();
            else assertThat(got).as("%s/%s", r[0], r[1]).isEqualTo(Double.parseDouble(r[2]));
        }
    }

    @Test
    @DisplayName("sayaç: 1999 temiz + 1 dolmuş → %99,9 (yuvarlama %100 yazıyordu)")
    void counterUsesFloor() {
        CertHealthCounts c = new CertHealthCounts();
        for (int i = 0; i < 1999; i++) c.add(true, "valid", 200);
        c.add(true, "valid", -1);
        assertThat(c.healthPct()).isEqualTo(99.9);
        assertThat(c.toMap()).containsEntry("health_pct", 99.9);
    }

    @Test
    @DisplayName("büyük paydalarda kayan nokta: tam bölünen her oran tam sayıya iner (aşağı kesme bir alt değere düşmez)")
    void exactRatiosStayExact() {
        for (int ev = 1; ev <= 5000; ev++) {
            for (int ok : new int[]{0, ev / 4, ev / 2, ev}) {
                if ((1000L * ok) % ev != 0) continue;
                assertThat(CertHealthCounts.healthPct(ok, ev)).as("%d/%d", ok, ev).isEqualTo((1000L * ok / ev) / 10.0);
            }
        }
    }
}
