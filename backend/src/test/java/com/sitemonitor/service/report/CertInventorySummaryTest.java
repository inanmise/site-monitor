package com.sitemonitor.service.report;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.service.mail.CertInventoryMail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aylık envanter ÖZETİ — e-posta gövdesi ve PDF eki aynı sayıları buradan alır. Kovalar ayrık ve toplamları aktif kayıt
 * sayısıdır; listeler TAM (kırpma sunum katmanında); tarih kurum gününe çevrilir.
 */
class CertInventorySummaryTest {

    @Test
    @DisplayName("sayaçlar: aktif/pasif/toplam, sahip takım (SY + UG), ayrık kalan-süre kovaları, hata sayısı")
    void counters() {
        CertInventoryMail.Report r = CertInventorySamples.large();
        assertThat(r.total()).isEqualTo(426);
        assertThat(r.active()).isEqualTo(412);
        assertThat(r.passive()).isEqualTo(14);
        assertThat(r.ownerTeams()).isEqualTo(14);          // 12 SY + 2 UG takımı
        CertInventoryMail.Buckets b = r.buckets();
        assertThat(List.of(b.expired(), b.days0to7(), b.days8to14(), b.days15to30(), b.days31to90(), b.over90(), b.unknown()))
                .containsExactly(2, 3, 5, 28, 61, 306, 7);
        assertThat(b.total()).isEqualTo(r.active());
        assertThat(b.within30()).isEqualTo(36);
        assertThat(b.within14()).isEqualTo(8);
        assertThat(b.within7()).isEqualTo(3);
        assertThat(r.errors()).isEqualTo(4);
        assertThat(r.findingTotal()).isEqualTo(31);
    }

    @Test
    @DisplayName("listeler TAM ve artan kalan güne göre: 30 gün penceresi (süresi dolmuşlar dahil) + sonrası; tarih yok olanlar listelenmez")
    void listsAreCompleteAndSorted() {
        CertInventoryMail.Report r = CertInventorySamples.large();
        assertThat(r.upcoming()).hasSize(38);
        assertThat(r.later()).hasSize(367);
        assertThat(r.upcoming().get(0).domain()).isEqualTo("legacy.example.com");
        assertThat(r.upcoming().get(1).domain()).isEqualTo(CertInventorySamples.LONG_HOST);
        assertThat(r.upcoming().stream().map(CertInventoryMail.Cert::daysLeft).toList()).isSorted();
        assertThat(r.later().stream().map(CertInventoryMail.Cert::daysLeft).toList()).isSorted();
        assertThat(r.upcoming()).allMatch(c -> c.daysLeft() <= 30);
        assertThat(r.later()).allMatch(c -> c.daysLeft() > 30);
        // hijyen grupları KIRPILMAZ (PDF hepsini basar; gövde ilk 10'u)
        CertInventoryMail.FindingGroup contacts = r.findings().stream().filter(g -> "contacts".equals(g.key())).findFirst().orElseThrow();
        assertThat(contacts.total()).isEqualTo(14);
        assertThat(contacts.findings()).hasSize(14);
    }

    @Test
    @DisplayName("satır alanları: takım adı, sağlayıcı (O → CN yedeği), kısa durum; bitiş kurum gününde (22:30 UTC → ertesi gün)")
    void rowFields() {
        CertInventoryMail.Report r = CertInventorySamples.large();
        CertInventoryMail.Cert www = r.upcoming().stream().filter(c -> c.domain().equals("www.example.com")).findFirst().orElseThrow();
        assertThat(www.expiry()).isEqualTo("01.10.2026");                 // 30.09 22:30 UTC = 01.10 01:30 İstanbul
        assertThat(www.team()).isNull();                                  // takım atanmamış kayıt
        CertInventoryMail.Cert legacy = r.upcoming().get(0);
        assertThat(legacy.status()).isEqualTo("süresi dolmuş");
        CertInventoryMail.Cert cdn = r.upcoming().stream().filter(c -> c.domain().equals("cdn.example.com")).findFirst().orElseThrow();
        assertThat(cdn.status()).isEqualTo("dağıtım eksik");

        CertificateDto cnOnly = new CertificateDto();
        cnOnly.setIssuerCn("Örnek CA G2");
        assertThat(CertInventorySummary.issuerOf(cnOnly)).isEqualTo("Örnek CA G2");
        assertThat(CertInventorySummary.issuerOf(new CertificateDto())).isNull();
        assertThat(CertInventorySummary.istDate("2026-12-31T21:30:00")).isEqualTo("01.01.2027");
        assertThat(CertInventorySummary.istDate("2026-12-31T21:30:00Z")).isEqualTo("01.01.2027");
        assertThat(CertInventorySummary.istDate("2026-12-31T10:00:00+00:00")).isEqualTo("31.12.2026");
        assertThat(CertInventorySummary.istDate("2026-12-31")).isEqualTo("31.12.2026");     // ayrıştırılamaz → dilim
        assertThat(CertInventorySummary.istDate(null)).isNull();
    }

    @Test
    @DisplayName("kırılım: takımlar ACİLİYETE göre (acil ↓, hata ↓, toplam ↓), sağlayıcılar PAYA göre; takımsız kayıt ayrı satır")
    void breakdowns() {
        CertInventoryMail.Report r = CertInventorySamples.large();
        List<CertInventoryMail.Breakdown> teams = r.byTeam();
        assertThat(teams).hasSize(13);
        assertThat(teams).extracting(CertInventoryMail.Breakdown::label).contains(CertInventorySummary.NO_TEAM, CertInventorySamples.NASTY_TEAM);
        for (int i = 1; i < teams.size(); i++) {
            assertThat(teams.get(i - 1).urgent()).isGreaterThanOrEqualTo(teams.get(i).urgent());
        }
        assertThat(teams.stream().mapToInt(CertInventoryMail.Breakdown::total).sum()).isEqualTo(r.active());
        assertThat(teams.stream().mapToInt(CertInventoryMail.Breakdown::urgent).sum()).isEqualTo(38);
        List<CertInventoryMail.Breakdown> cas = r.byIssuer();
        assertThat(cas).hasSize(5);
        for (int i = 1; i < cas.size(); i++) assertThat(cas.get(i - 1).total()).isGreaterThanOrEqualTo(cas.get(i).total());
    }

    @Test
    @DisplayName("boş envanter ve eksik girdiler düşürmez (null hijyen / null harita)")
    void emptyInputs() {
        CertInventoryMail.Report r = CertInventorySummary.of("Eylül 2026", List.of(), null, null, null);
        assertThat(r.total()).isZero();
        assertThat(r.findings()).isEmpty();
        assertThat(r.upcoming()).isEmpty();
        assertThat(CertInventorySamples.empty().active()).isZero();
        assertThat(CertInventorySamples.empty().passive()).isEqualTo(2);
    }
}
