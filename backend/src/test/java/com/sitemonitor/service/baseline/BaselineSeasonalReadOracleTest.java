package com.sitemonitor.service.baseline;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.baseline.BaselineMath.History;
import com.sitemonitor.service.baseline.BaselineMath.Sample;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mevsimsel taban okumasının aralık hâli ({@link BaselineSampleStore#readSeasonal}, performans 2026-11-17) ESKİ dairesel
 * uzaklık ifadesiyle BİREBİR aynı satırları, aynı sırayla döner — ORACLE: eski SQL aşağıda kopyalı. Üretilen geçmişler: günlük
 * ve haftalık periyot, farklı aralıklar (30 sn … 6 sa, periyodu bölmeyen 25 dk), gün / hafta sınırı sarması, boşluklar, aynı
 * {@code checked_ms}'te birden çok satır (id sırası), sınırdan az örnek, hiç örnek yok, "bugünün son yarım saati" kuralı.
 */
class BaselineSeasonalReadOracleTest {

    private static final String HTTP = BaselineTypes.HTTP;
    private static final long DAY = BaselineSampleStore.DAY_MS, WEEK = BaselineSampleStore.WEEK_MS;
    private static final long TOL = BaselineSampleStore.SEASON_TOLERANCE_MIN_MS;
    private static final long T0 = 1_800_000_000_000L;

    /** ESKİ ifade — değiştirilmiş BaselineSampleStore.readSeasonal'ın önceki gövdesinden kopya (oracle). */
    private static final String OLD_SQL = "SELECT value_ms, breach FROM " + BaselineSampleStore.TABLE
            + " WHERE monitor_type = ? AND monitor_id = ? AND checked_ms <= ? "
            + "AND LEAST(ABS(MOD(checked_ms, ?) - ?), ? - ABS(MOD(checked_ms, ?) - ?)) <= ? "
            + "ORDER BY checked_ms DESC, id DESC LIMIT ?";

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    BaselineSampleStore store;
    JdbcTemplate jdbc;
    long nextValue = 1;

    @BeforeEach
    void schema() {
        jdbc = mp.jdbc();
        store = new BaselineSampleStore(jdbc);
        new BaselineSchemaPatch(jdbc, store).apply();
    }

    @AfterEach
    void close() {
        mp.close();
    }

    /** ESKİ okuma — readSeasonal'ın önceki gövdesi (üst sınır hesabı dahil). */
    private List<Sample> oldRead(long id, long beforeMs, int limit, long period, long nowPos, long tol) {
        long upper = Math.min(beforeMs - 1, beforeMs - period + Math.max(0, tol));
        return jdbc.query(OLD_SQL, (rs, n) -> new Sample(rs.getLong(1), rs.getBoolean(2)),
                HTTP, id, upper, period, nowPos, period, period, nowPos, tol, limit);
    }

    /** {@code from}'dan {@code to}'ya (dahil) her {@code step} ms'de bir örnek; değer benzersiz (sıra karşılaştırması kesin). */
    private void fill(long id, long from, long to, long step, Random rnd, double gapProb, double dupProb) {
        List<Object[]> batch = new ArrayList<>();
        for (long t = from; t <= to; t += step) {
            if (rnd.nextDouble() < gapProb) continue;   // boşluk (ölçülmeyen kontrol)
            batch.add(new Object[] { HTTP, id, t, nextValue++, rnd.nextInt(5) == 0, 0L });
            if (rnd.nextDouble() < dupProb) batch.add(new Object[] { HTTP, id, t, nextValue++, rnd.nextBoolean(), 0L });
            if (batch.size() >= 5_000) { insert(batch); batch.clear(); }
        }
        if (!batch.isEmpty()) insert(batch);
    }

    private void insert(List<Object[]> rows) {
        jdbc.batchUpdate("INSERT INTO " + BaselineSampleStore.TABLE
                + " (monitor_type, monitor_id, checked_ms, value_ms, breach, created_ms) VALUES (?, ?, ?, ?, ?, ?)", rows);
    }

    private static String flat(List<Sample> s) {
        StringBuilder sb = new StringBuilder();
        for (Sample x : s) sb.append((long) x.value()).append(x.breach() ? "!" : "").append(',');
        return sb.toString();
    }

    private void assertSame(long id, long beforeMs, int limit, long period) {
        long nowPos = Math.floorMod(beforeMs, period);
        List<Sample> oracle = oldRead(id, beforeMs, limit, period, nowPos, TOL);
        History h = store.readSeasonal(HTTP, id, beforeMs, limit, period, nowPos, TOL);
        assertThat(flat(h.samples()))
                .as("monitor %d before %d limit %d period %d (oracle %d satır)", id, beforeMs, limit, period, oracle.size())
                .isEqualTo(flat(oracle));
        assertThat(h.complete()).as("mevsimsel okuma muhafazakâr").isFalse();
    }

    @Test
    @DisplayName("Günlük + haftalık, farklı aralıklar, boşluklar, aynı an satırları, sarma: eski ifadeyle aynı satırlar, aynı sıra")
    void generatedHistoriesMatchOracle() {
        Random rnd = new Random(20261117L);
        long[] steps = { 30_000L, 60_000L, 300_000L, 1_500_000L, 3_600_000L, 21_600_000L };
        long id = 1;
        for (long step : steps) {
            // 21 günlük geçmiş — sınırlı boşluk ve aynı-an satırları
            long end = T0 + rnd.nextInt(1_000_000);
            fill(id, end - 21 * DAY, end, step, rnd, 0.05, 0.02);
            for (int probe = 0; probe < 12; probe++) {
                long before = end - rnd.nextInt((int) (3 * DAY / 1000)) * 1000L + rnd.nextInt(2) * DAY;
                for (int limit : new int[] { 1, 5, 30, 200, 5_000 }) {
                    assertSame(id, before, limit, DAY);
                    assertSame(id, before, limit, WEEK);
                }
            }
            id++;
        }
    }

    @Test
    @DisplayName("Gün sınırı sarması: gece yarısının iki yanı (23:50 ↔ 00:10) ve hafta sınırı — eski ifadeyle aynı")
    void wrapAroundDayAndWeekBoundary() {
        Random rnd = new Random(7);
        long midnight = T0 - Math.floorMod(T0, DAY);
        long weekStart = T0 - Math.floorMod(T0, WEEK);
        fill(10, midnight - 10 * DAY, midnight + 2 * DAY, 60_000L, rnd, 0.1, 0.01);
        fill(11, weekStart - 6 * WEEK, weekStart + WEEK, 300_000L, rnd, 0.1, 0.01);
        long[] offsets = { -40 * 60_000L, -10 * 60_000L, -1, 0, 1, 10 * 60_000L, 29 * 60_000L, 30 * 60_000L, 31 * 60_000L };
        for (long off : offsets) {
            for (int limit : new int[] { 10, 30, 200 }) {
                assertSame(10, midnight + 2 * DAY + off, limit, DAY);
                assertSame(10, midnight + off + 5 * DAY, limit, DAY);
                assertSame(11, weekStart + WEEK + off, limit, WEEK);
                assertSame(11, weekStart + off - 2 * WEEK, limit, WEEK);
            }
        }
    }

    @Test
    @DisplayName("Seyrek eşleşme (6 sa aralık, pencere arada) ve uzun geçmiş: tüm geçmiş taranır, eski ifadeyle aynı (boş dahil)")
    void sparseAndEmpty() {
        Random rnd = new Random(3);
        long midnight = T0 - Math.floorMod(T0, DAY);
        // 00:00, 06:00, 12:00, 18:00 — 03:00 ±30 dk penceresine hiç düşmez (sonuç boş, tüm geçmiş denetlenir)
        fill(20, midnight - 400 * DAY, midnight, 21_600_000L, rnd, 0.0, 0.0);
        for (int limit : new int[] { 1, 30, 200 }) {
            assertSame(20, midnight + 3 * 3_600_000L, limit, DAY);    // 03:00 → hiç eşleşme yok
            assertSame(20, midnight + 6 * 3_600_000L, limit, DAY);    // 06:00 → her gün bir eşleşme (400 gün)
            assertSame(20, midnight + 6 * 3_600_000L, limit, WEEK);
        }
        // Hiç örneği olmayan izleme ve tek örnekli izleme
        assertSame(21, T0, 30, DAY);
        fill(22, T0 - DAY, T0 - DAY, 1, rnd, 0.0, 0.0);
        assertSame(22, T0, 30, DAY);
        assertSame(22, T0 - DAY + TOL, 30, DAY);   // tek örnek bu okumanın üst sınırında
        assertSame(22, T0 - DAY + TOL - 1, 30, DAY);
    }

    @Test
    @DisplayName("Bugünün son yarım saati 'aynı saat' sayılmaz; başka izlemenin satırları karışmaz — eski ifadeyle aynı")
    void upperBoundAndOtherMonitor() {
        Random rnd = new Random(11);
        fill(30, T0 - 3 * DAY, T0, 60_000L, rnd, 0.0, 0.0);
        fill(31, T0 - 3 * DAY, T0, 30_000L, rnd, 0.0, 0.0);   // aynı türde başka izleme
        for (int limit : new int[] { 10, 50, 200 }) {
            assertSame(30, T0, limit, DAY);
            assertSame(30, T0 + 1, limit, DAY);
            assertSame(30, T0 - 45 * 60_000L, limit, DAY);
        }
    }

    @Test
    @DisplayName("Aralık okuması koşulları: tolerans periyodun yarısını geçerse / konum dışarıdaysa eski ifadeye düşülür")
    void rangeReadableGuard() {
        assertThat(BaselineSampleStore.rangeReadable(DAY, 0, TOL, T0)).isTrue();
        assertThat(BaselineSampleStore.rangeReadable(DAY, DAY - 1, TOL, T0)).isTrue();
        assertThat(BaselineSampleStore.rangeReadable(DAY, DAY, TOL, T0)).isFalse();
        assertThat(BaselineSampleStore.rangeReadable(DAY, -1, TOL, T0)).isFalse();
        assertThat(BaselineSampleStore.rangeReadable(DAY, 0, -1, T0)).isFalse();
        assertThat(BaselineSampleStore.rangeReadable(DAY, 0, DAY / 2, T0)).isFalse();
        assertThat(BaselineSampleStore.rangeReadable(DAY, 0, TOL, -1)).isFalse();
        // Dışarıdaki girdi de eski ifadeyle aynı sonucu verir (eski ifade koşar)
        Random rnd = new Random(5);
        fill(40, T0 - 5 * DAY, T0, 600_000L, rnd, 0.0, 0.0);
        long before = T0;
        long upper = Math.min(before - 1, before - DAY + DAY / 2);
        List<Sample> oracle = jdbc.query(OLD_SQL, (rs, n) -> new Sample(rs.getLong(1), rs.getBoolean(2)),
                HTTP, 40L, upper, DAY, 5L, DAY, DAY, 5L, DAY / 2, 100);
        assertThat(flat(store.readSeasonal(HTTP, 40, before, 100, DAY, 5L, DAY / 2).samples())).isEqualTo(flat(oracle));
    }

    @Test
    @DisplayName("SQL şekli: her dal tam dizin koşulu (eşitlik + checked_ms aralığı), dairesel süzgeç yok")
    void sqlShape() {
        String sql = BaselineSampleStore.seasonalRangesSql(3);
        assertThat(sql).doesNotContain("MOD(").doesNotContain("LEAST(").doesNotContain("ABS(");
        assertThat(sql.split("checked_ms >= \\? AND checked_ms <= \\?", -1)).hasSize(4);
        assertThat(sql.chars().filter(c -> c == '?').count()).isEqualTo(3 * 5 + 1);
    }
}
