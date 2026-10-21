package com.sitemonitor.service.outage;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.SchedulerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Kaynak başına tek açık ağ kesintisi olayı — tek seferlik düzeltme (2026-10-21, çok pod — MP-01 / MP-16), H2. Kısmi tekil
 * indeks PostgreSQL'e özgü ({@code NetworkOutageIndexPostgresTest}); burada temizlik, nişan ve kilit sözleşmesi.
 */
class NetworkOutageOngoingDedupeTest {

    private MultiPodTestSupport mp;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        jdbc = mp.jdbc();
        NetworkOutageTestSchema.create(jdbc);
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SchedulerService> provider(SchedulerService s) {
        ObjectProvider<SchedulerService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(s);
        return p;
    }

    @Test
    @DisplayName("Her kaynakta EN YENİ açık olay kalır, eskiler kapanır (çözüm postası yok); nişan yazılır, ikinci koşu no-op")
    void keepsNewestPerSource_writesMarker_idempotent() {
        long cert1 = NetworkOutageTestSchema.insertLegacyOngoing(jdbc, null, "2026-10-20T01:00:00");
        long cert2 = NetworkOutageTestSchema.insertLegacyOngoing(jdbc, "CERT", "2026-10-20T02:00:00");
        long cert3 = NetworkOutageTestSchema.insertLegacyOngoing(jdbc, null, "2026-10-20T03:00:00");
        long acc1 = NetworkOutageTestSchema.insertLegacyOngoing(jdbc, "ACCESSIBILITY", "2026-10-20T01:00:00");
        long acc2 = NetworkOutageTestSchema.insertLegacyOngoing(jdbc, "ACCESSIBILITY", "2026-10-20T02:00:00");
        long port = NetworkOutageTestSchema.insertLegacyOngoing(jdbc, "PORT_DOWN", "2026-10-20T02:00:00");
        NetworkOutageOngoingDedupe job = new NetworkOutageOngoingDedupe(jdbc, provider(null));

        assertThat(job.done()).isFalse();
        assertThat(job.runLocked()).isEqualTo(3);

        assertThat(jdbc.queryForList("SELECT id FROM network_outage_events WHERE status = 'ONGOING' ORDER BY id", Long.class))
                .containsExactly(cert3, acc2, port);
        assertThat(jdbc.queryForList("SELECT resolved_mail_status FROM network_outage_events WHERE id IN (?, ?, ?)",
                String.class, cert1, cert2, acc1)).containsOnly(CertNetworkOutageService.MAIL_SKIPPED_DUPLICATE);
        assertThat(jdbc.queryForObject("SELECT resolved_at FROM network_outage_events WHERE id = ?", String.class, cert1)).isNotNull();
        assertThat(job.done()).as("H2: nişan yeter (indeks PostgreSQL'e özgü)").isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class,
                NetworkOutageOngoingDedupe.KEY)).isEqualTo(1);

        assertThat(job.runLocked()).as("bitti → no-op").isZero();
    }

    @Test
    @DisplayName("Zamanlanmış koşu kendi kilidiyle (noe-ongoing-dedupe) çalışır; kilit başkasındaysa iş yapmaz, nişan yazılmaz")
    void run_usesOwnLock() {
        NetworkOutageTestSchema.insertLegacyOngoing(jdbc, null, "2026-10-20T01:00:00");
        NetworkOutageTestSchema.insertLegacyOngoing(jdbc, null, "2026-10-20T02:00:00");
        SchedulerService sched = mock(SchedulerService.class);
        when(sched.callWithSchedulerLock(eq(NetworkOutageOngoingDedupe.LOCK), any())).thenReturn(Optional.empty());
        NetworkOutageOngoingDedupe busy = new NetworkOutageOngoingDedupe(jdbc, provider(sched));

        busy.run();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM network_outage_events WHERE status = 'ONGOING'", Integer.class)).isEqualTo(2);
        assertThat(busy.done()).isFalse();

        SchedulerService free = mock(SchedulerService.class);
        when(free.callWithSchedulerLock(eq(NetworkOutageOngoingDedupe.LOCK), any())).thenAnswer(i -> {
            Supplier<Integer> task = i.getArgument(1);
            return Optional.ofNullable(task.get());
        });
        NetworkOutageOngoingDedupe job = new NetworkOutageOngoingDedupe(jdbc, provider(free));
        job.run();
        job.run();   // bitti → kilit bile istenmez

        verify(free, times(1)).callWithSchedulerLock(eq(NetworkOutageOngoingDedupe.LOCK), any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM network_outage_events WHERE status = 'ONGOING'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("Veritabanı türü okunamazsa 'PostgreSQL değil → bitti' denmez: hata, nişan yazılmaz; 24 denemede pod bırakır")
    void unreadableDialect_isNotDone() {
        JdbcTemplate broken = mock(JdbcTemplate.class);
        when(broken.execute(any(org.springframework.jdbc.core.ConnectionCallback.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("havuz dolu"));
        when(broken.queryForObject(org.mockito.ArgumentMatchers.anyString(), eq(Integer.class), any())).thenReturn(0);
        NetworkOutageOngoingDedupe job = new NetworkOutageOngoingDedupe(broken, provider(null));

        assertThat(job.postgresOrNull()).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(job::runLocked).isInstanceOf(IllegalStateException.class);
        verify(broken, never()).update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO schema_patch_markers"), any(Object[].class));

        SchedulerService sched = mock(SchedulerService.class);
        when(sched.callWithSchedulerLock(eq(NetworkOutageOngoingDedupe.LOCK), any())).thenAnswer(i -> {
            Supplier<Integer> task = i.getArgument(1);
            return Optional.ofNullable(task.get());
        });
        NetworkOutageOngoingDedupe scheduled = spy(new NetworkOutageOngoingDedupe(broken, provider(sched)));
        doReturn(false).when(scheduled).done();
        for (int i = 0; i < 30; i++) scheduled.run();
        verify(sched, times(NetworkOutageOngoingDedupe.MAX_ATTEMPTS)).callWithSchedulerLock(eq(NetworkOutageOngoingDedupe.LOCK), any());
    }

    @Test
    @DisplayName("Kapatılan çift satırın açılış postası ara hâlde kalmaz (inceleme S-3): bekleyen → SUPERSEDED, gönderiliyor → UNKNOWN, gitmiş olduğu gibi")
    void closedDuplicateMailStatusIsFinal() {
        long pend = NetworkOutageTestSchema.insertLegacyOngoing(jdbc, null, "2026-10-20T01:00:00");
        long sending = NetworkOutageTestSchema.insertLegacyOngoing(jdbc, null, "2026-10-20T02:00:00");
        long sent = NetworkOutageTestSchema.insertLegacyOngoing(jdbc, null, "2026-10-20T03:00:00");
        long newest = NetworkOutageTestSchema.insertLegacyOngoing(jdbc, null, "2026-10-20T04:00:00");
        jdbc.update("UPDATE network_outage_events SET alert_mail_status = 'RETRY' WHERE id = ?", pend);
        jdbc.update("UPDATE network_outage_events SET alert_mail_status = 'SENDING', alert_mail_claimed_ms = 1 WHERE id = ?", sending);
        jdbc.update("UPDATE network_outage_events SET alert_mail_status = 'SENT' WHERE id = ?", sent);
        jdbc.update("UPDATE network_outage_events SET alert_mail_status = 'PENDING' WHERE id = ?", newest);

        new NetworkOutageOngoingDedupe(jdbc, provider(null)).dedupe();

        assertThat(jdbc.queryForObject("SELECT alert_mail_status FROM network_outage_events WHERE id = ?", String.class, pend))
                .isEqualTo(CertNetworkOutageService.MAIL_SUPERSEDED);
        assertThat(jdbc.queryForObject("SELECT alert_mail_status FROM network_outage_events WHERE id = ?", String.class, sending))
                .isEqualTo(CertNetworkOutageService.MAIL_UNKNOWN);
        assertThat(jdbc.queryForObject("SELECT alert_mail_status FROM network_outage_events WHERE id = ?", String.class, sent))
                .isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT alert_mail_status FROM network_outage_events WHERE id = ?", String.class, newest))
                .as("kalan en yeni açık satıra dokunulmaz").isEqualTo("PENDING");
    }
}
