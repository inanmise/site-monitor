package com.sitemonitor.service.page.scan;

/**
 * Görselin piksel boyutunu gövdenin İLK baytlarından okur (2026-10-31, kaynak analizi) — görseli çözmeden, yalnız başlıktan.
 * PNG (IHDR), GIF, BMP, WebP (VP8 / VP8L / VP8X) ve JPEG (SOF işaretçisi, ilk baytlar içinde) desteklenir; tanınmayan,
 * eksik ya da bozuk başlık {@code null} döner (asla fırlatmaz). Döngüler girdinin uzunluğuyla sınırlıdır.
 */
public final class ImageDimensions {

    /** Okunacak baytlar — JPEG'de SOF, büyük EXIF bloğunun ardında kalabilir. */
    public static final int HEAD_BYTES = 256 * 1024;

    /** Kabul edilen en büyük kenar (bozuk başlık saçma bir sayı vermesin). */
    static final int MAX_SIDE = 100_000;

    public record Size(int width, int height) {}

    private ImageDimensions() {}

    public static Size read(byte[] b) {
        if (b == null || b.length < 10) return null;
        try {
            Size s;
            if (isPng(b)) s = png(b);
            else if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') s = gif(b);
            else if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) s = jpeg(b);
            else if (b[0] == 'B' && b[1] == 'M') s = bmp(b);
            else if (b.length >= 30 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') s = webp(b);
            else s = null;
            return valid(s) ? s : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean valid(Size s) {
        return s != null && s.width() > 0 && s.height() > 0 && s.width() <= MAX_SIDE && s.height() <= MAX_SIDE;
    }

    /**
     * Çözme bilgisi (2026-11, ölçülmüş görsel kazancının bellek izni — inceleme O-2): yalnız PNG ve JPEG.
     *
     * @param format   {@code png} | {@code jpeg}
     * @param channels kanal sayısı (PNG renk türünden: gri 1, RGB 3, paletli 1, gri+alfa 2, RGBA 4; JPEG bileşen sayısı)
     * @param bitDepth örnek başına bit (PNG IHDR bit derinliği; JPEG kesinliği)
     * @param palette  paletli PNG (piksel = dizin)
     */
    public record Header(int width, int height, String format, int channels, int bitDepth, boolean palette) {
        /** Çözülmüş görüntünün piksel başına baytı (alt bayt derinlikleri 1 bayta yuvarlanır — güvenli taraf). */
        public int bytesPerPixel() {
            if (palette) return 1;
            return channels * (bitDepth > 8 ? 2 : 1);
        }
    }

    /** PNG / JPEG başlığından çözme bilgisi; tanınmayan / bozuk / desteklenmeyen (ör. JPEG'de 1 ve 3 dışı bileşen) → null. */
    public static Header header(byte[] b) {
        if (b == null || b.length < 26) return null;
        try {
            Header h = null;
            if (isPng(b)) h = pngHeader(b);
            else if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) h = jpegHeader(b);
            return h != null && valid(new Size(h.width(), h.height())) ? h : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Header pngHeader(byte[] b) {
        int depth = b[24] & 0xFF;
        int colorType = b[25] & 0xFF;
        if (depth != 1 && depth != 2 && depth != 4 && depth != 8 && depth != 16) return null;
        int channels;
        boolean palette = false;
        switch (colorType) {
            case 0 -> channels = 1;
            case 2 -> channels = 3;
            case 3 -> { channels = 1; palette = true; }
            case 4 -> channels = 2;
            case 6 -> channels = 4;
            default -> { return null; }
        }
        return new Header(be32(b, 16), be32(b, 20), "png", channels, depth, palette);
    }

    /** JPEG: {@link #jpeg} ile aynı işaretçi yürüyüşü; SOF'tan kesinlik ve bileşen sayısı (yalnız 1 = gri, 3 = renkli). */
    private static Header jpegHeader(byte[] b) {
        int i = 2;
        while (i + 9 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) return null;
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xFF) { i++; continue; }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { i += 2; continue; }
            if (marker == 0xD9 || marker == 0xDA) return null;
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (len < 2) return null;
            boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                int precision = b[i + 4] & 0xFF;
                int h = ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF);
                int w = ((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF);
                int comps = b[i + 9] & 0xFF;
                if (comps != 1 && comps != 3) return null;   // CMYK / YCCK: çözücü desteklemez
                return new Header(w, h, "jpeg", comps, precision, false);
            }
            i += 2 + len;
        }
        return null;
    }

    private static boolean isPng(byte[] b) {
        return b.length >= 24 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[12] == 'I' && b[13] == 'H' && b[14] == 'D' && b[15] == 'R';
    }

    private static Size png(byte[] b) {
        return new Size(be32(b, 16), be32(b, 20));
    }

    private static Size gif(byte[] b) {
        return new Size(le16(b, 6), le16(b, 8));
    }

    private static Size bmp(byte[] b) {
        if (b.length < 26) return null;
        int headerSize = le32(b, 14);
        if (headerSize == 12) return new Size(le16(b, 18), le16(b, 20));   // OS/2 çekirdek başlığı
        if (headerSize < 40) return null;
        return new Size(Math.abs(le32(b, 18)), Math.abs(le32(b, 22)));
    }

    private static Size webp(byte[] b) {
        String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        switch (chunk) {
            case "VP8 " -> {
                // Anahtar çerçeve imzası 9D 01 2A, ardından 14 bitlik genişlik / yükseklik
                if (b.length < 30 || (b[23] & 0xFF) != 0x9D || (b[24] & 0xFF) != 0x01 || (b[25] & 0xFF) != 0x2A) return null;
                return new Size(le16(b, 26) & 0x3FFF, le16(b, 28) & 0x3FFF);
            }
            case "VP8L" -> {
                if (b.length < 25 || (b[20] & 0xFF) != 0x2F) return null;
                int b1 = b[21] & 0xFF, b2 = b[22] & 0xFF, b3 = b[23] & 0xFF, b4 = b[24] & 0xFF;
                int w = 1 + (((b2 & 0x3F) << 8) | b1);
                int h = 1 + (((b4 & 0x0F) << 10) | (b3 << 2) | ((b2 & 0xC0) >> 6));
                return new Size(w, h);
            }
            case "VP8X" -> {
                if (b.length < 30) return null;
                int w = 1 + ((b[24] & 0xFF) | ((b[25] & 0xFF) << 8) | ((b[26] & 0xFF) << 16));
                int h = 1 + ((b[27] & 0xFF) | ((b[28] & 0xFF) << 8) | ((b[29] & 0xFF) << 16));
                return new Size(w, h);
            }
            default -> {
                return null;
            }
        }
    }

    /** JPEG: işaretçileri sırayla geçer; SOF0–SOF15 (DHT C4, JPG C8, DAC CC hariç) boyutu taşır. */
    private static Size jpeg(byte[] b) {
        int i = 2;
        while (i + 9 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) return null;
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xFF) { i++; continue; }                         // dolgu
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { i += 2; continue; }
            if (marker == 0xD9 || marker == 0xDA) return null;            // görüntü verisi / son — SOF bulunamadı
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (len < 2) return null;
            boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                int h = ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF);
                int w = ((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF);
                return new Size(w, h);
            }
            i += 2 + len;
        }
        return null;
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
}
