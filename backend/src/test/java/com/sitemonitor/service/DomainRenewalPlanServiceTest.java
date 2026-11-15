package com.sitemonitor.service;

import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.repository.DomainMonitorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpSession;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Alan adı yenileme planı (2026-09-22, madde H): koy / kaldır / yenileme görülünce kendiliğinden kapan. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DomainRenewalPlanServiceTest {

    @Mock DomainMonitorRepository repo;
    @Mock MonitorHistoryService history;
    @Mock ActivityLogService activityLog;
    private DomainRenewalPlanService svc;

    @BeforeEach
    void setUp() {
        svc = new DomainRenewalPlanService(repo, history, activityLog);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        // M2 (2026-11-15): plan yazımları hedefli — varsayılan 1 satır
        when(repo.writeRenewalPlan(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(repo.clearRenewalPlanIfUnchanged(any(), any(), any(), any(), any())).thenReturn(1);
    }

    private static DomainMonitor monitor() {
        DomainMonitor m = new DomainMonitor();
        m.setId(7L); m.setDomain("a.example.com"); m.setName("A"); m.setTeamId(1L);
        return m;
    }

    private static MockHttpSession session() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "ops"); s.setAttribute("displayName", "Ops Kişi");
        return s;
    }

    @Test
    @DisplayName("plan: tarih/not/kim + plan anındaki bitiş kıyas tabanı; geçmiş + aktivite kaydı")
    void plan_setsFields() {
        DomainMonitor m = monitor();
        DomainMonitor saved = svc.plan(m, "2026-10-15", "  registrar paneli, otomatik yenileme kapalı  ", "2026-11-22T00:00:00Z", session());
        assertThat(saved.getRenewalPlannedAt()).isEqualTo("2026-10-15");
        assertThat(saved.getRenewalPlannedNote()).isEqualTo("registrar paneli, otomatik yenileme kapalı");
        assertThat(saved.getRenewalPlannedBy()).isEqualTo("ops");
        assertThat(saved.getRenewalPlannedByName()).isEqualTo("Ops Kişi");
        assertThat(saved.getRenewalPlannedExpiry()).isEqualTo("2026-11-22T00:00:00Z");
        verify(history).record(eq("DOMAIN"), eq(7L), eq("A"), eq(1L), eq("UPDATE"), any(), any(), eq("renewal-plan"), any());
        verify(activityLog).recordLifecycle(eq("DOMAIN"), eq(7L), eq("A"), eq("a.example.com"), eq(1L), eq("RENEWAL_PLANNED"), eq("ops"), contains("2026-10-15"));
        assertThatThrownBy(() -> svc.plan(monitor(), "15.10.2026", null, null, session())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.plan(monitor(), "2026-02-31", null, null, session())).isInstanceOf(IllegalArgumentException.class);
        // Varlığın tamamı kaydedilmez — yalnız plan kolonları (M2)
        verify(repo).writeRenewalPlan(eq(7L), eq("2026-10-15"), eq("ops"), eq("Ops Kişi"), eq("registrar paneli, otomatik yenileme kapalı"),
                eq("2026-11-22T00:00:00Z"), any());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("M2: plan arada değiştiyse (koşullu kaldırma 0 satır) yenileme tespiti / elle kaldırma / alan adı değişimi İZ YAZMAZ")
    void conditionalClear_zeroRows_writesNoTrace() {
        DomainMonitor m = monitor();
        svc.plan(m, "2026-10-15", "n", "2026-11-22T00:00:00Z", session());
        when(repo.clearRenewalPlanIfUnchanged(any(), any(), any(), any(), any())).thenReturn(0);
        clearInvocations(history, activityLog);
        assertThat(svc.onCheckResult(m, new HashMap<>(Map.of("expiry_date", "2027-11-22T00:00:00Z")))).isFalse();
        assertThat(svc.unplan(m, session()).getRenewalPlannedAt()).isEqualTo("2026-10-15");
        assertThat(svc.clearForDomainChange(m, "old.example.com", session())).isNull();
        verifyNoInteractions(history, activityLog);
        verify(repo, times(3)).clearRenewalPlanIfUnchanged(eq(7L), eq("2026-10-15"), eq("2026-11-22T00:00:00Z"), eq("n"), any());
        verify(repo, never()).save(any());
        assertThat(DomainRenewalPlanService.validDay("2026-02-28")).isTrue();
        assertThat(DomainRenewalPlanService.validDay("2026-02-31")).isFalse();
        assertThat(DomainRenewalPlanService.validDay("2026-2-1")).isFalse();
        assertThat(DomainRenewalPlanService.validDay(null)).isFalse();
    }

    @Test
    @DisplayName("unplan: alanlar temizlenir")
    void unplan_clears() {
        DomainMonitor m = monitor();
        svc.plan(m, "2026-10-15", "n", "2026-11-22T00:00:00Z", session());
        DomainMonitor saved = svc.unplan(m, session());
        assertThat(saved.getRenewalPlannedAt()).isNull();
        assertThat(saved.getRenewalPlannedNote()).isNull();
        assertThat(saved.getRenewalPlannedExpiry()).isNull();
        verify(activityLog).recordLifecycle(eq("DOMAIN"), eq(7L), eq("A"), eq("a.example.com"), eq(1L), eq("RENEWAL_PLAN_CLEARED"), eq("ops"));
    }

    @Test
    @DisplayName("onCheckResult: bitiş plan anındaki bitişten SONRAYSA plan kapanır; aynı/erken bitiş, UNKNOWN, tabansız plan dokunmaz")
    void onCheckResult_autoClose() {
        DomainMonitor m = monitor();
        svc.plan(m, "2026-10-15", "n", "2026-11-22T00:00:00Z", session());
        Map<String, Object> same = new HashMap<>(Map.of("expiry_date", "2026-11-22T00:00:00Z"));
        assertThat(svc.onCheckResult(m, same)).isFalse();
        assertThat(svc.onCheckResult(m, new HashMap<>())).isFalse();                       // UNKNOWN: bitiş yok
        assertThat(m.getRenewalPlannedAt()).isEqualTo("2026-10-15");

        Map<String, Object> renewed = new HashMap<>(Map.of("expiry_date", "2027-11-22T00:00:00Z"));
        assertThat(svc.onCheckResult(m, renewed)).isTrue();
        assertThat(m.getRenewalPlannedAt()).isNull();
        verify(history).record(eq("DOMAIN"), eq(7L), eq("A"), eq(1L), eq("UPDATE"), any(), any(), contains("renewal-detected"), isNull());
        verify(activityLog).recordLifecycle(eq("DOMAIN"), eq(7L), eq("A"), eq("a.example.com"), eq(1L), eq("RENEWAL_DETECTED"), eq("scheduler"), contains("2027-11-22"));

        // Kıyas tabanı yoksa (bitiş bilinmeden konmuş plan) otomatik kapatma yok — elle kapatılır
        DomainMonitor noBase = monitor();
        svc.plan(noBase, "2026-10-15", null, null, session());
        assertThat(svc.onCheckResult(noBase, renewed)).isFalse();
        assertThat(noBase.getRenewalPlannedAt()).isEqualTo("2026-10-15");
        // Plansız izleme: hiçbir şey olmaz
        assertThat(svc.onCheckResult(monitor(), renewed)).isFalse();
    }

    @Test
    @DisplayName("2026-11-15: taban WHOIS tarihi, kontrol RDAP damgası (AYNI bitiş) → plan kapanmaz; gerçek yenileme yine kapatır")
    void onCheckResult_sourceFlipSameDay_keepsPlan() {
        DomainMonitor m = monitor();
        svc.plan(m, "2026-10-15", "n", "2026-11-22", session());                          // taban WHOIS yedeğinden
        Map<String, Object> rdapSame = new HashMap<>(Map.of("expiry_date", "2026-11-22T04:00:00Z"));
        assertThat(svc.onCheckResult(m, rdapSame)).isFalse();
        assertThat(m.getRenewalPlannedAt()).isEqualTo("2026-10-15");
        verify(activityLog, never()).recordLifecycle(any(), any(), any(), any(), any(), eq("RENEWAL_DETECTED"), any(), any());

        Map<String, Object> renewed = new HashMap<>(Map.of("expiry_date", "2027-11-22T04:00:00Z"));
        assertThat(svc.onCheckResult(m, renewed)).isTrue();
        assertThat(m.getRenewalPlannedAt()).isNull();
    }
}
