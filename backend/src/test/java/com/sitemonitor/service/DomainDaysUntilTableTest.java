package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alan adı kalan gün kuralı — {@link DomainCheckerService#daysUntil(String, Instant)} doğruluk tablosu (2026-11-15, inceleme
 * LOW 2). Kartın canlı kalan günü ({@code frontend/src/components/domain/domainCardModel.js} {@code domainDaysUntil}) AYNI
 * tabloyu {@code javaStringTable} ile okur ({@code domainDaysUntil.test.js}) — iki taraf ayrışamaz.
 *
 * <p>Kural: ofsetli zaman damgası (ISO {@code Z} / {@code ±HH[:MM[:SS]]}; {@code T} ve {@code Z} büyük-küçük harf duyarsız)
 * → kalan TAM gün ({@code floorDiv}); {@code Instant.parse} yedeği yalnız {@code 24:00:00} ve artık saniye {@code 23:59:60}
 * biçimlerini ayrıca kabul eder; geri kalan her şey (yalnız tarih, ofsetsiz zaman damgası, {@code +0300}) ilk 10 karakterin
 * İSTANBUL takvim günü farkı; geçersiz tarih → {@code null}.
 *
 * <p>Sütunlar: bitiş dizgisi, "şimdi" (UTC anı), beklenen gün (null = hesaplanamaz). Gece yarısına yakın satırlar
 * ({@code 21:30Z} = İstanbul ertesi gün 00:30) tarayıcı saat diliminden bağımsızlığı sabitler.
 */
class DomainDaysUntilTableTest {

    static final String[][] DAYS_TABLE = {
            // yalnız tarih (WHOIS) — İstanbul takvim günü
            { "2026-10-01", "2026-10-01T07:00:00Z", "0" },
            { "2026-10-02", "2026-10-01T07:00:00Z", "1" },
            { "2026-09-30", "2026-10-01T07:00:00Z", "-1" },
            { "2026-10-02", "2026-10-01T20:59:00Z", "1" },          // İstanbul 1 Ekim 23:59
            { "2026-10-02", "2026-10-01T21:30:00Z", "0" },          // İstanbul 2 Ekim 00:30 (UTC tarayıcı 1 derdi)
            { "2026-10-01", "2026-10-01T21:30:00Z", "-1" },
            { "2028-02-29", "2028-02-28T10:00:00Z", "1" },
            { "2026-02-29", "2026-02-28T10:00:00Z", null },         // artık yıl değil
            { "2026-02-31", "2026-02-01T10:00:00Z", null },
            { "2026-13-01", "2026-02-01T10:00:00Z", null },
            // ofsetsiz zaman damgası — sunucunun yedeği: İstanbul takvim günü (UTC sayılmaz)
            { "2026-10-02T23:30:00", "2026-10-01T21:30:00Z", "0" },
            { "2026-10-01T00:00:00", "2026-10-01T21:30:00Z", "-1" },
            { "2026-10-02 10:00", "2026-10-01T21:30:00Z", "0" },
            { "2026-10-02T10:00:00+0300", "2026-10-01T21:30:00Z", "0" },   // iki nokta üst üstesiz ofset → takvim günü
            { "2026-10-02T10Z", "2026-10-01T10:00:00Z", "1" },             // dakikasız saat → takvim günü
            { "2026-10-02T10:00:00+3:00", "2026-10-01T10:00:00Z", "1" },
            { "2026-10-02T10:00:00+19:00", "2026-10-01T10:00:00Z", "1" },  // ofset ±18 saati aşar
            { "2026-10-02T10:00:00+03:60", "2026-10-01T10:00:00Z", "1" },
            { "2026-10-02T10:00:00.1234567891Z", "2026-10-01T10:00:00Z", "1" },   // 10 haneli kesir
            { "2026-10-02T10:59:60Z", "2026-10-01T10:00:00Z", "1" },       // 60. saniye yalnız 23:59'da
            { "2026-10-02T24:00:01Z", "2026-10-01T10:00:00Z", "1" },
            { "2026-02-31T10:00:00Z", "2026-02-01T10:00:00Z", null },
            // ofsetli zaman damgası (RDAP) — tam gün, floorDiv
            { "2026-10-03T00:00:00Z", "2026-10-01T21:30:00Z", "1" },
            { "2026-10-02T10:00:00+03:00", "2026-10-01T21:30:00Z", "0" },
            { "2026-10-01T20:00:00Z", "2026-10-01T21:30:00Z", "-1" },      // dolalı <24 saat → -1 (0 değil)
            { "2026-10-03t00:00:00z", "2026-10-01T21:30:00Z", "1" },
            { "2026-10-03T00:00:00.999Z", "2026-10-01T21:30:00Z", "1" },
            { "2026-10-02T10:00Z", "2026-10-01T10:00:00Z", "1" },          // saniyesiz
            { "2026-10-02T09:59:59.999Z", "2026-10-01T10:00:00Z", "0" },
            { "2026-10-02T10:00+03", "2026-10-01T10:00:00Z", "0" },        // ±HH
            { "2026-10-02T10:00:00+03:00:00", "2026-10-01T10:00:00Z", "0" },
            { "2026-10-02T10:00:00-00:00", "2026-10-01T10:00:00Z", "1" },
            { "2026-10-02T10:00:00+18:00", "2026-10-01T10:00:00Z", "0" },
            { "2026-10-02T10:00:00-05:30", "2026-10-01T10:00:00Z", "1" },
            // Instant.parse yedeği: 24:00:00 ve artık saniye
            { "2026-10-02T24:00:00Z", "2026-10-02T00:00:00Z", "1" },
            { "2026-12-31T24:00:00.000Z", "2026-12-31T00:00:00Z", "1" },
            { "2026-10-02T24:00:00+03:00", "2026-10-02T00:00:00Z", "0" },
            { "2026-10-02T24:00:00.5Z", "2026-10-02T00:00:00Z", "0" },     // kesirli 24:00 → takvim günü (2 Ekim)
            { "2026-10-02T23:59:60Z", "2026-10-01T23:59:59Z", "1" },
            { "2026-10-02t23:59:60.5z", "2026-10-02T00:00:00Z", "0" },
            { "2026-10-02T23:59:60+03", "2026-10-02T00:00:00Z", "0" },     // artık saniyede ±HH yok → takvim günü
            // okunamayan / boş
            { "bozuk", "2026-10-01T10:00:00Z", null },
            { "2026-1-1", "2026-10-01T10:00:00Z", null },
            { "", "2026-10-01T10:00:00Z", null },
            { "   ", "2026-10-01T10:00:00Z", null },
            { " 2026-10-02", "2026-10-01T10:00:00Z", null },              // baştaki boşluk kırpılmaz
            { "+2026-10-02T10:00:00Z", "2026-10-01T10:00:00Z", null },
            { null, "2026-10-01T10:00:00Z", null },
    };

    @Test
    @DisplayName("DAYS_TABLE: daysUntil(iso, now) — kartın canlı kalan gün kuralıyla aynı tablo")
    void table() {
        for (String[] row : DAYS_TABLE) {
            Integer expected = row[2] == null ? null : Integer.valueOf(row[2]);
            assertThat(DomainCheckerService.daysUntil(row[0], Instant.parse(row[1])))
                    .as("daysUntil(%s, %s)", row[0], row[1])
                    .isEqualTo(expected);
        }
    }
}
