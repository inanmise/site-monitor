package com.sitemonitor.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sitemonitor.config.RequestLoggingFilter;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.ScriptedCheckerService;
import com.sitemonitor.service.ScriptedCheckerService.SecretLookup;
import com.sitemonitor.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sentetik izlemenin gizli env değerini GÖSTER ucu (2026-10-19, kullanıcı kararı): yalnız global yönetici ve izlemenin
 * takımını KENDİ kapsamıyla yöneten kapsamlı yönetici (müdür). Takım üyesi / TEAM_ADMIN / USER, AUDIT, 7/24 operatörü ve
 * takım erişim paylaşımı (EDIT / DELETE dâhil) 403; göremediği izleme 404. Değişken yok / gizli değil → 404, çözülemiyor →
 * 409, dakikalık / saatlik sınır → 429. Değer denetime, hata gövdesine ve günlüğe düşmez; yanıt no-store.
 */
@WebMvcTest(ScriptedSecretRevealController.class)
class ScriptedSecretRevealControllerTest {

    private static final String SECRET = "Pz9-Gizli-Deger-7781";
    private static final String URL = "/api/monitoring/scripted/41/env/API_TOKEN/reveal";

    @Autowired MockMvc mvc;
    @Autowired ScriptedSecretRevealController controller;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean ScriptedMonitorRepository scriptedMonitorRepo;
    @MockitoBean ScriptedCheckerService scriptedChecker;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;

    @BeforeEach
    void defaults() {
        // Denetleyici bağlamla birlikte testler arasında yaşar — sayaçlar her testte sıfırdan
        ((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(controller, "attempts")).clear();
        ScriptedMonitor m = new ScriptedMonitor();
        m.setId(41L);
        m.setName("Ödeme akışı");
        m.setTeamId(5L);
        when(scriptedMonitorRepo.findById(41L)).thenReturn(Optional.of(m));
        when(scriptedMonitorRepo.findById(99L)).thenReturn(Optional.empty());
        when(scriptedChecker.revealSecret(any(), eq("API_TOKEN")))
                .thenReturn(new SecretLookup(SecretLookup.Status.OK, SECRET));
    }

    // ── Oturumlar ──────────────────────────────────────────────────────────────────────────────────────

    private static MockHttpSession base(String role, Long userId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "kullanici" + userId);
        s.setAttribute("userId", userId);
        s.setAttribute("systemRole", role);
        return s;
    }

    /** Global yönetici: ADMIN, görüş kapsamı yok (null). */
    private static MockHttpSession globalAdmin() {
        return base("ADMIN", 1L);
    }

    /** Kapsamlı yönetici (müdür): ADMIN + görüş / yönetim kapsamı. */
    private static MockHttpSession scopedAdmin(Long userId, List<Long> view, List<Long> manage) {
        MockHttpSession s = base("ADMIN", userId);
        s.setAttribute("teamId", view.get(0));
        s.setAttribute("viewTeamIds", new ArrayList<>(view));
        s.setAttribute("manageTeamIds", new ArrayList<>(manage));
        s.setAttribute("memberTeamIds", List.of(view.get(0)));
        return s;
    }

    private static MockHttpSession member(String role, Long team, List<Long> manage, Long userId) {
        MockHttpSession s = base(role, userId);
        s.setAttribute("teamId", team);
        s.setAttribute("viewTeamIds", List.of(team));
        s.setAttribute("memberTeamIds", List.of(team));
        s.setAttribute("manageTeamIds", new ArrayList<>(manage));
        return s;
    }

