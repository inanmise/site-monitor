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
        when(schedules.touchUpdated(eq(5L), anyString(), any())).thenReturn(1);
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
        verify(schedules).touchUpdated(eq(5L), anyString(), eq("u5"));
        verify(schedules).setWeeklyNoticeEnabled(5L, true);
        verify(schedules).setWeeklyNoticeSince(eq(5L), anyString());
        verify(schedules, never()).setWeeklyNoticeMail(any(), any());
        verify(schedules, never()).setWeeklyNoticePush(any(), any());
        verify(schedules, never()).setWeeklyNoticeDay(any(), any());
        verify(schedules, never()).setWeeklyNoticeTime(any(), any());
        verify(schedules, never()).save(any());
        assertThat(OnCallService.noticeSnapshot(r.schedule())).containsEntry("weeklyNoticeEnabled", true)
                .containsEntry("weeklyNoticeDay", 1).containsEntry("weeklyNoticeTime", "08:30");
    }

    @Test
    @DisplayName("değişiklik yoksa hiçbir şey yazılmaz")
    void noChangeNoWrite() {
        OnCallService.WeeklyNoticeSave r = svc.saveWeeklyNotice(5L, patch(false, true, true, 1, "08:30"), "u5");
        assertThat(r.changed()).isFalse();
        verify(schedules, never()).touchUpdated(any(), any(), any());
    }

    @Test
    @DisplayName("eşzamanlı iki yönetici (biri gün, öteki saat): her kayıt YALNIZ kendi kolonunu yazar — biri ötekini geri almaz")
    void perColumnWrites() {
        row.setWeeklyNoticeEnabled(true);
        row.setWeeklyNoticeDay(1);
        row.setWeeklyNoticeTime("08:30");
        svc.saveWeeklyNotice(5L, patch(null, null, null, 3, null), "a");
        verify(schedules).setWeeklyNoticeDay(5L, 3);
        verify(schedules, never()).setWeeklyNoticeTime(any(), any());
        verify(schedules, never()).setWeeklyNoticeEnabled(any(), any());
        // ikinci yönetici aynı (bayat) satırı okumuştu: gün 1 — gün kolonu yazılmaz, yalnız saat
        row.setWeeklyNoticeDay(1);
        row.setWeeklyNoticeSince(null);
        svc.saveWeeklyNotice(5L, patch(null, null, null, null, "09:15"), "b");
        verify(schedules).setWeeklyNoticeTime(5L, "09:15");
        verify(schedules).setWeeklyNoticeDay(any(), any());   // yalnız ilk kayıttan
        verify(schedules, org.mockito.Mockito.times(2)).setWeeklyNoticeSince(eq(5L), anyString());   // açıkken zamanlama değişti
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
        verify(schedules, never()).touchUpdated(any(), any(), any());
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
        // var olan çizelgede bildirim kolonları tam kayıtla değil, yalnız değişenler hedefli UPDATE ile
        verify(schedules).setWeeklyNoticeEnabled(5L, true);
        verify(schedules).setWeeklyNoticePush(5L, false);
        verify(schedules).setWeeklyNoticeDay(5L, 3);
        verify(schedules).setWeeklyNoticeTime(5L, "09:15");
        verify(schedules).setWeeklyNoticeSince(eq(5L), anyString());
        verify(schedules, never()).setWeeklyNoticeMail(any(), any());
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
        last.setAttempts(1);
        last.setPushStatus("delivered");
        last.setSlotAt("2026-11-09T05:30:00");
        Map<String, Object> v = svc.view(5L, Instant.parse("2026-11-15T10:00:00Z"));
        assertThat((Map<String, Object>) v.get("weekly_notice")).containsEntry("enabled", true).containsEntry("mail", true)
                .containsEntry("push", true).containsEntry("day", 1).containsEntry("time", "08:30");
        assertThat(v.get("weekly_notice_next")).isEqualTo("2026-11-16T05:30:00");
        assertThat((Map<String, Object>) v.get("weekly_notice_last")).containsEntry("week", "2026-11-09").containsEntry("status", "SENT")
                .containsEntry("mail", "delivered").containsEntry("push_count", 4).containsEntry("at", "2026-11-09T05:30:07")
                .containsEntry("push", "delivered").containsEntry("attempts", 1).containsEntry("max_attempts", 3)
                .containsEntry("retry", false).containsEntry("stale", false);
        when(notices.findTopByTeamIdOrderByWeekKeyDescIdDesc(5L)).thenThrow(new IllegalStateException("db"));
        Map<String, Object> bad = svc.view(5L, Instant.parse("2026-11-15T10:00:00Z"));
        assertThat(bad.get("weekly_notice_unreadable")).isEqualTo(true);
        assertThat(bad.get("weekly_notice_last")).isNull();
    }

    @Test
    @DisplayName("sıradaki gönderim: yeniden denenecek FAILED satırı haftayı BİTİRMEZ; deneme hakkı bittiyse gelecek hafta")
    @SuppressWarnings("unchecked")
    void nextCountsRetryableFailedAsNotDone() {
        row.setWeeklyNoticeEnabled(true);
        OnCallWeeklyNotice failed = new OnCallWeeklyNotice();
        failed.setWeekKey("2026-11-16");
        failed.setStatus("FAILED");
        failed.setAttempts(1);
        failed.setSlotAt("2026-11-16T05:30:00");
        failed.setClaimedAt("2026-11-16T05:30:05");
        failed.setFinishedAt("2026-11-16T05:30:09");
        failed.setMailStatus("FAILED: bağlantı reddedildi");
        when(notices.findByTeamIdAndWeekKeyIn(eq(5L), any())).thenReturn(List.of(failed));
        when(notices.findTopByTeamIdOrderByWeekKeyDescIdDesc(5L)).thenReturn(Optional.of(failed));
        Instant mon1000 = Instant.parse("2026-11-16T07:00:00Z");
        Map<String, Object> v = svc.view(5L, mon1000);
        assertThat(v.get("weekly_notice_next")).isEqualTo("2026-11-16T05:30:00");          // pencere içinde yeniden denenecek
        assertThat((Map<String, Object>) v.get("weekly_notice_last")).containsEntry("retry", true).containsEntry("mail", "failed");
        failed.setAttempts(3);                                                            // deneme hakkı bitti
        Map<String, Object> done = svc.view(5L, mon1000);
        assertThat(done.get("weekly_notice_next")).isEqualTo("2026-11-23T05:30:00");
        assertThat((Map<String, Object>) done.get("weekly_notice_last")).containsEntry("retry", false);
        // e-postası iletilemeyen PARTIAL da yeniden denenir; e-postası atlanan PARTIAL kesin
        failed.setAttempts(1);
        failed.setStatus("PARTIAL");
        assertThat(svc.view(5L, mon1000).get("weekly_notice_next")).isEqualTo("2026-11-16T05:30:00");
        failed.setMailStatus("SKIPPED: takımın e-posta adresi yok");
        assertThat(svc.view(5L, mon1000).get("weekly_notice_next")).isEqualTo("2026-11-23T05:30:00");
        // bayat SENDING (15 dk'dan eski): sonuç bilinmiyor
        failed.setStatus("SENDING");
        failed.setFinishedAt(null);
        assertThat((Map<String, Object>) svc.view(5L, mon1000).get("weekly_notice_last")).containsEntry("stale", true);
    }

    @Test
    @DisplayName("sıradaki gönderim: aday haftaların satırları TEK sorguda okunur (hafta başına sorgu yok); bildirim kapalıyken hiç okunmaz")
    void nextReadsCandidateWeeksInOneQuery() {
        Instant mon1000 = Instant.parse("2026-11-16T07:00:00Z");
        row.setWeeklyNoticeEnabled(true);
        svc.view(5L, mon1000);
        verify(notices, org.mockito.Mockito.times(1)).findByTeamIdAndWeekKeyIn(5L,
                List.of("2026-11-09", "2026-11-16", "2026-11-23", "2026-11-30"));
        verify(notices, never()).findByTeamIdAndWeekKey(any(), any());
        assertThat(OnCallWeeklyNoticeSlots.nextWeekKeys(mon1000)).containsExactly("2026-11-09", "2026-11-16", "2026-11-23", "2026-11-30");
        // kapalı bildirim: sıradaki gönderim yok, aday hafta satırları okunmaz
        org.mockito.Mockito.clearInvocations(notices);
        row.setWeeklyNoticeEnabled(false);
        assertThat(svc.view(5L, mon1000).get("weekly_notice_next")).isNull();
        verify(notices, never()).findByTeamIdAndWeekKeyIn(any(), any());
        verify(notices, never()).findByTeamIdAndWeekKey(any(), any());
    }

    @Test
    @DisplayName("posta sonucu sınıfı: ham SMTP metni istemciye gitmez")
    void mailOutcome() {
        assertThat(OnCallService.mailOutcome("SENT")).isEqualTo("delivered");
        assertThat(OnCallService.mailOutcome("QUEUED_RETRY (421)")).isEqualTo("queued");
        assertThat(OnCallService.mailOutcome(OnCallWeeklyNoticeService.MAIL_OFF)).isEqualTo("off");
        assertThat(OnCallService.mailOutcome("SKIPPED: pasif kullanıcı")).isEqualTo("skipped");
        assertThat(OnCallService.mailOutcome("FAILED: smtp.example.com:25 refused")).isEqualTo("failed");
        assertThat(OnCallService.mailOutcome(null)).isNull();
    }
}
