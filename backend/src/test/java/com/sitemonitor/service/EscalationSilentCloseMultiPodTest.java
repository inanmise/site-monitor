package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
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
import com.sitemonitor.service.noc.NocNotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çok pod R2b · F (2026-10-23) — {@link EscalationService}'in kapanış yolları GERÇEK H2 üstünde: sessiz kapanış (silme /
 * duraklatma / anahtar değişikliği), bakımda düzelen alarmın e-postasız kapanışı, envanter silme / pasifleştirme ve koşullu
 * kapanışı (CAS) kazanmış elle / otomatik çözüm okunan alarm varlığını BÜTÜNÜYLE kaydetmez. Okumadan sonra yazılmış onay,
 * yeniden uyarı damgası, fırtına bağının çözülmesi ve başka yolun çözümü korunur; iki pod aynı kapanışı koşunca her alarm
 * BİR kez kapanır ve çözüm push'u BİR kez gider.
 *
 * <p>Bayat okuma, depo sahtesinin okuma metodu gerçek depodan okuduktan SONRA eşzamanlı yazımı uygulayarak kurulur; diğer
 * çağrılar gerçek depoya gider (eski yolun bütün varlık kaydı gerçekten yazardı). {@code NOT_SUPPORTED}: test işlemi
 * koşullu UPDATE'leri ve yarışı gizlerdi.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EscalationSilentCloseMultiPodTest {

    private static final String HTTP = EscalationService.TYPE_HTTP_DOWN;
    private static final String HTTP_SLOW = EscalationService.TYPE_HTTP_SLOW;
    private static final String PORT = EscalationService.TYPE_PORT_DOWN;

    @Autowired DataSource dataSource;
    @Autowired AlertEventRepository realRepo;
    @Autowired AlertEventSilentCloseRepository closeRepo;
    @Autowired CertificateInventoryRepository inventoryRepo;

    JdbcTemplate jdbc;
    UserPushService push;
    NocNotificationService noc;
    MaintenanceService maintenance;
    NotificationLogRepository notificationLogRepo;
    StormService storm;
    EscalationService selfMock;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        realRepo.deleteAll();
        push = mock(UserPushService.class);
        noc = mock(NocNotificationService.class);
        maintenance = mock(MaintenanceService.class);
        notificationLogRepo = mock(NotificationLogRepository.class);
        storm = mock(StormService.class);
        selfMock = mock(EscalationService.class);
    }

    /** NOT_SUPPORTED satırları işler — önbellekli @DataJpaTest bağlamını paylaşan sonraki testler temiz başlasın. */
    @AfterEach
    void tearDown() {
        realRepo.deleteAll();
        inventoryRepo.deleteAll();
    }

    /** Gerçek depoya giden sahte (okuma metotları teste göre bayatlatılır). */
    private AlertEventRepository delegating() {
        return mock(AlertEventRepository.class, AdditionalAnswers.delegatesTo(realRepo));
    }

    @SuppressWarnings("unchecked")
    private SilentAlarmCloser closer() {
        ObjectProvider<UserPushService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(push);
        return new SilentAlarmCloser(closeRepo, realRepo, inventoryRepo, provider);
    }

    /** Bir "pod": kendi {@link EscalationService} örneği, ortak veritabanı ve ortak bildirim sahteleri. */
    private EscalationService pod(AlertEventRepository repo, boolean withCloser) {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        EscalationService s = new EscalationService(repo, mock(AlertThresholdRepository.class),
                mock(EscalationContactRepository.class), inventoryRepo, mock(EmailNotificationService.class),
                mock(WeeklyAvailabilityReportService.class), mock(WebhookService.class), new ObjectMapper(),
                notificationLogRepo, mock(LatestCheckRepository.class), mock(TeamRepository.class),
                mock(SmtpSettingsService.class), maintenance, storm, push, mock(DomainMonitorRepository.class),
                mock(DomainCheckRepository.class), mock(DnsRecordRepository.class), mock(PageCheckRepository.class),
                mock(NotificationGroupService.class), settings);
        ReflectionTestUtils.setField(s, "self", selfMock);
        ReflectionTestUtils.setField(s, "nocNotifications", noc);
        s.setJdbcTemplate(jdbc);
        if (withCloser) s.setSilentAlarmCloser(closer());
        return s;
    }

    private EscalationService pod(AlertEventRepository repo) {
        return pod(repo, true);
    }

    private AlertEvent open(String domain, String type, String ctx) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("HIGH");
        e.setTeamId(7L);
        e.setResolved(false);
        e.setAcknowledged(false);
        e.setCreatedAt("2026-10-23T08:00:00");
        e.setLastReAlertAt("2026-10-23T08:00:00");
        e.setContextJson(ctx);
        return realRepo.save(e);
    }

    /** Bayat liste — sorgu gibi tür kümesine göre süzülür (alarmlar okunduğu andaki halleriyle). */
    private static org.mockito.stubbing.Answer<List<AlertEvent>> staleByType(List<AlertEvent> stale) {
        return i -> {
            Collection<?> types = i.getArgument(1);
            return stale.stream().filter(e -> types.contains(e.getAlertType())).toList();
        };
    }

    private AlertEvent row(Long id) {
        return realRepo.findById(id).orElseThrow();
    }

    private void ack(Long id) {
        jdbc.update("UPDATE alert_events SET acknowledged = TRUE, acknowledged_by = 'ops', "
                + "acknowledged_at = '2026-10-23T08:04:00' WHERE id = ?", id);
    }

    private void stampReAlert(Long id) {
        jdbc.update("UPDATE alert_events SET last_re_alert_at = '2026-10-23T08:04:30' WHERE id = ?", id);
    }

    private void resolveElsewhere(Long id) {
        jdbc.update("UPDATE alert_events SET resolved = TRUE, resolved_by = 'kullanici', "
                + "resolved_at = '2026-10-23T08:05:00' WHERE id = ?", id);
    }

    private long resolvePushes(Long id) {
        return mockingDetails(push).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("enqueueResolve"))
                .filter(i -> i.getArgument(0) instanceof AlertEvent e && id.equals(e.getId()))
                .count();
    }

    private static final String STANDALONE_CTX = "{\"monitor_id\":1,\"team_id\":7,\"standalone\":true}";

    @Test
    @DisplayName("Sessiz kapanış: okunduktan SONRA yazılan onay + yeniden uyarı damgası ezilmez; başka yolda kapanmış alarm yeniden yazılmaz, push yok")
    void staleRead_ackAndConcurrentResolveNotOverwritten() {
        String domain = "https://svc.example.com/";
        AlertEvent a = open(domain, HTTP, STANDALONE_CTX);
        AlertEvent b = open(domain, HTTP_SLOW, STANDALONE_CTX);
        List<AlertEvent> stale = realRepo.findByDomainAndAlertTypeInAndResolvedFalse(domain, Set.of(HTTP, HTTP_SLOW));
        assertThat(stale).hasSize(2);
        // Okumadan sonra: A'yı operatör onayladı ve yeniden uyarı damgası yazıldı; B'yi kullanıcı elle çözdü (başka pod).
        ack(a.getId());
        stampReAlert(a.getId());
        resolveElsewhere(b.getId());
        AlertEventRepository repo = delegating();
        doAnswer(staleByType(stale)).when(repo).findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection());

        pod(repo).resolveOpenAlertsSilently(domain, Set.of(HTTP, HTTP_SLOW), "Sistem (izleme silindi)");

        AlertEvent ra = row(a.getId());
        assertThat(ra.getResolved()).isTrue();
        assertThat(ra.getResolvedSilently()).isTrue();
        assertThat(ra.getResolvedBy()).isEqualTo("Sistem (izleme silindi)");
        assertThat(ra.getAcknowledged()).as("bütün varlık kaydı onayı geri alırdı").isTrue();
        assertThat(ra.getAcknowledgedBy()).isEqualTo("ops");
        assertThat(ra.getLastReAlertAt()).as("yeniden uyarı damgası korunur").isEqualTo("2026-10-23T08:04:30");
        AlertEvent rb = row(b.getId());
        assertThat(rb.getResolvedBy()).as("başka yolun kapanışı ezilmez").isEqualTo("kullanici");
        assertThat(rb.getResolvedAt()).isEqualTo("2026-10-23T08:05:00");
        assertThat(rb.getResolvedSilently()).isNull();
        assertThat(resolvePushes(a.getId())).isEqualTo(1);
        assertThat(resolvePushes(b.getId())).as("kapatmayan çağıran çözüm push'u denemez").isZero();
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("İki pod aynı sessiz kapanışı aynı anda koşar (ikisi de açık okur): her alarm TEK kez kapanır, çözüm push'u TEK kez")
    void twoPodsConcurrent_eachAlarmClosedOnce() throws Exception {
        String domain = "https://flow.example.com/";
        List<Long> ids = new ArrayList<>();
        for (String t : List.of(HTTP, HTTP_SLOW, EscalationService.TYPE_HTTP_SSL, EscalationService.TYPE_DOMAIN_EXPIRY))
            ids.add(open(domain, t, STANDALONE_CTX).getId());
        Set<String> types = Set.of(HTTP, HTTP_SLOW, EscalationService.TYPE_HTTP_SSL, EscalationService.TYPE_DOMAIN_EXPIRY);
        CyclicBarrier bothRead = new CyclicBarrier(2);
        EscalationService[] pods = new EscalationService[2];
        for (int n = 0; n < 2; n++) {
            AlertEventRepository repo = delegating();
            doAnswer(i -> {
                List<AlertEvent> r = realRepo.findByDomainAndAlertTypeInAndResolvedFalse(i.getArgument(0), i.getArgument(1));
                bothRead.await(10, TimeUnit.SECONDS);   // ikisi de açık okudu → ikisi de kapatmaya çalışır
                return r;
            }).when(repo).findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection());
            pods[n] = pod(repo);
        }
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = ex.submit(() -> pods[0].resolveOpenAlertsSilently(domain, types, "Sistem (izleme silindi)"));
            Future<?> f2 = ex.submit(() -> pods[1].resolveOpenAlertsSilently(domain, types, "Sistem (izleme duraklatıldı)"));
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            ex.shutdownNow();
        }
        for (Long id : ids) {
            AlertEvent e = row(id);
            assertThat(e.getResolved()).isTrue();
            assertThat(e.getResolvedSilently()).isTrue();
            assertThat(e.getResolvedBy()).isIn("Sistem (izleme silindi)", "Sistem (izleme duraklatıldı)");
            assertThat(resolvePushes(id)).as("alarm %d için çözüm push'u", id).isEqualTo(1);
        }
        verify(push, times(ids.size())).enqueueResolve(any(), any(), any());
    }

    @Test
    @DisplayName("Bakımda gerçekten düzelen alarm: e-postasız koşullu kapanış resolved_silently'yi DEĞİŞTİRMEZ, onay korunur, 7/24 çözümü BİR kez")
    void maintenanceQuietRecovery_keepsSilencedFlagAndNotifiesNoc() {
        String domain = "maint.example.com";
        AlertEvent a = open(domain, PORT, null);
        jdbc.update("UPDATE alert_events SET resolved_silently = FALSE WHERE id = ?", a.getId());
        AlertEvent b = open(domain, EscalationService.TYPE_ACCESSIBILITY, null);   // işaret NULL kalır
        List<AlertEvent> stale = realRepo.findByDomainAndAlertTypeInAndResolvedFalse(domain,
                Set.of(PORT, EscalationService.TYPE_ACCESSIBILITY));
        ack(a.getId());
        AlertEventRepository repo = delegating();
        doAnswer(staleByType(stale)).when(repo).findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection());
        when(maintenance.isUnderMaintenance(domain)).thenReturn(true);
        when(notificationLogRepo.findByAlertEventIdOrderBySentAtDesc(anyLong())).thenReturn(List.of());   // açılış bildirimi yok

        EscalationService p = pod(repo);
        p.resolveMonitoringAlertsForDomain(domain, PORT);
        p.resolveMonitoringAlertsForDomain(domain, EscalationService.TYPE_ACCESSIBILITY);

        AlertEvent ra = row(a.getId());
        assertThat(ra.getResolved()).isTrue();
        assertThat(ra.getResolvedBy()).isEqualTo("Sistem (bakım penceresi — sessiz kapanış)");
        assertThat(ra.getResolvedSilently()).as("kurtuldu, susturulmadı (O-c1) — kolon değiştirilmez").isFalse();
        assertThat(ra.getAcknowledged()).isTrue();
        assertThat(row(b.getId()).getResolvedSilently()).isNull();
        ArgumentCaptor<AlertEvent> nocArg = ArgumentCaptor.forClass(AlertEvent.class);
        verify(noc, times(2)).onAlertResolved(nocArg.capture());
        assertThat(nocArg.getAllValues()).allMatch(e -> Boolean.TRUE.equals(e.getResolved()));
        assertThat(resolvePushes(a.getId())).isEqualTo(1);
        verify(selfMock, never()).sendResolutionNotificationAsync(any(), any(), any());   // e-posta yok (bakım sessizliği)
        verify(repo, never()).save(any());

        // İkinci tur (başka pod aynı bayat listeyle): artık kapalı — 7/24 ve push tekrar gitmez.
        p.resolveMonitoringAlertsForDomain(domain, PORT);
        verify(noc, times(2)).onAlertResolved(any());
        assertThat(resolvePushes(a.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("Envanter pasifleştirme / silme: koşullu kapanış — onay korunur, başka yolda kapanmış alarm sayılmaz ve yeniden yazılmaz, bağımsız izlemenin olayı açık kalır")
    void inventoryDeactivateAndDelete_conditional() {
        String domain = "inv.example.com";
        AlertEvent expiry = open(domain, "EXPIRY", null);
        AlertEvent chain = open(domain, "CHAIN_BROKEN", null);
        AlertEvent port = open(domain, PORT, "{\"monitor_id\":9,\"team_id\":99,\"standalone\":true}");
        List<AlertEvent> stale = realRepo.findByDomainAndResolvedFalse(domain);
        assertThat(stale).hasSize(3);
        ack(expiry.getId());
        resolveElsewhere(chain.getId());
        AlertEventRepository repo = delegating();
        doAnswer(i -> stale).when(repo).findByDomainAndResolvedFalse(domain);

        assertThat(pod(repo).closeAlertsOnDeactivate(domain)).isEqualTo(1);

        AlertEvent re = row(expiry.getId());
        assertThat(re.getResolved()).isTrue();
        assertThat(re.getResolvedBy()).isEqualTo("inventory_deactivate");
        assertThat(re.getResolvedSilently()).isTrue();
        assertThat(re.getAcknowledged()).isTrue();
        assertThat(row(chain.getId()).getResolvedBy()).isEqualTo("kullanici");
        assertThat(row(port.getId()).getResolved()).as("bağımsız izlemenin olayı envantere ait değil").isFalse();
        assertThat(resolvePushes(expiry.getId())).isEqualTo(1);
        assertThat(resolvePushes(chain.getId())).isZero();

        // Silme: yalnız hâlâ açık envanter olayı (yok) — bağımsız olay yine açık.
        AlertEventRepository repo2 = delegating();
        assertThat(pod(repo2).closeAlertsOnInventoryDelete(domain)).isZero();
        AlertEvent cert2 = open(domain, "EXPIRY", null);
        assertThat(pod(delegating()).closeAlertsOnInventoryDelete(domain)).isEqualTo(1);
        assertThat(row(cert2.getId()).getResolvedBy()).isEqualTo("inventory_delete");
        assertThat(row(port.getId()).getResolved()).isFalse();
        verify(repo, never()).save(any());
        verify(repo2, never()).save(any());
    }

    @Test
    @DisplayName("Elle çözüm (notlu): koşullu kapanışı kazanınca bütün varlık kaydedilmez — okumadan sonraki onay ve damga korunur, not yazılır")
    void manualResolve_noteWritten_concurrentAckKept() {
        AlertEvent a = open("man.example.com", "EXPIRY", null);
        AlertEventRepository repo = delegating();
        doAnswer(i -> {
            var r = realRepo.findById(i.getArgument(0));
            ack(a.getId());              // okumadan sonra: onay + yeniden uyarı damgası (başka istek / pod)
            stampReAlert(a.getId());
            return r;
        }).when(repo).findById(a.getId());

        AlertEvent out = pod(repo).resolve(a.getId(), "Ayşe Yılmaz (Kanal / Uzman)", "sertifika yenilendi");

        AlertEvent r = row(a.getId());
        assertThat(r.getResolved()).isTrue();
        assertThat(r.getResolvedBy()).isEqualTo("Ayşe Yılmaz (Kanal / Uzman)");
        assertThat(r.getResolvedNote()).isEqualTo("sertifika yenilendi");
        assertThat(r.getResolvedAt()).isEqualTo(out.getResolvedAt());
        assertThat(r.getAcknowledged()).as("bütün varlık kaydı onayı geri alırdı").isTrue();
        assertThat(r.getLastReAlertAt()).isEqualTo("2026-10-23T08:04:30");
        assertThat(r.getResolutionNotify()).as("çözüm postası işareti CAS'ta yazıldı").isEqualTo("PENDING");
        verify(selfMock).sendResolutionNotificationAsync(argThat(e -> e != null && a.getId().equals(e.getId())
                && Boolean.TRUE.equals(e.getResolved())), eq("Ayşe Yılmaz (Kanal / Uzman)"), eq("MANUAL_RESOLVE"));
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("Otomatik çözüm: CAS sonrası bütün varlık kaydı yok — okumadan sonra çözülen fırtına bağı geri yazılmaz, onay korunur")
    void autoResolve_afterCas_stormLinkAndAckKept() {
        String domain = "auto.example.com";
        AlertEvent a = open(domain, PORT, null);
        jdbc.update("UPDATE alert_events SET storm_id = 5 WHERE id = ?", a.getId());
        List<AlertEvent> stale = realRepo.findByDomainAndAlertTypeInAndResolvedFalse(domain, Set.of(PORT));
        assertThat(stale.get(0).getStormId()).isEqualTo(5L);
        // Okumadan sonra: fırtına kapandı (üyenin bağı çözüldü) ve operatör onayladı.
        jdbc.update("UPDATE alert_events SET storm_id = NULL WHERE id = ?", a.getId());
        ack(a.getId());
        AlertEventRepository repo = delegating();
        doAnswer(staleByType(stale)).when(repo).findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection());
        when(maintenance.isUnderMaintenance(domain)).thenReturn(false);
        when(storm.isActive(5L)).thenReturn(false);

        pod(repo).resolveMonitoringAlertsForDomain(domain, PORT);

        AlertEvent r = row(a.getId());
        assertThat(r.getResolved()).isTrue();
        assertThat(r.getResolvedBy()).isEqualTo("system");
        assertThat(r.getStormId()).as("çözülmüş fırtına bağı geri yazılmaz").isNull();
        assertThat(r.getAcknowledged()).isTrue();
        assertThat(r.getResolutionNotify()).isEqualTo("PENDING");
        ArgumentCaptor<AlertEvent> sent = ArgumentCaptor.forClass(AlertEvent.class);
        verify(selfMock).sendResolutionNotificationAsync(sent.capture(), eq("Sistem (otomatik)"), eq("RESOLUTION"));
        assertThat(sent.getValue().getResolvedAt()).isEqualTo(r.getResolvedAt());
        verify(repo, never()).save(any());
    }
}
