package com.sitemonitor.service;

import com.sitemonitor.model.NetworkOutageEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Çok pod R1-B (2026-10-21, MP-16 / SWEEPS-8) — izleme türü bastırma kaydı ({@code network_outage_events}, kaynak = tür)
 * kaynak başına TEK açık satırdır. Bellekteki "geçiş" bayrağı pod başınadır: liderlik devrinde yeni lider aynı kesintiye
 * yeniden "girer" — eskiden ikinci ONGOING satırı yazıyor, temiz turda yalnız en yenisini kapatıyor, ilki sonsuza dek
 * "sürüyor" kalıyordu. Gerçek depo + H2 (iki {@link MonitoringOutageService} örneği aynı tabloyu paylaşır).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:suppressionrows;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver"})
class SuppressionRowsMultiPodTest {

    static final String TYPE = EscalationService.TYPE_PORT_DOWN;

    @Autowired NetworkOutageEventRepository repo;
    @Autowired JdbcTemplate jdbc;

    MonitoringOutageService podA;
    MonitoringOutageService podB;
    AtomicBoolean aLeads = new AtomicBoolean(true);

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        podA = mos();
        podB = mos();
        podA.setScheduledResultsFence(aLeads::get);
        podB.setScheduledResultsFence(() -> !aLeads.get());
    }

    MonitoringOutageService mos() {
        AlertEventRepository alerts = mock(AlertEventRepository.class);
        lenient().when(alerts.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        AppSettingsService settings = mock(AppSettingsService.class);
        lenient().when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        MonitoringOutageService s = new MonitoringOutageService(alerts, mock(EscalationService.class), mock(JdbcTemplate.class),
                mock(DnsRecordRepository.class), mock(DnsMonitorRepository.class), settings, repo);
        ReflectionTestUtils.setField(s, "portAlertEnabled", true);
        ReflectionTestUtils.setField(s, "bulkRateThreshold", 0.50);
        ReflectionTestUtils.setField(s, "bulkMinErrors", 3);
        ReflectionTestUtils.setField(s, "confirmAttempts", 3);
        return s;
    }

    static List<MonitoringOutageService.SweepItem> networkDown() {
        return List.of(item("a.example.com", false), item("b.example.com", false), item("c.example.com", false));
    }

    static List<MonitoringOutageService.SweepItem> healthy() {
        return List.of(item("a.example.com", true), item("b.example.com", true), item("c.example.com", true));
    }

    static MonitoringOutageService.SweepItem item(String host, boolean up) {
        return new MonitoringOutageService.SweepItem(TYPE, host, "443/TCP", up, up ? null : "Connect timed out", Map.of(),
                () -> Map.of("status", up ? "up" : "down"));
    }

    /** Liderlik A → B (iki pod da olayı görür). */
    void handOverToB() {
        aLeads.set(false);
        podA.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(false, "pod1", 0L, SweepLeadershipChangedEvent.LOST));
        podB.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(true, "pod2", 0L, SweepLeadershipChangedEvent.GAINED));
    }

    void handOverToA() {
        aLeads.set(true);
        podB.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(false, "pod2", 0L, SweepLeadershipChangedEvent.RELEASED));
        podA.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(true, "pod1", 0L, SweepLeadershipChangedEvent.GAINED));
    }

    long ongoing() {
        return repo.findByStatus("ONGOING").stream().filter(e -> TYPE.equals(e.getSource())).count();
    }

    @Test
    @DisplayName("A bastırma açar → devir → B de bastırılmış tur görür (ikinci satır YOK) → B temiz tur → tek satır RESOLVED")
    void handoverDuringSuppression_singleRow_resolved() {
        podA.handleSweepResults(TYPE, networkDown());
        assertThat(ongoing()).isEqualTo(1);

        handOverToB();
        podB.handleSweepResults(TYPE, networkDown());
        assertThat(repo.count()).as("ikinci ONGOING satırı yazılmadı").isEqualTo(1);

        podB.handleSweepResults(TYPE, healthy());
        assertThat(ongoing()).isZero();
        List<NetworkOutageEvent> all = repo.findAll();
        assertThat(all).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo("RESOLVED");
            assertThat(e.getResolvedAt()).isNotBlank();
        });
    }

    @Test
    @DisplayName("Bastırmalı / temiz turlar iki pod arasında gidip gelir → her kesinti tek satır, hepsi kapanır")
    void alternatingRuns_onePerOutage_allResolved() {
        podA.handleSweepResults(TYPE, networkDown());
        handOverToB();
        podB.handleSweepResults(TYPE, networkDown());
        podB.handleSweepResults(TYPE, healthy());
        handOverToA();
        podA.handleSweepResults(TYPE, healthy());      // A'nın belleği sıfırlandı — DB'ye bir kez bakar, açık kayıt yok
        podA.handleSweepResults(TYPE, networkDown());  // ikinci kesinti
        handOverToB();
        podB.handleSweepResults(TYPE, healthy());
        assertThat(ongoing()).isZero();
        assertThat(repo.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Bu düzeltmeden önce iki pod'un açtığı iki ONGOING satırı: ilk temiz tur İKİSİNİ de kapatır")
    void legacyDuplicateOngoingRows_allResolved() {
        for (int i = 0; i < 2; i++) {
            NetworkOutageEvent e = new NetworkOutageEvent();
            e.setDetectedAt("2026-10-21T05:00:0" + i);
            e.setStatus("ONGOING");
            e.setSource(TYPE);
            repo.save(e);
        }
        podA.handleSweepResults(TYPE, healthy());
        assertThat(ongoing()).isZero();
        assertThat(repo.findAll()).allSatisfy(e -> assertThat(e.getDurationMs()).isNotNull());
    }

    @Test
    @DisplayName("Takipçi (kapı kapalı) bastırma kaydına dokunmaz")
    void followerDoesNotWrite() {
        aLeads.set(false);
        podA.handleSweepResults(TYPE, networkDown());
        assertThat(repo.count()).isZero();
    }
}
