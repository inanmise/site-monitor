package com.sitemonitor.controller;

import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.dbquery.HolidayCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** r177 (H-7): takvim kapsamı yanıtta; sabit tarihli resmî tatilleri ekleme ucu yalnız global yöneticide. */
@WebMvcTest(HolidayCalendarController.class)
class HolidayCalendarAddFixedControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean HolidayCalendarService holidays;
    @MockitoBean AuditService auditService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.SchedulerService scheduler;

    @BeforeEach
    void setUp() {
        when(holidays.list()).thenReturn(List.of(Map.of("day", "2026-11-15", "name", "Kısa takvim")));
    }

    private static MockHttpSession session(String role, List<Long> viewTeams) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("displayName", "Global Yönetici");
        s.setAttribute("systemRole", role);
        if (viewTeams != null) s.setAttribute("viewTeamIds", viewTeams);
        return s;
    }

    @Test
    @DisplayName("GET: coverage_until + ends_soon (son gün 60 gün içinde)")
    void coverageInView() throws Exception {
        mvc.perform(get("/api/admin/holidays").session(session("USER", List.of(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverage_until").value("2026-11-15"))
                .andExpect(jsonPath("$.data.ends_soon").value(true))
                .andExpect(jsonPath("$.data.ends_soon_days").value(60));
    }

    @Test
    @DisplayName("add-fixed: kapsamlı müdür 403; yıl geçersizse 400 alan 'year'; eklenince denetim + plan yenileme, eklenmezse ikisi de yok")
    void addFixed() throws Exception {
        mvc.perform(post("/api/admin/holidays/add-fixed").param("year", "2027").session(session("ADMIN", List.of(1L))))
                .andExpect(status().isForbidden());
        verify(holidays, never()).addFixed(anyInt(), any());
        mvc.perform(post("/api/admin/holidays/add-fixed").param("year", "20x7").session(session("ADMIN", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.year").exists());
        mvc.perform(post("/api/admin/holidays/add-fixed").param("year", "1999").session(session("ADMIN", null)))
                .andExpect(status().isBadRequest());

        when(holidays.addFixed(2027, "Global Yönetici")).thenReturn(7);
        mvc.perform(post("/api/admin/holidays/add-fixed").param("year", "2027").session(session("ADMIN", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.added").value(7))
                .andExpect(jsonPath("$.data.year").value(2027))
                .andExpect(jsonPath("$.data.note").exists())
                .andExpect(jsonPath("$.data.days.length()").value(1));
        verify(auditService, times(1)).recordAction(eq("HOLIDAY_CALENDAR_SAVE"), any(jakarta.servlet.http.HttpSession.class),
                eq("HOLIDAY_CALENDAR"), eq("holidays"), contains("add_fixed"), isNull());
        verify(scheduler, times(1)).dbQueryCalendarChanged();

        when(holidays.addFixed(2028, "Global Yönetici")).thenReturn(0);
        mvc.perform(post("/api/admin/holidays/add-fixed").param("year", "2028").session(session("ADMIN", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.added").value(0));
        verify(auditService, times(1)).recordAction(anyString(), any(jakarta.servlet.http.HttpSession.class), anyString(), anyString(),
                anyString(), isNull());
        verify(scheduler, times(1)).dbQueryCalendarChanged();
    }
}
