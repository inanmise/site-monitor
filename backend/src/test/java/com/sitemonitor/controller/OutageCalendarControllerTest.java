package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.OutageCalendar;
import com.sitemonitor.service.OutageCalendarService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.noc.NocCallLogService;
import com.sitemonitor.service.retention.RetentionPolicy;
import com.sitemonitor.service.retention.RetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/admin/alerts/outage-calendar} — kapı ({@code alerts.read/view}), takım görünürlüğü (listenin kuralı:
 * global görüntüleyici / 7-24 tümü, diğerleri görüş + paylaşım; görünmeyen ya da bilinmeyen takım 404), doğrulama (400
 * {@code team} / {@code month}: eksik, biçim, gelecek, 24 aydan eski), 503 okuma hatası ve yanıt biçimi.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutageCalendarControllerTest {

    @Mock OutageCalendarService calendarService;
    @Mock TeamRepository teamRepo;
    @Mock PermissionService permissionService;
    @Mock NocCallLogService nocCallLog;

    private MockMvc mvc;
    private MockHttpSession admin;
    /** Şimdi: 2026-11-19 12:00 İstanbul. */
    private static final long NOW = LocalDateTime.parse("2026-11-19T09:00").toInstant(ZoneOffset.UTC).toEpochMilli();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        OutageCalendarController controller = new OutageCalendarController(calendarService, teamRepo, permissionService);
        ReflectionTestUtils.setField(controller, "nocCallLog", nocCallLog);
        controller.clock = () -> NOW;
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(mock(ObjectProvider.class)))
                .build();
        admin = new MockHttpSession();
        admin.setAttribute("systemRole", "ADMIN");   // viewTeamIds yok → global yönetici
        Team t = new Team();
        t.setId(5L);
        t.setName("Takim A");
        when(teamRepo.findById(5L)).thenReturn(Optional.of(t));
        when(teamRepo.findById(9L)).thenReturn(Optional.empty());
        List<OutageCalendar.Day> days = new ArrayList<>();
        for (int d = 1; d <= 30; d++) {
            LocalDate day = LocalDate.of(2026, 11, d);
            days.add(d == 3 ? new OutageCalendar.Day(day, 135, 2, "CRITICAL")
                    : d > 19 ? new OutageCalendar.Day(day, null, 0, null)
                    : new OutageCalendar.Day(day, 0, 0, null));
        }
        when(calendarService.forTeam(any(), anyLong(), anyLong(), anyLong())).thenReturn(new OutageCalendarService.TeamMonth(
                new OutageCalendar.Result(YearMonth.of(2026, 11), days, 135), false));
    }

    private MockHttpSession scoped(List<Long> view, List<Long> grantView) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", view);
        if (grantView != null) s.setAttribute(SessionScope.ATTR_GRANT_VIEW, grantView);
        return s;
    }

    @Test
    @DisplayName("mutlu yol: ay, takım adı, günler (gelecek null), toplam, truncated; hesap şimdiki anla yapılır")
    void happyPath() throws Exception {
        mvc.perform(get("/api/admin/alerts/outage-calendar").param("team", "5").param("month", "2026-11").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.month").value("2026-11"))
                .andExpect(jsonPath("$.data.team_id").value(5))
                .andExpect(jsonPath("$.data.team_name").value("Takim A"))
                .andExpect(jsonPath("$.data.days.length()").value(30))
                .andExpect(jsonPath("$.data.days[2].day").value("2026-11-03"))
                .andExpect(jsonPath("$.data.days[2].outage_minutes").value(135))
                .andExpect(jsonPath("$.data.days[2].alarms").value(2))
                .andExpect(jsonPath("$.data.days[2].worst_level").value("CRITICAL"))
                .andExpect(jsonPath("$.data.days[25].outage_minutes").doesNotExist())
                .andExpect(jsonPath("$.data.total_minutes").value(135))
                .andExpect(jsonPath("$.data.truncated").value(false));
        verify(calendarService).forTeam(YearMonth.of(2026, 11), 5L, NOW, Long.MIN_VALUE);   // saklama servisi yok → sınır yok
    }

    @Test
    @DisplayName("izin yok → 403, servis çağrılmaz")
    void noPermission() throws Exception {
        doThrow(new SecurityException("Bu işlem için yetkiniz yok: alerts.read/view"))
                .when(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq("alerts.read"), eq("view"));
        mvc.perform(get("/api/admin/alerts/outage-calendar").param("team", "5").param("month", "2026-11").session(admin))
                .andExpect(status().isForbidden());
        verify(calendarService, never()).forTeam(any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("görünmeyen takım (kapsam dışı) ve bilinmeyen takım → 404 TEAM_NOT_FOUND")
    void invisibleOrUnknownTeam() throws Exception {
        mvc.perform(get("/api/admin/alerts/outage-calendar").param("team", "5").param("month", "2026-11")
                        .session(scoped(List.of(1L), null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
        mvc.perform(get("/api/admin/alerts/outage-calendar").param("team", "9").param("month", "2026-11").session(admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
        verify(calendarService, never()).forTeam(any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("görüş kapsamı, erişim paylaşımı ve 7/24 konsolu takımı görür")
    void visibleTeams() throws Exception {
        mvc.perform(get("/api/admin/alerts/outage-calendar").param("team", "5").param("month", "2026-11")
                        .session(scoped(List.of(5L), null)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/alerts/outage-calendar").param("team", "5").param("month", "2026-11")
                        .session(scoped(List.of(1L), List.of(5L))))
                .andExpect(status().isOk());
        MockHttpSession noc = scoped(List.of(1L), null);
        when(nocCallLog.seesAllAlerts(noc)).thenReturn(true);
        mvc.perform(get("/api/admin/alerts/outage-calendar").param("team", "5").param("month", "2026-11").session(noc))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("doğrulama: team eksik / sayı değil → 400 team; month eksik / biçim / gelecek / 24 aydan eski → 400 month")
    void validation() throws Exception {
        mvc.perform(get("/api/admin/alerts/outage-calendar").param("month", "2026-11").session(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("team"));
        mvc.perform(get("/api/admin/alerts/outage-calendar").param("team", "abc").param("month", "2026-11").session(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("team"));
        for (String bad : new String[] { "", "2026-13", "2026-1", "26-11", "2026-11-01", "kasım" }) {
            mvc.perform(get("/api/admin/alerts/outage-calendar").param("team", "5").param("month", bad).session(admin))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.field").value("month"));
        }
        mvc.perform(get("/api/admin/alerts/outage-calendar").param("team", "5").param("month", "2026-12").session(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("month"));
        mvc.perform(get("/api/admin/alerts/outage-calendar").param("team", "5").param("month", "2024-10").session(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("month"));
        // tam 24 ay geri izinli
        mvc.perform(get("/api/admin/alerts/outage-calendar").param("team", "5").param("month", "2024-11").session(admin))
                .andExpect(status().isOk());
        verify(calendarService, never()).forTeam(eq(YearMonth.of(2026, 12)), anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("ay sınırı İstanbul takvimine göre: UTC 30 Kasım 21:30 = İstanbul 1 Aralık → Aralık geçerli")
    void currentMonthIsIstanbul() {
        long utcNov30Late = LocalDateTime.parse("2026-11-30T21:30").toInstant(ZoneOffset.UTC).toEpochMilli();
        org.assertj.core.api.Assertions.assertThat(OutageCalendarController.parseMonth("2026-12", utcNov30Late))
                .isEqualTo(YearMonth.of(2026, 12));
    }

    @Test
    @DisplayName("okuma hatası → 503 DB_UNAVAILABLE (asla 'kesinti yok')")
    void unavailable() throws Exception {
        when(calendarService.forTeam(any(), anyLong(), anyLong(), anyLong()))
                .thenThrow(new OutageCalendarService.UnavailableException(new RuntimeException("db")));
        mvc.perform(get("/api/admin/alerts/outage-calendar").param("team", "5").param("month", "2026-11").session(admin))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DB_UNAVAILABLE"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("saklama süresi: kapsanmayan günler no_data, covered_from / oldest_month / retention_days döner (inceleme 2026-11-19)")
    void retentionCoverage() throws Exception {
        OutageCalendarController controller = new OutageCalendarController(calendarService, teamRepo, permissionService);
        ReflectionTestUtils.setField(controller, "nocCallLog", nocCallLog);
        RetentionService retention = mock(RetentionService.class);
        when(retention.effectiveDays(any(RetentionPolicy.class))).thenReturn(365);
        ReflectionTestUtils.setField(controller, "retentionService", retention);
        controller.clock = () -> NOW;
        MockMvc m = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(mock(ObjectProvider.class)))
                .build();
        List<OutageCalendar.Day> days = new ArrayList<>();
        days.add(new OutageCalendar.Day(LocalDate.of(2025, 11, 19), null, 0, null, true));
        days.add(new OutageCalendar.Day(LocalDate.of(2025, 11, 20), 0, 0, null));
        when(calendarService.forTeam(any(), anyLong(), anyLong(), anyLong())).thenReturn(new OutageCalendarService.TeamMonth(
                new OutageCalendar.Result(YearMonth.of(2025, 11), days, 0), false));
        long coverFrom = NOW - 365L * 86_400_000L;   // 2025-11-19 12:00 İstanbul
        m.perform(get("/api/admin/alerts/outage-calendar").param("team", "5").param("month", "2025-11").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days[0].no_data").value(true))
                .andExpect(jsonPath("$.data.days[0].outage_minutes").doesNotExist())
                .andExpect(jsonPath("$.data.days[1].no_data").doesNotExist())
                .andExpect(jsonPath("$.data.days[1].outage_minutes").value(0))
                .andExpect(jsonPath("$.data.retention_days").value(365))
                .andExpect(jsonPath("$.data.covered_from").value("2025-11-20"))
                .andExpect(jsonPath("$.data.oldest_month").value("2025-11"));
        verify(calendarService).forTeam(YearMonth.of(2025, 11), 5L, NOW, coverFrom);
    }

    @Test
    @DisplayName("coveredFrom: sınır gün başına denk gelirse o gün, değilse ertesi gün")
    void coveredFromRule() {
        long dayStart = OutageCalendar.dayStartMs(LocalDate.of(2026, 1, 10));
        org.assertj.core.api.Assertions.assertThat(OutageCalendarController.coveredFrom(dayStart)).isEqualTo(LocalDate.of(2026, 1, 10));
        org.assertj.core.api.Assertions.assertThat(OutageCalendarController.coveredFrom(dayStart + 1)).isEqualTo(LocalDate.of(2026, 1, 11));
        org.assertj.core.api.Assertions.assertThat(OutageCalendarController.coveredFrom(dayStart - 1)).isEqualTo(LocalDate.of(2026, 1, 10));
    }
}
