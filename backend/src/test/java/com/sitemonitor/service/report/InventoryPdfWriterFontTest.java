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
