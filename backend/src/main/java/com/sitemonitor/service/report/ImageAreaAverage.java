package com.sitemonitor.service.report;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * ALAN ORTALAMASIYLA küçültme (2026-10-16, kullanıcı isteği: "yüklenen görsellerde rakamlar, yazılar okunur kalsın") —
 * istemcideki {@code imagePrep.areaResample}'ın Java eşi. Her hedef pikseli, kaynakta kapladığı dikdörtgendeki
 * piksellerin kapsama oranıyla ağırlıklı ortalamasıdır: örtüşme (titreyen ince çizgi) yok, Lanczos / bikübiğin harf
 * çevresindeki halesi yok, tam katta birebir ortalama. Ağırlıklar TAMSAYI (kaynak sütun c = [c·dstW, (c+1)·dstW), hedef
 * sütun x = [x·srcW, (x+1)·srcW)); ayrılabilir ve satır satır akar (bellek O(dstW)); saydamlık önçarpımlı ortalanır.
 * AWT {@code getScaledInstance} kullanılmaz (Toolkit / eşzamansız üretim — başsız sunucuda güvenilmez). Yalnız küçültür.
 */
public final class ImageAreaAverage {

    private ImageAreaAverage() {}

    public static BufferedImage resize(BufferedImage src, int dstW, int dstH) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (dstW < 1 || dstH < 1 || dstW > srcW || dstH > srcH) {
            throw new IllegalArgumentException("ImageAreaAverage yalnız küçültür");
        }
        boolean alpha = src.getColorModel().hasAlpha();
        Rows rows = new Rows(src);
        BufferedImage out = new BufferedImage(dstW, dstH, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        int[] hd = new int[srcW];
        long[] hw = new long[srcW];
        for (int c = 0; c < srcW; c++) {
            long start = (long) c * dstW;
            int d = (int) (start / srcW);
            hd[c] = d;
            hw[c] = Math.min(start + dstW, (long) (d + 1) * srcW) - start;
        }
        double norm = (double) srcW * srcH;
        double[] rowH = new double[dstW * 4];
        double[] acc = new double[dstW * 4];
        double[] next = new double[dstW * 4];
        int[] line = new int[srcW];
        int[] outLine = new int[dstW];
        int dRow = 0;
        for (int r = 0; r < srcH; r++) {
            rows.read(r, line);
            Arrays.fill(rowH, 0);
            for (int c = 0; c < srcW; c++) {
                int argb = line[c];
                int a = alpha ? argb >>> 24 : 255;
                double k = a / 255.0;
                double pr = ((argb >> 16) & 0xff) * k;
                double pg = ((argb >> 8) & 0xff) * k;
                double pb = (argb & 0xff) * k;
                double w1 = hw[c];
                double w2 = dstW - w1;
                int i = hd[c] * 4;
                rowH[i] += pr * w1;
                rowH[i + 1] += pg * w1;
                rowH[i + 2] += pb * w1;
                rowH[i + 3] += a * w1;
                if (w2 > 0) {
                    rowH[i + 4] += pr * w2;
                    rowH[i + 5] += pg * w2;
                    rowH[i + 6] += pb * w2;
                    rowH[i + 7] += a * w2;
                }
            }
            long start = (long) r * dstH;
            long bound = (long) (dRow + 1) * srcH;
            double v1 = Math.min(start + dstH, bound) - start;
            double v2 = dstH - v1;
            for (int i = 0; i < rowH.length; i++) acc[i] += rowH[i] * v1;
            if (v2 > 0) for (int i = 0; i < rowH.length; i++) next[i] += rowH[i] * v2;
            if (start + dstH >= bound) {
                for (int x = 0; x < dstW; x++) {
                    int i = x * 4;
                    double av = acc[i + 3] / norm;
                    if (av <= 0) {
                        outLine[x] = 0;
                    } else {
                        double un = 255.0 / av;
                        int rr = clamp(Math.round(acc[i] / norm * un));
                        int gg = clamp(Math.round(acc[i + 1] / norm * un));
                        int bb = clamp(Math.round(acc[i + 2] / norm * un));
                        int aa = alpha ? clamp(Math.round(av)) : 255;
                        outLine[x] = (aa << 24) | (rr << 16) | (gg << 8) | bb;
                    }
                }
                out.setRGB(0, dRow, dstW, 1, outLine, 0, dstW);
                double[] t = acc;
                acc = next;
                next = t;
                Arrays.fill(next, 0);
                dRow++;
            }
        }
        return out;
    }

