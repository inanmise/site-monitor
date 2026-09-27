package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * SMTP gönderim günlüğünde 7/24 (NOC) satırı: liste/özet/CSV/zincir ve detay HİÇBİR görücüye 7/24 grup ADRESİ ya da
 * arama listesi TELEFONU vermez (yayın öncesi inceleme 2026-09-27). Kapsamlı görücü (takım üyesi, kapsamlı müdür)
 * ve global görücü (AUDIT/yönetici) için aynı kural. Satır yeniden de GÖNDERİLEMEZ (günlükte adres yok, gövde maskeli).
 */
@SuppressWarnings("unchecked")
class SmtpLogNocRedactionTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Instant NOW = Instant.parse("2026-01-10T12:00:00Z");

    private final NotificationLogRepository logRepo = mock(NotificationLogRepository.class);
    private final AlertEventRepository alertRepo = mock(AlertEventRepository.class);
    private final CertificateInventoryRepository inventoryRepo = mock(CertificateInventoryRepository.class);
    private final TeamRepository teamRepo = mock(TeamRepository.class);
    private final EmailNotificationService emailService = mock(EmailNotificationService.class);
    private SmtpLogQueryService svc;

    private static final String LEGACY_BODY = "<p>Kişi A <a href=\"tel:+905550000012\" target=\"_blank\">+90 555 000 00 12</a></p>";

    @BeforeEach
    void setUp() {
        svc = new SmtpLogQueryService(logRepo, alertRepo, inventoryRepo, teamRepo, emailService, null);
        Team a = new Team(); a.setId(1L); a.setName("Takım A");
        when(teamRepo.findAll()).thenReturn(List.of(a));
        AlertEvent e = new AlertEvent(); e.setId(10L); e.setTeamId(1L); e.setDomain("a.example.com"); e.setAlertType("HTTP_DOWN");
        e.setAlertLevel("CRITICAL"); e.setResolved(false);
        when(alertRepo.findAllById(any())).thenReturn(List.of(e));
        when(alertRepo.findById(10L)).thenReturn(Optional.of(e));
        Object[] noc = {7L, 10L, ISO.format(NOW.minus(Duration.ofMinutes(5))), "7/24 İzleme Ekibi", "noc@example.com, yedek@example.com",
                "NOC", "[Site Monitor] [7/24] KRİTİK — a.example.com", "FAILED: 550", "SKIPPED", "NOC_OPEN", "noreply@example.com", null};
        when(logRepo.findWindowRows(anyString(), anyString())).thenReturn(List.<Object[]>of(noc));
        when(logRepo.findChainRows(10L)).thenReturn(List.<Object[]>of(noc));
        NotificationLog n = new NotificationLog();
        n.setId(7L); n.setAlertEventId(10L); n.setRecipientRole("NOC"); n.setRecipientName("7/24 İzleme Ekibi");
        n.setRecipientEmail("noc@example.com, yedek@example.com"); n.setMessage(LEGACY_BODY); n.setEmailStatus("FAILED: 550");
        n.setTrigger("NOC_OPEN"); n.setSubject("[Site Monitor] [7/24] KRİTİK — a.example.com");
        when(logRepo.findById(7L)).thenReturn(Optional.of(n));
    }

    private static void noLeak(Object payload) {
        String s = String.valueOf(payload);
        assertThat(s).doesNotContain("noc@example.com").doesNotContain("yedek@example.com")
                .doesNotContain("tel:+905550000012").doesNotContain("555 000 00 12").doesNotContain("5550000012");
    }

    @Test
    @DisplayName("detay + zincir + liste + CSV: kapsamlı görücü ve global görücüde adres/telefon YOK")
    void noLeakOnAnySurface() {
        SmtpLogQueryService.Scope team = new SmtpLogQueryService.Scope(false, Set.of(1L), Set.of());
        SmtpLogQueryService.Filter f = new SmtpLogQueryService.Filter(ISO.format(NOW.minus(Duration.ofDays(2))), null,
                null, null, null, null, null, null, null, null);
        for (SmtpLogQueryService.Scope scope : List.of(team, SmtpLogQueryService.Scope.all())) {
            Map<String, Object> d = svc.detail(7L, scope);
            assertThat(d).isNotNull();
            noLeak(d);
            assertThat(String.valueOf(d.get("message"))).contains("Kişi A").contains("••••••••12");
            assertThat(d.get("recipient_email")).isEqualTo("2 adres");
            noLeak(svc.search(f, scope, 0, 25, NOW));
            noLeak(svc.exportRows(f, scope, 100, NOW));
            noLeak(svc.summary(f, scope, NOW));
        }
    }

    @Test
    @DisplayName("7/24 satırı yeniden GÖNDERİLEMEZ (adres yok, gövde maskeli) — başka hiçbir kanala çıkmaz")
    void nocRowIsNotResendable() {
        SmtpLogQueryService.ResendResult r = svc.resend(7L, SmtpLogQueryService.Scope.all(), "admin");
        assertThat(r.ok()).isFalse();
        assertThat(r.reason()).isEqualTo("NOC_NOT_RESENDABLE");
        verify(emailService, never()).resendStoredHtml(anyString(), anyString(), anyString(), anyString());
    }
}
