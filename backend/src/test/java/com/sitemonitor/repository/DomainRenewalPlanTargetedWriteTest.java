package com.sitemonitor.repository;

import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.service.ActivityLogService;
import com.sitemonitor.service.DomainRenewalPlanService;
import com.sitemonitor.service.MonitorHistoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Alan adı yenileme planı — gerçek SQL (H2, işlemsiz): plan yazımları HEDEFLİ (2026-11-15, inceleme M2). Okuma ile yenileme
 * tespiti arasında (yavaş RDAP / WHOIS) kaydedilen eşik düzenlemesi korunur; arada değişen plan koşullu kaldırmayla silinmez;
 * izlemenin varlık kaydı (okunduğu andaki eski planla) plan kolonlarını geri yazmaz.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DomainRenewalPlanTargetedWriteTest {

    @Autowired DomainMonitorRepository repo;

    private DomainRenewalPlanService svc() {
        return new DomainRenewalPlanService(repo, mock(MonitorHistoryService.class), mock(ActivityLogService.class));
    }

    private static MockHttpSession session() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "ops");
        s.setAttribute("displayName", "Ops Kişi");
        return s;
    }

    private DomainMonitor monitor() {
        DomainMonitor m = new DomainMonitor();
        m.setName("Kurumsal");
        m.setDomain("ornek.com.tr");
        m.setTeamId(1L);
        m.setActive(true);
        m.setWarningDays(30);
        m.setCriticalDays(7);
        return repo.save(m);
    }

    @Test
    @DisplayName("okuma ile yenileme tespiti arasında kaydedilen eşik düzenlemesi KORUNUR; plan kapanır")
    void concurrentThresholdEditSurvivesDetection() {
        DomainRenewalPlanService svc = svc();
        DomainMonitor created = monitor();
        Long id = created.getId();
        try {
            svc.plan(repo.findById(id).orElseThrow(), "2026-12-01", "registrar paneli", "2026-12-10", session());
            DomainMonitor stale = repo.findById(id).orElseThrow();          // tur / "Şimdi kontrol et" okuması (ayrık)
            DomainMonitor edit = repo.findById(id).orElseThrow();           // eşzamanlı düzenleme — commit edildi
            edit.setWarningDays(45);
            edit.setDomain("yeni-ornek.com.tr");
            repo.save(edit);

            Map<String, Object> renewed = new HashMap<>(Map.of("expiry_date", "2027-12-10"));
            assertThat(svc.onCheckResult(stale, renewed)).isTrue();

            DomainMonitor now = repo.findById(id).orElseThrow();
            assertThat(now.getWarningDays()).isEqualTo(45);                // eski değere (30) geri yazılmadı
            assertThat(now.getDomain()).isEqualTo("yeni-ornek.com.tr");
            assertThat(now.getRenewalPlannedAt()).isNull();
            assertThat(now.getRenewalPlannedExpiry()).isNull();
        } finally {
            repo.deleteById(id);
        }
    }

    @Test
    @DisplayName("plan arada değiştiyse (yeniden kondu) tespit dokunmaz; eski planlı varlık kaydı planı geri yazmaz")
    void changedPlanIsNotClearedAndEntitySaveNeverRewritesPlan() {
        DomainRenewalPlanService svc = svc();
        Long id = monitor().getId();
        try {
            svc.plan(repo.findById(id).orElseThrow(), "2026-12-01", null, "2026-12-10", session());
            DomainMonitor stale = repo.findById(id).orElseThrow();
            svc.plan(repo.findById(id).orElseThrow(), "2026-12-05", "yeni not", "2026-12-10", session());   // arada yeniden planlandı
            assertThat(svc.onCheckResult(stale, new HashMap<>(Map.of("expiry_date", "2027-12-10")))).isFalse();
            assertThat(repo.findById(id).orElseThrow().getRenewalPlannedAt()).isEqualTo("2026-12-05");

            // Plan kaldırıldı; düzenleme formu eski (planlı) varlığı kaydediyor → plan GERİ GELMEZ (updatable = false)
            DomainMonitor form = repo.findById(id).orElseThrow();
            svc.unplan(repo.findById(id).orElseThrow(), session());
            form.setWarningDays(20);
            repo.save(form);
            DomainMonitor now = repo.findById(id).orElseThrow();
            assertThat(now.getWarningDays()).isEqualTo(20);
            assertThat(now.getRenewalPlannedAt()).isNull();
            assertThat(now.getRenewalPlannedNote()).isNull();
        } finally {
            repo.deleteById(id);
        }
    }
}
