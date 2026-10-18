package com.sitemonitor.service.techerror;

import com.sitemonitor.service.report.TechErrorReportPdfWriter;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Görseller tam çözünürlükte saklandığından (2026-10-16) PDF e-posta sınırını aşabilir: servis aynı veriden daha düşük
 * gömme katıyla yeniden üretir (inceleme 2026-10-16) — indirilen PDF ile e-posta eki aynıdır; sınır içindeyse ilk kat.
 */
class TechErrorPdfSizeLadderTest {

    static byte[] noisePng(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(7);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, r.nextInt(0x1000000));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static int widestImage(byte[] pdf) throws IOException {
        int w = 0;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage p : doc.getPages()) {
                for (var name : p.getResources().getXObjectNames()) {
                    if (p.getResources().getXObject(name) instanceof PDImageXObject img) w = Math.max(w, img.getWidth());
                }
            }
        }
        return w;
    }

    @Test
    @DisplayName("PDF sınırı aşarsa görseller sırayla daha düşük katla gömülür ve sınıra sığar; sınır içindeyse ≈ 432 dpi")
    void ladderFitsMailLimit() throws IOException {
        TechErrorReportService svc = new TechErrorReportService(null, null, null, null, null);
        Template t = TechErrorTemplate.defaults("tr");
        TechErrorPdfInput in = new TechErrorPdfInput(t, TechErrorValues.normalize(Map.of(), t), "Mobil", "Takım", 2026, 9,
                null, null, Map.of("annual-summary", new TechErrorPdfInput.Image(noisePng(1600, 800), 1600, 800)));
        int mb = 1024 * 1024;
        byte[] first = TechErrorReportPdfWriter.render(in, TechErrorReportPdfWriter.EMBED_SCALES[0]);
        assertThat(first.length).as("6 kat gömme 1 MB'ı aşar").isGreaterThan(mb);

        svc.maxMailMb = 15;
        byte[] roomy = svc.renderPdf(() -> in).pdf();
        assertThat(widestImage(roomy)).as("sınır içinde ilk kat (yarım yuva ≈ 1522 px)").isGreaterThan(1500);

        // Gönderen sınırı BİR kez okuyup verir (inceleme 2026-10-18): merdiven alanı değil verilen değeri uygular
        svc.maxMailMb = 15;
        byte[] fitted = svc.renderPdf(() -> in, mb).pdf();
        assertThat(fitted.length).isLessThanOrEqualTo(mb);
        assertThat(widestImage(fitted)).as("daha düşük katla gömüldü").isLessThan(1100);
        // Aralık dışı önyükleme değeri (1 MB < en az 5) ekranın gösterdiği varsayılana (15) düşer — PDF ilk katta kalır
        svc.maxMailMb = 1;
        assertThat(svc.mailLimitBytes()).isEqualTo(15L * mb);
        assertThat(widestImage(svc.renderPdf(() -> in).pdf())).isGreaterThan(1500);
    }
}
