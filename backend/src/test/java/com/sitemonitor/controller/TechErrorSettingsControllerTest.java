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
 * Ayarlar → Teknik hata raporu uçları. {@code /settings} 11. sürümle BİREBİR (yalnız görünürlük; gövdede {@code team_ids}
 * yoksa boş = herkes; GET'te hatırlatma bloğu YOK). Hatırlatmalar + posta sınırları (12. sürüm) AYRI uçta
 * ({@code /settings/reminders}, inceleme 2026-10-18 — yuvarlanan dağıtımda eski pod bu yolu tanımaz, 404 / 405 döner ve
 * görünürlüğe dokunmaz): GET etkin değerleri ve aralıkları döner; PUT YALNIZ gönderilen alanları yazar (görünürlük
 * anahtarı bu uçta yok sayılır), aralık / alanlar-arası hata 400 + alan adı, boş gövde hiçbir şey yazmaz ve denetlenmez;
 * denetim kaydı değişen anahtarları taşır; kapsamlı müdür okur (salt okunur), yazamaz (403).
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

    private static final String MAIN = "/api/admin/tech-error-reports/settings";
    private static final String REM = "/api/admin/tech-error-reports/settings/reminders";

    // ── /settings: 11. sürüm sözleşmesi ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /settings 11. sürümle aynı: görünürlük + kanal özeti, hatırlatma bloğu YOK; kapsamlı müdür read_only")
    void mainGetHasNoReminderBlock() throws Exception {
        mvc.perform(get(MAIN).session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read_only").value(false))
                .andExpect(jsonPath("$.data.team_ids.length()").value(0))
                .andExpect(jsonPath("$.data.period.month").value(9))
                .andExpect(jsonPath("$.data.reminders").doesNotExist());
        mvc.perform(get(MAIN).session(scopedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read_only").value(true));
    }

    @Test
    @DisplayName("PUT /settings 11. sürümle aynı: yalnız görünürlük yazılır; gövdede team_ids yoksa boş liste (= herkes) — hatırlatma anahtarları bu uçta YAZILMAZ")
    void mainPutIsTheRelease11Contract() throws Exception {
        mvc.perform(put(MAIN).session(globalAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"team_ids\":[2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_ids[0]").value(2))
                .andExpect(jsonPath("$.data.reminders").doesNotExist());
        assertThat(savedValues()).containsOnlyKeys(TechErrorReportSettings.KEY_TEAM_IDS).containsEntry(TechErrorReportSettings.KEY_TEAM_IDS, "2");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("TECH_ERROR_SETTINGS_SAVE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("SETTINGS"), eq("tech-error-report"), detail.capture());
        assertThat(detail.getValue()).contains("team_ids").contains("everyone");
    }

    @Test
    @DisplayName("PUT /settings hatırlatma gövdesi: 11. sürüm gibi davranır (eksik team_ids = boş) — yeni kart bu uca hatırlatma GÖNDERMEZ; hatırlatma anahtarı yazılmaz")
    void mainPutIgnoresReminderKeys() throws Exception {
        mvc.perform(put(MAIN).session(globalAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"reminder_day\":7}"))
                .andExpect(status().isOk());
        assertThat(savedValues()).containsOnlyKeys(TechErrorReportSettings.KEY_TEAM_IDS).containsEntry(TechErrorReportSettings.KEY_TEAM_IDS, "");
    }

    @Test
    @DisplayName("PUT /settings: geçersiz / bilinmeyen takım 400 + team_ids; kapsamlı müdür 403")
    void mainPutValidation() throws Exception {
        for (String body : List.of("{\"team_ids\":[999]}", "{\"team_ids\":\"1\"}", "{\"team_ids\":[0]}")) {
            mvc.perform(put(MAIN).session(globalAdmin()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.field").value("team_ids"));
        }
        mvc.perform(put(MAIN).session(scopedAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"team_ids\":[1]}"))
                .andExpect(status().isForbidden());
        verify(settings, never()).save(any(), anyString());
    }

    // ── /settings/reminders ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /settings/reminders: etkin değerler (varsayılanlar: gün 5, tekrar 3, onay 3, posta 15 / 6 MB, push açık, takım yok) + aralıklar; kapsamlı müdür read_only; yönetici olmayan 403")
    void getReminders() throws Exception {
        mvc.perform(get(REM).session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read_only").value(false))
                .andExpect(jsonPath("$.data.team_ids.length()").value(0))
                .andExpect(jsonPath("$.data.reminder_day").value(5))
                .andExpect(jsonPath("$.data.reminder_repeat_days").value(3))
                .andExpect(jsonPath("$.data.approval_reminder_days").value(3))
                .andExpect(jsonPath("$.data.max_mail_mb").value(15))
                .andExpect(jsonPath("$.data.mail_inline_mb").value(6))
                .andExpect(jsonPath("$.data.reminder_push").value(true))
                .andExpect(jsonPath("$.data.limits.reminder_day.min").value(1))
                .andExpect(jsonPath("$.data.limits.reminder_day.max").value(28))
                .andExpect(jsonPath("$.data.limits.max_mail_mb.max").value(50));
        stored.put(TechErrorReportSettings.KEY_REMINDER_TEAM_IDS, "2,1");
        stored.put(TechErrorReportSettings.KEY_REMINDER_DAY, "10");
        stored.put(TechErrorReportSettings.KEY_REMINDER_PUSH, "false");
        mvc.perform(get(REM).session(scopedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read_only").value(true))
                .andExpect(jsonPath("$.data.team_ids[0]").value(2))
                .andExpect(jsonPath("$.data.team_ids[1]").value(1))
                .andExpect(jsonPath("$.data.reminder_day").value(10))
                .andExpect(jsonPath("$.data.reminder_push").value(false));
        MockHttpSession user = globalAdmin();
        user.setAttribute("systemRole", "USER");
        mvc.perform(get(REM).session(user)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("inceleme 2026-10-18: aralık dışı ortam değeri (60 MB) varsayılana düşer ve yanıt bunu söyler (out_of_range: kaynak, değer, varsayılan); kayıtlı bozuk değer 'stored'; aralık içi alan listede yok")
    void getRemindersReportsOutOfRangeValues() throws Exception {
        mvc.perform(get(REM).session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.out_of_range").isMap())
                .andExpect(jsonPath("$.data.out_of_range.max_mail_mb").doesNotExist());
        stored.put(TechErrorReportSettings.KEY_MAX_MAIL_MB, "60");                 // getInt → ortam / özellik değeri (override yok)
        when(settings.getOverride(TechErrorReportSettings.KEY_MAIL_INLINE_MB)).thenReturn("abc");
        mvc.perform(get(REM).session(scopedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.max_mail_mb").value(15))
                .andExpect(jsonPath("$.data.out_of_range.max_mail_mb.source").value("env"))
                .andExpect(jsonPath("$.data.out_of_range.max_mail_mb.value").value("60"))
                .andExpect(jsonPath("$.data.out_of_range.max_mail_mb.default").value(15))
                .andExpect(jsonPath("$.data.out_of_range.mail_inline_mb.source").value("stored"))
                .andExpect(jsonPath("$.data.out_of_range.mail_inline_mb.value").value("abc"))
                .andExpect(jsonPath("$.data.out_of_range.reminder_day").doesNotExist());
    }

    @Test
    @DisplayName("PUT /settings/reminders yalnız gönderilen alanları yazar; görünürlük (team_ids) bu uçta YOK SAYILIR; denetim değişen anahtarları taşır; yanıt yeni değerler")
    void putWritesOnlySentKeys() throws Exception {
        mvc.perform(put(REM).session(globalAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reminder_team_ids\":[1],\"reminder_day\":7,\"reminder_push\":false,\"team_ids\":[2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.read_only").value(false))
                .andExpect(jsonPath("$.data.limits.reminder_day.max").value(28));
        Map<String, Object> values = savedValues();
        assertThat(values).containsOnlyKeys(TechErrorReportSettings.KEY_REMINDER_TEAM_IDS, TechErrorReportSettings.KEY_REMINDER_DAY,
                TechErrorReportSettings.KEY_REMINDER_PUSH);
        assertThat(values.get(TechErrorReportSettings.KEY_REMINDER_TEAM_IDS)).isEqualTo("1");
        assertThat(values.get(TechErrorReportSettings.KEY_REMINDER_DAY)).isEqualTo("7");
        assertThat(values.get(TechErrorReportSettings.KEY_REMINDER_PUSH)).isEqualTo("false");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("TECH_ERROR_SETTINGS_SAVE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("SETTINGS"), eq("tech-error-report"), detail.capture());
        assertThat(detail.getValue()).contains("reminder_day").contains(TechErrorReportSettings.KEY_REMINDER_PUSH)
                .doesNotContain("\"team_ids\"").doesNotContain(TechErrorReportSettings.KEY_TEAM_IDS);
    }

    @Test
    @DisplayName("PUT /settings/reminders alan hataları 400 VALIDATION_FAILED + field: aralık dışı gün, kesirli sayı, bilinmeyen takım, push metni; alanlar-arası gövde ≥ posta sınırı (etkin değerle)")
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
            mvc.perform(put(REM).session(globalAdmin()).contentType(MediaType.APPLICATION_JSON).content(c[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.field").value(c[1]));
        }
        verify(settings, never()).save(any(), anyString());
        // İkisi birlikte uyumlu: kabul
        mvc.perform(put(REM).session(globalAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"max_mail_mb\":25,\"mail_inline_mb\":12}"))
                .andExpect(status().isOk());
        assertThat(savedValues()).containsEntry(TechErrorReportSettings.KEY_MAX_MAIL_MB, "25").containsEntry(TechErrorReportSettings.KEY_MAIL_INLINE_MB, "12");
    }

    @Test
    @DisplayName("PUT /settings/reminders boş gövde (ve gövdesiz, ve yalnız team_ids): hiçbir şey yazılmaz, denetim yok, 200")
    void emptyBodyIsNoOp() throws Exception {
        mvc.perform(put(REM).session(globalAdmin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reminder_day").value(5));
        mvc.perform(put(REM).session(globalAdmin()).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mvc.perform(put(REM).session(globalAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"team_ids\":[1]}"))
                .andExpect(status().isOk());
        verify(settings, never()).save(any(), anyString());
        verify(auditService, never()).recordAction(anyString(), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("kapsamlı müdür hatırlatma / posta sınırı anahtarlarını yazamaz (403), hiçbir şey yazılmaz")
    void scopedAdminCannotWrite() throws Exception {
        for (String body : List.of("{\"reminder_day\":7}", "{\"max_mail_mb\":20}", "{\"reminder_team_ids\":[1]}", "{}")) {
            mvc.perform(put(REM).session(scopedAdmin()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
        verify(settings, never()).save(any(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("inceleme 2026-10-18: ayarda ZATEN kayıtlı ama silinmiş takım (999) kaydı engellemez — sessizce düşer; YENİ eklenen bilinmeyen takım yine 400; görünürlükte yalnız silinmiş takımlardan oluşan kısıt kendiliğinden 'herkes'e dönmez")
    void deletedStoredTeamIsDroppedSilently() throws Exception {
        stored.put(TechErrorReportSettings.KEY_REMINDER_TEAM_IDS, "1,999");
        mvc.perform(put(REM).session(globalAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"reminder_team_ids\":[1,999,2]}"))
                .andExpect(status().isOk());
        stored.put(TechErrorReportSettings.KEY_REMINDER_TEAM_IDS, "1");
        mvc.perform(put(REM).session(globalAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"reminder_team_ids\":[1,998]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("reminder_team_ids"));
        stored.put(TechErrorReportSettings.KEY_TEAM_IDS, "2,999");
        mvc.perform(put(MAIN).session(globalAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"team_ids\":[2,999]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team_ids.length()").value(1))
                .andExpect(jsonPath("$.data.team_ids[0]").value(2));
        stored.put(TechErrorReportSettings.KEY_TEAM_IDS, "999");
        mvc.perform(put(MAIN).session(globalAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"team_ids\":[999]}"))
                .andExpect(status().isOk());
        mvc.perform(put(MAIN).session(globalAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"team_ids\":[]}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(settings, org.mockito.Mockito.times(4)).save(body.capture(), eq("admin"));
        List<Map<String, Object>> saves = body.getAllValues();
        assertThat((Map<String, Object>) saves.get(0).get("values")).containsEntry(TechErrorReportSettings.KEY_REMINDER_TEAM_IDS, "1,2");
        assertThat((Map<String, Object>) saves.get(1).get("values")).containsEntry(TechErrorReportSettings.KEY_TEAM_IDS, "2");
        // Yalnız silinmiş takımlı kısıt gevşemez (boş = herkes olurdu); yönetici çipi kaldırınca açılır
        assertThat((Map<String, Object>) saves.get(2).get("values")).containsEntry(TechErrorReportSettings.KEY_TEAM_IDS, "999");
        assertThat((Map<String, Object>) saves.get(3).get("values")).containsEntry(TechErrorReportSettings.KEY_TEAM_IDS, "");
    }
}
