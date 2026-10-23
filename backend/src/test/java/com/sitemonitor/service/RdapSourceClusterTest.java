package com.sitemonitor.service;

import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.RetentionRunRepository;
import com.sitemonitor.repository.SystemHeartbeatRepository;
import com.sitemonitor.service.health.ClusterHealthSchemaPatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RDAP veri kaynağı kartı kümede (2026-10-23, çok pod R2b, N4): RDAP sorgusunu yalnız alan adı taramalarını koşturan lider pod
 * yapar; takipçi eskiden kendi belleğine bakıp "boşta" diyor, RDAP kesintisini gizliyordu. Artık her pod son gözlemini
 * heartbeat ritminde yayınlar, kart kümedeki EN YENİ gözlemi + gözlemleyen pod'u gösterir.
 */
class RdapSourceClusterTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    static final String LEADER = "sitemonitor-6b7c-aaaaa-0000000a";
    static final String FOLLOWER = "sitemonitor-6b7c-bbbbb-0000000b";

    JdbcTemplate jdbc;
    RdapDomainClient leaderRdap;
    RdapDomainClient followerRdap;
    ExtendedHealthService leader;
    ExtendedHealthService follower;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:rdapcluster" + SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
        new ClusterHealthSchemaPatch(jdbc).apply();
        leaderRdap = mock(RdapDomainClient.class);
        followerRdap = mock(RdapDomainClient.class);
        leader = service(LEADER, leaderRdap);
        follower = service(FOLLOWER, followerRdap);
        when(followerRdap.getSourceStatus()).thenReturn(status("IDLE", false, null, null, null));
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("SHUTDOWN");
    }

    ExtendedHealthService service(String id, RdapDomainClient rdap) {
        return service(id, rdap, jdbc);
    }

    ExtendedHealthService service(String id, RdapDomainClient rdap, JdbcTemplate template) {
        ExtendedHealthService s = new ExtendedHealthService(mock(NotificationLogRepository.class), mock(SystemHeartbeatRepository.class),
                mock(AlertEventRepository.class), template, rdap, mock(RetentionRunRepository.class), mock(AppSettingsService.class));
        s.instanceIdSupplier = () -> id;
        return s;
    }

    static Map<String, Object> status(String source, boolean alarm, String reason, String lastOk, String lastErr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("alarm", alarm);
        m.put("reason", reason);
        m.put("last_success", lastOk);
        m.put("last_error", lastErr);
        m.put("bootstrap_loaded", true);
        m.put("bootstrap_tld_count", 1200);
        return m;
    }

    @Test
    @DisplayName("lider RDAP hatası gördü → takipçinin kartı da NONE + alarm, gözlemleyen = lider (eskiden IDLE, kesinti gizli)")
    void followerShowsLeadersOutage() {
        when(leaderRdap.getSourceStatus()).thenReturn(status("NONE", true, "connect timed out", "2026-10-22T06:00:00Z", "2026-10-23T06:00:00Z"));
        leader.publishDomainExpirySource();

        Map<String, Object> f = follower.getDomainExpirySourceStatus();
        assertThat(f).containsEntry("source", "NONE").containsEntry("alarm", true).containsEntry("reason", "connect timed out")
                .containsEntry("scope", "cluster").containsEntry("observed_by", LEADER)
                .containsEntry("observed_by_pod", "sitemonitor-6b7c-aaaaa")
                .containsEntry("observed_at", "2026-10-23T06:00:00Z");
        assertThat(leader.getDomainExpirySourceStatus()).containsEntry("source", "NONE").containsEntry("observed_by", LEADER);
    }

    @Test
    @DisplayName("takipçinin kendi gözlemi daha yeniyse (elle tanı başarılı) kendi durumu; hiçbir pod sorgu yapmadıysa IDLE (küme)")
    void newestObservationWins_idleWhenNoneAnywhere() {
        assertThat(follower.getDomainExpirySourceStatus()).containsEntry("source", "IDLE").containsEntry("scope", "cluster")
                .containsEntry("observed_by", null);

        when(leaderRdap.getSourceStatus()).thenReturn(status("NONE", true, "x", null, "2026-10-23T06:00:00Z"));
        leader.publishDomainExpirySource();
        when(followerRdap.getSourceStatus()).thenReturn(status("RDAP", false, null, "2026-10-23T06:05:00Z", null));
        assertThat(follower.getDomainExpirySourceStatus()).containsEntry("source", "RDAP").containsEntry("observed_by", FOLLOWER);
        // takipçi yayınlayınca lider de en yeniyi görür
        follower.publishDomainExpirySource();
        assertThat(leader.getDomainExpirySourceStatus()).containsEntry("source", "RDAP").containsEntry("observed_by", FOLLOWER);
    }

    @Test
    @DisplayName("yayın: yalnız yeni bir sorgu olduysa yazılır (her heartbeat'te değil); boşta pod hiç yazmaz")
    void publishOnlyOnNewEvent() {
        JdbcTemplate spy = org.mockito.Mockito.spy(jdbc);
        ExtendedHealthService s = service(LEADER, leaderRdap, spy);
        when(leaderRdap.getSourceStatus()).thenReturn(status("RDAP", false, null, "2026-10-23T06:00:00Z", null));
        s.publishDomainExpirySource();
        s.publishDomainExpirySource();
        s.publishDomainExpirySource();
        assertThat(observationWrites(spy)).as("ilk yayın: UPDATE (0 satır) + INSERT; sonraki iki tık yazmaz").isEqualTo(2);

        JdbcTemplate idleSpy = org.mockito.Mockito.spy(jdbc);
        ExtendedHealthService idle = service(FOLLOWER, followerRdap, idleSpy);
        idle.publishDomainExpirySource();
        assertThat(observationWrites(idleSpy)).isZero();
    }

    static long observationWrites(JdbcTemplate spy) {
        return org.mockito.Mockito.mockingDetails(spy).getInvocations().stream()
                .filter(i -> "update".equals(i.getMethod().getName()))
                .filter(i -> i.getMethod().getParameterTypes().length == 2 && i.getMethod().getParameterTypes()[1] == Object[].class)
                .filter(i -> i.getArguments().length > 0 && i.getArguments()[0] instanceof String sql
                        && (sql.startsWith("UPDATE cluster_observations") || sql.startsWith("INSERT INTO cluster_observations")))
                .count();
    }

    @Test
    @DisplayName("tablo okunamazsa bu pod'un durumu, scope=local (küme gerçeği gibi gösterilmez)")
    void missingTableIsLocal() {
        jdbc.execute("DROP TABLE cluster_observations");
        assertThat(follower.getDomainExpirySourceStatus()).containsEntry("source", "IDLE").containsEntry("scope", "local");
    }
}
