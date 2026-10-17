package com.sitemonitor.service.mail;

import com.sitemonitor.service.techerror.TechErrorPdfInput.Image;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** E-postaya gömülen görsel kopyaları (2026-10-17, S5): ≤ 1000 px, tür korunur, bütçe sırayla, okunamayan atlanır. */
class TechErrorMailImagesTest {

    static byte[] png(int w, int h, Color c) throws IOException {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageIO.write(bi, "png", bo);
        return bo.toByteArray();
    }

    static byte[] jpeg(int w, int h) throws IOException {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageIO.write(bi, "jpeg", bo);
        return bo.toByteArray();
    }

    static byte[] grayPng(int w, int h, int level) throws IOException {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) bi.getRaster().setSample(x, y, 0, level);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageIO.write(bi, "png", bo);
        return bo.toByteArray();
    }

    static BufferedImage decode(byte[] b) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(b));
    }

    @Test
    @DisplayName("geniş PNG 1000 px'e alan ortalamasıyla iner ve PNG kalır; JPEG kaynağı JPEG; sığan görsel OLDUĞU GİBİ (aynı baytlar)")
    void resizeKeepsType() throws IOException {
        var wide = TechErrorMailImages.one("a", new Image(png(3000, 1500, Color.BLUE), 3000, 1500), 1000, 1400);
        assertThat(wide.width()).isEqualTo(1000);
        assertThat(wide.height()).isEqualTo(500);
        assertThat(wide.contentType()).isEqualTo("image/png");
        assertThat(wide.cid()).isEqualTo("te-img-a");
        BufferedImage d = decode(wide.data());
        assertThat(d.getWidth()).isEqualTo(1000);
        assertThat(d.getHeight()).isEqualTo(500);
        assertThat(new Color(d.getRGB(500, 250)).getBlue()).isEqualTo(255);
        var jp = TechErrorMailImages.one("j", new Image(jpeg(2400, 600), 2400, 600), 1000, 1400);
        assertThat(jp.contentType()).isEqualTo("image/jpeg");
        assertThat((jp.data()[0] & 0xff)).isEqualTo(0xff);
        assertThat(decode(jp.data()).getWidth()).isEqualTo(1000);
        byte[] small = png(800, 400, Color.RED);
        var same = TechErrorMailImages.one("s", new Image(small, 800, 400), 1000, 1400);
        assertThat(same.data()).isSameAs(small);
        assertThat(same.width()).isEqualTo(800);
        // Çok uzun görsel yüksekliğe göre iner
        var tall = TechErrorMailImages.one("t", new Image(png(600, 3000, Color.GREEN), 600, 3000), 1000, 1400);
        assertThat(tall.width()).isEqualTo(280);
        assertThat(tall.height()).isEqualTo(1400);
    }

    @Test
    @DisplayName("gri PNG sığsa da sRGB'ye çevrilir (istemciler griyi soluk çiziyordu) ve gri tonu korunur")
    void grayPngConverted() throws IOException {
        byte[] gray = grayPng(300, 100, 128);
        assertThat(TechErrorMailImages.isGrayPng(gray)).isTrue();
        var p = TechErrorMailImages.one("g", new Image(gray, 300, 100), 1000, 1400);
        assertThat(p.data()).isNotSameAs(gray);
        BufferedImage d = decode(p.data());
        assertThat(TechErrorMailImages.isGrayPng(p.data())).isFalse();
        assertThat(new Color(d.getRGB(10, 10)).getRed()).isBetween(126, 130);
    }

    @Test
    @DisplayName("bütçe yuva sırasıyla dolar: sığmayan ve okunamayan yuvalar atlanır, sıradaki olmayan yuva yok sayılır, boş girdi boş sonuç")
    void budgetAndOrder() throws IOException {
        Map<String, Image> images = new LinkedHashMap<>();
        byte[] a = png(900, 300, Color.BLUE);
        byte[] b = png(900, 300, Color.RED);
        images.put("a", new Image(a, 900, 300));
        images.put("b", new Image(b, 900, 300));
        images.put("broken", new Image(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, 10, 10));
        images.put("not-in-template", new Image(a, 900, 300));
        long budget = a.length + 10;         // yalnız biri sığar
        var r = TechErrorMailImages.prepare(images, List.of("b", "a", "broken"), 1000, 1400, budget);
        assertThat(r.embedded().keySet()).containsExactly("b");         // sıra: b önce geldi ve sığdı
        assertThat(r.skipped()).containsExactly("a");                   // bütçeye sığmadı → PDF ekinde
        assertThat(r.unreadable()).containsExactly("broken");           // okunamadı → PDF'te de çizilemedi
        assertThat(r.present("a")).isTrue();
        assertThat(r.present("broken")).isTrue();
        assertThat(r.present("not-in-template")).isFalse();
        assertThat(r.bytes()).isEqualTo(b.length);
        assertThat(TechErrorMailImages.prepare(Map.of(), List.of("a"), 1000, 1400, budget).embedded()).isEmpty();
        assertThat(TechErrorMailImages.prepare(null, List.of("a"), 1000, 1400, budget).embedded()).isEmpty();
        // Bütçe 0: hiçbir şey gömülmez, hepsi atlanır
        assertThat(TechErrorMailImages.prepare(images, List.of("a", "b"), 1000, 1400, 0).skipped()).containsExactly("a", "b");
        assertThat(TechErrorMailImages.one("x", new Image(new byte[0], 0, 0), 1000, 1400)).isNull();
        assertThat(TechErrorMailImages.one("x", null, 1000, 1400)).isNull();
    }

    @Test
    @DisplayName("rapor girdisinden: yuva sırası şablonun görsel bölümlerinden (şablonda olmayan yuva yok sayılır); allSkipped görseli olan her yuvayı atlanmış sayar")
    void fromInput() throws IOException {
        var t = com.sitemonitor.service.techerror.TechErrorTemplate.defaults("tr");
        Map<String, Image> images = new LinkedHashMap<>();
        images.put("zzz-not-a-slot", new Image(png(100, 100, Color.BLUE), 100, 100));
        images.put("monthly-tx", new Image(png(100, 100, Color.BLUE), 100, 100));
        images.put("annual-summary", new Image(png(100, 100, Color.RED), 100, 100));
        var in = new com.sitemonitor.service.techerror.TechErrorPdfInput(t, com.sitemonitor.service.techerror.TechErrorValues.Values.empty(),
                "Mobil", "T", 2026, 9, null, null, images);
        assertThat(TechErrorMailImages.slotOrder(t)).startsWith("sla-shot-1", "sla-shot-2", "annual-summary", "monthly-tx");
        var r = TechErrorMailImages.prepare(in, TechErrorMailImages.DEFAULT_BUDGET);
        assertThat(r.embedded().keySet()).containsExactly("annual-summary", "monthly-tx");
        assertThat(r.skipped()).isEmpty();
        assertThat(TechErrorMailImages.allSkipped(in).skipped()).containsExactly("annual-summary", "monthly-tx");
        assertThat(TechErrorMailImages.allSkipped(in).embedded()).isEmpty();
        assertThat(TechErrorMailImages.allSkipped(in).unreadable()).isEmpty();
        assertThat(TechErrorMailImages.prepare((com.sitemonitor.service.techerror.TechErrorPdfInput) null, 10).embedded()).isEmpty();
        assertThat(TechErrorMailImages.allSkipped(in.withoutImages()).skipped()).isEmpty();
    }
}
