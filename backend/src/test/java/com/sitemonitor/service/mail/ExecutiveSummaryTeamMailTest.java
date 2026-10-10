package com.sitemonitor.service.mail;

import com.sitemonitor.service.report.ExecutiveSummaryPdfWriter;
import com.sitemonitor.service.report.executive.ExecutiveSummary;
import com.sitemonitor.service.report.executive.ExecutiveSummarySamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Takım özeti postası ve PDF'i (2026-10-10): konu / rozet / bağlantılar / dosya adı takımı söyler; kurum özeti birebir
 * eskisi gibi kalır.
 */
class ExecutiveSummaryTeamMailTest {

    private static ExecutiveSummary team(ExecutiveSummary base, Long id, String name) {
        return new ExecutiveSummary(base.month(), base.monthLabel(), base.from(), base.to(), base.complete(), base.generatedAt(),
                base.source(), base.status(), base.headline(), base.headlineKpis(), base.sections(), base.settings(),
                ExecutiveSummary.Scope.team(id, name));
    }

    @Test
    @DisplayName("kurum özeti değişmedi: konu, 'Kurum geneli' rozeti, ayar bağlantısı ex_cfg=1, dosya adı ay")
    void orgUnchanged() {
        ExecutiveSummary org = ExecutiveSummarySamples.full();
        assertThat(ExecutiveSummaryMail.subject(org)).startsWith("[Site Monitor] Aylık Yönetici Özeti · " + org.monthLabel() + " · ");
        MailDoc.Mail m = ExecutiveSummaryMail.build(org, "https://sm.example.com", "10.10.2026 12:00", "x.pdf");
        assertThat(m.html()).contains("Kurum geneli").contains("/?tab=executive&amp;ex_cfg=1").doesNotContain("ex_team");
        assertThat(ExecutiveSummaryPdfWriter.fileName(org)).isEqualTo("site-monitor-yonetici-ozeti-" + org.month() + ".pdf");
    }

    @Test
    @DisplayName("takım özeti: konu ve rozet takım adlı; 'uygulamada aç' ve ayar bağlantısı ex_team taşır; metin takım alıcılarını anlatır")
    void teamMail() {
        ExecutiveSummary t = team(ExecutiveSummarySamples.full(), 7L, "Ödeme Ağ Geçidi");
        assertThat(ExecutiveSummaryMail.subject(t))
                .startsWith("[Site Monitor] Aylık Yönetici Özeti · Ödeme Ağ Geçidi · " + t.monthLabel() + " · ");
        MailDoc.Mail m = ExecutiveSummaryMail.build(t, "https://sm.example.com", "10.10.2026 12:00", "x.pdf");
        assertThat(m.html()).contains("Takım: Ödeme Ağ Geçidi").doesNotContain("Kurum geneli")
                .contains("ex_team=7").contains("takımı yöneten müdürler");
        assertThat(m.text()).contains("ex_team=7&ex_cfg=1");
    }

    @Test
    @DisplayName("takım PDF'i: dosya adı ASCII'ye katlanmış takım adı + ay; belge üretilir; ad boşsa takım kimliği")
    void teamPdf() {
        ExecutiveSummary t = team(ExecutiveSummarySamples.full(), 7L, "Ödeme Ağ Geçidi (İç Ğ/Ş)");
        assertThat(ExecutiveSummaryPdfWriter.fileName(t))
                .isEqualTo("site-monitor-yonetici-ozeti-odeme-ag-gecidi-ic-g-s-" + t.month() + ".pdf");
        byte[] pdf = ExecutiveSummaryPdfWriter.render(t);
        assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        ExecutiveSummary noName = team(ExecutiveSummarySamples.quiet(), 9L, "  ");
        assertThat(ExecutiveSummaryPdfWriter.fileName(noName)).isEqualTo("site-monitor-yonetici-ozeti-takim-9-" + noName.month() + ".pdf");
    }
}
