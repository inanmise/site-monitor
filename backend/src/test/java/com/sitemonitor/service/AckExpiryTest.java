package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertEventSilentCloseRepository;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.DomainCheckRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.PageCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Süreli alarm onayı (2026-11-19, kullanıcı kararı: "Süresiz + hazır süreler"; "süre dolunca onay kalkar + hemen hatırlatma")
 * GERÇEK H2 üstünde: süreli onay {@code ack_expires_at}'ı yazar, süresiz yeniden onay siler; süpürme yalnız süresi dolmuş, AÇIK
 * ve hâlâ ONAYLI satırı koşullu olarak düşürür (onay kolonları boşalır, {@code last_re_alert_at} = şimdi — günlük hatırlatma
 * takvimi buradan yeniden başlar) ve hatırlatmayı BİR kez kuyruğa koyar. İki pod / yeniden onay / kapanış yarışında tek kazanan.
 * {@code NOT_SUPPORTED}: test işlemi yarışı gizlerdi.
 */
@DataJpaTest
@Import(AlertEventFieldWriter.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AckExpiryTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Autowired AlertEventRepository realRepo;
    @Autowired AlertEventSilentCloseRepository closeRepo;
    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired AlertEventFieldWriter writer;

    @BeforeEach
    void setUp() {
        realRepo.deleteAll();
    }

    @AfterEach
    void tearDown() {
        realRepo.deleteAll();
    }

    @SuppressWarnings("unchecked")
    private EscalationService pod(AlertEventRepository repo, EscalationService self) {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        SmtpSettingsService smtp = mock(SmtpSettingsService.class);
        SmtpSettings s = new SmtpSettings();
        s.setInterDomainDelayMs(0);
        when(smtp.getOrDefaults()).thenReturn(s);
        EscalationService svc = new EscalationService(repo, mock(AlertThresholdRepository.class),
                mock(EscalationContactRepository.class), inventoryRepo, mock(EmailNotificationService.class),
                mock(WeeklyAvailabilityReportService.class), mock(WebhookService.class), new ObjectMapper(),
                mock(NotificationLogRepository.class), mock(LatestCheckRepository.class), mock(TeamRepository.class),
                smtp, mock(MaintenanceService.class), mock(StormService.class), mock(UserPushService.class),
                mock(DomainMonitorRepository.class), mock(DomainCheckRepository.class), mock(DnsRecordRepository.class),
                mock(PageCheckRepository.class), mock(NotificationGroupService.class), settings);
        ReflectionTestUtils.setField(svc, "self", self);
        ObjectProvider<UserPushService> provider = mock(ObjectProvider.class);
        svc.setSilentAlarmCloser(new SilentAlarmCloser(closeRepo, realRepo, inventoryRepo, provider));
        svc.setFieldWriter(writer);
        return svc;
    }

    private static String iso(Instant at) {
        return ISO.format(at);
    }

    private AlertEvent open(String domain) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(EscalationService.TYPE_PORT_DOWN);
        e.setAlertLevel("HIGH");
        e.setMessage("port kapalı");
        e.setTeamId(7L);
        e.setResolved(false);
        e.setAcknowledged(false);
        e.setCreatedAt(iso(Instant.now().minus(3, ChronoUnit.HOURS)));
        e.setLastReAlertAt(iso(Instant.now().minus(2, ChronoUnit.HOURS)));
        e.setRealertCount(0);
        e.setContextJson("{\"monitor_id\":1,\"team_id\":7,\"standalone\":true}");
        return realRepo.save(e);
    }

    /** Onaylı + süresi belli satır (doğrudan yazım — süpürme testlerinde "şimdi"yi kontrol etmek için). */
    private AlertEvent acked(String domain, Instant expiresAt) {
        AlertEvent e = open(domain);
        e.setAcknowledged(true);
        e.setAcknowledgedBy("Ayşe Yılmaz");
        e.setAcknowledgedAt(iso(Instant.now().minus(1, ChronoUnit.HOURS)));
        e.setAcknowledgedNote("bakıyoruz");
        e.setAckExpiresAt(expiresAt == null ? null : iso(expiresAt));
        return realRepo.save(e);
    }

    private AlertEvent row(Long id) {
        return realRepo.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("süreli onay ack_expires_at'ı yazar; süresiz yeniden onay eski süreyi siler")
    void acknowledge_writesAndClearsExpiry() {
        EscalationService svc = pod(realRepo, mock(EscalationService.class));
        Long id = open("a.example.com").getId();
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        AlertEvent out = svc.acknowledge(id, "Ayşe Yılmaz", "bakım planlandı", 240);

        AlertEvent r = row(id);
        assertThat(r.getAcknowledged()).isTrue();
        assertThat(r.getAckExpiresAt()).isNotNull();
        Instant at = Instant.parse(r.getAckExpiresAt() + "Z");
        assertThat(at).isBetween(before.plus(240, ChronoUnit.MINUTES), Instant.now().plus(241, ChronoUnit.MINUTES));
        assertThat(out.getAckExpiresAt()).isEqualTo(r.getAckExpiresAt());

        svc.acknowledge(id, "Ayşe Yılmaz", "süresiz devam");
        assertThat(row(id).getAckExpiresAt()).isNull();
        assertThat(row(id).getAcknowledged()).isTrue();
    }

    @Test
    @DisplayName("süresi dolan açık onay düşer: onay kolonları boşalır, last_re_alert_at = şimdi, hatırlatma BİR kez")
    void expire_dropsAckAndSchedulesReminderOnce() {
        EscalationService self = mock(EscalationService.class);
        EscalationService svc = pod(realRepo, self);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Long id = acked("due.example.com", now.minus(1, ChronoUnit.MINUTES)).getId();

        assertThat(svc.expireDueAcknowledgements(now)).isEqualTo(1);

        AlertEvent r = row(id);
        assertThat(r.getAcknowledged()).isFalse();
        assertThat(r.getAcknowledgedBy()).isNull();
        assertThat(r.getAcknowledgedAt()).isNull();
        assertThat(r.getAckExpiresAt()).isNull();
        assertThat(r.getLastReAlertAt()).isEqualTo(iso(now));
        assertThat(r.getResolved()).isFalse();
        verify(self).ackExpiredReminderAsync(id);

        // ikinci tur: düşecek bir şey kalmadı
        assertThat(svc.expireDueAcknowledgements(now.plus(1, ChronoUnit.MINUTES))).isZero();
    }

    @Test
    @DisplayName("süresi dolmamış, süresiz, kapanmış ya da onayı zaten kalkmış satıra dokunulmaz")
    void expire_leavesOtherRowsAlone() {
        EscalationService self = mock(EscalationService.class);
        EscalationService svc = pod(realRepo, self);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Long future = acked("future.example.com", now.plus(10, ChronoUnit.MINUTES)).getId();
        Long unlimited = acked("unlimited.example.com", null).getId();
        AlertEvent closed = acked("closed.example.com", now.minus(5, ChronoUnit.MINUTES));
        closed.setResolved(true);
        closed.setResolvedAt(iso(now.minus(1, ChronoUnit.MINUTES)));
        realRepo.save(closed);
        // terfi onayı kaldırdı ama ack_expires_at bayat kaldı (zararsız — sorgu acknowledged = true ister)
        AlertEvent unacked = acked("unacked.example.com", now.minus(5, ChronoUnit.MINUTES));
        unacked.setAcknowledged(false);
        realRepo.save(unacked);

        assertThat(svc.expireDueAcknowledgements(now)).isZero();

        assertThat(row(future).getAcknowledged()).isTrue();
        assertThat(row(unlimited).getAcknowledged()).isTrue();
        assertThat(row(closed.getId()).getAcknowledgedBy()).isEqualTo("Ayşe Yılmaz");
        verify(self, never()).ackExpiredReminderAsync(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("iki pod aynı satırı görür: yalnız koşullu UPDATE'i kazanan düşürür ve hatırlatır")
    void twoPods_oneWinner() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Long id = acked("race.example.com", now.minus(1, ChronoUnit.MINUTES)).getId();
        // B'nin okuması A'nın düşürmesinden ÖNCE yapılmış gibi: aynı satırı listeler
        List<Object[]> seenByB = realRepo.findDueAckExpiries(iso(now), 200);
        assertThat(seenByB).hasSize(1);
        AlertEventRepository repoB = mock(AlertEventRepository.class, AdditionalAnswers.delegatesTo(realRepo));
        when(repoB.findDueAckExpiries(anyString(), anyInt())).thenReturn(seenByB);

        EscalationService selfA = mock(EscalationService.class);
        EscalationService selfB = mock(EscalationService.class);
        EscalationService podA = pod(realRepo, selfA);
        EscalationService podB = pod(repoB, selfB);

        assertThat(podA.expireDueAcknowledgements(now)).isEqualTo(1);
        assertThat(podB.expireDueAcknowledgements(now)).isZero();

        verify(selfA).ackExpiredReminderAsync(id);
        verify(selfB, never()).ackExpiredReminderAsync(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("okuma ile sahiplenme arasında yeni (süreli) onay: eski süreyle sahiplenme 0 satır — yeni onay kalır")
    void reAcknowledgedMeanwhile_notExpired() {
        EscalationService self = mock(EscalationService.class);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Long id = acked("reack.example.com", now.minus(1, ChronoUnit.MINUTES)).getId();
        List<Object[]> stale = realRepo.findDueAckExpiries(iso(now), 200);
        // başka biri 8 saatlik yeniden onay verdi
        pod(realRepo, mock(EscalationService.class)).acknowledge(id, "Mehmet Demir", "yeniden", 480);
        AlertEventRepository repo = mock(AlertEventRepository.class, AdditionalAnswers.delegatesTo(realRepo));
        when(repo.findDueAckExpiries(anyString(), anyInt())).thenReturn(stale);

        assertThat(pod(repo, self).expireDueAcknowledgements(now)).isZero();

        AlertEvent r = row(id);
        assertThat(r.getAcknowledged()).isTrue();
        assertThat(r.getAcknowledgedBy()).isEqualTo("Mehmet Demir");
        assertThat(r.getAckExpiresAt()).isNotNull();
        verify(self, never()).ackExpiredReminderAsync(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("okuma ile sahiplenme arasında kapanış: alarm kapalı kalır, onay düşmez, hatırlatma yok")
    void closedMeanwhile_notExpired() {
        EscalationService self = mock(EscalationService.class);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        AlertEvent e = acked("closing.example.com", now.minus(1, ChronoUnit.MINUTES));
        List<Object[]> stale = realRepo.findDueAckExpiries(iso(now), 200);
        e.setResolved(true);
        e.setResolvedAt(iso(now));
        realRepo.save(e);
        AlertEventRepository repo = mock(AlertEventRepository.class, AdditionalAnswers.delegatesTo(realRepo));
        when(repo.findDueAckExpiries(anyString(), anyInt())).thenReturn(stale);

        assertThat(pod(repo, self).expireDueAcknowledgements(now)).isZero();

        assertThat(row(e.getId()).getResolved()).isTrue();
        assertThat(row(e.getId()).getAcknowledged()).isTrue();
        verify(self, never()).ackExpiredReminderAsync(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("tur sınırı: en çok ACK_EXPIRY_BATCH satır, en eski süre önce; kalan sonraki turda")
    void batchLimitOldestFirst() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Long newer = acked("newer.example.com", now.minus(1, ChronoUnit.MINUTES)).getId();
        Long older = acked("older.example.com", now.minus(30, ChronoUnit.MINUTES)).getId();

        List<Object[]> one = realRepo.findDueAckExpiries(iso(now), 1);
        assertThat(one).hasSize(1);
        assertThat(((Number) one.get(0)[0]).longValue()).isEqualTo(older);
        assertThat(realRepo.findDueAckExpiries(iso(now), 200)).extracting(r -> ((Number) r[0]).longValue())
                .containsExactly(older, newer);
    }

    @Test
    @DisplayName("hatırlatma kuyruğa alınamazsa (havuz kapalı) onay yine düşer, FAILED satırı yazılır")
    void reminderRejected_stillExpired() {
        EscalationService self = mock(EscalationService.class);
        org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("kapalı"))
                .when(self).ackExpiredReminderAsync(org.mockito.ArgumentMatchers.any());
        NotificationLogRepository logs = mock(NotificationLogRepository.class);
        EscalationService svc = pod(realRepo, self);
        ReflectionTestUtils.setField(svc, "notificationLogRepo", logs);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Long id = acked("rejected.example.com", now.minus(1, ChronoUnit.MINUTES)).getId();

        assertThat(svc.expireDueAcknowledgements(now)).isEqualTo(1);

        assertThat(row(id).getAcknowledged()).isFalse();
        verify(logs).save(org.mockito.ArgumentMatchers.argThat(n ->
                EscalationService.TRIGGER_ACK_EXPIRED.equals(n.getTrigger())
                        && n.getEmailStatus().startsWith("FAILED: onay süresi hatırlatması kuyruğa alınamadı")));
        verify(self).ackExpiredReminderAsync(eq(id));
    }
}
