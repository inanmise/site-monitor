package com.sitemonitor.service.report;

import com.sitemonitor.service.mail.ExecutiveSummaryMail;
import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.report.executive.ExecutiveSummary;
import com.sitemonitor.service.report.executive.ExecutiveSummarySamples;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yönetici özeti PDF'i ve e-postası — duman testi: geçerli PDF ({@code %PDF}), A4, Türkçe karakterler korunur (gömülü
 * Roboto), sayfa numarası, bölümler genel çizilir; e-posta konusu marka kuralına uyar, gövde Gmail sınırının altında.
 */
class ExecutiveSummaryPdfWriterTest {

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            assertThat(doc.getPage(0).getMediaBox().getWidth()).isEqualTo(PdfCanvas.PAGE.getWidth());
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    @DisplayName("dolu özet: %PDF, Türkçe karakterler (ğüşıöç İĞÜŞÖÇ), dört bölüm başlığı, sayfa numarası")
    void fullPdf() throws Exception {
        ExecutiveSummary s = ExecutiveSummarySamples.full();
        byte[] pdf = ExecutiveSummaryPdfWriter.render(s);
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        String t = text(pdf);
        assertThat(t).contains("Aylık Yönetici Özeti", "Eylül 2026",
                "Erişilebilirlik hedefi uyumu", "En gürültülü alarmlar", "Yaklaşan sertifika bitişleri", "Yenileme süresine uyum");
        assertThat(t).contains("ğüşıöç İĞÜŞÖÇ");
        assertThat(t).contains("Sayfa 1 /");
        assertThat(t).contains("Süresi dolduktan sonra");
        // Yeni bölümler (2026-10-10): TLS notu, kripto hazırlığı, veri kalitesi — kod sütunları Türkçe yazılır
        assertThat(t).contains("TLS yapılandırma notu", "Kripto envanteri ve PQC hazırlığı", "Takım veri kalitesi puanı");
        assertThat(t).contains("TLS 1.0 açık", "Bugün zayıf", "P1 · şimdi", "Sahipsiz envanter kaydı", "İyileştirilmeli");
        assertThat(t).doesNotContain("TLS10_ENABLED", "INV_NO_TEAM", "NEEDS_ATTENTION");
    }

    @Test
    @DisplayName("her bölüm tablosu posta / PDF için ≤ 15 satır (yeni bölümler ≤ 10)")
    void tableRowLimits() {
        for (com.sitemonitor.service.report.executive.SectionResult sec : ExecutiveSummarySamples.full().sections()) {
            for (com.sitemonitor.service.report.executive.SectionResult.Table tb : sec.tables()) {
                int max = java.util.Set.of("tls-grade", "crypto-readiness", "data-quality").contains(sec.key()) ? 10 : 15;
                assertThat(tb.rows()).as(sec.key() + "/" + tb.code()).hasSizeLessThanOrEqualTo(max);
            }
        }
    }

    @Test
    @DisplayName("sakin ay da üretilir; dosya adı ayı taşır")
    void quietPdfAndFileName() throws Exception {
        byte[] pdf = ExecutiveSummaryPdfWriter.render(ExecutiveSummarySamples.quiet());
        assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        assertThat(text(pdf)).contains("SORUNSUZ");
        assertThat(ExecutiveSummaryPdfWriter.fileName("2026-09")).isEqualTo("site-monitor-yonetici-ozeti-2026-09.pdf");
        assertThat(ExecutiveSummaryPdfWriter.fileName("../../x")).isEqualTo("site-monitor-yonetici-ozeti-.pdf");
    }

    @Test
    @DisplayName("e-posta: konu [Site Monitor] ile başlar, 'SiteMonitor' bitişik yok, düz metin dolu, gövde < 95 KB, bölüm kartları")
    void mail() {
        ExecutiveSummary s = ExecutiveSummarySamples.full();
        MailDoc.Mail m = ExecutiveSummaryMail.build(s, "https://sitemonitor.example.com", "10.10.2026 12:00",
                ExecutiveSummaryPdfWriter.fileName(s.month()));
        assertThat(ExecutiveSummaryMail.subject(s)).startsWith("[Site Monitor] Aylık Yönetici Özeti · Eylül 2026 · ");
        assertThat(m.html()).doesNotContain("SiteMonitor").contains("?tab=executive&amp;ex_m=2026-09")
                .contains("Yenileme süresine uyum").contains("site-monitor-yonetici-ozeti-2026-09.pdf")
                .contains("TLS yapılandırma notu").contains("Kripto envanteri ve PQC hazırlığı")
                .contains("Takım veri kalitesi puanı");
        assertThat(m.html().getBytes(StandardCharsets.UTF_8).length).isLessThan(95_000);
        assertThat(m.text()).contains("Erişilebilirlik hedefi uyumu").doesNotContain("<table");
    }
}
