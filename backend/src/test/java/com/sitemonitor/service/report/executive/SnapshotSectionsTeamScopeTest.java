package com.sitemonitor.service.report.executive;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.TlsGradeChange;
import com.sitemonitor.service.crypto.CryptoInventoryService;
import com.sitemonitor.service.quality.DataQualityService;
import com.sitemonitor.service.quality.DataQualityService.IssueCount;
import com.sitemonitor.service.quality.DataQualityService.MonthEnds;
import com.sitemonitor.service.quality.DataQualityService.TeamDigest;
import com.sitemonitor.service.tlsgrade.TlsGradeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.sitemonitor.service.report.executive.ExecTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Rapor anı bölümleri — TAKIM kapsamı (2026-10-10): TLS notu (bağlamın SY/UG uç noktaları + düşüş penceresi alan adıyla
 * süzülür), kripto hazırlığı (servis kapsamı [takım]), veri kalitesi (takımın puanı, ay sonu team_key satırı, takımın
 * kuralları). Kurum çıktısı değişmez.
 */
class SnapshotSectionsTeamScopeTest {

    private static final Map<String, ExecutiveSummaryContext.InventoryRow> INV = invMap(
            inv("sy.example.com", 1L, null, 1, null),
            inv("ug.example.com", 2L, 1L, 2, null),
            inv("other.example.com", 2L, null, 1, null));

    private static CertificateDto graded(String domain, Long team, Integer tier, String grade, String... reasons) {
        CertificateDto d = cert(domain, team, tier, null, null);
        d.setTlsGrade(grade);
        d.setTlsGradeReasons(List.of(reasons));
        return d;
    }

    private static final List<CertificateDto> LATEST = List.of(
            graded("sy.example.com", 1L, 1, "A"),
            graded("ug.example.com", 2L, 2, "C", "WEAK_CIPHER_ACCEPTED"),
            graded("other.example.com", 2L, 1, "F", "CERT_EXPIRED"));

    private static TlsGradeChange drop(long inv, String domain, Long team, String from, String to, String at) {
        TlsGradeChange c = new TlsGradeChange();
        c.setInventoryId(inv);
        c.setDomain(domain);
        c.setTeamId(team);
        c.setFromGrade(from);
        c.setToGrade(to);
        c.setDirection(TlsGradeChange.DROP);
        c.setChangedAt(at);
        return c;
    }

    private static List<TlsGradeChange> drops() {
        List<TlsGradeChange> d = new ArrayList<>();
        d.add(drop(2, "ug.example.com", 2L, "A", "C", "2026-09-20T08:00:00"));
        d.add(drop(3, "other.example.com", 2L, "B", "F", "2026-09-15T08:00:00"));
        d.add(drop(1, "sy.example.com", 1L, "A+", "A", "2026-09-10T08:00:00"));
        d.add(drop(3, "other.example.com", 2L, "A", "B", "2026-09-05T08:00:00"));
        return d;
    }

    // ── TLS notu ──

    @Test
    @DisplayName("TLS (a) dağılım + kapsama + düşüşler yalnız takımın SY/UG uç noktaları; (c) kapsam notu; kurum ifadesi yok")
    void tlsTeam() {
        TlsGradeService svc = mock(TlsGradeService.class);
        when(svc.coverageForDomains(any())).thenReturn(Map.of("endpoints", 2, "ok", 2, "partial", 0, "failed", 0));
        when(svc.dropsBetween(anyString(), anyString(), anyInt())).thenReturn(new TlsGradeService.DropWindow(4, drops()));
        SectionResult r = new TlsGradeSection(svc).compute(teamCtx(1, LATEST, INV));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> cap = ArgumentCaptor.forClass(Collection.class);
        verify(svc).coverageForDomains(cap.capture());
        assertThat(cap.getValue()).containsExactlyInAnyOrder("sy.example.com", "ug.example.com");
        assertThat(kpi(r, "graded").value()).isEqualTo(2);
        assertThat(kpi(r, "f_count").value()).isEqualTo(0);                // other'ın F'si takımın değil
        assertThat(r.status()).isEqualTo(SectionResult.ATTENTION);          // C payı %50 + toparlanmamış düşüş
        // Düşüş KAYDI sayısı takımın kayıtlarından (2), uç nokta 2; other'ın iki kaydı yok
        assertThat(kpi(r, "drops").value()).isEqualTo(2L);
        assertThat(table(r, "drops").rows()).extracting(m -> m.get("domain"))
                .containsExactlyInAnyOrder("ug.example.com", "sy.example.com");
        assertThat(noteCodes(r)).contains("ASOF", "METHOD", "TEAM_SCOPE").doesNotContain("DROPS_SAMPLED", "TEAM_DROPS_SAMPLED");
        assertNoOrgWording(r);

        // Pencere sınırlıysa (500'den çok düşüş) takım notu; kurum notu ve kurum toplamı takımda görünmez
        when(svc.dropsBetween(anyString(), anyString(), anyInt())).thenReturn(new TlsGradeService.DropWindow(900, drops()));
        SectionResult sampled = new TlsGradeSection(svc).compute(teamCtx(1, LATEST, INV));
        assertThat(noteCodes(sampled)).contains("TEAM_DROPS_SAMPLED").doesNotContain("DROPS_SAMPLED");
        assertThat(kpi(sampled, "drops").value()).isEqualTo(2L);
        assertNoOrgWording(sampled);
    }

