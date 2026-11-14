package com.sitemonitor.service.page.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Görsel TÜRÜ yalnız sihirli baytlardan (2026-11-14, görsel aracı): kabul edilen altı tür gerçek kodlayıcı çıktıları / elle
 * başlıklarla; SVG, HTML, ICO, TIFF, HEIC, boş ve kısa gövde reddedilir. AVIF boyutu {@code ispe} kutusundan.
 */
class ImageTypeTest {

    static byte[] encode(String format, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, "jpg".equals(format) || "bmp".equals(format)
                ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, format, bos)).as(format).isTrue();
        return bos.toByteArray();
    }

    static byte[] webp(int w, int h) {
        byte[] b = new byte[40];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, b, 8, 4);
        System.arraycopy("VP8X".getBytes(StandardCharsets.US_ASCII), 0, b, 12, 4);
        int ww = w - 1, hh = h - 1;
        b[24] = (byte) ww; b[25] = (byte) (ww >> 8); b[26] = (byte) (ww >> 16);
        b[27] = (byte) hh; b[28] = (byte) (hh >> 8); b[29] = (byte) (hh >> 16);
        return b;
    }

    /** {@code ftyp} (ana marka + uyumlu markalar) + isteğe bağlı {@code ispe} kutuları. */
    static byte[] isoBmff(String major, String[] compatible, int[][] ispe) {
        int ftyp = 16 + 4 * compatible.length;
        byte[] b = new byte[ftyp + ispe.length * 20 + 16];
        be32(b, 0, ftyp);
        ascii(b, 4, "ftyp");
        ascii(b, 8, major);
        for (int i = 0; i < compatible.length; i++) ascii(b, 16 + 4 * i, compatible[i]);
        int o = ftyp;
        for (int[] wh : ispe) {
            be32(b, o, 20);
            ascii(b, o + 4, "ispe");
            be32(b, o + 12, wh[0]);
            be32(b, o + 16, wh[1]);
            o += 20;
        }
        return b;
    }

    private static void ascii(byte[] b, int o, String s) {
        System.arraycopy(s.getBytes(StandardCharsets.US_ASCII), 0, b, o, 4);
    }

    private static void be32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24); b[o + 1] = (byte) (v >>> 16); b[o + 2] = (byte) (v >>> 8); b[o + 3] = (byte) v;
    }

    @Test
    @DisplayName("kabul edilen altı tür: PNG, JPEG, GIF, BMP (gerçek kodlayıcı), WebP ve AVIF (başlık) — MIME ve uzantı eşlemesiyle")
    void acceptedTypes() throws Exception {
        assertThat(ImageDimensions.type(encode("png", 4, 4))).isEqualTo("png");
        assertThat(ImageDimensions.type(encode("jpg", 4, 4))).isEqualTo("jpeg");
        assertThat(ImageDimensions.type(encode("gif", 4, 4))).isEqualTo("gif");
        assertThat(ImageDimensions.type(encode("bmp", 4, 4))).isEqualTo("bmp");
        assertThat(ImageDimensions.type(webp(300, 200))).isEqualTo("webp");
        assertThat(ImageDimensions.type(isoBmff("avif", new String[]{"mif1", "miaf"}, new int[0][]))).isEqualTo("avif");
        assertThat(ImageDimensions.type(isoBmff("mif1", new String[]{"miaf", "avif"}, new int[0][])))
                .as("uyumlu markalarda avif").isEqualTo("avif");
        assertThat(ImageDimensions.type(isoBmff("avis", new String[]{"msf1"}, new int[0][]))).as("hareketli AVIF").isEqualTo("avif");

        assertThat(ImageDimensions.mime("png")).isEqualTo("image/png");
        assertThat(ImageDimensions.mime("jpeg")).isEqualTo("image/jpeg");
        assertThat(ImageDimensions.mime("avif")).isEqualTo("image/avif");
        assertThat(ImageDimensions.mime("svg")).isNull();
        assertThat(ImageDimensions.mime(null)).isNull();
        assertThat(ImageDimensions.extension("jpeg")).isEqualTo("jpg");
        assertThat(ImageDimensions.extension("webp")).isEqualTo("webp");
        assertThat(ImageDimensions.extension("html")).isNull();
    }

    @Test
    @DisplayName("reddedilenler: SVG, HTML hata sayfası, ICO, TIFF, HEIC / MP4, yalnız \"BM\" ile başlayan metin, kısa ve boş gövde, null")
    void rejectedTypes() {
        assertThat(ImageDimensions.type("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(ImageDimensions.type("<?xml version=\"1.0\"?><svg></svg>".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(ImageDimensions.type("<!doctype html><html><body>404</body></html>".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(ImageDimensions.type(new byte[]{0, 0, 1, 0, 1, 0, 16, 16, 0, 0, 1, 0, 32, 0, 0, 0})).as("ICO").isNull();
        assertThat(ImageDimensions.type(new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0})).as("TIFF").isNull();
        assertThat(ImageDimensions.type(isoBmff("heic", new String[]{"mif1", "heic"}, new int[0][]))).as("HEIC").isNull();
        assertThat(ImageDimensions.type(isoBmff("isom", new String[]{"mp41", "mp42"}, new int[0][]))).as("MP4").isNull();
        assertThat(ImageDimensions.type("BMW is a car brand, not a bitmap header at all".getBytes(StandardCharsets.UTF_8)))
                .as("BM + bilinmeyen DIB boyutu").isNull();
        assertThat(ImageDimensions.type("GIF8xa not really a gif".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(ImageDimensions.type(new byte[]{(byte) 0x89, 'P', 'N', 'G'})).as("kısa").isNull();
        assertThat(ImageDimensions.type(new byte[0])).isNull();
        assertThat(ImageDimensions.type(null)).isNull();
        // PNG imzasının yalnız ilk dört baytı yetmez (satır sonu dönüşümüyle bozulmuş dosya)
        byte[] broken = new byte[32];
        broken[0] = (byte) 0x89; broken[1] = 'P'; broken[2] = 'N'; broken[3] = 'G'; broken[4] = 0x0A;
        assertThat(ImageDimensions.type(broken)).isNull();
    }

    @Test
    @DisplayName("ftyp: 64 bitlik / çok küçük kutu boyutu reddedilir; marka taraması kutu boyutu ve 64 markayla sınırlı")
    void ftypBounds() {
        byte[] avif = isoBmff("mif1", new String[]{"avif"}, new int[0][]);
        byte[] largesize = avif.clone();
        be32(largesize, 0, 1);
        assertThat(ImageDimensions.type(largesize)).as("boyut = 1 (64 bit)").isNull();
        // avif markası kutunun DIŞINDA (kutu boyutu 16): sayılmaz
        byte[] outside = avif.clone();
        be32(outside, 0, 16);
        assertThat(ImageDimensions.type(outside)).isNull();
        // 64 markanın ötesindeki avif okunmaz (sınır)
        String[] many = new String[80];
        Arrays.fill(many, "xxxx");
        many[79] = "avif";
        assertThat(ImageDimensions.type(isoBmff("mif1", many, new int[0][]))).isNull();
        many[10] = "avif";
        assertThat(ImageDimensions.type(isoBmff("mif1", many, new int[0][]))).isEqualTo("avif");
    }

    @Test
    @DisplayName("readAny: bilinen türlerde read ile aynı; AVIF'te en büyük alanlı ispe; ispe yoksa null; büyük girdi kilitlemez")
    void readAnyAvif() throws Exception {
        assertThat(ImageDimensions.readAny(encode("png", 321, 123))).isEqualTo(new ImageDimensions.Size(321, 123));
        assertThat(ImageDimensions.readAny(webp(300, 200))).isEqualTo(new ImageDimensions.Size(300, 200));
        byte[] grid = isoBmff("avif", new String[]{"mif1"}, new int[][]{{512, 512}, {4000, 3000}, {160, 120}});
        assertThat(ImageDimensions.read(grid)).as("tarama ölçümü AVIF okumaz (değişmedi)").isNull();
        assertThat(ImageDimensions.readAny(grid)).isEqualTo(new ImageDimensions.Size(4000, 3000));
        assertThat(ImageDimensions.readAny(isoBmff("avif", new String[]{"mif1"}, new int[0][]))).isNull();
        assertThat(ImageDimensions.readAny(isoBmff("avif", new String[]{"mif1"}, new int[][]{{0, 10}, {200_000, 5}})))
                .as("geçersiz boyutlar").isNull();
        assertThat(ImageDimensions.readAny(null)).isNull();
        byte[] big = new byte[26 * 1024 * 1024];
        byte[] head = isoBmff("avif", new String[]{"mif1"}, new int[][]{{640, 480}});
        System.arraycopy(head, 0, big, 0, head.length);
        assertThat(assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> ImageDimensions.readAny(big)))
                .isEqualTo(new ImageDimensions.Size(640, 480));
    }
}
