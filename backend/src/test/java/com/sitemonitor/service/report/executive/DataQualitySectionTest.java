package com.sitemonitor.service.report.executive;

import com.sitemonitor.service.quality.DataQualityService;
import com.sitemonitor.service.quality.DataQualityService.Digest;
import com.sitemonitor.service.quality.DataQualityService.IssueCount;
import com.sitemonitor.service.quality.DataQualityService.MonthEnds;
import com.sitemonitor.service.quality.DataQualityService.TeamScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.report.executive.ExecTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Veri kalitesi bölümü — durum kurum bandına göre (doğruluk tablosu) + ay içi düşüş kuralı, aydan aya değişim (ay sonu
 * görüntüleri, ayın İstanbul takvimi), en düşük takımlar ve en çok puan kaybettiren kurallar.
 */
class DataQualitySectionTest {

    private static final Instant GEN = Instant.parse("2026-10-10T08:59:00Z");
    private final DataQualityService quality = mock(DataQualityService.class);
    private final DataQualitySection section = new DataQualitySection(quality);

    private static Digest digest(Integer score, String band, List<TeamScore> teams, List<IssueCount> costly) {
        return new Digest(GEN, score, band, 7, teams, costly, costly);
    }

    private static MonthEnds ends(Integer prevOrg, Integer curOrg) {
        return new MonthEnds(prevOrg == null ? null : "2026-08-31", curOrg == null ? null : "2026-09-30",
                prevOrg == null ? Map.of() : Map.of(0L, prevOrg), curOrg == null ? Map.of() : Map.of(0L, curOrg));
    }

    @ParameterizedTest(name = "puan {0} ({1}), ay sonu {2} → {3} ⇒ {4}")
    @CsvSource({
            "95, EXCELLENT, 90, 95, ok",
            "80, GOOD,      80, 80, ok",
            "80, GOOD,      85, 80, attention",     // ay içinde 5 puan düştü → takip
            "80, GOOD,      84, 80, ok",            // 4 puan düşüş eşiğin altında
            "60, NEEDS_ATTENTION, , , attention",
            "40, POOR,      ,   , critical",
            ",   NO_DATA,   ,   , no_data",
    })
    @DisplayName("DURUM (kurum bandı): Mükemmel/İyi → sorunsuz, İyileştirilmeli → takip, Zayıf → kritik; ≥ 5 puan düşüş → takip")
    void statusTruthTable(Integer score, String band, Integer prev, Integer cur, String expected) {
        SectionResult r = section.evaluate(ctx(), digest(score, band, List.of(), List.of()), ends(prev, cur));
        assertThat(r.status()).isEqualTo(expected);
    }

    @Test
    @DisplayName("compute: özet + AYIN İLK GÜNÜYLE ay sonu görüntüleri (İstanbul takvimi); rapor anı = özetin üretim anı")
    void computeUsesMonthStart() {
        when(quality.digest()).thenReturn(digest(88, "GOOD", List.of(), List.of()));
        when(quality.monthEnds(LocalDate.of(2026, 9, 1))).thenReturn(ends(84, 88));
        SectionResult r = section.compute(ctx(YearMonth.of(2026, 9), NOW, 99.9, 30, List.of(), Map.of(), Map.of()));
        verify(quality).monthEnds(LocalDate.of(2026, 9, 1));
        assertThat(r.snapshot()).isTrue();
        assertThat(r.asOf()).isEqualTo("2026-10-10T08:59:00");
        assertThat(verdictCodes(r)).containsExactly("SCORE", "MONTH_UP");
        assertThat(kpi(r, "month_end").value()).isEqualTo(88);
        assertThat(kpi(r, "month_end").delta()).isEqualTo(4.0);
        assertThat(kpi(r, "month_end").deltaFormat()).isEqualTo("pp");
        assertThat(kpi(r, "month_end").deltaTone()).isEqualTo(SectionResult.T_OK);
        assertThat(r.notes()).extracting(SectionResult.Note::code).containsExactly("ASOF", "TREND", "METHOD");
    }

