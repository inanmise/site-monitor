package com.sitemonitor.service.dbquery;

import com.sitemonitor.service.SchedulerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tatil takvimini 10 yıla tamamlayan tek seferlik iş (2026-11-15) — gerçek SQL, H2 (PostgreSQL kipi): yalnız HİÇ günü olmayan
 * yıllar eklenir; yöneticinin düzenlediği / gün sildiği yıla dokunulmaz; ilk tohumun nişanını bekler; nişan kilit içinde
 * yeniden okunur ve yalnız başarıda yazılır; kilit başka pod'daysa hiçbir şey yapılmaz; deneme sınırı.
 */
class HolidayCalendarTenYearFillTest {

    private static final String COUNT_ALL = "SELECT COUNT(*) FROM holiday_days";

    private static JdbcTemplate jdbc(String name) {
        JdbcTemplate j = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", ""));
        j.execute("DROP TABLE IF EXISTS holiday_days");
        j.execute("DROP TABLE IF EXISTS schema_patch_markers");
        j.execute("CREATE TABLE holiday_days (holiday_date VARCHAR(10) PRIMARY KEY, name VARCHAR(120) NOT NULL, "
                + "created_at VARCHAR(30), created_by_name VARCHAR(255))");
        return j;
    }

    /** Kilidi hemen veren zamanlayıcı (tek pod). */
    @SuppressWarnings("unchecked")
    private static SchedulerService lockingScheduler() {
        SchedulerService s = mock(SchedulerService.class);
        when(s.callWithSchedulerLock(anyString(), any(Supplier.class)))
                .thenAnswer(i -> Optional.ofNullable(((Supplier<Object>) i.getArgument(1)).get()));
        return s;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SchedulerService> provider(SchedulerService s) {
        ObjectProvider<SchedulerService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(s);
        return p;
    }

    private static int count(JdbcTemplate j, String sql, Object... args) {
        Integer n = j.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static int yearCount(JdbcTemplate j, int y) {
        return count(j, "SELECT COUNT(*) FROM holiday_days WHERE holiday_date >= ? AND holiday_date <= ?", y + "-01-01", y + "-12-31");
    }

    private static int markers(JdbcTemplate j, String key) {
        j.execute(com.sitemonitor.service.StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        return count(j, "SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", key);
    }

    /** Önceki sürümün tohumunu taklit eder: 2026–2027 satırları + eski nişan. */
    private static void previousReleaseSeeded(JdbcTemplate j) {
        for (String[] d : HolidayCalendarDatasetTest.FIRST_SEED_ORACLE) {
            j.update("INSERT INTO holiday_days (holiday_date, name, created_at, created_by_name) VALUES (?, ?, ?, ?)",
                    d[0], d[1], "2026-10-31T12:00:00", "SiteMonitor");
        }
        j.execute(com.sitemonitor.service.StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        j.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                HolidayCalendarSeed.KEY, "2026-10-31T12:00:00", HolidayCalendarDatasetTest.FIRST_SEED_ORACLE.size());
    }

    private static int datasetRows(int fromYear, int toYear) {
        int n = 0;
        for (var e : HolidayCalendarDataset.byYear().entrySet()) {
            if (e.getKey() >= fromYear && e.getKey() <= toYear) n += e.getValue().size();
        }
        return n;
    }

    @Test
    @DisplayName("yeni kurulum: ilk tohum on yılı yazar; genişletme tohum nişanını bekler, sonra ekleyecek yıl bulmaz, nişanını 0 ile yazar")
    void freshInstall_seedWritesTenYears_fillWaitsThenNoop() {
        JdbcTemplate j = jdbc("hol10y_fresh");
        HolidayCalendarService cal = mock(HolidayCalendarService.class);
        SchedulerService s = lockingScheduler();
        HolidayCalendarTenYearFill fill = new HolidayCalendarTenYearFill(j, provider(s), cal);

        // Tohum nişanı yok → bekler: kilit denenmez, hiçbir şey yazılmaz, deneme sayılmaz
        fill.run();
        assertThat(count(j, COUNT_ALL)).isZero();
        assertThat(markers(j, HolidayCalendarTenYearFill.KEY)).isZero();
        verify(s, never()).callWithSchedulerLock(eq(HolidayCalendarTenYearFill.LOCK), any(Supplier.class));

        new HolidayCalendarSeed(j, provider(s), cal).run();
        assertThat(count(j, COUNT_ALL)).isEqualTo(HolidayCalendarDataset.DAYS.size());
        for (int y = 2026; y <= 2035; y++) assertThat(yearCount(j, y)).as("yıl %d", y).isPositive();
        verify(cal, times(1)).invalidate();
        verify(s, times(1)).dbQueryCalendarChanged();
        verify(s, times(1)).scriptedCalendarChanged();
        verify(s, times(1)).pageSpeedCalendarChanged();

        fill.run();
        assertThat(count(j, COUNT_ALL)).isEqualTo(HolidayCalendarDataset.DAYS.size());
        assertThat(count(j, "SELECT rows_affected FROM schema_patch_markers WHERE patch_key = ?", HolidayCalendarTenYearFill.KEY)).isZero();
        verify(cal, times(1)).invalidate();   // genişletme yazmadı → yeniden bayatlatmaz
        assertThat(count(j, "SELECT COUNT(*) FROM holiday_days WHERE holiday_date = '2033-01-01' AND name LIKE 'Yılbaşı · %'")).isEqualTo(1);
    }

    @Test
    @DisplayName("var olan kurulum: 2026–2027 yöneticinin elinde (bir gün silinmiş, bir gün adı değişmiş) → yalnız 2028–2035 eklenir")
    void existingInstall_onlyMissingYearsAdded() {
        JdbcTemplate j = jdbc("hol10y_existing");
        previousReleaseSeeded(j);
        j.update("DELETE FROM holiday_days WHERE holiday_date = '2027-01-01'");
        j.update("UPDATE holiday_days SET name = 'Bizim Ramazan adımız' WHERE holiday_date = '2026-03-20'");
        int before = count(j, COUNT_ALL);
        HolidayCalendarService cal = mock(HolidayCalendarService.class);
        SchedulerService s = lockingScheduler();
        HolidayCalendarTenYearFill fill = new HolidayCalendarTenYearFill(j, provider(s), cal);

        fill.run();
        int added = datasetRows(2028, 2035);
        assertThat(count(j, COUNT_ALL)).isEqualTo(before + added);
        assertThat(count(j, "SELECT COUNT(*) FROM holiday_days WHERE holiday_date = '2027-01-01'")).as("silinen gün geri gelmez").isZero();
        assertThat(j.queryForObject("SELECT name FROM holiday_days WHERE holiday_date = '2026-03-20'", String.class))
                .isEqualTo("Bizim Ramazan adımız");
        assertThat(j.queryForObject("SELECT name FROM holiday_days WHERE holiday_date = '2029-04-23'", String.class))
                .isEqualTo("Ulusal Egemenlik ve Çocuk Bayramı · Kurban Bayramı arifesi — arife (yarım gün)");
        assertThat(j.queryForObject("SELECT created_by_name FROM holiday_days WHERE holiday_date = '2035-12-03'", String.class))
                .isEqualTo("SiteMonitor");
        assertThat(count(j, "SELECT rows_affected FROM schema_patch_markers WHERE patch_key = ?", HolidayCalendarTenYearFill.KEY))
                .isEqualTo(added);
        verify(cal).invalidate();
        verify(s).dbQueryCalendarChanged();
        verify(s).scriptedCalendarChanged();
        verify(s).pageSpeedCalendarChanged();

        // İkinci tur (aynı pod / yeni pod) hiçbir şey yazmaz — nişan
        j.update("DELETE FROM holiday_days WHERE holiday_date >= '2030-01-01' AND holiday_date <= '2030-12-31'");
        fill.run();
        new HolidayCalendarTenYearFill(j, provider(s), cal).run();
        assertThat(yearCount(j, 2030)).as("nişan yazıldıktan sonra silinen yıl geri gelmez").isZero();
        verify(cal, times(1)).invalidate();
    }

    @Test
    @DisplayName("tek bir günü olan yıl (yöneticinin şirket günü) bütünüyle atlanır; yalnız boş yıllar dolar")
    void yearWithAnyRowIsSkipped() {
        JdbcTemplate j = jdbc("hol10y_partial");
        previousReleaseSeeded(j);
        j.update("INSERT INTO holiday_days (holiday_date, name) VALUES ('2030-06-14', 'Şirket günü')");
        HolidayCalendarTenYearFill fill = new HolidayCalendarTenYearFill(j, provider(lockingScheduler()),
                mock(HolidayCalendarService.class));
        fill.run();
        assertThat(yearCount(j, 2030)).isEqualTo(1);
        assertThat(yearCount(j, 2031)).isEqualTo(HolidayCalendarDataset.byYear().get(2031).size());
        assertThat(yearCount(j, 2027)).isEqualTo(16);
    }

    @Test
    @DisplayName("nişan kilit İÇİNDE yeniden okunur: ön denetimden sonra başka pod yazdıysa hiçbir gün eklenmez")
    @SuppressWarnings("unchecked")
    void markerReadInsideLock() {
        JdbcTemplate j = jdbc("hol10y_race");
        previousReleaseSeeded(j);
        SchedulerService s = mock(SchedulerService.class);
        when(s.callWithSchedulerLock(eq(HolidayCalendarTenYearFill.LOCK), any(Supplier.class))).thenAnswer(i -> {
            // Bu pod kilidi beklerken öteki pod işi bitirdi
            j.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                    HolidayCalendarTenYearFill.KEY, "2026-11-15T09:00:00", 0);
            return Optional.ofNullable(((Supplier<Object>) i.getArgument(1)).get());
        });
        HolidayCalendarService cal = mock(HolidayCalendarService.class);
        HolidayCalendarTenYearFill fill = new HolidayCalendarTenYearFill(j, provider(s), cal);
        fill.run();
        assertThat(count(j, COUNT_ALL)).isEqualTo(HolidayCalendarDatasetTest.FIRST_SEED_ORACLE.size());
        assertThat(markers(j, HolidayCalendarTenYearFill.KEY)).isEqualTo(1);
        assertThat((Boolean) ReflectionTestUtils.getField(fill, "doneSeen")).isTrue();
        verify(cal, never()).invalidate();
    }

    @Test
    @DisplayName("kilit başka pod'da: hiçbir şey yazılmaz, nişan yok, deneme sayılmaz; sonraki turda kilit alınca eklenir")
    @SuppressWarnings("unchecked")
    void lockHeldElsewhere() {
        JdbcTemplate j = jdbc("hol10y_lock");
        previousReleaseSeeded(j);
        SchedulerService s = mock(SchedulerService.class);
        when(s.callWithSchedulerLock(eq(HolidayCalendarTenYearFill.LOCK), any(Supplier.class)))
                .thenReturn(Optional.empty())
                .thenAnswer(i -> Optional.ofNullable(((Supplier<Object>) i.getArgument(1)).get()));
        HolidayCalendarTenYearFill fill = new HolidayCalendarTenYearFill(j, provider(s), mock(HolidayCalendarService.class));
        fill.run();
        assertThat(count(j, COUNT_ALL)).isEqualTo(HolidayCalendarDatasetTest.FIRST_SEED_ORACLE.size());
        assertThat(markers(j, HolidayCalendarTenYearFill.KEY)).isZero();
        assertThat(((AtomicInteger) ReflectionTestUtils.getField(fill, "failures")).get()).isZero();
        fill.run();
        assertThat(yearCount(j, 2035)).isPositive();
        assertThat(markers(j, HolidayCalendarTenYearFill.KEY)).isEqualTo(1);
    }

    @Test
    @DisplayName("tohum nişanı kilit içinde yoksa bekler (WAITING): ne gün ne nişan, deneme sayılmaz")
    void waitsForSeedMarkerInsideLock() {
        JdbcTemplate j = jdbc("hol10y_wait");
        HolidayCalendarTenYearFill fill = new HolidayCalendarTenYearFill(j, provider(lockingScheduler()),
                mock(HolidayCalendarService.class));
        assertThat(fill.fillLocked()).isEqualTo(HolidayCalendarTenYearFill.WAITING);
        assertThat(count(j, COUNT_ALL)).isZero();
        assertThat(markers(j, HolidayCalendarTenYearFill.KEY)).isZero();
    }

    @Test
    @DisplayName("okunamayan nişan 'bitti' sayılmaz: ön denetimde tur atlanır; kilit içinde istisna (deneme), hiçbir şey yazılmaz")
    void unreadableMarker() {
        JdbcTemplate broken = mock(JdbcTemplate.class);
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("veritabanı yok")).when(broken).execute(anyString());
        SchedulerService s = lockingScheduler();
        HolidayCalendarTenYearFill fill = new HolidayCalendarTenYearFill(broken, provider(s), mock(HolidayCalendarService.class));
        fill.run();
        assertThat((Boolean) ReflectionTestUtils.getField(fill, "doneSeen")).isFalse();
        verify(s, never()).callWithSchedulerLock(anyString(), any(Supplier.class));
        assertThatThrownBy(fill::fillLocked).isInstanceOf(IllegalStateException.class);
        verify(broken, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("deneme sınırı: kilit içindeki hata her turda sayılır; 24 hatadan sonra bu pod denemeyi bırakır")
    @SuppressWarnings("unchecked")
    void attemptCap() {
        JdbcTemplate j = jdbc("hol10y_cap");
        previousReleaseSeeded(j);
        SchedulerService s = mock(SchedulerService.class);
        when(s.callWithSchedulerLock(eq(HolidayCalendarTenYearFill.LOCK), any(Supplier.class)))
                .thenThrow(new IllegalStateException("kilit içinde hata"));
        HolidayCalendarTenYearFill fill = new HolidayCalendarTenYearFill(j, provider(s), mock(HolidayCalendarService.class));
        for (int i = 0; i < 30; i++) fill.run();
        verify(s, times(HolidayCalendarTenYearFill.MAX_ATTEMPTS)).callWithSchedulerLock(eq(HolidayCalendarTenYearFill.LOCK), any(Supplier.class));
        assertThat(markers(j, HolidayCalendarTenYearFill.KEY)).isZero();
    }

    @Test
    @DisplayName("yıl tek deyimle yazılır: yılın bir satırı bile varsa 0; boş yıl tam yazılır; yarıda kalan ilk tohumun eksik yılları tamamlanır")
    void yearIsWrittenAtomically() {
        JdbcTemplate j = jdbc("hol10y_atomic");
        List<String[]> y2031 = HolidayCalendarDataset.byYear().get(2031);
        j.update("INSERT INTO holiday_days (holiday_date, name) VALUES ('2031-11-11', 'Yöneticinin günü')");
        assertThat(HolidayCalendarSeed.insertYearIfEmpty(j, 2031, y2031, "2026-11-15T09:00:00")).isZero();
        assertThat(yearCount(j, 2031)).isEqualTo(1);
        assertThat(HolidayCalendarSeed.insertYearIfEmpty(j, 2032, HolidayCalendarDataset.byYear().get(2032), "2026-11-15T09:00:00"))
                .isEqualTo(17);
        assertThat(j.queryForObject("SELECT created_by_name FROM holiday_days WHERE holiday_date = '2032-10-29'", String.class))
                .isEqualTo("SiteMonitor");
        assertThat(HolidayCalendarSeed.insertYearIfEmpty(j, 2033, List.of(), "x")).isZero();

        // İlk tohum 2026–2028'i yazıp düştü, sonraki turda tablo dolu diye nişanı yazdı → genişletme kalan yılları tamamlar
        JdbcTemplate k = jdbc("hol10y_resume");
        for (int y = 2026; y <= 2028; y++) HolidayCalendarSeed.insertYearIfEmpty(k, y, HolidayCalendarDataset.byYear().get(y), "t");
        k.execute(com.sitemonitor.service.StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        k.update("INSERT INTO schema_patch_markers (patch_key, applied_at, rows_affected) VALUES (?, ?, ?)",
                HolidayCalendarSeed.KEY, "2026-11-15T09:00:00", 0);
        new HolidayCalendarTenYearFill(k, provider(lockingScheduler()), mock(HolidayCalendarService.class)).run();
        assertThat(count(k, COUNT_ALL)).isEqualTo(HolidayCalendarDataset.DAYS.size());
    }

    @Test
    @DisplayName("takvim 500 gün sınırını aşmaz: sığmayan yıllar atlanır (yönetici ekranı listeyi kaydedebilsin), nişan yazılır")
    void respectsMaxDays() {
        JdbcTemplate j = jdbc("hol10y_max");
        previousReleaseSeeded(j);
        // 2040–2041'de 450 özel gün → 33 + 450 = 483; yalnız ilk sığan yıl (2028, 17 gün = 500) eklenebilir
        java.time.LocalDate d = java.time.LocalDate.of(2040, 1, 1);
        for (int i = 0; i < 450; i++) {
            j.update("INSERT INTO holiday_days (holiday_date, name) VALUES (?, ?)", d.plusDays(i).toString(), "Özel " + i);
        }
        HolidayCalendarTenYearFill fill = new HolidayCalendarTenYearFill(j, provider(lockingScheduler()),
                mock(HolidayCalendarService.class));
        fill.run();
        assertThat(count(j, COUNT_ALL)).isLessThanOrEqualTo(HolidayCalendarService.MAX_DAYS);
        assertThat(yearCount(j, 2028)).isEqualTo(17);
        for (int y : List.of(2029, 2030, 2031, 2032, 2033, 2034, 2035)) assertThat(yearCount(j, y)).as("yıl %d", y).isZero();
        assertThat(markers(j, HolidayCalendarTenYearFill.KEY)).isEqualTo(1);
    }
}
