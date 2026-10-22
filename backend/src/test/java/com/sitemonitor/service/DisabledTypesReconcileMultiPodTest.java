package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertEventSilentCloseRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.service.schedule.ClusterLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MP-44 (2026-10-23, çok pod R2a · D2) — bildirimi kapatılan türlerin açık alarmlarının sessiz kapanışı GERÇEK H2 üstünde:
 * iki pod aynı anda uzlaştırır → her alarm BİR kez kapanır, çözüm push'u BİR kez; kapanış koşullu (okunduktan sonra yazılmış
 * onay ve başka yolun çözümü ezilmez); kilit başka pod'dayken uzlaştırma hiçbir şey okumaz; envanter pasifleştirmesi aynı
 * koşullu yolla ve sahiplik kuralıyla kapatır.
 *
 * <p>{@code NOT_SUPPORTED}: {@code @DataJpaTest}'in test işlemi koşullu UPDATE'leri ve yarışı gizlerdi.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DisabledTypesReconcileMultiPodTest {

    private static final String SCRIPTED = EscalationService.TYPE_SCRIPTED_FAIL;
    private static final String PORT = EscalationService.TYPE_PORT_DOWN;

    @Autowired DataSource dataSource;
    @Autowired AlertEventRepository alertEventRepo;
    @Autowired AlertEventSilentCloseRepository closeRepo;
    @Autowired CertificateInventoryRepository inventoryRepo;

    JdbcTemplate jdbc;
    UserPushService push;
    EscalationService escalation;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(MultiPodTestSupport.SCHEDULER_LOCK_DDL);
        jdbc.update("DELETE FROM scheduler_lock");
        alertEventRepo.deleteAll();
        push = mock(UserPushService.class);
        escalation = mock(EscalationService.class);
    }

    @SuppressWarnings("unchecked")
    private SilentAlarmCloser closer() {
        ObjectProvider<UserPushService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(push);
        return new SilentAlarmCloser(closeRepo, alertEventRepo, inventoryRepo, provider);
    }

    /** Bir "pod": kendi MOS örneği, kendi kilit sahibi, ortak veritabanı; betik alarm bildirimleri KAPALI. */
    private MonitoringOutageService pod(int n) {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(settings.getBoolean(eq("site.monitor.scripted.alert-enabled"), anyBoolean())).thenReturn(false);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        MonitoringOutageService mos = new MonitoringOutageService(alertEventRepo, escalation, jdbc,
                mock(DnsRecordRepository.class), mock(DnsMonitorRepository.class), settings, mock(NetworkOutageEventRepository.class));
        for (String f : List.of("uptimeAlertEnabled", "portAlertEnabled", "dnsAlertEnabled", "keywordAlertEnabled", "pingAlertEnabled"))
            org.springframework.test.util.ReflectionTestUtils.setField(mos, f, true);
        mos.setClusterLockService(new ClusterLockService(jdbc, MultiPodTestSupport.owner(n), System::currentTimeMillis, false));
        mos.setSilentAlarmCloser(closer());
        return mos;
    }

    private AlertEvent open(String domain, String type) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("WARNING");
        e.setTeamId(7L);
        e.setResolved(false);
        e.setAcknowledged(false);
        e.setCreatedAt("2026-10-23T08:00:00");
        e.setContextJson("{\"monitor_id\":1,\"team_id\":7,\"standalone\":true}");
        return alertEventRepo.save(e);
    }

    @Test
    @DisplayName("İki pod aynı anda uzlaştırır: her alarm TEK kez kapanır, çözüm push'u TEK kez; açık türün alarmı kalır")
    void twoPodsConcurrent_eachAlarmClosedOnce() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) ids.add(open("flow-" + i, SCRIPTED).getId());
        Long portId = open("app.example.com", PORT).getId();
        MonitoringOutageService a = pod(1), b = pod(2);

        try (MultiPodTestSupport race = MultiPodTestSupport.create()) {
            List<Integer> r = race.race(() -> a.closeAlarmsOfDisabledTypes("ayar değişikliği"),
                    () -> b.closeAlarmsOfDisabledTypes("açılış"));
            int closed = r.stream().filter(x -> x > 0).mapToInt(Integer::intValue).sum();
            assertThat(closed).as("toplam kapanış = alarm sayısı (biri kilidi beklemeden atlar ya da 0 bulur)").isEqualTo(40);
        }
        verify(push, times(40)).enqueueResolve(any(), any(), any());
        verify(escalation, never()).resolveOpenAlertsSilently(any(), any(), any());
        for (Long id : ids) {
            AlertEvent e = alertEventRepo.findById(id).orElseThrow();
            assertThat(e.getResolved()).isTrue();
            assertThat(e.getResolvedSilently()).isTrue();
            assertThat(e.getResolvedBy()).isEqualTo(SilentAlarmCloser.BY_TYPE_DISABLED);
        }
        assertThat(alertEventRepo.findById(portId).orElseThrow().getResolved()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scheduler_lock", Integer.class))
                .as("kilit bırakıldı").isZero();
    }

    @Test
    @DisplayName("Koşullu kapanış: okunduktan SONRA yazılan onay ezilmez; başka yolda kapanmış alarm yeniden yazılmaz, push yok")
    void staleRead_ackAndConcurrentResolveNotOverwritten() {
        AlertEvent staleA = open("a.example.com", SCRIPTED);
        AlertEvent staleB = open("b.example.com", SCRIPTED);
        // Okumadan sonra: A'yı operatör onayladı, B'yi kullanıcı elle çözdü (başka pod / başka istek).
        jdbc.update("UPDATE alert_events SET acknowledged = TRUE, acknowledged_by = 'ops' WHERE id = ?", staleA.getId());
        jdbc.update("UPDATE alert_events SET resolved = TRUE, resolved_by = 'kullanici', resolved_at = '2026-10-23T08:05:00' "
                + "WHERE id = ?", staleB.getId());
        SilentAlarmCloser closer = closer();

        assertThat(closer.closeIfOpen(staleA, SilentAlarmCloser.BY_TYPE_DISABLED)).isTrue();
        assertThat(closer.closeIfOpen(staleB, SilentAlarmCloser.BY_TYPE_DISABLED)).isFalse();

        AlertEvent a = alertEventRepo.findById(staleA.getId()).orElseThrow();
        assertThat(a.getResolved()).isTrue();
        assertThat(a.getAcknowledged()).as("bütün varlık kaydı onayı geri alırdı").isTrue();
        assertThat(a.getAcknowledgedBy()).isEqualTo("ops");
        AlertEvent b = alertEventRepo.findById(staleB.getId()).orElseThrow();
        assertThat(b.getResolvedBy()).as("başka yolun kapanışı ezilmez").isEqualTo("kullanici");
        assertThat(b.getResolvedAt()).isEqualTo("2026-10-23T08:05:00");
        assertThat(b.getResolvedSilently()).isNull();
        verify(push, times(1)).enqueueResolve(argThat(e -> e != null && staleA.getId().equals(e.getId())
                && Boolean.TRUE.equals(e.getResolved())), any(), eq(7L));
        assertThat(staleA.getResolved()).as("okunan varlık değiştirilmez (çağıranın işleminde yönetiliyor olabilir)").isFalse();
    }

    @Test
    @DisplayName("Kilit başka pod'dayken uzlaştırma -1 döner, açık alarmları okumaz ve kapatmaz")
    void lockHeldElsewhere_skipsWithoutTouchingAlarms() {
        Long id = open("flow", SCRIPTED).getId();
        ClusterLockService other = new ClusterLockService(jdbc, MultiPodTestSupport.owner(1), System::currentTimeMillis, false);
        assertThat(other.tryAcquire(MonitoringOutageService.DISABLED_TYPES_LOCK)).isPresent();

        assertThat(pod(2).closeAlarmsOfDisabledTypes("ayar değişikliği")).isEqualTo(-1);
        assertThat(alertEventRepo.findById(id).orElseThrow().getResolved()).isFalse();
        verifyNoInteractions(push);

        other.release(MonitoringOutageService.DISABLED_TYPES_LOCK);
        assertThat(pod(2).closeAlarmsOfDisabledTypes("ayar değişikliği")).isEqualTo(1);
    }

    @Test
    @DisplayName("Ayar kaydı yolu kilit meşgulken sınırlı yeniden dener ve kilit boşalınca kapatır")
    void settingsPath_retriesWhileLockBusy() {
        Long id = open("flow", SCRIPTED).getId();
        MonitoringOutageService p = pod(2);
        ClusterLockService real = new ClusterLockService(jdbc, MultiPodTestSupport.owner(2), System::currentTimeMillis, false);
        ClusterLockService spy = spy(real);
        doReturn(java.util.Optional.empty()).doCallRealMethod()
                .when(spy).tryAcquire(eq(MonitoringOutageService.DISABLED_TYPES_LOCK), any(), any());
        p.setClusterLockService(spy);
        p.disabledTypesRetryDelayMs = 1L;

        p.closeAlarmsOfDisabledTypesWithRetry("ayar değişikliği");

        verify(spy, times(2)).tryAcquire(eq(MonitoringOutageService.DISABLED_TYPES_LOCK), any(), any());
        assertThat(alertEventRepo.findById(id).orElseThrow().getResolved()).isTrue();
    }

    @Test
    @DisplayName("Kilit sürekli meşgul: en çok 6 deneme, sonra bırakır (sonsuz döngü yok); pod kapanıyorsa hemen bırakır")
    void settingsPath_boundedRetry_andStopsAtShutdown() {
        open("flow", SCRIPTED);
        MonitoringOutageService p = pod(2);
        ClusterLockService spy = spy(new ClusterLockService(jdbc, MultiPodTestSupport.owner(2), System::currentTimeMillis, false));
        doReturn(java.util.Optional.empty()).when(spy).tryAcquire(eq(MonitoringOutageService.DISABLED_TYPES_LOCK), any(), any());
        p.setClusterLockService(spy);
        p.disabledTypesRetryDelayMs = 1L;
        p.closeAlarmsOfDisabledTypesWithRetry("ayar değişikliği");
        verify(spy, times(MonitoringOutageService.DISABLED_TYPES_ATTEMPTS)).tryAcquire(eq(MonitoringOutageService.DISABLED_TYPES_LOCK), any(), any());

        clearInvocations(spy);
        spy.stopAcquiring();
        p.closeAlarmsOfDisabledTypesWithRetry("ayar değişikliği");
        verify(spy, times(1)).tryAcquire(eq(MonitoringOutageService.DISABLED_TYPES_LOCK), any(), any());
    }

    @Test
    @DisplayName("Envanter pasifleştirmesi: kendi olayları koşullu kapanır, başka takımın bağımsız izleme olayı kapanmaz")
    void inventoryDeactivate_conditionalAndOwnerScoped() {
        AlertEvent cert = new AlertEvent();
        cert.setDomain("shop.example.com");
        cert.setAlertType("EXPIRY");
        cert.setAlertLevel("WARNING");
        cert.setResolved(false);
        cert.setCreatedAt("2026-10-23T08:00:00");
        cert = alertEventRepo.save(cert);
        AlertEvent standalone = open("shop.example.com", PORT);   // başka takımın bağımsız Port izlemesi
        AlertEvent closedElsewhere = new AlertEvent();
        closedElsewhere.setDomain("shop.example.com");
        closedElsewhere.setAlertType("CHAIN_BROKEN");
        closedElsewhere.setAlertLevel("HIGH");
        closedElsewhere.setResolved(true);
        closedElsewhere.setResolvedBy("system");
        closedElsewhere.setCreatedAt("2026-10-23T07:00:00");
        closedElsewhere = alertEventRepo.save(closedElsewhere);

        assertThat(closer().closeOnInventoryDeactivate("shop.example.com")).isEqualTo(1);

        AlertEvent c = alertEventRepo.findById(cert.getId()).orElseThrow();
        assertThat(c.getResolved()).isTrue();
        assertThat(c.getResolvedBy()).isEqualTo(SilentAlarmCloser.BY_INVENTORY_DEACTIVATE);
        assertThat(c.getResolvedSilently()).isTrue();
        assertThat(alertEventRepo.findById(standalone.getId()).orElseThrow().getResolved()).isFalse();
        assertThat(alertEventRepo.findById(closedElsewhere.getId()).orElseThrow().getResolvedBy()).isEqualTo("system");
        verify(push, times(1)).enqueueResolve(any(), any(), any());
        assertThat(closer().closeOnInventoryDeactivate("shop.example.com")).as("ikinci çağrı no-op").isZero();
    }
}
