package com.sitemonitor.service.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aylık envanter raporunun TÜRKÇE taşıma kapısı.
 *
 * <p>Rapor PDF'inde Türkçe karakterlerin bozulmasının TEK sebebi gömülü fontun
 * yüklenememesidir: {@code InventoryPdfWriter} o durumda Standard-14 Helvetica'ya düşer ve
 * her metni {@code sanitize()} ile ASCII'ye indirger — belge üretilir ama ş/ğ/İ/ç/ö/ü erir.
 *
 * <p>Bu düşüş SESSİZDİ (yükleme hatası yutuluyordu), dolayısıyla prod'daki "PDF'te Türkçe
 * bozuk" şikâyeti teşhis edilemiyordu. Artık hem günlüğe yazılıyor hem burada pinleniyor:
 * font kaynakları paketten düşerse (kaynak dizini taşınır, jar'a girmez, filtreleme bozar)
 * bu test kırmızı olur — kullanıcı bozuk PDF almadan önce.
 */
class InventoryPdfWriterFontTest {

    @Test
    @DisplayName("Roboto fontları SINIF YOLUNDA ve yüklenebilir — yoksa PDF ASCII'ye düşer")
    void fonts_areEmbeddable() throws Exception {
        try (InventoryPdfWriter w = new InventoryPdfWriter()) {
            assertThat(w.fontsEmbedded())
                    .as("gömülü font yüklenemedi → rapordaki Türkçe karakterler kaybolur")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Font kaynakları paketin İÇİNDE (jar'a giren yol ile aynı)")
    void fontResources_areOnClasspath() {
        for (String name : new String[]{"Roboto-Regular.ttf", "Roboto-Bold.ttf"}) {
            assertThat(InventoryPdfWriter.class.getResourceAsStream("/report-fonts/" + name))
                    .as("/report-fonts/%s sınıf yolunda yok", name)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("sabit genişlikli font GÖMÜLÜ (2026-10-30): DejaVu Sans Mono sınıf yolunda ve yüklenir — standart 14 Courier "
            + "kullanılmaz (sistemde yoksa PDFBox her raporda 'fallback font' WARN yazıyordu)")
    void monoFont_isEmbedded() throws Exception {
        assertThat(InventoryPdfWriter.class.getResourceAsStream("/report-fonts/DejaVuSansMono.ttf")).isNotNull();
        try (InventoryPdfWriter w = new InventoryPdfWriter()) {
            assertThat(w.monoEmbedded()).isTrue();
            Object mono = org.springframework.test.util.ReflectionTestUtils.getField(w, "mono");
            assertThat(mono).isInstanceOf(org.apache.pdfbox.pdmodel.font.PDType0Font.class);
            String tr = "CN=Şube Doğrulama İç CA, O=Örnek Çağrı";
            String out = (String) org.springframework.test.util.ReflectionTestUtils.invokeMethod(w, "encodable", mono, tr);
            assertThat(out).as("gömülü mono Türkçeyi ASCII'ye indirgemez").isEqualTo(tr);
        }
    }

    @Test
    @DisplayName("üretilen PDF'te Courier YOK; beklenen parmak izi + Türkçe subject gömülü mono ile basılır")
    void pdfUsesEmbeddedMonoNotCourier() throws Exception {
        com.sitemonitor.model.CertificateInventory row = CertInventorySamples.inv("ornek.akbank.com", 1L, 1);
        row.setExpectedFingerprint("AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD");
        row.setExpectedSubject("CN=Şube Doğrulama İç CA, O=Örnek Çağrı");
        byte[] pdf;
        try (InventoryPdfWriter w = new InventoryPdfWriter()) {
            pdf = w.write(java.util.List.of(row), java.util.Map.of(1L, "Takım"));
        }
        java.util.Set<String> fonts = new java.util.TreeSet<>();
        String text;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            for (org.apache.pdfbox.pdmodel.PDPage page : doc.getPages()) {
                org.apache.pdfbox.pdmodel.PDResources res = page.getResources();
                for (org.apache.pdfbox.cos.COSName n : res.getFontNames()) fonts.add(res.getFont(n).getName());
            }
            text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        }
        assertThat(fonts).noneMatch(f -> f.contains("Courier"));
        assertThat(fonts).anyMatch(f -> f.contains("DejaVuSansMono"));
        assertThat(text).contains("CN=Şube Doğrulama İç CA", "AA:BB:CC:DD");
    }

    @Test
    @DisplayName("TURKCE karakterler kodlayicidan SAG CIKAR (asil sozlesme)")
    void turkishSurvivesEncoder() throws Exception {
        // Gomulu font yuklenmis olsa bile encodable() her karakteri glyph kontrolunden geciriyor;
        // eleyen bir kontrol Turkce'yi sessizce '?' yapardi. Asil kullanici sozlesmesi budur.
        String tr = "şğıİÖÜÇçöüĞŞ Akıllı Şifre Doğrulama";
        try (InventoryPdfWriter w = new InventoryPdfWriter()) {
            Object regular = org.springframework.test.util.ReflectionTestUtils.getField(w, "regular");
            String out = (String) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    w, "encodable", regular, tr);
            assertThat(out).isEqualTo(tr);
        }
    }
}
