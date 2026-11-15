package com.sitemonitor.service.dbquery;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.StandaloneMonitorDeletionBackfill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tatil takvimi 2026–2035 GERÇEK PostgreSQL'de (2026-11-15) — {@code mvn -Ppostgres-it test}. Her test kendi şemasında koşar
 * ({@code currentSchema}): tablo uygulamanın Hibernate'in kurduğu {@code public.holiday_days} tablosundan
 * {@code LIKE … INCLUDING ALL} ile kopyalanır (gerçek kolon türleri + birincil anahtar), paylaşılan bağlamın kendi tohum
 * işleriyle çakışmaz. Kanıtlanan: yıl başına tek deyimli {@code INSERT … SELECT … FROM (VALUES (CAST(? …), …)) AS v(d, n)
 * WHERE NOT EXISTS (… LIKE ?)} PostgreSQL'de çalışır; tohum boş tabloya 171 günü yazar; genişletme yalnız 2026–2027'si olan
 * tabloya tam 2028–2035'i ekler; boş takvime hiçbir şey yazmaz; Türkçe adlar bozulmadan saklanır.
 */
@PostgresIntegration
class HolidayCalendarPostgresTest {

    /** Kendi şeması olan bir bağlantı — tablolar yalnız bu şemada kurulur ve okunur. */
    private static JdbcTemplate isolated() {
        PostgresIt.Config cfg = PostgresIt.config();
        assertThat(cfg).as("IT_DB_URL / IT_DB_USER").isNotNull();
        String schema = "hol_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        JdbcTemplate app = PostgresIt.app().jdbc();
        app.execute("CREATE SCHEMA " + schema);
        app.execute("CREATE TABLE " + schema + ".holiday_days (LIKE public.holiday_days INCLUDING ALL)");
        String url = cfg.url() + (cfg.url().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        JdbcTemplate j = new JdbcTemplate(new DriverManagerDataSource(url, cfg.user(), cfg.password()));
        assertThat(j.queryForObject("SELECT current_schema()", String.class)).isEqualTo(schema);
        return j;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SchedulerService> lockingProvider() {
        SchedulerService s = mock(SchedulerService.class);
        when(s.callWithSchedulerLock(anyString(), any(Supplier.class)))
                .thenAnswer(i -> Optional.ofNullable(((Supplier<Object>) i.getArgument(1)).get()));
        ObjectProvider<SchedulerService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(s);
        return p;
    }

    private static int count(JdbcTemplate j, String sql, Object... args) {
        Integer n = j.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static int yearCount(JdbcTemplate j, int y) {
        return count(j, "SELECT COUNT(*) FROM holiday_days WHERE holiday_date LIKE ?", y + "-%");
    }

    private static void assertYearsEqualDataset(JdbcTemplate j, int from, int to) {
        for (Map.Entry<Integer, List<String[]>> e : HolidayCalendarDataset.byYear().entrySet()) {
            int y = e.getKey();
            if (y < from || y > to) continue;
            assertThat(yearCount(j, y)).as("yıl %d", y).isEqualTo(e.getValue().size());
            for (String[] d : e.getValue()) {
                assertThat(j.queryForObject("SELECT name FROM holiday_days WHERE holiday_date = ?", String.class, d[0]))
                        .as(d[0]).isEqualTo(d[1]);
            }
        }
    }

    @Test
    @DisplayName("PostgreSQL: tohum boş tabloya 2026–2035'i (171 gün) yıl başına tek deyimle yazar; adlar ve künye birebir")
    void seedWritesTenYearsOnEmptyTable() {
        JdbcTemplate j = isolated();
        new HolidayCalendarSeed(j, lockingProvider(), mock(HolidayCalendarService.class)).run();

        assertThat(count(j, "SELECT COUNT(*) FROM holiday_days")).isEqualTo(171).isEqualTo(HolidayCalendarDataset.DAYS.size());
        assertYearsEqualDataset(j, 2026, 2035);
        assertThat(j.queryForObject("SELECT name FROM holiday_days WHERE holiday_date = '2029-04-23'", String.class))
                .isEqualTo("Ulusal Egemenlik ve Çocuk Bayramı · Kurban Bayramı arifesi — arife (yarım gün)");
        assertThat(count(j, "SELECT COUNT(*) FROM holiday_days WHERE created_by_name = 'SiteMonitor' AND created_at LIKE '20__-__-__T__:__:__'"))
                .isEqualTo(171);
        assertThat(count(j, "SELECT rows_affected FROM schema_patch_markers WHERE patch_key = ?", HolidayCalendarSeed.KEY))
                .isEqualTo(171);
        assertThat(j.queryForObject("SELECT MAX(holiday_date) FROM holiday_days", String.class)).isEqualTo("2035-12-03");
    }

    @Test
    @DisplayName("PostgreSQL: yalnız 2026–2027'si olan tablo → genişletme tam 2028–2035'i ekler (138 gün); 2026–2027'ye dokunmaz")
    void fillAddsOnlyMissingYears() {
        JdbcTemplate j = isolated();
        for (String[] d : HolidayCalendarDatasetTest.FIRST_SEED_ORACLE) {
            j.update("INSERT INTO holiday_days (holiday_date, name, created_at, created_by_name) VALUES (?, ?, ?, ?)",
                    d[0], d[1], "2026-10-31T12:00:00", "SiteMonitor");
        }
        j.update("UPDATE holiday_days SET name = 'Bizim adımız' WHERE holiday_date = '2027-01-01'");
        j.update("DELETE FROM holiday_days WHERE holiday_date = '2026-07-15'");
        j.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        j.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                HolidayCalendarSeed.KEY, "2026-10-31T12:00:00", 33);

        new HolidayCalendarTenYearFill(j, lockingProvider(), mock(HolidayCalendarService.class)).run();

        assertThat(yearCount(j, 2026)).isEqualTo(16);
        assertThat(yearCount(j, 2027)).isEqualTo(16);
        assertThat(j.queryForObject("SELECT name FROM holiday_days WHERE holiday_date = '2027-01-01'", String.class))
                .isEqualTo("Bizim adımız");
        assertThat(count(j, "SELECT COUNT(*) FROM holiday_days WHERE holiday_date = '2026-07-15'")).isZero();
        assertYearsEqualDataset(j, 2028, 2035);
        assertThat(count(j, "SELECT COUNT(*) FROM holiday_days")).isEqualTo(32 + 138);
        assertThat(count(j, "SELECT rows_affected FROM schema_patch_markers WHERE patch_key = ?", HolidayCalendarTenYearFill.KEY))
                .isEqualTo(138);
        // Satırı olan yıl için tek deyim hiçbir şey yazmaz
        assertThat(HolidayCalendarSeed.insertYearIfEmpty(j, 2030, HolidayCalendarDataset.byYear().get(2030), "2026-11-15T09:00:00"))
                .isZero();
    }

    @Test
    @DisplayName("PostgreSQL: tamamen boş takvim (tohum nişanı var) → genişletme hiçbir gün yazmaz, nişanı 0 ile yazar")
    void fillLeavesDeliberatelyEmptyTable() {
        JdbcTemplate j = isolated();
        j.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        j.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                HolidayCalendarSeed.KEY, "2026-10-31T12:00:00", 33);
        new HolidayCalendarTenYearFill(j, lockingProvider(), mock(HolidayCalendarService.class)).run();
        assertThat(count(j, "SELECT COUNT(*) FROM holiday_days")).isZero();
        assertThat(count(j, "SELECT rows_affected FROM schema_patch_markers WHERE patch_key = ?", HolidayCalendarTenYearFill.KEY))
                .isZero();
    }
}