    @Test
    @DisplayName("aydan aya: düşüş / aynı hükümleri; görüntü yoksa değişim boş + NO_TREND notu")
    void monthOverMonth() {
        SectionResult down = section.evaluate(ctx(), digest(70, "NEEDS_ATTENTION", List.of(), List.of()), ends(78, 70));
        assertThat(verdictCodes(down)).contains("MONTH_DOWN");
        assertThat(down.verdicts().get(1).params()).containsExactly(78, 70, 8);
        assertThat(down.verdicts().get(1).tone()).isEqualTo(SectionResult.T_WARN);
        assertThat(kpi(down, "month_end").deltaTone()).isEqualTo(SectionResult.T_BAD);

        SectionResult same = section.evaluate(ctx(), digest(90, "EXCELLENT", List.of(), List.of()), ends(90, 90));
        assertThat(verdictCodes(same)).contains("MONTH_SAME");

        SectionResult onlyCur = section.evaluate(ctx(), digest(90, "EXCELLENT", List.of(), List.of()), ends(null, 90));
        assertThat(verdictCodes(onlyCur)).doesNotContain("MONTH_UP", "MONTH_DOWN", "MONTH_SAME");
        assertThat(kpi(onlyCur, "month_end").value()).isEqualTo(90);
        assertThat(kpi(onlyCur, "month_end").delta()).isNull();
        assertThat(onlyCur.notes()).extracting(SectionResult.Note::code).contains("NO_TREND");

        SectionResult none = section.evaluate(ctx(), digest(90, "EXCELLENT", List.of(), List.of()), MonthEnds.NONE);
        assertThat(kpi(none, "month_end").value()).isNull();
        assertThat(kpi(none, "month_end").hint()).isNull();
    }

    @Test
    @DisplayName("takımlar: puanı olmayanlar sıralamaya girmez, en düşük önce (özet sırası), ≤ 10 satır, takım farkı; kurallar puan kaybına göre")
    void teamsAndRules() {
        List<TeamScore> teams = new ArrayList<>();
        teams.add(new TeamScore(1L, "Ödeme", 38, "POOR", 12));
        teams.add(new TeamScore(2L, "Ağ", 62, "NEEDS_ATTENTION", 5));
        for (int i = 0; i < 12; i++) teams.add(new TeamScore(10L + i, "T" + i, 90 + (i % 10), "EXCELLENT", 0));
        teams.add(new TeamScore(99L, "Boş takım", null, "NO_DATA", 0));
        List<IssueCount> costly = List.of(new IssueCount("TEAM_NO_ESCALATION", 2, 14, 7.5),
                new IssueCount("INV_NO_TIER", 30, 200, 3.2));
        MonthEnds me = new MonthEnds("2026-08-31", "2026-09-30", Map.of(0L, 74, 1L, 45),
                Map.of(0L, 71, 1L, 38, 2L, 62));
        SectionResult r = section.evaluate(ctx(), digest(71, "NEEDS_ATTENTION", teams, costly), me);

        assertThat(r.status()).isEqualTo(SectionResult.ATTENTION);
        assertThat(verdictCodes(r)).containsExactly("SCORE", "MONTH_DOWN", "POOR_TEAMS", "TOP_RULE");
        assertThat(kpi(r, "teams_scored").value()).isEqualTo(14);
        assertThat(kpi(r, "teams_scored").hintParams()).containsExactly(1);

        SectionResult.Table t = table(r, "lowest_teams");
        assertThat(t.rows()).hasSize(DataQualitySection.TEAM_LIMIT);
        assertThat(t.total()).isEqualTo(14);
        assertThat(t.rows().get(0)).containsEntry("team", "Ödeme").containsEntry("score", 38).containsEntry("band", "POOR")
                .containsEntry("delta", -7.0).containsEntry("state", SectionResult.T_BAD);
        assertThat(t.rows().get(1)).containsEntry("delta", null);                     // önceki ay sonu görüntüsü yok
        assertThat(t.rows()).extracting(m -> m.get("team")).doesNotContain("Boş takım");

        SectionResult.Table rules = table(r, "costly_rules");
        assertThat(rules.rows()).extracting(m -> m.get("rule")).containsExactly("TEAM_NO_ESCALATION", "INV_NO_TIER");
        assertThat(rules.rows().get(0)).containsEntry("points_lost", 7.5).containsEntry("failing", 2).containsEntry("eligible", 14);
        assertThat(r.verdicts().get(3).params().get(0)).isEqualTo(new SectionResult.Param("TEAM_NO_ESCALATION", "dq_rule"));
    }

    @Test
    @DisplayName("veri yok: puan null → VERİ YOK, gösterge boş, tablolar boş metinle")
    void noData() {
        SectionResult r = section.evaluate(ctx(), digest(null, "NO_DATA", List.of(), List.of()), MonthEnds.NONE);
        assertThat(r.status()).isEqualTo(SectionResult.NO_DATA);
        assertThat(verdictCodes(r)).containsExactly("NO_DATA");
        assertThat(kpi(r, "org_score").value()).isNull();
        assertThat(kpi(r, "findings").value()).isNull();
        assertThat(table(r, "lowest_teams").rows()).isEmpty();
    }
}
