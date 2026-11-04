package com.sitemonitor.controller;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.page.scan.PageResourceScanService;
import com.sitemonitor.service.page.scan.budget.ResourceBudget;
import com.sitemonitor.service.page.scan.budget.ResourceBudgetService;
import com.sitemonitor.service.page.scan.budget.ResourceTrendService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kaynak analizi bütçesi + eğilim uçları (F1 / F2): okuma = analiz listesinin kapısı (göremediği izleme 404), yazma = izlemeyi
 * düzenleme kapısı ({@code monitoring.crud/edit} + takımda işletme — EDIT paylaşımı dahil, AUDIT / yalnız görüntüleyen 403);
 * geçersiz alan 400 {@code VALIDATION_FAILED} + {@code field} (yazım / denetim yok); değişiklik yoksa denetim yok; liste satırları
 * ve tek analiz GÜNCEL bütçeyle işaretlenir.
 */
@WebMvcTest(PageResourceScanController.class)
class PageResourceBudgetControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean PageMonitorRepository pageMonitorRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean PageResourceScanService scans;
    @MockitoBean AuditService auditService;
    @MockitoBean ResourceBudgetService budgets;
    @MockitoBean ResourceTrendService trends;

    PageMonitor monitor;

    @BeforeEach
    void defaults() {
        monitor = PageResourceScanControllerTest.page(61L, 5L, "https://shop.example.test/?token=GIZLI99");
        monitor.setResourceBudgetJson("{\"max_page_kb\":500}");
        when(pageMonitorRepo.findById(61L)).thenReturn(Optional.of(monitor));
        when(pageMonitorRepo.findById(404L)).thenReturn(Optional.empty());
        when(permissionService.allows(any(HttpSession.class), eq("monitoring.crud"), eq("edit"))).thenReturn(true);
        // Kaydetme: gerçek bütçe kuralı (doğrulama + değişti mi) — servis yazımı ResourceBudgetServiceTest'te
        when(budgets.save(anyLong(), any(), anyMap())).thenAnswer(a -> {
            ResourceBudget before = ResourceBudget.parse(a.getArgument(1));
            ResourceBudget after = before.withChanges(a.getArgument(2));
            return new ResourceBudgetService.Save(after.equals(before) ? ResourceBudgetService.Outcome.UNCHANGED
                    : ResourceBudgetService.Outcome.SAVED, before, after);
        });
        when(budgets.annotate(anyLong(), any(), anyList())).thenReturn(true);
    }

    static MockHttpSession user(long teamId) {
        return PageResourceScanControllerTest.session("USER", teamId, 2L);
    }

    @Test
    @DisplayName("GET bütçe: yedi sınır + defined + can_edit + aralıklar; AUDIT okur ama düzenleyemez; başka takım 404")
    void getBudget() throws Exception {
        mvc.perform(get("/api/monitoring/page/61/resource-budget").session(user(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.budget.max_page_kb").value(500))
                .andExpect(jsonPath("$.data.budget.min_grade").doesNotExist())
                .andExpect(jsonPath("$.data.defined").value(true))
                .andExpect(jsonPath("$.data.can_edit").value(true))
                .andExpect(jsonPath("$.data.ranges.max_page_kb.min").value(50))
                .andExpect(jsonPath("$.data.ranges.max_page_kb.max").value(51200))
                .andExpect(jsonPath("$.data.ranges.max_page_kb.unit").value("kb"))
                .andExpect(jsonPath("$.data.ranges.min_grade.kind").value("min"))
                .andExpect(jsonPath("$.data.changed").doesNotExist());
        mvc.perform(get("/api/monitoring/page/61/resource-budget").session(PageResourceScanControllerTest.session("AUDIT", null, 4L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_edit").value(false));
        mvc.perform(get("/api/monitoring/page/61/resource-budget").session(user(9L))).andExpect(status().isNotFound());
        mvc.perform(get("/api/monitoring/page/404/resource-budget").session(user(5L))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT: yalnız gönderilen alan değişir; denetim PAGE_RESOURCE_BUDGET_SAVE farkla (URL sorgusuz); changed:true")
    void putSaves() throws Exception {
        mvc.perform(put("/api/monitoring/page/61/resource-budget").session(user(5L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"min_grade\":70,\"unknown\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.budget.max_page_kb").value(500))
                .andExpect(jsonPath("$.data.budget.min_grade").value(70))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.can_edit").value(true));
        verify(budgets).save(eq(61L), eq("{\"max_page_kb\":500}"), anyMap());
        verify(auditService).recordAction(eq("PAGE_RESOURCE_BUDGET_SAVE"), any(HttpSession.class), eq("PAGE_MONITOR"), eq("61"),
                argThat((String d) -> d.contains("shop.example.test") && !d.contains("GIZLI99")),
                argThat((String c) -> c != null && c.contains("min_grade") && c.contains("70") && !c.contains("max_page_kb")));
    }

    @Test
    @DisplayName("PUT: değişiklik yoksa changed:false, denetim yok; boş gövde de değişiklik değildir")
    void putUnchanged() throws Exception {
        mvc.perform(put("/api/monitoring/page/61/resource-budget").session(user(5L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"max_page_kb\":500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(false));
        mvc.perform(put("/api/monitoring/page/61/resource-budget").session(user(5L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(false));
        verify(auditService, never()).recordAction(eq("PAGE_RESOURCE_BUDGET_SAVE"), any(HttpSession.class), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("PUT: geçersiz değer 400 VALIDATION_FAILED + field (aralık dışı / metin / kesirli); denetim yok")
    void putInvalid() throws Exception {
        for (String body : List.of("{\"max_page_kb\":10}", "{\"max_page_kb\":\"500\"}", "{\"max_page_kb\":500.5}",
                "{\"max_page_kb\":true}")) {
            mvc.perform(put("/api/monitoring/page/61/resource-budget").session(user(5L)).contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.field").value("max_page_kb"));
        }
        verify(auditService, never()).recordAction(eq("PAGE_RESOURCE_BUDGET_SAVE"), any(HttpSession.class), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("PUT kapıları: başka takım 404; AUDIT (işletemez) 403; düzenleme izni yok 403; EDIT paylaşımı yazabilir")
    void putGates() throws Exception {
        String body = "{\"min_grade\":70}";
        mvc.perform(put("/api/monitoring/page/61/resource-budget").session(user(9L)).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isNotFound());
        mvc.perform(put("/api/monitoring/page/404/resource-budget").session(user(5L)).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isNotFound());
        mvc.perform(put("/api/monitoring/page/61/resource-budget").session(PageResourceScanControllerTest.session("AUDIT", null, 4L))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        // Takım erişim paylaşımı: 9. takımın kullanıcısı 5. takımda EDIT paylaşımı aldı → yazar
        MockHttpSession grantee = user(9L);
        grantee.setAttribute("grantViewTeamIds", List.of(5L));
        grantee.setAttribute("grantExecTeamIds", List.of(5L));
        grantee.setAttribute("grantEditTeamIds", List.of(5L));
        mvc.perform(put("/api/monitoring/page/61/resource-budget").session(grantee).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk());
        // VIEW paylaşımı okur ama yazamaz
        MockHttpSession viewer = user(9L);
        viewer.setAttribute("grantViewTeamIds", List.of(5L));
        mvc.perform(get("/api/monitoring/page/61/resource-budget").session(viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.can_edit").value(false));
        mvc.perform(put("/api/monitoring/page/61/resource-budget").session(viewer).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isForbidden());
        doThrow(new SecurityException("yok")).when(permissionService)
                .require(any(HttpSession.class), eq("monitoring.crud"), eq("edit"));
        mvc.perform(put("/api/monitoring/page/61/resource-budget").session(user(5L)).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isForbidden());
        verify(budgets).save(anyLong(), any(), anyMap());   // yalnız paylaşım sahibinin çağrısı
    }

    @Test
    @DisplayName("PUT: izleme arada silindi 404; eş zamanlı yazımlar tükenince 409 CONCURRENT_MODIFICATION — denetim yok")
    void putNotFoundAndConflict() throws Exception {
        when(budgets.save(anyLong(), any(), anyMap())).thenReturn(new ResourceBudgetService.Save(
                ResourceBudgetService.Outcome.NOT_FOUND, ResourceBudget.EMPTY, ResourceBudget.EMPTY));
        mvc.perform(put("/api/monitoring/page/61/resource-budget").session(user(5L)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"min_grade\":70}")).andExpect(status().isNotFound());
        when(budgets.save(anyLong(), any(), anyMap())).thenReturn(new ResourceBudgetService.Save(
                ResourceBudgetService.Outcome.CONFLICT, ResourceBudget.EMPTY, ResourceBudget.EMPTY));
        mvc.perform(put("/api/monitoring/page/61/resource-budget").session(user(5L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"min_grade\":70}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        verify(auditService, never()).recordAction(eq("PAGE_RESOURCE_BUDGET_SAVE"), any(HttpSession.class), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("liste: GÜNCEL bütçe işaretlemeye gider, yanıt bütçe özetini taşır; okunamazsa budget_unreadable")
    void listAnnotates() throws Exception {
        Map<String, Object> list = new LinkedHashMap<>();
        list.put("items", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("id", 900)))));
        list.put("running", null);
        when(scans.list(eq(61L), anyInt())).thenReturn(list);
        mvc.perform(get("/api/monitoring/page/61/resource-scans").session(user(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.budget.defined").value(true))
                .andExpect(jsonPath("$.data.budget.max_page_kb").value(500))
                .andExpect(jsonPath("$.data.budget_unreadable").doesNotExist());
        verify(budgets).annotate(eq(61L), argThat(b -> b != null && Integer.valueOf(500).equals(b.limit("max_page_kb"))), anyList());
        when(budgets.annotate(anyLong(), any(), anyList())).thenReturn(false);
        mvc.perform(get("/api/monitoring/page/61/resource-scans").session(user(5L)))
                .andExpect(jsonPath("$.data.budget_unreadable").value(true));
    }

    @Test
    @DisplayName("tek analiz: budget alanı servisten (GÜNCEL bütçe, o analizin kimliği)")
    void oneCarriesBudget() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scan", Map.of("id", 900, "status", "DONE"));
        when(scans.one(900L, 61L)).thenReturn(data);
        when(budgets.evaluateOne(eq(61L), any(), eq(900L))).thenReturn(Map.of("defined", true, "status", "exceeded", "exceeded", 1));
        mvc.perform(get("/api/monitoring/page/61/resource-scans/900").session(user(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scan.status").value("DONE"))
                .andExpect(jsonPath("$.data.budget.status").value("exceeded"))
                .andExpect(jsonPath("$.data.budget.exceeded").value(1));
    }

    @Test
    @DisplayName("eğilim: okuma kapısı; gün değeri 30/90/365 (başkası 90); başka takım 404, servis çağrılmaz")
    void trendGatesAndDays() throws Exception {
        when(trends.trend(eq(61L), anyInt(), any())).thenReturn(Map.of("days", 30, "points", List.of(), "growth", List.of()));
        mvc.perform(get("/api/monitoring/page/61/resource-scans/trend?days=30").session(user(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(30));
        verify(trends).trend(eq(61L), eq(30), any());
        mvc.perform(get("/api/monitoring/page/61/resource-scans/trend?days=7").session(user(5L))).andExpect(status().isOk());
        verify(trends).trend(eq(61L), eq(90), any());
        mvc.perform(get("/api/monitoring/page/61/resource-scans/trend").session(user(9L))).andExpect(status().isNotFound());
        doThrow(new SecurityException("yok")).when(permissionService)
                .require(any(HttpSession.class), eq("monitoring.read"), eq("view"));
        mvc.perform(get("/api/monitoring/page/61/resource-scans/trend").session(user(5L))).andExpect(status().isForbidden());
        verify(scans, never()).one(anyLong(), anyLong());   // /trend tek tarama yoluna düşmez
    }
}