    @Test
    @DisplayName("TLS (b) kurum kapsamı değişmez: bütün uç noktalar, kurum düşüş toplamı, DROPS_SAMPLED, kapsam notu yok")
    void tlsOrg() {
        TlsGradeService svc = mock(TlsGradeService.class);
        when(svc.coverageForDomains(any())).thenReturn(Map.of("endpoints", 3, "ok", 3, "partial", 0, "failed", 0));
        when(svc.dropsBetween(anyString(), anyString(), anyInt())).thenReturn(new TlsGradeService.DropWindow(900, drops()));
        SectionResult r = new TlsGradeSection(svc).compute(orgCtx(LATEST, INV));
        assertThat(kpi(r, "graded").value()).isEqualTo(3);
        assertThat(kpi(r, "f_count").value()).isEqualTo(1);
        assertThat(kpi(r, "drops").value()).isEqualTo(900L);
        assertThat(noteCodes(r)).contains("DROPS_SAMPLED").doesNotContain("TEAM_SCOPE", "TEAM_DROPS_SAMPLED");
        assertThat(r.status()).isEqualTo(SectionResult.CRITICAL);           // other: Seviye 1'de F
    }

    @Test
    @DisplayName("TLS: düşüş penceresi koşu boyu paylaşılır — iki takım bağlamı TEK okuma; kapsama takım başına (alan adları farklı)")
    void tlsSharedDrops() {
        TlsGradeService svc = mock(TlsGradeService.class);
        when(svc.dropsBetween(anyString(), anyString(), anyInt())).thenReturn(new TlsGradeService.DropWindow(4, drops()));
        TlsGradeSection s = new TlsGradeSection(svc);
        Map<String, Object> shared = new ConcurrentHashMap<>();
        s.compute(teamCtx(1, LATEST, INV, shared));
        s.compute(teamCtx(2, LATEST, INV, shared));
        verify(svc, times(1)).dropsBetween(eq("2026-08-31T21:00:00"), eq("2026-09-30T21:00:00"), eq(TlsGradeSection.DROP_SCAN));
        verify(svc, times(2)).coverageForDomains(any());
    }

    // ── Kripto hazırlığı ──

    @Test
    @DisplayName("kripto (a) servis kapsamı [takım] (SY ya da UG — servisin kuralı), (b) kurum null, (c) TEAM_ASOF, kurum ifadesi yok")
    void cryptoTeam() {
        CryptoInventoryService crypto = mock(CryptoInventoryService.class);
        when(crypto.summary(eq(List.of(1L)), eq(CryptoReadinessSection.TOP_N)))
                .thenReturn(CryptoReadinessSectionTest.summary(1, 0, 2, 0, 0, 3));
        when(crypto.summary(isNull(), eq(CryptoReadinessSection.TOP_N)))
                .thenReturn(CryptoReadinessSectionTest.summary(2, 5, 9, 0, 1, 16));
        CryptoReadinessSection s = new CryptoReadinessSection(crypto);

        SectionResult team = s.compute(teamCtx(1, List.of(), INV));
        verify(crypto).summary(List.of(1L), CryptoReadinessSection.TOP_N);
        assertThat(kpi(team, "broken").value()).isEqualTo(1);
        assertThat(team.data()).containsEntry("total", 3);
        assertThat(noteCodes(team)).containsExactly("TEAM_ASOF", "METHOD", "KEX");
        assertThat(team.headlineKpi()).isEqualTo("broken");
        assertNoOrgWording(team);

        SectionResult org = s.compute(orgCtx(List.of(), INV));
        assertThat(org.data()).containsEntry("total", 17);
        assertThat(noteCodes(org)).containsExactly("ASOF", "METHOD", "KEX");
    }

    // ── Veri kalitesi ──

    private static final Instant GEN = Instant.parse("2026-10-10T08:59:00Z");

    /** Ay sonu görüntüleri: kurum (0) 80→70, takım 1 60→52, takım 2 90→91. */
    private static MonthEnds ends() {
        return new MonthEnds("2026-08-31", "2026-09-30", Map.of(0L, 80, 1L, 60, 2L, 90), Map.of(0L, 70, 1L, 52, 2L, 91));
    }

    private static TeamDigest teamDigest(Integer score, String band, List<IssueCount> costliest) {
        return new TeamDigest(GEN, 1L, "Takım A", score, band, 6, 14, costliest);
    }

