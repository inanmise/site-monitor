package com.sitemonitor.service.oncall;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.OnCallSchedule;
import com.sitemonitor.model.OnCallWeeklyNotice;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.OnCallOverrideRepository;
import com.sitemonitor.repository.OnCallScheduleRepository;
import com.sitemonitor.repository.OnCallWeeklyNoticeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Haftalık nöbet bildirimi ayarı (2026-11-15): yalnız gönderilen alan değişir, değer doğrulaması (gün, saat, en az bir kanal),
 * değişiklik yoksa yazım yok, yalnız-bildirim kaydı hedefli UPDATE (çizelgeye dokunmaz), çizelge + bildirim tek kayıt,
 * görünümde ayar / sıradaki gönderim / son sonuç.
 */
class OnCallWeeklyNoticeSettingsTest {

    OnCallScheduleRepository schedules;
    OnCallOverrideRepository overrides;
    AppUserRepository users;
    OnCallWeeklyNoticeRepository notices;
    OnCallService svc;
    OnCallSchedule row;

    @BeforeEach
    void setUp() {
        schedules = mock(OnCallScheduleRepository.class);
        overrides = mock(OnCallOverrideRepository.class);
        users = mock(AppUserRepository.class);
        notices = mock(OnCallWeeklyNoticeRepository.class);
        svc = new OnCallService(schedules, overrides, users);
        svc.setWeeklyNotices(notices);
        row = new OnCallSchedule();
        row.setId(1L);
        row.setTeamId(5L);
        row.setEnabled(true);
        row.setRotation("WEEKLY");
        row.setAnchor("2026-11-02T08:30");
        row.setMemberIds("11");
        row.setNotifyOncall(false);
        when(schedules.findByTeamId(5L)).thenAnswer(i -> Optional.of(row));
        when(schedules.save(any())).thenAnswer(i -> i.getArgument(0));
        when(schedules.updateWeeklyNotice(eq(5L), any(), any(), any(), any(), any(), any(), anyString(), any())).thenReturn(1);
        when(overrides.findByTeamIdAndEndsAtGreaterThanOrderByStartsAtAscIdAsc(any(), anyString())).thenReturn(List.of());
        when(users.findAllById(any())).thenAnswer(i -> {
            List<AppUser> out = new ArrayList<>();
            for (Object id : (Iterable<?>) i.getArgument(0)) {
                AppUser u = new AppUser();
                u.setId((Long) id);
                u.setUsername("N" + id);
                u.setActive(true);
                out.add(u);
            }
            return out;
        });
    }

    private static OnCallService.WeeklyNoticePatch patch(Boolean enabled, Boolean mail, Boolean push, Integer day, String time) {
        return new OnCallService.WeeklyNoticePatch(enabled, mail, push, day, time);
    }

    private static String field(Throwable t) {
        return ((GlobalExceptionHandler.FieldValidationException) t).getFields().keySet().iterator().next();
    }

    @Test
    @DisplayName("açma: yalnız gönderilen alan yazılır (hedefli UPDATE), varsayılanlar korunur, 'since' damgalanır")
    void enableTargeted() {
        OnCallService.WeeklyNoticeSave r = svc.saveWeeklyNotice(5L, patch(true, null, null, null, null), "u5");
        assertThat(r.changed()).isTrue();
        verify(schedules).updateWeeklyNotice(eq(5L), eq(true), eq(true), eq(true), eq(1), eq("08:30"), anyString(), anyString(), eq("u5"));
        verify(schedules, never()).save(any());
        assertThat(OnCallService.noticeSnapshot(r.schedule())).containsEntry("weeklyNoticeEnabled", true)
                .containsEntry("weeklyNoticeDay", 1).containsEntry("weeklyNoticeTime", "08:30");
    }

