package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.service.oncallroutine.OnCallRoutineService.Perms;
import com.sitemonitor.service.oncallroutine.OnCallRoutineService.TeamCtx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.A;
import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.B;
import static com.sitemonitor.service.oncallroutine.OnCallRoutineTestRig.TEAM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nöbet rutini istatistikleri ve dışa aktarımı (2026-11-26) gerçek bir hafta fikstüründe: gün durum sayıları, satırsız iş
 * günleri, zamanında oranı (geç tamamlama sayılmaz, bugün yalnız tamamlanmışsa payda), görev × durum + ortanca saat, kişi
 * başına sayılar, sorunlar; yüzdeler aşağı yuvarlanır; ortanca alt orta.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OnCallRoutineStatsTest {

    static final LocalDate MON = LocalDate.parse("2026-11-23");
    static final LocalDate TUE = LocalDate.parse("2026-11-24");
    static final LocalDate WED = LocalDate.parse("2026-11-25");
    static final LocalDate THU = LocalDate.parse("2026-11-26");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    OnCallRoutineTestRig rig;
    OnCallRoutineService svc;
    OnCallRoutineStatsService stats;

    @BeforeEach
    void setUp() {
        rig = new OnCallRoutineTestRig(jdbc, txm);
        rig.clean();
        svc = rig.svc;
        stats = new OnCallRoutineStatsService(svc, rig.store);
    }

    @AfterEach
    void tearDown() {
        rig.clean();
    }

    /** Pazartesi A zamanında tamamlar; Salı B bir görev + sorun; Çarşamba B Salı'yı GEÇ tamamlar; Perşembe 09:30 bakılır. */
    TeamCtx week() {
        rig.at(Instant.parse("2026-11-23T07:00:00Z"));   // Pzt 10:00 İstanbul
        rig.saveTemplate(OnCallRoutineTestRig.template());
        TeamCtx c = rig.ctx();
        svc.applyState(c, MON, "d1", "DAY", "2026-11-23", "DONE", null, null, A, true);
        svc.applyState(c, MON, "d2", "DAY", "2026-11-23", "DONE", null, null, A, true);
        svc.completeDay(c, MON, rig.store.day(TEAM, "2026-11-23").version(), null, null, A);

        rig.at(Instant.parse("2026-11-24T07:00:00Z"));   // Sal 10:00
        c = rig.ctx();
        svc.applyState(c, TUE, "d1", "DAY", "2026-11-24", "DONE", null, null, B, true);
        svc.createProblem(c, TUE, "d1", "DAY", "2026-11-24", "HIGH", "Kuyruk birikti", false, B);

        rig.at(Instant.parse("2026-11-25T07:00:00Z"));   // Çar 10:00 — Salı'yı geç tamamlar
        c = rig.ctx();
        svc.applyState(c, TUE, "d2", "DAY", "2026-11-24", "DONE", null, null, B, true);
        svc.completeDay(c, TUE, rig.store.day(TEAM, "2026-11-24").version(), null, null, B);

        rig.at(OnCallRoutineTestRig.THU_0930);
        return rig.ctx();
    }

    @Test
    @DisplayName("yüzde aşağı yuvarlanır (bir ondalık), payda 0 → null; ortanca saat alt orta")
    void helpers() {
        assertThat(OnCallRoutineStatsService.pct(1, 3)).isEqualTo(33.3);
        assertThat(OnCallRoutineStatsService.pct(2, 3)).isEqualTo(66.6);
        assertThat(OnCallRoutineStatsService.pct(3, 3)).isEqualTo(100.0);
        assertThat(OnCallRoutineStatsService.pct(0, 0)).isNull();
        assertThat(OnCallRoutineStatsService.medianTime(List.of(600, 540, 610))).isEqualTo("10:00");
        assertThat(OnCallRoutineStatsService.medianTime(List.of(600, 540))).isEqualTo("09:00");
        assertThat(OnCallRoutineStatsService.medianTime(List.of())).isNull();
        assertThat(OnCallRoutineStatsService.local("2026-11-26T06:30:00").toString()).isEqualTo("2026-11-26T09:30");
        assertThat(OnCallRoutineStatsService.local("bozuk")).isNull();
    }

    @Test
    @DisplayName("hafta fikstürü: gün sayıları, satırsız iş günleri, zamanında oranı, görevler, kişiler, sorunlar")
    @SuppressWarnings("unchecked")
    void weekStats() {
        TeamCtx c = week();
        Map<String, Object> s = stats.stats(c, MON, THU);
        assertThat(s).containsEntry("from", "2026-11-23").containsEntry("to", "2026-11-26").containsEntry("workdays", 4)
                .containsEntry("truncated", false);
        assertThat((Map<String, Object>) s.get("days")).containsEntry("completed", 2).containsEntry("partial", 0)
                .containsEntry("missed", 0).containsEntry("none", 2);
        // payda: Pzt, Sal, Çar (geçmiş iş günleri) — Perşembe tamamlanmadığı için dışarıda; zamanında yalnız Pazartesi
        assertThat(s.get("on_time_pct")).isEqualTo(33.3);

        List<Map<String, Object>> tasks = (List<Map<String, Object>>) s.get("tasks");
        Map<String, Object> d1 = tasks.stream().filter(m -> "d1".equals(m.get("task_id"))).findFirst().orElseThrow();
        assertThat(d1).containsEntry("done", 2).containsEntry("done_pct", 100.0).containsEntry("problems", 1)
                .containsEntry("median_done_time", "10:00").containsEntry("title", "Kuyruklar");
        Map<String, Object> d2 = tasks.stream().filter(m -> "d2".equals(m.get("task_id"))).findFirst().orElseThrow();
        assertThat(d2).containsEntry("done", 2).containsEntry("last_done_by_name", "Kişi B");
        // en düşük oran önce
        Double first = (Double) tasks.get(0).get("done_pct");
        assertThat(first == null ? 101 : first).isLessThanOrEqualTo(100.0);
        for (int i = 1; i < tasks.size(); i++) {
            Double a = (Double) tasks.get(i - 1).get("done_pct");
            Double b = (Double) tasks.get(i).get("done_pct");
            assertThat(a == null ? 101 : a).isLessThanOrEqualTo(b == null ? 101 : b);
        }

        List<Map<String, Object>> people = (List<Map<String, Object>>) s.get("people");
        Map<String, Object> pa = people.stream().filter(m -> Long.valueOf(10L).equals(m.get("user_id"))).findFirst().orElseThrow();
        Map<String, Object> pb = people.stream().filter(m -> Long.valueOf(11L).equals(m.get("user_id"))).findFirst().orElseThrow();
        assertThat(pa).containsEntry("days_completed", 1).containsEntry("days_completed_on_time", 1)
                .containsEntry("items_done", 2).containsEntry("problems_opened", 0).containsEntry("name", "Kişi A");
        assertThat(pb).containsEntry("days_completed", 1).containsEntry("days_completed_on_time", 0)
                .containsEntry("items_done", 2).containsEntry("problems_opened", 1);

        Map<String, Object> pm = (Map<String, Object>) s.get("problems");
        assertThat(pm).containsEntry("opened", 1).containsEntry("resolved", 0).containsEntry("open_now", 1)
                .containsEntry("median_resolve_hours", null);
        assertThat((Map<String, Object>) pm.get("by_severity")).containsEntry("HIGH", 1).containsEntry("LOW", 0);

        // boş aralık: oran null (bilinmiyor), asla 0
        Map<String, Object> empty = stats.stats(c, LocalDate.parse("2026-11-28"), LocalDate.parse("2026-11-29"));
        assertThat(empty).containsEntry("workdays", 0).containsEntry("on_time_pct", null);
    }

    @Test
    @DisplayName("iş günü kuralı değişince zamanında oranı ve gün sayıları AYNI günlerden sayılır; oran %100'ü aşmaz (inceleme #10)")
    @SuppressWarnings("unchecked")
    void onTimeUsesTheSameDays() {
        week();
        rig.at(Instant.parse("2026-11-25T07:30:00Z"));   // Çar 10:30 — Çarşamba zamanında tamamlanır
        TeamCtx c = rig.ctx();
        svc.applyState(c, WED, "d1", "DAY", "2026-11-25", "DONE", null, null, A, true);
        svc.applyState(c, WED, "d2", "DAY", "2026-11-25", "DONE", null, null, A, true);
        svc.completeDay(c, WED, rig.store.day(TEAM, "2026-11-25").version(), null, null, A);
        rig.at(OnCallRoutineTestRig.THU_0930);
        // yalnız Çarşamba iş günü olur: Pazartesi (zamanında) ve Salı (geç) satırları artık iş günü değil
        svc.saveSettings(TEAM, rig.store.team(TEAM).settingsVersion(), Map.of("workdays", List.of(3)), A);
        c = rig.ctx();
        Map<String, Object> s = stats.stats(c, MON, THU);
        assertThat(s).containsEntry("workdays", 1);
        assertThat((Map<String, Object>) s.get("days")).containsEntry("completed", 1).containsEntry("none", 0)
                .containsEntry("partial", 0).containsEntry("open", 0);
        assertThat(s.get("on_time_pct")).as("pay ve payda aynı küme: 1 / 1").isEqualTo(100.0);
        // yalnız Perşembe iş günü: tamamlanmamış bugün paydaya girmez → bilinmiyor (null), Pazartesi sayılmaz
        svc.saveSettings(TEAM, rig.store.team(TEAM).settingsVersion(), Map.of("workdays", List.of(4)), A);
        Map<String, Object> thu = stats.stats(rig.ctx(), MON, THU);
        assertThat(thu).containsEntry("workdays", 1).containsEntry("on_time_pct", null);
        assertThat((Map<String, Object>) thu.get("days")).containsEntry("completed", 0).containsEntry("none", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("hata avı: rutin etkinleşmeden önceki günler sayılmaz; satırı olmayan geçmiş gün zamanında oranını düşürmez")
    void daysBeforeRoutineStartAreNotCounted() {
        rig.at(Instant.parse("2026-11-25T07:00:00Z"));   // rutin Çarşamba 10:00'da kuruldu
        rig.saveTemplate(OnCallRoutineTestRig.template());
        assertThat(rig.store.team(TEAM).enabledSince()).isEqualTo("2026-11-25");
        TeamCtx c = rig.ctx();
        svc.applyState(c, WED, "d1", "DAY", "2026-11-25", "DONE", null, null, A, true);
        svc.applyState(c, WED, "d2", "DAY", "2026-11-25", "DONE", null, null, A, true);
        svc.completeDay(c, WED, rig.store.day(TEAM, "2026-11-25").version(), null, null, A);
        rig.at(OnCallRoutineTestRig.THU_0930);
        Map<String, Object> s = stats.stats(rig.ctx(), MON, THU);
        assertThat(s).as("Pazartesi / Salı rutin yokken: iş günü değil").containsEntry("workdays", 2);
        assertThat((Map<String, Object>) s.get("days")).containsEntry("completed", 1).containsEntry("none", 1);
        assertThat(s.get("on_time_pct")).as("pay ve payda: yalnız Çarşamba").isEqualTo(100.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("hata avı: zorunlu günlük görevi olmayan şablonun satırsız günleri zamanında oranının paydasına girmez (0 değil, null)")
    void dayWithoutRequiredDailyTaskIsNotDue() {
        rig.at(Instant.parse("2026-11-23T07:00:00Z"));
        rig.saveTemplate(Map.of("sections", List.of(Map.of("id", "s2", "title", "Haftalık", "tasks", List.of(
                Map.of("id", "w1", "title", "Yedek deneme", "frequency", "WEEKLY", "weekday", 5))))));
        rig.at(OnCallRoutineTestRig.THU_0930);
        Map<String, Object> s = stats.stats(rig.ctx(), MON, THU);
        assertThat(s).containsEntry("workdays", 4).containsEntry("on_time_pct", null);
        assertThat((Map<String, Object>) s.get("days")).containsEntry("none", 4);
    }

    @Test
    @DisplayName("dışa aktarım: gün listesi, görev satırları (yalnız aralık), sorunlar görselsiz, istatistik, satır sınırı")
    @SuppressWarnings("unchecked")
    void export() {
        TeamCtx c = week();
        Map<String, Object> e = stats.export(c, Perms.readOnly(10L), MON, TUE);
        assertThat(e).containsKeys("days", "items", "problems", "stats", "truncated", "max_rows");
        assertThat(e).containsEntry("max_rows", OnCallRoutineCodes.EXPORT_MAX_ROWS).containsEntry("truncated", false);
        List<Map<String, Object>> items = (List<Map<String, Object>>) e.get("items");
        assertThat(items).isNotEmpty();
        assertThat(items).allSatisfy(m -> assertThat(String.valueOf(m.get("date"))).isIn("2026-11-23", "2026-11-24"));
        assertThat(items).anySatisfy(m -> assertThat(m).containsEntry("state", "DONE").containsEntry("task_title", "Kuyruklar")
                .containsEntry("state_by_name", "Kişi A"));
        List<Map<String, Object>> probs = (List<Map<String, Object>>) e.get("problems");
        assertThat(probs).hasSize(1);
        assertThat(probs.get(0)).doesNotContainKey("images");
    }
}
