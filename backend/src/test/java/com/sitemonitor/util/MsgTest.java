package com.sitemonitor.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sunucu mesajı istek dilini izler: X-Lang > Accept-Language > tr. İstek bağlamı yoksa
 * (zamanlayıcı/boot) tr — eski istemciler bugünkü davranışı görür.
 */
class MsgTest {

    @AfterEach
    void reset() { RequestContextHolder.resetRequestAttributes(); }

    private static void bind(String xLang, String accept) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (xLang != null) req.addHeader(Msg.HEADER, xLang);
        if (accept != null) req.addHeader("Accept-Language", accept);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    @DisplayName("istek bağlamı yok → tr (varsayılan)")
    void noRequest_defaultsToTurkish() {
        assertThat(Msg.lang()).isEqualTo("tr");
        assertThat(Msg.t("Kaydedildi", "Saved")).isEqualTo("Kaydedildi");
    }

    @Test
    @DisplayName("X-Lang: en → İngilizce; büyük/küçük harf ve bölge eki toleranslı")
    void xLangEnglish() {
        bind("en", null);
        assertThat(Msg.t("Kaydedildi", "Saved")).isEqualTo("Saved");
        bind("EN-GB", null);
        assertThat(Msg.isEn()).isTrue();
    }

    @Test
    @DisplayName("X-Lang: tr → Türkçe; X-Lang Accept-Language'ı EZER")
    void xLangTurkishOverridesAccept() {
        bind("tr", "en-US,en;q=0.9");
        assertThat(Msg.t("Kaydedildi", "Saved")).isEqualTo("Kaydedildi");
    }

    @Test
    @DisplayName("X-Lang yoksa Accept-Language; tanınmayan dil → tr")
    void acceptLanguageFallback() {
        bind(null, "en-US,en;q=0.9");
        assertThat(Msg.isEn()).isTrue();
        bind(null, "de-DE");
        assertThat(Msg.lang()).isEqualTo("tr");
        bind("", "fr");
        assertThat(Msg.lang()).isEqualTo("tr");
    }
}
