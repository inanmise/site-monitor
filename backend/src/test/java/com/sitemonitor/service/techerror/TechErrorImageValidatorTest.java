package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Grafik görseli doğrulaması (2026-10-13): tür baytlardan, boyut, piksel ölçüsü çözmeden. */
class TechErrorImageValidatorTest {

    static byte[] png(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static byte[] jpeg(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /** Yalnız PNG imzası + IHDR (piksel verisi YOK) — dev tuval iddiası. */
    static byte[] pngHeaderOnly(int w, int h) {
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        byte[] type = "IHDR".getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(ihdr.array());
        ByteBuffer b = ByteBuffer.allocate(8 + 4 + 4 + 13 + 4);
        b.put(new byte[]{ (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A });
        b.putInt(13).put(type).put(ihdr.array()).putInt((int) crc.getValue());
        return b.array();
    }

    @Test
    @DisplayName("PNG ve JPEG kabul; tür ve ölçü baytlardan")
    void acceptsPngAndJpeg() throws IOException {
        var p = TechErrorImageValidator.check(png(640, 360));
        assertThat(p.contentType()).isEqualTo("image/png");
        assertThat(p.width()).isEqualTo(640);
        assertThat(p.height()).isEqualTo(360);
        var j = TechErrorImageValidator.check(jpeg(320, 200));
        assertThat(j.contentType()).isEqualTo("image/jpeg");
        assertThat(j.width()).isEqualTo(320);
    }

    @Test
    @DisplayName("SVG, metin, uzantısı değiştirilmiş dosya, boş dosya reddedilir")
    void rejectsNonImages() {
        assertRejected("<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8));
        assertRejected("GIF89a....".getBytes(StandardCharsets.US_ASCII));
        assertRejected(new byte[0]);
        assertRejected(null);
    }

    @Test
    @DisplayName("küçük dosyada dev tuval (sıkıştırma bombası) pikseller çözülmeden reddedilir; çok küçük görsel de")
    void rejectsHugeCanvasWithoutDecoding() throws IOException {
        assertRejected(pngHeaderOnly(30_000, 30_000));
        assertRejected(pngHeaderOnly(5000, 5000));       // 25 M piksel > 12 M
        assertRejected(png(8, 8));
    }

    @Test
    @DisplayName("5 MB üstü reddedilir")
    void rejectsTooLarge() {
        byte[] big = new byte[(int) TechErrorImageValidator.MAX_BYTES + 1];
        big[0] = (byte) 0x89;
        assertRejected(big);
    }

    private static void assertRejected(byte[] data) {
        assertThatThrownBy(() -> TechErrorImageValidator.check(data))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("file"));
    }
}
