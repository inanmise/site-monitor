package com.sitemonitor.service.report.executive;

import com.sitemonitor.model.ExecutiveSummaryReport;
import com.sitemonitor.model.ExecutiveSummaryTeamReport;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.ExecutiveSummaryReportRepository;
import com.sitemonitor.repository.ExecutiveSummaryTeamReportRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.CertificateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.sitemonitor.service.report.executive.ExecTestSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Takım kapsamı (2026-10-10) — servis düzeyi: bağlam kapsamı bölümlere iner, özet kapsamı taşır, kurum ve takım
 * belleği ayrıdır, gönderilmiş takım ayı takım kaydından okunur, koşu boyu paylaşım taban veriyi bir kez okur.
 */
class ExecutiveSummaryServiceTeamScopeTest {

    private final ExecutiveSummarySettings settings = mock(ExecutiveSummarySettings.class);
    private final CertificateService certs = mock(CertificateService.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ExecutiveSummaryReportRepository repo = mock(ExecutiveSummaryReportRepository.class);
    private final ExecutiveSummaryTeamReportRepository teamRepo = mock(ExecutiveSummaryTeamReportRepository.class);

    /** Bağlamın kapsamını kaydeden sahte bölüm. */
    static final class Probe implements ExecutiveSummarySection {
        final List<Long> scopes = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        @Override public String key() { return "availability"; }
        @Override public int order() { return 10; }
        @Override public String title() { return "Erişilebilirlik"; }
        @Override public SectionResult compute(ExecutiveSummaryContext ctx) {
            scopes.add(ctx.scopeTeamId());
            names.add(ctx.scopeTeamName());
            ctx.inventory();
            ctx.latestCerts();
            return SectionResult.builder(key(), order(), title()).status(SectionResult.OK)
                    .verdict("V", SectionResult.T_OK, "hüküm").build();
        }
    }

    @BeforeEach
    void setUp() {
        when(settings.availabilityTarget()).thenReturn(99.9);
        when(settings.renewalTargetDays()).thenReturn(30);
        when(repo.findByReportYearAndReportMonth(anyInt(), anyInt())).thenReturn(Optional.empty());
        when(teamRepo.findByTeamIdAndReportYearAndReportMonth(anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());
        Team a = new Team();
        a.setId(5L);
        a.setName("Ödeme");
        when(teams.findAll()).thenReturn(List.of(a));
    }

    private ExecutiveSummaryService svc(List<ExecutiveSummarySection> sections) {
        ExecutiveSummaryService s = new ExecutiveSummaryService(sections, settings, certs, teams, jdbc, repo);
        s.setTeamReportRepo(teamRepo);
        s.setClock(() -> NOW);
        return s;
    }

    @Test
    @DisplayName("takım hesabı: bölüm bağlamı takım kapsamında, özetin kapsamı takım (ad dahil); kurum hesabı kapsam ORG")
    void scopeReachesSectionsAndSummary() {
        Probe p = new Probe();
        ExecutiveSummaryService s = svc(List.of(p));
        ExecutiveSummary team = s.compute(YearMonth.of(2026, 9), 5L, null);
        ExecutiveSummary org = s.compute(YearMonth.of(2026, 9));
        assertThat(p.scopes).containsExactly(5L, null);
        assertThat(p.names).containsExactly("Ödeme", null);
        assertThat(team.scope()).isEqualTo(ExecutiveSummary.Scope.team(5L, "Ödeme"));
        assertThat(team.scope().isTeam()).isTrue();
        assertThat(org.scope()).isEqualTo(ExecutiveSummary.Scope.ORG);
        assertThat(org.scope().isTeam()).isFalse();
    }

    @Test
    @DisplayName("bellek kapsam başına: aynı ayın kurum ve takım hesabı birbirini ezmez; takım ikinci istekte hesaplanmaz")
    void memoPerScope() {
        Probe p = new Probe();
        ExecutiveSummaryService s = svc(List.of(p));
        YearMonth sep = YearMonth.of(2026, 9);
        assertThat(s.get(sep, false, false).scope().isTeam()).isFalse();
        assertThat(s.get(sep, 5L, false, false).scope().teamId()).isEqualTo(5L);
        s.get(sep, 5L, false, false);
        s.get(sep, false, false);
        assertThat(p.scopes).containsExactly(null, 5L);
    }

    @Test
    @DisplayName("koşu boyu paylaşım: aynı haritayla kurum + iki takım hesabı envanteri ve son sertifikaları BİR kez okur")
    void sharedRunReadsBaseOnce() throws Exception {
        Probe p = new Probe();
        ExecutiveSummaryService s = svc(List.of(p));
        Map<String, Object> shared = s.newRunShared();
        YearMonth sep = YearMonth.of(2026, 9);
        s.compute(sep, null, shared);
        s.compute(sep, 5L, shared);
        s.compute(sep, 6L, shared);
        verify(jdbc, times(1)).queryForList(ExecutiveSummaryService.INVENTORY_SQL);
        verify(certs, times(1)).getAllLatest();
        verify(teams, times(1)).findAll();
    }

    @Test
    @DisplayName("gönderilmiş takım ayı takım kaydından okunur (snapshot, kapsam korunur); kurum kaydı takıma karışmaz")
    void teamSnapshot() {
        Probe p = new Probe();
        ExecutiveSummaryService s = svc(List.of(p));
        YearMonth sep = YearMonth.of(2026, 9);
        ExecutiveSummary computed = s.compute(sep, 5L, null);
        ExecutiveSummaryTeamReport row = new ExecutiveSummaryTeamReport();
        row.setTeamId(5L);
        row.setStatus(ExecutiveSummaryTeamReport.SENT);
        row.setSummaryJson(ExecutiveSummaryService.toJson(computed));
        when(teamRepo.findByTeamIdAndReportYearAndReportMonth(5L, 2026, 9)).thenReturn(Optional.of(row));
        ExecutiveSummaryReport orgRow = new ExecutiveSummaryReport();
        orgRow.setStatus(ExecutiveSummaryReport.SENT);
        orgRow.setSummaryJson(ExecutiveSummaryService.toJson(s.compute(sep)));
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(orgRow));

        ExecutiveSummary snap = s.get(sep, 5L, false, false);
        assertThat(snap.source()).isEqualTo(ExecutiveSummary.SOURCE_SNAPSHOT);
        assertThat(snap.scope()).isEqualTo(ExecutiveSummary.Scope.team(5L, "Ödeme"));
        assertThat(s.get(sep, false, false).scope()).isEqualTo(ExecutiveSummary.Scope.ORG);
        // takım 6'nın kaydı yok → canlı
        assertThat(s.get(sep, 6L, false, false).source()).isEqualTo(ExecutiveSummary.SOURCE_LIVE);
    }

    @Test
    @DisplayName("kapsam alanı olmayan ESKİ kayıt (2026-10-10 öncesi JSON) kurum kapsamıyla okunur")
    void legacySnapshotWithoutScope() {
        Probe p = new Probe();
        ExecutiveSummaryService s = svc(List.of(p));
        tools.jackson.databind.node.ObjectNode tree = (tools.jackson.databind.node.ObjectNode)
                ExecutiveSummaryService.JSON.readTree(ExecutiveSummaryService.toJson(s.compute(YearMonth.of(2026, 9))));
        assertThat(tree.has("scope")).isTrue();
        tree.remove("scope");
        String json = ExecutiveSummaryService.JSON.writeValueAsString(tree);
        assertThat(json).doesNotContain("\"scope\"");
        ExecutiveSummaryReport row = new ExecutiveSummaryReport();
        row.setStatus(ExecutiveSummaryReport.SENT);
        row.setSummaryJson(json);
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(row));
        ExecutiveSummary snap = s.snapshot(YearMonth.of(2026, 9));
        assertThat(snap).isNotNull();
        assertThat(snap.scope()).isEqualTo(ExecutiveSummary.Scope.ORG);
    }

    @Test
    @DisplayName("ay listesi takım kapsamında takımın gönderim durumunu gösterir")
    void monthsForTeam() {
        ExecutiveSummaryTeamReport r = new ExecutiveSummaryTeamReport();
        r.setTeamId(5L);
        r.setReportYear(2026);
        r.setReportMonth(9);
        r.setStatus(ExecutiveSummaryTeamReport.SENT);
        r.setSentAt("2026-10-01T06:00:00");
        when(teamRepo.findByTeamIdOrderByReportYearDescReportMonthDesc(eq(5L), any())).thenReturn(List.of(r));
        List<Map<String, Object>> months = svc(List.of()).months(5L);
        assertThat(months).hasSize(13);
        assertThat(months.get(1)).containsEntry("month", "2026-09").containsEntry("status", "SENT");
        assertThat(months.get(0)).containsEntry("current", true).containsEntry("status", null);
    }
}
