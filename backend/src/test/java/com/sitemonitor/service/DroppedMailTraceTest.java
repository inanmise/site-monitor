package com.sitemonitor.service;

import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.IncidentImageRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-10-23 (çok pod R2a · D2) — pod durunca kuyrukta kalan olay kaydı postası ve "yeni cihazdan giriş" denetimi SESSİZCE
 * düşmez: kapanışta ortak süre içinde boşaltılır, başlayamayan her görev için {@code notification_logs} satırı yazılır;
 * kapanmış havuza gelen görevin izi hemen yazılır. Başlamış (kesilen) görev iz yazmaz (posta gitmiş olabilir).
 */
class DroppedMailTraceTest {

    @Test
    @DisplayName("DroppableTask: kuyrukta bekleyenler kapanışta iz bırakır, çalışan görev bırakmaz; kapalı havuz hemen iz yazar")
    void helper_tracesNotStartedOnly() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1), never = new CountDownLatch(1);
        AtomicInteger dropped = new AtomicInteger();
        DroppableTask.submit(exec, () -> {
            started.countDown();
            try { never.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, dropped::incrementAndGet);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        DroppableTask.submit(exec, () -> { }, dropped::incrementAndGet);
        DroppableTask.submit(exec, () -> { }, dropped::incrementAndGet);

        assertThat(DroppableTask.drain(exec, 50, DroppableTask.MAX_TRACES, n -> { throw new AssertionError("özet beklenmiyor"); }))
                .isEqualTo(2);
        assertThat(dropped.get()).as("yalnız başlamamış iki görev").isEqualTo(2);
        assertThat(DroppableTask.submit(exec, () -> { }, dropped::incrementAndGet)).isFalse();
        assertThat(dropped.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("Kapanış izi sınırlı: ilk N görev tek tek, kalanı TEK özet satırında (kapanış bütçesinden sonra sınırsız yazım yok)")
    void helper_capsPerTaskTraces() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger dropped = new AtomicInteger();
        java.util.List<Integer> summaries = new java.util.ArrayList<>();
        DroppableTask.submit(exec, () -> {
            started.countDown();
            try { new CountDownLatch(1).await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, dropped::incrementAndGet);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 7; i++) DroppableTask.submit(exec, () -> { }, dropped::incrementAndGet);

        assertThat(DroppableTask.drain(exec, 50, 3, summaries::add)).isEqualTo(7);
        assertThat(dropped.get()).isEqualTo(3);
        assertThat(summaries).containsExactly(4);
    }

    @Test
    @DisplayName("Olay postası özet satırı: kalan sayıyı taşıyan TEK FAILED satırı")
    void incident_summaryRow() {
        NotificationLogRepository logs = mock(NotificationLogRepository.class);
        IncidentNotificationService svc = new IncidentNotificationService(mock(EmailNotificationService.class), mock(TeamRepository.class),
                mock(AppUserRepository.class), mock(IncidentImageRepository.class), mock(AppSettingsService.class),
                mock(NotificationGroupService.class));
        svc.setNotificationLogRepo(logs);
        svc.recordDroppedMore(57);
        ArgumentCaptor<NotificationLog> c = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(c.capture());
        assertThat(c.getValue().getEmailStatus()).startsWith("FAILED: sonuç yok").contains("57 olay kaydı postası");
        assertThat(c.getValue().getTrigger()).isEqualTo(IncidentNotificationService.TRIGGER_INCIDENT_NOTIFY);
        svc.shutdownExecutor();
    }

    private static Map<String, Object> incident(String title) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", 42L);
        m.put("title", title);
        m.put("team_id", 5L);
        m.put("team_name", "Ödeme Ekibi");
        return m;
    }

    @Test
    @DisplayName("Olay kaydı postası: kapanışta başlayamayan her görev FAILED izi (INCIDENT_NOTIFY); kapandıktan sonraki istek de")
    void incident_queuedMailsTracedAtShutdown() throws Exception {
        TeamRepository teamRepo = mock(TeamRepository.class);
        CountDownLatch started = new CountDownLatch(1);
        when(teamRepo.findById(anyLong())).thenAnswer(i -> {
            started.countDown();
            new CountDownLatch(1).await(30, TimeUnit.SECONDS);   // kapanış kesene dek takılı kalan gönderim
            return Optional.of(new Team());
        });
        NotificationLogRepository logs = mock(NotificationLogRepository.class);
        IncidentNotificationService svc = new IncidentNotificationService(mock(EmailNotificationService.class), teamRepo,
                mock(AppUserRepository.class), mock(IncidentImageRepository.class), mock(AppSettingsService.class),
                mock(NotificationGroupService.class));
        svc.setNotificationLogRepo(logs);
        svc.drainCapMs = 50;

        svc.notifyIncident(incident("DB havuzu tükendi"), "NEW");
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        svc.notifyIncident(incident("Ödeme yavaş"), "UPDATED");
        svc.notifyIncident(incident("Ödeme yavaş"), "RESOLVED");

        svc.shutdownExecutor();
        svc.notifyIncident(incident("Geç gelen"), "NEW");   // kapanmış havuz → hemen iz

        ArgumentCaptor<NotificationLog> c = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs, times(3)).save(c.capture());
        List<NotificationLog> rows = new ArrayList<>(c.getAllValues());
        assertThat(rows).allSatisfy(n -> {
            assertThat(n.getTrigger()).isEqualTo(IncidentNotificationService.TRIGGER_INCIDENT_NOTIFY);
            assertThat(n.getEmailStatus()).isEqualTo(IncidentNotificationService.STATUS_DROPPED);
            assertThat(n.getAlertEventId()).isZero();
            assertThat(n.getRecipientName()).isEqualTo("Ödeme Ekibi");
        });
        assertThat(rows).extracting(NotificationLog::getSubject).containsExactly(
                "[Site Monitor] Olay Güncellendi — Ödeme yavaş", "[Site Monitor] Olay Çözüldü — Ödeme yavaş",
                "[Site Monitor] Yeni Olay — Geç gelen");
    }

    private static NewDeviceNotifier notifier(AppSettingsService settings, AuditLogRepository audit, NotificationLogRepository logs) {
        NewDeviceNotifier n = new NewDeviceNotifier(audit, mock(AppUserRepository.class), mock(EmailNotificationService.class),
                settings, mock(GeoIpService.class));
        n.setNotificationLogRepo(logs);
        n.drainCapMs = 50;
        return n;
    }

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36";

    @Test
    @DisplayName("Yeni cihaz denetimi: özellik açıkken kapanışta başlayamayan her denetim SKIPPED izi (NEW_DEVICE); kapalıyken iz yok")
    void newDevice_queuedChecksTracedOnlyWhenEnabled() throws Exception {
        for (boolean enabled : new boolean[]{true, false}) {
            AppSettingsService settings = mock(AppSettingsService.class);
            when(settings.getBoolean(eq(NewDeviceNotifier.SETTING_KEY), anyBoolean())).thenReturn(enabled);
            AuditLogRepository audit = mock(AuditLogRepository.class);
            CountDownLatch started = new CountDownLatch(1);
            when(audit.findDistinctLoginUserAgents(anyString(), anyLong())).thenAnswer(i -> {
                started.countDown();
                new CountDownLatch(1).await(30, TimeUnit.SECONDS);
                return List.of();
            });
            NotificationLogRepository logs = mock(NotificationLogRepository.class);
            NewDeviceNotifier n = notifier(settings, audit, logs);

            n.notifyIfNewDevice(1L, "N12345", UA, "10.0.0.1", "2026-10-23T10:00:00");
            if (enabled) assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            else Thread.sleep(100);   // kapalı: denetim hemen döner, kuyruk boş
            n.notifyIfNewDevice(2L, "N12345", UA, "10.0.0.1", "2026-10-23T10:00:01");
            n.notifyIfNewDevice(3L, "N54321", UA, "10.0.0.2", "2026-10-23T10:00:02");
            if (!enabled) Thread.sleep(100);
            n.shutdownExecutor();

            if (enabled) {
                ArgumentCaptor<NotificationLog> c = ArgumentCaptor.forClass(NotificationLog.class);
                verify(logs, times(2)).save(c.capture());
                assertThat(c.getAllValues()).allSatisfy(row -> {
                    assertThat(row.getTrigger()).isEqualTo(NewDeviceNotifier.TRIGGER_NEW_DEVICE);
                    assertThat(row.getEmailStatus()).isEqualTo(NewDeviceNotifier.STATUS_DROPPED);
                });
                assertThat(c.getAllValues()).extracting(NotificationLog::getRecipientName).containsExactly("N12345", "N54321");
            } else {
                verify(logs, never()).save(any());
            }
        }
    }

    @Test
    @DisplayName("Yeni cihaz: giriş yanıtı bloklanmaz — denetim kuyruk iş parçacığında koşar (eski @Async davranışı)")
    void newDevice_entryReturnsImmediately() throws Exception {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(eq(NewDeviceNotifier.SETTING_KEY), anyBoolean())).thenReturn(true);
        AuditLogRepository audit = mock(AuditLogRepository.class);
        CountDownLatch release = new CountDownLatch(1), done = new CountDownLatch(1);
        when(audit.findDistinctLoginUserAgents(anyString(), anyLong())).thenAnswer(i -> {
            release.await(5, TimeUnit.SECONDS);
            done.countDown();
            return List.of();
        });
        NewDeviceNotifier n = notifier(settings, audit, mock(NotificationLogRepository.class));
        long t0 = System.nanoTime();
        n.notifyIfNewDevice(1L, "N12345", UA, "10.0.0.1", "2026-10-23T10:00:00");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(1_000);
        release.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        n.shutdownExecutor();
    }
}
