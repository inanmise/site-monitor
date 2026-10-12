package com.sitemonitor.service.incident;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.IncidentActionItem;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.IncidentActionItemRepository;
import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.SystemMaintenanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Olay aksiyonu hatırlatması (2026-10-11): takvim (yarın / bugün / gecikmenin ilk günü / haftalık), sahip başına tek
 * e-posta, gönderimden önce koşullu sahiplenme (kaybedilen sahiplenme e-postaya girmez), pasif / adressiz sahip ve
 * sistem bakımı kararları notification_logs'ta, bakımda sahiplenme yok. Adresler sahte.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentActionReminderServiceTest {

    static final LocalDate TODAY = LocalDate.parse("2026-10-11");

    @Mock IncidentActionItemRepository actions;
    @Mock IncidentRecordRepository incidents;
    @Mock AppUserRepository users;
    @Mock EmailNotificationService email;
    @Mock NotificationLogRepository logs;
    @Mock AppSettingsService settings;
    @Mock SystemMaintenanceService maintenance;

    IncidentActionReminderService svc;

    @BeforeEach
    void setUp() {
        svc = new IncidentActionReminderService(actions, incidents, users, email, logs, settings);
        svc.setMaintenance(maintenance);
        when(settings.getString(eq("site.monitor.app.base-url"), anyString())).thenReturn("https://sitemonitor.example.com");
        when(email.sendHtml(any(), any(), anyString(), anyString(), anyString(), any(), anyBoolean(), any())).thenReturn("SENT");
        when(actions.claimFirstReminder(anyLong(), anyString())).thenReturn(1);
        when(actions.claimNextReminder(anyLong(), anyString(), anyString())).thenReturn(1);
        IncidentRecord r = new IncidentRecord();
        r.setId(10L);
        r.setTitle("Ödeme kesintisi");
        when(incidents.findAllById(any())).thenReturn(List.of(r));
    }

    private static IncidentActionItem item(long id, long owner, String due, String remindedOn) {
        IncidentActionItem a = new IncidentActionItem();
        a.setId(id);
        a.setIncidentId(10L);
        a.setTitle("Aksiyon " + id);
        a.setStatus("OPEN");
        a.setPriority("HIGH");
        a.setOwnerUserId(owner);
        a.setOwnerName("Sahip " + owner);
        a.setDueDate(due);
        a.setRemindedOn(remindedOn);
        return a;
    }

    private static AppUser user(long id, boolean active, String email) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername("u" + id);
        u.setDisplayName("Kullanıcı " + id);
        u.setActive(active);
        u.setEmail(email);
        return u;
    }

    @Test
    @DisplayName("takvim: yarın, bugün, gecikmenin ilk günü, sonra 7 günde bir; aynı gün ikinci kez yok")
    void schedule() {
        assertThat(IncidentActionReminderService.whenToRemind("2026-10-12", null, TODAY)).isEqualTo("SOON");
        assertThat(IncidentActionReminderService.whenToRemind("2026-10-12", "2026-10-11", TODAY)).isNull();
        assertThat(IncidentActionReminderService.whenToRemind("2026-10-11", "2026-10-10", TODAY)).isEqualTo("TODAY");
        assertThat(IncidentActionReminderService.whenToRemind("2026-10-10", "2026-10-10", TODAY)).isEqualTo("OVERDUE");
        assertThat(IncidentActionReminderService.whenToRemind("2026-10-01", "2026-10-08", TODAY)).isNull();
        assertThat(IncidentActionReminderService.whenToRemind("2026-09-20", "2026-10-04", TODAY)).isEqualTo("OVERDUE");
        assertThat(IncidentActionReminderService.whenToRemind("2026-09-20", "2026-10-05", TODAY)).isNull();
        assertThat(IncidentActionReminderService.whenToRemind("2026-10-20", null, TODAY)).isNull();
        assertThat(IncidentActionReminderService.whenToRemind(null, null, TODAY)).isNull();
        assertThat(IncidentActionReminderService.whenToRemind("bozuk", null, TODAY)).isNull();
    }

    @Test
    @DisplayName("sahip başına tek e-posta; kaybedilen sahiplenme listeye girmez; günlük satırı yazılır")
    void groupsByOwnerAndClaims() {
        when(actions.reminderCandidates("2026-10-12")).thenReturn(List.of(
                item(1, 7, "2026-10-12", null), item(2, 7, "2026-10-05", "2026-10-05"), item(3, 7, "2026-10-11", null),
                item(4, 8, "2026-10-12", null)));
        when(actions.claimFirstReminder(eq(3L), anyString())).thenReturn(0);   // başka pod aldı
        when(users.findAllById(any())).thenReturn(List.of(user(7, true, "a@example.com"), user(8, true, "b@example.com")));

        IncidentActionReminderService.Result r = svc.run(TODAY);
        assertThat(r.owners()).isEqualTo(2);
        assertThat(r.sent()).isEqualTo(2);
        assertThat(r.items()).isEqualTo(3);
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email, times(2)).sendHtml(to.capture(), isNull(), anyString(), html.capture(), anyString(), isNull(), eq(false), isNull());
        assertThat(to.getAllValues().get(0)).containsExactly("a@example.com");
        assertThat(html.getAllValues().get(0)).contains("Aksiyon 1").contains("Aksiyon 2").doesNotContain("Aksiyon 3")
                .contains("6 gün gecikti").contains("Ödeme kesintisi").contains("ih_view=actions");
        verify(actions).claimNextReminder(2L, "2026-10-05", "2026-10-11");
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs, times(2)).save(log.capture());
        assertThat(log.getAllValues()).allSatisfy(n -> {
            assertThat(n.getTrigger()).isEqualTo(IncidentActionReminderService.TRIGGER);
            assertThat(n.getAlertEventId()).isZero();
            assertThat(n.getEmailStatus()).isEqualTo("SENT");
        });
    }

    @Test
    @DisplayName("pasif ya da adressiz sahip: e-posta yok, karar günlükte")
    void inactiveOrNoAddress() {
        when(actions.reminderCandidates(anyString())).thenReturn(List.of(item(1, 7, "2026-10-12", null), item(2, 8, "2026-10-12", null)));
        when(users.findAllById(any())).thenReturn(List.of(user(7, false, "a@example.com"), user(8, true, " ")));
        IncidentActionReminderService.Result r = svc.run(TODAY);
        assertThat(r.sent()).isZero();
        assertThat(r.skipped()).isEqualTo(2);
        verify(email, never()).sendHtml(any(), any(), anyString(), anyString(), anyString(), any(), anyBoolean(), any());
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs, times(2)).save(log.capture());
        assertThat(log.getAllValues()).extracting(NotificationLog::getEmailStatus)
                .containsExactlyInAnyOrder("SKIPPED: pasif kullanıcı", "SKIPPED: e-posta adresi yok");
    }

    @Test
    @DisplayName("sistem bakımı susturması: sahiplenme YOK (ertesi gün yeniden), karar günlükte")
    void mutedDuringMaintenance() {
        when(maintenance.notificationsMuted()).thenReturn(true);
        when(actions.reminderCandidates(anyString())).thenReturn(List.of(item(1, 7, "2026-10-12", null)));
        when(users.findAllById(any())).thenReturn(List.of(user(7, true, "a@example.com")));
        svc.run(TODAY);
        verify(actions, never()).claimFirstReminder(anyLong(), anyString());
        verify(email, never()).sendHtml(any(), any(), anyString(), anyString(), anyString(), any(), anyBoolean(), any());
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getEmailStatus()).isEqualTo("SKIPPED: sistem bakımı");
    }

    @Test
    @DisplayName("aday yoksa hiçbir şey yapılmaz; kapalıyken zamanlanmış koşu atlar")
    void nothingToDo() {
        when(actions.reminderCandidates(anyString())).thenReturn(List.of(item(1, 7, "2026-10-30", null)));
        assertThat(svc.run(TODAY).owners()).isZero();
        verifyNoInteractions(email, logs);
        svc.setEnabled(false);
        svc.scheduled();
        verify(actions, times(1)).reminderCandidates(anyString());
    }
}
