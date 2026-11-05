package com.sitemonitor.service.page.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Algoritma 3 tarama yardımcıları: HTTP/3 duyurusu, CDN isabeti, kaynak haritası adresi, GIF kare sayısı. */
class PageResourceScanDeepHelpersTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "h3=\":443\"; ma=86400|true",
            "h3-29=\":443\", h2=\":443\"|true",
            "h2=\":443\"; ma=60|false",
            "clear|false",
            "fooh3=\":1\"|false"})
    void h3(String altSvc, boolean expected) {
        assertThat(PageResourceScanService.h3(altSvc)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} / {1} → {2}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            "HIT|-|hit",
            "TCP_HIT from cdn|-|hit",
            "MISS|-|miss",
            "EXPIRED|-|miss",
            "DYNAMIC|-|miss",
            "TCP_DENIED|-|-",
            "-|120|hit",
            "-|0|-",
            "-|bozuk|-",
            "-|-|-"})
    void cdnCache(String xCache, String age, String expected) {
        assertThat(PageResourceScanService.cdnCache(xCache, age)).isEqualTo(expected);
    }

    @Test
    @DisplayName("kaynak haritası adresi: başlık yorumdan önce; göreli → mutlak; data / http dışı → null")
    void sourceMapUrl() {
        assertThat(PageResourceScanService.sourceMapUrl("https://a.com/js/app.js", null, "app.js.map"))
                .isEqualTo("https://a.com/js/app.js.map");
        assertThat(PageResourceScanService.sourceMapUrl("https://a.com/js/app.js", "/maps/x.map", "app.js.map"))
                .isEqualTo("https://a.com/maps/x.map");
        assertThat(PageResourceScanService.sourceMapUrl("https://a.com/js/app.js", null, "file:///etc/passwd")).isNull();
        assertThat(PageResourceScanService.sourceMapUrl("https://a.com/js/app.js", null, null)).isNull();
    }

    static byte[] gif(int frames, boolean globalTable) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[]{'G', 'I', 'F', '8', '9', 'a', 2, 0, 2, 0, (byte) (globalTable ? 0x80 : 0), 0, 0});
        if (globalTable) o.writeBytes(new byte[6]);                                   // 2 renk × 3
        for (int k = 0; k < frames; k++) {
            o.writeBytes(new byte[]{0x21, (byte) 0xF9, 4, 0, 10, 0, 0, 0});
            o.writeBytes(new byte[]{0x2C, 0, 0, 0, 0, 2, 0, 2, 0, (byte) 0x80});        // yerel renk tablosu (2 renk)
            o.writeBytes(new byte[6]);
            o.writeBytes(new byte[]{2, 3, 0x44, 0x01, 0x02, 0});
        }
        o.write(0x3B);
        return o.toByteArray();
    }

    @Test
    @DisplayName("GIF kareleri: renk tabloları ve uzantılar atlanır; kesik dosyada alt sınır; GIF değil → 0")
    void gifFrames() {
        assertThat(ImageDimensions.gifFrames(gif(1, false))).isEqualTo(1);
        assertThat(ImageDimensions.gifFrames(gif(5, true))).isEqualTo(5);
        byte[] five = gif(5, true);
        byte[] cut = java.util.Arrays.copyOf(five, five.length / 2);
        assertThat(ImageDimensions.gifFrames(cut)).isBetween(1, 4);
        assertThat(ImageDimensions.gifFrames(new byte[]{1, 2, 3})).isZero();
        assertThat(ImageDimensions.gifFrames(null)).isZero();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 0, 0};
        assertThat(ImageDimensions.gifFrames(png)).isZero();
    }
}
