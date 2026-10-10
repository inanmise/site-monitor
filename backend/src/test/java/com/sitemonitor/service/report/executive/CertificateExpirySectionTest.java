package com.sitemonitor.service.report.executive;

import com.sitemonitor.dto.CertificateDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.report.executive.ExecTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Yaklaşan bitişler — kova sınırları, tier/takım kırılımı, plan kapsaması, duraklatılmış hariç, rapor anı. */
class CertificateExpirySectionTest {

    private final CertificateExpirySection section = new CertificateExpirySection();

    /** NOW'dan {@code days} gün + 1 saat sonra biten sertifika (tam gün aşağı yuvarlanır → {@code days}). */
    private static String in(int days) {
        return NOW.plusSeconds(days * 86_400L + 3600).toString();
    }

    @ParameterizedTest(name = "{0} gün → kova {1}")
    @CsvSource({ "-1,-1", "0,0", "30,0", "31,1", "60,1", "61,2", "90,2", "91,3" })
    @DisplayName("kova sınırları (ayrık): <0 / 0–30 / 31–60 / 61–90 / 90+")
    void buckets(int days, int bucket) {
        assertThat(CertificateExpirySection.bucket(days)).isEqualTo(bucket);
    }

    @Test
    @DisplayName("tarih yoksa kova 4; kalan gün aşağı yuvarlanır (23 sa → 0, −1 sa → −1)")
    void unknownAndFloor() {
        assertThat(CertificateExpirySection.bucket(null)).isEqualTo(4);
        assertThat(CertificateExpirySection.daysBetween(NOW, NOW.plusSeconds(23 * 3600))).isEqualTo(0);
        assertThat(CertificateExpirySection.daysBetween(NOW, NOW.minusSeconds(3600))).isEqualTo(-1);
    }

    @Test
    @DisplayName("sayılar + plan kapsaması + tier/takım kırılımı + duraklatılmış hariç; süresi dolmuş → KRİTİK")
    void evaluate() {
        CertificateDto expired = cert("expired.com", 1L, 1, in(-3), null);
        CertificateDto soon = cert("soon.com", 1L, 1, in(10), null);
        CertificateDto mid = cert("mid.com", 2L, 2, in(45), null);
        CertificateDto late = cert("late.com", 2L, null, in(80), null);
        CertificateDto far = cert("far.com", 2L, 3, in(200), null);
        CertificateDto nodate = cert("nodate.com", 2L, 3, null, null);
        CertificateDto paused = cert("paused.com", 1L, 1, in(1), null);
        paused.setPaused(true);
        ExecutiveSummaryContext c = ctx(SEP, NOW, 99.9, 30, List.of(expired, soon, mid, late, far, nodate, paused),
                invMap(inv("soon.com", 1L, 1, "2026-10-15")), Map.of(1L, "Takım A", 2L, "Takım B"));
        SectionResult r = section.compute(c);

        assertThat(r.snapshot()).isTrue();
        assertThat(r.asOf()).isEqualTo("2026-10-10T09:00:00");
        assertThat(r.status()).isEqualTo(SectionResult.CRITICAL);
        assertThat(verdictCodes(r)).containsExactly("EXPIRED", "WITHIN30");
        assertThat(kpi(r, "expired").value()).isEqualTo(1);
        assertThat(kpi(r, "within30").value()).isEqualTo(1);
        assertThat(kpi(r, "within60").value()).isEqualTo(2);
        assertThat(kpi(r, "within90").value()).isEqualTo(3);
        assertThat(kpi(r, "unknown").value()).isEqualTo(1);
        // acil = süresi dolmuş + 30 gün içinde = 2; 1'inin planı var → %50
        assertThat(kpi(r, "plan_coverage").value()).isEqualTo(50.0);

        SectionResult.Table tiers = table(r, "by_tier");
        assertThat(tiers.rows()).extracting(m -> m.get("tier")).containsExactly(null, 1, 2, 3);
        Map<String, Object> t1 = tiers.rows().get(1);
        assertThat(t1).containsEntry("expired", 1).containsEntry("d30", 1);

        SectionResult.Table soonest = table(r, "soonest");
        assertThat(soonest.rows()).extracting(m -> m.get("domain")).containsExactly("expired.com", "soon.com", "mid.com", "late.com");
        assertThat(soonest.rows().get(0).get("state")).isEqualTo(SectionResult.T_BAD);
        assertThat(soonest.rows().get(1).get("planned_at")).isEqualTo("2026-10-15");
        assertThat(soonest.rows().get(1).get("team")).isEqualTo("Takım A");

        SectionResult.Table teams = table(r, "by_team");
        assertThat(teams.rows()).extracting(m -> m.get("team")).containsExactly("Takım A", "Takım B");
        assertThat(teams.rows().get(0)).containsEntry("expired", 1).containsEntry("upto30", 1).containsEntry("planned", 1);
    }

    @Test
    @DisplayName("30 gün içinde yok → SORUNSUZ + CLEAR30 (90 gün sayısı parametrede); boş envanter → VERİ YOK")
    void clearAndEmpty() {
        ExecutiveSummaryContext c = ctx(SEP, NOW, 99.9, 30, List.of(cert("x.com", 1L, 1, in(70), null)), Map.of(), Map.of());
        SectionResult r = section.compute(c);
        assertThat(r.status()).isEqualTo(SectionResult.OK);
        assertThat(r.verdicts().get(0).code()).isEqualTo("CLEAR30");
        assertThat(r.verdicts().get(0).params()).containsExactly(1);

        SectionResult empty = section.compute(ctx());
        assertThat(empty.status()).isEqualTo(SectionResult.NO_DATA);
    }

    @Test
    @DisplayName("bitiş günü İstanbul takvimiyle: 21:30Z → ertesi gün")
    void expiryDayIstanbul() {
        ExecutiveSummaryContext c = ctx(SEP, NOW, 99.9, 30,
                List.of(cert("x.com", 1L, 1, "2026-10-20T21:30:00", null)), Map.of(), Map.of());
        List<CertificateExpirySection.Cert> certs = CertificateExpirySection.certs(c);
        assertThat(certs.get(0).expiryDay()).isEqualTo("2026-10-21");
        assertThat(CertificateExpirySection.parseUtc("2026-10-20")).isEqualTo(Instant.parse("2026-10-20T00:00:00Z"));
    }
}
