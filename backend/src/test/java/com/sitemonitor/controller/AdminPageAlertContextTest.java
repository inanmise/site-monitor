package com.sitemonitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
