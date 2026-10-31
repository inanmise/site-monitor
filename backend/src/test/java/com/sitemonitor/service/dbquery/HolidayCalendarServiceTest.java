package com.sitemonitor.service.dbquery;

import com.sitemonitor.controller.DbQueryApiException;
import com.sitemonitor.model.HolidayDay;
import com.sitemonitor.repository.HolidayDayRepository;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.cache.CacheBus;
import com.sitemonitor.service.cache.CacheBusNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Kurum tatil takvimi (2026-10-31, veritabanı sorgu izlemesi "tatillerde çalışma"): doğrulama (biçim, tekillik, sınır),
 * tam değiştirme sayıları, 60 sn özet + yarış koruması (okuma sürerken boşaltma gelirse özet taze sayılmaz), öteki pod'lara
 * önbellek veri yolu ile yayın ve tek seferlik tohum (boş tabloya; doluysa dokunulmaz; nişan yalnız başarıda).
 */
class HolidayCalendarServiceTest {

    private static HolidayDay day(String d, String n) {
        HolidayDay h = new HolidayDay();
        h.setDay(d);
        h.setName(n);
        return h;
    }

    @Test
    @DisplayName("parse: geçerli liste; geçersiz tarih / yinelenen gün / boş ad satır anahtarıyla; liste değilse 'days'")
    void parseRules() {
        List<HolidayCalendarService.Day> ok = HolidayCalendarService.parse(List.of(
                Map.of("day", "2026-10-29", "name", "Cumhuriyet Bayramı"),
                Map.of("day", " 2027-01-01 ", "name", " Yılbaşı ")));
        assertThat(ok).containsExactly(new HolidayCalendarService.Day("2026-10-29", "Cumhuriyet Bayramı"),
                new HolidayCalendarService.Day("2027-01-01", "Yılbaşı"));

        assertThatThrownBy(() -> HolidayCalendarService.parse(List.of(
                Map.of("day", "2026-02-30", "name", "Yok"),
                Map.of("day", "2026-05-01", "name", "Emek"),
                Map.of("day", "2026-05-01", "name", "Emek tekrar"),
                Map.of("day", "2026-07-15", "name", ""),
                Map.of("day", "1999-01-01", "name", "Eski"))))
                .isInstanceOfSatisfying(DbQueryApiException.class, e -> {
                    Map<String, Object> body = e.toResponse().getBody();
                    assertThat(body).containsEntry("code", "VALIDATION_FAILED");
                    @SuppressWarnings("unchecked") Map<String, String> fields = (Map<String, String>) body.get("fields");
                    assertThat(fields).containsKeys("days[0].day", "days[2].day", "days[3].name", "days[4].day")
                            .doesNotContainKey("days[1].day");
                });

        assertThatThrownBy(() -> HolidayCalendarService.parse("2026-10-29"))
                .isInstanceOfSatisfying(DbQueryApiException.class,
                        e -> assertThat(String.valueOf(e.toResponse().getBody().get("fields"))).contains("days="));
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i <= HolidayCalendarService.MAX_DAYS; i++) tooMany.add(Map.of("day", "2026-01-01", "name", "x"));
        assertThatThrownBy(() -> HolidayCalendarService.parse(tooMany)).isInstanceOf(DbQueryApiException.class);
    }

    @Test
    @DisplayName("replaceAll: eklenen / silinen / adı değişen sayılır; değişmeyen günün künyesi korunur; değişiklik yoksa yayın yok")
    void replaceAllCountsAndPublishes() {
        HolidayDayRepository repo = mock(HolidayDayRepository.class);
        CacheBus bus = mock(CacheBus.class);
        HolidayCalendarService svc = new HolidayCalendarService(repo);
        svc.setCacheBus(bus);
        verify(bus).register(eq(CacheBusNames.HOLIDAYS), any());
        HolidayDay kept = day("2026-01-01", "Yılbaşı");
        kept.setCreatedByName("Eski Yönetici");
        when(repo.findAll()).thenReturn(List.of(kept, day("2026-05-01", "Emek"), day("2026-07-15", "15 Temmuz")));

        HolidayCalendarService.Change c = svc.replaceAll(List.of(
                new HolidayCalendarService.Day("2026-01-01", "Yılbaşı"),
                new HolidayCalendarService.Day("2026-07-15", "Demokrasi ve Milli Birlik Günü"),
                new HolidayCalendarService.Day("2026-10-29", "Cumhuriyet Bayramı")), "Global Yönetici");
        assertThat(c.added()).isEqualTo(1);
        assertThat(c.removed()).isEqualTo(1);
        assertThat(c.renamed()).isEqualTo(1);
        assertThat(c.total()).isEqualTo(3);
        assertThat(kept.getCreatedByName()).isEqualTo("Eski Yönetici");
        verify(repo).deleteAll(argThat((Iterable<HolidayDay> it) -> it.iterator().next().getDay().equals("2026-05-01")));
        verify(bus).bump(CacheBusNames.HOLIDAYS);

        clearInvocations(bus, repo);
        when(repo.findAll()).thenReturn(List.of(kept));
        assertThat(svc.replaceAll(List.of(new HolidayCalendarService.Day("2026-01-01", "Yılbaşı")), "x").changed()).isFalse();
        verify(bus, never()).bump(any());
        verify(repo, never()).saveAll(any());
    }

    @Test
    @DisplayName("days(): 60 sn özet; bozuk satır atlanır; okuma hatası önceki özeti korur; invalidate yeniden okutur")
    void daysCacheAndInvalidate() {
        HolidayDayRepository repo = mock(HolidayDayRepository.class);
        HolidayCalendarService svc = new HolidayCalendarService(repo);
        when(repo.findAll()).thenReturn(List.of(day("2026-10-29", "Cumhuriyet"), day("bozuk", "x")));
        assertThat(svc.days()).containsExactly(LocalDate.of(2026, 10, 29));
        assertThat(svc.days()).hasSize(1);
        verify(repo, times(1)).findAll();   // özet

        svc.invalidateLocal();
        when(repo.findAll()).thenThrow(new RuntimeException("db yok"));
        assertThat(svc.days()).containsExactly(LocalDate.of(2026, 10, 29));   // okunamadı → önceki özet (boş DEĞİL)
    }

    /** Tek seferlik tohum — gerçek SQL, H2 (PostgreSQL kipi). */
    @Nested
    class Seed {

        private JdbcTemplate jdbc(String name) {
            JdbcTemplate j = new JdbcTemplate(new DriverManagerDataSource(
                    "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", ""));
            j.execute("DROP TABLE IF EXISTS holiday_days");
            j.execute("DROP TABLE IF EXISTS schema_patch_markers");
            j.execute("CREATE TABLE holiday_days (holiday_date VARCHAR(10) PRIMARY KEY, name VARCHAR(120) NOT NULL, "
                    + "created_at VARCHAR(30), created_by_name VARCHAR(200))");
            return j;
        }

        @SuppressWarnings("unchecked")
        private HolidayCalendarSeed seed(JdbcTemplate j, HolidayCalendarService cal) {
            SchedulerService s = mock(SchedulerService.class);
            when(s.callWithSchedulerLock(eq(HolidayCalendarSeed.LOCK), any(Supplier.class)))
                    .thenAnswer(i -> Optional.ofNullable(((Supplier<Object>) i.getArgument(1)).get()));
            ObjectProvider<SchedulerService> p = mock(ObjectProvider.class);
            when(p.getIfAvailable()).thenReturn(s);
            return new HolidayCalendarSeed(j, p, cal);
        }

        @Test
        @DisplayName("boş tablo: 2026–2027 günleri yazılır (arife dahil), nişan yazılır, takvim bayatlatılır; ikinci tur hiçbir şey yazmaz")
        void seedsEmptyTableOnce() {
            JdbcTemplate j = jdbc("holidayseed1");
            HolidayCalendarService cal = mock(HolidayCalendarService.class);
            HolidayCalendarSeed seed = seed(j, cal);
            seed.run();
            assertThat(j.queryForObject("SELECT COUNT(*) FROM holiday_days", Integer.class)).isEqualTo(HolidayCalendarSeed.DAYS.size());
            assertThat(j.queryForObject("SELECT name FROM holiday_days WHERE holiday_date = '2026-10-28'", String.class)).contains("arife");
            assertThat(j.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class,
                    HolidayCalendarSeed.KEY)).isEqualTo(1);
            verify(cal).invalidate();

            // Yönetici bir günü sildi — yeni pod / ikinci tur tohumu YENİDEN yazmaz (nişan)
            j.update("DELETE FROM holiday_days WHERE holiday_date = '2026-01-01'");
            seed(j, cal).run();
            assertThat(j.queryForObject("SELECT COUNT(*) FROM holiday_days WHERE holiday_date = '2026-01-01'", Integer.class)).isZero();
        }

        @Test
        @DisplayName("dolu tablo: yöneticinin takvimine dokunulmaz, yalnız nişan yazılır")
        void filledTableIsUntouched() {
            JdbcTemplate j = jdbc("holidayseed2");
            j.update("INSERT INTO holiday_days (holiday_date, name) VALUES ('2026-12-31', 'Şirket günü')");
            HolidayCalendarService cal = mock(HolidayCalendarService.class);
            seed(j, cal).run();
            assertThat(j.queryForObject("SELECT COUNT(*) FROM holiday_days", Integer.class)).isEqualTo(1);
            assertThat(j.queryForObject("SELECT rows_affected FROM schema_patch_markers WHERE patch_key = ?", Integer.class,
                    HolidayCalendarSeed.KEY)).isZero();
            verify(cal, never()).invalidate();
        }

        @Test
        @DisplayName("tohum günleri: geçerli, tekil, 2026–2027, arife adlı; ayrıştırıcı kurallarından geçer")
        void seedDaysAreValid() {
            List<Map<String, Object>> raw = new ArrayList<>();
            for (String[] d : HolidayCalendarSeed.DAYS) raw.add(Map.of("day", d[0], "name", d[1]));
            List<HolidayCalendarService.Day> parsed = HolidayCalendarService.parse(raw);
            assertThat(parsed).hasSize(HolidayCalendarSeed.DAYS.size());
            assertThat(parsed).allSatisfy(d -> assertThat(d.day()).matches("202[67]-\\d\\d-\\d\\d"));
            assertThat(parsed.stream().filter(d -> d.name().contains("arife")).count()).isEqualTo(6);
        }
    }
}