    private static int clamp(long v) {
        return (int) Math.max(0, Math.min(255, v));
    }

    /** Gri renk uzaylı görsel mi (gri PNG / JPEG, 8 / 16 bit, gri + saydam)? */
    public static boolean isGray(BufferedImage img) {
        return img.getColorModel().getColorSpace().getType() == java.awt.color.ColorSpace.TYPE_GRAY;
    }

    /**
     * Gri görseli değerleri DEĞİŞMEDEN sRGB'ye (INT_RGB / INT_ARGB) çevirir; gri değilse aynısını döner. Java gri renk
     * uzayını DOĞRUSAL sayar: getRGB, drawImage ve PDFBox gri 128'i 188 yapıyordu — ızgara çizgisi ve rakamlar PDF'te
     * soluyordu (inceleme 2026-10-16). Tarayıcı gri PNG'yi değeriyle gösterir; PDF de öyle basar.
     */
    public static BufferedImage toSrgb(BufferedImage src) {
        if (!isGray(src)) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        boolean alpha = src.getColorModel().hasAlpha();
        BufferedImage out = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Rows rows = new Rows(src);
        int[] line = new int[w];
        for (int y = 0; y < h; y++) {
            rows.read(y, line);
            out.setRGB(0, y, w, 1, line, 0, w);
        }
        return out;
    }

    /**
     * Satır okuyucu: renkli görselde getRGB; GRİ görselde raster örnekleri doğrudan (bant 0 gri, varsa bant 1 saydamlık),
     * bit derinliğine göre 8 bite ölçeklenir — getRGB / drawImage griyi doğrusal sayıp açıyordu (gri + saydam dahil).
     */
    private static final class Rows {
        private final BufferedImage src;
        private final boolean gray;
        private final boolean alpha;
        private final boolean premultiplied;
        private final int maxG;
        private final int maxA;
        private final int[] gbuf;
        private final int[] abuf;

        Rows(BufferedImage src) {
            this.src = src;
            this.gray = isGray(src);
            java.awt.image.ColorModel cm = src.getColorModel();
            this.alpha = cm.hasAlpha();
            this.premultiplied = cm.isAlphaPremultiplied();
            int bands = src.getRaster().getNumBands();
            this.maxG = gray ? (1 << cm.getComponentSize(0)) - 1 : 255;
            this.maxA = gray && alpha && bands > 1 ? (1 << cm.getComponentSize(1)) - 1 : 255;
            this.gbuf = gray ? new int[src.getWidth()] : null;
            this.abuf = gray && alpha && bands > 1 ? new int[src.getWidth()] : null;
        }

        void read(int y, int[] line) {
            int w = src.getWidth();
            if (!gray) {
                src.getRGB(0, y, w, 1, line, 0, w);
                return;
            }
            java.awt.image.WritableRaster raster = src.getRaster();
            raster.getSamples(0, y, w, 1, 0, gbuf);
            if (abuf != null) raster.getSamples(0, y, w, 1, 1, abuf);
            for (int x = 0; x < w; x++) {
                int a = abuf == null ? 255 : (int) Math.round(abuf[x] * 255.0 / maxA);
                int g = (int) Math.round(gbuf[x] * 255.0 / maxG);
                if (premultiplied && a > 0 && a < 255) g = Math.min(255, (int) Math.round(g * 255.0 / a));
                line[x] = (a << 24) | (g << 16) | (g << 8) | g;
            }
        }
    }
}
