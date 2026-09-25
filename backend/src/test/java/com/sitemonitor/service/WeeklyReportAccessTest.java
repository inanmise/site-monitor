package com.sitemonitor.service;

import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeeklyReportImageRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import com.sitemonitor.service.WeeklyReportService.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Haftalık Raporlar modülünün TAKIM BAZLI görünürlüğü (2026-09-16, kullanıcı kararı):
 * varsayılan KAPALI, yalnız açılan takım görür.
 *
 * <p>2026-09-25 kullanıcı kararı: "ADMIN yetkisine sahip bir user her zaman haftalık raporu görmeli" —
 * ADMIN ROLÜ (global ya da takım-kapsamlı müdür) bayrağa takılmaz; KAPSAM değişmez (müdür yine yalnız
 * kendi takımı). AUDIT ve diğer roller için "kapalı" tek anlamda kalır; pano/şerit/hatırlatma değişmedi.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyReportAccessTest {

    @Mock WeeklyReportRepository reportRepo;
    @Mock WeeklyReportImageRepository imageRepo;
    @Mock com.sitemonitor.repository.WeeklyReportMailRepository mailRepo;
    @Mock TeamRepository teamRepo;
    @Mock AppUserRepository userRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock EmailNotificationService emailService;
    @Mock AppSettingsService appSettings;
    @Mock PermissionService permissionService;
    @Mock WeeklyReportKpiService kpiService;
    @Mock MonitoringWeeklyStatsService monitoringStatsService;

    private WeeklyReportService service;

    private static final Actor ADMIN   = new Actor(1L, "admin", "Admin", null, "ADMIN");
    private static final Actor AUDIT   = new Actor(2L, "audit", "Denetçi", null, "AUDIT");
    private static final Actor USER_ON = new Actor(10L, "acik", "Açık Takım", 2L, "USER");
    private static final Actor USER_OFF = new Actor(11L, "kapali", "Kapalı Takım", 7L, "USER");
    /** AD kaynaklı takım-kapsamlı müdür: rol ADMIN ama global DEĞİL — kendi takımı (kapalı) 7. */
    private static final Actor SCOPED_ADMIN_OFF = new Actor(12L, "mudur", "Müdür", 7L, "ADMIN", false);

    private static Team team(Long id, String name, boolean enabled) {
        Team t = new Team();
        t.setId(id); t.setName(name); t.setActive(true); t.setEmail(name + "@example.com");
        t.setWeeklyReportsEnabled(enabled); t.setWeeklyReminderEnabled(true);
        return t;
    }

    private static WeeklyReport report(Long id, Long teamId) {
        WeeklyReport r = new WeeklyReport();
        r.setId(id); r.setTeamId(teamId); r.setStatus("DRAFT"); r.setReportYear(2026); r.setWeekNo(37);
        r.setWeekLabel("2026-W37"); r.setContentJson("{}"); r.setVersion(1);
        return r;
    }

    @BeforeEach
    void setUp() {
        service = new WeeklyReportService(reportRepo, imageRepo, mailRepo, teamRepo, userRepo,
                contactRepo, emailService, new ObjectMapper(), appSettings, permissionService, kpiService, monitoringStatsService,
                org.mockito.Mockito.mock(com.sitemonitor.repository.DomainMonitorRepository.class),
                org.mockito.Mockito.mock(com.sitemonitor.repository.DomainCheckRepository.class));
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(reportRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        Team on = team(2L, "Acik", true), off = team(7L, "Kapali", false);
        when(teamRepo.findById(2L)).thenReturn(Optional.of(on));
        when(teamRepo.findById(7L)).thenReturn(Optional.of(off));
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(on, off));
    }

    @Test
    @DisplayName("varsayılan KAPALI: bayrağı yazılmamış takım kapalı sayılır; açık takımlar kümesi yalnız açık olanı taşır")
    void defaultsToClosed() {
        Team fresh = new Team(); fresh.setId(9L); fresh.setName("Yeni"); fresh.setActive(true);   // bayrak hiç set edilmedi
        when(teamRepo.findById(9L)).thenReturn(Optional.of(fresh));
        assertThat(service.featureEnabled(9L)).isFalse();
        assertThat(service.featureEnabled(null)).isFalse();
        assertThat(service.featureEnabled(2L)).isTrue();
        assertThat(service.featureEnabled(7L)).isFalse();
        assertThat(service.enabledTeamIds()).containsExactly(2L);
    }

    @Test
    @DisplayName("görünürlük: açık takımın üyesi görür, kapalı takımın üyesi görmez; yönetici/denetçi ancak EN AZ BİR takım açıkken görür")
    void visibility() {
        assertThat(service.visibleFor(List.of(2L), false)).isTrue();
        assertThat(service.visibleFor(List.of(7L), false)).isFalse();
        assertThat(service.visibleFor(List.of(7L, 2L), false)).isTrue();
        assertThat(service.visibleFor(null, false)).isFalse();
        assertThat(service.visibleFor(List.of(), true)).isTrue();          // bir takım açık
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(team(7L, "Kapali", false)));
        assertThat(service.visibleFor(List.of(), true)).isFalse();         // hiçbiri açık değil → AUDIT görmez
        assertThat(service.visibleFor(List.of(7L), false)).isFalse();
    }

    @Test
    @DisplayName("2026-09-25: ADMIN rolü (global ya da kapsamlı) HİÇBİR takım açık değilken de menüyü görür; AUDIT/kullanıcı görmez")
    void adminRoleAlwaysSees() {
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(team(7L, "Kapali", false)));   // hepsi kapalı
        assertThat(service.visibleFor(List.of(), true, true)).isTrue();        // global admin
        assertThat(service.visibleFor(List.of(7L), false, true)).isTrue();     // kapsamlı müdür, kapalı takım
        assertThat(service.visibleFor(List.of(), true, false)).isFalse();      // AUDIT değişmedi
        assertThat(service.visibleFor(List.of(7L), false, false)).isFalse();   // kullanıcı değişmedi
    }

    @Test
    @DisplayName("liste: kapalı takım üyesi/AUDIT için kapalı takım elenir; ADMIN rolü kapalı takımı da listeler (2026-09-25)")
    void listFiltersDisabled() {
        when(reportRepo.findByTeamIdAndReportYearOrderByWeekNoDesc(anyLong(), anyInt()))
                .thenAnswer(i -> List.of(report(5L, i.getArgument(0))));
        when(reportRepo.findByReportYearOrderByTeamIdAscWeekNoDesc(anyInt()))
                .thenReturn(List.of(report(5L, 2L), report(6L, 7L)));

        assertThat(service.list(null, 2026, USER_OFF)).isEmpty();          // kapalı takım üyesi
        assertThat(service.list(7L, 2026, ADMIN)).hasSize(1);              // ADMIN, kapalı takımı istedi → görür
        assertThat(service.list(2L, 2026, USER_ON)).hasSize(1);
        assertThat(service.list(null, 2026, ADMIN)).extracting(WeeklyReport::getTeamId).containsExactly(2L, 7L);
        assertThat(service.list(null, 2026, AUDIT)).extracting(WeeklyReport::getTeamId).containsExactly(2L);
        assertThat(service.list(null, 2026, SCOPED_ADMIN_OFF)).hasSize(1);   // müdür: kendi (kapalı) takımı
    }

    @Test
    @DisplayName("okuma/oluşturma: kapalı takım AUDIT/kullanıcı için 403 (WEEKLY_REPORTS_DISABLED); ADMIN rolü okur ve açar, kapsam korunur")
    void readAndCreateBlocked() {
        when(reportRepo.findById(6L)).thenReturn(Optional.of(report(6L, 7L)));
        when(reportRepo.findById(5L)).thenReturn(Optional.of(report(5L, 2L)));
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(reportRepo.findFirstByTeamIdOrderByReportYearDescWeekNoDesc(anyLong())).thenReturn(Optional.empty());

        assertThat(service.get(6L, ADMIN).getId()).isEqualTo(6L);                       // ADMIN: kapalı takımı okur
        assertThat(service.get(6L, SCOPED_ADMIN_OFF).getId()).isEqualTo(6L);            // müdür: kendi kapalı takımı
        assertThatThrownBy(() -> service.get(5L, SCOPED_ADMIN_OFF))                      // müdür: BAŞKA takım → kapsam dışı
                .isInstanceOf(SecurityException.class).hasMessageNotContaining("WEEKLY_REPORTS_DISABLED");
        assertThatThrownBy(() -> service.get(6L, AUDIT)).isInstanceOf(SecurityException.class).hasMessageContaining("WEEKLY_REPORTS_DISABLED");
        assertThatThrownBy(() -> service.get(6L, USER_OFF)).isInstanceOf(SecurityException.class);
        assertThat(service.get(5L, USER_ON).getId()).isEqualTo(5L);

        assertThat(service.create(7L, 2026, 37, ADMIN).getTeamId()).isEqualTo(7L);     // ADMIN: kapalı takıma açar
        assertThatThrownBy(() -> service.create(7L, 2026, 37, USER_OFF)).isInstanceOf(SecurityException.class).hasMessageContaining("WEEKLY_REPORTS_DISABLED");
        assertThat(service.create(2L, 2026, 37, USER_ON).getTeamId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("pano/şerit/hatırlatma durumu kapalı takımı saymaz")
    void boardsSkipDisabled() {
        when(reportRepo.findByReportYearOrderByTeamIdAscWeekNoDesc(anyInt())).thenReturn(List.of(report(6L, 7L)));
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> boardTeams = (List<java.util.Map<String, Object>>) service.completion(2026, ADMIN).get("teams");
        assertThat(boardTeams).extracting(m -> m.get("team_id")).containsExactly(2L);

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> stripTeams = (List<java.util.Map<String, Object>>) service.thisWeek(ADMIN).get("teams");
        assertThat(stripTeams).extracting(m -> m.get("team_id")).containsExactly(2L);
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> stripOff = (List<java.util.Map<String, Object>>) service.thisWeek(USER_OFF).get("teams");
        assertThat(stripOff).isEmpty();

        assertThat(service.reminderStatus(true)).containsEntry("opt_in_teams", 1);
    }

    @Test
    @DisplayName("anahtar: setFeatureEnabled takımı kaydeder, açıp kapatmak veriyi silmez")
    void setFeatureEnabled() {
        Team off = team(7L, "Kapali", false);
        when(teamRepo.findById(7L)).thenReturn(Optional.of(off));
        when(teamRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Team opened = service.setFeatureEnabled(7L, true);
        assertThat(opened.getWeeklyReportsEnabled()).isTrue();
        org.mockito.Mockito.verify(teamRepo).save(off);
        org.mockito.Mockito.verify(reportRepo, org.mockito.Mockito.never()).deleteById(anyLong());

        assertThat(service.setFeatureEnabled(7L, false).getWeeklyReportsEnabled()).isFalse();
    }
}
