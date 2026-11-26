package com.sitemonitor.service;

import com.sitemonitor.service.schedule.SideJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tek seferlik erişilebilirlik özeti yeniden hesabı (H paketi 2026-11-26, rapor B1 / B2): nişan + kilit + deneme tavanı,
 * sürüm karışıkken bekleme, PostgreSQL dışında yalnız nişan, dilim döngüsü / imleç / bütçe / kilit kaybı. Gerçek SQL
 * (silme + yeniden yazma tek işlemde) {@code AvailabilityRollupRecomputePostgresTest}'te.
 */
class AvailabilityRollupRecomputeTest {

    private JdbcTemplate jdbc;
    private SchedulerService scheduler;
    private DeploymentHistoryService history;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:arrtest" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", ""));
        scheduler = mock(SchedulerService.class);
        history = mock(DeploymentHistoryService.class);
        when(history.liveOtherVersions()).thenReturn(List.of());
        when(scheduler.schedulerLockHeld(anyString())).thenReturn(true);
        when(scheduler.offloadSideJob(any(SideJob.class))).thenReturn(false);   // koşucu yok → gövde satır içi
        when(scheduler.callWithSchedulerLock(eq(AvailabilityRollupRecompute.LOCK), any(Duration.class), any()))
                .thenAnswer(inv -> Optional.ofNullable(((Supplier<?>) inv.getArgument(2)).get()));
    }

    private AvailabilityRollupRecompute job() {
        return new AvailabilityRollupRecompute(jdbc, provider(scheduler), provider(history));
    }

    private long markers() {
        jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Long.class,
                AvailabilityRollupRecompute.KEY);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("İlk güvenli gün: en eski ham günün ertesi (ilk TAM gün) + 1 güvenlik günü; boş / bozuk damga null")
    void firstSafeDayRule() {
        assertThat(AvailabilityRollupRecompute.firstSafeDayOf("2026-11-01T10:00:00"))
                .isEqualTo(Instant.parse("2026-11-03T00:00:00Z"));
        assertThat(AvailabilityRollupRecompute.firstSafeDayOf(null)).isNull();
        assertThat(AvailabilityRollupRecompute.firstSafeDayOf("2026-11")).isNull();
        assertThat(AvailabilityRollupRecompute.firstSafeDayOf("not-a-date-at-all")).isNull();
    }

    @Test
    @DisplayName("Birimler: sekiz tür + Durum İzleme; her özet SQL'i kendi tablosunun sayım süzgecini taşır (gece rollup'ıyla aynı cümle)")
    void unitsCarryTheCountedFilter() {
        List<AvailabilityRollupRecompute.Unit> units = AvailabilityRollupRecompute.units();
        assertThat(units).extracting(AvailabilityRollupRecompute.Unit::type)
                .containsExactly("PORT", "PING", "KEYWORD", "HTTP", "PAGE", "SCRIPTED", "PAGESPEED", "DBQUERY", "UPTIME");
        for (AvailabilityRollupRecompute.Unit u : units) {
            String filter = AvailabilityRows.counted(u.table());
            assertThat(filter).as(u.table()).isNotNull();
            assertThat(u.dailySql()).as(u.type()).contains(filter).contains("monitor_check_daily");
            assertThat(u.hourlySql()).as(u.type()).contains(filter).contains("monitor_check_hourly");
        }
        assertThat(units.get(1).dailySql()).contains("ICMP_UNAVAILABLE");
    }

    @Test
    @DisplayName("PostgreSQL değil (H2): iş yok, nişan yazılır; ikinci tur kilide bile gitmez")
    void notPostgres_writesMarkerOnly() {
        AvailabilityRollupRecompute j = job();
        j.run();
        assertThat(markers()).isEqualTo(1);
        clearInvocations(scheduler);
        j.run();
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any(Duration.class), any());
    }

    @Test
    @DisplayName("Başka sürüm koşan pod var ya da sürümler okunamıyor: tur atlanır (kilit alınmaz, hata sayılmaz)")
    void waitsWhileVersionsMixedOrUnreadable() {
        when(history.liveOtherVersions()).thenReturn(List.of(Map.of("version", "20.225.0")));
        job().run();
        when(history.liveOtherVersions()).thenReturn(null);
        job().run();
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any(Duration.class), any());
        assertThat(markers()).isZero();
    }

    @Test
    @DisplayName("Kilit başka pod'da: sessizce atlanır, hata sayılmaz, nişan yazılmaz")
    void lockElsewhereIsNotAFailure() {
        when(scheduler.callWithSchedulerLock(eq(AvailabilityRollupRecompute.LOCK), any(Duration.class), any()))
                .thenReturn(Optional.empty());
        AvailabilityRollupRecompute j = job();
        for (int i = 0; i < AvailabilityRollupRecompute.MAX_ATTEMPTS + 2; i++) j.run();
        verify(scheduler, times(AvailabilityRollupRecompute.MAX_ATTEMPTS + 2))
                .callWithSchedulerLock(anyString(), any(Duration.class), any());
        assertThat(markers()).isZero();
    }

    @Test
    @DisplayName("Kalıcı hata: pod başına en çok MAX_ATTEMPTS deneme, sonra bu pod bırakır (sonsuz yeniden deneme yok)")
    void attemptCap() {
        when(scheduler.callWithSchedulerLock(eq(AvailabilityRollupRecompute.LOCK), any(Duration.class), any()))
                .thenThrow(new IllegalStateException("db"));
        AvailabilityRollupRecompute j = job();
        for (int i = 0; i < AvailabilityRollupRecompute.MAX_ATTEMPTS + 5; i++) j.run();
        verify(scheduler, times(AvailabilityRollupRecompute.MAX_ATTEMPTS))
                .callWithSchedulerLock(anyString(), any(Duration.class), any());
    }

    /** PostgreSQL gibi davranan, SQL yerine dilim çağrılarını kaydeden iş (döngü / imleç kuralları). */
    private final class Recording extends AvailabilityRollupRecompute {
        final List<String> slices = new ArrayList<>();
        final List<Instant> ends = new ArrayList<>();
        final Map<String, String> oldest;
        Runnable onSlice = () -> {};
        Recording(Map<String, String> oldest) {
            super(jdbc, provider(scheduler), provider(history));
            this.oldest = oldest;
        }
        @Override Boolean postgres() { return Boolean.TRUE; }
        @Override Instant firstSafeDay(String table) { return firstSafeDayOf(oldest.get(table)); }
        @Override long recomputeSlice(Unit unit, Instant s, Instant e, Instant todayStart) {
            slices.add(unit.type() + "@" + s.toString().substring(0, 10));
            ends.add(e);
            onSlice.run();
            return 1;
        }
    }

    @Test
    @DisplayName("Dilim döngüsü: ham satırı olan her tür ilk güvenli günden bugüne haftalık dilimlerle; ham satırı olmayan tür atlanır; bitince nişan")
    void slicesCoverEveryTypeFromFirstSafeDay() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.DAYS);
        String tenDaysAgo = now.minus(10, ChronoUnit.DAYS).toString().substring(0, 19);
        Recording j = new Recording(Map.of("ping_checks", tenDaysAgo, "uptime_checks", tenDaysAgo));
        j.run();
        // ilk güvenli gün = 8 gün önce → [−8, −1) ve [−1, +6) iki dilim; bugünün sonuna dek
        assertThat(j.slices).hasSize(4);
        assertThat(j.slices).allSatisfy(s -> assertThat(s).matches("(PING|UPTIME)@.*"));
        assertThat(j.slices.get(0)).isEqualTo("PING@" + now.minus(8, ChronoUnit.DAYS).toString().substring(0, 10));
        assertThat(markers()).isEqualTo(1);
        assertThat(j.cursor()).isNull();
    }

    @Test
    @DisplayName("inceleme: son dilim CANLI saatlik kovaya girmez — içinde bulunulan saatin başında biter")
    void lastSliceStopsAtTheCurrentHour() {
        Instant now = Instant.parse("2026-11-26T10:37:00Z");
        Recording j = new Recording(Map.of("ping_checks", "2026-11-16T05:00:00"));
        j.clock = now::toEpochMilli;
        j.run();
        Instant hour = Instant.parse("2026-11-26T10:00:00Z");
        assertThat(j.ends).isNotEmpty();
        assertThat(j.ends).allSatisfy(e -> assertThat(e).isBeforeOrEqualTo(hour));
        assertThat(j.ends.get(j.ends.size() - 1)).isEqualTo(hour);
        assertThat(markers()).isEqualTo(1);
    }

    @Test
    @DisplayName("inceleme: silme cümlesi yalnız HÂLÂ ham satırı olan kovaları hedefler (tür, kova, ham aralık sırası)")
    void deleteTargetsOnlyBucketsWithRawRows() {
        AvailabilityRollupRecompute.Unit http = AvailabilityRollupRecompute.units().stream()
                .filter(u -> u.type().equals("HTTP")).findFirst().orElseThrow();
        AvailabilityRollupRecompute.Unit uptime = AvailabilityRollupRecompute.units().stream()
                .filter(u -> u.type().equals("UPTIME")).findFirst().orElseThrow();
        assertThat(AvailabilityRollupRecompute.deleteSql(http, "monitor_check_daily", "day", 10))
                .isEqualTo("DELETE FROM monitor_check_daily WHERE monitor_type = ? AND day >= ? AND day < ? AND (monitor_key, day) "
                        + "IN (SELECT CAST(monitor_id AS varchar), substr(checked_at,1,10) FROM http_checks "
                        + "WHERE checked_at >= ? AND checked_at < ?)");
        assertThat(AvailabilityRollupRecompute.deleteSql(uptime, "monitor_check_hourly", "hour_bucket", 13))
                .contains("(monitor_key, hour_bucket) IN (SELECT domain, substr(checked_at,1,13) FROM uptime_checks");
    }

    @Test
    @DisplayName("Kilit kaybı / kapanış / bütçe: tur imleçle bırakılır (hata sayılmaz, nişan yok); sonraki tur KALDIĞI dilimden sürer")
    void pausesKeepACursorAndResume() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.DAYS);
        String old = now.minus(30, ChronoUnit.DAYS).toString().substring(0, 19);
        Recording j = new Recording(Map.of("port_checks", old, "http_checks", old));
        // 2. dilimden sonra kilit kaybolur
        int[] calls = {0};
        j.onSlice = () -> { if (++calls[0] == 2) when(scheduler.schedulerLockHeld(anyString())).thenReturn(false); };
        j.run();
        assertThat(j.slices).hasSize(2);
        assertThat(markers()).isZero();
        assertThat(j.cursor()).isNotNull();
        assertThat(j.cursor().unit()).isZero();   // PORT

        when(scheduler.schedulerLockHeld(anyString())).thenReturn(true);
        j.onSlice = () -> {};
        List<String> before = List.copyOf(j.slices);
        j.slices.clear();
        j.run();
        assertThat(j.slices.get(0)).as("ilk iki dilim yinelenmez").isNotIn(before);
        assertThat(j.slices.get(0)).startsWith("PORT@");
        assertThat(j.slices).anySatisfy(s -> assertThat(s).startsWith("HTTP@"));
        assertThat(markers()).isEqualTo(1);

        // bütçe: saat bütçeyi aşınca ilk dilimden önce bırakılır
        Recording budget = new Recording(Map.of("port_checks", old));
        jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key = ?", AvailabilityRollupRecompute.KEY);
        long[] t = {System.currentTimeMillis()};   // "bugün" bu saatten hesaplanır — gerçek zamandan başla
        budget.clock = () -> t[0];
        budget.onSlice = () -> t[0] += AvailabilityRollupRecompute.RUN_BUDGET_MS + 1;
        budget.run();
        assertThat(budget.slices).hasSize(1);
        assertThat(markers()).isZero();

        // kapanış: koşucu durduruluyorsa dilim başlamaz
        when(scheduler.sideJobsStopping()).thenReturn(true);
        Recording stopping = new Recording(Map.of("port_checks", old));
        stopping.run();
        assertThat(stopping.slices).isEmpty();
    }
}
