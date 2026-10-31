package com.sitemonitor.service.page.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Ölçülmüş görsel kazancı: saydam PNG → PNG, diğerleri → JPEG 0,82; çözülemeyen / tavan üstü → null; küçültme yok. */
class ImageOptimizerTest {

    static BufferedImage noise(int w, int h, int type, Integer alpha) {
        BufferedImage img = new BufferedImage(w, h, type);
        Random r = new Random(7);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = r.nextInt(0x1000000);
                img.setRGB(x, y, ((alpha == null ? 0xFF : alpha) << 24) | rgb);
            }
        }
        return img;
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    static byte[] jpeg(BufferedImage img, float q) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bos)) {
            w.setOutput(ios);
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(q);
            w.write(null, new IIOImage(img, null, null), p);
        } finally {
            w.dispose();
        }
        return bos.toByteArray();
    }

    @Test
    @DisplayName("yüksek kaliteli JPEG 0,82 ile yeniden kodlanınca küçülür")
    void jpegShrinks() throws IOException {
        byte[] q100 = jpeg(noise(300, 200, BufferedImage.TYPE_INT_RGB, null), 1.0f);
        ImageOptimizer.Outcome o = ImageOptimizer.optimize(q100, 40_000_000L);
        assertThat(o).isNotNull();
        assertThat(o.format()).isEqualTo("jpeg");
        assertThat(o.bytes()).isPositive().isLessThan(q100.length);
    }

    @Test
    @DisplayName("PNG: saydam → PNG; alfa kanalı olup tamamen opak → JPEG; RGB PNG → JPEG; gri PNG → JPEG")
    void pngRules() throws IOException {
        assertThat(ImageOptimizer.optimize(png(noise(64, 64, BufferedImage.TYPE_INT_ARGB, 0x40)), 40_000_000L).format())
                .isEqualTo("png");
        assertThat(ImageOptimizer.optimize(png(noise(64, 64, BufferedImage.TYPE_INT_ARGB, 0xFF)), 40_000_000L).format())
                .isEqualTo("jpeg");
        byte[] rgb = png(noise(200, 200, BufferedImage.TYPE_INT_RGB, null));
        ImageOptimizer.Outcome o = ImageOptimizer.optimize(rgb, 40_000_000L);
        assertThat(o.format()).isEqualTo("jpeg");
        assertThat(o.bytes()).isLessThan(rgb.length);
        BufferedImage gray = new BufferedImage(80, 80, BufferedImage.TYPE_BYTE_GRAY);
        assertThat(ImageOptimizer.optimize(png(gray), 40_000_000L).format()).isEqualTo("jpeg");
    }

    @Test
    @DisplayName("çözülemeyen girdi, desteklenmeyen biçim (GIF) ve piksel tavanı üstü → null (çağıran tahmini kullanır)")
    void unsupported() throws IOException {
        assertThat(ImageOptimizer.optimize(null, 40_000_000L)).isNull();
        assertThat(ImageOptimizer.optimize(new byte[]{1, 2, 3, 4, 5}, 40_000_000L)).isNull();
        ByteArrayOutputStream gif = new ByteArrayOutputStream();
        ImageIO.write(noise(10, 10, BufferedImage.TYPE_INT_RGB, null), "gif", gif);
        assertThat(ImageOptimizer.optimize(gif.toByteArray(), 40_000_000L)).isNull();
        assertThat(ImageOptimizer.optimize(png(noise(100, 100, BufferedImage.TYPE_INT_RGB, null)), 9_999L)).isNull();
    }

    /** Gerçek 16 bit RGBA PNG (yarı saydam) — ImageIO 16 bit kanalları yazar. */
    static byte[] png16Rgba(int w, int h) throws IOException {
        java.awt.color.ColorSpace cs = java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_sRGB);
        java.awt.image.ComponentColorModel cm = new java.awt.image.ComponentColorModel(cs, new int[]{16, 16, 16, 16}, true,
                false, java.awt.Transparency.TRANSLUCENT, java.awt.image.DataBuffer.TYPE_USHORT);
        java.awt.image.WritableRaster r = cm.createCompatibleWritableRaster(w, h);
        Random rnd = new Random(3);
        int[] px = new int[4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                px[0] = rnd.nextInt(65536); px[1] = rnd.nextInt(65536); px[2] = rnd.nextInt(65536); px[3] = 30000;
                r.setPixel(x, y, px);
            }
        }
        return png(new BufferedImage(cm, r, false, null));
    }

    @Test
    @DisplayName("16 bit RGBA PNG: başlık hesabı 12 B/piksel; çözmeden ÖNCE izin denetlenir — yetmezse null, yeterse ölçülür")
    void sixteenBitBudget() throws IOException {
        byte[] data = png16Rgba(120, 80);
        ImageDimensions.Header h = ImageDimensions.header(data);
        assertThat(h).isEqualTo(new ImageDimensions.Header(120, 80, "png", 4, 16, false));
        long need = ImageOptimizer.workingBytes(h, data.length);
        assertThat(need).isEqualTo(120L * 80 * 12 + data.length);
        assertThat(ImageOptimizer.optimize(data, 120, 80, need / 2)).as("izin yetmez → çözülmez").isNull();
        ImageOptimizer.Outcome ok = ImageOptimizer.optimize(data, 120, 80, need);
        assertThat(ok).isNotNull();
        assertThat(ok.format()).as("yarı saydam").isEqualTo("png");
    }

    @Test
    @DisplayName("okuyucunun bildirdiği boyut izin alınan başlıktan farklı → çözülmez")
    void readerSizeMustMatchPermit() throws IOException {
        byte[] data = png(noise(64, 48, BufferedImage.TYPE_INT_RGB, null));
        assertThat(ImageOptimizer.optimize(data, 64, 48, 10_000_000L)).isNotNull();
        assertThat(ImageOptimizer.optimize(data, 32, 48, 10_000_000L)).isNull();
        assertThat(ImageOptimizer.optimize(data, 64, 47, 10_000_000L)).isNull();
    }
}
