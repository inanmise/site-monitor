package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.service.AuditEventCatalog;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.MyPushHistoryService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kişinin KENDİ push yüzeyi (2026-10-04): tercihler, susturma, kendine test, geçmiş. Kimlik YALNIZ oturumdan; doğrulama
 * hatası 400 + {@code field}; test 10 dakikada 3 (429); her durum değişikliği denetlenir.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MyPushControllerTest {

    @Mock UserService userService;
    @Mock UserPushService userPushService;
    @Mock MyPushHistoryService historyService;
    @Mock AuditService auditService;

    private MyPushController controller;
    private MockMvc mvc;
    private AppUser me;
    /** 2026-10-04 11:00 UTC = 14:00 İstanbul. */
    private static final Instant NOW = Instant.parse("2026-10-04T11:00:00Z");

    @BeforeEach
    void setUp() {
        controller = new MyPushController(userService, userPushService, historyService, auditService);
        controller.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        me = new AppUser();
        me.setId(5L);
        me.setUsername("N00005");   // DB'deki kanonik ad (oturumda küçük harfli gelebilir)
        me.setActive(true);
        when(userService.findByUsername("n00005")).thenReturn(Optional.of(me));
        when(userService.savePushPreferences(any(), any(), any(), any())).thenAnswer(i -> {
            AppUser u = i.getArgument(0);
            u.setPushMinLevel(i.getArgument(1));
            u.setPushFamilies(i.getArgument(2));
            u.setPushLang(i.getArgument(3));
            return u;
        });
        when(userService.savePushSnooze(any(), any(), any())).thenAnswer(i -> {
            AppUser u = i.getArgument(0);
            u.setPushSnoozeUntil(i.getArgument(1));
            if (i.getArgument(2) != null) u.setPushSnoozeCritical(i.getArgument(2));
            return u;
        });
    }

    private static MockHttpSession session() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "n00005");
        s.setAttribute("userId", 5L);
        s.setAttribute("systemRole", "USER");
        s.setAttribute("memberTeamIds", new java.util.ArrayList<>(List.of(3L)));
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(3L, 4L)));
        return s;
    }

    @Test
    @DisplayName("GET tercihler: varsayılan (tercih yok) — seviye/aile null, dil tr, susturma yok, aileler kataloğu")
    void getPreferences_defaults() throws Exception {
        mvc.perform(get("/api/me/push-preferences").session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.lang").value("tr"))
                .andExpect(jsonPath("$.snooze_active").value(false))
                .andExpect(jsonPath("$.snooze_critical").value(true))
                .andExpect(jsonPath("$.available_families[0]").value("cert"));
    }

    @Test
    @DisplayName("PUT tercihler: normalize (WARNING=hepsi → null, bütün aileler → null), dil en; denetim PUSH_PREFS_UPDATE yalnız değişince")
    void putPreferences_normalizesAndAudits() throws Exception {
        mvc.perform(put("/api/me/push-preferences").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"min_level\":\"HIGH\",\"families\":[\"http\",\"cert\"],\"lang\":\"en\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.min_level").value("HIGH"))
                .andExpect(jsonPath("$.families[0]").value("cert"))
                .andExpect(jsonPath("$.families[1]").value("http"))
                .andExpect(jsonPath("$.lang").value("en"));
        verify(userService).savePushPreferences(any(), eq("HIGH"), eq("cert,http"), eq("en"));
        verify(auditService).recordAction(eq("PUSH_PREFS_UPDATE"), any(), eq("USER"), eq("5"), anyString(), anyString());

        org.mockito.Mockito.clearInvocations(auditService);
        mvc.perform(put("/api/me/push-preferences").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"min_level\":\"HIGH\"}"))   // kısmi gövde: aile/dil korunur → değişiklik yok
                .andExpect(status().isOk());
        verify(auditService, never()).recordAction(eq("PUSH_PREFS_UPDATE"), any(), anyString(), anyString(), anyString(), any());

        String all = String.join("\",\"", com.sitemonitor.service.MonitorTypeCatalog.ORDER);
        mvc.perform(put("/api/me/push-preferences").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"min_level\":\"WARNING\",\"families\":[\"" + all + "\"],\"lang\":\"tr\"}"))
                .andExpect(status().isOk());
        verify(userService).savePushPreferences(any(), isNull(), isNull(), isNull());   // varsayılan = tercih YOK
    }

    @Test
    @DisplayName("PUT tercihler: geçersiz alan 400 + field (seviye / aile / boş aile listesi / dil) — kayıt yok")
    void putPreferences_fieldErrors() throws Exception {
        mvc.perform(put("/api/me/push-preferences").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"min_level\":\"LOUD\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("min_level"));
        mvc.perform(put("/api/me/push-preferences").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"families\":[\"telepathy\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("families"));
        mvc.perform(put("/api/me/push-preferences").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"families\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("families"));
        mvc.perform(put("/api/me/push-preferences").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lang\":\"de\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("lang"))
                .andExpect(jsonPath("$.error").isNotEmpty());
        verify(userService, never()).savePushPreferences(any(), any(), any(), any());
    }

    @Test
    @DisplayName("susturma hazır seçenekleri: 1 sa / 4 sa / yarın 08:00 (İstanbul) / kapat; yalnız 'kritikler' değişimi süreyi korur; denetim PUSH_SNOOZE")
    void snoozePresets() throws Exception {
        mvc.perform(post("/api/me/push-snooze").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"1h\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snooze_until").value("2026-10-04T12:00:00"))
                .andExpect(jsonPath("$.snooze_active").value(true));
        mvc.perform(post("/api/me/push-snooze").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"4h\"}"))
                .andExpect(jsonPath("$.snooze_until").value("2026-10-04T15:00:00"));
        mvc.perform(post("/api/me/push-snooze").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"tomorrow\",\"critical\":false}"))
                .andExpect(jsonPath("$.snooze_until").value("2026-10-05T05:00:00"))   // 08:00 İstanbul = 05:00 UTC
                .andExpect(jsonPath("$.snooze_critical").value(false));
        mvc.perform(post("/api/me/push-snooze").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"critical\":true}"))
                .andExpect(jsonPath("$.snooze_until").value("2026-10-05T05:00:00"))
                .andExpect(jsonPath("$.snooze_critical").value(true));
        mvc.perform(post("/api/me/push-snooze").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"off\"}"))
                .andExpect(jsonPath("$.snooze_active").value(false));
        assertThat(me.getPushSnoozeUntil()).isNull();
        mvc.perform(post("/api/me/push-snooze").session(session()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preset\":\"forever\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("preset"));
        verify(auditService, org.mockito.Mockito.times(5)).recordAction(eq("PUSH_SNOOZE"), any(), eq("USER"), eq("5"), anyString(), isNull());
    }

    @Test
    @DisplayName("kendine test: YALNIZ oturumdaki kişinin kanonik kimliğine, kendi dilinde; 10 dk'da 3 → 4. deneme 429; denetim PUSH_SELF_TEST")
    void selfTest_ownTarget_rateLimited_audited() throws Exception {
        me.setPushLang("en");
        when(userPushService.sendSelfTest("N00005", "en")).thenReturn(new UserPushService.DirectResult(true, 200, null));
        when(userPushService.enabled()).thenReturn(true);
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/me/push-test").session(session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.outcome").value("OK"))
                    .andExpect(jsonPath("$.http_status").value(200));
        }
        mvc.perform(post("/api/me/push-test").session(session()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        verify(userPushService, org.mockito.Mockito.times(3)).sendSelfTest("N00005", "en");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService, org.mockito.Mockito.times(3)).recordAction(eq("PUSH_SELF_TEST"), any(), eq("USER"), eq("5"),
                detail.capture(), isNull());
        assertThat(detail.getValue()).contains("\"outcome\":\"OK\"").contains("\"lang\":\"en\"");

        controller.clock = Clock.fixed(NOW.plusSeconds(601), ZoneOffset.UTC);   // pencere kaydı
        mvc.perform(post("/api/me/push-test").session(session())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("kendine test sonuçları: adres yok → NOT_CONFIGURED, HTTP hatası → HTTP + kod")
    void selfTest_outcomes() throws Exception {
        when(userPushService.sendSelfTest(anyString(), anyString())).thenReturn(new UserPushService.DirectResult(false, null, "NOT_CONFIGURED"));
        mvc.perform(post("/api/me/push-test").session(session()))
                .andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.outcome").value("NOT_CONFIGURED"));
        when(userPushService.sendSelfTest(anyString(), anyString())).thenReturn(new UserPushService.DirectResult(false, 502, "HTTP 502"));
        mvc.perform(post("/api/me/push-test").session(session()))
                .andExpect(jsonPath("$.outcome").value("HTTP")).andExpect(jsonPath("$.http_status").value(502));
    }

    @Test
    @DisplayName("geçmiş: kimlik + üyelik kapsamı oturumdan; görüş kapsamı yüklemi geçilir; geçersiz dönem/süzgeç 400")
    void history_usesSessionScope() throws Exception {
        when(historyService.history(anyString(), any(), any(), anyInt(), anyString(), anyInt(), anyInt(), any()))
                .thenReturn(Map.of("success", true, "data", List.of()));
        mvc.perform(get("/api/me/push-history?days=30&filter=not_sent&page=1&size=10").session(session()))
                .andExpect(status().isOk());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.function.Predicate<Long>> canView = ArgumentCaptor.forClass(java.util.function.Predicate.class);
        verify(historyService).history(eq("N00005"), eq(List.of(3L)), canView.capture(), eq(30), eq("not_sent"), eq(1), eq(10), eq(NOW));
        assertThat(canView.getValue().test(4L)).isTrue();
        assertThat(canView.getValue().test(9L)).isFalse();

        mvc.perform(get("/api/me/push-history?days=90").session(session()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("days"));
        mvc.perform(get("/api/me/push-history?filter=everything").session(session()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("filter"));
    }

    @Test
    @DisplayName("oturumsuz çağrı reddedilir (kimlik parametresi yok — IDOR yüzeyi yok)")
    void noSession_rejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.getPreferences(new MockHttpSession()))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("denetim kataloğu: yeni push olayları katalogda ve ENTEGRASYON kategorisinde")
    void auditCatalog() {
        for (String t : List.of("PUSH_PREFS_UPDATE", "PUSH_SNOOZE", "PUSH_SELF_TEST", "PUSH_SNOOZE_CLEAR")) {
            assertThat(AuditEventCatalog.TYPES).contains(t);
            assertThat(AuditEventCatalog.categoryOf(t)).isEqualTo(AuditEventCatalog.INTEGRATION);
        }
    }
}
