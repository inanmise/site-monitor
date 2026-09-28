package com.sitemonitor.service.mail;

import com.sitemonitor.service.mail.MailKit.Cell;
import com.sitemonitor.service.mail.MailKit.Col;
import com.sitemonitor.service.mail.MailKit.Item;
import com.sitemonitor.service.mail.MailKit.ListItem;
import com.sitemonitor.service.mail.MailKit.Segment;
import com.sitemonitor.service.mail.MailTokens.Tone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MailKit'in AYLIK ENVANTER bölümündeki yapı taşları (2026-09-28): parçalı durum çubuğu, tonlu hap, 4→2 sütunlu öğe
 * tablosu, liste kartı, sıkı sayısal tablo. Outlook-güvenli dil: td bgcolor + düz hex, yüzde genişlik, sol şerit YOK.
 */
class MailKitInventoryBlocksTest {

    @Test
    @DisplayName("çubuk genişlikleri: toplam TAM 100, dolu dilim en az %2 (1/1000'lik dilim de görünür)")
    void segmentWidths() {
        List<Segment> segs = List.of(new Segment("a", 1, "#dc2626"), new Segment("b", 997, "#16a34a"), new Segment("c", 2, "#d97706"));
        int[] w = MailKit.segmentWidths(segs, 1000);
        assertThat(w[0] + w[1] + w[2]).isEqualTo(100);
        assertThat(w[0]).isGreaterThanOrEqualTo(MailKit.SEGMENT_MIN_PCT);
        assertThat(w[2]).isGreaterThanOrEqualTo(MailKit.SEGMENT_MIN_PCT);
        int[] thirds = MailKit.segmentWidths(List.of(new Segment("a", 1, "#000000"), new Segment("b", 1, "#000000"), new Segment("c", 1, "#000000")), 3);
        assertThat(thirds[0] + thirds[1] + thirds[2]).isEqualTo(100);
        assertThat(MailKit.segmentPct(2, 412)).isEqualTo("<%1");
        assertThat(MailKit.segmentPct(306, 412)).isEqualTo("%74");
        assertThat(MailKit.segmentPct(0, 412)).isEqualTo("%0");
    }

    @Test
    @DisplayName("parçalı çubuk: yalnız dolu dilimler, yüzde genişlikli td bgcolor, lejantta adet + yüzde; boşsa hiç çizilmez; ŞERİT YOK")
    void segmentBar() {
        String h = MailKit.segmentBar(List.of(new Segment("Süresi dolmuş", 2, Tone.DESTRUCTIVE.text),
                new Segment("Boş", 0, Tone.INFO.strong), new Segment("90 gün üstü", 98, Tone.SUCCESS.strong)));
        assertThat(h).contains("bgcolor=\"" + Tone.DESTRUCTIVE.text + "\"").contains("bgcolor=\"" + Tone.SUCCESS.strong + "\"")
                .doesNotContain("bgcolor=\"" + Tone.INFO.strong + "\"").doesNotContain(">Boş")
                .contains("<td width=\"2%\"").contains("<td width=\"98%\"")
                .contains("Süresi dolmuş <strong").contains(">2</strong> · %2").contains(">98</strong> · %98")
                .doesNotContain("border-left").doesNotContain("rgba(").doesNotContain("gradient").doesNotContain("<img");
        assertThat(MailKit.segmentBar(List.of(new Segment("x", 0, "#000000")))).isEmpty();
    }

    @Test
    @DisplayName("tonlu hap: dolmuş = dolu kırmızı + beyaz yazı; ton yoksa nötr hap; etiket kaçırılır")
    void pill() {
        assertThat(MailKit.pill("2 gün önce doldu", Tone.DESTRUCTIVE, true))
                .contains("background-color:" + Tone.DESTRUCTIVE.strong).contains("color:#ffffff");
        assertThat(MailKit.pill("9 gün", Tone.WARNING, false)).contains("background-color:" + Tone.WARNING.bg)
                .contains("border:1px solid " + Tone.WARNING.border).contains("color:" + Tone.WARNING.text);
        assertThat(MailKit.pill("<x>", Tone.SUCCESS, false)).contains("&lt;x&gt;").doesNotContain("<x>");
        assertThat(MailKit.pill("n", null, false)).isEqualTo(MailKit.pill("n"));
    }

