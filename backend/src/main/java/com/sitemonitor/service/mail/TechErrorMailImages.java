package com.sitemonitor.service.mail;

import com.sitemonitor.service.EmailNotificationService.InlineImage;
import com.sitemonitor.service.report.ImageAreaAverage;
import com.sitemonitor.service.techerror.TechErrorPdfInput;
import com.sitemonitor.service.techerror.TechErrorTemplate;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Teknik hata raporu e-postasına gömülen görseller (2026-10-17, kullanıcı kararı S5: "görseller e-postada da, ≤ 1000 px; tam
 * çözünürlük PDF ekinde"). Saklanan görsel tam çözünürlüktedir; e-posta için {@link #MAX_WIDTH} × {@link #MAX_HEIGHT} içine
 * aynı alan ortalaması algoritmasıyla ({@link ImageAreaAverage} — PDF gömmesiyle aynı) küçültülür; sığan PNG / JPEG OLDUĞU
 * GİBİ gömülür (yeniden kodlama yok). PNG kaynağı PNG kalır (ekran görüntüsü asla JPEG olmaz), JPEG kaynağı JPEG
 * {@link #JPEG_QUALITY} ile; gri PNG ({@link #isGrayPng}) sRGB'ye çevrilir (PDF'teki gibi; posta istemcileri de griyi
 * soluk çiziyordu). Toplam gömülü bayt {@code budget} ile sınırlıdır (PDF ekinin sınırından ayrı): yuva sırasıyla ilk
 * sığanlar gömülür, sığmayanlar ve okunamayanlar {@link Result#skipped} listesine düşer — e-posta onların yerine "PDF
 * ekinde" kutusu çizer. Hiçbir şey geçici dosyaya yazılmaz (salt okunur kök dosya sistemi).
 */
public final class TechErrorMailImages {

    private TechErrorMailImages() {}

    /** E-postadaki en geniş görsel (kullanıcı kararı 1000 px). */
    public static final int MAX_WIDTH = 1000;
    /** Çok uzun (dikey) bir görsel e-postayı sayfalarca uzatmasın. */
    public static final int MAX_HEIGHT = 1400;
    /** Gömülü görsellerin varsayılan toplam bütçesi ({@code site.monitor.tech-error-report.mail-inline-mb}). */
    public static final long DEFAULT_BUDGET = 6L * 1024 * 1024;
    public static final float JPEG_QUALITY = 0.9f;
    /** Yuva başına Content-ID öneki — marka logosunun {@code brand-logo} kimliğiyle çakışmaz. */
    public static final String CID_PREFIX = "te-img-";

    /** Gömülmeye hazır görsel: kimlik, baytlar, tür ve piksel ölçüsü (HTML'deki en geniş gösterim). */
    public record Prepared(String slotId, String cid, byte[] data, String contentType, int width, int height) {
        public InlineImage inline() { return new InlineImage(cid, data, contentType); }
    }

    /**
     * Hazırlanan görseller (yuva kimliği → görsel, şablon sırasıyla), bütçeye sığmadığı için atlanan yuvalar
     * ({@code skipped} — görsel PDF ekinde), okunamayan / kodlanamayan yuvalar ({@code unreadable} — PDF'te de
     * "çizilemedi" kutusu; inceleme 2026-10-17: ikisi aynı metni almasın) ve toplam gömülü bayt.
     */
    public record Result(Map<String, Prepared> embedded, List<String> skipped, List<String> unreadable, long bytes) {
        public static Result empty() { return new Result(Map.of(), List.of(), List.of(), 0); }

        /** Yuvanın görseli var mı (gömülü, atlanmış ya da okunamamış)? Yoksa "bu yuvaya görsel yüklenmedi". */
        public boolean present(String slotId) {
            return embedded.containsKey(slotId) || skipped.contains(slotId) || unreadable.contains(slotId);
        }
    }

    public static String cid(String slotId) {
        return CID_PREFIX + slotId;
    }

    /** Şablonun görsel yuvaları şablon sırasıyla (bütçe ilk yuvalardan dolar — PDF'teki sıra). */
    public static List<String> slotOrder(TechErrorTemplate.Template t) {
        List<String> order = new ArrayList<>();
        if (t == null) return order;
        for (TechErrorTemplate.Section s : t.sections()) {
            if (!"images".equals(s.kind())) continue;
            for (TechErrorTemplate.Slot sl : s.slots()) order.add(sl.id());
        }
        return order;
    }

    /**
     * Rapor girdisinin görsellerini e-posta için hazırlar ({@link #MAX_WIDTH} × {@link #MAX_HEIGHT}, {@code budget} bayt).
     * Gönderici bunu PDF izninin İÇİNDE (renderPdf yükleyicisinde) çağırır: görsel baytları bellekte yalnız izinle durur.
     */
    public static Result prepare(TechErrorPdfInput in, long budget) {
        if (in == null) return Result.empty();
        return prepare(in.images(), slotOrder(in.template()), MAX_WIDTH, MAX_HEIGHT, budget);
    }

    /** Hazırlama hiç yapılamadıysa: görseli olan her yuva "PDF ekinde" kutusu alır (hiçbiri "yüklenmedi" görünmez). */
    public static Result allSkipped(TechErrorPdfInput in) {
        List<String> skipped = new ArrayList<>();
        if (in != null && in.images() != null) {
            for (String slot : slotOrder(in.template())) if (in.images().containsKey(slot)) skipped.add(slot);
        }
        return new Result(Map.of(), skipped, List.of(), 0);
    }

    /**
     * {@code slotOrder} sırasıyla hazırlar: ilk sığanlar gömülür (bütçe {@code budget} bayt), sığmayan / okunamayan yuva
     * atlanır. {@code images} null ya da boşsa boş sonuç.
     */
    public static Result prepare(Map<String, TechErrorPdfInput.Image> images, List<String> slotOrder, int maxW, int maxH, long budget) {
        Map<String, Prepared> out = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        long total = 0;
        if (images == null || images.isEmpty() || slotOrder == null) return new Result(out, skipped, unreadable, 0);
        for (String slot : slotOrder) {
            TechErrorPdfInput.Image img = images.get(slot);
            if (img == null) continue;
            Prepared p = one(slot, img, maxW, maxH);
            if (p == null) {
                unreadable.add(slot);
                continue;
            }
            if (total + p.data().length > budget) {
                skipped.add(slot);
                continue;
            }
            total += p.data().length;
            out.put(slot, p);
        }
        return new Result(out, skipped, unreadable, total);
    }

    /** Tek görsel; okunamayan ya da kodlanamayan görsel için {@code null}. */
    static Prepared one(String slot, TechErrorPdfInput.Image img, int maxW, int maxH) {
        byte[] bytes = img == null ? null : img.data();
        if (bytes == null || bytes.length == 0) return null;
        String type = contentType(bytes);
        boolean gray = isGrayPng(bytes);
        // Sığan ve posta istemcisinin bildiği tür: olduğu gibi (yeniden kodlama yok — en keskin hâli)
        if (type != null && !gray && img.width() > 0 && img.height() > 0 && img.width() <= maxW && img.height() <= maxH) {
            return new Prepared(slot, cid(slot), bytes, type, img.width(), img.height());
        }
        BufferedImage src;
        try {
            // Bellekte okunur: salt okunur kök dosya sisteminde geçici dosya açılmaz
            src = ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes)));
        } catch (Exception e) {
            src = null;
        }
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) return null;
        double f = Math.min(1.0, Math.min((double) maxW / src.getWidth(), (double) maxH / src.getHeight()));
        BufferedImage dst;
        if (f >= 1.0) {
            dst = ImageAreaAverage.toSrgb(src);
        } else {
            // +1e-9: kayan nokta 1000 / 3000 × 3000'i 999,99… yapıp bir piksel kaybettirmesin (PdfCanvas ile aynı)
            int tw = Math.max(1, (int) Math.floor(src.getWidth() * f + 1e-9));
            int th = Math.max(1, (int) Math.floor(src.getHeight() * f + 1e-9));
            dst = ImageAreaAverage.resize(src, tw, th);
        }
        boolean jpeg = "image/jpeg".equals(type);
        byte[] enc = jpeg ? encodeJpeg(dst, JPEG_QUALITY) : encodePng(dst);
        if (enc == null) return null;
        return new Prepared(slot, cid(slot), enc, jpeg ? "image/jpeg" : "image/png", dst.getWidth(), dst.getHeight());
    }

    /** Sihirli baytlardan tür: PNG / JPEG; başka her şey null (yeniden kodlanır — PNG). */
    static String contentType(byte[] b) {
        if (b == null || b.length < 4) return null;
        if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if ((b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8) return "image/jpeg";
        return null;
    }

    /** PNG imzası + IHDR renk türü 0 (gri) ya da 4 (gri + saydam) — {@code PdfCanvas.isGrayPng} ile aynı kural. */
    static boolean isGrayPng(byte[] b) {
        return b != null && b.length > 25 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && (b[25] == 0 || b[25] == 4);
    }

    static byte[] encodePng(BufferedImage img) {
        ImageWriter w = null;
        try {
            var it = ImageIO.getImageWritersByFormatName("png");
            if (!it.hasNext()) return null;
            w = it.next();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
            try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bo)) {
                w.setOutput(out);
                w.write(null, new IIOImage(img, null, null), w.getDefaultWriteParam());
            }
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (w != null) w.dispose();
        }
    }

    static byte[] encodeJpeg(BufferedImage img, float quality) {
        ImageWriter w = null;
        try {
            var it = ImageIO.getImageWritersByFormatName("jpeg");
            if (!it.hasNext()) return null;
            w = it.next();
            // JPEG saydamlık taşımaz: RGB kopya (beyaz zemin)
            BufferedImage rgb = img.getType() == BufferedImage.TYPE_INT_RGB ? img
                    : new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            if (rgb != img) {
                java.awt.Graphics2D g = rgb.createGraphics();
                try {
                    g.setColor(java.awt.Color.WHITE);
                    g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                    g.drawImage(img, 0, 0, null);
                } finally {
                    g.dispose();
                }
            }
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
            try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bo)) {
                w.setOutput(out);
                w.write(null, new IIOImage(rgb, null, null), p);
            }
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (w != null) w.dispose();
        }
    }
}
