package com.sitemonitor.service.report;

import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.mock;

/**
 * Görsel doğrulama yardımcısı (varsayılan KAPALI): aylık envanter raporunun PDF ekini örnek veriyle
 * ({@link CertInventorySamples}) üretir, ilk sayfaları PNG'ye çevirir — {@code -Dcertinv.preview.dir=<klasör>} verilince
 * koşar. Üretim kodunu test etmez; göz kontrolü içindir (yer tutucu veri: example.com / Takım A).
 */
@EnabledIfSystemProperty(named = "certinv.preview.dir", matches = ".+")
class InventoryPdfPreviewDumpTest {

    @Test
    void dumpPdf() throws Exception {
        Path dir = Path.of(System.getProperty("certinv.preview.dir"));
        Files.createDirectories(dir);
        InventoryExportService svc = new InventoryExportService(mock(CertificateInventoryRepository.class), mock(TeamRepository.class));
        CertInventorySamples.Large l = CertInventorySamples.largeData();
        byte[] pdf = svc.pdf(l.rows, l.teams, CertInventorySamples.large());
        Files.write(dir.resolve("sertifika-envanteri-large.pdf"), pdf);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFRenderer r = new PDFRenderer(doc);
            int n = Math.min(4, doc.getNumberOfPages());
            for (int i = 0; i < n; i++) {
                BufferedImage img = r.renderImageWithDPI(i, 110);
                ImageIO.write(img, "png", dir.resolve("pdf-page-" + (i + 1) + ".png").toFile());
            }
            Files.writeString(dir.resolve("pdf-pages.txt"), "pages=" + doc.getNumberOfPages() + System.lineSeparator());
        }
    }
}
