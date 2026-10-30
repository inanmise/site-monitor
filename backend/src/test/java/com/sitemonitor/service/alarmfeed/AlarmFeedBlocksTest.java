package com.sitemonitor.service.alarmfeed;

import com.sitemonitor.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Alarm akışı otomatik IP engeli — GERÇEK SQL (H2): yazım, katlama, kaldırma, iki pod, veritabanı düşerken bellek. */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlarmFeedBlocksTest {

    static final long T0 = 1_800_000_000_000L;
    static final long MIN = 60_000L;

    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    AlarmFeedBlocks blocks;
    AuditService audit;
    long now = T0;

    static AlarmFeedSettings.Config cfg(int threshold, int minutes, boolean escalate) {
        return new AlarmFeedSettings.Config(true, "WARNING", Set.of(), Set.of(), 15, "status", "Clear", "Critical", "Major",
                "Warning", "target_type", "SiteMonitor", "", "semicolon", List.of(), 30, 2000, true, true, threshold,
                minutes, escalate);
    }

    static final AlarmFeedSettings.Config DEF = cfg(120, 15, true);

    AlarmFeedBlocks pod() {
        AlarmFeedBlocks b = new AlarmFeedBlocks(jdbc);
        b.setAuditService(audit);
        b.clock = () -> now;
        return b;
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        audit = mock(AuditService.class);
        blocks = pod();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM alarm_feed_blocks");
    }

    @Test
    @DisplayName("Eşiği aşan IP süreli engellenir: satır yazılır, bir kez denetlenir; ikinci karar aynı engeli döndürür")
    void placeOnceAndAudit() {
        AlarmFeedBlocks.Placed p = blocks.place("10.1.2.3", 143, "curl/8", DEF, now, null);
        assertThat(p.newBlock()).isTrue();
        assertThat(p.minutes()).isEqualTo(15);
        assertThat(p.until()).isEqualTo(now + 15 * MIN);
        assertThat(blocks.blockedUntil("10.1.2.3", now + MIN)).isEqualTo(p.until());
        assertThat(blocks.blockedUntil("10.9.9.9", now + MIN)).isZero();
        AlarmFeedBlocks.Placed again = blocks.place("10.1.2.3", 160, "curl/8", DEF, now + 2 * MIN, null);
        assertThat(again.newBlock()).isFalse();
        assertThat(again.until()).isEqualTo(p.until());
        verify(audit, times(1)).recordAction(eq("ALARM_FEED_IP_BLOCKED"), anyString(), any(), any(), anyString(),
                eq("ALARM_FEED"), eq("10.1.2.3"), anyString(), anyString(), anyString(), any());
        Map<String, Object> row = jdbc.queryForMap("SELECT hits, threshold, block_count, minutes FROM alarm_feed_blocks");
        assertThat(((Number) row.get("hits")).intValue()).isEqualTo(143);
        assertThat(((Number) row.get("threshold")).intValue()).isEqualTo(120);
        assertThat(((Number) row.get("block_count")).intValue()).isEqualTo(1);
        // Süre bitince engel kalkar
        assertThat(blocks.blockedUntil("10.1.2.3", p.until() + 6_000L)).isZero();
    }

    @Test
    @DisplayName("Tekrarında süre katlanır (15 → 30 → 60), en çok 24 saat; 24 saatten sonra ya da elle kaldırılınca tabandan başlar")
    void escalation() {
        long until1 = blocks.place("10.1.2.3", 200, null, DEF, now, null).until();
        now = until1 + MIN;
        AlarmFeedBlocks.Placed b2 = blocks.place("10.1.2.3", 200, null, DEF, now, null);
        assertThat(b2.minutes()).isEqualTo(30);
        assertThat(b2.blockCount()).isEqualTo(2);
        now = b2.until() + MIN;
        assertThat(blocks.place("10.1.2.3", 200, null, DEF, now, null).minutes()).isEqualTo(60);
        // Katlama kapalı → taban
        now += 2 * 3600_000L;
        assertThat(blocks.place("10.1.2.3", 200, null, cfg(120, 15, false), now, null).minutes()).isEqualTo(15);
        // 24 saat sessizlik → taban
        now += 26 * 3600_000L;
        assertThat(blocks.place("10.1.2.3", 200, null, DEF, now, null).minutes()).isEqualTo(15);
        // Elle kaldırılan engel katlamayı sıfırlar
        assertThat(blocks.unblock("10.1.2.3", "admin", now + MIN)).isTrue();
        now += 2 * MIN;
        assertThat(blocks.place("10.1.2.3", 200, null, DEF, now, null).minutes()).isEqualTo(15);
        // Üst sınır
        assertThat(AlarmFeedBlocks.minutesFor(new AlarmFeedBlocks.Row(now - MIN, 1000, 5, null), 15, true, now)).isEqualTo(1440);
    }

    @Test
    @DisplayName("Elle kaldırma: engel hemen kalkar, satır 'kaldırıldı' olur; süren engel yoksa false")
    void unblock() {
        blocks.place("10.1.2.3", 200, null, DEF, now, null);
        assertThat(blocks.unblock("10.1.2.3", "admin", now + MIN)).isTrue();
        assertThat(blocks.blockedUntil("10.1.2.3", now + MIN)).isZero();
        List<Map<String, Object>> list = blocks.list(now + 2 * MIN).rows();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("state")).isEqualTo(AlarmFeedBlocks.STATE_UNBLOCKED);
        assertThat(list.get(0).get("unblocked_by")).isEqualTo("admin");
        assertThat(blocks.unblock("10.1.2.3", "admin", now + 3 * MIN)).isFalse();
        assertThat(blocks.unblock("10.7.7.7", "admin", now + 3 * MIN)).isFalse();
    }

    @Test
    @DisplayName("İki pod: A'nın engeli B'de tazeleme aralığından sonra uygulanır; A'nın kaldırması da B'ye yayılır")
    void twoPodsShareBlocks() {
        AlarmFeedBlocks b = pod();
        assertThat(b.blockedUntil("10.1.2.3", now)).isZero();   // B listeyi okudu (engel yok)
        long until = blocks.place("10.1.2.3", 200, null, DEF, now, null).until();
        assertThat(b.blockedUntil("10.1.2.3", now + 1_000L)).isZero();   // tazeleme aralığı dolmadı
        assertThat(b.blockedUntil("10.1.2.3", now + AlarmFeedBlocks.REFRESH_MS + 1)).isEqualTo(until);
        blocks.unblock("10.1.2.3", "admin", now + 10_000L);
        assertThat(b.blockedUntil("10.1.2.3", now + 2 * AlarmFeedBlocks.REFRESH_MS + 1)).isZero();
    }

    @Test
    @DisplayName("İki pod aynı anda engellerse tek engel yazılır ve tek denetim kaydı düşer")
    void concurrentPlaceWritesOnce() throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                jdbc.update("DELETE FROM alarm_feed_blocks");
                audit = mock(AuditService.class);
                AlarmFeedBlocks a = pod();
                AlarmFeedBlocks b = pod();
                CountDownLatch go = new CountDownLatch(1);
                Future<AlarmFeedBlocks.Placed> fa = ex.submit(() -> { go.await(); return a.place("10.1.2.3", 200, null, DEF, now, null); });
                Future<AlarmFeedBlocks.Placed> fb = ex.submit(() -> { go.await(); return b.place("10.1.2.3", 200, null, DEF, now, null); });
                go.countDown();
                AlarmFeedBlocks.Placed pa = fa.get(30, TimeUnit.SECONDS);
                AlarmFeedBlocks.Placed pb = fb.get(30, TimeUnit.SECONDS);
                assertThat(pa.newBlock() ^ pb.newBlock()).as("tam olarak biri yazdı").isTrue();
                assertThat(jdbc.queryForObject("SELECT block_count FROM alarm_feed_blocks", Integer.class)).isEqualTo(1);
                verify(audit, times(1)).recordAction(eq("ALARM_FEED_IP_BLOCKED"), anyString(), any(), any(), anyString(),
                        anyString(), anyString(), anyString(), anyString(), any(), any());
            }
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    @DisplayName("Veritabanı yazılamazsa engel bu pod'un belleğinde yine uygulanır (sel altında açık kalmaz)")
    void databaseDownStillBlocksLocally() {
        JdbcTemplate broken = mock(JdbcTemplate.class, inv -> {
            if (inv.getMethod().getName().equals("getDataSource")) return null;
            throw new org.springframework.dao.DataAccessResourceFailureException("db");
        });
        AlarmFeedBlocks b = new AlarmFeedBlocks(broken);
        b.clock = () -> now;
        AlarmFeedBlocks.Placed p = b.place("10.1.2.3", 200, null, DEF, now, null);
        assertThat(p.newBlock()).isTrue();
        assertThat(b.blockedUntil("10.1.2.3", now + MIN)).isEqualTo(p.until());
        assertThat(b.activeCount(now + MIN)).isEqualTo(1);
        assertThat(b.blockedUntil("10.1.2.3", p.until() + 1)).isZero();
    }

    /** SQL_ACTIVE okunduktan HEMEN sonra kancayı çalıştıran şablon (yenileme ↔ yerel yazım yarışı). */
    static final class HookedJdbc extends JdbcTemplate {
        Runnable afterActiveRead;
        volatile boolean failing;

        HookedJdbc(DataSource ds) {
            super(ds);
        }

        @Override
        public void query(String sql, org.springframework.jdbc.core.RowCallbackHandler rch, Object... args) {
            if (failing) throw new org.springframework.dao.DataAccessResourceFailureException("db");
            super.query(sql, rch, args);
            if (AlarmFeedBlocks.SQL_ACTIVE.equals(sql) && afterActiveRead != null) {
                Runnable r = afterActiveRead;
                afterActiveRead = null;
                r.run();
            }
        }

        @Override
        public <T> T query(String sql, org.springframework.jdbc.core.ResultSetExtractor<T> rse, Object... args) {
            if (failing) throw new org.springframework.dao.DataAccessResourceFailureException("db");
            return super.query(sql, rse, args);
        }

        @Override
        public int update(String sql, Object... args) {
            if (failing) throw new org.springframework.dao.DataAccessResourceFailureException("db");
            return super.update(sql, args);
        }
    }

    @Test
    @DisplayName("Yenileme sorgusu sürerken bu pod'da yazılan engel / kaldırma yenilemeyle EZİLMEZ")
    void refreshDoesNotEraseConcurrentLocalWrites() {
        HookedJdbc hj = new HookedJdbc(dataSource);
        AlarmFeedBlocks b = new AlarmFeedBlocks(hj, true);
        b.clock = () -> now;
        // Anlık görüntü engelsiz okundu, sonra engel yazıldı → yenileme onu silmemeli
        hj.afterActiveRead = () -> b.place("10.1.2.3", 200, null, DEF, now, null);
        // Yenilemeyi tetikler; kanca engeli anlık görüntüden SONRA yazar — yenileme onu yeni listeye taşımalı
        assertThat(b.blockedUntil("10.1.2.3", now)).isEqualTo(now + 15 * MIN);
        assertThat(b.blockedUntil("10.1.2.3", now + 1_000L)).isEqualTo(now + 15 * MIN);
        // Anlık görüntü engelli okundu, sonra elle kaldırıldı → yenileme engeli geri getirmemeli
        hj.afterActiveRead = () -> b.unblock("10.1.2.3", "admin", now + AlarmFeedBlocks.REFRESH_MS + 1);
        b.blockedUntil("10.1.2.3", now + AlarmFeedBlocks.REFRESH_MS + 1);
        assertThat(b.blockedUntil("10.1.2.3", now + AlarmFeedBlocks.REFRESH_MS + 2)).isZero();
    }

    @Test
    @DisplayName("Veritabanı düşükken yazılan yerel engel, veritabanı dönünce yazılır (diğer pod'lar ve ekran görür)")
    void localBlockIsPersistedAfterRecovery() {
        HookedJdbc hj = new HookedJdbc(dataSource);
        AlarmFeedBlocks b = new AlarmFeedBlocks(hj, true);
        b.clock = () -> now;
        hj.failing = true;
        AlarmFeedBlocks.Placed p = b.place("10.4.4.4", 200, null, DEF, now, null);
        hj.failing = false;
        assertThat(b.blockedUntil("10.4.4.4", now + 6_000L)).isEqualTo(p.until());   // yenileme + yazım
        assertThat(jdbc.queryForObject("SELECT blocked_until_ms FROM alarm_feed_blocks WHERE client_ip = '10.4.4.4'", Long.class))
                .isEqualTo(p.until());
        AlarmFeedBlocks other = pod();
        assertThat(other.blockedUntil("10.4.4.4", now + 7_000L)).isEqualTo(p.until());
        assertThat(other.unblock("10.4.4.4", "admin", now + 8_000L)).isTrue();
    }

    @Test
    @DisplayName("Denetim bütçesi: dakikada 10 engel tek tek, fazlası sonraki dakikadaki ilk engelde TEK özet kaydı")
    void auditBudget() {
        for (int i = 0; i < 13; i++) blocks.place("10.8.0." + i, 200, null, DEF, now, null);
        verify(audit, times(10)).recordAction(eq("ALARM_FEED_IP_BLOCKED"), anyString(), any(), any(), anyString(),
                eq("ALARM_FEED"), org.mockito.ArgumentMatchers.startsWith("10.8.0."), anyString(), anyString(), any(), any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alarm_feed_blocks", Integer.class)).isEqualTo(13);
        now += 61_000L;
        blocks.place("10.8.1.1", 200, null, DEF, now, null);
        verify(audit, times(1)).recordAction(eq("ALARM_FEED_IP_BLOCKED"), anyString(), any(), any(), anyString(),
                eq("ALARM_FEED"), eq("*"), org.mockito.ArgumentMatchers.contains("\"suppressed_blocks\":3"), any(), any(), any());
        verify(audit, times(11)).recordAction(eq("ALARM_FEED_IP_BLOCKED"), anyString(), any(), any(), anyString(),
                eq("ALARM_FEED"), org.mockito.ArgumentMatchers.startsWith("10.8."), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Liste: etkin önce kalan süreyle, biten 'expired'; denetim yoksa sessiz")
    void listStates() {
        blocks.setAuditService(null);
        blocks.place("10.1.1.1", 200, "ua-1", DEF, now, null);
        now += 20 * MIN;
        blocks.place("10.2.2.2", 300, "ua-2", DEF, now, null);
        AlarmFeedBlocks.Listing l = blocks.list(now + MIN);
        assertThat(l.active()).isEqualTo(1);
        assertThat(l.activeTruncated()).isFalse();
        List<Map<String, Object>> list = l.rows();
        assertThat(list).extracting(m -> m.get("ip")).containsExactly("10.2.2.2", "10.1.1.1");
        assertThat(list.get(0).get("state")).isEqualTo(AlarmFeedBlocks.STATE_ACTIVE);
        assertThat((Long) list.get(0).get("remaining_seconds")).isBetween(13 * 60L, 14 * 60L);
        assertThat(list.get(1).get("state")).isEqualTo(AlarmFeedBlocks.STATE_EXPIRED);
        assertThat(list.get(0).get("last_user_agent")).isEqualTo("ua-2");
        verify(audit, never()).recordSecurityEvent(anyString(), any(), any(), anyString(), anyString(), anyString());
    }
}
