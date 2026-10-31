package com.sitemonitor.service.page.scan;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Locale;

/**
 * Ölçülmüş görsel kazancı (2026-11, kaynak analizi — inceleme P11): JPEG / PNG görseli SUNUCUDA çözüp yeniden kodlar ve
 * yeni boyutu döner — "%60 tahmini" yerine ölçülmüş sayı.
 *
 * <p>Kural: saydam PNG → PNG (saydamlık korunur); diğer her şey (JPEG, saydam olmayan PNG) → JPEG kalite
 * {@value #JPEG_QUALITY}. Görsel KÜÇÜLTÜLMEZ: ekran boyutu bilinmediği için piksel boyutu korunur. Girdi ve çıktı yalnız
 * bellekte ({@link MemoryCacheImageInputStream} / {@link MemoryCacheImageOutputStream} — salt okunur kök dosya sisteminde
 * geçici dosya YOK); çıktı tutulmaz, yalnız sayılır. Çözülemeyen (CMYK JPEG, bozuk, bilinmeyen biçim) ya da piksel
 * tavanını aşan görsel {@code null} döner — çağıran eski tahmini kullanır. Asla fırlatmaz.
 */
public final class ImageOptimizer {

    /** JPEG yeniden kodlama kalitesi (web için yaygın denge). */
    public static final float JPEG_QUALITY = 0.82f;

    /** Yeniden kodlama sonucu: yeni boyut (bayt) + kullanılan biçim ({@code jpeg} | {@code png}). */
    public record Outcome(long bytes, String format) {}

    private ImageOptimizer() {}

    /**
     * @param data      görselin (açılmış) gövdesi
     * @param maxPixels kabul edilen en çok piksel (genişlik × yükseklik) — çözmeden ÖNCE başlıktan denetlenir
     */
    public static Outcome optimize(byte[] data, long maxPixels) {
        if (data == null || data.length == 0) return null;
        try (ImageInputStream iis = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                String fmt = reader.getFormatName() == null ? "" : reader.getFormatName().toLowerCase(Locale.ROOT);
                boolean png = fmt.contains("png");
                boolean jpeg = fmt.contains("jpeg") || fmt.contains("jpg");
                if (!png && !jpeg) return null;
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || (long) w * h > maxPixels) return null;
                BufferedImage img = reader.read(0);
                if (img == null) return null;
                if (png && transparent(img)) return new Outcome(encode(img, "png"), "png");
                return new Outcome(encode(jpegCompatible(img), "jpeg"), "jpeg");
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;   // çözülemedi → ölçülemedi (tahmin kullanılır). Bellek, çağıranın bellek izniyle sınırlıdır.
        }
    }

    /** Gerçekten saydam piksel var mı — alfa kanalı satır satır taranır (doğrusal, piksel sayısıyla sınırlı). */
    static boolean transparent(BufferedImage img) {
        ColorModel cm = img.getColorModel();
        if (!cm.hasAlpha()) return false;
        if (cm instanceof IndexColorModel icm) return icm.getTransparency() != java.awt.Transparency.OPAQUE;
        WritableRaster alpha = img.getAlphaRaster();
        if (alpha == null) return true;   // bilinmiyor: saydamlığı korumak güvenli taraf
        int bits = cm.getComponentSize(cm.getNumComponents() - 1);
        int max = bits >= 31 ? Integer.MAX_VALUE : (1 << bits) - 1;
        int w = alpha.getWidth();
        int h = alpha.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            alpha.getSamples(0, y, w, 1, 0, row);
            for (int x = 0; x < w; x++) if (row[x] < max) return true;
        }
        return false;
    }

    /** JPEG yazıcısının doğrudan alabildiği türe çevirir (gri tek kanal korunur, alfa beyaz zemine düşer). */
    static BufferedImage jpegCompatible(BufferedImage img) {
        int t = img.getType();
        if (t == BufferedImage.TYPE_3BYTE_BGR || t == BufferedImage.TYPE_INT_RGB || t == BufferedImage.TYPE_BYTE_GRAY) return img;
        boolean gray = img.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY && !img.getColorModel().hasAlpha();
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(),
                gray ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Kodlar ve yalnız SAYAR (çıktı tutulmaz). */
    static long encode(BufferedImage img, String format) throws java.io.IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) throw new java.io.IOException("yazıcı yok: " + format);
        ImageWriter writer = writers.next();
        CountingStream counter = new CountingStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(counter)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if ("jpeg".equals(format) && param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(img, null, null), param);
            ios.flush();
        } finally {
            writer.dispose();
        }
        return counter.count;
    }

    /** Baytları sayan, tutmayan çıkış akışı. */
    private static final class CountingStream extends OutputStream {
        long count;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            count += len;
        }
    }
}
