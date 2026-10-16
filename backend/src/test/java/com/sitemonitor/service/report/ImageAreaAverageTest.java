package com.sitemonitor.service.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Alan ortalamasıyla küçültme (2026-10-16, "rakamlar / yazılar okunur kalsın") — istemcideki areaResample ile AYNI
 * örnekler (imagePrep.test.js): iki taraf aynı kuralla küçültür.
 */
class ImageAreaAverageTest {

    static BufferedImage gray(int w, int h, java.util.function.IntBinaryOperator v) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int g = v.applyAsInt(x, y);
                img.setRGB(x, y, (g << 16) | (g << 8) | g);
            }
        }
        return img;
    }

    static int g(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) & 0xff;
    }

    @Test
    @DisplayName("2:1 tam katta birebir ortalama; 3:2 kesirli kapsama (yatay ve dikey)")
    void exactAndFractional() {
        int[] a = { 0, 100, 200, 255 };
        BufferedImage out = ImageAreaAverage.resize(gray(4, 2, (x, y) -> a[x]), 2, 1);
        assertThat(new int[]{ g(out, 0, 0), g(out, 1, 0) }).containsExactly(50, 228);
        int[] b = { 0, 90, 180 };
        BufferedImage h = ImageAreaAverage.resize(gray(3, 1, (x, y) -> b[x]), 2, 1);
        assertThat(new int[]{ g(h, 0, 0), g(h, 1, 0) }).containsExactly(30, 150);
        BufferedImage v = ImageAreaAverage.resize(gray(1, 3, (x, y) -> b[y]), 1, 2);
        assertThat(new int[]{ g(v, 0, 0), g(v, 0, 1) }).containsExactly(30, 150);
    }

    @Test
    @DisplayName("düz renk aynen kalır; ortalama parlaklık korunur; ince çizgi örtüşmeyle kaybolmaz")
    void flatEnergyAndThinLines() {
        BufferedImage flat = ImageAreaAverage.resize(gray(7, 5, (x, y) -> 77), 3, 2);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 3; x++) assertThat(g(flat, x, y)).isEqualTo(77);
        long sum = 0;
        for (int y = 0; y < 30; y++) for (int x = 0; x < 40; x++) sum += (x * 37 + y * 11) % 256;
        BufferedImage out = ImageAreaAverage.resize(gray(40, 30, (x, y) -> (x * 37 + y * 11) % 256), 16, 12);
        long osum = 0;
        for (int y = 0; y < 12; y++) for (int x = 0; x < 16; x++) osum += g(out, x, y);
        assertThat(Math.abs(osum / 192.0 - sum / 1200.0)).isLessThan(1.0);
        BufferedImage lines = ImageAreaAverage.resize(gray(16, 2, (x, y) -> x % 4 == 1 ? 0 : 255), 8, 1);
        int below = 0, min = 255;
        for (int x = 0; x < 8; x++) {
            int v = g(lines, x, 0);
            if (v < 255) below++;
            min = Math.min(min, v);
        }
        assertThat(below).isEqualTo(4);
        assertThat(min).isEqualTo(128);
    }

    @Test
    @DisplayName("inceleme 2026-10-16: gri PNG (ImageIO gri renk uzayı) tonunu korur — getRGB 128'i 188 yapıp çizgileri soldururdu")
    void grayPngKeepsLevels() throws java.io.IOException {
        BufferedImage g = new BufferedImage(4, 2, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 4; x++) g.getRaster().setSample(x, y, 0, x < 2 ? 128 : 40);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(g, "png", out);
        BufferedImage back = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(out.toByteArray()));
        assertThat(back.getColorModel().getColorSpace().getType()).isEqualTo(java.awt.color.ColorSpace.TYPE_GRAY);
        BufferedImage small = ImageAreaAverage.resize(back, 2, 1);
        assertThat(new int[]{ small.getRGB(0, 0) & 0xff, small.getRGB(1, 0) & 0xff }).containsExactly(128, 40);
        assertThat(ImageAreaAverage.toSrgb(back).getRGB(0, 0) & 0xffffff).isEqualTo(0x808080);
    }

    @Test
    @DisplayName("inceleme 2026-10-16: gri + saydam (PNG renk türü 4, TYPE_CUSTOM) ve 16 bit gri de tonunu korur")
    void grayAlphaAnd16BitKeepLevels() throws java.io.IOException {
        // gri + saydam: ImageIO TYPE_CUSTOM döner; drawImage / getRGB 128'i yine 188 yapıyordu
        java.awt.image.ColorModel cm = new java.awt.image.ComponentColorModel(
                java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_GRAY), true, false,
                java.awt.Transparency.TRANSLUCENT, java.awt.image.DataBuffer.TYPE_BYTE);
        java.awt.image.WritableRaster ras = cm.createCompatibleWritableRaster(4, 2);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 4; x++) { ras.setSample(x, y, 0, 128); ras.setSample(x, y, 1, 255); }
        BufferedImage ga = new BufferedImage(cm, ras, false, null);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(ga, "png", out);
        byte[] png = out.toByteArray();
        assertThat(png[25]).isEqualTo((byte) 4);                                  // PNG renk türü 4
        BufferedImage back = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        int argb = ImageAreaAverage.resize(back, 2, 1).getRGB(0, 0);
        assertThat(new int[]{ argb >>> 24, argb & 0xff }).containsExactly(255, 128);
        assertThat(ImageAreaAverage.toSrgb(back).getRGB(3, 1) & 0xff).isEqualTo(128);
        // 16 bit gri: 32768 → 128
        BufferedImage g16 = new BufferedImage(2, 2, BufferedImage.TYPE_USHORT_GRAY);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) g16.getRaster().setSample(x, y, 0, 32768);
        assertThat(ImageAreaAverage.resize(g16, 1, 1).getRGB(0, 0) & 0xff).isEqualTo(128);
    }

    @Test
    @DisplayName("saydamlık önçarpımlı: saydam pikselin rengi koyu saçak olarak sızmaz; yalnız küçültür")
    void alphaAndBounds() {
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xffff0000);                                   // opak kırmızı
        img.setRGB(1, 0, 0x00000000);                                   // saydam siyah
        BufferedImage out = ImageAreaAverage.resize(img, 1, 1);
        int argb = out.getRGB(0, 0);
        assertThat(new int[]{ argb >>> 24, (argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff }).containsExactly(128, 255, 0, 0);
        assertThatThrownBy(() -> ImageAreaAverage.resize(gray(10, 10, (x, y) -> 0), 11, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
