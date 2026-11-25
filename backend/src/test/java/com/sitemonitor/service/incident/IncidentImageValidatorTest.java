package com.sitemonitor.service.incident;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Hızlı Giriş" (2026-11-20) — olay görseli tam çözünürlükte gelir; sunucu türü BAYTLARDAN, ölçüyü başlıktan (çözmeden)
 * denetler. Saklanan tür istemcinin başlığı değil baytların türüdür.
 */
class IncidentImageValidatorTest {

    static final long MAX = 5L * 1024 * 1024;

    static byte[] encode(String format, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, (x * 31 + y * 17) & 0xFFFFFF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, format, out)).as("ImageIO yazıcısı " + format).isTrue();
        return out.toByteArray();
    }

    /** Yalnız IHDR'si olan PNG başlığı — ölçü kuralını piksel üretmeden sınamak için. */
    static byte[] pngHeader(int w, int h) {
        ByteBuffer b = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN);
        b.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        b.putInt(13).put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(w).putInt(h);
        b.put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0).putInt(0);
        return b.array();
    }

    /** VP8L (kayıpsız) WebP başlığı — 14 bitlik genişlik / yükseklik. */
    static byte[] webp(int w, int h) {
        ByteBuffer b = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(22).put("WEBP".getBytes(StandardCharsets.US_ASCII));
        b.put("VP8L".getBytes(StandardCharsets.US_ASCII)).putInt(10).put((byte) 0x2F);
        int bits = (w - 1) | ((h - 1) << 14);
        b.putInt(bits);
        return b.array();
    }

    @Test
    @DisplayName("PNG / JPEG / GIF / BMP / WebP kabul — tür ve ölçü baytlardan")
    void acceptsCommonTypes() throws Exception {
        assertThat(IncidentImageValidator.check(encode("png", 1920, 1080), MAX))
                .isEqualTo(new IncidentImageValidator.Checked("image/png", 1920, 1080));
        assertThat(IncidentImageValidator.check(encode("jpeg", 640, 480), MAX).contentType()).isEqualTo("image/jpeg");
        assertThat(IncidentImageValidator.check(encode("gif", 32, 20), MAX).contentType()).isEqualTo("image/gif");
        assertThat(IncidentImageValidator.check(encode("bmp", 40, 30), MAX).contentType()).isEqualTo("image/bmp");
        assertThat(IncidentImageValidator.check(webp(300, 200), MAX))
                .isEqualTo(new IncidentImageValidator.Checked("image/webp", 300, 200));
    }

    @Test
    @DisplayName("küçük görsel (alt sınır yok) kabul — ekran parçası / simge olağandır")
    void tinyImageAccepted() throws Exception {
        assertThat(IncidentImageValidator.check(encode("png", 4, 4), MAX).width()).isEqualTo(4);
    }

    @Test
    @DisplayName("SVG / HTML / AVIF / uzantısı değiştirilmiş metin reddedilir (alan 'file')")
    void rejectsNonImages() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
        byte[] html = "<!doctype html><html><body>x</body></html>".getBytes(StandardCharsets.UTF_8);
        byte[] avif = new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'a', 'v', 'i', 'f', 0, 0, 0, 0, 'a', 'v', 'i', 'f', 'm', 'i', 'f', '1'};
        for (byte[] d : new byte[][]{svg, html, avif}) {
            assertThatThrownBy(() -> IncidentImageValidator.check(d, MAX))
                    .isInstanceOfSatisfying(FieldValidationException.class, ex -> {
                        assertThat(ex.getFields()).containsOnlyKeys("file");
                        assertThat(ex.getMessage()).contains("PNG");
                    });
        }
    }

    @Test
    @DisplayName("boyut sınırı: sınırı aşan dosya reddedilir; boş dosya reddedilir")
    void sizeLimit() throws Exception {
        byte[] png = encode("png", 64, 64);
        assertThatThrownBy(() -> IncidentImageValidator.check(png, png.length - 1L))
                .isInstanceOf(FieldValidationException.class).hasMessageContaining("MB");
        assertThat(IncidentImageValidator.check(png, png.length).width()).isEqualTo(64);
        assertThatThrownBy(() -> IncidentImageValidator.check(new byte[0], MAX)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> IncidentImageValidator.check(null, MAX)).isInstanceOf(FieldValidationException.class);
    }

    @Test
    @DisplayName("ölçü başlıktan: kenar > 6000 ya da toplam > 12 MP reddedilir; sınırdakiler kabul")
    void dimensionLimits() {
        assertThat(IncidentImageValidator.check(pngHeader(6000, 2000), MAX).width()).isEqualTo(6000);
        assertThat(IncidentImageValidator.check(pngHeader(4000, 3000), MAX).height()).isEqualTo(3000);
        for (byte[] d : new byte[][]{pngHeader(6001, 10), pngHeader(10, 6001), pngHeader(4000, 3001)}) {
            assertThatThrownBy(() -> IncidentImageValidator.check(d, MAX))
                    .isInstanceOfSatisfying(FieldValidationException.class,
                            ex -> assertThat(ex.getMessage()).contains("6000").contains("12"));
        }
    }

    @Test
    @DisplayName("ölçüsü okunamayan (kesik başlık) görsel reddedilir")
    void unreadableHeader() {
        byte[] cut = java.util.Arrays.copyOf(pngHeader(100, 100), 14);
        assertThatThrownBy(() -> IncidentImageValidator.check(cut, MAX))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> assertThat(ex.getMessage()).contains("okunamadı"));
    }
}
