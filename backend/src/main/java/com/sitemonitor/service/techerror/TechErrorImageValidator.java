package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.util.Msg;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.Iterator;

/**
 * Grafik görseli doğrulaması (2026-10-13). Haftalık raporun zayıf noktası (yalnız istemcinin bildirdiği Content-Type)
 * burada kapanır:
 * <ul>
 *   <li>tür BAYTLARDAN belirlenir (PNG / JPEG sihirli baytları) — SVG/HTML ya da uzantısı değiştirilmiş dosya reddedilir;</li>
 *   <li>boyut ≤ 5 MB;</li>
 *   <li>piksel ölçüsü PİKSELLER ÇÖZÜLMEDEN başlıktan okunur (ImageReader.getWidth/getHeight) — kenar ≤ 6000 px, toplam
 *       ≤ 12 milyon piksel: küçük dosyada dev tuval (sıkıştırma bombası) PDF üretiminde belleği tüketemez.</li>
 * </ul>
 */
public final class TechErrorImageValidator {

    private TechErrorImageValidator() {}

    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final int MAX_SIDE = 6000;
    public static final long MAX_PIXELS = 12_000_000L;
    public static final int MIN_SIDE = 16;

    public record Checked(String contentType, int width, int height) { }

    public static Checked check(byte[] data) {
        if (data == null || data.length == 0) {
            throw bad(Msg.t("Dosya boş. Görseli PNG ya da JPEG olarak kaydedip yeniden yükleyin.",
                    "The file is empty. Save the image as PNG or JPEG and upload it again."));
        }
        if (data.length > MAX_BYTES) {
            throw bad(Msg.t("Görsel en çok 5 MB olabilir. Ekran görüntüsünü küçültüp ya da JPEG olarak kaydedip yeniden yükleyin.",
                    "An image can be at most 5 MB. Make the screenshot smaller or save it as JPEG, then upload it again."));
        }
        String type = sniff(data);
        if (type == null) {
            throw bad(Msg.t("Yalnız PNG ya da JPEG görsel yüklenebilir. Dosya bu türlerden biri değil (uzantısı değiştirilmiş olabilir).",
                    "Only PNG or JPEG images can be uploaded. This file isn't one of them (its extension may have been changed)."));
        }
        int[] size = dimensions(data, "image/png".equals(type) ? "png" : "jpeg");
        if (size == null) {
            throw bad(Msg.t("Görsel okunamadı; dosya bozuk olabilir. Görseli yeniden dışa aktarıp yükleyin.",
                    "The image couldn't be read; the file may be damaged. Export the image again and upload it."));
        }
        int w = size[0], h = size[1];
        if (w < MIN_SIDE || h < MIN_SIDE) {
            throw bad(Msg.t("Görsel çok küçük (en az 16 × 16 piksel olmalı).", "The image is too small (at least 16 × 16 pixels)."));
        }
        if (w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS) {
            throw bad(Msg.t("Görsel çok büyük (" + w + " × " + h + " piksel). Kenarı en çok 6000 piksel ve toplamı en çok 12 milyon piksel (ör. 4000 × 3000) olacak şekilde küçültüp yeniden yükleyin.",
                    "The image is too large (" + w + " × " + h + " pixels). Resize it to at most 6000 pixels per side and 12 million pixels in total (for example 4000 × 3000), then upload it again."));
        }
        return new Checked(type, w, h);
    }

    /** PNG: 89 50 4E 47 0D 0A 1A 0A; JPEG: FF D8 FF. Başka her şey null. */
    static String sniff(byte[] d) {
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && d[4] == 0x0D && d[5] == 0x0A && d[6] == 0x1A && d[7] == 0x0A) return "image/png";
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "image/jpeg";
        return null;
    }

    /** Başlıktan genişlik/yükseklik — pikselleri çözmez. Okunamazsa null. */
    static int[] dimensions(byte[] data, String format) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
        if (!readers.hasNext()) return null;
        ImageReader reader = readers.next();
        // Bellek akışı: ImageIO.createImageInputStream önbellek açıkken geçici DOSYA açar — prod kök dosya sistemi salt okunur.
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(in, true, true);
            return new int[]{ reader.getWidth(0), reader.getHeight(0) };
        } catch (Exception e) {
            return null;
        } finally {
            reader.dispose();
        }
    }

    private static FieldValidationException bad(String msg) {
        return new FieldValidationException("file", msg);
    }
}
