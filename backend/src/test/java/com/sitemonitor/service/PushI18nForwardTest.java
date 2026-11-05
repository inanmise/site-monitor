package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alarm iletimi push metinleri (2026-11-05): sahip takım öneki (TR / EN, tekil / çoğul), çözüm push'unun öneki önceki satırdan
 * geri okuması ve iletilen fırtına metni. Saf işlevler.
 */
class PushI18nForwardTest {

    @Test
    @DisplayName("önek: tr 'X takımından: ' / çoğul 'A, B takımlarından: '; en 'From team X: ' / 'From teams A, B: '; sahip yoksa genel önek")
    void prefixes() {
        assertThat(PushI18n.forwardPrefix(List.of("Ödeme Sistemleri"), "tr")).isEqualTo("Ödeme Sistemleri takımından: ");
        assertThat(PushI18n.forwardPrefix(List.of("Ödeme Sistemleri", "Kart Ekibi"), "tr"))
                .isEqualTo("Ödeme Sistemleri, Kart Ekibi takımlarından: ");
        assertThat(PushI18n.forwardPrefix(List.of("Ödeme Sistemleri"), "en")).isEqualTo("From team Ödeme Sistemleri: ");
        assertThat(PushI18n.forwardPrefix(List.of("A", "B"), "en")).isEqualTo("From teams A, B: ");
        assertThat(PushI18n.forwardPrefix(List.of(), "tr")).isEqualTo("İletilen alarm: ");
        assertThat(PushI18n.forwardPrefix((List<String>) null, "en")).isEqualTo("Forwarded alarm: ");
        assertThat(PushI18n.forwardPrefix(Arrays.asList(" ", null), "tr")).isEqualTo("İletilen alarm: ");
        // aynı ad iki kez (SY = UG adı) tekil sayılır
        assertThat(PushI18n.forwardPrefix(List.of("Kart Ekibi", "Kart Ekibi"), "tr")).isEqualTo("Kart Ekibi takımından: ");
    }

    @Test
    @DisplayName("uzun AD takım adı 50 karakterde '...' ile biter; ad içindeki ':' ve satır sonu boşluğa döner (ayırıcıyla karışmaz)")
    void longAndUnsafeNames() {
        String longName = "Çağrı Merkezi Uygulama Destek Ekibi (Müşteri Deneyimi / Dijital Kanallar)";
        String p = PushI18n.forwardPrefix(List.of(longName), "tr");
        String label = p.substring(0, p.indexOf(" takımından: "));
        assertThat(label).hasSizeLessThanOrEqualTo(PushI18n.FORWARD_OWNER_MAX).endsWith("...");
        assertThat(PushI18n.forwardPrefix(List.of("Ekip: Ödeme\nDestek"), "en")).isEqualTo("From team Ekip Ödeme Destek: ");
    }

    @Test
    @DisplayName("geri okuma: önceki iletim satırının öneki (tr / en, tekil / çoğul, kırpılmış) bulunur; iletim olmayan metin null")
    void ownerRoundTrip() {
        for (List<String> owners : List.of(List.of("Ödeme Sistemleri"), List.of("A", "B"),
                List.of("Çağrı Merkezi Uygulama Destek Ekibi (Müşteri Deneyimi / Dijital Kanallar)"))) {
            for (String lang : List.of("tr", "en")) {
                String msg = PushText.pushSafe(PushI18n.forwardPrefix(owners, lang) + "YÜKSEK: x yanıt vermiyor. Başlangıç 10:00.");
                PushI18n.ForwardOwner o = PushI18n.forwardOwnerOf(msg);
                assertThat(o).as(msg).isNotNull();
                assertThat(o.plural()).isEqualTo(owners.size() > 1);
                // başka dilde yeniden kurulan önek aynı sahip parçasını taşır
                String other = PushI18n.forwardPrefixFor(o, "tr".equals(lang) ? "en" : "tr");
                assertThat(other).contains(o.label());
                assertThat(PushI18n.forwardOwnerOf(other + "DÜZELDİ").label()).isEqualTo(o.label());
            }
        }
        assertThat(PushI18n.forwardOwnerOf("İletilen alarm: KRİTİK: x")).isEqualTo(new PushI18n.ForwardOwner(null, false));
        assertThat(PushI18n.forwardOwnerOf("YÜKSEK: x yanıt vermiyor.")).isNull();
        // önek yalnız mesajın BAŞINDA: sahip şablonu "{seviye}: …" ile başlar, metnin içindeki "takımından:" önek sayılmaz
        assertThat(PushI18n.forwardOwnerOf("KRİTİK: x - Ödeme takımından: değişti")).isNull();
        assertThat(PushI18n.forwardOwnerOf(null)).isNull();
        assertThat(PushI18n.forwardOwnerOf("From team : x")).isNull();
    }

    @Test
    @DisplayName("iletilen fırtına metni: açılış / günlük / çözüm, tr + en, tekil / çoğul; sahip adı yoksa genel metin; push kanalına güvenli")
    void stormTexts() {
        assertThat(PushI18n.forwardStormText("Ödeme Sistemleri", 5, "CRITICAL", "INITIAL", "tr"))
                .isEqualTo("Ödeme Sistemleri takımının fırtınası: size iletilen 5 alarm (KRİTİK).");
        assertThat(PushI18n.forwardStormText("Ödeme Sistemleri", 5, "HIGH", "DAILY_REALERT", "tr"))
                .isEqualTo("Ödeme Sistemleri takımının fırtınası sürüyor: size iletilen 5 alarm (YÜKSEK).");
        assertThat(PushI18n.forwardStormText("Ödeme Sistemleri", 2, "CRITICAL", "RESOLVE", "tr"))
                .isEqualTo("Ödeme Sistemleri takımının fırtınası sona erdi.");
        assertThat(PushI18n.forwardStormText("Payments", 1, "CRITICAL", "INITIAL", "en"))
                .isEqualTo("Storm of team Payments: 1 alarm forwarded to you (CRITICAL).");
        assertThat(PushI18n.forwardStormText("Payments", 3, "WARNING", "DAILY_REALERT", "en"))
                .isEqualTo("Storm of team Payments continues: 3 alarms forwarded to you (WARNING).");
        assertThat(PushI18n.forwardStormText("Payments", 3, "WARNING", "RESOLVE", "en")).isEqualTo("Storm of team Payments has ended.");
        assertThat(PushI18n.forwardStormText(null, 4, "HIGH", "INITIAL", "tr")).isEqualTo("Alarm fırtınası: size iletilen 4 alarm (YÜKSEK).");
        assertThat(PushI18n.forwardStormText(" ", 4, "HIGH", "RESOLVE", "en")).isEqualTo("The forwarded alert storm has ended.");
        for (String t : List.of("INITIAL", "DAILY_REALERT", "RESOLVE"))
            for (String lang : List.of("tr", "en")) {
                String s = PushI18n.forwardStormText("Çağrı Merkezi Uygulama Destek Ekibi (Müşteri Deneyimi / Dijital Kanallar)", 12,
                        "CRITICAL", t, lang);
                assertThat(PushText.pushSafe(s)).isEqualTo(s);
            }
    }
}
