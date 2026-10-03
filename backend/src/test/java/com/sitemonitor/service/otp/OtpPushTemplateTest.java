package com.sitemonitor.service.otp;

import com.sitemonitor.service.PushText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kodla giriş push metni şablonu (2026-10-03): doğrulama kuralları (başlıkta yer tutucu yok, mesajda {@code {kod}} TAM bir
 * kez, bilinmeyen yer tutucu yok, en kötü dolumla kanal süzgecinden sonra tavan), dolum ve teslimde varsayılana düşme.
 */
class OtpPushTemplateTest {

    @Test
    @DisplayName("yerleşik metinler kanal-güvenli (ISO-8859-9), {kod} bir kez, 200 tavanının altında; boş = geçerli")
    void defaults_areValidAndChannelSafe() {
        for (boolean en : new boolean[] {false, true}) {
            assertThat(PushText.isChannelSafe(OtpPushTemplate.defaultTitle(en))).isTrue();
            assertThat(PushText.isChannelSafe(OtpPushTemplate.defaultMessage(en))).isTrue();
            assertThat(OtpPushTemplate.validateTitle(OtpPushTemplate.defaultTitle(en))).isNull();
            assertThat(OtpPushTemplate.validateMessage(OtpPushTemplate.defaultMessage(en), 200)).isNull();
            assertThat(OtpPushTemplate.worstCaseLength(OtpPushTemplate.defaultMessage(en))).isLessThanOrEqualTo(120);
        }
        assertThat(OtpPushTemplate.validateTitle("")).isNull();
        assertThat(OtpPushTemplate.validateTitle(null)).isNull();
        assertThat(OtpPushTemplate.validateMessage("  ", 80)).isNull();
    }

    @ParameterizedTest(name = "[{index}] mesaj \"{0}\" → {1}")
    @CsvSource(delimiter = '|', value = {
            "Kodunuz: {kod}                          | OK",
            "Kod {kod} — {sure} sn ({saat})           | OK",
            "Kodunuz hazır                           | {kod} yer tutucusunu içermeli",
            "{kod} ve yine {kod}                     | yalnız bir kez",
            "Kod {kod} {code}                        | Bilinmeyen yer tutucu: {code}",
            "Kod {kod} { sure }                      | Bilinmeyen yer tutucu: { sure }",
            "Kod {kod} {}                            | Bilinmeyen yer tutucu: {}",
    })
    void messageRules(String message, String expect) {
        OtpPushTemplate.Problem p = OtpPushTemplate.validateMessage(message.strip(), 200);
        if ("OK".equals(expect)) {
            assertThat(p).isNull();
        } else {
            assertThat(p).isNotNull();
            assertThat(p.part()).isEqualTo("message");
            assertThat(p.tr()).contains(expect);
            assertThat(p.en()).isNotBlank();
        }
    }

    @Test
    @DisplayName("başlık: {kod} ve diğer yer tutucular yasak, 60 karakter (süzgeçten sonra), yalnız emoji → telefonda boş")
    void titleRules() {
        assertThat(OtpPushTemplate.validateTitle("Giriş kodu {kod}").tr()).contains("{kod} başlıkta kullanılamaz");
        assertThat(OtpPushTemplate.validateTitle("Giriş {saat}").tr()).contains("yer tutucu kullanılamaz");
        assertThat(OtpPushTemplate.validateTitle("x".repeat(60))).isNull();
        assertThat(OtpPushTemplate.validateTitle("x".repeat(61)).tr()).contains("en çok 60");
        // "…" kanalda "..." olur → 59 + 3 = 62 > 60 (sayaç telefonun gördüğünü sayar)
        assertThat(OtpPushTemplate.validateTitle("x".repeat(59) + "…")).isNotNull();
        assertThat(OtpPushTemplate.validateTitle("🔒🔑").tr()).contains("boş kalıyor");
        assertThat(OtpPushTemplate.validateTitle("Giriş ✓ kodu")).isNull();
    }

    @Test
    @DisplayName("uzunluk EN KÖTÜ dolumla ve kanal süzgecinden SONRA: 6 haneli kod + 3 haneli süre + HH:mm")
    void worstCaseLength() {
        // "{kod}" (5) → 6, "{sure}" (6) → 3, "{saat}" (6) → 5
        assertThat(OtpPushTemplate.worstCaseLength("{kod}")).isEqualTo(6);
        assertThat(OtpPushTemplate.worstCaseLength("{kod} {sure} {saat}")).isEqualTo(6 + 1 + 3 + 1 + 5);
        // "…" → "..." (süzgeç genişletir), emoji düşer
        assertThat(OtpPushTemplate.worstCaseLength("{kod}… 😀")).isEqualTo(9);
        String almost = "a".repeat(74) + " {kod}";   // 74 + 1 + 6 = 81
        assertThat(OtpPushTemplate.validateMessage(almost, 81)).isNull();
        OtpPushTemplate.Problem p = OtpPushTemplate.validateMessage(almost, 80);
        assertThat(p).isNotNull();
        assertThat(p.tr()).contains("81").contains("80");
        assertThat(p.en()).contains("81").contains("80");
    }

