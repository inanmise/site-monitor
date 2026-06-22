package com.certmonitor.service;

import com.certmonitor.model.*;
import com.certmonitor.repository.*;
import com.certmonitor.service.EmailNotificationService.AvailabilityRow;
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
        when(appSettings.getBoolean(eq("cert.monitor.weekly-availability.enabled"), anyBoolean())).thenReturn(true);
        when(emailService.sendHtml(any(), any(), any(), any(), any())).thenReturn("SENT");
        when(emailService.buildWeeklyAvailabilityHtml(any(), any(), any(), any())).thenReturn("<html></html>");
        when(emailService.getEmailFrom()).thenReturn("noreply@certmonitor");
        when(walRepo.findByTeamIdAndReportYearAndWeekNo(anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(latestCheckRepo.findById(anyString())).thenReturn(Optional.empty());
    }

    private UptimeCheck uc(String status, Long ms, String checkedAt) {
        UptimeCheck c = new UptimeCheck();
        c.setDomain("x"); c.setPort(443);
        c.setStatus(status); c.setResponseMs(ms); c.setCheckedAt(checkedAt);
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
        assertThat(r.availabilityPct()).isEqualTo(33.3);     // 1 up / 3
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
    @DisplayName("send: devre dışıysa hiç gönderilmez")
    void send_disabled() {
        when(appSettings.getBoolean(eq("cert.monitor.weekly-availability.enabled"), anyBoolean())).thenReturn(false);
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
        assertThat(((Map<String, Object>) values).get("cert.monitor.weekly-availability.enabled")).isEqualTo("false");
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
        NotificationLog n = nlog(7L, "Dijital", "dijital@x.com", "po@x.com", "[CertMonitor] Dijital", "SENT", "WEEKLY_AVAILABILITY");
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

    private Team team(Long id, String name, String email) {
        Team t = new Team(); t.setId(id); t.setName(name); t.setEmail(email); t.setActive(true); return t;
    }
    private CertificateInventory inv(String domain) {
        CertificateInventory c = new CertificateInventory(); c.setDomain(domain); c.setPort(443); c.setTeamId(5L); return c;
    }
    private EscalationContact contact(String email) {
        EscalationContact c = new EscalationContact(); c.setEmail(email); c.setActive(true); return c;
    }
}
