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

    // ── Çözme bilgisi (2026-11, inceleme O-2: bellek izni gerçek başlıktan) ───────────────────────

    /** Elle PNG başlığı: imza + IHDR (genişlik, yükseklik, bit derinliği, renk türü) — çözülmez, yalnız başlık okunur. */
    static byte[] pngHeader(int w, int h, int depth, int colorType) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(64);
        b.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        b.putInt(13).put(new byte[]{'I', 'H', 'D', 'R'}).putInt(w).putInt(h);
        b.put((byte) depth).put((byte) colorType).put((byte) 0).put((byte) 0).put((byte) 0).putInt(0);
        return b.array();
    }

    /** Elle JPEG başlığı: SOI + SOF0 (kesinlik, yükseklik, genişlik, bileşen sayısı). */
    static byte[] jpegHeader(int w, int h, int comps) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(64);
        b.put(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC0, 0x00, 0x11, 0x08});
        b.putShort((short) h).putShort((short) w).put((byte) comps);
        return b.array();
    }

    @Test
    @DisplayName("çözme bilgisi: PNG renk türü + bit derinliği (16 bit RGBA = 8 B/piksel, paletli 1, gri 1 / 16 bit gri 2), JPEG bileşen")
    void decodeHeader() {
        ImageDimensions.Header rgba16 = ImageDimensions.header(pngHeader(1000, 500, 16, 6));
        assertThat(rgba16).isEqualTo(new ImageDimensions.Header(1000, 500, "png", 4, 16, false));
        assertThat(rgba16.bytesPerPixel()).isEqualTo(8);
        assertThat(ImageDimensions.header(pngHeader(10, 10, 8, 2)).bytesPerPixel()).isEqualTo(3);
        assertThat(ImageDimensions.header(pngHeader(10, 10, 16, 2)).bytesPerPixel()).isEqualTo(6);
        assertThat(ImageDimensions.header(pngHeader(10, 10, 4, 3)).bytesPerPixel()).as("paletli").isEqualTo(1);
        assertThat(ImageDimensions.header(pngHeader(10, 10, 8, 0)).bytesPerPixel()).isEqualTo(1);
        assertThat(ImageDimensions.header(pngHeader(10, 10, 16, 0)).bytesPerPixel()).isEqualTo(2);
        assertThat(ImageDimensions.header(pngHeader(10, 10, 8, 4)).bytesPerPixel()).as("gri + alfa").isEqualTo(2);
        assertThat(ImageDimensions.header(pngHeader(10, 10, 8, 5))).as("geçersiz renk türü").isNull();
        assertThat(ImageDimensions.header(pngHeader(10, 10, 7, 2))).as("geçersiz bit derinliği").isNull();
        assertThat(ImageDimensions.header(jpegHeader(640, 480, 3)))
                .isEqualTo(new ImageDimensions.Header(640, 480, "jpeg", 3, 8, false));
        assertThat(ImageDimensions.header(jpegHeader(640, 480, 1)).bytesPerPixel()).isEqualTo(1);
        assertThat(ImageDimensions.header(jpegHeader(640, 480, 4))).as("CMYK çözülmez").isNull();
        assertThat(ImageDimensions.header(new byte[]{1, 2, 3})).isNull();
        assertThat(ImageDimensions.header(null)).isNull();
    }

    @Test
    @DisplayName("çalışma belleği: çözülmüş görüntü + JPEG'e çevirme kopyası (8 bit RGB / gri 0, 16 bit gri 1, alfalı / paletli / 16 bit renkli 4 B/piksel) + gövde")
    void workingBytesTable() {
        long px = 1000L * 500;
        assertThat(ImageOptimizer.workingBytes(ImageDimensions.header(pngHeader(1000, 500, 16, 6)), 7))
                .as("16 bit RGBA: 8 + 4 B/piksel").isEqualTo(px * 12 + 7);
        assertThat(ImageOptimizer.workingBytes(ImageDimensions.header(pngHeader(1000, 500, 8, 2)), 0)).isEqualTo(px * 3);
        assertThat(ImageOptimizer.workingBytes(ImageDimensions.header(pngHeader(1000, 500, 8, 6)), 0)).isEqualTo(px * 8);
        assertThat(ImageOptimizer.workingBytes(ImageDimensions.header(pngHeader(1000, 500, 8, 3)), 0)).isEqualTo(px * 5);
        assertThat(ImageOptimizer.workingBytes(ImageDimensions.header(pngHeader(1000, 500, 16, 0)), 0)).isEqualTo(px * 3);
        assertThat(ImageOptimizer.workingBytes(ImageDimensions.header(jpegHeader(1000, 500, 3)), 0)).isEqualTo(px * 3);
        assertThat(ImageOptimizer.workingBytes(ImageDimensions.header(jpegHeader(1000, 500, 1)), 0)).isEqualTo(px);
    }
}
