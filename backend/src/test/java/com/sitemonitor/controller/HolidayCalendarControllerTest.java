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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tatil takvimi uçları (2026-10-31): okuma oturum açık herkese (form + zamanlama önizlemesi), yazma YALNIZ global yöneticiye
 * (kapsamlı müdür 403); doğrulama hatası satır anahtarıyla; denetim yalnız gerçekten değişince.
 */
@WebMvcTest(HolidayCalendarController.class)
class HolidayCalendarControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean HolidayCalendarService holidays;
    @MockitoBean AuditService auditService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        when(holidays.list()).thenReturn(List.of(Map.of("day", "2026-10-29", "name", "Cumhuriyet Bayramı")));
    }

    private static MockHttpSession session(String role, List<Long> viewTeams) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);
        if (viewTeams != null) s.setAttribute("viewTeamIds", viewTeams);
        return s;
    }

    private static final String BODY = "{\"days\":[{\"day\":\"2026-10-29\",\"name\":\"Cumhuriyet Bayramı\"}]}";

    @Test
    @DisplayName("GET: oturum açık her kullanıcı okur; can_edit yalnız global yöneticide")
    void readForEverySession() throws Exception {
        mvc.perform(get("/api/admin/holidays").session(session("USER", List.of(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days[0].day").value("2026-10-29"))
                .andExpect(jsonPath("$.data.can_edit").value(false));
        mvc.perform(get("/api/admin/holidays").session(session("ADMIN", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_edit").value(true));
    }

    @Test
    @DisplayName("PUT: kapsamlı müdür / USER 403; global yönetici kaydeder; değişiklik varsa denetlenir, yoksa denetim yok")
    void writeGlobalAdminOnly() throws Exception {
        mvc.perform(put("/api/admin/holidays").session(session("ADMIN", List.of(1L)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/holidays").session(session("USER", List.of(1L)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(holidays, never()).replaceAll(any(), any());

        when(holidays.replaceAll(any(), any())).thenReturn(new HolidayCalendarService.Change(1, 0, 0, 1));
        mvc.perform(put("/api/admin/holidays").session(session("ADMIN", null))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days.length()").value(1));
        verify(auditService, times(1)).recordAction(eq("HOLIDAY_CALENDAR_SAVE"), any(jakarta.servlet.http.HttpSession.class),
                eq("HOLIDAY_CALENDAR"), eq("holidays"), anyString(), isNull());

        when(holidays.replaceAll(any(), any())).thenReturn(new HolidayCalendarService.Change(0, 0, 0, 1));
        mvc.perform(put("/api/admin/holidays").session(session("ADMIN", null))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        verify(auditService, times(1)).recordAction(eq("HOLIDAY_CALENDAR_SAVE"), any(jakarta.servlet.http.HttpSession.class),
                anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("PUT: geçersiz gün satır anahtarıyla 400 VALIDATION_FAILED; hiçbir şey yazılmaz")
    void invalidRowsAreFieldErrors() throws Exception {
        mvc.perform(put("/api/admin/holidays").session(session("ADMIN", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\":[{\"day\":\"29.10.2026\",\"name\":\"Cumhuriyet\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields['days[0].day']").exists());
        verify(holidays, never()).replaceAll(any(), any());
    }
}
