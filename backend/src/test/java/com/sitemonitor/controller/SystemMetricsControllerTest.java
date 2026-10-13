package com.sitemonitor.controller;

import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.metrics.SystemMetricsQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Metrik uçları (2026-10-13): kapı {@code system_health.read/view} SORGUDAN ÖNCE; pod parametresi temizlenir. */
class SystemMetricsControllerTest {

    private final PermissionService perms = mock(PermissionService.class);
    private final SystemMetricsQueryService query = mock(SystemMetricsQueryService.class);
    private final SystemMetricsController ctrl = new SystemMetricsController(perms, query);

    @Test
    @DisplayName("yetki yoksa sorgu hiç çalışmaz")
    void deniedBeforeQuery() {
        MockHttpSession s = new MockHttpSession();
        doThrow(new SecurityException("yok")).when(perms).require(s, "system_health.read", "view");
        assertThatThrownBy(() -> ctrl.overview(s)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> ctrl.series(s, "1h", null)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(query);
    }

    @Test
    @DisplayName("seri: boş pod → tümü, kırpılır; 80 karakterden uzun → 400; özet sarmalanır")
    void seriesAndOverview() {
        MockHttpSession s = new MockHttpSession();
        when(query.series(anyString(), any(), any(Instant.class))).thenReturn(Map.of("range", "1h"));
        when(query.overview(any(Instant.class))).thenReturn(Map.of("pods", java.util.List.of()));

        assertThat(ctrl.series(s, "1h", " pod-a ").getBody()).containsEntry("success", true);
        verify(query).series(eq("1h"), eq("pod-a"), any(Instant.class));
        ctrl.series(s, "6h", "  ");
        verify(query).series(eq("6h"), isNull(), any(Instant.class));
        assertThatThrownBy(() -> ctrl.series(s, "6h", "x".repeat(81))).as("uzun kimlik tüm podlar sayılmaz")
                .isInstanceOf(com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException.class);
        assertThat(ctrl.overview(s).getBody()).containsEntry("success", true).containsKey("data");
        verify(perms, org.mockito.Mockito.times(4)).require(s, "system_health.read", "view");
    }
}
