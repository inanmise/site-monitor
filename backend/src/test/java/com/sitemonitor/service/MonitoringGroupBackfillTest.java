package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AÇILIŞ BACKFILL'İ — uygulama her başladığında 9 monitör tablosunda toplu save/UPDATE çalıştırıyor
 * ve buraya kadar HİÇ testi yoktu. İki dalı sessizce veri bozar:
 *
 *  - {@code fill()}: envanter domain eşleşmesinden TAKIM ATIYOR. Yanlış atama = o takım başkasının
 *    monitörünü görür/düzenler. Sonuç yalnız bir log sayacı olduğu için fark edilmez.
 *  - {@code host()}: şema/port/path'i yanlış ayrıştırırsa eşleşme kayar ve yukarıdaki atama bozulur.
 *
 * Gövdenin tamamı try/catch(Exception){log.warn} ile sarılı olduğundan yarım kalan bir backfill
 * uygulamayı sağlıklı gösterir — bu yüzden mantık birim testiyle kilitleniyor.
 */
class MonitoringGroupBackfillTest {

    // ── host(): URL → ana makine ────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "https://a.akbank.com/x/y,  a.akbank.com",
        "http://a.akbank.com,       a.akbank.com",
        "a.akbank.com:8443,         a.akbank.com",
        "https://a.akbank.com:8443/p, a.akbank.com",
        "a.akbank.com,              a.akbank.com",
        "'  a.akbank.com  ',        a.akbank.com",
    })
    @DisplayName("host(): şema, port ve path soyulur — eşleşme anahtarı saf ana makine olur")
    void host_stripsSchemePortPath(String raw, String expected) {
        assertThat(MonitoringGroupBackfill.host(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("host(): null/boş girdi null döner (eşleşme denenmez)")
    void host_nullSafe() {
        assertThat(MonitoringGroupBackfill.host(null)).isNull();
        assertThat(MonitoringGroupBackfill.host("   ")).isNull();
        assertThat(MonitoringGroupBackfill.host("https://")).isNull();
    }

    // ── fill(): takım ataması ───────────────────────────────────────────────────

    @Test
    @DisplayName("Takımı OLAN monitöre dokunulmaz (mevcut atama ezilmez)")
    void fill_existingTeam_notOverwritten() {
        AtomicReference<Long> assigned = new AtomicReference<>(null);

        int r = MonitoringGroupBackfill.fill(7L, "a.akbank.com",
                Map.of("a.akbank.com", 99L), assigned::set, "Prod");

        assertThat(r).isZero();
        assertThat(assigned.get()).isNull();   // setter HİÇ çağrılmadı
    }

    @Test
    @DisplayName("Envanterde eşleşen domain → takım atanır (küçük/büyük harf duyarsız)")
    void fill_inventoryMatch_assignsTeam() {
        AtomicReference<Long> assigned = new AtomicReference<>(null);

        int r = MonitoringGroupBackfill.fill(null, "A.Akbank.COM",
                Map.of("a.akbank.com", 5L), assigned::set, null);

        assertThat(r).isEqualTo(1);
        assertThat(assigned.get()).isEqualTo(5L);
    }

    @Test
    @DisplayName("Eşleşme YOKSA takım TAHMİN EDİLMEZ; grubu varsa öksüz olarak raporlanır")
    void fill_noMatch_doesNotGuess() {
        AtomicReference<Long> assigned = new AtomicReference<>(null);

        int orphan = MonitoringGroupBackfill.fill(null, "bilinmeyen.example.com",
                Map.of("a.akbank.com", 5L), assigned::set, "Prod");
        int silent = MonitoringGroupBackfill.fill(null, "bilinmeyen.example.com",
                Map.of("a.akbank.com", 5L), assigned::set, null);

        assertThat(orphan).isEqualTo(-1);   // grubu var → öksüz sayacına
        assertThat(silent).isZero();        // grubu yok → sessiz geç
        assertThat(assigned.get()).isNull(); // İKİ durumda da atama YOK — yanlış takım riski budur
    }

    @Test
    @DisplayName("Domain null ise atama denenmez (host() null döndürdüğü durum)")
    void fill_nullDomain_noAssignment() {
        AtomicReference<Long> assigned = new AtomicReference<>(null);

        int r = MonitoringGroupBackfill.fill(null, null, Map.of("a.akbank.com", 5L), assigned::set, "Prod");

        assertThat(r).isEqualTo(-1);
        assertThat(assigned.get()).isNull();
    }
}
