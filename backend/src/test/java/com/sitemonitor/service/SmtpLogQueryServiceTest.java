package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SMTP Gönderim Logu sorgu servisi (2026-09-19): süzgeç/kapsam/sıralama/sayfalama, özet (KPI, kova, takım,
 * hata sınıfı, alıcı/alan), detay zinciri, yeniden gönderim kuralları. {@code now} enjekte (kayan pencere).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("unchecked")
class SmtpLogQueryServiceTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");

    @Mock NotificationLogRepository logRepo;
    @Mock AlertEventRepository alertRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock TeamRepository teamRepo;
    @Mock EmailNotificationService emailService;
    SmtpLogQueryService svc;

    private static String at(long minutesAgo) { return ISO.format(NOW.minus(Duration.ofMinutes(minutesAgo))); }

    /** Sıra: id, alertEventId, sentAt, recipientName, recipientEmail, recipientRole, subject, emailStatus, webhookStatus, trigger, emailFrom, cc */
    private static Object[] row(long id, Long ae, String at, String name, String email, String subject, String status, String trigger) {
        return new Object[]{id, ae, at, name, email, "TEAM", subject, status, "SKIPPED", trigger, "noreply@example.com", null};
    }

    private static AlertEvent ae(long id, String domain, Long team, String level, boolean resolved) {
        AlertEvent e = new AlertEvent(); e.setId(id); e.setDomain(domain); e.setTeamId(team); e.setAlertLevel(level); e.setAlertType("HTTP_DOWN"); e.setResolved(resolved); return e;
    }

    @BeforeEach
    void setUp() {
        svc = new SmtpLogQueryService(logRepo, alertRepo, inventoryRepo, teamRepo, emailService, null);
        Team a = new Team(); a.setId(1L); a.setName("Takım A");
        Team b = new Team(); b.setId(2L); b.setName("Takım B");
        when(teamRepo.findAll()).thenReturn(List.of(a, b));
        CertificateInventory inv = new CertificateInventory(); inv.setDomain("inv.example.com"); inv.setTeamId(2L); inv.setActive(true);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));
        // olay 10: takım 1 / a.example.com; olay 11: takımsız ama envanter alanı (→ takım 2); olay 12: takım 2 çözülmüş
        when(alertRepo.findAllById(any())).thenReturn(List.of(ae(10, "a.example.com", 1L, "CRITICAL", false),
                ae(11, "inv.example.com", null, "WARNING", false), ae(12, "b.example.com", 2L, "HIGH", true)));
        when(logRepo.findWindowRows(anyString(), anyString())).thenReturn(List.of(
                row(1, 10L, at(5), "Ops", "ops@example.com", "[CRITICAL] a.example.com down", "SENT", "INITIAL"),
                row(2, 10L, at(4), "Ops", "ops@example.com", "[CRITICAL] a.example.com down", "FAILED: 550 5.1.1 mailbox unavailable", "ESCALATION"),
                row(3, 11L, at(3), "Team", "team@example.com", "[WARNING] inv.example.com", "FAILED: Connection timed out", "INITIAL"),
                row(4, 12L, at(2), "Mgr", "mgr@example.com", "[HIGH] b.example.com", "SKIPPED_DISABLED", "DAILY_REALERT"),
                row(5, null, at(1), "Admin", "admin@example.com", "SMTP test", "FAILED: 535 Authentication failed", "MANUAL"),
                row(6, 12L, at(60 * 30), "Mgr", "mgr@example.com", "[HIGH] b.example.com", "SENT", "RESOLUTION")));
    }

    private SmtpLogQueryService.Filter f(String status, String trigger, Long team, String q) {
        return new SmtpLogQueryService.Filter(at(60 * 48), null, status, trigger, team, null, null, null, q, null);
    }

    @Test
    @DisplayName("sınıflandırma: kind SENT/FAILED/SKIPPED/QUEUED/UNKNOWN; hata metni önekten arınır; sınıf TIMEOUT>AUTH>RATE>RECIPIENT>CONNECT>OTHER")
    void classify() {
        assertThat(SmtpLogQueryService.kindOf("SENT")).isEqualTo("SENT");
        assertThat(SmtpLogQueryService.kindOf("FAILED: x")).isEqualTo("FAILED");
        assertThat(SmtpLogQueryService.kindOf("SKIPPED_DISABLED")).isEqualTo("SKIPPED");
        assertThat(SmtpLogQueryService.kindOf("QUEUED_RETRY")).isEqualTo("QUEUED");
        assertThat(SmtpLogQueryService.kindOf(null)).isEqualTo("UNKNOWN");
        assertThat(SmtpLogQueryService.errorOf("FAILED: 550 no")).isEqualTo("550 no");
        assertThat(SmtpLogQueryService.errorOf("SKIPPED_DISABLED")).isEqualTo("DISABLED");
        assertThat(SmtpLogQueryService.errorOf("SENT")).isEmpty();
        assertThat(SmtpLogQueryService.classifyError("FAILED: Connection timed out")).isEqualTo("TIMEOUT");
        assertThat(SmtpLogQueryService.classifyError("FAILED: 535 5.7.8 Authentication credentials invalid")).isEqualTo("AUTH");
        assertThat(SmtpLogQueryService.classifyError("FAILED: 421 too many connections")).isEqualTo("RATE");
        assertThat(SmtpLogQueryService.classifyError("FAILED: 550 5.1.1 mailbox unavailable")).isEqualTo("RECIPIENT");
        assertThat(SmtpLogQueryService.classifyError("FAILED: Could not connect to SMTP host")).isEqualTo("CONNECT");
        assertThat(SmtpLogQueryService.classifyError("FAILED: something odd")).isEqualTo("OTHER");
        assertThat(SmtpLogQueryService.classifyError("SKIPPED_DISABLED")).isNull();
    }

    @Test
    @DisplayName("arama: global kapsam 6 satır yeni üstte; zenginleştirme (takım adı, alan, envanter-türevi takım, alarm seviyesi); sayfalama total'i korur")
    void search_globalPaged() {
        Map<String, Object> r = svc.search(f(null, null, null, null), SmtpLogQueryService.Scope.all(), 0, 4, NOW);
        assertThat(r).containsEntry("total", 6).containsEntry("page", 0).containsEntry("size", 4);
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.get("items");
        assertThat(items).hasSize(4);
        assertThat(items.get(0)).containsEntry("id", 5L).containsEntry("kind", "FAILED").containsEntry("error_class", "AUTH").containsEntry("domain", null);
        assertThat(items.get(2)).containsEntry("id", 3L).containsEntry("team_id", 2L).containsEntry("team_name", "Takım B").containsEntry("domain", "inv.example.com");
        assertThat(items.get(3)).containsEntry("id", 2L).containsEntry("error", "550 5.1.1 mailbox unavailable").containsEntry("alert_level", "CRITICAL").containsEntry("team_name", "Takım A");
        Map<String, Object> p2 = svc.search(f(null, null, null, null), SmtpLogQueryService.Scope.all(), 1, 4, NOW);
        assertThat((List<?>) p2.get("items")).hasSize(2);
    }

    @Test
    @DisplayName("kapsam: takım 2 kullanıcısı yalnız takım 2 / envanter alanı satırlarını görür (takımsız test maili görünmez); süzgeçler durum+tetikleyici+takım+q")
    void search_scopeAndFilters() {
        SmtpLogQueryService.Scope scoped = new SmtpLogQueryService.Scope(false, Set.of(2L), Set.of("inv.example.com"));
        Map<String, Object> r = svc.search(f(null, null, null, null), scoped, 0, 25, NOW);
        assertThat(((List<Map<String, Object>>) r.get("items"))).extracting(m -> m.get("id")).containsExactly(4L, 3L, 6L);
        assertThat(svc.search(f("FAILED", null, null, null), SmtpLogQueryService.Scope.all(), 0, 25, NOW)).containsEntry("total", 3);
        assertThat(svc.search(f(null, "RESOLUTION", null, null), SmtpLogQueryService.Scope.all(), 0, 25, NOW)).containsEntry("total", 1);
        assertThat(svc.search(f(null, null, 1L, null), SmtpLogQueryService.Scope.all(), 0, 25, NOW)).containsEntry("total", 2);
        assertThat(svc.search(f(null, null, null, "mailbox"), SmtpLogQueryService.Scope.all(), 0, 25, NOW)).containsEntry("total", 1);   // hata metninde
        assertThat(svc.search(f(null, null, null, "takım b"), SmtpLogQueryService.Scope.all(), 0, 25, NOW)).containsEntry("total", 3);   // takım adında
        SmtpLogQueryService.Filter cls = new SmtpLogQueryService.Filter(at(60 * 48), null, null, null, null, null, null, "TIMEOUT", null, null);
        assertThat(svc.search(cls, SmtpLogQueryService.Scope.all(), 0, 25, NOW)).containsEntry("total", 1);
    }

    @Test
    @DisplayName("sıralama: recipient,asc alfabetik (eşitlikte yeni üstte); sent_at,asc en eski üstte; pencere tavanı 365 gün, from yoksa 7 gün")
    void sortAndBounds() {
        SmtpLogQueryService.Filter byRecipient = new SmtpLogQueryService.Filter(at(60 * 48), null, null, null, null, null, null, null, null, "recipient,asc");
        List<Map<String, Object>> items = (List<Map<String, Object>>) svc.search(byRecipient, SmtpLogQueryService.Scope.all(), 0, 25, NOW).get("items");
        assertThat(items).extracting(m -> m.get("id")).containsExactly(5L, 4L, 6L, 2L, 1L, 3L);
        SmtpLogQueryService.Filter oldest = new SmtpLogQueryService.Filter(at(60 * 48), null, null, null, null, null, null, null, null, "sent_at,asc");
        items = (List<Map<String, Object>>) svc.search(oldest, SmtpLogQueryService.Scope.all(), 0, 2, NOW).get("items");
        assertThat(items).extracting(m -> m.get("id")).containsExactly(6L, 1L);
        String[] b = SmtpLogQueryService.bounds(new SmtpLogQueryService.Filter(null, null, null, null, null, null, null, null, null, null), NOW);
        assertThat(b[0]).isEqualTo(at(7 * 1440)); assertThat(b[1]).isNull();
        b = SmtpLogQueryService.bounds(new SmtpLogQueryService.Filter("2020-01-01T00:00:00", "2026-09-19T00:00:00", null, null, null, null, null, null, null, null), NOW);
        assertThat(b[0]).isEqualTo(at(365 * 1440)); assertThat(b[1]).isEqualTo("2026-09-19T00:00:00");
    }

    @Test
    @DisplayName("özet: KPI (2 sent / 3 failed / 1 skipped, oran %40, başarısız alıcı 3), saatlik kova (48 sa ≤ 72), takım kırılımı başarısız üstte, hata sınıfları, alıcı/alan özeti")
    void summary() {
        Map<String, Object> s = svc.summary(f(null, null, null, null), SmtpLogQueryService.Scope.all(), NOW);
        Map<String, Object> kpi = (Map<String, Object>) s.get("kpi");
        assertThat(kpi).containsEntry("total", 6).containsEntry("sent", 2).containsEntry("failed", 3).containsEntry("skipped", 1)
                .containsEntry("success_rate", 40.0).containsEntry("failed_recipients", 3).containsEntry("last_sent_at", at(5)).containsEntry("last_failed_at", at(1));
        assertThat(s).containsEntry("granularity", "hour");
        List<Map<String, Object>> tl = (List<Map<String, Object>>) s.get("timeline");
        assertThat(tl).hasSize(49);   // 48 saat + şimdiki saat, boş kovalar dâhil
        Map<String, Object> lastBucket = tl.get(tl.size() - 1);
        assertThat(lastBucket).containsEntry("bucket", "2026-09-19T12:00:00").containsEntry("sent", 0).containsEntry("failed", 0);
        Map<String, Object> prev = tl.get(tl.size() - 2);   // 11:xx → 5 satır: 1 sent, 3 failed, 1 skipped
        assertThat(prev).containsEntry("bucket", "2026-09-19T11:00:00").containsEntry("sent", 1).containsEntry("failed", 3).containsEntry("skipped", 1);
        List<Map<String, Object>> teams = (List<Map<String, Object>>) s.get("teams");
        assertThat(teams.get(0)).containsEntry("team_name", "Takım B").containsEntry("failed", 1).containsEntry("total", 3).containsEntry("success_rate", 50.0);
        assertThat(teams.get(1)).containsEntry("team_name", "Takım A").containsEntry("failed", 1).containsEntry("sent", 1).containsEntry("success_rate", 50.0);
        assertThat(teams.get(2)).containsEntry("team_id", null).containsEntry("total", 1);
        List<Map<String, Object>> classes = (List<Map<String, Object>>) s.get("error_classes");
        assertThat(classes).extracting(m -> m.get("error_class")).containsExactlyInAnyOrder("RECIPIENT", "TIMEOUT", "AUTH");
        List<Map<String, Object>> rec = (List<Map<String, Object>>) s.get("top_recipients");
        assertThat(rec.get(0)).containsEntry("recipient_email", "ops@example.com").containsEntry("total", 2).containsEntry("failed", 1);
        List<Map<String, Object>> dom = (List<Map<String, Object>>) s.get("top_domains");
        assertThat(dom).extracting(m -> m.get("domain")).containsExactly("a.example.com", "inv.example.com", "b.example.com");
        assertThat((List<Map<String, Object>>) s.get("triggers")).extracting(m -> m.get("trigger")).contains("INITIAL", "ESCALATION", "MANUAL");
    }

    @Test
    @DisplayName("özet: 10 günlük pencere günlük kovaya düşer")
    void summary_dailyBuckets() {
        SmtpLogQueryService.Filter ten = new SmtpLogQueryService.Filter(at(10 * 1440), null, null, null, null, null, null, null, null, null);
        Map<String, Object> s = svc.summary(ten, SmtpLogQueryService.Scope.all(), NOW);
        assertThat(s).containsEntry("granularity", "day");
        assertThat((List<?>) s.get("timeline")).hasSize(11);
    }

    @Test
    @DisplayName("detay: gövde + alarm özeti + zincir; kapsam dışı → null")
    void detail() {
        NotificationLog n = new NotificationLog(); n.setId(2L); n.setAlertEventId(10L); n.setSentAt(at(4)); n.setRecipientEmail("ops@example.com");
        n.setSubject("s"); n.setEmailStatus("FAILED: 550 x"); n.setTrigger("ESCALATION"); n.setMessage("<html>body</html>");
        when(logRepo.findById(2L)).thenReturn(Optional.of(n));
        when(alertRepo.findById(10L)).thenReturn(Optional.of(ae(10, "a.example.com", 1L, "CRITICAL", false)));
        when(logRepo.findChainRows(10L)).thenReturn(List.of(row(2, 10L, at(4), "Ops", "ops@example.com", "s", "FAILED: 550 x", "ESCALATION"),
                row(1, 10L, at(5), "Ops", "ops@example.com", "s", "SENT", "INITIAL")));

        Map<String, Object> d = svc.detail(2L, SmtpLogQueryService.Scope.all());
        assertThat(d).containsEntry("message", "<html>body</html>").containsEntry("error_class", "RECIPIENT");
        assertThat((Map<String, Object>) d.get("alert")).containsEntry("domain", "a.example.com").containsEntry("resolved", false);
        assertThat((List<?>) d.get("chain")).hasSize(2);
        assertThat(svc.detail(2L, new SmtpLogQueryService.Scope(false, Set.of(2L), Set.of()))).isNull();
    }

    @Test
    @DisplayName("yeniden gönder: yalnız FAILED, çözülmüş alarm reddedilir, kapsam dışı NOT_FOUND; başarıda yeni log satırı (MANUAL, aynı alıcı/konu/gövde), logo varyantı seviyeden")
    void resend() {
        NotificationLog failed = new NotificationLog(); failed.setId(2L); failed.setAlertEventId(10L); failed.setSentAt(at(4)); failed.setRecipientName("Ops");
        failed.setRecipientEmail("ops@example.com"); failed.setSubject("subj"); failed.setEmailStatus("FAILED: 550 x"); failed.setTrigger("ESCALATION"); failed.setMessage("<html>b</html>");
        NotificationLog sent = new NotificationLog(); sent.setId(1L); sent.setAlertEventId(10L); sent.setEmailStatus("SENT"); sent.setRecipientEmail("ops@example.com"); sent.setMessage("x");
        NotificationLog resolvedFail = new NotificationLog(); resolvedFail.setId(7L); resolvedFail.setAlertEventId(12L); resolvedFail.setEmailStatus("FAILED: y"); resolvedFail.setRecipientEmail("mgr@example.com"); resolvedFail.setMessage("x");
        when(logRepo.findById(2L)).thenReturn(Optional.of(failed));
        when(logRepo.findById(1L)).thenReturn(Optional.of(sent));
        when(logRepo.findById(7L)).thenReturn(Optional.of(resolvedFail));
        when(alertRepo.findById(10L)).thenReturn(Optional.of(ae(10, "a.example.com", 1L, "CRITICAL", false)));
        when(alertRepo.findById(12L)).thenReturn(Optional.of(ae(12, "b.example.com", 2L, "HIGH", true)));
        when(emailService.resendStoredHtml(eq("ops@example.com"), eq("subj"), eq("<html>b</html>"), eq("critical"))).thenReturn("SENT");
        when(emailService.getEmailFrom()).thenReturn("noreply@example.com");
        when(logRepo.save(any())).thenAnswer(inv -> { NotificationLog x = inv.getArgument(0); x.setId(99L); return x; });

        assertThat(svc.resend(1L, SmtpLogQueryService.Scope.all(), "admin").reason()).isEqualTo("NOT_FAILED");
        assertThat(svc.resend(7L, SmtpLogQueryService.Scope.all(), "admin").reason()).isEqualTo("ALERT_RESOLVED");
        assertThat(svc.resend(2L, new SmtpLogQueryService.Scope(false, Set.of(2L), Set.of()), "admin").reason()).isEqualTo("NOT_FOUND");
        assertThat(svc.resend(404L, SmtpLogQueryService.Scope.all(), "admin").reason()).isEqualTo("NOT_FOUND");
        verify(emailService, never()).resendStoredHtml(anyString(), anyString(), anyString(), anyString());

        SmtpLogQueryService.ResendResult r = svc.resend(2L, SmtpLogQueryService.Scope.all(), "admin");
        assertThat(r.ok()).isTrue(); assertThat(r.status()).isEqualTo("SENT"); assertThat(r.newLogId()).isEqualTo(99L);
        ArgumentCaptor<NotificationLog> cap = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepo).save(cap.capture());
        assertThat(cap.getValue().getTrigger()).isEqualTo("MANUAL");
        assertThat(cap.getValue().getEmailStatus()).isEqualTo("SENT");
        assertThat(cap.getValue().getAlertEventId()).isEqualTo(10L);
        assertThat(cap.getValue().getMessage()).isEqualTo("<html>b</html>");
    }

    @Test
    @DisplayName("dışa aktarma: süzgeçle eşleşen tüm satırlar, tavan uygulanır")
    void export() {
        assertThat(svc.exportRows(f("FAILED", null, null, null), SmtpLogQueryService.Scope.all(), 2, NOW)).hasSize(2);
        assertThat(svc.exportRows(f(null, null, null, null), SmtpLogQueryService.Scope.all(), 100, NOW)).hasSize(6);
        List<Object[]> none = new ArrayList<>();
        when(logRepo.findWindowRows(anyString(), anyString())).thenReturn(none);
        assertThat(svc.exportRows(f(null, null, null, null), SmtpLogQueryService.Scope.all(), 100, NOW)).isEmpty();
    }
}
