package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ayarlar → Haftalık Raporlar → takım görünürlüğü satır zenginleştirmesi (2026-09-30): PO'lar, müdür
 * ({@link TeamManagerResolver} — Takım Yönetimi ile AYNI kural), son rapor haftası/durumu; hiç rapor yoksa null.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyReportTeamInfoServiceTest {

    @Mock WeeklyReportRepository reportRepo;
    @Mock AppUserRepository userRepo;

    private WeeklyReportTeamInfoService service;

    private static AppUser user(long id, String name, String orgRole, Long teamId, Set<Long> teamIds, boolean active) {
        AppUser u = new AppUser();
        u.setId(id); u.setUsername("u" + id); u.setDisplayName(name); u.setEmail("u" + id + "@example.com");
        u.setOrgRole(orgRole); u.setTeamId(teamId); u.setActive(active);
        if (teamIds != null) u.setTeamIds(new java.util.LinkedHashSet<>(teamIds));
        return u;
    }

    private static Team team(long id, Long managerId, Long leaderId) {
        Team t = new Team();
        t.setId(id); t.setName("Takım " + id); t.setActive(true); t.setManagerId(managerId); t.setLeaderId(leaderId);
        return t;
    }

    private static WeeklyReport report(long teamId, int year, int week, String status) {
        WeeklyReport r = new WeeklyReport();
        r.setId(100L); r.setTeamId(teamId); r.setReportYear(year); r.setWeekNo(week); r.setWeekLabel("etiket");
        r.setStatus(status); r.setContentJson("{}");
        r.setSubmittedAt("2026-09-25T10:00:00"); r.setApprovedAt("2026-09-26T08:00:00"); r.setUpdatedAt("2026-09-26T08:00:00");
        return r;
    }

    // Takım 1: birincil PO (Kişi B), ek üyelikle PO (Ayşe A), pasif PO (elenir), TECH (elenmez), müdür 90 (üye değil).
    private final AppUser poPrimary   = user(11, "Kişi B", "PO", 1L, Set.of(1L), true);
    private final AppUser poSecondary = user(12, "Ayşe A", "po", 2L, Set.of(2L, 1L), true);
    private final AppUser poInactive  = user(13, "Pasif P", "PO", 1L, Set.of(1L), false);
    private final AppUser tech        = user(14, "Kişi T", "TECH", 1L, Set.of(1L), true);
    private final AppUser manager     = user(90, "Müdür M", "MANAGER", null, Set.of(), true);
    private final AppUser manualMgr   = user(91, "Elle E", "MANAGER", null, Set.of(), true);

    @BeforeEach
    void setUp() {
        tech.setManagerId(90L);
        poPrimary.setManagerId(90L);
        service = new WeeklyReportTeamInfoService(reportRepo, userRepo);
        // Sorgu yalnız aktifleri döndürür; mock bilerek pasif PO'yu da verir — servis savunma olarak yine eler.
        when(userRepo.findByActiveTrueOrderByUsernameAsc())
                .thenReturn(List.of(poPrimary, poSecondary, poInactive, tech, manager, manualMgr));
        when(reportRepo.findLatestPerTeam(anyCollection())).thenReturn(List.of());
    }

    @Test
    @DisplayName("PO listesi: aktif üyeler (birincil + ek üyelik), rol büyük/küçük harf duyarsız, pasif ve TECH elenir, ada göre sıralı, e-posta taşır")
    @SuppressWarnings("unchecked")
    void poUsers() {
        Map<String, Object> m = service.infoByTeam(List.of(team(1, null, null))).get(1L);
        List<Map<String, Object>> pos = (List<Map<String, Object>>) m.get("po_users");
        assertThat(pos).extracting(p -> p.get("display_name")).containsExactly("Ayşe A", "Kişi B");
        assertThat(pos.get(1)).containsEntry("user_id", 11L).containsEntry("email", "u11@example.com");
        verify(userRepo, times(1)).findByActiveTrueOrderByUsernameAsc();   // takım başına değil, bir kez
        verify(userRepo, never()).findAllByOrderByUsernameAsc();            // performans: pasifler (ve fotoları) yüklenmez
    }

    @Test
    @DisplayName("müdür: elle atanmış manager_id kazanır (manager_manual=true) ve e-postası gelir")
    void managerManual() {
        Map<String, Object> m = service.infoByTeam(List.of(team(1, 91L, null))).get(1L);
        assertThat(m).containsEntry("manager_user_id", 91L)
                .containsEntry("manager_display_name", "Elle E")
                .containsEntry("manager_manual", true)
                .containsEntry("manager_email", "u91@example.com");
    }

    @Test
    @DisplayName("müdür: elle atama yoksa üyelerin yönetim zincirinden türetilir (manager_manual=false) — Takım Yönetimi ile aynı kişi")
    void managerDerived() {
        Map<String, Object> m = service.infoByTeam(List.of(team(1, null, null))).get(1L);
        assertThat(m).containsEntry("manager_user_id", 90L)
                .containsEntry("manager_display_name", "Müdür M")
                .containsEntry("manager_manual", false)
                .containsEntry("manager_email", "u90@example.com");
    }

    @Test
    @DisplayName("üyesi olmayan takım: PO listesi boş, müdür yok (null alanlar, manual=false), son rapor null")
    void emptyTeam() {
        Map<String, Object> m = service.infoByTeam(List.of(team(7, null, null))).get(7L);
        assertThat((List<?>) m.get("po_users")).isEmpty();
        assertThat(m.get("manager_user_id")).isNull();
        assertThat(m.get("manager_display_name")).isNull();
        assertThat(m).containsEntry("manager_manual", false);
        assertThat(m.get("manager_email")).isNull();
        assertThat(m.get("last_report")).isNull();
    }

    @Test
    @DisplayName("son rapor: en güncel satırın ISO haftası (2026-W07 biçimi), durumu ve gönderim/onay zamanı")
    @SuppressWarnings("unchecked")
    void lastReport() {
        when(reportRepo.findLatestPerTeam(anyCollection()))
                .thenReturn(List.of(report(1L, 2026, 7, "APPROVED")));
        Map<String, Object> m = service.infoByTeam(List.of(team(1, null, null))).get(1L);
        Map<String, Object> last = (Map<String, Object>) m.get("last_report");
        assertThat(last).containsEntry("iso_week", "2026-W07")
                .containsEntry("report_year", 2026).containsEntry("week_no", 7)
                .containsEntry("status", "APPROVED")
                .containsEntry("submitted_at", "2026-09-25T10:00:00")
                .containsEntry("approved_at", "2026-09-26T08:00:00")
                .containsEntry("week_label", "etiket");
    }

    @Test
    @DisplayName("boş takım listesi: kullanıcı deposu hiç sorgulanmaz")
    void emptyInput() {
        assertThat(service.infoByTeam(List.of())).isEmpty();
        verify(userRepo, times(0)).findByActiveTrueOrderByUsernameAsc();
        verify(reportRepo, never()).findLatestPerTeam(anyCollection());
    }

    @Test
    @DisplayName("performans (2026-10-01): son raporlar takım başına değil TEK sorguda; her takım kendi satırını alır, raporu olmayan null")
    @SuppressWarnings("unchecked")
    void lastReport_singleQueryForAllTeams() {
        when(reportRepo.findLatestPerTeam(anyCollection()))
                .thenReturn(List.of(report(1L, 2026, 7, "APPROVED"), report(2L, 2025, 52, "PENDING")));
        Map<Long, Map<String, Object>> out = service.infoByTeam(List.of(team(1, null, null), team(2, null, null), team(3, null, null)));
        assertThat((Map<String, Object>) out.get(1L).get("last_report")).containsEntry("iso_week", "2026-W07");
        assertThat((Map<String, Object>) out.get(2L).get("last_report")).containsEntry("iso_week", "2025-W52").containsEntry("status", "PENDING");
        assertThat(out.get(3L).get("last_report")).isNull();
        verify(reportRepo, times(1)).findLatestPerTeam(org.mockito.ArgumentMatchers.argThat(ids -> ids.containsAll(List.of(1L, 2L, 3L)) && ids.size() == 3));
        verify(reportRepo, never()).findFirstByTeamIdOrderByReportYearDescWeekNoDesc(anyLong());
    }
}
