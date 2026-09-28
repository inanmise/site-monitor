package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.LoginIssueReport;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.ClientIpResolver;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.LoginIssueMailService;
import com.sitemonitor.service.LoginIssueService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Oturum içi "Sorun Bildir" (USER_REPORT) — kimlik oturumdan, e-posta kuralı (profil birincil /
 *  yoksa zorunlu + profile kaydetme), maskeleme, kategori doğrulama, digest modunda tekil mail atlama. */
@WebMvcTest(IssueReportController.class)
class IssueReportControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean AppSettingsService appSettings;
    @MockitoBean LoginIssueMailService loginIssueMailService;
    @MockitoBean AuditService auditService;
    @MockitoBean ClientIpResolver clientIpResolver;
    @MockitoBean LoginIssueService loginIssueService;
    @MockitoBean AppUserRepository appUserRepository;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    private MockHttpSession session;
    private String username;

    @BeforeEach
    void setUp(TestInfo info) {
        when(clientIpResolver.resolve(any())).thenReturn("10.1.2.3");
        when(appSettings.getString(eq("site.monitor.system-admin.email"), anyString())).thenReturn("admin@example.com");
        when(appSettings.getBoolean(eq("site.monitor.issue-reports.daily-digest"), anyBoolean())).thenReturn(false);
        LoginIssueReport saved = new LoginIssueReport();
        saved.setId(88L); saved.setReportedAt("2026-08-07T00:00:00");
        when(loginIssueService.save(anyString(), anyString(), any(), anyString(),
                anyList(), anyString(), any(), anyString(), any(LoginIssueService.ReportMeta.class))).thenReturn(saved);
        // Rate-limit haritası controller bean'inde sınıf boyu yaşar → her test KENDİ kullanıcısıyla
        // koşar ki testler birbirinin 10/saat bütçesini tüketmesin.
        username = "N-" + info.getTestMethod().map(m -> m.getName()).orElse("x");
        session = new MockHttpSession();
        session.setAttribute("authenticated", true);
        session.setAttribute("username", username);
    }

    private AppUser userWithEmail(String email) {
        AppUser u = new AppUser();
        u.setUsername(username); u.setEmail(email);
        return u;
    }

    @Test
    @DisplayName("Profil e-postası VARSA: payload e-postası YOK SAYILIR, profil adresi kullanılır; kimlik oturumdan")
    void report_profileEmail_wins() throws Exception {
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(userWithEmail("profil@example.com")));
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Grafik bos gorunuyor\",\"category\":\"BLOCKER\","
                                + "\"email\":\"sahte@x.com\",\"username\":\"BASKASI\","
                                + "\"url\":\"https://cm.example.com/?tab=scripted\",\"tabKey\":\"scripted\","
                                + "\"appVersion\":\"20.1.0\",\"theme\":\"dark\",\"lang\":\"tr\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value("LIR-2026-000088"))
                .andExpect(jsonPath("$.emailSavedToProfile").value(false));

        verify(loginIssueService).save(eq(username), eq("profil@example.com"), any(), eq("Grafik bos gorunuyor"),
                anyList(), eq("10.1.2.3"), any(), anyString(),
                argThat((LoginIssueService.ReportMeta m) -> "USER_REPORT".equals(m.source())
                        && "BLOCKER".equals(m.category()) && "scripted".equals(m.tabKey())
                        && m.autoContextJson() != null && m.autoContextJson().contains("dark")));
        verify(loginIssueMailService).dispatchUserReport(eq(88L), eq("LIR-2026-000088"), eq("admin@example.com"),
                eq(username), eq("profil@example.com"), eq("BLOCKER"), anyString(), any(), any(),
                eq("scripted"), eq("20.1.0"), anyList(), eq("10.1.2.3"), any(), anyString(), isNull(), isNull());
        verify(loginIssueMailService).dispatchAck(eq(88L), anyString(), eq("profil@example.com"),
                eq(username), any(), anyString(), anyList(), anyString());
        // Profil e-postası varken profil GÜNCELLENMEZ.
        verify(appUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("Alan adı aktarım talebi (2026-09-28): category=DOMAIN_TRANSFER kabul edilir ve kayda/maile aynen gider; uydurma tür 400")
    void report_domainTransferCategory() throws Exception {
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(userWithEmail("profil@example.com")));
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Alan adı aktarım talebi - Alan adı: shop.example.com\",\"category\":\"DOMAIN_TRANSFER\","
                                + "\"tabKey\":\"inventory\"}"))
                .andExpect(status().isOk());
        verify(loginIssueService).save(eq(username), eq("profil@example.com"), any(), anyString(),
                anyList(), anyString(), any(), anyString(),
                argThat((LoginIssueService.ReportMeta m) -> "DOMAIN_TRANSFER".equals(m.category())));
        verify(loginIssueMailService).dispatchUserReport(eq(88L), anyString(), eq("admin@example.com"),
                eq(username), eq("profil@example.com"), eq("DOMAIN_TRANSFER"), anyString(), any(), any(),
                eq("inventory"), any(), anyList(), anyString(), any(), anyString(), isNull(), isNull());
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"x\",\"category\":\"TRANSFER_EVERYTHING\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Çoklu etki (2026-09-28): impacts izin listesiyle kanonik CSV'ye, 'Diğer' metni ayıklanıp kayda ve admin mailine gider; bilinmeyen kod 400")
    void report_impacts_validatedPersistedAndMailed() throws Exception {
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(userWithEmail("profil@example.com")));
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Pano yavaş\",\"category\":\"ANNOYANCE\","
                                + "\"impacts\":[\"SLOW\",\"other\",\"LOGIN\",\"SLOW\"],\"impactOther\":\"  VPN   açıkken  \"}"))
                .andExpect(status().isOk());
        verify(loginIssueService).save(eq(username), eq("profil@example.com"), any(), eq("Pano yavaş"),
                anyList(), anyString(), any(), anyString(),
                argThat((LoginIssueService.ReportMeta m) -> "LOGIN,SLOW,OTHER".equals(m.impacts())
                        && "VPN açıkken".equals(m.impactOther()) && "ANNOYANCE".equals(m.category())));
        verify(loginIssueMailService).dispatchUserReport(eq(88L), anyString(), eq("admin@example.com"),
                eq(username), eq("profil@example.com"), eq("ANNOYANCE"), anyString(), any(), any(),
                any(), any(), anyList(), anyString(), any(), anyString(), eq("LOGIN,SLOW,OTHER"), eq("VPN açıkken"));

        // OTHER seçilmeden gelen "Diğer" metni SAKLANMAZ
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"m2\",\"impacts\":[\"MOBILE\"],\"impactOther\":\"gizli metin\"}"))
                .andExpect(status().isOk());
        verify(loginIssueService).save(eq(username), any(), any(), eq("m2"), anyList(), anyString(), any(), anyString(),
                argThat((LoginIssueService.ReportMeta m) -> "MOBILE".equals(m.impacts()) && m.impactOther() == null));

        // Bilinmeyen kod ve 200'ü aşan "Diğer" → 400, kayıt YOK
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"m3\",\"impacts\":[\"LOGIN\",\"DROP_TABLE\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"m4\",\"impacts\":[\"OTHER\"],\"impactOther\":\"" + "x".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest());
        verify(loginIssueService, never()).save(any(), any(), any(), eq("m3"), anyList(), any(), any(), any(), any(LoginIssueService.ReportMeta.class));
        verify(loginIssueService, never()).save(any(), any(), any(), eq("m4"), anyList(), any(), any(), any(), any(LoginIssueService.ReportMeta.class));
    }

    @Test
    @DisplayName("Profil e-postası YOKSA: payload e-postası zorunlu; eksikse 400")
    void report_noProfileEmail_required() throws Exception {
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(userWithEmail(null)));
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"sorun var\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("saveEmailToProfile=true: verilen e-posta profile yazılır + yanıt bayrağı true")
    void report_saveEmailToProfile() throws Exception {
        AppUser u = userWithEmail(null);
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(u));
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"sorun var\",\"email\":\"yeni@example.com\",\"saveEmailToProfile\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailSavedToProfile").value(true));
        verify(appUserRepository).save(argThat((AppUser saved) -> "yeni@example.com".equals(saved.getEmail())));
    }

    @Test
    @DisplayName("Gizlilik: errorText + url + failedRequests yollarındaki hassas query değerleri MASKELENİR")
    void report_masksSecrets() throws Exception {
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(userWithEmail("p@x.com")));
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"m\",\"errorText\":\"GET /api/x?token=GIZLI123 failed\","
                                + "\"url\":\"https://cm/?tab=a&password=SIFRE1\","
                                + "\"failedRequests\":[{\"path\":\"/api/y?api_key=KEY99\",\"status\":500,\"at\":\"t\"}]}"))
                .andExpect(status().isOk());
        verify(loginIssueService).save(anyString(), anyString(),
                argThat((String err) -> !err.contains("GIZLI123") && err.contains("*****")),
                anyString(), anyList(), anyString(), any(), anyString(),
                argThat((LoginIssueService.ReportMeta m) ->
                        !m.autoContextJson().contains("SIFRE1") && !m.autoContextJson().contains("KEY99")));
    }

    @Test
    @DisplayName("Geçersiz kategori → 400; boş açıklama → 400")
    void report_validation() throws Exception {
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(userWithEmail("p@x.com")));
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"m\",\"category\":\"WRONG\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Digest modu AÇIK: tekil admin maili ATLANIR, ACK yine gider, kayıt yazılır")
    void report_digestMode_skipsAdminMail() throws Exception {
        when(appSettings.getBoolean(eq("site.monitor.issue-reports.daily-digest"), anyBoolean())).thenReturn(true);
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(userWithEmail("p@x.com")));
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"digest testi\"}"))
                .andExpect(status().isOk());
        verify(loginIssueService).save(anyString(), anyString(), any(), eq("digest testi"),
                anyList(), anyString(), any(), anyString(), any(LoginIssueService.ReportMeta.class));
        verify(loginIssueMailService, never()).dispatchUserReport(anyLong(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), any(), any(), any(), any(), anyList(),
                anyString(), any(), anyString());
        verify(loginIssueMailService).dispatchAck(anyLong(), anyString(), eq("p@x.com"),
                anyString(), any(), anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("Rate-limit: aynı kullanıcıdan 11. bildirim → 429 (10/saat)")
    void report_rateLimit() throws Exception {
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(userWithEmail("p@x.com")));
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/issue-reports").session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"m" + i + "\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"m11\"}"))
                .andExpect(status().isTooManyRequests());
    }

    // ── "Bildirimlerim" (2026-09-26): kimlik oturumdan, sahiplik 404, iç not sızmaz, yeniden açma ──

    private LoginIssueReport mine(long id, String status) {
        LoginIssueReport r = new LoginIssueReport();
        r.setId(id); r.setStatus(status); r.setUsername(username); r.setReportedAt("2026-09-20T10:00:00");
        r.setMessage("mesaj"); r.setReporterEmail(username + "@example.com");
        return r;
    }

    private static com.sitemonitor.model.IssueReportComment comment(long id, String kind, String author, boolean internal,
                                                                     boolean byReporter, String body) {
        com.sitemonitor.model.IssueReportComment c = new com.sitemonitor.model.IssueReportComment();
        c.setId(id); c.setReportId(7L); c.setKind(kind); c.setAuthorUsername(author); c.setInternal(internal);
        c.setByReporter(byReporter); c.setBody(body); c.setCreatedAt("2026-09-21T10:00:00");
        return c;
    }

    @Test
    @DisplayName("GET mine: kullanıcı adı OTURUMDAN (istek parametresi yok sayılır); standart sayfa zarfı + sayaçlar + yorum sayısı")
    void mine_listUsesSessionUserOnly() throws Exception {
        when(loginIssueService.listMine(eq(username), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(mine(1L, "OPEN"))));
        when(loginIssueService.countsMine(username)).thenReturn(java.util.Map.of("OPEN", 1L, "IN_PROGRESS", 0L, "RESOLVED", 0L));
        when(loginIssueService.publicCommentCounts(any())).thenReturn(java.util.Map.of(1L, 2L));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/issue-reports/mine?username=baskasi&status=OPEN&page=0&size=20").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].refCode").value("LIR-2026-000001"))
                .andExpect(jsonPath("$.data[0].commentCount").value(2))
                .andExpect(jsonPath("$.data[0].unread").value(false))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.counts.OPEN").value(1));

        verify(loginIssueService).listMine(eq(username), eq("OPEN"), eq(0), eq(20));
        verify(loginIssueService, never()).listMine(eq("baskasi"), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("Kapsamlı müdür (AD ADMIN) yönetici listesine giremez ama KENDİ bildirimini yazar ve 'Bildirimlerim'i görür (2026-09-28)")
    void scopedAdmin_ownReportsStayOpen() throws Exception {
        session.setAttribute("systemRole", "ADMIN");
        session.setAttribute("viewTeamIds", java.util.List.of(2L));
        session.setAttribute("manageTeamIds", java.util.List.of(2L));
        when(appUserRepository.findByUsername(username)).thenReturn(Optional.of(userWithEmail("mudur@example.com")));
        when(loginIssueService.listMine(eq(username), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(mine(1L, "OPEN"))));
        when(loginIssueService.countsMine(username)).thenReturn(java.util.Map.of("OPEN", 1L));
        when(loginIssueService.publicCommentCounts(any())).thenReturn(java.util.Map.of());

        mvc.perform(post("/api/issue-reports").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Grafik boş\"}"))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/issue-reports/mine").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].refCode").value("LIR-2026-000001"));
    }

    @Test
    @DisplayName("GET mine/{id}: BAŞKASININ raporu 404 (403 değil — varlık sızmaz); görüldü damgası vurulmaz")
    void mineDetail_otherUsersReportIs404() throws Exception {
        when(loginIssueService.getMine(7L, username)).thenReturn(Optional.empty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/issue-reports/mine/7").session(session))
                .andExpect(status().isNotFound());
        verify(loginIssueService, never()).markSeenByReporter(any());
    }

    @Test
    @DisplayName("GET mine/{id}: yalnız HERKESE AÇIK satırlar (publicComments); 'internal' alanı yanıtta hiç yok; zaman çizelgesi; görüldü damgası")
    void mineDetail_publicOnlyWithTimelineAndSeen() throws Exception {
        LoginIssueReport r = mine(7L, "IN_PROGRESS");
        when(loginIssueService.getMine(7L, username)).thenReturn(Optional.of(r));
        when(loginIssueService.images(7L)).thenReturn(java.util.List.of());
        when(loginIssueService.publicComments(7L)).thenReturn(java.util.List.of(
                comment(1L, "COMMENT", "someadmin", false, false, "herkese açık yanıt"),
                comment(2L, "STATUS", "someadmin", false, false, "IN_PROGRESS")));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/issue-reports/mine/7").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refCode").value("LIR-2026-000007"))
                .andExpect(jsonPath("$.data.comments.length()").value(1))
                .andExpect(jsonPath("$.data.comments[0].body").value("herkese açık yanıt"))
                .andExpect(jsonPath("$.data.comments[0].byReporter").value(false))
                .andExpect(jsonPath("$.data.comments[0].internal").doesNotExist())
                .andExpect(jsonPath("$.data.timeline.length()").value(2))
                .andExpect(jsonPath("$.data.timeline[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data.timeline[0].byReporter").value(true))
                .andExpect(jsonPath("$.data.timeline[1].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.timeline[1].by").value("someadmin"))
                .andExpect(jsonPath("$.data.ipAddress").doesNotExist())
                .andExpect(jsonPath("$.data.autoContextJson").doesNotExist());

        verify(loginIssueService).markSeenByReporter(7L);
        verify(loginIssueService, never()).comments(any());   // iç notları da döndüren YÖNETİCİ metodu asla çağrılmaz
    }

    @Test
    @DisplayName("POST mine/{id}/comments: başkasının raporuna yorum 404 — hiçbir şey yazılmaz, audit yok")
    void mineComment_otherUsersReportIs404() throws Exception {
        when(loginIssueService.getMine(7L, username)).thenReturn(Optional.empty());
        mvc.perform(post("/api/issue-reports/mine/7/comments").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}"))
                .andExpect(status().isNotFound());
        verify(loginIssueService, never()).addComment(any(), any(), any(), anyBoolean(), anyBoolean(), any());
        verify(auditService, never()).recordAction(anyString(), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("POST mine/{id}/comments: boş → 400, 4001 karakter → 400; servis çağrılmaz")
    void mineComment_lengthLimits() throws Exception {
        when(loginIssueService.getMine(7L, username)).thenReturn(Optional.of(mine(7L, "OPEN")));
        mvc.perform(post("/api/issue-reports/mine/7/comments").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/issue-reports/mine/7/comments").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + "x".repeat(4001) + "\"}"))
                .andExpect(status().isBadRequest());
        verify(loginIssueService, never()).addComment(any(), any(), any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    @DisplayName("POST mine/{id}/comments (RESOLVED → yeniden açılır): reopened=true, COMMENT + REOPEN audit'i, internal isteği yok sayılır, kendine mail YOK")
    void mineComment_reopensAndAudits() throws Exception {
        when(loginIssueService.getMine(7L, username)).thenReturn(Optional.of(mine(7L, "RESOLVED")));
        when(loginIssueService.addComment(eq(7L), eq(username), anyString(), eq(false), eq(true), eq("devam ediyor")))
                .thenReturn(new LoginIssueService.CommentResult(comment(5L, "COMMENT", username, false, true, "devam ediyor"), true));

        mvc.perform(post("/api/issue-reports/mine/7/comments").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"devam ediyor\",\"internal\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reopened").value(true))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.comment.byReporter").value(true))
                .andExpect(jsonPath("$.data.comment.body").value("devam ediyor"));

        verify(auditService).recordAction(eq("ISSUE_REPORT_COMMENT"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("LOGIN_ISSUE"), eq("7"), anyString());
        verify(auditService).recordAction(eq("ISSUE_REPORT_REOPEN"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("LOGIN_ISSUE"), eq("7"), anyString());
        org.mockito.Mockito.verifyNoInteractions(loginIssueMailService);   // bildirenin kendi yorumu ona mail üretmez
    }

    @Test
    @DisplayName("POST mine/{id}/comments (OPEN): reopened=false, yalnız COMMENT audit'i; mail yok")
    void mineComment_openNoReopen() throws Exception {
        when(loginIssueService.getMine(7L, username)).thenReturn(Optional.of(mine(7L, "OPEN")));
        when(loginIssueService.addComment(eq(7L), eq(username), anyString(), eq(false), eq(true), eq("ek bilgi")))
                .thenReturn(new LoginIssueService.CommentResult(comment(6L, "COMMENT", username, false, true, "ek bilgi"), false));

        mvc.perform(post("/api/issue-reports/mine/7/comments").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"ek bilgi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reopened").value(false))
                .andExpect(jsonPath("$.data.status").value("OPEN"));

        verify(auditService).recordAction(eq("ISSUE_REPORT_COMMENT"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("LOGIN_ISSUE"), eq("7"), anyString());
        verify(auditService, never()).recordAction(eq("ISSUE_REPORT_REOPEN"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(), anyString());
        org.mockito.Mockito.verifyNoInteractions(loginIssueMailService);
    }
}
