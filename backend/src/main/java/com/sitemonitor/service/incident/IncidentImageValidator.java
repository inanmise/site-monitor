package com.sitemonitor.service.incident;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.page.scan.ImageDimensions;
import com.sitemonitor.util.Msg;

/**
 * Olay kaydı görseli doğrulaması (2026-11-20, "Hızlı Giriş" — tam çözünürlük görsel). Eskiden istemci görseli 1000 px /
 * 70 KB'a küçültüyor, sunucu yalnız istemcinin bildirdiği {@code Content-Type}'a bakıyordu. Artık görsel tam çözünürlükte
 * gelir ve sunucu:
 * <ul>
 *   <li>türü BAYTLARDAN belirler (PNG / JPEG / GIF / WebP / BMP sihirli baytları — {@link ImageDimensions#type}); SVG, HTML
 *       ya da uzantısı değiştirilmiş dosya reddedilir; saklanan tür de bu türdür (istemcinin başlığı değil);</li>
 *   <li>boyutu sınırlar (varsayılan 5 MB, {@code site.monitor.incident.image-max-bytes});</li>
 *   <li>piksel ölçüsünü PİKSELLER ÇÖZÜLMEDEN başlıktan okur ({@link ImageDimensions#read}) — kenar ≤ {@value #MAX_SIDE} px,
 *       toplam ≤ 12 milyon piksel (teknik hata raporu görselleriyle aynı kural; küçük dosyada dev tuval e-posta küçültmesinde
 *       belleği tüketemez). Ölçüsü okunamayan görsel reddedilir.</li>
 * </ul>
 * Alt sınır yoktur (küçük simge / ekran parçası olağandır). Hata alan adı {@code file} ile 400 döner.
 */
public final class IncidentImageValidator {

    private IncidentImageValidator() {}

    public static final int MAX_SIDE = 6000;
    public static final long MAX_PIXELS = 12_000_000L;

    /** Doğrulanmış görsel: baytlardan belirlenen MIME türü ve piksel ölçüsü. */
    public record Checked(String contentType, int width, int height) { }

    /**
     * @param data     dosya baytları
     * @param maxBytes en büyük boyut (bayt)
     */
    public static Checked check(byte[] data, long maxBytes) {
        if (data == null || data.length == 0) {
            throw bad(Msg.t("Dosya boş. Görseli PNG ya da JPEG olarak kaydedip yeniden yükleyin.",
                    "The file is empty. Save the image as PNG or JPEG and upload it again."));
        }
        if (data.length > maxBytes) throw tooLarge(maxBytes);
        String type = ImageDimensions.type(data);
        String mime = ImageDimensions.AVIF.equals(type) ? null : ImageDimensions.mime(type);
        if (mime == null) {
            throw bad(Msg.t("Yalnız PNG, JPEG, GIF, WebP ya da BMP görsel yüklenebilir. Dosya bu türlerden biri değil (uzantısı değiştirilmiş olabilir).",
                    "Only PNG, JPEG, GIF, WebP or BMP images can be uploaded. This file isn't one of them (its extension may have been changed)."));
        }
        ImageDimensions.Size size = ImageDimensions.read(data);
        if (size == null) {
            throw bad(Msg.t("Görselin ölçüsü okunamadı; dosya bozuk olabilir. Görseli yeniden dışa aktarıp yükleyin.",
                    "The image's size couldn't be read; the file may be damaged. Export the image again and upload it."));
        }
        int w = size.width(), h = size.height();
        if (w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS) {
            throw bad(Msg.t("Görsel çok büyük (" + w + " × " + h + " piksel). Kenarı en çok 6000 piksel ve toplamı en çok 12 milyon piksel (ör. 4000 × 3000) olacak şekilde küçültüp yeniden yükleyin.",
                    "The image is too large (" + w + " × " + h + " pixels). Resize it to at most 6000 pixels per side and 12 million pixels in total (for example 4000 × 3000), then upload it again."));
        }
        return new Checked(mime, w, h);
    }

    /** "Dosya çok büyük" hatası — gövde okunmadan da (çağıranın {@code getSize()} denetimi) aynı ileti. */
    public static FieldValidationException tooLarge(long maxBytes) {
        long mb = Math.max(1, maxBytes / (1024 * 1024));
        return bad(Msg.t("Görsel en çok " + mb + " MB olabilir. Görseli küçültüp ya da JPEG olarak kaydedip yeniden yükleyin.",
                "An image can be at most " + mb + " MB. Make the image smaller or save it as JPEG, then upload it again."));
    }

    private static FieldValidationException bad(String msg) {
        return new FieldValidationException("file", msg);
    }
}
