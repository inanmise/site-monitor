package com.sitemonitor.controller;

import com.sitemonitor.model.LoginIssueMailLog;
import com.sitemonitor.model.LoginIssueReport;
import com.sitemonitor.model.LoginIssueReportImage;
import com.sitemonitor.repository.LoginIssueMailLogRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.LoginIssueMailService;
import com.sitemonitor.service.LoginIssueService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin Login Sorun Bildirimleri API — permission gate, listeleme, durum akışı. */
@WebMvcTest(LoginIssueController.class)
class LoginIssueControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean LoginIssueService loginIssueService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean LoginIssueMailService loginIssueMailService;
    @MockitoBean LoginIssueMailLogRepository mailLogRepo;
    @MockitoBean com.sitemonitor.service.AppSettingsService appSettings;
    // AuthInterceptor bağımlılıkları:
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    private MockHttpSession authed() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "someadmin");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    @Test
    void list_returnsCountsAndData() throws Exception {
        when(loginIssueService.list(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());
        when(loginIssueService.counts(any(), any())).thenReturn(Map.of("OPEN", 2L, "IN_PROGRESS", 1L, "RESOLVED", 0L));
        mvc.perform(get("/api/admin/login-issues").session(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.counts.OPEN").value(2));
    }

    @Test
    void noPermission_403() throws Exception {
        doThrow(new SecurityException("Bu işlem için yetkiniz yok: issues.login-reports/view"))
                .when(permissionService).require(any(HttpSession.class), eq("issues.login-reports"), eq("view"));
        mvc.perform(get("/api/admin/login-issues").session(authed()))
                .andExpect(status().isForbidden());
    }

    // ── Kapsamlı müdür (AD ADMIN) kapısı (2026-09-28 regresyon taraması) ─────────────────────
    // ADMIN rolü matriste issues.login-reports(+purge) taşır; müdür yalnız permissionService.require ile her
    // kullanıcının bildirimini (IP, tarayıcı, ekran görüntüsü) okuyup yanıtlayabiliyor, durumunu değiştirip
    // kalıcı silebiliyordu. Kural: SessionScope.requireNotScopedAdmin — matris izni mock'ta AÇIK olsa da 403.

    private MockHttpSession scopedAdmin() {
        MockHttpSession s = authed();
        s.setAttribute("viewTeamIds", List.of(2L));
        s.setAttribute("manageTeamIds", List.of(2L));
        return s;
    }

    @Test
    @DisplayName("Kapsamlı müdür: liste/ayrıntı/yorumlar/durum/yanıt/kalıcı silme HEPSİ 403 — servis hiç çağrılmaz")
    void scopedAdmin_blockedOnEveryAdminEndpoint() throws Exception {
        when(loginIssueService.get(7L)).thenReturn(Optional.of(report(7L)));

        mvc.perform(get("/api/admin/login-issues").session(scopedAdmin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/login-issues/7").session(scopedAdmin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/login-issues/7/comments").session(scopedAdmin())).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/login-issues/7/status").session(scopedAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/login-issues/7/comments").session(scopedAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"yanıt\",\"internal\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/login-issues/7").session(scopedAdmin())).andExpect(status().isForbidden());

        verify(loginIssueService, never()).list(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
        verify(loginIssueService, never()).get(any());
        verify(loginIssueService, never()).purge(any());
        verify(loginIssueService, never()).updateStatus(any(), any(), any(), any(), any());
        verify(loginIssueService, never()).addComment(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    @DisplayName("Kapı yalnız kapsamlı ADMIN'i keser: matristen izin verilen başka rol (TEAM_ADMIN, takım kapsamlı) listeyi açar")
    void explicitGrantToOtherRole_unaffected() throws Exception {
        when(loginIssueService.list(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());
        when(loginIssueService.counts(any(), any())).thenReturn(Map.of());
        MockHttpSession s = authed();
        s.setAttribute("systemRole", "TEAM_ADMIN");
        s.setAttribute("viewTeamIds", List.of(2L));
        s.setAttribute("manageTeamIds", List.of(2L));
        mvc.perform(get("/api/admin/login-issues").session(s)).andExpect(status().isOk());
    }

    @Test
    void resolveWithoutNote_400() throws Exception {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(9L); r.setStatus("OPEN"); r.setReportedAt("2026-07-23T10:00:00");
        when(loginIssueService.get(9L)).thenReturn(Optional.of(r));
        when(loginIssueService.updateStatus(eq(9L), eq("RESOLVED"), any(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("Çözüm notu zorunludur"));
        mvc.perform(put("/api/admin/login-issues/9/status").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statusUpdate_success() throws Exception {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(9L); r.setStatus("IN_PROGRESS"); r.setReportedAt("2026-07-23T10:00:00");
        when(loginIssueService.get(9L)).thenReturn(Optional.of(r));
        when(loginIssueService.updateStatus(eq(9L), eq("IN_PROGRESS"), any(), anyString(), any())).thenReturn(r);
        when(loginIssueService.images(9L)).thenReturn(List.of());
        mvc.perform(put("/api/admin/login-issues/9/status").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void resolve_notifiesReporterByEmail() throws Exception {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(9L); r.setStatus("RESOLVED"); r.setReportedAt("2026-07-24T09:00:00");
        r.setReporterEmail("reporter@example.com"); r.setResolutionNote("Hesap açıldı"); r.setResolvedAt("2026-07-24T10:00:00");
        when(loginIssueService.get(9L)).thenReturn(Optional.of(r));
        when(loginIssueService.updateStatus(eq(9L), eq("RESOLVED"), any(), anyString(), any())).thenReturn(r);
        when(loginIssueService.images(9L)).thenReturn(List.of());
        when(appSettings.getString(eq("site.monitor.system-admin.email"), anyString())).thenReturn("admin@example.com");
        mvc.perform(put("/api/admin/login-issues/9/status").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\",\"resolutionNote\":\"Hesap açıldı\"}"))
                .andExpect(status().isOk());
        // Çözüldü maili ASYNC + loglu (dispatchResolved): reportId, refCode, reporter, admin + zenginleştirilmiş
        // içerik (username/errorText/message/reportedAt null/eq), çözüm notu, çözülme zamanı, görseller.
        verify(loginIssueMailService).dispatchResolved(eq(9L), eq("LIR-2026-000009"),
                eq("reporter@example.com"), eq("admin@example.com"),
                any(), any(), any(), eq("2026-07-24T09:00:00"),
                eq("Hesap açıldı"), eq("2026-07-24T10:00:00"), any());
    }

    @Test
    void detail_notFound_404() throws Exception {
        when(loginIssueService.get(999L)).thenReturn(Optional.empty());
        mvc.perform(get("/api/admin/login-issues/999").session(authed()))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_mapsRealRow_refCodeSummaryImageCount() throws Exception {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(7L); r.setReportedAt("2026-07-24T09:00:00"); r.setStatus("RESOLVED");
        r.setUsername("N77"); r.setIpAddress("1.2.3.4"); r.setImageCount(2);
        r.setResolvedAt("2026-07-24T11:30:00"); r.setResolvedBy("admin");   // liste satırında çözülme tarihi
        // 80+ karakter + iç boşluklar → özet 80'de kırpılır, "\s+" tek boşluğa iner, "…" eklenir.
        r.setMessage("Satır1\n\n  çok    boşluklu   ve uzun bir mesaj " + "x".repeat(90));
        when(loginIssueService.list(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(r)));
        when(loginIssueService.counts(any(), any())).thenReturn(Map.of("OPEN", 1L, "IN_PROGRESS", 0L, "RESOLVED", 0L));

        mvc.perform(get("/api/admin/login-issues?q=locked&since=2026-07-01T00:00:00&status=RESOLVED").session(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].refCode").value("LIR-2026-000007"))
                .andExpect(jsonPath("$.data[0].username").value("N77"))
                .andExpect(jsonPath("$.data[0].imageCount").value(2))
                .andExpect(jsonPath("$.data[0].resolvedAt").value("2026-07-24T11:30:00"))
                .andExpect(jsonPath("$.data[0].messageSummary", org.hamcrest.Matchers.endsWith("…")))
                .andExpect(jsonPath("$.data[0].messageSummary", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\n"))))
                .andExpect(jsonPath("$.total").value(1));

        // q/since/status request param'ları servise iletilir (source/category filtresi yok → null).
        verify(loginIssueService).list(eq("RESOLVED"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), eq("locked"),
                eq("2026-07-01T00:00:00"), any(), anyInt(), anyInt());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Çoklu etki (2026-09-28): impact süzgeci servise iletilir; liste satırı impacts dizisi, detay impacts + impactOther taşır; eski kayıt []")
    void impacts_filterListAndDetail() throws Exception {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(8L); r.setReportedAt("2026-09-28T09:00:00"); r.setStatus("OPEN"); r.setUsername("N8"); r.setMessage("m");
        r.setSource("USER_REPORT"); r.setImpacts("LOGIN,SLOW,OTHER"); r.setImpactOther("VPN açıkken");
        LoginIssueReport old = new LoginIssueReport();
        old.setId(9L); old.setReportedAt("2026-07-01T09:00:00"); old.setStatus("OPEN"); old.setMessage("eski");
        when(loginIssueService.list(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(r, old)));
        when(loginIssueService.counts(any(), any())).thenReturn(Map.of("OPEN", 2L, "IN_PROGRESS", 0L, "RESOLVED", 0L));
        mvc.perform(get("/api/admin/login-issues?impact=SLOW").session(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].impacts[0]").value("LOGIN"))
                .andExpect(jsonPath("$.data[0].impacts.length()").value(3))
                .andExpect(jsonPath("$.data[0].impactOther").doesNotExist())   // listede serbest metin yok (hafif)
                .andExpect(jsonPath("$.data[1].impacts.length()").value(0));
        verify(loginIssueService).list(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), eq("SLOW"), org.mockito.ArgumentMatchers.isNull(), any(), any(), anyInt(), anyInt());

        when(loginIssueService.get(8L)).thenReturn(Optional.of(r));
        mvc.perform(get("/api/admin/login-issues/8").session(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.impacts[2]").value("OTHER"))
                .andExpect(jsonPath("$.data.impactOther").value("VPN açıkken"));
    }

    @Test
    void detail_serialisesImagesAsDataUrls() throws Exception {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(5L); r.setReportedAt("2026-07-24T09:00:00"); r.setStatus("OPEN");
        r.setUsername("N5"); r.setMessage("giriş yapamıyorum"); r.setImageCount(2);
        when(loginIssueService.get(5L)).thenReturn(Optional.of(r));
        LoginIssueReportImage i1 = new LoginIssueReportImage(); i1.setContentType("image/png");  i1.setDataBase64("AAAA");
        LoginIssueReportImage i2 = new LoginIssueReportImage(); i2.setContentType("image/jpeg"); i2.setDataBase64("BBBB");
        when(loginIssueService.images(5L)).thenReturn(List.of(i1, i2));

        mvc.perform(get("/api/admin/login-issues/5").session(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refCode").value("LIR-2026-000005"))
                .andExpect(jsonPath("$.data.images[0]").value("data:image/png;base64,AAAA"))
                .andExpect(jsonPath("$.data.images[1]").value("data:image/jpeg;base64,BBBB"));
    }

    @Test
    void detail_includesMailHistory() throws Exception {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(9L); r.setReportedAt("2026-07-24T09:00:00"); r.setStatus("RESOLVED");
        r.setUsername("N9"); r.setMessage("giriş yok");
        when(loginIssueService.get(9L)).thenReturn(Optional.of(r));
        LoginIssueMailLog m = new LoginIssueMailLog();
        m.setMailType("RESOLVED"); m.setRecipientTo("reporter@example.com"); m.setCc("admin@example.com");
        m.setEmailFrom("noreply@sitemonitor"); m.setSubject("[SiteMonitor] ✅ ... LIR-2026-000009");
        m.setBodyHtml("<html>çözüldü</html>");
        m.setStatus("SENT"); m.setForced(true); m.setSentAt("2026-07-24T10:00:00");
        when(mailLogRepo.findByReportIdOrderByIdAsc(9L)).thenReturn(List.of(m));

        mvc.perform(get("/api/admin/login-issues/9").session(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mailHistory[0].mailType").value("RESOLVED"))
                .andExpect(jsonPath("$.data.mailHistory[0].from").value("noreply@sitemonitor"))
                .andExpect(jsonPath("$.data.mailHistory[0].to").value("reporter@example.com"))
                .andExpect(jsonPath("$.data.mailHistory[0].cc").value("admin@example.com"))
                .andExpect(jsonPath("$.data.mailHistory[0].subject").value("[SiteMonitor] ✅ ... LIR-2026-000009"))
                .andExpect(jsonPath("$.data.mailHistory[0].body").value("<html>çözüldü</html>"))
                .andExpect(jsonPath("$.data.mailHistory[0].status").value("SENT"))
                .andExpect(jsonPath("$.data.mailHistory[0].forced").value(true));
    }

    // ── Kalici silme (AYRI + hassas yetki) ───────────────────────────────────

    @Test
    @DisplayName("Kimlik izi (2026-09-28c): matrisle izin verilen başka rol bildirenin IP / tarayıcısını GÖRMEZ (satır işaretli); global admin / AUDIT görür")
    void reporterIdentityTrace_maskedForNonGlobal() throws Exception {
        com.sitemonitor.model.LoginIssueReport r = report(7L);
        r.setIpAddress("198.51.100.90"); r.setUserAgent("Mozilla/5.0 GateBrowser/4.0");
        // Otomatik bağlam JSON METNİ de aynı izi taşır (IssueReportController.buildAutoContext)
        r.setAutoContextJson("{\"url\":\"/app\",\"tab\":\"domains\",\"ip\":\"198.51.100.90\",\"userAgent\":\"Mozilla/5.0 GateBrowser/4.0\"}");
        when(loginIssueService.get(7L)).thenReturn(Optional.of(r));
        when(loginIssueService.list(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(r)));
        when(loginIssueService.counts(any(), any())).thenReturn(Map.of());
        MockHttpSession teamAdmin = authed();
        teamAdmin.setAttribute("systemRole", "TEAM_ADMIN");
        teamAdmin.setAttribute("viewTeamIds", List.of(2L));
        teamAdmin.setAttribute("manageTeamIds", List.of(2L));
        String list = mvc.perform(get("/api/admin/login-issues").session(teamAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ipAddress").doesNotExist())
                .andExpect(jsonPath("$.data[0].identity_masked").value(true))
                .andReturn().getResponse().getContentAsString();
        String detail = mvc.perform(get("/api/admin/login-issues/7").session(teamAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ipAddress").doesNotExist())
                .andExpect(jsonPath("$.data.userAgent").doesNotExist())
                .andExpect(jsonPath("$.data.identity_masked").value(true))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(list + detail).doesNotContain("198.51.100.90").doesNotContain("GateBrowser")
                .contains("domains");   // bağlamın izsiz kısmı kalır
        for (String role : List.of("ADMIN", "AUDIT")) {
            MockHttpSession g = authed();
            g.setAttribute("systemRole", role);
            mvc.perform(get("/api/admin/login-issues/7").session(g))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ipAddress").value("198.51.100.90"))
                    .andExpect(jsonPath("$.data.userAgent").value("Mozilla/5.0 GateBrowser/4.0"));
        }
    }

    private com.sitemonitor.model.LoginIssueReport report(long id) {
        com.sitemonitor.model.LoginIssueReport r = new com.sitemonitor.model.LoginIssueReport();
        r.setId(id);
        r.setReportedAt("2026-08-24T10:00:00");
        r.setSource("USER_REPORT");
        r.setUsername("N12345");
        return r;
    }

    @Test
    @DisplayName("Kalici silme AYRI yetki ister — duzenleme yetkisi YETMEZ")
    void purge_requiresDedicatedPermission() throws Exception {
        // Raporun DURUMUNU degistirmek ile kaydi YOK ETMEK farkli yetkiler: ikincisi guvenlik
        // bildirimlerini de silebilir. "Raporlari yonetsin ama kanit silemesin" ayrimi korunmali.
        // require() asiri yuklu (HttpSession / String) → any() belirsiz kalir, TIPLI matcher sart.
        doThrow(new SecurityException("yok")).when(permissionService)
                .require(any(jakarta.servlet.http.HttpSession.class),
                        eq("issues.login-reports.purge"), eq("execute"));

        mvc.perform(delete("/api/admin/login-issues/7").session(authed()))
                .andExpect(status().isForbidden());

        verify(loginIssueService, never()).purge(any());
    }

    @Test
    @DisplayName("Yetkiliyse kayit silinir ve DENETIME yazilir")
    void purge_deletesAndAudits() throws Exception {
        when(loginIssueService.get(7L)).thenReturn(Optional.of(report(7L)));

        mvc.perform(delete("/api/admin/login-issues/7").session(authed()))
                .andExpect(status().isOk());

        verify(loginIssueService).purge(7L);
        verify(auditService).recordAction(eq("LOGIN_ISSUE_PURGE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), anyString(), eq("7"), anyString());
    }

    @Test
    @DisplayName("OLMAYAN kayit 404 — silme cagrilmaz")
    void purge_missingRowIs404() throws Exception {
        when(loginIssueService.get(99L)).thenReturn(Optional.empty());

        mvc.perform(delete("/api/admin/login-issues/99").session(authed()))
                .andExpect(status().isNotFound());

        verify(loginIssueService, never()).purge(any());
    }

    // ── Konuşma dizisi (2026-09-26): yanıt maili tam bir kez, iç not sessiz, durum maili yalnız gerçek geçişte ──

    private static LoginIssueReport reported(long id, String status, String username) {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(id); r.setStatus(status); r.setReportedAt("2026-09-20T10:00:00"); r.setUpdatedAt("2026-09-21T10:00:00");
        r.setUsername(username); r.setReporterEmail("kullanici.x@example.com"); r.setMessage("mesaj");
        return r;
    }

    private static com.sitemonitor.model.IssueReportComment comment(long id, boolean internal) {
        com.sitemonitor.model.IssueReportComment c = new com.sitemonitor.model.IssueReportComment();
        c.setId(id); c.setReportId(9L); c.setKind("COMMENT"); c.setAuthorUsername("someadmin"); c.setAuthorRole("ADMIN");
        c.setInternal(internal); c.setByReporter(false); c.setBody(internal ? "iç not" : "yanıt"); c.setCreatedAt("2026-09-21T10:00:00");
        return c;
    }

    @Test
    @DisplayName("POST comments: herkese açık yanıt → TAM BİR yanıt maili + audit; iç not → mail YOK (audit var)")
    void adminReply_publicMailsOnce_internalNever() throws Exception {
        when(loginIssueService.get(9L)).thenReturn(Optional.of(reported(9L, "OPEN", "kullanici.x")));
        when(loginIssueService.addComment(eq(9L), eq("someadmin"), eq("ADMIN"), eq(false), eq(false), eq("yanıt")))
                .thenReturn(new LoginIssueService.CommentResult(comment(1L, false), false));
        when(loginIssueService.addComment(eq(9L), eq("someadmin"), eq("ADMIN"), eq(true), eq(false), eq("iç not")))
                .thenReturn(new LoginIssueService.CommentResult(comment(2L, true), false));

        mvc.perform(post("/api/admin/login-issues/9/comments").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"yanıt\",\"internal\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.internal").value(false))
                .andExpect(jsonPath("$.data.body").value("yanıt"));
        verify(loginIssueMailService, org.mockito.Mockito.times(1)).dispatchAdminReply(eq(9L), eq("LIR-2026-000009"),
                eq("kullanici.x@example.com"), eq("kullanici.x"), eq("OPEN"), eq("yanıt"), any(), any());

        mvc.perform(post("/api/admin/login-issues/9/comments").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"iç not\",\"internal\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.internal").value(true));
        verify(loginIssueMailService, org.mockito.Mockito.times(1)).dispatchAdminReply(any(), any(), any(), any(), any(), any(), any(), any());   // hâlâ 1
        verify(auditService, org.mockito.Mockito.times(2)).recordAction(eq("LOGIN_ISSUE_COMMENT"), any(HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("LOGIN_ISSUE"), eq("9"), anyString());
    }

    @Test
    @DisplayName("POST comments: yönetici KENDİ raporuna yanıt yazarsa kendine mail gitmez (büyük/küçük harf duyarsız)")
    void adminReply_ownReportNoSelfMail() throws Exception {
        when(loginIssueService.get(9L)).thenReturn(Optional.of(reported(9L, "OPEN", "SomeAdmin")));
        when(loginIssueService.addComment(eq(9L), eq("someadmin"), eq("ADMIN"), eq(false), eq(false), eq("kendime not")))
                .thenReturn(new LoginIssueService.CommentResult(comment(3L, false), false));
        mvc.perform(post("/api/admin/login-issues/9/comments").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"kendime not\"}"))
                .andExpect(status().isOk());
        verify(loginIssueMailService, never()).dispatchAdminReply(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST comments: boş / 4001 karakter → 400; olmayan kayıt → 404; izinsiz → 403")
    void adminReply_validation() throws Exception {
        when(loginIssueService.get(9L)).thenReturn(Optional.of(reported(9L, "OPEN", "kullanici.x")));
        mvc.perform(post("/api/admin/login-issues/9/comments").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/login-issues/9/comments").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + "x".repeat(4001) + "\"}"))
                .andExpect(status().isBadRequest());
        when(loginIssueService.get(99L)).thenReturn(Optional.empty());
        mvc.perform(post("/api/admin/login-issues/99/comments").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}"))
                .andExpect(status().isNotFound());
        verify(loginIssueService, never()).addComment(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
        doThrow(new SecurityException("yetki yok"))
                .when(permissionService).require(any(HttpSession.class), eq("issues.login-reports"), eq("edit"));
        mvc.perform(post("/api/admin/login-issues/9/comments").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET comments: iç notlar DÂHİL döner (yönetici görünümü) + zaman çizelgesi")
    void comments_includeInternalForAdmin() throws Exception {
        when(loginIssueService.get(9L)).thenReturn(Optional.of(reported(9L, "OPEN", "kullanici.x")));
        when(loginIssueService.comments(9L)).thenReturn(List.of(comment(1L, false), comment(2L, true)));
        mvc.perform(get("/api/admin/login-issues/9/comments").session(authed()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].internal").value(true))
                .andExpect(jsonPath("$.timeline[0].status").value("OPEN"));
    }

    @Test
    @DisplayName("PUT status OPEN→IN_PROGRESS: kısa durum maili TAM BİR kez; aynı durumu tekrar kaydetmek mail üretmez; çözüldü maili yok")
    void statusChange_shortMailOnlyOnRealChange() throws Exception {
        LoginIssueReport before = reported(9L, "OPEN", "kullanici.x");
        LoginIssueReport after = reported(9L, "IN_PROGRESS", "kullanici.x");
        when(loginIssueService.get(9L)).thenReturn(Optional.of(before));
        when(loginIssueService.updateStatus(eq(9L), eq("IN_PROGRESS"), any(), anyString(), any())).thenReturn(after);
        when(loginIssueService.images(9L)).thenReturn(List.of());

        mvc.perform(put("/api/admin/login-issues/9/status").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk());
        verify(loginIssueMailService, org.mockito.Mockito.times(1)).dispatchStatusChange(eq(9L), eq("LIR-2026-000009"),
                eq("kullanici.x@example.com"), eq("kullanici.x"), eq("IN_PROGRESS"), any(), any(), any());
        verify(loginIssueMailService, never()).dispatchResolved(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        // Aktörün ROLÜ servise geçer (zaman çizelgesi satırı)
        verify(loginIssueService).updateStatus(eq(9L), eq("IN_PROGRESS"), any(), eq("someadmin"), eq("ADMIN"));

        // Aynı durum tekrar (yalnız not güncellemesi): gerçek geçiş yok → mail yok
        when(loginIssueService.get(9L)).thenReturn(Optional.of(after));
        mvc.perform(put("/api/admin/login-issues/9/status").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\",\"resolutionNote\":\"not\"}"))
                .andExpect(status().isOk());
        verify(loginIssueMailService, org.mockito.Mockito.times(1)).dispatchStatusChange(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("PUT status RESOLVED: zengin çözüldü maili gider, kısa durum maili GİTMEZ (çift mail yok)")
    void resolve_noShortStatusMail() throws Exception {
        LoginIssueReport before = reported(9L, "IN_PROGRESS", "kullanici.x");
        LoginIssueReport after = reported(9L, "RESOLVED", "kullanici.x");
        after.setResolutionNote("Hesap açıldı"); after.setResolvedAt("2026-09-21T11:00:00");
        when(loginIssueService.get(9L)).thenReturn(Optional.of(before));
        when(loginIssueService.updateStatus(eq(9L), eq("RESOLVED"), any(), anyString(), any())).thenReturn(after);
        when(loginIssueService.images(9L)).thenReturn(List.of());
        when(appSettings.getString(eq("site.monitor.system-admin.email"), anyString())).thenReturn("admin@example.com");

        mvc.perform(put("/api/admin/login-issues/9/status").session(authed())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\",\"resolutionNote\":\"Hesap açıldı\"}"))
                .andExpect(status().isOk());
        verify(loginIssueMailService, org.mockito.Mockito.times(1)).dispatchResolved(eq(9L), anyString(), eq("kullanici.x@example.com"),
                eq("admin@example.com"), any(), any(), any(), any(), any(), any(), any());
        verify(loginIssueMailService, never()).dispatchStatusChange(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
