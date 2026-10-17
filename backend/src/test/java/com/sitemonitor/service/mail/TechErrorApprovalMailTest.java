package com.sitemonitor.service.mail;

import com.sitemonitor.service.techerror.TechErrorPdfInput;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.techerror.TechErrorValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PO onayı postaları (2026-10-17, S6): istek = onay kartı + tam gövde; karar = kısa, notlu, gönderim sonucu. */
class TechErrorApprovalMailTest {

    private static TechErrorPdfInput input(String lang) {
        TechErrorTemplate.Template t = TechErrorTemplate.defaults(lang);
        TechErrorValues.Values v = TechErrorValues.normalize(Map.of("fields", Map.of("total-tx", 1000, "error-count", 5)), t);
        return new TechErrorPdfInput(t, v, "Mobil", "Dijital", 2026, 9, "Ayşe", Instant.parse("2026-10-02T06:30:00Z"), Map.of());
    }

    @Test
    @DisplayName("istek: '[Onay bekliyor]' konu; banddan sonra onay kartı (kim istedi, kaç yöneticiye gider, kişiye özel bağlantı), 'Onayla ya da reddet' ve 'Site Monitor'de aç' düğmeleri, ardından raporun gövdesi; bağlantı yoksa uygulama içi not")
    void request() {
        TechErrorPdfInput in = input("tr");
        TechErrorReportMail.Built b = TechErrorApprovalMail.request(in, TechErrorMailImages.Result.empty(), "https://sm.example", "m.pdf",
                100L, 10L, "Ayşe Yılmaz", "PO Bir", 3, "https://sm.example/api/tech-error-reports/approve-link?token=abc", 14);
        String html = b.mail().html();
        assertThat(TechErrorApprovalMail.requestSubject(in)).isEqualTo("[Onay bekliyor] Mobil Kanalı Teknik Hata Mesajları - Eylül 2026");
        int band = html.indexOf("data-slot=\"te-mail-band\"");
        int card = html.indexOf("Onayınız bekleniyor, PO Bir");
        int body = html.indexOf("Yönetici Özeti");
        assertThat(band).isPositive();
        assertThat(card).isGreaterThan(band);
        assertThat(body).isGreaterThan(card);
        assertThat(html).contains("Ayşe Yılmaz bu raporu onayınıza sundu").contains("3 yönetici alıcısına").contains("14 gün")
                .contains("approve-link?token=abc").contains("Onayla ya da reddet")
                .contains("te_view=report&amp;te_ch=10&amp;te_rep=100").contains("Site Monitor&#39;de aç");
        assertThat(b.mail().text()).contains("Onayınız bekleniyor").contains("approve-link?token=abc");
        // Bağlantısız (base-url yok): düğme yok, uygulama içi not
        String noLink = TechErrorApprovalMail.request(in, TechErrorMailImages.Result.empty(), null, "m.pdf", 100L, 10L, null, "PO Bir", 1, null, 14)
                .mail().html();
        assertThat(noLink).contains("Bir takım üyesi bu raporu").contains("raporu Site Monitor&#39;de açın").doesNotContain("approve-link");
        // PDF sığmadı (fileName null): ek olmadığı söylenir, "ekteki PDF" cümlesi yok
        String noPdf = TechErrorApprovalMail.request(in, TechErrorMailImages.Result.empty(), "https://sm.example", null, 100L, 10L, "Ayşe", "PO Bir", 1,
                "https://sm.example/x?token=t", 14).mail().html();
        assertThat(noPdf).contains("PDF bu postanın boyut sınırına sığmadığından eklenmedi").doesNotContain("ekteki PDF&#39;tedir");
        assertThat(noLink).contains("ekteki PDF&#39;tedir (m.pdf)");
        // EN
        String en = TechErrorApprovalMail.request(input("en"), TechErrorMailImages.Result.empty(), "https://sm.example", "m.pdf", 100L, 10L,
                "Ayşe", "PO One", 2, "https://sm.example/x?token=t", 14).mail().html();
        assertThat(en).contains("Your approval is needed, PO One").contains("2 executive recipient(s)").contains("Approve or reject");
    }

    @Test
    @DisplayName("karar: onaylandı + gönderildi / kuyrukta / gönderilemedi (yeniden gönder notu); reddedildi + not; '[Onaylandı]' / '[Reddedildi]' konu; rapor bağlantısı; EN")
    void decision() {
        MailDoc.Mail sent = TechErrorApprovalMail.decision("Mobil Kanalı — Eylül 2026", false, true, "Uygun", "PO Bir", "SENT",
                List.of("ceo@example.com"), "https://sm.example", 100L, 10L);
        assertThat(sent.html()).contains("Onaylandı").contains("PO Bir onayladı").contains("yönetici alıcılara gönderildi (ceo@example.com)")
                .contains("Uygun").contains("te_rep=100").doesNotContain("Henüz gönderilmedi");
        assertThat(TechErrorApprovalMail.decisionSubject("X", false, true)).isEqualTo("[Onaylandı] X");
        assertThat(TechErrorApprovalMail.decisionSubject("X", true, false)).isEqualTo("[Rejected] X");
        MailDoc.Mail queued = TechErrorApprovalMail.decision("X", false, true, null, "PO", "QUEUED", List.of(), null, 1L, 2L);
        assertThat(queued.html()).contains("posta sunucusuna verildi");
        MailDoc.Mail failed = TechErrorApprovalMail.decision("X", false, true, null, "PO", "PDF_TOO_LARGE", List.of(), null, 1L, 2L);
        assertThat(failed.html()).contains("Henüz gönderilmedi").contains("PDF_TOO_LARGE").contains("Yöneticilere gönder");
        MailDoc.Mail rejected = TechErrorApprovalMail.decision("X", false, false, "Rakamlar eksik", "PO Bir", null, List.of(), "https://sm.example", 1L, 2L);
        assertThat(rejected.html()).contains("Reddedildi").contains("PO Bir reddetti").contains("Rakamlar eksik").contains("taslağa döndü");
        assertThat(rejected.text()).contains("Rakamlar eksik");
        MailDoc.Mail en = TechErrorApprovalMail.decision("X", true, false, "Missing", "PO", null, List.of(), null, 1L, 2L);
        assertThat(en.html()).contains("Rejected by PO").contains("back in draft");
    }
}
