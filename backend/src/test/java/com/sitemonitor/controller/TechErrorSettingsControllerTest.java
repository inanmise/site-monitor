package com.sitemonitor.controller;

import com.sitemonitor.model.Team;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.techerror.TechErrorApprovalService;
import com.sitemonitor.service.techerror.TechErrorReportService;
import com.sitemonitor.service.techerror.TechErrorReportSettings;
import com.sitemonitor.service.techerror.TechErrorVisibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ayarlar → Teknik hata raporu uçları (12. sürüm, 2026-10-17 — hatırlatmalar + posta sınırları): GET etkin değerleri ve
 * aralıkları döner; PUT YALNIZ gönderilen alanları yazar, aralık / alanlar-arası hata 400 + alan adı, boş gövde hiçbir şey
 * yazmaz ve denetlenmez; denetim kaydı değişen anahtarları taşır; kapsamlı müdür okur (salt okunur), yazamaz (403).
 */
@WebMvcTest(TechErrorSettingsController.class)
class TechErrorSettingsControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean PermissionService permissions;

    @MockitoBean AppSettingsService settings;
    @MockitoBean TeamRepository teams;
    @MockitoBean TechErrorReportService service;
    @MockitoBean TechErrorApprovalService approvals;
    @MockitoBean TechErrorVisibilityService visibility;
    @MockitoBean AuditService auditService;

    private final Map<String, String> stored = new HashMap<>();

    @BeforeEach
    void setUp() {
        stored.clear();
        when(service.channels(any())).thenReturn(List.of());
        when(service.defaultPeriod()).thenReturn(YearMonth.of(2026, 9));
        when(service.now()).thenReturn(Instant.parse("2026-10-13T09:00:00Z"));
        when(service.periodRows(any(), any())).thenReturn(Map.of());
        when(teams.findAll()).thenReturn(List.of(team(1), team(2)));
        when(teams.findAllById(any())).thenAnswer(inv -> {
            List<Team> out = new ArrayList<>();
            for (Object o : (Iterable<?>) inv.getArgument(0)) if ((Long) o < 100) out.add(team((Long) o));
            return out;
        });
        when(settings.getString(anyString(), any())).thenAnswer(inv -> stored.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> {
            String v = stored.get((String) inv.getArgument(0));
            return v == null ? inv.getArgument(1) : Integer.parseInt(v);
        });
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
            String v = stored.get((String) inv.getArgument(0));
            return v == null ? inv.getArgument(1) : Boolean.parseBoolean(v);
        });
    }

    private static Team team(long id) {
        Team t = new Team();
        t.setId(id);
        t.setName("Takım " + id);
        t.setActive(true);
        return t;
    }

    private static MockHttpSession globalAdmin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("userId", 1L);
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    private static MockHttpSession scopedAdmin() {
        MockHttpSession s = globalAdmin();
        s.setAttribute("username", "mudur");
        s.setAttribute("userId", 2L);
        s.setAttribute("viewTeamIds", List.of(1L));
        s.setAttribute("manageTeamIds", List.of(1L));
        s.setAttribute("memberTeamIds", List.of(1L));
        return s;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> savedValues() {
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(settings).save(body.capture(), eq("admin"));
        return (Map<String, Object>) body.getValue().get("values");
    }

    @Test
    @DisplayName("GET: hatırlatma bloğu etkin değerler (varsayılanlar: gün 5, tekrar 3, onay 3, posta 15 / 6 MB, push açık, takım yok) + aralıklar; kapsamlı müdür read_only")
    void getReturnsReminderBlock() throws Exception {
        mvc.perform(get("/api/admin/tech-error-reports/settings").session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read_only").value(false))
                .andExpect(jsonPath("$.data.reminders.team_ids.length()").value(0))
                .andExpect(jsonPath("$.data.reminders.reminder_day").value(5))
                .andExpect(jsonPath("$.data.reminders.reminder_repeat_days").value(3))
                .andExpect(jsonPath("$.data.reminders.approval_reminder_days").value(3))
                .andExpect(jsonPath("$.data.reminders.max_mail_mb").value(15))
                .andExpect(jsonPath("$.data.reminders.mail_inline_mb").value(6))
                .andExpect(jsonPath("$.data.reminders.reminder_push").value(true))
                .andExpect(jsonPath("$.data.reminders.limits.reminder_day.min").value(1))
                .andExpect(jsonPath("$.data.reminders.limits.reminder_day.max").value(28))
                .andExpect(jsonPath("$.data.reminders.limits.max_mail_mb.max").value(50));
        stored.put(TechErrorReportSettings.KEY_REMINDER_TEAM_IDS, "2,1");
        stored.put(TechErrorReportSettings.KEY_REMINDER_DAY, "10");
        stored.put(TechErrorReportSettings.KEY_REMINDER_PUSH, "false");
        mvc.perform(get("/api/admin/tech-error-reports/settings").session(scopedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read_only").value(true))
                .andExpect(jsonPath("$.data.reminders.team_ids[0]").value(2))
                .andExpect(jsonPath("$.data.reminders.team_ids[1]").value(1))
                .andExpect(jsonPath("$.data.reminders.reminder_day").value(10))
                .andExpect(jsonPath("$.data.reminders.reminder_push").value(false));
    }

    @Test
    @DisplayName("PUT yalnız gönderilen alanları yazar (görünürlük dokunulmaz); denetim değişen anahtarları taşır; yanıt yeni değerler")
    void putWritesOnlySentKeys() throws Exception {
        mvc.perform(put("/api/admin/tech-error-reports/settings").session(globalAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reminder_team_ids\":[1],\"reminder_day\":7,\"reminder_push\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        Map<String, Object> values = savedValues();
        assertThat(values).containsOnlyKeys(TechErrorReportSettings.KEY_REMINDER_TEAM_IDS, TechErrorReportSettings.KEY_REMINDER_DAY,
                TechErrorReportSettings.KEY_REMINDER_PUSH);
        assertThat(values.get(TechErrorReportSettings.KEY_REMINDER_TEAM_IDS)).isEqualTo("1");
        assertThat(values.get(TechErrorReportSettings.KEY_REMINDER_DAY)).isEqualTo("7");
        assertThat(values.get(TechErrorReportSettings.KEY_REMINDER_PUSH)).isEqualTo("false");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("TECH_ERROR_SETTINGS_SAVE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("SETTINGS"), eq("tech-error-report"), detail.capture());
        assertThat(detail.getValue()).contains("reminder_day").contains(TechErrorReportSettings.KEY_REMINDER_PUSH).doesNotContain("\"team_ids\"");
    }

    @Test
    @DisplayName("eski istemci gövdesi { team_ids } aynen çalışır (yalnız görünürlük yazılır)")
    void legacyVisibilityBody() throws Exception {
        mvc.perform(put("/api/admin/tech-error-reports/settings").session(globalAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"team_ids\":[2]}"))
                .andExpect(status().isOk());
        assertThat(savedValues()).containsOnlyKeys(TechErrorReportSettings.KEY_TEAM_IDS).containsEntry(TechErrorReportSettings.KEY_TEAM_IDS, "2");
    }

    @Test
    @DisplayName("alan hataları 400 VALIDATION_FAILED + field: aralık dışı gün, kesirli sayı, bilinmeyen takım, push metni; alanlar-arası gövde ≥ posta sınırı (etkin değerle)")
    void validation() throws Exception {
        String[][] cases = {
                {"{\"reminder_day\":29}", "reminder_day"},
                {"{\"reminder_repeat_days\":2.5}", "reminder_repeat_days"},
                {"{\"approval_reminder_days\":0}", "approval_reminder_days"},
                {"{\"max_mail_mb\":4}", "max_mail_mb"},
                {"{\"reminder_team_ids\":[999]}", "reminder_team_ids"},
                {"{\"reminder_team_ids\":\"1\"}", "reminder_team_ids"},
                {"{\"reminder_push\":\"evet\"}", "reminder_push"},
                {"{\"mail_inline_mb\":15}", "mail_inline_mb"},                       // etkin posta sınırı 15
                {"{\"max_mail_mb\":6}", "max_mail_mb"},                              // etkin gövde bütçesi 6
                {"{\"max_mail_mb\":10,\"mail_inline_mb\":12}", "mail_inline_mb"},
        };
        for (String[] c : cases) {
            mvc.perform(put("/api/admin/tech-error-reports/settings").session(globalAdmin()).contentType(MediaType.APPLICATION_JSON).content(c[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.field").value(c[1]));
        }
        verify(settings, never()).save(any(), anyString());
        // İkisi birlikte uyumlu: kabul
        mvc.perform(put("/api/admin/tech-error-reports/settings").session(globalAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"max_mail_mb\":25,\"mail_inline_mb\":12}"))
                .andExpect(status().isOk());
        assertThat(savedValues()).containsEntry(TechErrorReportSettings.KEY_MAX_MAIL_MB, "25").containsEntry(TechErrorReportSettings.KEY_MAIL_INLINE_MB, "12");
    }

    @Test
    @DisplayName("boş gövde: hiçbir şey yazılmaz, denetim yok, 200 (değişiklik yok)")
    void emptyBodyIsNoOp() throws Exception {
        mvc.perform(put("/api/admin/tech-error-reports/settings").session(globalAdmin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reminders.reminder_day").value(5));
        verify(settings, never()).save(any(), anyString());
        verify(auditService, never()).recordAction(anyString(), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("kapsamlı müdür hatırlatma / posta sınırı anahtarlarını yazamaz (403), hiçbir şey yazılmaz")
    void scopedAdminCannotWrite() throws Exception {
        for (String body : List.of("{\"reminder_day\":7}", "{\"max_mail_mb\":20}", "{\"reminder_team_ids\":[1]}")) {
            mvc.perform(put("/api/admin/tech-error-reports/settings").session(scopedAdmin()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
        verify(settings, never()).save(any(), anyString());
    }
}