    @Test
    @DisplayName("öğe tablosu: masaüstünde 4 sütun; ikincil sütunlar telefonda gizli (col-opt) ve m-only satırına iner; satır tek kez basılır")
    void itemTable() {
        String h = MailKit.itemTable(List.of("Sertifika", "Takım", "Bitiş", "Kalan"),
                List.of(new Item("a.example.com", "DigiCert Inc · T1", "Takım A", "01.10.2026", MailKit.pill("5 gün", Tone.DESTRUCTIVE, false)),
                        new Item("b.example.com", null, null, null, null)));
        assertThat(h).contains(">Sertifika</td>").contains("<td class=\"col-opt\" bgcolor").contains(">Kalan</td>")
                .contains("<td class=\"col-opt nw\" valign=\"top\"")
                .contains("<!--[if !mso]><!--><div class=\"m-only\" style=\"display:none;max-height:0;overflow:hidden;mso-hide:all;margin:2px 0 0;")
                .contains(">Takım A · 01.10.2026</div><!--<![endif]-->");
        // satır başına TEK a.example.com (dataTable gibi kart kopyası yok)
        assertThat(h.split("a\\.example\\.com", -1).length - 1).isEqualTo(1);
        // boş alanlar "—"
        assertThat(h).contains(">—</td>");
        assertThat(MailKit.itemTable(List.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("sade bağlantı: kaçışlı, şema denetimli (javascript: → düz metin), altı çizgisiz")
    void quietLink() {
        assertThat(MailKit.quietLink("https://example.com/?a=1&b=2", "a<b>"))
                .isEqualTo("<a href=\"https://example.com/?a=1&amp;b=2\" target=\"_blank\" style=\"color:" + MailTokens.PRIMARY
                        + ";text-decoration:none\">a&lt;b&gt;</a>");
        assertThat(MailKit.quietLink("javascript:alert(1)", "x")).isEqualTo("x");
    }

    @Test
    @DisplayName("liste kartı: başlık + sağda rozet, açıklama, aksiyon kutusu, öğeler, dip notu; sol şerit YOK")
    void listCard() {
        String h = MailKit.listCard("Başlık", MailKit.Badge.tint("3 kayıt", Tone.WARNING), "açıklama", "<strong>Önerilen:</strong> yap",
                List.of(new ListItem("x.example.com", "detay")), "+2 kayıt daha");
        assertThat(h).contains(">Başlık</td>").contains("align=\"right\"").contains(">3 kayıt</td>").contains("açıklama")
                .contains("<strong>Önerilen:</strong> yap").contains("x.example.com").contains(">detay</p>").contains("+2 kayıt daha")
                .contains("bgcolor=\"" + MailTokens.SUBTLE + "\"").doesNotContain("border-left");
        String bare = MailKit.listCard("T", null, null, null, List.of(), null);
        assertThat(bare).contains(">T</td>").doesNotContain("Önerilen").doesNotContain("align=\"right\"");
    }

    @Test
    @DisplayName("sıkı sayısal tablo: başlık satırı, sayısal sütun sağa hizalı ve tek satır, hücre rengi/kalınlık, telefon kopyası YOK")
    void compactTable() {
        String h = MailKit.compactTable(List.of(Col.of("Takım"), Col.num("Acil")),
                List.of(List.of(Cell.of("Takım <A>"), Cell.of("5", Tone.WARNING.strong, true))));
        assertThat(h).contains(">Takım</td>").contains(">Acil</td>").contains("Takım &lt;A&gt;")
                .contains("color:" + Tone.WARNING.strong + ";font-weight:600;text-align:right;white-space:nowrap\">5</td>")
                .doesNotContain("m-only").doesNotContain("d-only");
        assertThat(MailKit.compactTable(List.of(Col.of("a")), List.of())).isEmpty();
    }
}
