package com.sitemonitor.service.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BD1 (bug regresyon 2026-09-27) — e-posta bağlantılarında şema beyaz listesi. {@code href}'e yalnız
 * http/https/mailto (ve şemasız göreli değer) basılır; {@code javascript:}/{@code data:}/{@code vbscript:}/
 * {@code file:} bağlantı olarak ÇİZİLMEZ.
 */
class MailKitSafeHrefTest {

    private static final char TAB = (char) 9;
    private static final char LF = (char) 10;

    @Test
    @DisplayName("safeHref: http/https/mailto ve göreli değer kabul; tehlikeli şemalar (gizlenmiş hâlleri dâhil) red")
    void safeHref_allowList() {
        for (String ok : new String[]{"https://www.example.com/x", "http://www.example.com", "HTTPS://WWW.EXAMPLE.COM",
                "mailto:ad.soyad@example.com", "/?tab=domains", "?tab=x", "#bolum", "/rapor?t=10:00"}) {
            assertThat(MailKit.safeHref(ok)).as(ok).isTrue();
        }
        for (String bad : new String[]{"javascript:alert(1)", "JaVaScRiPt:alert(1)", " javascript:alert(1)",
                "java" + TAB + "script:alert(1)", "java" + LF + "script:alert(1)", "data:text/html;base64,PHNjcmlwdD4=",
                "vbscript:msgbox(1)", "file:///C:/Windows/win.ini", "ftp://www.example.com/x", "www.example.com:8080/x",
                // tel: GENEL izin listesinde YOK (2026-09-27): kullanıcı girdisi bağlantı bu şemayı taşıyamaz;
                // telefon bağlantısı yalnız MailKit.telLink ile (7/24 arama listesi) kurulur.
                "tel:+905550000000", "TEL:05550000000",
                "", "   ", null}) {
            assertThat(MailKit.safeHref(bad)).as(String.valueOf(bad)).isFalse();
        }
    }

    @Test
    @DisplayName("link: izinsiz şemada <a> KURULMAZ, etiket kaçırılmış düz metin; izinlide bağlantı aynen")
    void link_unsafeRendersPlainLabel() {
        String bad = MailKit.link("javascript:alert(1)", "Kayıtlar <tıkla>");
        assertThat(bad).doesNotContain("<a").doesNotContain("javascript").isEqualTo("Kayıtlar &lt;tıkla&gt;");
        assertThat(MailKit.link("https://www.example.com/k", "Kayıtlar")).contains("href=\"https://www.example.com/k\"");
    }

    @Test
    @DisplayName("button / MailDoc.button: izinsiz şemada buton da düz metin satırı da üretilmez")
    void button_unsafeOmitted() {
        assertThat(MailKit.button("javascript:alert(1)", "Onayla", MailKit.Variant.PRIMARY, 500)).isEmpty();
        assertThat(MailKit.button("https://www.example.com/onay", "Onayla", MailKit.Variant.PRIMARY, 500))
                .contains("href=\"https://www.example.com/onay\"");

        MailDoc d = MailDoc.create("Deneme").button("javascript:alert(1)", "Onayla")
                .button("https://www.example.com/ac", "Aç");
        assertThat(d.html()).doesNotContain("javascript").contains("href=\"https://www.example.com/ac\"");
        assertThat(d.text()).doesNotContain("javascript").contains("Aç: https://www.example.com/ac");
    }

    @Test
    @DisplayName("telLink: hedef yalnız rakam + baştaki '+'; görünen metin kaçırılır; çevrilemeyen numara düz metin")
    void telLink_digitsOnly() {
        assertThat(MailKit.telHref("+90 (555) 000-00-00")).isEqualTo("tel:+905550000000");
        assertThat(MailKit.telHref("555+12")).isEqualTo("tel:55512");
        assertThat(MailKit.telHref("javascript:alert(1)")).isNull();
        assertThat(MailKit.telHref("12")).isNull();
        assertThat(MailKit.telHref(null)).isNull();
        String link = MailKit.telLink("0555 000 00 00 <x>");
        assertThat(link).contains("href=\"tel:05550000000\"").contains("&lt;x&gt;").doesNotContain("<x>");
        assertThat(MailKit.telLink("<b>yok</b>")).isEqualTo("&lt;b&gt;yok&lt;/b&gt;");
    }
}
