package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.techerror.TechErrorApprovalService;
import com.sitemonitor.service.techerror.TechErrorApprovalService.TokenStatus;
import com.sitemonitor.service.techerror.TechErrorReportSender;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.techerror.TechErrorValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PO onayı e-posta bağlantısı (2026-10-17, S6): girişsiz sayfa, sebepli red sayfaları, karar + denetim, sınır, bakım. */
@WebMvcTest(TechErrorApprovalLinkController.class)
class TechErrorApprovalLinkControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean AuditService auditService;
    @MockitoBean TechErrorApprovalService approvals;
    @MockitoBean TeamRepository teams;

    private static TokenStatus valid() {
        TechErrorReport r = new TechErrorReport();
        r.setId(100L);
        r.setTeamId(1L);
        r.setChannelId(10L);
        r.setReportYear(2026);
        r.setReportMonth(9);
        r.setTemplateJson(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
        r.setValuesJson(TechErrorValues.toJson(TechErrorValues.Values.empty()));
        r.setApprovalRequestedById(5L);
        TechErrorChannel ch = new TechErrorChannel();
        ch.setId(10L);
        ch.setTeamId(1L);
        ch.setName("Mobil");
        AppUser u = new AppUser();
        u.setId(7L);
        u.setUsername("po1");
        u.setDisplayName("PO Bir");
        u.setTeamId(1L);
        return new TokenStatus(true, null, null, r, ch, u);
    }

    @Test
    @DisplayName("geçerli bağlantı: girişsiz 200 HTML — rapor başlığı, takım · kanal · dönem, isteyen, onay ve red formları (betiksiz), no-store")
    void page() throws Exception {
        when(approvals.status("abc")).thenReturn(valid());
        when(approvals.displayNameOf(5L)).thenReturn("Ayşe");
        Team t = new Team();
        t.setId(1L);
        t.setName("Dijital");
        when(teams.findById(1L)).thenReturn(Optional.of(t));
        mvc.perform(get("/api/tech-error-reports/approve-link").param("token", "abc"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("Mobil Kanalı Teknik Hata Mesajları - Eylül 2026")))
                .andExpect(content().string(containsString("Dijital")))
                .andExpect(content().string(containsString("Eylül 2026")))
                .andExpect(content().string(containsString("Ayşe")))
                .andExpect(content().string(containsString("PO Bir")))
                .andExpect(content().string(containsString("action='/api/tech-error-reports/approve-link/confirm'")))
                .andExpect(content().string(containsString("action='/api/tech-error-reports/approve-link/reject'")))
                .andExpect(content().string(containsString("value='abc'")))
                .andExpect(content().string(not(containsString("<script"))));
        verify(auditService, never()).recordSecurityEvent(anyString(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("geçersiz bağlantı: sebebe göre sayfa (kullanıldı / süresi doldu / eskidi / bekleyen yok / onaylayıcı pasif / izin kapalı (A1) / yerine geçti (A2) / geçersiz) + güvenlik olayı; karar yazılmaz")
    void deniedReasons() throws Exception {
        String[][] cases = {
                { "used", "Bağlantı kullanıldı" }, { "expired", "süresi doldu" }, { "stale", "Rapor değişti" },
                { "not_pending", "Bekleyen istek yok" }, { "approver_inactive", "Onay yetkiniz yok" },
                // A1: uygulama içi kapılar (okuma + onay izni, modül görünürlüğü) — sayfa sebebi söyler (üç durumda da doğru
                // metin), onay / red formu yok, belirteç tüketilmez (servis status() ile eler)
                { "no_permission", "hesabınızın rolü için teknik hata raporu okuma ya da onay izni kapalı, ya da modül takımınıza kapatılmış" },
                // A2: yeniden istek — eski bağlantı "geçersiz" değil, "en son e-postadaki bağlantıyı kullanın"; yeni postanın
                // ulaşmamış olabileceği için uygulama içi yol da söylenir (inceleme 2026-10-17)
                { "superseded", "daha yeni bir onay isteği gönderildi; lütfen en son e-postadaki bağlantıyı kullanın. Postayı bulamıyorsanız raporu Site Monitor&#39;de açıp kararınızı oradan verebilirsiniz." },
                // kullanıcı adı ön eki (2026-10-21 inceleme): girişe kapalı hesap — kendi sebebi, belirteç tüketilmez
                { "username_prefix", "yalnız izinli ön ekle başlayan hesaplar girebilir" },
                { "not_found", "Bağlantı geçersiz" } };
        for (int i = 0; i < cases.length; i++) {
            String[] c = cases[i];
            // Her sebep kendi istemci IP'sinden: 9 × 3 istek, denetleyicinin IP başına dakikalık sınırı (30) aynı fasulyede
            // sınıfın diğer testleriyle paylaşılır — sınır üretim davranışı, test onu tetiklemez
            String ip = "10.9.0." + (i + 1);
            when(approvals.status("t-" + c[0])).thenReturn(new TokenStatus(false, c[0], null, null, null, null));
            mvc.perform(get("/api/tech-error-reports/approve-link").param("token", "t-" + c[0]).with(r -> { r.setRemoteAddr(ip); return r; }))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(c[1])))
                    .andExpect(content().string(not(containsString("action='/api/tech-error-reports/approve-link/confirm'"))))
                    .andExpect(content().string(not(containsString("action='/api/tech-error-reports/approve-link/reject'"))));
            mvc.perform(post("/api/tech-error-reports/approve-link/confirm").param("token", "t-" + c[0]).with(r -> { r.setRemoteAddr(ip); return r; }))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(c[1])));
            // Red de aynı sebeple durur (not verilmiş olsa bile)
            mvc.perform(post("/api/tech-error-reports/approve-link/reject").param("token", "t-" + c[0]).param("note", "Eksik")
                            .with(r -> { r.setRemoteAddr(ip); return r; }))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(c[1])));
        }
        // 9 sebep × (sayfa + onay + red) = 27 güvenlik olayı; sebep ayrıntıda ("no_permission (approve)" gibi)
        verify(auditService, org.mockito.Mockito.times(27)).recordSecurityEvent(eq("TECH_ERROR_APPROVAL_LINK_DENIED"), any(), isNull(),
                eq("TECH_ERROR_REPORT"), anyString(), anyString());
        verify(auditService).recordSecurityEvent(eq("TECH_ERROR_APPROVAL_LINK_DENIED"), any(), isNull(), eq("TECH_ERROR_REPORT"),
                anyString(), eq("no_permission (approve)"));
        verify(auditService).recordSecurityEvent(eq("TECH_ERROR_APPROVAL_LINK_DENIED"), any(), isNull(), eq("TECH_ERROR_REPORT"),
                anyString(), eq("superseded (reject)"));
        verify(auditService).recordSecurityEvent(eq("TECH_ERROR_APPROVAL_LINK_DENIED"), any(), isNull(), eq("TECH_ERROR_REPORT"),
                anyString(), eq("username_prefix (approve)"));
        verify(approvals, never()).decideViaToken(anyString(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("inceleme 2026-10-18: onay akışı kaldırılmış kanalın bağlantısı güvenlik olayı DEĞİL — nötr tonlu 'onay gerekmiyor' sayfası (kırmızı değil), nötr bilgi kaydı TECH_ERROR_APPROVAL_LINK_NOT_NEEDED; karar yazılmaz")
    void flowRemovedIsNeutral() throws Exception {
        when(approvals.status("t-flow")).thenReturn(new TokenStatus(false, "flow_removed", null, null, null, null));
        String ip = "10.9.1.1";
        mvc.perform(get("/api/tech-error-reports/approve-link").param("token", "t-flow").with(r -> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Onay gerekmiyor")))
                .andExpect(content().string(containsString("raporun onaya ihtiyacı kalmadı ve ekip onu takım adresine doğrudan gönderebilir")))
                .andExpect(content().string(containsString("Sizin bir şey yapmanız gerekmiyor.")))
                .andExpect(content().string(containsString(".strip{height:4px;background:" + TechErrorApprovalLinkController.NEUTRAL_ACCENT + "}")))
                .andExpect(content().string(not(containsString("#dc2626"))))
                .andExpect(content().string(not(containsString("action='/api/tech-error-reports/approve-link/confirm'"))));
        mvc.perform(post("/api/tech-error-reports/approve-link/confirm").param("token", "t-flow").with(r -> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Onay gerekmiyor")));
        mvc.perform(post("/api/tech-error-reports/approve-link/reject").param("token", "t-flow").param("note", "Eksik")
                        .with(r -> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Onay gerekmiyor")));
        verify(auditService, never()).recordSecurityEvent(anyString(), any(), any(), anyString(), anyString(), anyString());
        verify(auditService, org.mockito.Mockito.times(3)).recordTokenAction(eq("TECH_ERROR_APPROVAL_LINK_NOT_NEEDED"), any(),
                eq("email-approval"), isNull(), eq("TECH_ERROR_REPORT"), eq("email-token"), contains("flow_removed"));
        verify(auditService).recordTokenAction(eq("TECH_ERROR_APPROVAL_LINK_NOT_NEEDED"), any(), anyString(), any(), anyString(),
                anyString(), contains("reject"));
        verify(approvals, never()).decideViaToken(anyString(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("onay: karar e-posta belirteciyle, denetim TECH_ERROR_REPORT_APPROVE via=email_token; gönderim başarılıysa yeşil sayfa, başarısızsa 'yeniden gönderebilir' uyarısı; red notsuz → gerekçe sayfası, notla → REJECT")
    void decisions() throws Exception {
        when(approvals.status("ok")).thenReturn(valid());
        Team t = new Team();
        t.setId(1L);
        t.setName("Dijital");
        when(teams.findById(1L)).thenReturn(Optional.of(t));
        when(approvals.decideViaToken(eq("ok"), eq(t), eq(true), any())).thenReturn(new TechErrorApprovalService.Decision("APPROVED",
                new TechErrorReportSender.Result("SENT", "SENT", List.of("ceo@example.com"), 10, "Konu"), null, null));
        mvc.perform(post("/api/tech-error-reports/approve-link/confirm").param("token", "ok").param("note", "Uygun"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Onaylandı")))
                .andExpect(content().string(containsString("yönetici alıcılara gönderildi")));
        verify(auditService).recordAction(eq("TECH_ERROR_REPORT_APPROVE"), eq("po1"), eq(7L), eq(1L), any(), eq("TECH_ERROR_REPORT"),
                eq("100"), containsStr("email_token"), any(), any(), isNull());

        when(approvals.decideViaToken(eq("ok"), eq(t), eq(true), any())).thenReturn(new TechErrorApprovalService.Decision("APPROVED",
                null, "PDF_TOO_LARGE", null));
        mvc.perform(post("/api/tech-error-reports/approve-link/confirm").param("token", "ok"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("posta gönderilemedi")));

        mvc.perform(post("/api/tech-error-reports/approve-link/reject").param("token", "ok").param("note", "  "))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Gerekçe gerekli")));
        verify(approvals, never()).decideViaToken(anyString(), any(), eq(false), any());

        when(approvals.decideViaToken(eq("ok"), eq(t), eq(false), eq("Eksik"))).thenReturn(new TechErrorApprovalService.Decision("REJECTED", null, null, null));
        mvc.perform(post("/api/tech-error-reports/approve-link/reject").param("token", "ok").param("note", "Eksik"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reddedildi")));
        verify(auditService).recordAction(eq("TECH_ERROR_REPORT_REJECT"), eq("po1"), eq(7L), eq(1L), any(), eq("TECH_ERROR_REPORT"),
                eq("100"), containsStr("email_token"), any(), any(), isNull());

        // Karar sırasında geçersizleşen belirteç (ikinci tıklama): servis "used" der → kullanıldı sayfası + güvenlik olayı
        when(approvals.decideViaToken(eq("ok"), eq(t), eq(true), any())).thenThrow(new IllegalStateException("used"));
        mvc.perform(post("/api/tech-error-reports/approve-link/confirm").param("token", "ok"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bağlantı kullanıldı")));
        // Yarış: başka PO önce karar verdi → servis 409 → "karar kaydedilemedi" sayfası; güvenlik olayı DEĞİL, sonuçlu işlem kaydı
        when(approvals.decideViaToken(eq("ok"), eq(t), eq(true), any())).thenThrow(
                new com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException("APPROVAL_NOT_PENDING", "Bekleyen istek yok."));
        mvc.perform(post("/api/tech-error-reports/approve-link/confirm").param("token", "ok"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Karar kaydedilemedi")))
                .andExpect(content().string(containsString("Bekleyen istek yok.")));
        verify(auditService, never()).recordSecurityEvent(anyString(), any(), any(), anyString(), anyString(), containsStr("APPROVAL_NOT_PENDING"));
        verify(auditService).recordAction(eq("TECH_ERROR_REPORT_APPROVE"), eq("po1"), eq(7L), eq(1L), any(), eq("TECH_ERROR_REPORT"),
                eq("100"), containsStr("APPROVAL_NOT_PENDING"), any(), any(), isNull());
        // Not kuralı (1000+ karakter): bağlantı tüketilmez, sayfa sebebi söyler
        when(approvals.decideViaToken(eq("ok"), eq(t), eq(false), anyString())).thenThrow(
                new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException("note", "Not en çok 1000 karakter olabilir."));
        mvc.perform(post("/api/tech-error-reports/approve-link/reject").param("token", "ok").param("note", "x".repeat(1001)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Not en çok 1000 karakter olabilir.")));
    }

    @Test
    @DisplayName("eskimiş istek (içerik değişti): sayfa yalnız RED formunu gösterir ve nedenini söyler; onay POST'u belirteci tüketmeden reddedilir; red yine çalışır")
    void stalePage() throws Exception {
        TokenStatus v = valid();
        TokenStatus stale = new TokenStatus(true, null, null, v.report(), v.channel(), v.user(), true);
        when(approvals.status("st")).thenReturn(stale);
        Team t = new Team();
        t.setId(1L);
        t.setName("Dijital");
        when(teams.findById(1L)).thenReturn(Optional.of(t));
        mvc.perform(get("/api/tech-error-reports/approve-link").param("token", "st"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("yalnız reddedilebilir")))
                .andExpect(content().string(containsString("action='/api/tech-error-reports/approve-link/reject'")))
                .andExpect(content().string(not(containsString("action='/api/tech-error-reports/approve-link/confirm'"))));
        mvc.perform(post("/api/tech-error-reports/approve-link/confirm").param("token", "st"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Rapor değişti")));
        verify(approvals, never()).decideViaToken(anyString(), any(), eq(true), any());
        when(approvals.decideViaToken(eq("st"), eq(t), eq(false), eq("Eksik"))).thenReturn(new TechErrorApprovalService.Decision("REJECTED", null, null, null));
        mvc.perform(post("/api/tech-error-reports/approve-link/reject").param("token", "st").param("note", "Eksik"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reddedildi")));
    }

    @Test
    @DisplayName("bakım: sayfa ve karar bakım sayfası döner, karar yazılmaz; sınır: aynı IP'den dakikada 30'dan fazla istek 429")
    void maintenanceAndLimit() throws Exception {
        com.sitemonitor.service.SystemMaintenanceService m = org.mockito.Mockito.mock(com.sitemonitor.service.SystemMaintenanceService.class);
        when(m.isActive()).thenReturn(true);
        when(m.signalBody()).thenReturn(java.util.Map.of("error", "Bakım 22:00'ye kadar"));
        TechErrorApprovalLinkController c = new TechErrorApprovalLinkController(approvals, teams, auditService);
        c.setSystemMaintenance(m);
        jakarta.servlet.http.HttpServletRequest req = new org.springframework.mock.web.MockHttpServletRequest();
        org.assertj.core.api.Assertions.assertThat(c.page("abc", req).getBody()).contains("Sistem Bakımda").contains("Bakım 22:00");
        org.assertj.core.api.Assertions.assertThat(c.confirm("abc", null, req).getBody()).contains("Sistem Bakımda");
        verify(approvals, never()).decideViaToken(anyString(), any(), anyBoolean(), any());
        verify(approvals, never()).status(anyString());

        TechErrorApprovalLinkController lim = new TechErrorApprovalLinkController(approvals, teams, auditService);
        org.springframework.mock.web.MockHttpServletRequest ip = new org.springframework.mock.web.MockHttpServletRequest();
        ip.setRemoteAddr("10.0.0.9");
        for (int i = 0; i < TechErrorApprovalLinkController.PER_MINUTE; i++) org.assertj.core.api.Assertions.assertThat(lim.limited(ip)).isFalse();
        org.assertj.core.api.Assertions.assertThat(lim.limited(ip)).isTrue();
        org.springframework.mock.web.MockHttpServletRequest other = new org.springframework.mock.web.MockHttpServletRequest();
        other.setRemoteAddr("10.0.0.10");
        org.assertj.core.api.Assertions.assertThat(lim.limited(other)).isFalse();
        when(approvals.status("x")).thenReturn(new TokenStatus(false, "not_found", null, null, null, null));
        org.assertj.core.api.Assertions.assertThat(lim.page("x", ip).getStatusCode().value()).isEqualTo(429);
    }

    private static String containsStr(String part) {
        return org.mockito.ArgumentMatchers.argThat(s -> s != null && s.contains(part));
    }
}
