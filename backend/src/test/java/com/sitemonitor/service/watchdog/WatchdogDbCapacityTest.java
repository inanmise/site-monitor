package com.sitemonitor.service.watchdog;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.dbgrowth.DbGrowthService;
import com.sitemonitor.service.dbgrowth.DbGrowthService.CapacityFacts;
import com.sitemonitor.service.watchdog.WatchdogTransition.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Kendi kendini izleme "Veritabanı doluluğu" (2026-11-13): kapasite tanımsız / PostgreSQL değil → NA; kullanım ≥ %85 ya da
 * (≥ 7 günlük geçmişle) ≤ 30 gün içinde dolacak → PROBLEM (orta; %95 / 7 gün → yüksek); geçmiş kısa → yalnız kullanım;
 * okunamadı → UNKNOWN. Katalogda SON sırada.
 */
class WatchdogDbCapacityTest {

    static final long GB = 1L << 30;

    private WatchdogProbes probes(DbGrowthService g) {
        WatchdogProbes p = new WatchdogProbes(mock(JdbcTemplate.class), mock(AppSettingsService.class));
        p.setDbGrowth(g);
        return p;
    }

    private DbGrowthService growth(int capGb, CapacityFacts f) {
        DbGrowthService g = mock(DbGrowthService.class);
        when(g.capacityGb()).thenReturn(capGb);
        when(g.capacityFacts()).thenReturn(f);
        return g;
    }

    private static CapacityFacts facts(double pct, Integer days, int history) {
        long cap = 100 * GB;
        return new CapacityFacts(true, cap, Math.round(cap * pct / 100), pct, days, days != null, history, "2026-11-13");
    }

    private WatchdogProbes.Probe run(DbGrowthService g) {
        return probes(g).run(WatchdogCheck.DB_CAPACITY, Instant.parse("2026-11-13T10:00:00Z"));
    }

    @Test
    @DisplayName("katalog: anahtar db_capacity, varsayılan orta önem, iki dilde ad; katalogda SON; 9 kontrol")
    void catalog() {
        assertThat(WatchdogCheck.byKey("db_capacity")).isEqualTo(WatchdogCheck.DB_CAPACITY);
        assertThat(WatchdogCheck.DB_CAPACITY.severity()).isEqualTo(WatchdogCheck.Severity.MEDIUM);
        assertThat(WatchdogCheck.DB_CAPACITY.labelTr()).isEqualTo("Veritabanı doluluğu");
        assertThat(WatchdogCheck.DB_CAPACITY.labelEn()).isEqualTo("Database capacity");
        WatchdogCheck[] all = WatchdogCheck.values();
        assertThat(all[all.length - 1]).isEqualTo(WatchdogCheck.DB_CAPACITY);
        assertThat(all).hasSize(9);
    }

    @Test
    @DisplayName("NA: servis yok, kapasite tanımsız (sorgu yok), PostgreSQL değil")
    void notApplicable() {
        assertThat(run(null).result()).isEqualTo(Result.NA);
        DbGrowthService noCap = growth(0, null);
        WatchdogProbes.Probe p = run(noCap);
        assertThat(p.result()).isEqualTo(Result.NA);
        assertThat(p.facts().get("reason")).isEqualTo("capacity_unknown");
        org.mockito.Mockito.verify(noCap, org.mockito.Mockito.never()).capacityFacts();
        WatchdogProbes.Probe pg = run(growth(100, new CapacityFacts(false, 100 * GB, null, null, null, false, 0, null)));
        assertThat(pg.result()).isEqualTo(Result.NA);
        assertThat(pg.facts().get("reason")).isEqualTo("not_postgres");
    }

    @Test
    @DisplayName("OK: %60 kullanım, 200 gün; PROBLEM: %86 (orta), 20 gün (orta), %96 (yüksek), 5 gün (yüksek)")
    void thresholds() {
        assertThat(run(growth(100, facts(60, 200, 14))).result()).isEqualTo(Result.OK);
        WatchdogProbes.Probe pct = run(growth(100, facts(86, null, 14)));
        assertThat(pct.result()).isEqualTo(Result.PROBLEM);
        assertThat(pct.severity()).isEqualTo("MEDIUM");
        WatchdogProbes.Probe days = run(growth(100, facts(50, 20, 14)));
        assertThat(days.result()).isEqualTo(Result.PROBLEM);
        assertThat(days.facts().get("days_until_full")).isEqualTo(20);
        assertThat(run(growth(100, facts(96, null, 14))).severity()).isEqualTo("HIGH");
        assertThat(run(growth(100, facts(50, 5, 14))).severity()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("geçmiş < 7 gün → tahmin yok sayılır, yalnız kullanım karar verir")
    void shortHistoryUsageOnly() {
        WatchdogProbes.Probe p = run(growth(100, facts(50, 5, 3)));
        assertThat(p.result()).isEqualTo(Result.OK);
        assertThat(p.facts().get("forecast")).isEqualTo(false);
        assertThat(p.facts()).doesNotContainKey("days_until_full");
        assertThat(run(growth(100, facts(90, null, 3))).result()).isEqualTo(Result.PROBLEM);
    }

    @Test
    @DisplayName("okunamadı → UNKNOWN (istisna ya da kullanım bilinmiyor) — sayaçlar değişmez")
    void unknown() {
        DbGrowthService g = mock(DbGrowthService.class);
        when(g.capacityGb()).thenReturn(100);
        when(g.capacityFacts()).thenThrow(new RuntimeException("db down"));
        assertThat(run(g).result()).isEqualTo(Result.UNKNOWN);
        assertThat(run(growth(100, new CapacityFacts(true, 100 * GB, null, null, null, false, 0, null))).result())
                .isEqualTo(Result.UNKNOWN);
    }

    @Test
    @DisplayName("ayrıntı cümlesi: kullanım / kapasite, dolma günü, eşikler; NA nedenleri iki dilde")
    void messages() {
        WatchdogProbes.Probe p = run(growth(100, facts(86, 20, 14)));
        String tr = WatchdogMessages.detail(WatchdogCheck.DB_CAPACITY, p.facts(), false);
        String en = WatchdogMessages.detail(WatchdogCheck.DB_CAPACITY, p.facts(), true);
        assertThat(tr).contains("100,0 GB").contains("%86,0").contains("20 gün");
        assertThat(en).contains("100.0 GB").contains("86.0%").contains("20 day");
        WatchdogProbes.Probe s = run(growth(100, facts(50, null, 2)));
        assertThat(WatchdogMessages.detail(WatchdogCheck.DB_CAPACITY, s.facts(), false)).contains("yeterli günlük görüntü yok");
        assertThat(WatchdogMessages.detail(WatchdogCheck.DB_CAPACITY, java.util.Map.of("reason", "capacity_unknown"), false))
                .contains("kapasitesi tanımlı değil");
        assertThat(WatchdogMessages.detail(WatchdogCheck.DB_CAPACITY, java.util.Map.of("reason", "not_postgres"), true))
                .contains("PostgreSQL only");
    }
}