    // ── Kim görebilir ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("global yönetici: değer döner, yanıt no-store; denetim adı + takımı yazar, DEĞERİ yazmaz")
    void globalAdminReveals() throws Exception {
        MvcResult r = mvc.perform(post(URL).session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("API_TOKEN"))
                .andExpect(jsonPath("$.data.value").value(SECRET))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Pragma", "no-cache"))
                .andReturn();
        assertThat(r.getResponse().getContentAsString()).contains(SECRET);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("SCRIPTED_SECRET_REVEAL"), any(HttpSession.class), eq("SCRIPTED_MONITOR"),
                eq("41"), detail.capture(), isNull());
        assertThat(detail.getValue())
                .contains("\"variable\":\"API_TOKEN\"").contains("\"team_id\":5").contains("\"monitor_id\":41")
                .contains("Ödeme akışı")
                .doesNotContain(SECRET);
        verify(permissionService).require(any(HttpSession.class), eq("monitoring.read"), eq("view"));
    }

    @Test
    @DisplayName("kapsamlı yönetici sahibi takımı YÖNETİYOR → 200; yalnız görüyor (astın takımı değil) → 403")
    void scopedAdminNeedsOwnManageScope() throws Exception {
        mvc.perform(post(URL).session(scopedAdmin(2L, List.of(5L, 9L), List.of(5L, 9L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value").value(SECRET));
        mvc.perform(post(URL).session(scopedAdmin(3L, List.of(9L, 5L), List.of(9L))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("takım üyesi USER / TEAM_ADMIN (takımı yönetse bile), AUDIT ve 7/24 operatörü → 403, değer okunmaz")
    void nonAdminsAreForbidden() throws Exception {
        MockHttpSession noc = member("USER", 3L, List.of(), 13L);
        noc.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
        noc.setAttribute(SessionScope.ATTR_NOC_TEAM_IDS, List.of(3L));
        for (MockHttpSession s : List.of(
                member("USER", 5L, List.of(), 10L),
                member("TEAM_ADMIN", 5L, List.of(5L), 11L),
                base("AUDIT", 12L),
                noc)) {
            String body = mvc.perform(post(URL).session(s))
                    .andExpect(status().isForbidden())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(SECRET);
        }
        verify(scriptedChecker, never()).revealSecret(any(), anyString());
        verify(auditService, never()).recordAction(eq("SCRIPTED_SECRET_REVEAL"), any(HttpSession.class), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("takım erişim paylaşımı ASLA vermez: EDIT / DELETE paylaşımlı USER ve paylaşımlı (yönetmediği takım) müdür → 403")
    void teamAccessGrantNeverReveals() throws Exception {
        MockHttpSession grantee = member("USER", 9L, List.of(), 20L);
        MockHttpSession grantAdmin = scopedAdmin(21L, List.of(9L), List.of(9L));
        for (MockHttpSession s : List.of(grantee, grantAdmin)) {
            for (String a : List.of(SessionScope.ATTR_GRANT_VIEW, SessionScope.ATTR_GRANT_EXECUTE,
                    SessionScope.ATTR_GRANT_EDIT, SessionScope.ATTR_GRANT_DELETE)) {
                s.setAttribute(a, new ArrayList<>(List.of(5L)));
            }
            mvc.perform(post(URL).session(s)).andExpect(status().isForbidden());
        }
        verify(scriptedChecker, never()).revealSecret(any(), anyString());
    }

    @Test
    @DisplayName("göremediği takımın izlemesi ve olmayan izleme → 404 (varlık sızmaz); izin yoksa 403")
    void invisibleOrMissingIsNotFound() throws Exception {
        mvc.perform(post(URL).session(member("USER", 9L, List.of(), 30L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post(URL).session(scopedAdmin(31L, List.of(9L), List.of(9L))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/monitoring/scripted/99/env/API_TOKEN/reveal").session(globalAdmin()))
                .andExpect(status().isNotFound());
        doThrow(new SecurityException("Bu işlem için yetkiniz yok"))
                .when(permissionService).require(any(HttpSession.class), eq("monitoring.read"), eq("view"));
        mvc.perform(post(URL).session(globalAdmin())).andExpect(status().isForbidden());
        verify(scriptedChecker, never()).revealSecret(any(), anyString());
    }

    @Test
    @DisplayName("değişken yok / gizli değil / değer boş → 404 NOT_FOUND; çözülemiyor → 409 SECRET_UNREADABLE; denetim yok")
    void notSecretAndUnreadable() throws Exception {
        when(scriptedChecker.revealSecret(any(), eq("BASE_URL"))).thenReturn(new SecretLookup(SecretLookup.Status.NOT_FOUND, null));
        when(scriptedChecker.revealSecret(any(), eq("OLD_KEY"))).thenReturn(new SecretLookup(SecretLookup.Status.UNREADABLE, null));
        mvc.perform(post("/api/monitoring/scripted/41/env/BASE_URL/reveal").session(globalAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error").value(containsString("gizli")));
        mvc.perform(post("/api/monitoring/scripted/41/env/OLD_KEY/reveal").session(globalAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECRET_UNREADABLE"))
                .andExpect(jsonPath("$.error").value(containsString("yeniden girip kaydedin")));
        // Çok uzun ad hiçbir aramaya girmez
        mvc.perform(post("/api/monitoring/scripted/41/env/" + "A".repeat(201) + "/reveal").session(globalAdmin()))
                .andExpect(status().isNotFound());
        verify(auditService, never()).recordAction(eq("SCRIPTED_SECRET_REVEAL"), any(HttpSession.class), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("kullanıcı başına dakikada 10: 11. istek 429 RATE_LIMITED + Retry-After, değer dönmez; başka kullanıcı etkilenmez")
    void minuteRateLimit() throws Exception {
        MockHttpSession admin = base("ADMIN", 77L);
        for (int i = 0; i < ScriptedSecretRevealController.MAX_PER_MINUTE; i++) {
            mvc.perform(post(URL).session(admin)).andExpect(status().isOk());
        }
        String body = mvc.perform(post(URL).session(admin))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.retry_after_seconds").isNumber())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(SECRET);
        mvc.perform(post(URL).session(base("ADMIN", 78L))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("saatte 30: dakikaya yayılmış 30 deneme kabul, 31. saat dolana kadar reddedilir (kalan süre saniye olarak)")
    void hourRateLimit() {
        long t0 = 1_000_000_000L;
        for (int i = 0; i < ScriptedSecretRevealController.MAX_PER_HOUR; i++) {
            assertThat(controller.tryAcquire("u:hour", t0 + i * 61_000L)).isZero();
        }
        long now = t0 + 30 * 61_000L;
        long retry = controller.tryAcquire("u:hour", now);
        assertThat(retry).isPositive().isLessThanOrEqualTo(3600L);
        assertThat(controller.tryAcquire("u:hour", t0 + ScriptedSecretRevealController.HOUR_MS)).isZero();   // ilki düştü
        // dakikalık sınır: 10 hızlı deneme, 11. reddedilir; bir dakika sonra kabul
        long t1 = t0 + 10 * ScriptedSecretRevealController.HOUR_MS;
        for (int i = 0; i < ScriptedSecretRevealController.MAX_PER_MINUTE; i++) {
            assertThat(controller.tryAcquire("u:minute", t1 + i)).isZero();
        }
        assertThat(controller.tryAcquire("u:minute", t1 + 500)).isBetween(1L, 60L);
        assertThat(controller.tryAcquire("u:minute", t1 + ScriptedSecretRevealController.MINUTE_MS)).isZero();
    }

    @Test
    @DisplayName("istek / yanıt günlüğü TRACE iken bile değer HİÇBİR günlük satırına düşmez (gövde atlandı izi var)")
    void valueNeverLogged() throws Exception {
        Logger filterLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level before = filterLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        filterLogger.setLevel(Level.TRACE);
        try {
            mvc.perform(post(URL).session(globalAdmin())).andExpect(status().isOk());
            List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(lines).isNotEmpty();
            assertThat(String.join("\n", lines)).doesNotContain(SECRET);
            assertThat(lines).anyMatch(l -> l.contains("/reveal") && l.contains("[omitted: sensitive endpoint]"));
        } finally {
            filterLogger.setLevel(before);
            root.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("canReveal: global yönetici her takımda (takımsız dahil); müdür yalnız manageTeamIds'te; diğer roller ve paylaşım hayır")
    void canRevealRule() {
        assertThat(ScriptedSecretRevealController.canReveal(globalAdmin(), 5L)).isTrue();
        assertThat(ScriptedSecretRevealController.canReveal(globalAdmin(), null)).isTrue();
        MockHttpSession mudur = scopedAdmin(2L, List.of(5L, 9L), List.of(5L));
        assertThat(ScriptedSecretRevealController.canReveal(mudur, 5L)).isTrue();
        assertThat(ScriptedSecretRevealController.canReveal(mudur, 9L)).isFalse();
        assertThat(ScriptedSecretRevealController.canReveal(mudur, null)).isFalse();
        MockHttpSession granted = scopedAdmin(3L, List.of(9L), List.of(9L));
        granted.setAttribute(SessionScope.ATTR_GRANT_DELETE, List.of(5L));
        granted.setAttribute(SessionScope.ATTR_GRANT_EDIT, List.of(5L));
        assertThat(ScriptedSecretRevealController.canReveal(granted, 5L)).isFalse();
        assertThat(ScriptedSecretRevealController.canReveal(member("TEAM_ADMIN", 5L, List.of(5L), 4L), 5L)).isFalse();
        assertThat(ScriptedSecretRevealController.canReveal(member("USER", 5L, List.of(), 6L), 5L)).isFalse();
        assertThat(ScriptedSecretRevealController.canReveal(base("AUDIT", 7L), 5L)).isFalse();
        assertThat(ScriptedSecretRevealController.canReveal(null, 5L)).isFalse();
    }
}
