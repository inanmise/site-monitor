package com.sitemonitor.service;

import com.sitemonitor.model.*;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.EmailNotificationService.AvailabilityRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Haftalık erişilebilirlik raporu — availability/kesinti hesabı (computeRow) + gönderim akışı
 * (alıcı çözümü, idempotency, devre dışı, kesinti olmasa da gönderim). Repo'lar mock.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyAvailabilityReportServiceTest {

    @Mock TeamRepository teamRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock UptimeCheckRepository uptimeCheckRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock AppUserRepository userRepo;
    @Mock EmailNotificationService emailService;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock WeeklyAvailabilityLogRepository walRepo;
    @Mock AppSettingsService appSettings;

    private WeeklyAvailabilityReportService service;

    @BeforeEach
    void setUp() {
        service = new WeeklyAvailabilityReportService(teamRepo, inventoryRepo, uptimeCheckRepo,
                latestCheckRepo, contactRepo, userRepo, emailService, notificationLogRepo, walRepo, appSettings);
        when(appSettings.getBoolean(eq("site.monitor.weekly-availability.enabled"), anyBoolean())).thenReturn(true);
        when(emailService.sendHtml(any(), any(), any(), any(), any())).thenReturn("SENT");
        when(emailService.buildWeeklyAvailabilityHtml(any(), any(), any(), any())).thenReturn("<html></html>");
        when(emailService.getEmailFrom()).thenReturn("noreply@sitemonitor");
        when(walRepo.findByTeamIdAndReportYearAndWeekNo(anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(latestCheckRepo.findById(anyString())).thenReturn(Optional.empty());
    }

    private UptimeCheck uc(String status, Long ms, String checkedAt) {
        UptimeCheck c = new UptimeCheck();
        c.setDomain("x"); c.setPort(443);
        c.setStatus(status); c.setResponseMs(ms); c.setCheckedAt(checkedAt);
        return c;
    }

    private UptimeCheck ucMaint(String status, String checkedAt) {
        UptimeCheck c = uc(status, null, checkedAt);
        c.setMaintenance(true);
        return c;
    }

    @Test
    @DisplayName("computeRow: availability %, kesinti sayısı/süresi, ort/p95")
    void computeRow_basic() {
        List<UptimeCheck> checks = List.of(
                uc("up",   100L, "2026-06-15T00:00:00"),
                uc("up",   200L, "2026-06-15T00:01:00"),
                uc("down", null, "2026-06-15T00:02:00"),
                uc("down", null, "2026-06-15T00:03:00"),
                uc("up",   300L, "2026-06-15T00:04:00"));
        Instant windowEnd = Instant.parse("2026-06-15T00:05:00Z");

        AvailabilityRow r = service.computeRow("x", checks, windowEnd, 45);

        assertThat(r.availabilityPct()).isEqualTo(60.0);     // 3 up / 5
        assertThat(r.outageCount()).isEqualTo(1);
        assertThat(r.downtimeMinutes()).isEqualTo(2);        // 00:02 → 00:04 kurtarma
        assertThat(r.longestOutageMinutes()).isEqualTo(2);
        assertThat(r.avgMs()).isEqualTo(200);                // (100+200+300)/3
        assertThat(r.p95Ms()).isEqualTo(300);
        assertThat(r.certDaysRemaining()).isEqualTo(45);
    }

    @Test
    @DisplayName("computeRow: kurtarmasız kesinti pencere sonuna kadar sayılır")
    void computeRow_unresolvedOutage() {
        List<UptimeCheck> checks = List.of(
                uc("up",   100L, "2026-06-15T00:00:00"),
                uc("down", null, "2026-06-15T00:02:00"),
                uc("down", null, "2026-06-15T00:03:00"));
        Instant windowEnd = Instant.parse("2026-06-15T00:10:00Z");

        AvailabilityRow r = service.computeRow("x", checks, windowEnd, null);

        assertThat(r.outageCount()).isEqualTo(1);
        assertThat(r.downtimeMinutes()).isEqualTo(8);        // 00:02 → 00:10 (pencere sonu)
        assertThat(r.availabilityPct()).isEqualTo(33.33);    // 1 up / 3 (2 ondalık)
    }

    @Test
    @DisplayName("computeRow: bakım süresi downtime dakikalarına SAYILMAZ (yalnız %'den değil) (M2)")
    void computeRow_maintenanceExcludedFromDowntime() {
        List<UptimeCheck> checks = List.of(
                uc("down", null, "2026-06-15T10:00:00"),
                ucMaint("down", "2026-06-15T10:05:00"),      // bakım başladı
                ucMaint("down", "2026-06-15T10:30:00"),
                ucMaint("down", "2026-06-15T10:55:00"),      // bakım penceresi
                uc("up",   100L, "2026-06-15T11:00:00"));
        Instant windowEnd = Instant.parse("2026-06-15T11:05:00Z");

        AvailabilityRow r = service.computeRow("x", checks, windowEnd, null);

        // Kesinti yalnız 10:00 → 10:05 = 5 dk (bakımdaki 50 dk HARİÇ). Hatalı kod 10:00 → 11:00 = 60 dk sayardı.
        assertThat(r.downtimeMinutes()).isEqualTo(5);
        assertThat(r.longestOutageMinutes()).isEqualTo(5);
        // % yalnız bakım-dışı örneklerden: 1 up / 2 = %50.
        assertThat(r.availabilityPct()).isEqualTo(50.0);
        assertThat(r.outageCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("computeRow: bakımı köprüleyen TEK kesinti tek sayılır (M2-COUNT); bakım dakikaları yine hariç")
    void computeRow_outageBridgingMaintenanceCountedOnce() {
        List<UptimeCheck> checks = List.of(
                uc("down", null, "2026-06-15T10:00:00"),
                uc("down", null, "2026-06-15T10:02:00"),
                ucMaint("down", "2026-06-15T10:05:00"),      // bakım kesintiyi böler ama KAPATMAZ
                ucMaint("down", "2026-06-15T10:30:00"),
                uc("down", null, "2026-06-15T10:55:00"),      // bakımdan SONRA hâlâ down → aynı kesinti
                uc("down", null, "2026-06-15T10:58:00"),
                uc("up",   100L, "2026-06-15T11:00:00"));
        Instant windowEnd = Instant.parse("2026-06-15T11:05:00Z");

        AvailabilityRow r = service.computeRow("x", checks, windowEnd, null);

        // Tek sürekli kesinti (bakımla bölünmüş) → 1 sayılır, 2 DEĞİL. (M2-COUNT öncesi kod 2 sayardı.)
        assertThat(r.outageCount()).isEqualTo(1);
        // Süre: 10:00→10:05 (5dk) + 10:55→11:00 (5dk) = 10dk; aradaki 50dk bakım HARİÇ.
        assertThat(r.downtimeMinutes()).isEqualTo(10);
    }

    @Test
    @DisplayName("computeRow: bakım + araya GERÇEK kurtarma (up) → 2 ayrı kesinti sayılır")
    void computeRow_recoveryBetweenMaintenanceCountsTwo() {
        List<UptimeCheck> checks = List.of(
                uc("down", null, "2026-06-15T10:00:00"),
                ucMaint("down", "2026-06-15T10:05:00"),      // bakım (kesintiyi böler)
                uc("up",   100L, "2026-06-15T10:30:00"),      // GERÇEK kurtarma → kesinti kapanır
                uc("up",   100L, "2026-06-15T10:35:00"),
                uc("down", null, "2026-06-15T10:40:00"),      // yeni kesinti (araya kurtarma girdi)
                uc("up",   100L, "2026-06-15T10:45:00"));
        Instant windowEnd = Instant.parse("2026-06-15T10:50:00Z");

        AvailabilityRow r = service.computeRow("x", checks, windowEnd, null);
        assertThat(r.outageCount()).isEqualTo(2);   // kurtarma araya girdiği için köprüleme YOK
    }

    @Test
    @DisplayName("computeRow: veri yoksa availability null (ortalamaya katılmaz)")
    void computeRow_noData() {
        AvailabilityRow r = service.computeRow("x", List.of(), Instant.now(), null);
        assertThat(r.availabilityPct()).isNull();
        assertThat(r.outageCount()).isZero();
        assertThat(r.avgMs()).isNull();
    }

    @Test
    @DisplayName("computeRow: tek down örnek (2015/2016) 100'e yuvarlanmaz → %99.95 (2 ondalık)")
    void computeRow_singleDownNotRoundedTo100() {
        List<UptimeCheck> checks = new java.util.ArrayList<>();
        for (int i = 0; i < 2015; i++) checks.add(uc("up", 100L, "2026-06-15T00:00:00"));
        checks.add(uc("down", null, "2026-06-21T23:00:00"));
        AvailabilityRow r = service.computeRow("x", checks, Instant.parse("2026-06-22T00:00:00Z"), null);
        assertThat(r.availabilityPct()).isEqualTo(99.95);   // ÖNCEDEN 100.0 görünüyordu
        assertThat(r.outageCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("computeRow: down varsa availability asla 100.00 değil — clamp 99.99")
    void computeRow_clampNever100() {
        List<UptimeCheck> checks = new java.util.ArrayList<>();
        for (int i = 0; i < 20000; i++) checks.add(uc("up", 100L, "2026-06-15T00:00:00"));
        checks.add(uc("down", null, "2026-06-21T23:00:00"));  // 20000/20001 → yuvarlama 100.00 → clamp
        AvailabilityRow r = service.computeRow("x", checks, Instant.parse("2026-06-22T00:00:00Z"), null);
        assertThat(r.availabilityPct()).isEqualTo(99.99);
    }

    @Test
    @DisplayName("send: takım kutusu TO + PO/MANAGER kontakları CC; kesinti olmasa da gönderir")
    void send_toTeamWithCc_alwaysSends() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L))
                .thenReturn(List.of(inv("a.com"), inv("b.com")));
        // hepsi up → kesinti yok ama yine de gönderilmeli
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(5L, "PO"))
                .thenReturn(List.of(contact("po@x.com")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(5L, "MANAGER")).thenReturn(List.of());

        var result = service.sendWeeklyReports(false);

        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String[]> ccCap = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendHtml(toCap.capture(), ccCap.capture(), anyString(), anyString(), any());
        assertThat(toCap.getValue()).containsExactly("dijital@x.com");
        assertThat(ccCap.getValue()).contains("po@x.com");
        verify(walRepo).save(any(WeeklyAvailabilityLog.class));
        assertThat(result.sent()).isEqualTo(1);
    }

    @Test
    @DisplayName("Takım anahtarı KAPALI/NULL → o takıma erişilebilirlik raporu gitmez (domain'i olsa bile)")
    void send_teamWithAvailabilityDisabled_isSkipped() {
        Team off = team(5L, "Kapali", "kapali@x.com", false);
        Team nulls = team(6L, "Null", "null@x.com", true);
        nulls.setWeeklyAvailabilityEnabled(null);           // kolon yeni eklendi, dokunulmamış satır
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(off, nulls));

        var result = service.sendWeeklyReports(false);

        verify(emailService, never()).sendHtml(any(), any(), any(), any(), any());
        assertThat(result.sent()).isZero();
        assertThat(result.skippedDisabled()).isEqualTo(2);
        // Kapalı takım için domain sorgusu bile çalışmamalı.
        verify(inventoryRepo, never()).findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(anyLong());
    }

    @Test
    @DisplayName("İki anahtar BAĞIMSIZ: hatırlatma kapalı olsa da erişilebilirlik raporu gider")
    void send_switchesAreIndependent() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        t.setWeeklyReminderEnabled(false);                  // Cuma hatırlatması kapalı
        t.setWeeklyAvailabilityEnabled(true);               // Pazartesi raporu açık
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L)).thenReturn(List.of(inv("a.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));

        assertThat(service.sendWeeklyReports(false).sent()).isEqualTo(1);
    }

    @Test
    @DisplayName("send: devre dışıysa hiç gönderilmez")
    void send_disabled() {
        when(appSettings.getBoolean(eq("site.monitor.weekly-availability.enabled"), anyBoolean())).thenReturn(false);
        var result = service.sendWeeklyReports(false);
        verify(emailService, never()).sendHtml(any(), any(), any(), any(), any());
        assertThat(result.sent()).isZero();
    }

    @Test
    @DisplayName("send: aynı hafta SENT ise (force=false) atlanır; force=true gönderir")
    void send_idempotent() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L)).thenReturn(List.of(inv("a.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));
        WeeklyAvailabilityLog sentLog = new WeeklyAvailabilityLog();
        sentLog.setStatus("SENT");
        when(walRepo.findByTeamIdAndReportYearAndWeekNo(eq(5L), anyInt(), anyInt())).thenReturn(Optional.of(sentLog));

        assertThat(service.sendWeeklyReports(false).sent()).isZero();         // zaten gönderilmiş → atla
        verify(emailService, never()).sendHtml(any(), any(), any(), any(), any());

        assertThat(service.sendWeeklyReports(true).sent()).isEqualTo(1);      // force → gönder
        verify(emailService).sendHtml(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("send: domain'i olmayan takım atlanır")
    void send_skipsNoDomains() {
        Team t = team(9L, "Boş", "bos@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(9L)).thenReturn(List.of());

        var result = service.sendWeeklyReports(false);
        verify(emailService, never()).sendHtml(any(), any(), any(), any(), any());
        assertThat(result.skippedNoDomains()).isEqualTo(1);
    }

    @Test
    @DisplayName("send: takım kutusu yoksa CC'den ilki TO olur; hiç alıcı yoksa atlanır")
    void send_noTeamEmail_promotesCc_orSkips() {
        Team noMail = team(7L, "Mailsiz", null);
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(noMail));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(7L)).thenReturn(List.of(inv("a.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 100L, "2026-06-15T00:00:00")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(7L, "PO")).thenReturn(List.of(contact("po@x.com")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(7L, "MANAGER")).thenReturn(List.of());

        var result = service.sendWeeklyReports(false);
        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendHtml(toCap.capture(), any(), anyString(), anyString(), any());
        assertThat(toCap.getValue()).containsExactly("po@x.com");   // CC adayı TO'ya terfi
        assertThat(result.sent()).isEqualTo(1);
    }

    @Test
    @DisplayName("preview: tek takım HTML + çözülmüş TO/CC + domainCount; göndermez")
    void preview_buildsHtmlAndResolvesRecipients() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findById(5L)).thenReturn(Optional.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L))
                .thenReturn(List.of(inv("a.com"), inv("b.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(5L, "PO")).thenReturn(List.of(contact("po@x.com")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(5L, "MANAGER")).thenReturn(List.of());

        var p = service.preview(5L);

        assertThat(p.html()).isEqualTo("<html></html>");
        assertThat(p.teamName()).isEqualTo("Dijital");
        assertThat(p.to()).containsExactly("dijital@x.com");
        assertThat(p.cc()).contains("po@x.com");
        assertThat(p.domainCount()).isEqualTo(2);
        assertThat(p.noRecipients()).isFalse();
        verify(emailService, never()).sendHtml(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("preview: bilinmeyen takım → IllegalArgumentException")
    void preview_unknownTeam_throws() {
        when(teamRepo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.preview(99L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("windowForOffset: 1 = lastFullWeekWindow (geçen tam hafta); 0 = içinde bulunulan hafta (bitiş ≈ now)")
    void windowForOffset_currentVsLast() {
        var last = service.windowForOffset(1);
        var lastAlias = service.lastFullWeekWindow();
        assertThat(last.year()).isEqualTo(lastAlias.year());
        assertThat(last.week()).isEqualTo(lastAlias.week());
        assertThat(last.weekLabel()).isEqualTo(lastAlias.weekLabel());

        var cur = service.windowForOffset(0);
        LocalDate thisMonday = LocalDate.now(java.time.ZoneId.of("Europe/Istanbul"))
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        assertThat(cur.week())
                .isEqualTo(thisMonday.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()));
        assertThat(cur.week()).isNotEqualTo(last.week());                 // bu hafta ≠ geçen hafta
        assertThat(cur.windowEnd()).isBeforeOrEqualTo(Instant.now().plusSeconds(5));  // gelecek Pazar'a değil, now'a kırpılı
    }

    @Test
    @DisplayName("weekOptions: [0]=bu hafta(current), [1]=e-posta(emailed), 9 seçenek, etiketler dolu")
    void weekOptions_currentAndEmailedFlags() {
        var opts = service.weekOptions();
        assertThat(opts).hasSize(9);
        assertThat(opts.get(0).offset()).isZero();
        assertThat(opts.get(0).current()).isTrue();
        assertThat(opts.get(0).emailed()).isFalse();
        assertThat(opts.get(1).offset()).isEqualTo(1);
        assertThat(opts.get(1).current()).isFalse();
        assertThat(opts.get(1).emailed()).isTrue();
        assertThat(opts).allSatisfy(o -> assertThat(o.label()).isNotBlank());
    }

    @Test
    @DisplayName("preview(teamId, offset): seçilen haftanın etiketini kullanır; null → geçen hafta (offset 1)")
    void preview_usesSelectedWeek() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findById(5L)).thenReturn(Optional.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L)).thenReturn(List.of(inv("a.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(anyLong(), anyString())).thenReturn(List.of());

        assertThat(service.preview(5L, 0).weekLabel()).isEqualTo(service.windowForOffset(0).weekLabel());   // bu hafta
        assertThat(service.preview(5L, 2).weekLabel()).isEqualTo(service.windowForOffset(2).weekLabel());   // 2 hafta önce
        assertThat(service.preview(5L, null).weekLabel()).isEqualTo(service.preview(5L, 1).weekLabel());     // null = offset 1
    }

    @Test
    @DisplayName("sendTest: yalnız verilen adrese, cc yok, idempotency log'una YAZMAZ")
    void sendTest_sendsOnlyToGivenAddress_noCc_noWalLog() {
        Team t = team(5L, "Dijital", "dijital@x.com");
        when(teamRepo.findById(5L)).thenReturn(Optional.of(t));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L)).thenReturn(List.of(inv("a.com")));
        when(uptimeCheckRepo.findByDomainAndPortAndCheckedAtBetweenOrderByCheckedAtAsc(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(uc("up", 120L, "2026-06-15T00:00:00")));

        String status = service.sendTest(5L, "tester@x.com");
        assertThat(status).isEqualTo("SENT");

        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String[]> ccCap = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendHtml(toCap.capture(), ccCap.capture(), contains("[TEST]"), anyString(), any());
        assertThat(toCap.getValue()).containsExactly("tester@x.com");
        assertThat(ccCap.getValue()).isNull();
        verify(walRepo, never()).save(any(WeeklyAvailabilityLog.class));   // idempotency log'una dokunmaz

        // Arşiv: NotificationLog yazılır; alertEventId=0 sentinel (NOT NULL) + test trigger'ı
        ArgumentCaptor<NotificationLog> nlCap = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo).save(nlCap.capture());
        assertThat(nlCap.getValue().getAlertEventId()).isEqualTo(0L);
        assertThat(nlCap.getValue().getTrigger()).isEqualTo("WEEKLY_AVAILABILITY_TEST");
    }

    @Test
    @DisplayName("setEnabled: appSettings.save doğru key + values ile çağrılır")
    @SuppressWarnings("unchecked")
    void setEnabled_persistsViaAppSettings() {
        service.setEnabled(false, "admin");

        ArgumentCaptor<Map<String, Object>> bodyCap = ArgumentCaptor.forClass(Map.class);
        verify(appSettings).save(bodyCap.capture(), eq("admin"));
        Object values = bodyCap.getValue().get("values");
        assertThat(values).isInstanceOf(Map.class);
        assertThat(((Map<String, Object>) values).get("site.monitor.weekly-availability.enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("status: yalnız domain'i olan takımlar + alıcılar + son gönderim")
    void status_listsTeamsWithDomains() {
        Team a = team(5L, "Dijital", "dijital@x.com");
        Team b = team(9L, "Boş", "bos@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(a, b));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(5L)).thenReturn(List.of(inv("a.com")));
        when(inventoryRepo.findByTeamIdAndActiveTrueAndDeletedAtIsNullOrderByDomainAsc(9L)).thenReturn(List.of());
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(5L, "PO")).thenReturn(List.of(contact("po@x.com")));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(5L, "MANAGER")).thenReturn(List.of());
        WeeklyAvailabilityLog sentLog = new WeeklyAvailabilityLog();
        sentLog.setStatus("SENT"); sentLog.setSentAt("2026-06-15T08:00:00");
        when(walRepo.findByTeamIdAndReportYearAndWeekNo(eq(5L), anyInt(), anyInt())).thenReturn(Optional.of(sentLog));

        var st = service.status();

        assertThat(st.enabled()).isTrue();
        assertThat(st.teams()).hasSize(1);                  // domain'siz takım listede yok
        var ts = st.teams().get(0);
        assertThat(ts.name()).isEqualTo("Dijital");
        assertThat(ts.domainCount()).isEqualTo(1);
        assertThat(ts.to()).containsExactly("dijital@x.com");
        assertThat(ts.cc()).contains("po@x.com");
        assertThat(ts.lastStatus()).isEqualTo("SENT");
        assertThat(ts.lastSentAt()).isEqualTo("2026-06-15T08:00:00");
        verify(emailService, never()).sendHtml(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("history: trigger'a göre listeler + alanları eşler; includeTest=false → yalnız ana trigger")
    void history_listsAndMaps() {
        NotificationLog n = nlog(7L, "Dijital", "dijital@x.com", "po@x.com", "[SiteMonitor] Dijital", "SENT", "WEEKLY_AVAILABILITY");
        when(notificationLogRepo.findByTriggerInOrderBySentAtDesc(any(), any())).thenReturn(List.of(n));

        var list = service.history(50, false);

        assertThat(list).hasSize(1);
        var a = list.get(0);
        assertThat(a.id()).isEqualTo(7L);
        assertThat(a.team()).isEqualTo("Dijital");
        assertThat(a.to()).isEqualTo("dijital@x.com");
        assertThat(a.cc()).isEqualTo("po@x.com");
        assertThat(a.status()).isEqualTo("SENT");
        assertThat(a.trigger()).isEqualTo("WEEKLY_AVAILABILITY");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> trigCap = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(notificationLogRepo).findByTriggerInOrderBySentAtDesc(trigCap.capture(), any());
        assertThat(trigCap.getValue()).containsExactly("WEEKLY_AVAILABILITY");   // test maili hariç
    }

    @Test
    @DisplayName("history: includeTest=true → test trigger'ı da dahil")
    void history_includeTest() {
        when(notificationLogRepo.findByTriggerInOrderBySentAtDesc(any(), any())).thenReturn(List.of());
        service.history(50, true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> trigCap = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(notificationLogRepo).findByTriggerInOrderBySentAtDesc(trigCap.capture(), any());
        assertThat(trigCap.getValue()).contains("WEEKLY_AVAILABILITY", "WEEKLY_AVAILABILITY_TEST");
    }

    @Test
    @DisplayName("historyItem: haftalık trigger ise saklanan HTML döner")
    void historyItem_returnsHtml() {
        NotificationLog n = nlog(7L, "Dijital", "dijital@x.com", null, "konu", "SENT", "WEEKLY_AVAILABILITY");
        n.setMessage("<html>arşiv</html>");
        when(notificationLogRepo.findById(7L)).thenReturn(Optional.of(n));

        var d = service.historyItem(7L);
        assertThat(d.html()).isEqualTo("<html>arşiv</html>");
        assertThat(d.team()).isEqualTo("Dijital");
        assertThat(d.trigger()).isEqualTo("WEEKLY_AVAILABILITY");
    }

    @Test
    @DisplayName("historyItem: haftalık olmayan trigger → reddedilir (başka notifikasyon sızdırılmaz)")
    void historyItem_rejectsForeignTrigger() {
        NotificationLog n = nlog(8L, "X", "x@x.com", null, "k", "SENT", "ESCALATION");
        when(notificationLogRepo.findById(8L)).thenReturn(Optional.of(n));
        assertThatThrownBy(() -> service.historyItem(8L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("weekRangeLabel: TAM hafta Pzt–Paz (15–21, 15–19 DEĞİL); ay/yıl geçişleri")
    void weekRangeLabel_coversFullWeek() {
        assertThat(WeeklyAvailabilityReportService.weekRangeLabel(
                LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 21)))
                .isEqualTo("15–21 Haziran 2026");
        assertThat(WeeklyAvailabilityReportService.weekRangeLabel(
                LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 5)))
                .isEqualTo("29 Haziran – 5 Temmuz 2026");
        assertThat(WeeklyAvailabilityReportService.weekRangeLabel(
                LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4)))
                .isEqualTo("29 Aralık 2025 – 4 Ocak 2026");
    }

    @Test
    @DisplayName("schedulerHealth: enabled + cron + sıradaki çalışma + son çalışma özeti (gönderilen/hata)")
    void schedulerHealth_summary() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cronExpr", "0 0 10 ? * MON");
        WeeklyAvailabilityLog last = new WeeklyAvailabilityLog();
        last.setReportYear(2026); last.setWeekNo(25); last.setStatus("SENT"); last.setSentAt("2026-06-22T07:00:00");
        when(walRepo.findTopByOrderBySentAtDesc()).thenReturn(Optional.of(last));
        WeeklyAvailabilityLog a = new WeeklyAvailabilityLog(); a.setStatus("SENT");
        WeeklyAvailabilityLog b = new WeeklyAvailabilityLog(); b.setStatus("FAILED: smtp down");
        when(walRepo.findByReportYearAndWeekNo(2026, 25)).thenReturn(List.of(a, b));

        var m = service.schedulerHealth();

        assertThat(m.get("enabled")).isEqualTo(true);
        assertThat(m.get("cron")).isEqualTo("0 0 10 ? * MON");
        assertThat(m.get("next_run")).isNotNull();                 // cron'dan hesaplanır
        assertThat(m.get("last_run_at")).isEqualTo("2026-06-22T07:00:00");
        assertThat(m.get("last_run_sent")).isEqualTo(1L);
        assertThat(m.get("last_run_failed")).isEqualTo(1L);
        assertThat(m.get("last_run_teams")).isEqualTo(2);
        assertThat(m.get("last_run_week")).isNotNull();
    }

    @Test
    @DisplayName("schedulerHealth: hiç çalışma yoksa last_run_at null")
    void schedulerHealth_neverRun() {
        when(walRepo.findTopByOrderBySentAtDesc()).thenReturn(Optional.empty());
        var m = service.schedulerHealth();
        assertThat(m.get("last_run_at")).isNull();
        assertThat(m).doesNotContainKey("last_run_sent");
    }

    // ── helpers ──
    private NotificationLog nlog(Long id, String team, String to, String cc, String subject, String status, String trigger) {
        NotificationLog n = new NotificationLog();
        n.setId(id); n.setRecipientName(team); n.setRecipientEmail(to); n.setCc(cc);
        n.setSubject(subject); n.setEmailStatus(status); n.setTrigger(trigger);
        n.setSentAt("2026-06-15T08:00:00");
        return n;
    }

    /** Varsayılan fabrika: erişilebilirlik raporu anahtarı AÇIK — "kapalıysa gönderilmez" ayrı testte. */
    private Team team(Long id, String name, String email) {
        return team(id, name, email, true);
    }
    private Team team(Long id, String name, String email, boolean availabilityEnabled) {
        Team t = new Team(); t.setId(id); t.setName(name); t.setEmail(email); t.setActive(true);
        t.setWeeklyAvailabilityEnabled(availabilityEnabled); return t;
    }
    private CertificateInventory inv(String domain) {
        CertificateInventory c = new CertificateInventory(); c.setDomain(domain); c.setPort(443); c.setTeamId(5L); return c;
    }
    private EscalationContact contact(String email) {
        EscalationContact c = new EscalationContact(); c.setEmail(email); c.setActive(true); return c;
    }
}
