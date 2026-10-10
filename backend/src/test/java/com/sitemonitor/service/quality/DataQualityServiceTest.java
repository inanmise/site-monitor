package com.sitemonitor.service.quality;

import com.sitemonitor.service.noc.NocType;
import com.sitemonitor.service.quality.DataQualityService.TrendPoint;
import com.sitemonitor.service.quality.DataQualityService.Viewer;
import com.sitemonitor.service.quality.DataQualitySource.TeamFact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Bellek, görüş kapsamı, satır bayrakları, yeniden kullanılabilir özet. */
@SuppressWarnings("unchecked")
class DataQualityServiceTest {

    /** İleri sarılabilen saat. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-10T09:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advanceMs(long ms) { now = now.plusMillis(ms); }
    }

    DataQualitySource source;
    JdbcTemplate jdbc;
    DataQualityService service;
    MutableClock clock;
    AtomicInteger loads;

    @BeforeEach
    void setUp() {
        source = mock(DataQualitySource.class);
        jdbc = mock(JdbcTemplate.class);
        clock = new MutableClock();
        loads = new AtomicInteger();
        DataQualityFixtures fx = new DataQualityFixtures().team(1, "Ödeme").team(2, "Altyapı").team(3, "Kanal");
        fx.inv(10, "pay.example.com", 1L).setTier(null);
        fx.inv(11, "core.example.com", 2L);
        fx.inv(12, "orphan.example.com", null);
        fx.monitor(NocType.PING, 5, "p", "h", 3L, true, null, "h");
        when(source.load(any())).thenAnswer(inv -> { loads.incrementAndGet(); return fx.facts(); });
        service = new DataQualityService(source, jdbc, 60_000);
        service.clock = clock;
    }

    private static Viewer viewer(boolean seesAll, boolean globalAdmin, Set<Long> visible, Set<Long> writable) {
        return new Viewer(seesAll, globalAdmin, visible::contains, writable::contains, writable::contains, writable::contains);
    }

    @Test
    @DisplayName("bellek: 60 sn içinde tek hesap; süre dolunca yeniden; fresh en çok 5 sn'de bir atlar")
    void memo() {
        Viewer v = viewer(true, true, Set.of(1L, 2L, 3L), Set.of());
        service.summary(v, false);
        service.summary(v, false);
        service.teamDetail(v, "1", false);
        assertThat(loads).hasValue(1);
        service.summary(v, true);                 // 0 ms sonra fresh → bellek (≤ 5 sn)
        assertThat(loads).hasValue(1);
        clock.advanceMs(5_001);
        service.summary(v, true);                 // fresh, 5 sn geçti → yeniden
        assertThat(loads).hasValue(2);
        clock.advanceMs(59_000);
        service.summary(v, false);
        assertThat(loads).hasValue(2);
        clock.advanceMs(1_001);
        service.summary(v, false);
        assertThat(loads).hasValue(3);
    }

    @Test
    @DisplayName("eşzamanlı istekler tek hesabı bekler (tek yükleme)")
    void singleFlight() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        DataQualityFixtures fx = new DataQualityFixtures().team(1, "A");
        when(source.load(any())).thenAnswer(inv -> { loads.incrementAndGet(); gate.await(5, TimeUnit.SECONDS); return fx.facts(); });
        loads.set(0);   // when(…) yukarıda eski cevabı bir kez çağırdı
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 4; i++) pool.submit(() -> service.evaluation(false));
            Thread.sleep(150);
            gate.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("kapsam: takım listesi yalnız görünür takımlar; Sahipsiz yalnız tümünü görene; kurum puanı herkese")
    void scoping() {
        Map<String, Object> scoped = service.summary(viewer(false, false, Set.of(1L), Set.of(1L)), false);
        assertThat((List<Map<String, Object>>) scoped.get("teams")).extracting(t -> t.get("id")).containsExactly(1L);
        assertThat(scoped).doesNotContainKey("unassigned");
        assertThat((Map<String, Object>) scoped.get("org")).containsKey("score");

        Map<String, Object> all = service.summary(viewer(true, false, Set.of(1L, 2L, 3L), Set.of()), false);
        assertThat((List<Map<String, Object>>) all.get("teams")).extracting(t -> t.get("name"))
                .containsExactly("Altyapı", "Kanal", "Ödeme");
        assertThat((Map<String, Object>) all.get("unassigned")).containsEntry("findings", 1);
    }

    @Test
    @DisplayName("ayrıntı: görünmeyen takım / yok takım / Sahipsiz (kapsamlı) → null (404); görünen takım kalemleriyle")
    void detailScoping() {
        Viewer scoped = viewer(false, false, Set.of(1L), Set.of(1L));
        assertThat(service.teamDetail(scoped, "2", false)).isNull();
        assertThat(service.teamDetail(scoped, "unassigned", false)).isNull();
        assertThat(service.teamDetail(scoped, "abc", false)).isNull();
        assertThat(service.teamDetail(viewer(true, true, Set.of(1L, 2L, 3L), Set.of()), "999", false)).isNull();

        Map<String, Object> d = service.teamDetail(scoped, "1", false);
        assertThat(d).containsEntry("unassigned", false);
        List<Map<String, Object>> rules = (List<Map<String, Object>>) d.get("rules");
        Map<String, Object> noTier = rules.stream().filter(r -> "INV_NO_TIER".equals(r.get("code"))).findFirst().orElseThrow();
        assertThat(noTier).containsEntry("failing", 1).containsEntry("truncated", 0);
        Map<String, Object> item = ((List<Map<String, Object>>) noTier.get("items")).get(0);
        assertThat(item).containsEntry("kind", "inventory").containsEntry("type", "SSL").containsEntry("id", 10L)
                .containsEntry("can_edit", true).containsKey("facts").doesNotContainKey("detail");

        Map<String, Object> u = service.teamDetail(viewer(true, false, Set.of(1L, 2L, 3L), Set.of()), "unassigned", false);
        assertThat(u).containsEntry("unassigned", true);
        List<Map<String, Object>> urules = (List<Map<String, Object>>) u.get("rules");
        assertThat(urules).extracting(r -> r.get("code")).contains("INV_NO_TEAM");
        Map<String, Object> orphan = ((List<Map<String, Object>>) urules.stream()
                .filter(r -> "INV_NO_TEAM".equals(r.get("code"))).findFirst().orElseThrow().get("items")).get(0);
        assertThat(orphan).as("sahipsiz kalemi yalnız global admin düzeltir").containsEntry("can_edit", false);
    }

    @Test
    @DisplayName("satır bayrağı kaydın KENDİ yazma kapısı: envanter / izleme / takım ayrı yüklemler; global admin hepsi")
    void canEditFlags() {
        DataQualityEvaluator.Finding inv = new DataQualityEvaluator.Finding(DataQualityRule.INV_NO_TIER, "inventory", "SSL",
                1, "a", "a", 7L, false, Map.of());
        DataQualityEvaluator.Finding mon = new DataQualityEvaluator.Finding(DataQualityRule.MON_NO_GROUP, "monitor", "HTTP",
                2, "b", "b", 7L, false, Map.of());
        DataQualityEvaluator.Finding team = new DataQualityEvaluator.Finding(DataQualityRule.TEAM_NO_MEMBERS, "team", "TEAM",
                7, "T", null, 7L, false, Map.of());
        Viewer onlyMonitor = new Viewer(false, false, id -> true, id -> false, id -> id == 7L, id -> false);
        assertThat(DataQualityService.canEdit(inv, onlyMonitor)).isFalse();
        assertThat(DataQualityService.canEdit(mon, onlyMonitor)).isTrue();
        assertThat(DataQualityService.canEdit(team, onlyMonitor)).isFalse();
        Viewer admin = new Viewer(true, true, id -> true, id -> false, id -> false, id -> false);
        assertThat(DataQualityService.canEdit(team, admin)).isTrue();
        assertThat(DataQualityService.canEdit(inv, null)).isFalse();
    }

    @Test
    @DisplayName("takım satırı: puan, bant, en çok puan kaybettiren ≤ 3 sorun (kayba göre)")
    void teamRowShape() {
        Map<String, Object> all = service.summary(viewer(true, true, Set.of(1L, 2L, 3L), Set.of()), false);
        Map<String, Object> pay = ((List<Map<String, Object>>) all.get("teams")).stream()
                .filter(t -> Long.valueOf(1L).equals(t.get("id"))).findFirst().orElseThrow();
        assertThat(pay).containsKeys("score", "band", "findings", "items", "delta_7d", "trend", "top_issues");
        List<Map<String, Object>> top = (List<Map<String, Object>>) pay.get("top_issues");
        assertThat(top).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        assertThat(top.get(0)).containsKeys("code", "severity", "failing", "eligible", "points");
        assertThat(all).containsKeys("catalog", "config", "notes", "generated_at");
        assertThat((List<?>) all.get("catalog")).hasSize(DataQualityRule.values().length);
    }

    @Test
    @DisplayName("digest: kurum puanı + takımlar kötüden iyiye + en sık 5 sorun (süzülmemiş, yeniden kullanım için)")
    void digest() {
        DataQualityService.Digest d = service.digest();
        assertThat(d.orgScore()).isNotNull();
        assertThat(d.teams()).hasSize(3);
        for (int i = 1; i < d.teams().size(); i++) {
            assertThat(d.teams().get(i - 1).score()).isLessThanOrEqualTo(d.teams().get(i).score());
        }
        assertThat(d.topIssues()).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(d.topIssues()).extracting(DataQualityService.IssueCount::code).contains("INV_NO_TEAM");
    }

    @Test
    @DisplayName("7 günlük fark: tam 7 gün önceki görüntüden; yoksa null")
    void delta() {
        List<TrendPoint> trend = List.of(new TrendPoint("2026-10-02", 70), new TrendPoint("2026-10-03", 74),
                new TrendPoint("2026-10-09", 80));
        assertThat(DataQualityService.delta(81, trend, "2026-10-10")).isEqualTo(7);
        assertThat(DataQualityService.delta(81, trend, "2026-10-12")).isNull();
        assertThat(DataQualityService.delta(null, trend, "2026-10-10")).isNull();
        assertThat(DataQualityService.delta(81, null, "2026-10-10")).isNull();
    }

    @Test
    @DisplayName("sabit sorgu bütçesi: takım sayısı 3 → 30 olsa da kaynak TEK kez yüklenir (takım başına çağrı yok)")
    void singleLoadRegardlessOfTeams() {
        DataQualityFixtures many = new DataQualityFixtures();
        for (long i = 1; i <= 30; i++) many.team(i, "T" + i);
        when(source.load(any())).thenReturn(many.facts());
        DataQualityService s = new DataQualityService(source, jdbc, 60_000);
        s.summary(viewer(true, true, Set.of(), Set.of()), false);
        for (long i = 1; i <= 30; i++) s.teamDetail(new Viewer(true, true, id -> true, id -> true, id -> true, id -> true), String.valueOf(i), false);
        verify(source, times(1)).load(any());
        assertThat(new TeamFact(1, "x", true, true, true, true, false, false).name()).isEqualTo("x");
    }
}
