package com.sitemonitor.service;

import com.sitemonitor.model.DomainExpiryReminder;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.repository.DomainExpiryReminderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Alan adı süre-bitişi hatırlatmaları (2026-09-22, madde E / bulgu F1): thresholdsCsv artık gerçekten işler.
 * Depo yerine bellek-içi "gönderildi" kümesi: existsBy… kapısı ve save aynı kümeye bakar.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DomainExpiryReminderServiceTest {

    @Mock DomainExpiryReminderRepository repo;
    @Mock EscalationService escalation;
    @Mock EmailNotificationService email;
    @Mock UserPushService push;
    @Mock ActivityLogService activityLog;

    private DomainExpiryReminderService svc;
    private final Set<String> sent = new HashSet<>();

    @BeforeEach
    void setUp() {
        svc = new DomainExpiryReminderService(repo, escalation, email, push, activityLog);
        when(repo.existsByMonitorIdAndExpiryDateAndThresholdDays(anyLong(), anyString(), anyInt()))
                .thenAnswer(i -> sent.contains(i.getArgument(0) + "|" + i.getArgument(1) + "|" + i.getArgument(2)));
        when(repo.save(any())).thenAnswer(i -> {
            DomainExpiryReminder r = i.getArgument(0);
            sent.add(r.getMonitorId() + "|" + r.getExpiryDate() + "|" + r.getThresholdDays());
            return r;
        });
        when(escalation.teamEmailsForMonitor(anyLong(), any())).thenReturn(List.of("team@example.com"));
        when(email.buildDomainExpiryReminderHtml(any(), any(), anyInt(), any(), anyInt(), any(), any(), any())).thenReturn("<html/>");
        when(email.sendHtml(any(), any(), any(), any(), any())).thenReturn("SENT");
        when(push.enqueueTeamNotice(any(), any(), any(), any(), any(), any(), any())).thenReturn(Map.of("queued", 2));
    }

    private static DomainMonitor monitor(String thresholds) {
        DomainMonitor m = new DomainMonitor();
        m.setId(7L); m.setDomain("a.example.com"); m.setName("A"); m.setTeamId(1L); m.setActive(true);
        m.setThresholdsCsv(thresholds); m.setWarningDays(30); m.setCriticalDays(7);
        return m;
    }

    private static Map<String, Object> result(Integer days, String expiry) {
        Map<String, Object> r = new HashMap<>();
        r.put("days_remaining", days); r.put("expiry_date", expiry); r.put("registrar", "Registrar A");
        return r;
    }

    @Test
    @DisplayName("parseThresholds: azalan, tekil, bozuk parça atlanır, boş → varsayılan")
    void parseThresholds() {
        assertThat(DomainExpiryReminderService.parseThresholds("7, 60,30,abc,7,14")).containsExactly(60, 30, 14, 7);
        assertThat(DomainExpiryReminderService.parseThresholds(null)).containsExactly(60, 30, 14, 7, 3, 1);
        assertThat(DomainExpiryReminderService.parseThresholds("x")).containsExactly(60, 30, 14, 7, 3, 1);
    }

    @Test
    @DisplayName("eşik üstünde hiçbir şey gönderilmez; eşik geçilince e-posta + push bir kez, ikinci turda tekrar yok")
    void crossesThresholdOnce() {
        DomainMonitor m = monitor("60,30,14,7");
        assertThat(svc.evaluate(m, result(61, "2026-11-22T00:00:00Z"))).isNull();
        verify(email, never()).sendHtml(any(), any(), any(), any(), any());

        DomainExpiryReminder r = svc.evaluate(m, result(59, "2026-11-22T00:00:00Z"));
        assertThat(r).isNotNull();
        assertThat(r.getThresholdDays()).isEqualTo(60);
        assertThat(r.getStatus()).isEqualTo("SENT");
        assertThat(r.getRecipients()).isEqualTo("team@example.com");
        assertThat(r.getPushQueued()).isEqualTo(2);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(eq(new String[]{"team@example.com"}), isNull(), subject.capture(), eq("<html/>"), isNull());
        assertThat(subject.getValue()).contains("59 gün").contains("60 gün eşiği");
        verify(push).enqueueTeamNotice(eq(1L), eq("DOMAIN_EXPIRY_REMINDER"), eq("INFO"), eq("DOMAIN"), eq("A"), contains("59 gün sonra"), eq("domain-reminder:7:2026-11-22T00:00:00Z:60"));
        verify(activityLog).recordLifecycle(eq("DOMAIN"), eq(7L), eq("A"), eq("a.example.com"), eq(1L), eq("EXPIRY_REMINDER"), eq("scheduler"), contains("eşik 60"));

        // Aynı bitiş, aynı eşik: ikinci tur sessiz
        assertThat(svc.evaluate(m, result(58, "2026-11-22T00:00:00Z"))).isNull();
        verify(email, times(1)).sendHtml(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("birden çok eşik aynı anda aşılmışsa yalnız EN SIKI eşik gönderilir, diğerleri COVERED (tek mail)")
    void multipleCrossed_onlyTightestSent() {
        DomainMonitor m = monitor("60,30,14,7,3,1");
        DomainExpiryReminder r = svc.evaluate(m, result(5, "2026-09-27T00:00:00Z"));
        assertThat(r.getThresholdDays()).isEqualTo(7);
        verify(email, times(1)).sendHtml(any(), any(), any(), any(), any());
        // 60/30/14 COVERED olarak kaydedildi → bir sonraki turda yeniden gönderilmez; 3 ve 1 henüz aşılmadı
        ArgumentCaptor<DomainExpiryReminder> saved = ArgumentCaptor.forClass(DomainExpiryReminder.class);
        verify(repo, times(4)).save(saved.capture());
        assertThat(saved.getAllValues().stream().filter(x -> "COVERED".equals(x.getStatus())).map(DomainExpiryReminder::getThresholdDays))
                .containsExactlyInAnyOrder(60, 30, 14);
        assertThat(svc.evaluate(m, result(4, "2026-09-27T00:00:00Z"))).isNull();
        // 3 gün eşiği geçilince yeni hatırlatma; seviye CRITICAL (≤7)
        DomainExpiryReminder r3 = svc.evaluate(m, result(2, "2026-09-27T00:00:00Z"));
        assertThat(r3.getThresholdDays()).isEqualTo(3);
        verify(push).enqueueTeamNotice(eq(1L), eq("DOMAIN_EXPIRY_REMINDER"), eq("CRITICAL"), eq("DOMAIN"), eq("A"), anyString(), eq("domain-reminder:7:2026-09-27T00:00:00Z:3"));
    }

    @Test
    @DisplayName("yenileme (bitiş ileri gitti): aynı eşik yeni bitiş için yeniden gönderilir")
    void renewalStartsNewSeries() {
        DomainMonitor m = monitor("30");
        assertThat(svc.evaluate(m, result(20, "2026-10-12T00:00:00Z"))).isNotNull();
        assertThat(svc.evaluate(m, result(19, "2026-10-12T00:00:00Z"))).isNull();
        // yenilendi: 385 gün → eşik üstü, sessiz; sonra yeni bitişe 30 gün kala tekrar
        assertThat(svc.evaluate(m, result(385, "2027-10-12T00:00:00Z"))).isNull();
        DomainExpiryReminder again = svc.evaluate(m, result(30, "2027-10-12T00:00:00Z"));
        assertThat(again).isNotNull();
        assertThat(again.getExpiryDate()).isEqualTo("2027-10-12T00:00:00Z");
        verify(email, times(2)).sendHtml(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("kanallar izlemenin tercihini izler: mail kapalı → yalnız push; ikisi kapalı → SKIPPED_CHANNELS_OFF (yine kaydedilir, tekrar denenmez)")
    void channelsFollowMonitorPrefs() {
        DomainMonitor m = monitor("30");
        m.setNotifyEmail(false);
        DomainExpiryReminder r = svc.evaluate(m, result(10, "2026-10-02T00:00:00Z"));
        assertThat(r.getStatus()).isEqualTo("SENT");
        assertThat(r.getRecipients()).isNull();
        verify(email, never()).sendHtml(any(), any(), any(), any(), any());
        verify(push).enqueueTeamNotice(any(), any(), any(), any(), any(), any(), any());

        DomainMonitor off = monitor("30"); off.setId(8L); off.setNotifyEmail(false); off.setNotifyWebhook(false);
        DomainExpiryReminder r2 = svc.evaluate(off, result(10, "2026-10-02T00:00:00Z"));
        assertThat(r2.getStatus()).isEqualTo("SKIPPED_CHANNELS_OFF");
        assertThat(svc.evaluate(off, result(9, "2026-10-02T00:00:00Z"))).isNull();
    }

    @Test
    @DisplayName("veri yok (UNKNOWN), pasif izleme, bozuk sonuç → hatırlatma yok; depo hatası sweep'i kırmaz")
    void noDataOrInactive_noReminder() {
        DomainMonitor m = monitor("30");
        assertThat(svc.evaluate(m, result(null, null))).isNull();
        assertThat(svc.evaluate(m, result(5, null))).isNull();
        m.setActive(false);
        assertThat(svc.evaluate(m, result(5, "2026-09-27T00:00:00Z"))).isNull();
        verify(email, never()).sendHtml(any(), any(), any(), any(), any());
        m.setActive(true);
        when(repo.existsByMonitorIdAndExpiryDateAndThresholdDays(anyLong(), anyString(), anyInt())).thenThrow(new RuntimeException("db down"));
        assertThat(svc.evaluate(m, result(5, "2026-09-27T00:00:00Z"))).isNull();   // exception yutulur
    }
}
