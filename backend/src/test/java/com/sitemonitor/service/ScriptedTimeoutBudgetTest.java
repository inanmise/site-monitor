package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TERS TIMEOUT BÜTÇESİ — sahada 289 koşumun 289'unu sebepsiz bırakan kusur.
 *
 * <p>Vaka: {@code llm-test-qwen35-27b} monitörü her koşumda tam <b>10006 ms</b> sürüp
 * "Süre aşımı — süreç sonlandırıldı, exit -1" verdi. Script'te istek timeout'u AÇIKÇA
 * {@code '20s'} yazıyordu; monitörün süreç bütçesi ise 10 sn'ydi. Yani k6 kendi isteğini
 * düşürmeye fırsat bulamadan süreci biz öldürüyorduk ve başarısızlığın gerçek sebebi
 * (bağlantı reddi / DNS / TLS / istek zaman aşımı) HİÇ yazılamıyordu.
 *
 * <p>Neden iki ay boyunca fark edilmedi: {@link ScriptedCheckerService#auditRequestTimeouts}
 * bunun TERSİNİ denetliyor (açık timeout YOKLUĞU). Bu monitörde açık timeout vardı, dolayısıyla
 * hiç uyarı çıkmadı — üstelik arayüz "script'e açık timeout ekleyin" diye kullanıcının çoktan
 * yaptığı şeyi tavsiye ediyordu. Buradaki testler o körlüğü kapatır.
 */
class ScriptedTimeoutBudgetTest {

    /** Sahadaki gerçek script (kısaltılmış) — regresyonun canlı örneği. */
    private static final String FIELD_SCRIPT = """
            import http from 'k6/http';
            import { check } from 'k6';
            export default function () {
              const r = http.get('https://www.akbank.com', { timeout: '20s' });
              check(r, { 'status 200': (res) => res.status === 200 });
            }
            """;

    // ── Süre ayrıştırma ─────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "20s,      20000",
        "500ms,      500",
        "1m,       60000",
        "1m30s,    90000",
        "2h,     7200000",
        "20000,    20000",   // ÇIPLAK SAYI k6'da milisaniyedir — saniye sanılırsa 20 sn'lik bir
                             // timeout 20000 sn okunur ve ters-bütçe uyarısı hiç çıkmaz
        "'  20s  ', 20000",
    })
    @DisplayName("k6 süre gösterimleri ms'e çevrilir (çıplak sayı = ms)")
    void parsesDurations(String raw, long expectedMs) {
        assertThat(ScriptedCheckerService.parseK6DurationMs(raw.trim().replace("'", ""))).isEqualTo(expectedMs);
    }

    @Test
    @DisplayName("Ayrıştırılamayan süre null döner — denetim susar, YANLIŞ uyarı üretmez")
    void unparsableDurationIsNull() {
        assertThat(ScriptedCheckerService.parseK6DurationMs("yirmi saniye")).isNull();
        assertThat(ScriptedCheckerService.parseK6DurationMs("")).isNull();
        assertThat(ScriptedCheckerService.parseK6DurationMs(null)).isNull();
    }

    // ── En büyük istek timeout'u ────────────────────────────────────────────────

    @Test
    @DisplayName("Script'teki EN BÜYÜK açık timeout alınır (süreci öldüren en uzun bekleyendir)")
    void takesLargestTimeout() {
        String multi = """
                export default function () {
                  http.get('https://a', { timeout: '5s' });
                  http.post('https://b', '{}', { timeout: '45s' });
                  http.get('https://c', { timeout: '10s' });
                }
                """;
        assertThat(ScriptedCheckerService.maxRequestTimeoutSeconds(multi)).isEqualTo(45);
    }

    @Test
    @DisplayName("Açık timeout yoksa null; yorumdaki timeout SAYILMAZ")
    void noExplicitTimeout() {
        assertThat(ScriptedCheckerService.maxRequestTimeoutSeconds(
                "export default function(){ http.get('https://x'); }")).isNull();
        assertThat(ScriptedCheckerService.maxRequestTimeoutSeconds(
                "// timeout: '20s'\nexport default function(){ http.get('https://x'); }")).isNull();
    }

    @Test
    @DisplayName("URL'deki '//' yorum sanılmaz — aynı satırdaki timeout görünür kalır")
    void urlSlashesDoNotHideTimeout() {
        assertThat(ScriptedCheckerService.maxRequestTimeoutSeconds(FIELD_SCRIPT)).isEqualTo(20);
    }

    @Test
    @DisplayName("k6 options'taki setupTimeout istek timeout'u sanılmaz (büyük harf T)")
    void setupTimeoutIsNotRequestTimeout() {
        String opts = """
                export const options = { setupTimeout: '300s' };
                export default function(){ http.get('https://x', { timeout: '5s' }); }
                """;
        assertThat(ScriptedCheckerService.maxRequestTimeoutSeconds(opts)).isEqualTo(5);
    }

    // ── Ters bütçe denetimi (asıl kusur) ────────────────────────────────────────

    @Test
    @DisplayName("SAHA VAKASI: istek 20 sn > bütçe 10 sn → uyarı, iki sayı da ve iki çıkış yolu da yazılı")
    void fieldCase_inverseBudgetIsReported() {
        var warnings = ScriptedCheckerService.auditTimeoutBudget(FIELD_SCRIPT, 10);

        assertThat(warnings).singleElement().asString()
                .contains("20 sn").contains("10 sn")
                .contains("≥ 35 sn")      // bütçeyi yükselt: istek + 15 sn pay
                .contains("≤ 7 sn");      // ya da isteği düşür: bütçe - 3 sn
    }

    @Test
    @DisplayName("EŞİTLİK de hatadır — iki bütçe aynı anda dolar, yarışı kim kazanır belirsiz")
    void equalBudgetsAlsoWarn() {
        String s = "export default function(){ http.get('https://x', { timeout: '30s' }); }";
        assertThat(ScriptedCheckerService.auditTimeoutBudget(s, 30)).hasSize(1);
    }

    @Test
    @DisplayName("Sıra DOĞRUYSA uyarı yok — gürültü üretmez")
    void correctOrderIsSilent() {
        String s = "export default function(){ http.get('https://x', { timeout: '20s' }); }";
        assertThat(ScriptedCheckerService.auditTimeoutBudget(s, 60)).isEmpty();
    }

    @Test
    @DisplayName("Açık timeout yoksa bu denetim SUSAR (o durum auditRequestTimeouts'un işi)")
    void noExplicitTimeoutIsOtherAuditsJob() {
        String s = "export default function(){ http.get('https://x'); }";
        assertThat(ScriptedCheckerService.auditTimeoutBudget(s, 10)).isEmpty();
        assertThat(ScriptedCheckerService.auditRequestTimeouts(s)).hasSize(1);   // öteki denetim konuşur
    }

    @Test
    @DisplayName("Bütçe bilinmiyorsa denetim atlanır (eski iki argümanlı doğrulama yolu)")
    void unknownBudgetSkips() {
        assertThat(ScriptedCheckerService.auditTimeoutBudget(FIELD_SCRIPT, null)).isEmpty();
        assertThat(ScriptedCheckerService.auditTimeoutBudget(FIELD_SCRIPT, 0)).isEmpty();
    }

    // ── Koşum bağlam satırı ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Başarısız koşum bağlamı: bütçe, istek timeout'u, ÇIKIŞ YOLU ve CA tek satırda")
    void contextNoteCarriesEgressPath() {
        String note = ScriptedCheckerService.runContextNote(
                FIELD_SCRIPT, 10, true, "proxy.akbank.com:8080", true);

        assertThat(note)
                .contains("süreç bütçesi=10s")
                .contains("script istek timeout'u=20s")
                .contains("vekil (proxy.akbank.com:8080)")
                .contains("kurumsal CA=verildi")
                // Ters bütçe varsa ÖNCE o anlatılır: sebebin neden hiç yazılamadığını açıklayan
                // tek cümle odur ve kullanıcı bunu başka hiçbir yerden göremez.
                .startsWith("İstek timeout'u");
    }

    @Test
    @DisplayName("Doğrudan çıkışta vekil adresi yazılmaz; açık timeout yoksa k6 varsayılanı anılır")
    void contextNoteDirectAndImplicit() {
        String note = ScriptedCheckerService.runContextNote(
                "export default function(){ http.get('https://x'); }", 60, false, null, false);

        assertThat(note)
                .contains("çıkış=doğrudan")
                .contains("verilmemiş (k6 varsayılanı 60s)")
                .contains("kurumsal CA=verilmedi")
                .doesNotContain("vekil");
    }

    @Test
    @DisplayName("Sıra doğruyken bağlam satırı SADE kalır (uyarı cümlesi eklenmez)")
    void contextNoteWithoutInversion() {
        String note = ScriptedCheckerService.runContextNote(
                FIELD_SCRIPT, 60, false, null, true);

        assertThat(note).doesNotContain("İstek timeout'u").startsWith("süreç bütçesi=60s");
    }
}
