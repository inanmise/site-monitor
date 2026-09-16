package com.sitemonitor.service;

import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyReportReminderServiceTest {

    @Mock TeamRepository teamRepo;
    @Mock WeeklyReportRepository reportRepo;
    @Mock EmailNotificationService emailService;
    @Mock AppSettingsService appSettings;

    private WeeklyReportReminderService service;

    @BeforeEach
    void setUp() {
        service = new WeeklyReportReminderService(teamRepo, reportRepo, emailService, appSettings);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://cm.example.com/");
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(emailService.buildWeeklyReportReminderHtml(any(), any(), any(), any())).thenReturn("<html/>");
        when(emailService.sendHtml(any(), any(), anyString(), anyString(), any())).thenReturn("SENT");
    }

    /** Varsayılan fabrika: hatırlatma anahtarı AÇIK — "kapalıysa gönderilmez" davranışı ayrı testte. */
    private static Team team(Long id, String name, String email) {
        return team(id, name, email, true);
    }

    private static Team team(Long id, String name, String email, boolean reminderEnabled) {
        Team t = new Team();
        t.setId(id); t.setName(name); t.setEmail(email); t.setActive(true);
        t.setWeeklyReminderEnabled(reminderEnabled);
        t.setWeeklyReportsEnabled(true);   // modül açık (2026-09-16); kapalı takım davranışı ayrı testte
        return t;
    }

    private static WeeklyReport reportWithStatus(String status) {
        WeeklyReport r = new WeeklyReport();
        r.setStatus(status);
        return r;
    }

    @Test
    @DisplayName("sendFridayReminders: yalnız onaya-göndermemiş + e-postası olan SY takımlarına gönderir")
    void sendsOnlyToTeamsThatHaveNotSubmitted() {
        Team a = team(1L, "AlphaSY",   "alpha@x.com");   // rapor yok       → gönder
        Team b = team(2L, "BetaSY",    "beta@x.com");    // DRAFT           → gönder
        Team c = team(3L, "GammaSY",   "gamma@x.com");   // APPROVED        → atla (girilmiş)
        Team d = team(4L, "DeltaSY",   "");              // rapor yok, mail yok → atla
        Team e = team(5L, "EpsilonSY", "eps@x.com");     // PENDING_APPROVAL → atla (girilmiş)
        when(teamRepo.findByActiveTrueOrderByNameAsc())
                .thenReturn(List.of(a, b, c, d, e));

        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(eq(1L), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(eq(2L), anyInt(), anyInt())).thenReturn(Optional.of(reportWithStatus("DRAFT")));
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(eq(3L), anyInt(), anyInt())).thenReturn(Optional.of(reportWithStatus("APPROVED")));
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(eq(4L), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(eq(5L), anyInt(), anyInt())).thenReturn(Optional.of(reportWithStatus("PENDING_APPROVAL")));

        WeeklyReportReminderService.ReminderResult res = service.sendFridayReminders();

        assertThat(res.candidates()).isEqualTo(5);
        assertThat(res.sent()).isEqualTo(2);
        assertThat(res.skippedNoEmail()).isEqualTo(1);
        assertThat(res.skippedDone()).isEqualTo(2);

        // İki gönderim; alıcılar alpha + beta
        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        verify(emailService, times(2)).sendHtml(toCap.capture(), isNull(), anyString(), anyString(), isNull());
        assertThat(toCap.getAllValues().stream().map(arr -> arr[0]))
                .containsExactlyInAnyOrder("alpha@x.com", "beta@x.com");
    }

    @Test
    @DisplayName("sendFridayReminders: mail linki ?tab=weeklyreports deep-link içerir (çift / temizlenir)")
    void buildsDeepLinkUrl() {
        Team a = team(1L, "AlphaSY", "alpha@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(java.util.List.of(a));
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());

        service.sendFridayReminders();

        ArgumentCaptor<String> urlCap = ArgumentCaptor.forClass(String.class);
        verify(emailService).buildWeeklyReportReminderHtml(eq("AlphaSY"), anyString(), urlCap.capture(), anyString());
        assertThat(urlCap.getValue()).isEqualTo("https://cm.example.com/?tab=weeklyreports");
    }

    @Test
    @DisplayName("Takım anahtarı KAPALI (veya hiç açılmamış) → o takıma hatırlatma gitmez")
    void teamWithReminderDisabledIsSkipped() {
        Team on   = team(1L, "AcikSY",   "acik@x.com",  true);
        Team off  = team(2L, "KapaliSY", "kapali@x.com", false);
        Team nulls = team(3L, "NullSY",  "null@x.com",  true);
        nulls.setWeeklyReminderEnabled(null);   // kolon yeni eklendi, hiç dokunulmamış satır
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(on, off, nulls));
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());

        WeeklyReportReminderService.ReminderResult res = service.sendFridayReminders();

        assertThat(res.sent()).isEqualTo(1);
        assertThat(res.skippedDisabled()).isEqualTo(2);   // kapalı + NULL
        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        verify(emailService, times(1)).sendHtml(toCap.capture(), isNull(), anyString(), anyString(), isNull());
        assertThat(toCap.getValue()[0]).isEqualTo("acik@x.com");
        // Kapalı takım için rapor durumu bile sorgulanmaz (gereksiz iş yok).
        verify(reportRepo, never()).findByTeamIdAndReportYearAndWeekNo(eq(2L), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Anahtarı açık ama PASİF takım → gönderilmez (aday sorgusu yalnız aktifleri döndürür)")
    void inactiveTeamNeverReceives() {
        // findByActiveTrueOrderByNameAsc pasif takımı zaten getirmez; sözleşmeyi sabitle.
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of());

        WeeklyReportReminderService.ReminderResult res = service.sendFridayReminders();

        assertThat(res.candidates()).isZero();
        assertThat(res.sent()).isZero();
        verify(emailService, never()).sendHtml(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("sendFridayReminders: devre dışıyken hiç gönderim yapmaz")
    void disabledSkipsEntirely() {
        ReflectionTestUtils.setField(service, "enabled", false);

        WeeklyReportReminderService.ReminderResult res = service.sendFridayReminders();

        assertThat(res.sent()).isZero();
        verifyNoInteractions(teamRepo, reportRepo);
        verify(emailService, never()).sendHtml(any(), any(), anyString(), anyString(), any());
    }
    // ── Son giriş zamanı canlı ayardan (2026-09-12) ─────────────────────────────────────────

    @Test
    @DisplayName("2026-09-12: zamanlanmış koşu yalnız SON GİRİŞ GÜNÜNDE gönderir; elle tetik gün bakmaz; mail metni ayardan")
    void scheduledRun_onlyOnDeadlineDay() {
        Team a = team(1L, "AlphaSY", "alpha@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(java.util.List.of(a));
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());

        // Son giriş günü = YARIN (kayan: bugünün gününe göre) → zamanlanmış koşu atlar, elle tetik gönderir
        java.time.DayOfWeek tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Istanbul")).plusDays(1).getDayOfWeek();
        when(appSettings.getString(eq(WeeklyReportDeadline.KEY_DAY), any())).thenReturn(tomorrow.name().substring(0, 3));
        when(appSettings.getString(eq(WeeklyReportDeadline.KEY_TIME), any())).thenReturn("17:30");

        assertThat(service.sendFridayReminders(true).sent()).isEqualTo(0);
        verify(emailService, org.mockito.Mockito.never()).sendHtml(any(), isNull(), anyString(), anyString(), isNull());

        assertThat(service.sendFridayReminders(false).sent()).isEqualTo(1);
        ArgumentCaptor<String> dl = ArgumentCaptor.forClass(String.class);
        verify(emailService).buildWeeklyReportReminderHtml(eq("AlphaSY"), anyString(), anyString(), dl.capture());
        String[] tr = {"Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar"};
        assertThat(dl.getValue()).isEqualTo(tr[tomorrow.getValue() - 1] + " saat 17:30");

        // Son giriş günü = BUGÜN → zamanlanmış koşu gönderir, metin "bugün saat 17:30"
        org.mockito.Mockito.clearInvocations(emailService);
        java.time.DayOfWeek today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Istanbul")).getDayOfWeek();
        when(appSettings.getString(eq(WeeklyReportDeadline.KEY_DAY), any())).thenReturn(today.name().substring(0, 3));
        assertThat(service.sendFridayReminders(true).sent()).isEqualTo(1);
        verify(emailService).buildWeeklyReportReminderHtml(eq("AlphaSY"), anyString(), anyString(), eq("bugün saat 17:30"));
    }
    @Test
    @DisplayName("2026-09-16: Haftalık Raporlar modülü KAPALI takıma hatırlatma gitmez (hatırlatma anahtarı açık olsa bile)")
    void moduleDisabledTeamIsSkipped() {
        Team off = team(9L, "Kapali", "kapali@example.com", true);
        off.setWeeklyReportsEnabled(false);
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(off));

        WeeklyReportReminderService.ReminderResult r = service.sendFridayReminders();

        assertThat(r.sent()).isEqualTo(0);
        assertThat(r.skippedDisabled()).isEqualTo(1);   // aday listesindeydi ama modül kapalı diye atlandı
        verify(emailService, never()).sendHtml(any(), any(), anyString(), anyString(), any());
    }
}
