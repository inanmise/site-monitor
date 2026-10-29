package com.sitemonitor.service.checks;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AnchoredDbClock} (2026-10-29, çok pod Sürüm 3-3 paket A, tasarım §3.3): tembel çapa (kurucu sorgu yapmaz — K-1),
 * monoton saatle ileri tahmin, 30 sn'de bir yeniden çapa, okuma hatasında 5 dk'ya dek eski çapa sonra pod saati + stale,
 * uzun gidiş-dönüş reddi, gerçek H2 deyimi.
 */
class DbClockTest {

    private static final long DB0 = 1_900_000_000_000L;
    private static final long WALL = 1_700_000_000_000L;

    private final AtomicLong nano = new AtomicLong(1_000_000_000L);
    private final AtomicLong db = new AtomicLong(DB0);
    private final AtomicInteger queries = new AtomicInteger();
    private volatile boolean failing;
    private volatile long rttNanos;

    private AnchoredDbClock clock() {
        return new AnchoredDbClock(() -> {
            queries.incrementAndGet();
            if (failing) throw new IllegalStateException("db down");
            nano.addAndGet(rttNanos);
            return db.get();
        }, nano::get, () -> WALL);
    }

    private void advanceMs(long ms) {
        nano.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
        db.addAndGet(ms);
    }

    @Test
    @DisplayName("Kurucu sorgu yapmaz; ilk nowMs çapalar, sonrası ek sorgu olmadan ilerler; 30 sn'de bir yeniden çapa")
    void lazyAnchorAndExtrapolation() {
        AnchoredDbClock c = clock();
        assertThat(queries).hasValue(0);
        assertThat(c.stale()).as("hiç çapalanmadı").isTrue();
        assertThat(queries).as("stale() da sorgu yapmaz").hasValue(0);
        assertThat(c.nowMs()).isEqualTo(DB0);
        assertThat(queries).hasValue(1);
        assertThat(c.stale()).isFalse();
        advanceMs(10_000);
        assertThat(c.nowMs()).isEqualTo(DB0 + 10_000);
        advanceMs(19_000);
        assertThat(c.nowMs()).isEqualTo(DB0 + 29_000);
        assertThat(queries).hasValue(1);
        advanceMs(1_000);
        db.addAndGet(250);   // veritabanı saati pod saatinden biraz kaydı → yeniden çapa onu yakalar
        assertThat(c.nowMs()).isEqualTo(DB0 + 30_250);
        assertThat(queries).hasValue(2);
        assertThat(c.anchors()).isEqualTo(2);
    }

    @Test
    @DisplayName("Okuma hatası: eski çapadan 5 dk'ya dek ilerler (denemeler 30 sn'de bir), sonra pod saati + stale; düzelince yeniden çapa")
    void failureExtrapolatesThenFallsBack() {
        AnchoredDbClock c = clock();
        assertThat(c.nowMs()).isEqualTo(DB0);
        failing = true;
        advanceMs(31_000);
        assertThat(c.nowMs()).isEqualTo(DB0 + 31_000);
        assertThat(c.stale()).isFalse();
        int afterFirstFail = queries.get();
        advanceMs(1_000);
        c.nowMs();
        assertThat(queries).as("başarısız deneme 30 sn tekrarlanmaz").hasValue(afterFirstFail);
        advanceMs(DbClock.MAX_EXTRAPOLATE_MS);
        assertThat(c.nowMs()).as("5 dk aşıldı → pod saati").isEqualTo(WALL);
        assertThat(c.stale()).isTrue();
        assertThat(c.anchorFailures()).isPositive();
        failing = false;
        advanceMs(31_000);
        assertThat(c.nowMs()).isEqualTo(db.get());
        assertThat(c.stale()).isFalse();
    }

    @Test
    @DisplayName("Hiç çapalanamayan saat: pod saati + stale")
    void neverAnchored() {
        failing = true;
        AnchoredDbClock c = clock();
        assertThat(c.nowMs()).isEqualTo(WALL);
        assertThat(c.stale()).isTrue();
    }

    @Test
    @DisplayName("Gidiş-dönüş 2 sn'yi aşarsa ölçüm atılır; ortada çapalanır")
    void longRoundTripRejected() {
        rttNanos = TimeUnit.MILLISECONDS.toNanos(2_500);
        AnchoredDbClock c = clock();
        assertThat(c.nowMs()).isEqualTo(WALL);
        assertThat(c.stale()).isTrue();
        rttNanos = TimeUnit.MILLISECONDS.toNanos(400);
        advanceMs(31_000);
        long now = c.nowMs();
        // çapa anı gidiş-dönüşün ortası (t0 + 200 ms) → okuma sonu t0 + 400 ms'de "DB0 + 31 000 + 200"
        assertThat(now).isEqualTo(db.get() + 200);
    }

