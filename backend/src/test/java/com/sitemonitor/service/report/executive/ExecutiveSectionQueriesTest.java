package com.sitemonitor.service.report.executive;

import com.sitemonitor.model.TlsGradeChange;
import com.sitemonitor.model.TlsProfile;
import com.sitemonitor.repository.TlsGradeChangeRepository;
import com.sitemonitor.repository.TlsGradeStatusRepository;
import com.sitemonitor.repository.TlsProfileRepository;
import com.sitemonitor.service.quality.DataQualityService;
import com.sitemonitor.service.quality.DataQualitySource;
import com.sitemonitor.service.tlsgrade.TlsGradeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Yönetici özetinin yeni bölümlerinin GERÇEK sorguları (H2, PostgreSQL kipi): TLS not düşüşlerinin ay penceresi (İstanbul
 * ayı → UTC damga, yarı açık, yalnız DROP), profil kapsaması ve veri kalitesinin ay sonu görüntüleri.
 */
@DataJpaTest
@org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase(
        replace = org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ExecutiveSectionQueriesTest {

    @Autowired DataSource dataSource;
    @Autowired TlsProfileRepository profiles;
    @Autowired TlsGradeStatusRepository statuses;
    @Autowired TlsGradeChangeRepository changes;

    private TlsGradeChange change(long inv, String direction, String at) {
        TlsGradeChange c = new TlsGradeChange();
        c.setInventoryId(inv);
        c.setDomain("d" + inv + ".example.com");
        c.setFromGrade("A");
        c.setToGrade("B");
        c.setDirection(direction);
        c.setChangedAt(at);
        return changes.save(c);
    }

    @Test
    @DisplayName("TLS düşüşleri: Eylül (İstanbul) = [31.08 21:00Z, 30.09 21:00Z); sınır dahil/hariç, RISE/REFINE sayılmaz, liste sınırlı")
    void tlsDropsMonthWindow() {
        change(1, TlsGradeChange.DROP, "2026-08-31T20:59:59");   // Ağustos (TR 23:59:59)
        change(2, TlsGradeChange.DROP, "2026-08-31T21:00:00");   // Eylül'ün ilk anı (TR 00:00)
        change(3, TlsGradeChange.DROP, "2026-09-15T10:00:00");
        change(4, TlsGradeChange.DROP, "2026-09-30T20:59:59");   // Eylül'ün son anı
        change(5, TlsGradeChange.DROP, "2026-09-30T21:00:00");   // Ekim (TR 1 Ekim 00:00)
        change(6, TlsGradeChange.RISE, "2026-09-10T10:00:00");
        change(7, TlsGradeChange.REFINE, "2026-09-11T10:00:00");
        ExecutiveSummaryContext ctx = new ExecutiveSummaryContext(YearMonth.of(2026, 9),
                java.time.Instant.parse("2026-10-10T09:00:00Z"), 99.9, 30, List::of, Map::of, Map::of);
        TlsGradeService svc = new TlsGradeService(profiles, statuses, changes);

        TlsGradeService.DropWindow w = svc.dropsBetween(ctx.fromIso(), ctx.toIso(), 500);
        assertThat(w.total()).isEqualTo(3);
        assertThat(w.rows()).extracting(TlsGradeChange::getInventoryId).containsExactly(4L, 3L, 2L);

        TlsGradeService.DropWindow capped = svc.dropsBetween(ctx.fromIso(), ctx.toIso(), 2);
        assertThat(capped.total()).isEqualTo(3);
        assertThat(capped.rows()).hasSize(2);

        TlsGradeService.DropWindow aug = svc.dropsBetween("2026-07-31T21:00:00", "2026-08-31T21:00:00", 500);
        assertThat(aug.total()).isEqualTo(1);
        assertThat(aug.rows()).extracting(TlsGradeChange::getInventoryId).containsExactly(1L);
    }

    @Test
    @DisplayName("TLS profil kapsaması: verilen ağ alan adları; yoklanan / taranamayan / bekleyen")
    void tlsCoverageForDomains() {
        for (String[] p : new String[][]{ {"a.com", TlsProfile.STATUS_OK}, {"b.com", TlsProfile.STATUS_PARTIAL},
                {"c.com", TlsProfile.STATUS_FAILED}, {"other.com", TlsProfile.STATUS_OK} }) {
            TlsProfile t = new TlsProfile();
            t.setDomain(p[0]);
            t.setStatus(p[1]);
            t.setProbedAt("2026-10-10T06:00:00");
            profiles.save(t);
        }
        Map<String, Object> c = new TlsGradeService(profiles, statuses, changes)
                .coverageForDomains(Set.of("a.com", "b.com", "c.com", "d.com"));
        assertThat(c).containsEntry("endpoints", 4).containsEntry("ok", 1).containsEntry("partial", 1)
                .containsEntry("failed", 1).containsEntry("pending", 1);
    }

    @Test
    @DisplayName("veri kalitesi ay sonu görüntüleri: ayın ve önceki ayın SON günü (kurum satırı), o iki günün kova puanları")
    void dataQualityMonthEnds() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String ins = "INSERT INTO data_quality_daily(snap_day, team_key, score, findings, items, created_at) VALUES (?, ?, ?, 0, 0, ?)";
        jdbc.update(ins, "2026-08-15", 0L, 70, "x");
        jdbc.update(ins, "2026-08-31", 0L, 72, "x");
        jdbc.update(ins, "2026-08-31", 5L, 60, "x");
        jdbc.update(ins, "2026-09-10", 0L, 75, "x");
        jdbc.update(ins, "2026-09-30", 0L, 80, "x");
        jdbc.update(ins, "2026-09-30", 5L, 66, "x");
        jdbc.update(ins, "2026-09-30", -1L, null, "x");
        jdbc.update(ins, "2026-10-01", 0L, 90, "x");                 // sonraki ay — Eylül'e girmez
        DataQualityService svc = new DataQualityService(mock(DataQualitySource.class), jdbc, 60_000);

        DataQualityService.MonthEnds sep = svc.monthEnds(LocalDate.of(2026, 9, 1));
        assertThat(sep.prevDay()).isEqualTo("2026-08-31");
        assertThat(sep.curDay()).isEqualTo("2026-09-30");
        assertThat(sep.prev()).containsEntry(0L, 72).containsEntry(5L, 60);
        assertThat(sep.cur()).containsEntry(0L, 80).containsEntry(5L, 66).doesNotContainKey(-1L);

        DataQualityService.MonthEnds oct = svc.monthEnds(LocalDate.of(2026, 10, 1));
        assertThat(oct.prevDay()).isEqualTo("2026-09-30");
        assertThat(oct.curDay()).isEqualTo("2026-10-01");
        assertThat(oct.cur()).containsEntry(0L, 90);

        DataQualityService.MonthEnds aug = svc.monthEnds(LocalDate.of(2026, 8, 1));
        assertThat(aug.prevDay()).isNull();
        assertThat(aug.curDay()).isEqualTo("2026-08-31");
        assertThat(aug.cur()).containsEntry(0L, 72);
        assertThat(aug.prev()).isEmpty();

        assertThat(svc.monthEnds(LocalDate.of(2026, 3, 1))).isEqualTo(DataQualityService.MonthEnds.NONE);
    }
}
