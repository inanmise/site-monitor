package com.sitemonitor.service.page.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** Görsel boyutu yalnız başlıktan okunur — gerçek kodlayıcı çıktıları (PNG / JPEG / GIF / BMP) + elle WebP başlıkları. */
class ImageDimensionsTest {

    private static byte[] encode(String format, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, "jpg".equals(format) || "bmp".equals(format)
                ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, format, bos)).as(format).isTrue();
        return bos.toByteArray();
    }

    @Test
    @DisplayName("PNG / JPEG / GIF / BMP: gerçek kodlayıcı çıktısının boyutu başlıktan okunur")
    void realEncoders() throws Exception {
        assertThat(ImageDimensions.read(encode("png", 321, 123))).isEqualTo(new ImageDimensions.Size(321, 123));
        assertThat(ImageDimensions.read(encode("jpg", 640, 480))).isEqualTo(new ImageDimensions.Size(640, 480));
        assertThat(ImageDimensions.read(encode("gif", 17, 9))).isEqualTo(new ImageDimensions.Size(17, 9));
        assertThat(ImageDimensions.read(encode("bmp", 50, 40))).isEqualTo(new ImageDimensions.Size(50, 40));
    }

    @Test
    @DisplayName("JPEG: yalnız ilk baytlar elde (gövde kesik) — SOF içerdeyse yine okunur")
    void jpegHeadOnly() throws Exception {
        byte[] full = encode("jpg", 1920, 1080);
        byte[] head = Arrays.copyOf(full, Math.min(full.length, 700));
        assertThat(ImageDimensions.read(head)).isEqualTo(new ImageDimensions.Size(1920, 1080));
    }

    @Test
    @DisplayName("WebP: VP8X / VP8L / VP8 başlıkları")
    void webp() {
        byte[] vp8x = riff("VP8X", 30);
        // genişlik-1 = 2999 (0x000BB7), yükseklik-1 = 1999 (0x0007CF), 24 bit LE
        vp8x[24] = (byte) 0xB7; vp8x[25] = 0x0B; vp8x[26] = 0;
        vp8x[27] = (byte) 0xCF; vp8x[28] = 0x07; vp8x[29] = 0;
        assertThat(ImageDimensions.read(vp8x)).isEqualTo(new ImageDimensions.Size(3000, 2000));

        byte[] vp8l = riff("VP8L", 30);
        vp8l[20] = 0x2F;
        int w = 400 - 1, h = 300 - 1;
        int bits = w | (h << 14);
        vp8l[21] = (byte) (bits & 0xFF);
        vp8l[22] = (byte) ((bits >> 8) & 0xFF);
        vp8l[23] = (byte) ((bits >> 16) & 0xFF);
        vp8l[24] = (byte) ((bits >> 24) & 0xFF);
        assertThat(ImageDimensions.read(vp8l)).isEqualTo(new ImageDimensions.Size(400, 300));

        byte[] vp8 = riff("VP8 ", 30);
        vp8[23] = (byte) 0x9D; vp8[24] = 0x01; vp8[25] = 0x2A;
        vp8[26] = (byte) (800 & 0xFF); vp8[27] = (byte) (800 >> 8);
        vp8[28] = (byte) (600 & 0xFF); vp8[29] = (byte) (600 >> 8);
        assertThat(ImageDimensions.read(vp8)).isEqualTo(new ImageDimensions.Size(800, 600));
    }

    @Test
    @DisplayName("tanınmayan / boş / bozuk / SVG / kesik başlık → null (fırlatmaz)")
    void unknownIsNull() throws Exception {
        assertThat(ImageDimensions.read(null)).isNull();
        assertThat(ImageDimensions.read(new byte[3])).isNull();
        assertThat(ImageDimensions.read("<svg width=\"10\" height=\"10\"></svg>".getBytes())).isNull();
        byte[] png = encode("png", 10, 10);
        assertThat(ImageDimensions.read(Arrays.copyOf(png, 12))).isNull();
        byte[] jpeg = encode("jpg", 10, 10);
        assertThat(ImageDimensions.read(Arrays.copyOf(jpeg, 20))).isNull();
        byte[] junk = new byte[4096];
        junk[0] = (byte) 0xFF; junk[1] = (byte) 0xD8;
        for (int i = 2; i < junk.length; i++) junk[i] = (byte) 0xFF;   // sonsuz dolgu → sınırlı döngü
        assertThat(ImageDimensions.read(junk)).isNull();
        byte[] zero = encode("png", 10, 10);
        zero[16] = zero[17] = zero[18] = zero[19] = 0;                 // genişlik 0 → geçersiz
        assertThat(ImageDimensions.read(zero)).isNull();
    }

    private static byte[] riff(String chunk, int len) {
        byte[] b = new byte[len];
        b[0] = 'R'; b[1] = 'I'; b[2] = 'F'; b[3] = 'F';
        b[8] = 'W'; b[9] = 'E'; b[10] = 'B'; b[11] = 'P';
        for (int i = 0; i < 4; i++) b[12 + i] = (byte) chunk.charAt(i);
        return b;
    }
}
