package com.sitemonitor.service;

import com.sitemonitor.model.ScriptedCheck;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.ScriptedCheckRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CHECKS-4 (2026-10-22): anomali guard'ı, sweep başında okunmuş ESKİ varlığı {@code save} ederek kullanıcının arada
 * kaydettiği script / env / adı geri yazıyordu ({@code ScriptedMonitor}'da sürüm alanı yok → birleştirme tüm kolonları
 * yazar). Gerçek repository (H2) — {@code NOT_SUPPORTED}: {@code @DataJpaTest}'in test başı işlemi, işlem gerektiren
 * {@code @Modifying} sorgunun kendi işlemi olmadan çalışmasını gizlerdi (CLAUDE.md "derived deleteBy… without
 * @Transactional").
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScriptedAnomalyGuardStaleEntityTest {

    @Autowired ScriptedMonitorRepository monitorRepo;
    @Autowired ScriptedCheckRepository checkRepo;

    private ScriptedAnomalyGuard guard() {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(eq("site.monitor.scripted.anomaly.enabled"), anyBoolean())).thenReturn(true);
        when(settings.getInt(eq("site.monitor.scripted.anomaly.timeout-streak"), anyInt())).thenReturn(5);
        ScriptedCheckerService checker = mock(ScriptedCheckerService.class);
        when(checker.maxRequestsPerRun()).thenReturn(200);
        EscalationService esc = mock(EscalationService.class);
        when(esc.teamEmailsForMonitor(anyLong(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        return new ScriptedAnomalyGuard(monitorRepo, checkRepo, checker, mock(ActivityLogService.class), esc,
                mock(EmailNotificationService.class), mock(UserPushService.class), settings);
    }

    private static ScriptedCheckerService.ScriptedResult timeout() {
        var phases = new ScriptedCheckerService.Phases(null, null, null, null, null, null, null, null, null, 1L);
        return new ScriptedCheckerService.ScriptedResult("TIMEOUT", false, 180_000L, -1,
                0, 0, null, null, null, null, null, "timeout", false, phases);
    }

    @Test
    @DisplayName("Eski kopya + kullanıcı script/env/adı değiştirmiş → satır KAPALI ve YENİ script/env/ad korunur")
    void staleEntityDoesNotRevertUserEdit() {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setName("Giriş akışı");
        m.setTeamId(3L);
        m.setActive(true);
        m.setScript("export default function () { /* eski */ }");
        m.setEnvJson("[{\"name\":\"A\",\"value\":\"eski\"}]");
        m.setTimeoutSeconds(60);
        m.setCreatedAt("2026-10-01T10:00:00");
        m = monitorRepo.save(m);
        Long id = m.getId();
        ScriptedMonitor stale = monitorRepo.findById(id).orElseThrow();   // sweep başındaki kopya

        // Kullanıcı, sweep sürerken düzeltmeyi kaydediyor
        ScriptedMonitor edited = monitorRepo.findById(id).orElseThrow();
        edited.setScript("export default function () { /* yeni */ }");
        edited.setEnvJson("[{\"name\":\"A\",\"value\":\"yeni\"}]");
        edited.setName("Giriş akışı (düzeltildi)");
        monitorRepo.save(edited);

        for (int i = 0; i < 5; i++) {
            ScriptedCheck c = new ScriptedCheck();
            c.setMonitorId(id);
            c.setOk(false);
            c.setStatus("TIMEOUT");
            c.setDurationMs(180_000L);
            c.setManual(false);
            c.setCheckedAt("2026-10-22T0" + i + ":00:00");
            checkRepo.save(c);
        }

        String reason = guard().evaluate(stale, timeout());

        assertThat(reason).isNotNull().contains("süre aşımı");
        ScriptedMonitor row = monitorRepo.findById(id).orElseThrow();
        assertThat(row.getActive()).isFalse();
        assertThat(row.getDisabledReason()).isEqualTo(reason);
        assertThat(row.getDisabledAt()).isNotBlank();
        assertThat(row.getScript()).contains("yeni");
        assertThat(row.getEnvJson()).contains("yeni");
        assertThat(row.getName()).isEqualTo("Giriş akışı (düzeltildi)");
    }

    private void row(Long monitorId, String checkedAt, Boolean manual, Boolean recheck) {
        ScriptedCheck c = new ScriptedCheck();
        c.setMonitorId(monitorId);
        c.setOk(false);
        c.setStatus("TIMEOUT");
        c.setDurationMs(180_000L);
        c.setManual(manual);
        c.setRecheck(recheck);
        c.setCheckedAt(checkedAt);
        checkRepo.save(c);
    }

    @Test
    @DisplayName("inceleme #1: confirm-attempts=4 — zamanlanmış TIMEOUT + 4 teyit yeniden kontrolü TIMEOUT, zincir sürerken 5. TIMEOUT kapatmaz")
    void confirmationChainRechecks_doNotFillTheStreak() {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setName("Teyit zinciri");
        m.setTeamId(3L);
        m.setActive(true);
        m.setScript("export default function () {}");
        m.setTimeoutSeconds(60);
        m.setCreatedAt("2026-10-01T10:00:00");
        m = monitorRepo.save(m);
        Long id = m.getId();
        row(id, "2026-10-31T10:00:00", false, null);                           // zamanlanmış koşum (zinciri başlattı)
        for (int i = 1; i <= 4; i++) row(id, "2026-10-31T10:00:" + (10 * i), false, Boolean.TRUE);   // teyit adımları
        row(id, "2026-10-31T10:01:00", Boolean.TRUE, null);                    // araya giren elle koşum

        // seri yalnız zamanlanmış satırları görür
        assertThat(checkRepo.findRecentScheduledByMonitorId(id, 5)).hasSize(1);

        ScriptedAnomalyGuard g = guard();
        assertThat(g.evaluate(m, timeout(), false, true)).as("4. teyit adımının sonucu").isNull();
        row(id, "2026-10-31T10:05:00", false, null);                           // zincir sürerken sıradaki zamanlanmış koşum
        assertThat(g.evaluate(m, timeout(), false, false)).as("5. zaman aşımı").isNull();
        ScriptedMonitor after = monitorRepo.findById(id).orElseThrow();
        assertThat(after.getActive()).isTrue();
        assertThat(after.getDisabledReason()).isNull();

        // karşı kanıt: beş ZAMANLANMIŞ zaman aşımı bugünkü gibi kapatır
        for (int i = 0; i < 3; i++) row(id, "2026-10-31T10:1" + i + ":00", false, Boolean.FALSE);
        assertThat(checkRepo.findRecentScheduledByMonitorId(id, 5)).hasSize(5);
        assertThat(g.evaluate(monitorRepo.findById(id).orElseThrow(), timeout(), false, false)).contains("süre aşımı");
    }

    @Test
    @DisplayName("Kullanıcı arada kendisi kapatmış → guard 0 satır günceller, 'kapattım' demez")
    void userAlreadyPaused_noDisable() {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setName("Sepet");
        m.setTeamId(3L);
        m.setActive(true);
        m.setScript("export default function () {}");
        m.setCreatedAt("2026-10-01T10:00:00");
        m = monitorRepo.save(m);
        ScriptedMonitor stale = monitorRepo.findById(m.getId()).orElseThrow();
        ScriptedMonitor paused = monitorRepo.findById(m.getId()).orElseThrow();
        paused.setActive(false);
        monitorRepo.save(paused);

        assertThat(monitorRepo.disableIfActive(m.getId(), "x", "2026-10-22T00:00:00Z")).isZero();
        assertThat(guard().evaluate(stale, new ScriptedCheckerService.ScriptedResult("PASS", true, 1000L, 0,
                1, 0, null, null, null, null, null, null, false,
                new ScriptedCheckerService.Phases(null, null, null, null, null, null, null, null, null, 5000L))))
                .isNull();
        assertThat(monitorRepo.findById(m.getId()).orElseThrow().getDisabledReason()).isNull();
    }
}
