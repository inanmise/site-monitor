package com.sitemonitor.repository;

import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.service.ScriptedSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * k6r D2 (2026-10-31): sentetik zamanlama sorguları gerçek JPQL / SQL ile (H2) — {@code NOT_SUPPORTED}: işlem gerektiren
 * {@code @Modifying} sorgunun kendi işlemiyle koştuğu görülsün (CLAUDE.md "derived deleteBy… without @Transactional").
 * Plan ({@code next_run_at}) varlık kaydıyla YAZILMAZ ({@code updatable = false}), yalnız hedefli UPDATE ile; gönderim izdüşümü
 * zamanlama kolonlarını taşır; tatil değişimi okuması yalnız aktif + tatil atlayan izlemeleri getirir.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScriptedScheduleQueriesTest {

    @Autowired ScriptedMonitorRepository repo;

    @BeforeEach
    void clean() {
        repo.deleteAll();   // NOT_SUPPORTED: satırlar testler arasında kalır
    }

    private ScriptedMonitor save(String name, boolean active, String mode, Boolean skipHolidays) {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setName(name);
        m.setTeamId(1L);
        m.setActive(active);
        m.setScheduleMode(mode);
        m.setSkipHolidays(skipHolidays);
        if ("CRON".equals(mode)) m.setCronExpression("0 8 * * *");
        return repo.save(m);
    }

    @Test
    @DisplayName("next_run_at: varlık kaydı yazmaz (bayat okuma planı geri yazamaz); hedefli UPDATE yazar ve NULL'a sıfırlar")
    void nextRunAtOnlyViaTargetedUpdate() {
        ScriptedMonitor m = save("plan", true, "CRON", null);
        assertThat(repo.updateNextRunAt(m.getId(), "2026-11-10T05:00:00")).isEqualTo(1);
        assertThat(repo.findById(m.getId()).orElseThrow().getNextRunAt()).isEqualTo("2026-11-10T05:00:00");

        ScriptedMonitor stale = repo.findById(m.getId()).orElseThrow();
        stale.setNextRunAt("2000-01-01T00:00:00");
        stale.setName("plan (yeni ad)");
        repo.save(stale);
        ScriptedMonitor fresh = repo.findById(m.getId()).orElseThrow();
        assertThat(fresh.getName()).isEqualTo("plan (yeni ad)");
        assertThat(fresh.getNextRunAt()).isEqualTo("2026-11-10T05:00:00");

        assertThat(repo.updateNextRunAt(m.getId(), null)).isEqualTo(1);
        assertThat(repo.findById(m.getId()).orElseThrow().getNextRunAt()).isNull();
    }

    @Test
    @DisplayName("inceleme #5: koşullu plan yazımı — NULL'dan yalnız bir kez kurulur; öne çekme yalnız okunan değer hâlâ saklıysa yazar")
    void casNextRunAt() {
        ScriptedMonitor m = save("cas", true, "CRON", null);
        assertThat(repo.updateNextRunAtIfNull(m.getId(), "2026-11-10T05:00:00")).isEqualTo(1);
        assertThat(repo.updateNextRunAtIfNull(m.getId(), "2026-11-10T06:00:00")).as("ikinci 'first' kaybeder").isZero();
        assertThat(repo.findById(m.getId()).orElseThrow().getNextRunAt()).isEqualTo("2026-11-10T05:00:00");

        assertThat(repo.updateNextRunAtIfEquals(m.getId(), "2000-01-01T00:00:00", "2026-11-09T05:00:00"))
                .as("bayat okuma kaybeder").isZero();
        assertThat(repo.updateNextRunAtIfEquals(m.getId(), "2026-11-10T05:00:00", "2026-11-09T05:00:00")).isEqualTo(1);
        assertThat(repo.findById(m.getId()).orElseThrow().getNextRunAt()).isEqualTo("2026-11-09T05:00:00");
    }

    @Test
    @DisplayName("gönderim izdüşümü: [id, aralık, kip, cron, pencereler, yasaklar, tatil, ek tarihler, plan] — yalnız aktif")
    void dispatchRowsCarryScheduleColumns() {
        ScriptedMonitor cron = save("cron", true, "CRON", true);
        repo.updateNextRunAt(cron.getId(), "2026-11-10T05:00:00");
        ScriptedMonitor flat = save("duz", true, null, null);
        save("pasif", false, "CRON", true);
        List<Object[]> rows = repo.findCheckDispatchRows();
        assertThat(rows).hasSize(2);
        Object[] c = rows.stream().filter(r -> cron.getId().equals(((Number) r[0]).longValue())).findFirst().orElseThrow();
        assertThat(c).hasSize(9);
        assertThat(ScriptedSchedule.rowHasPlan(c)).isTrue();
        assertThat(ScriptedSchedule.specOfRow(c).cron()).isEqualTo("0 8 * * *");
        assertThat(ScriptedSchedule.rowNextRunAt(c)).isEqualTo("2026-11-10T05:00:00");
        Object[] f = rows.stream().filter(r -> flat.getId().equals(((Number) r[0]).longValue())).findFirst().orElseThrow();
        assertThat(((Number) f[1]).intValue()).isEqualTo(300);
        assertThat(ScriptedSchedule.rowHasPlan(f)).isFalse();
    }

    @Test
    @DisplayName("tatil değişimi okuması: yalnız aktif ve tatil atlayan izlemeler [id, kip]")
    void holidayScheduledRows() {
        ScriptedMonitor a = save("a", true, "CRON", true);
        ScriptedMonitor b = save("b", true, "INTERVAL", true);
        save("c", true, "CRON", false);
        save("d", false, "CRON", true);
        save("e", true, null, null);
        List<Object[]> rows = repo.findHolidayScheduledRows();
        assertThat(rows).extracting(r -> ((Number) r[0]).longValue()).containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(rows).extracting(r -> r[1]).containsExactlyInAnyOrder("CRON", "INTERVAL");
    }
}