    @Test
    @DisplayName("dolum: {kod}/{sure}/{saat}; saat İstanbul (UTC+3); regex özel karakterleri ($, \\) güvenli")
    void fill() {
        assertThat(OtpPushTemplate.fill("Kod {kod} - {sure} sn - {saat}", "123456", "45", "14:05"))
                .isEqualTo("Kod 123456 - 45 sn - 14:05");
        assertThat(OtpPushTemplate.fill("$1 \\ {kod}", "654321", "45", "00:00")).isEqualTo("$1 \\ 654321");
        assertThat(OtpPushTemplate.clock(Instant.parse("2026-10-03T08:15:30Z"))).isEqualTo("11:15");
        String r = OtpPushTemplate.render("Kod {kod} ({saat})", false, "999000", 45, Instant.parse("2026-10-03T21:30:00Z"), 200);
        assertThat(r).isEqualTo("Kod 999000 (00:30)");
    }

    @Test
    @DisplayName("teslim: kayıtlı şablon geçersizse / tavanı aşıyorsa yerleşik varsayılana düşülür — kod HER ZAMAN tam gider")
    void effective_fallsBackToDefault() {
        assertThat(OtpPushTemplate.effectiveMessage("Kod yok burada", false, 200)).isEqualTo(OtpPushTemplate.DEFAULT_MESSAGE_TR);
        assertThat(OtpPushTemplate.effectiveMessage("Code {kod}", true, 200)).isEqualTo("Code {kod}");
        assertThat(OtpPushTemplate.effectiveMessage("a".repeat(100) + " {kod}", false, 80)).isEqualTo(OtpPushTemplate.DEFAULT_MESSAGE_TR);
        assertThat(OtpPushTemplate.effectiveTitle("Başlık {kod}", true)).isEqualTo(OtpPushTemplate.DEFAULT_TITLE_EN);
        assertThat(OtpPushTemplate.effectiveTitle("  Özel başlık  ", false)).isEqualTo("Özel başlık");
        assertThat(OtpPushTemplate.storedInvalid("", "Kod yok", 200)).isTrue();
        assertThat(OtpPushTemplate.storedInvalid("", "", 200)).isFalse();
        assertThat(OtpPushTemplate.render("Kod yok", false, "123456", 45, null, 200)).contains("123456");
    }

    @Test
    @DisplayName("kanal repertuvarı = ön yüz aynasının kuralı (utils/pushSafeText.js canEncodeUnit): U+0000–U+00FF − {Ð Ý Þ ð ý þ} + {Ğ ğ İ ı Ş ş}")
    void channelRepertoire_matchesFrontendMirror() {
        java.nio.charset.CharsetEncoder enc = java.nio.charset.Charset.forName("ISO-8859-9").newEncoder();
        java.util.Set<Integer> gaps = java.util.Set.of(0xD0, 0xDD, 0xDE, 0xF0, 0xFD, 0xFE);
        java.util.Set<Integer> extra = java.util.Set.of(0x11E, 0x11F, 0x130, 0x131, 0x15E, 0x15F);
        java.util.List<String> diff = new java.util.ArrayList<>();
        for (int c = 0; c <= 0xFFFF; c++) {
            boolean mirror = (c < 0x100 && !gaps.contains(c)) || extra.contains(c);
            if (enc.canEncode((char) c) != mirror) diff.add(String.format("U+%04X", c));
        }
        assertThat(diff).as("ISO-8859-9 repertuvarı ön yüz aynasından sapıyor").isEmpty();
    }

    @Test
    @DisplayName("arayüz yer tutucu tanımları: {kod} zorunlu ve ilk; örnek + en kötü uzunluk")
    void placeholderView() {
        List<Map<String, Object>> v = OtpPushTemplate.placeholderView(45);
        assertThat(v).extracting(m -> m.get("token")).containsExactly("{kod}", "{sure}", "{saat}");
        assertThat(v.get(0)).containsEntry("required", true).containsEntry("sample", "123456").containsEntry("worst_len", 6);
        assertThat(v.get(1)).containsEntry("required", false).containsEntry("sample", "45").containsEntry("worst_len", 3);
        assertThat(v.get(2)).containsEntry("worst_len", 5);
    }
}