    @Test
    @DisplayName("veri kalitesi (a) takımın puanı + team_key ay sonu satırı + takım kovasının kuralları; (c) takım kodları, kurum ifadesi yok")
    void dataQualityTeam() {
        DataQualitySection s = new DataQualitySection(mock(DataQualityService.class));
        List<IssueCount> costly = List.of(new IssueCount("INV_NO_TIER", 3, 10, 9.5), new IssueCount("TEAM_NO_ESCALATION", 1, 1, 7.1));
        SectionResult r = s.evaluateTeam(teamCtx(1, List.of(), INV), teamDigest(52, "NEEDS_ATTENTION", costly), ends());
        assertThat(r.headlineKpi()).isEqualTo("team_score");
        assertThat(kpiCodes(r)).containsExactly("team_score", "month_end", "findings", "items");
        assertThat(kpi(r, "team_score").value()).isEqualTo(52);
        assertThat(kpi(r, "month_end").value()).isEqualTo(52);
        assertThat(kpi(r, "month_end").delta()).isEqualTo(-8.0);         // takımın 60 → 52 (kurumun 80 → 70 değil)
        assertThat(kpi(r, "items").value()).isEqualTo(14);
        assertThat(verdictCodes(r)).containsExactly("TEAM_SCORE", "MONTH_DOWN", "TEAM_TOP_RULE");
        assertThat(r.verdicts().get(1).params()).containsExactly(60, 52, 8);
        assertThat(tableCodes(r)).containsExactly("costly_rules");
        assertThat(table(r, "costly_rules").rows()).extracting(m -> m.get("rule")).containsExactly("INV_NO_TIER", "TEAM_NO_ESCALATION");
        assertThat(noteCodes(r)).containsExactly("TEAM_ASOF", "TREND", "TEAM_METHOD");
        assertThat(r.status()).isEqualTo(SectionResult.ATTENTION);
        assertThat(r.data()).containsEntry("team_score", 52).doesNotContainKey("org_score");
        assertNoOrgWording(r);

        // İyi bant ama ay içinde ≥ 5 puan düşüş → takip (kurumla aynı kural, takımın satırıyla)
        SectionResult good = s.evaluateTeam(teamCtx(1, List.of(), INV), teamDigest(80, "GOOD", List.of()),
                new MonthEnds("2026-08-31", "2026-09-30", Map.of(1L, 86), Map.of(1L, 80)));
        assertThat(good.status()).isEqualTo(SectionResult.ATTENTION);
        assertThat(verdictCodes(good)).containsExactly("TEAM_SCORE", "MONTH_DOWN");

        // Takım değerlendirmede yok (pasif / silinmiş) ya da puanı yok → TEAM_NO_DATA; görüntü yok → NO_TREND
        SectionResult none = s.evaluateTeam(teamCtx(1, List.of(), INV), null, MonthEnds.NONE);
        assertThat(none.status()).isEqualTo(SectionResult.NO_DATA);
        assertThat(verdictCodes(none)).containsExactly("TEAM_NO_DATA");
        assertThat(noteCodes(none)).containsExactly("TEAM_ASOF", "NO_TREND", "TEAM_METHOD");
        assertNoOrgWording(none);
    }

    @Test
    @DisplayName("veri kalitesi compute: takımda teamDigest(takım) + paylaşılan ay sonu (koşuda TEK okuma); kurum özeti istenmez")
    void dataQualityComputeAndShare() {
        DataQualityService q = mock(DataQualityService.class);
        when(q.teamDigest(anyLong())).thenReturn(teamDigest(95, "EXCELLENT", List.of()));
        when(q.monthEnds(LocalDate.of(2026, 9, 1))).thenReturn(ends());
        DataQualitySection s = new DataQualitySection(q);
        Map<String, Object> shared = new ConcurrentHashMap<>();
        SectionResult a = s.compute(teamCtx(1, List.of(), INV, shared));
        s.compute(teamCtx(2, List.of(), INV, shared));
        verify(q).teamDigest(1L);
        verify(q).teamDigest(2L);
        verify(q, never()).digest();
        verify(q, times(1)).monthEnds(LocalDate.of(2026, 9, 1));
        // Mükemmel bant ama takımın ay sonu puanı 60 → 52 (≥ 5 puan düşüş) → takip
        assertThat(a.status()).isEqualTo(SectionResult.ATTENTION);
        assertThat(verdictCodes(a)).containsExactly("TEAM_SCORE", "MONTH_DOWN");

        // Kurum kapsamı aynı haritayla: ay sonu yeniden okunmaz, kurum özeti kullanılır
        when(q.digest()).thenReturn(new DataQualityService.Digest(GEN, 70, "NEEDS_ATTENTION", 9, List.of(), List.of(), List.of()));
        SectionResult org = s.compute(new ExecutiveSummaryContext(SEP, NOW, 99.9, 30, List::of, () -> INV, () -> TEAMS,
                null, null, shared));
        verify(q, times(1)).monthEnds(LocalDate.of(2026, 9, 1));
        assertThat(org.headlineKpi()).isEqualTo("org_score");
        assertThat(verdictCodes(org)).startsWith("SCORE");
    }
}