    @Test
    @DisplayName("Gerçek H2: SELECT CURRENT_TIMESTAMP okunur, değer duvar saatine yakın")
    void realH2Statement() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            AnchoredDbClock c = new AnchoredDbClock(mp.jdbc());
            long v = c.nowMs();
            assertThat(c.stale()).isFalse();
            assertThat(Math.abs(v - System.currentTimeMillis())).isLessThan(10_000L);
        }
    }

    /** {@code getConnection} çağrılarını sayan (ve isteğe bağlı bekleten) veri kaynağı. */
    private static DataSource wrapping(DataSource real, AtomicInteger connections, Runnable onConnect) {
        return (DataSource) Proxy.newProxyInstance(DbClockTest.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        connections.incrementAndGet();
                        if (onConnect != null) onConnect.run();
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    @DisplayName("Havuz beklemesi ölçüme girmez (inceleme QW-L4): bağlantı 2,5 sn beklese de deyim hızlıysa çapa alınır")
    void connectionWaitDoesNotDiscardTheAnchor() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            AtomicInteger connections = new AtomicInteger();
            // Havuz doygun: bağlantı alma 2,5 sn (sahte monoton saat) — deyimin kendisi anında.
            DataSource slowPool = wrapping(mp.dataSource(), connections, () -> nano.addAndGet(TimeUnit.MILLISECONDS.toNanos(2_500)));
            AnchoredDbClock c = new AnchoredDbClock(slowPool, nano::get, () -> WALL);
            long v = c.nowMs();
            assertThat(connections).hasValue(1);
            assertThat(c.stale()).as("yalnız deyim ölçülür — çapa atılmadı").isFalse();
            assertThat(c.anchors()).isEqualTo(1);
            assertThat(v).as("pod saati değil, veritabanı saati").isNotEqualTo(WALL);
            assertThat(Math.abs(v - System.currentTimeMillis())).isLessThan(10_000L);
        }
    }

    @Test
    @DisplayName("Deyim 2 sn'yi aşarsa (bağlantı hızlıyken) ölçüm yine atılır — sınır deyime uygulanır")
    void slowStatementStillRejected() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("H2");
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(anyString())).thenAnswer(inv -> {
            nano.addAndGet(TimeUnit.MILLISECONDS.toNanos(2_500));
            return rs;
        });
        when(rs.next()).thenReturn(true);
        when(rs.getObject(1)).thenReturn(new Timestamp(DB0));
        AnchoredDbClock c = new AnchoredDbClock(ds, nano::get, () -> WALL);
        assertThat(c.nowMs()).isEqualTo(WALL);
        assertThat(c.stale()).isTrue();
    }

    @Test
    @DisplayName("PostgreSQL'de çapa clock_timestamp() (işlem başlangıcı değil), diğer türde CURRENT_TIMESTAMP; tür ezberlenir")
    void postgresUsesClockTimestamp() throws Exception {
        List<String> executed = new ArrayList<>();
        AtomicInteger metadataReads = new AtomicInteger();
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenAnswer(inv -> {
            metadataReads.incrementAndGet();
            return md;
        });
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(anyString())).thenAnswer(inv -> {
            executed.add(inv.getArgument(0));
            return rs;
        });
        when(rs.next()).thenReturn(true);
        when(rs.getObject(1)).thenReturn(new Timestamp(DB0));
        AnchoredDbClock c = new AnchoredDbClock(ds, nano::get, () -> WALL);
        assertThat(c.nowMs()).isEqualTo(DB0);
        advanceMs(31_000);
        c.nowMs();
        assertThat(executed).containsExactly(AnchoredDbClock.SQL_DB_NOW_PG, AnchoredDbClock.SQL_DB_NOW_PG);
        assertThat(metadataReads).as("tür bir kez okunur").hasValue(1);
        org.mockito.Mockito.verify(st, org.mockito.Mockito.times(2)).setQueryTimeout(AnchoredDbClock.QUERY_TIMEOUT_SECONDS);
        org.mockito.Mockito.verify(conn, org.mockito.Mockito.times(2)).close();
    }

    @Test
    @DisplayName("Çapa çağıranın Spring işlemine katılmaz (inceleme QW-L3): işlem içinden okunsa da KENDİ bağlantısını alır")
    void anchorNeverJoinsTheCallersTransaction() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            AtomicInteger connections = new AtomicInteger();
            DataSource ds = wrapping(mp.dataSource(), connections, null);
            AnchoredDbClock c = new AnchoredDbClock(new JdbcTemplate(ds));
            DataSourceTransactionManager txm = new DataSourceTransactionManager(ds);
            new TransactionTemplate(txm).executeWithoutResult(status -> {
                new JdbcTemplate(ds).queryForObject("SELECT 1", Integer.class);   // dış işlem bağlantısını aldı
                int before = connections.get();
                long v = c.nowMs();
                assertThat(connections.get()).as("çapa ayrı bir bağlantı açtı (işleme bağlı olanı kullanmadı)")
                        .isEqualTo(before + 1);
                assertThat(c.stale()).isFalse();
                assertThat(Math.abs(v - System.currentTimeMillis())).isLessThan(10_000L);
            });
        }
    }

    @Test
    @DisplayName("Sistem saati sözleşmesi: DbClock.SYSTEM her zaman stale")
    void systemClock() {
        assertThat(DbClock.SYSTEM.stale()).isTrue();
        assertThat(Math.abs(DbClock.SYSTEM.nowMs() - System.currentTimeMillis())).isLessThan(5_000L);
    }
}