    @Test
    @DisplayName("değişiklik yoksa hiçbir şey yazılmaz")
    void noChangeNoWrite() {
        OnCallService.WeeklyNoticeSave r = svc.saveWeeklyNotice(5L, patch(false, true, true, 1, "08:30"), "u5");
        assertThat(r.changed()).isFalse();
        verify(schedules, never()).updateWeeklyNotice(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("doğrulama: gün 1–7, saat SS:dd, açıkken en az bir kanal; hatalı istek hiçbir şey yazmaz")
    void validation() {
        assertThatThrownBy(() -> svc.saveWeeklyNotice(5L, patch(null, null, null, 8, null), "u5"))
                .satisfies(t -> assertThat(field(t)).isEqualTo("weekly_notice.day"));
        assertThatThrownBy(() -> svc.saveWeeklyNotice(5L, patch(null, null, null, null, "8:30"), "u5"))
                .satisfies(t -> assertThat(field(t)).isEqualTo("weekly_notice.time"));
        assertThatThrownBy(() -> svc.saveWeeklyNotice(5L, patch(true, false, false, null, null), "u5"))
                .satisfies(t -> assertThat(field(t)).isEqualTo("weekly_notice.mail"));
        verify(schedules, never()).updateWeeklyNotice(any(), any(), any(), any(), any(), any(), any(), any(), any());
        // kapalıyken iki kanal da kapatılabilir
        assertThat(svc.saveWeeklyNotice(5L, patch(null, false, false, null, null), "u5").changed()).isTrue();
    }

    @Test
    @DisplayName("çizelge kaydedilmemişse yalnız-bildirim kaydı 400 (weekly_notice.enabled)")
    void requiresSchedule() {
        when(schedules.findByTeamId(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.saveWeeklyNotice(7L, patch(true, null, null, null, null), "u5"))
                .satisfies(t -> assertThat(field(t)).isEqualTo("weekly_notice.enabled"));
    }

    @Test
    @DisplayName("çizelge + bildirim tek kayıt; bildirim hatası çizelgeyi de yazmaz")
    void combinedSave() {
        OnCallSchedule saved = svc.save(5L, true, "WEEKLY", "2026-11-02T08:30", List.of(11L), false,
                patch(true, null, false, 3, "09:15"), "u5");
        assertThat(saved.getWeeklyNoticeEnabled()).isTrue();
        assertThat(saved.getWeeklyNoticePush()).isFalse();
        assertThat(saved.getWeeklyNoticeDay()).isEqualTo(3);
        assertThat(saved.getWeeklyNoticeTime()).isEqualTo("09:15");
        assertThat(saved.getWeeklyNoticeSince()).isNotNull();
        verify(schedules).save(any());
        row.setWeeklyNoticeEnabled(null);
        assertThatThrownBy(() -> svc.save(5L, true, "WEEKLY", "2026-11-02T08:30", List.of(11L), false,
                patch(null, null, null, null, "99:99"), "u5")).isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);
        verify(schedules).save(any());   // yalnız ilk kayıt
    }

    @Test
    @DisplayName("eski 7 değişkenli kayıt bildirim kolonlarına dokunmaz")
    void legacySaveKeepsNotice() {
        row.setWeeklyNoticeEnabled(true);
        row.setWeeklyNoticeDay(4);
        OnCallSchedule saved = svc.save(5L, true, "WEEKLY", "2026-11-02T08:30", List.of(11L), true, "u5");
        assertThat(saved.getWeeklyNoticeEnabled()).isTrue();
        assertThat(saved.getWeeklyNoticeDay()).isEqualTo(4);
    }

    @Test
    @DisplayName("görünüm: ayar + sıradaki gönderim + son sonuç (ham posta metni yok); kayıtlar okunamazsa bilinmiyor")
    @SuppressWarnings("unchecked")
    void viewFields() {
        row.setWeeklyNoticeEnabled(true);
        OnCallWeeklyNotice last = new OnCallWeeklyNotice();
        last.setWeekKey("2026-11-09");
        last.setStatus("SENT");
        last.setMailStatus("SENT");
        last.setPushCount(4);
        last.setClaimedAt("2026-11-09T05:30:05");
        last.setFinishedAt("2026-11-09T05:30:07");
        when(notices.findTopByTeamIdOrderByWeekKeyDescIdDesc(5L)).thenReturn(Optional.of(last));
        when(notices.findByTeamIdAndWeekKey(eq(5L), anyString())).thenReturn(Optional.empty());
        Map<String, Object> v = svc.view(5L, Instant.parse("2026-11-15T10:00:00Z"));
        assertThat((Map<String, Object>) v.get("weekly_notice")).containsEntry("enabled", true).containsEntry("mail", true)
                .containsEntry("push", true).containsEntry("day", 1).containsEntry("time", "08:30");
        assertThat(v.get("weekly_notice_next")).isEqualTo("2026-11-16T05:30:00");
        assertThat((Map<String, Object>) v.get("weekly_notice_last")).containsEntry("week", "2026-11-09").containsEntry("status", "SENT")
                .containsEntry("mail", "delivered").containsEntry("push_count", 4).containsEntry("at", "2026-11-09T05:30:07");
        when(notices.findTopByTeamIdOrderByWeekKeyDescIdDesc(5L)).thenThrow(new IllegalStateException("db"));
        Map<String, Object> bad = svc.view(5L, Instant.parse("2026-11-15T10:00:00Z"));
        assertThat(bad.get("weekly_notice_unreadable")).isEqualTo(true);
        assertThat(bad.get("weekly_notice_last")).isNull();
    }

    @Test
    @DisplayName("posta sonucu sınıfı: ham SMTP metni istemciye gitmez")
    void mailOutcome() {
        assertThat(OnCallService.mailOutcome("SENT")).isEqualTo("delivered");
        assertThat(OnCallService.mailOutcome("QUEUED_RETRY (421)")).isEqualTo("delivered");
        assertThat(OnCallService.mailOutcome(OnCallWeeklyNoticeService.MAIL_OFF)).isEqualTo("off");
        assertThat(OnCallService.mailOutcome("SKIPPED: pasif kullanıcı")).isEqualTo("skipped");
        assertThat(OnCallService.mailOutcome("FAILED: smtp.example.com:25 refused")).isEqualTo("failed");
        assertThat(OnCallService.mailOutcome(null)).isNull();
    }
}
