package com.sitemonitor.controller;

import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.service.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * İzleme Panosu ucu (2026-10-01 yeniden tasarım): {@code ?fresh=1|true} Yenile düğmesinin taze isteğidir — servisin
 * {@code build(..., fresh=true)} yoluna gider (bellek kaydı 5 sn'den eskiyse yeniden hesaplanır); parametresiz / başka
 * değerli çağrı (dakikalık yoklama) eski 4 argümanlı bellekli yoldan okur. Kapsam anahtarı kablolaması
 * {@code PolledEndpointsMemoWiringTest}'te.
 */
class MonitoringOverviewControllerTest {

    private static MockHttpSession admin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    @Test
    @DisplayName("fresh=1 / true → build(..., true); parametresiz, 0 ya da çöp değer → bellekli 4 argümanlı build")
    void freshParam_routesToFreshBuild() {
        MonitoringOverviewService svc = mock(MonitoringOverviewService.class);
        MonitoringOverviewController c = new MonitoringOverviewController(svc, mock(PermissionService.class));
        when(svc.build(anyString(), any(), anyBoolean(), anyInt())).thenReturn(Map.of("k", "cached"));
        when(svc.build(anyString(), any(), anyBoolean(), anyInt(), anyBoolean())).thenReturn(Map.of("k", "fresh"));

        assertThat(c.overview(24, "1", admin()).getBody()).containsEntry("data", Map.of("k", "fresh"));
        assertThat(c.overview(168, "true", admin()).getBody()).containsEntry("data", Map.of("k", "fresh"));
        verify(svc).build(eq("ALL"), any(), eq(true), eq(24), eq(true));
        verify(svc).build(eq("ALL"), any(), eq(true), eq(168), eq(true));

        assertThat(c.overview(24, null, admin()).getBody()).containsEntry("data", Map.of("k", "cached"));
        c.overview(24, "0", admin());
        c.overview(24, "yes-please", admin());
        c.overview(24, admin());
        verify(svc, times(4)).build(eq("ALL"), any(), eq(true), eq(24));
        verify(svc, never()).build(anyString(), any(), anyBoolean(), anyInt(), eq(false));
    }

    @Test
    @DisplayName("isFresh: yalnız 1 / true (boşluk ve büyük harf toleranslı)")
    void isFresh() {
        assertThat(MonitoringOverviewController.isFresh("1")).isTrue();
        assertThat(MonitoringOverviewController.isFresh(" TRUE ")).isTrue();
        assertThat(MonitoringOverviewController.isFresh(null)).isFalse();
        assertThat(MonitoringOverviewController.isFresh("0")).isFalse();
        assertThat(MonitoringOverviewController.isFresh("")).isFalse();
    }
}
