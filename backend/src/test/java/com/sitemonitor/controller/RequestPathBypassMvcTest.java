package com.sitemonitor.controller;

import com.sitemonitor.repository.ActivityLogRepository;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BK1 (KRİTİK, bug regresyon 2026-09-27) — uçtan uca kapı: gerçek {@code WebConfig} kablolaması (filtre
 * zinciri + AuthInterceptor + DispatcherServlet) üzerinden, ham yolu "/api/" ile BAŞLAMAYAN ama yönlendiricinin
 * korumalı uca götürdüğü istekler ASLA 200 almaz. Düzeltmeden önce {@code GET /api;x/activity} oturumsuz 200
 * dönüyordu (kimlik kapısı atlanıyordu). Korumalı uç olarak oturumdan başka kapısı olmayan
 * {@link ActivityController} seçildi — kapı atlanırsa 200 döner, yani test gerçekten ısırır.
 */
@WebMvcTest(ActivityController.class)
class RequestPathBypassMvcTest {

    @Autowired MockMvc mvc;

    // Web slice ortak bean'leri (AuthInterceptor) + controller bağımlılığı.
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.AuditService auditService;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean ActivityLogRepository repo;

    /** Ham yolu olduğu gibi taşıyan istek (şablon genişletmesi / yeniden kodlama yok). */
    private static URI raw(String path) {
        return URI.create("http://localhost" + path);
    }

    private static MockHttpSession user() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(1L)));
        return s;
    }

    @Test
    @DisplayName("KAPI: güvensiz ham yol (matris / kodlu nokta / çift eğik çizgi) → 400; işleyici HİÇ çalışmaz")
    void unsafeRawPaths_neverReachHandler() throws Exception {
        for (String p : new String[]{"/api;x/activity", "/api/activity;x", "/api;jsessionid=1/activity",
                "/api/%2e%2e/api/activity", "/api/%2E/activity", "//api/activity", "/api//activity"}) {
            MvcResult r = mvc.perform(get(raw(p))).andReturn();
            assertThat(r.getResponse().getStatus()).as(p).isEqualTo(400);
        }
        verify(repo, never()).findFiltered(anyBoolean(), anyList(), anyBoolean(), anyList(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    @DisplayName("KAPI: yüzde kodlu harfli yol (/%61pi/...) güvenlik duvarından geçer ama kimlik kapısı normalize yolla 401 verir")
    void percentEncodedLetters_stillAuthenticated() throws Exception {
        mvc.perform(get(raw("/%61pi/activity"))).andExpect(status().isUnauthorized());
        mvc.perform(get(raw("/%61%70%69/activity/summary"))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("KAPI: durum değiştiren matris yollu istek (kardeş köken) de 400 — CSRF katmanı atlanamaz")
    void unsafeWrite_rejected() throws Exception {
        MvcResult r = mvc.perform(post(raw("/api;x/activity")).header("Origin", "https://evil.example.com"))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("Normal yol etkilenmez: oturumsuz 401, oturumlu 200; API dışı yol 400 almaz")
    void normalPaths_unaffected() throws Exception {
        when(repo.findFiltered(anyBoolean(), anyList(), anyBoolean(), anyList(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        mvc.perform(get(raw("/api/activity"))).andExpect(status().isUnauthorized());
        mvc.perform(get(raw("/api/activity")).session(user())).andExpect(status().isOk());
        mvc.perform(get(raw("/api/activity?q=a;b&from=%2e%2e")).session(user())).andExpect(status().isOk());

        // Bu dilimde statik kaynak / sağlık ucu yok → 404 beklenir; önemli olan 400 (duvar) OLMAMASI.
        for (String p : new String[]{"/health", "/assets/index-abc123.js", "/"}) {
            assertThat(mvc.perform(get(raw(p))).andReturn().getResponse().getStatus()).as(p).isNotEqualTo(400);
        }
    }
}
