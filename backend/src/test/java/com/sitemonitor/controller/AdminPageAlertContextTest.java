package com.sitemonitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.MonitoringOutageService;
import com.sitemonitor.service.SchedulerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * E2/E3 (2026-11-12): sayfa bütünlüğü alarm detayında SORUNLU KAYNAKLAR (problem_rows → yapısal liste) ve TEYİT/
 * KURTARMA ayarları — alarm bağlamından güvenli ayrıştırılır; yönlendirme/alıcı bağlamı SIZMAZ.
 */
class AdminPageAlertContextTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static String ctxJson(Map<String, Object> ctx) throws Exception {
        return M.writeValueAsString(ctx);   // problem_rows içindeki tab/newline'ı Jackson kaçırır — elle kaçış yok
    }

    @Test
    @DisplayName("E2: problem_rows → tür/URL/HTTP; boş HTTP atlanır; '… +N daha' sayaç satırı")
    void pageIssuesFromRows() throws Exception {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("problem_rows", "BROKEN\thttps://x/a.js\t404\nTIMEOUT\thttps://x/b.png\t\n… +3 daha");
        Map<String, Object> parsed = AdminController.parsePageAlertContext(ctxJson(ctx));
        List<Map<String, Object>> rows = AdminController.pageIssuesFromContext(parsed);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("issue_type", "BROKEN")
                .containsEntry("resource_url", "https://x/a.js").containsEntry("http_status", 404);
        assertThat(rows.get(1)).containsEntry("issue_type", "TIMEOUT")
                .containsEntry("resource_url", "https://x/b.png").doesNotContainKey("http_status");
        assertThat(rows.get(2)).containsKey("more");
    }

    @Test
    @DisplayName("E3: teyit/kurtarma ayarları bağlamdan okunur (ms → long)")
    void confirmInfo() throws Exception {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_confirm_attempts", 3);
        ctx.put("monitor_confirm_interval_ms", 30000);
        ctx.put("monitor_recovery_checks", 3);
        ctx.put("monitor_recovery_interval_ms", 30000);
        Map<String, Object> ci = AdminController.confirmInfoFromContext(AdminController.parsePageAlertContext(ctxJson(ctx)));
        assertThat(ci).containsEntry("confirm_attempts", 3L).containsEntry("confirm_interval_ms", 30000L)
                .containsEntry("recovery_checks", 3L).containsEntry("recovery_interval_ms", 30000L);
    }

    @Test
    @DisplayName("Güvenlik: bozuk/boş JSON → boş; problem_rows yok → boş; yönlendirme alanları SIZMAZ")
    void safety() throws Exception {
        assertThat(AdminController.parsePageAlertContext(null)).isEmpty();
        assertThat(AdminController.parsePageAlertContext("")).isEmpty();
        assertThat(AdminController.parsePageAlertContext("{bozuk")).isEmpty();
        assertThat(AdminController.pageIssuesFromContext(Map.of())).isEmpty();
        assertThat(AdminController.confirmInfoFromContext(Map.of())).isEmpty();
        // Yönlendirme/alıcı bağlamı confirm_info'ya GİRMEZ — yalnız teyit/kurtarma alanları.
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("team_id", 5);
        ctx.put("notification_group_id", 7);
        ctx.put("monitor_confirm_attempts", 3);
        Map<String, Object> ci = AdminController.confirmInfoFromContext(AdminController.parsePageAlertContext(ctxJson(ctx)));
        assertThat(ci).containsOnlyKeys("confirm_attempts");
    }

    // ── 2026-11-13 hata taraması düzeltmeleri ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Bulgu 6: alarm detayı toplam sorun sayısını taşır (page_issues_total) — arayüz '+N kaynak daha' der; "
            + "kaynak satırı yoksa toplam da yok")
    void pageAlertDetailCarriesTotal() throws Exception {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("problem_rows", "BROKEN\thttps://x/a.js\t404\nSLOW\thttps://x/b.png\t200");
        ctx.put("problem_total", 60);
        Map<String, Object> d = AdminController.pageAlertDetail(AdminController.parsePageAlertContext(ctxJson(ctx)));
        assertThat(d).containsEntry("page_issues_total", 60);
        assertThat((List<?>) d.get("page_issues")).hasSize(2);
        assertThat(AdminController.pageAlertDetail(Map.of("problem_total", 5))).doesNotContainKey("page_issues_total");
    }

    @SuppressWarnings("unchecked")
    private static <T> T build(Class<T> type, Map<Class<?>, Object> provided) throws Exception {
        Constructor<?> c = java.util.Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = provided.containsKey(pt[i]) ? provided.get(pt[i]) : mock(pt[i]);
        return (T) c.newInstance(args);
    }

    /**
     * Bulgu 3 (E3 üretimde görünmüyordu): bağlam ÜRETİMİN yazdığı yoldan kurulur — tarama kalemi (SchedulerService
     * .addPageSweepItems) → MOS'un teyit bağlamı (kalem bağlamı aynen) → EscalationService.snapshotContext (alarm olayına
     * yazılan JSON) → alarm detayının ayrıştırıcısı. Teyit / kurtarma anahtarları anlık görüntü listesinde değilken
     * confirm_info HER ZAMAN boştu; eski testler bağlamı elle kurduğu için göremiyordu.
     */
    @Test
    @DisplayName("Bulgu 3: teyit / kurtarma bilgisi üretim yolundan (snapshotContext) geçip alarm detayına ulaşır")
    void confirmInfoSurvivesTheProductionSnapshot() throws Exception {
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(AppSettingsService.class, appSettings);
        provided.put(tools.jackson.databind.ObjectMapper.class, new tools.jackson.databind.ObjectMapper());
        EscalationService escalation = build(EscalationService.class, provided);
        provided.put(EscalationService.class, escalation);
        SchedulerService scheduler = build(SchedulerService.class, provided);

        PageMonitor m = new PageMonitor();
        m.setId(41L); m.setName("Ödeme sayfası"); m.setUrl("https://pay.example.com/"); m.setTeamId(3L);
        m.setConfirmAttempts(4); m.setConfirmIntervalSeconds(45); m.setRecoveryChecks(2); m.setRecoveryIntervalSeconds(60);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("main_up", true); raw.put("integrity_up", false); raw.put("check_mode", "SINGLE_PAGE");
        raw.put("broken_resources", 1); raw.put("problem_rows", "BROKEN\thttps://pay.example.com/a.js\t404");
        raw.put("problem_total", 1);
        List<MonitoringOutageService.SweepItem> down = new ArrayList<>(), integrity = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(scheduler, "addPageSweepItems", m, raw, down, integrity, true);
        assertThat(integrity).hasSize(1);

        Map<String, Object> outage = new LinkedHashMap<>(integrity.get(0).ctxExtra());   // MOS buildOutageContext: putAll
        String json = ReflectionTestUtils.invokeMethod(escalation, "snapshotContext", outage);
        Map<String, Object> detail = AdminController.pageAlertDetail(AdminController.parsePageAlertContext(json));
        @SuppressWarnings("unchecked")
        Map<String, Object> ci = (Map<String, Object>) detail.get("confirm_info");
        assertThat(ci).as("teyit / kurtarma ayarları anlık görüntüye girmeli").isNotNull()
                .containsEntry("confirm_attempts", 4L).containsEntry("confirm_interval_ms", 45000L)
                .containsEntry("recovery_checks", 2L).containsEntry("recovery_interval_ms", 60000L);
        assertThat(detail).containsKey("page_issues").containsEntry("page_issues_total", 1);
        // Yönlendirme bağlamı confirm_info'ya GİRMEZ
        assertThat(ci).containsOnlyKeys("confirm_attempts", "confirm_interval_ms", "recovery_checks", "recovery_interval_ms");
    }
}
