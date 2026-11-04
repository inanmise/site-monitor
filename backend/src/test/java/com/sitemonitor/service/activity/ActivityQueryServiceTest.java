package com.sitemonitor.service.activity;

import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.service.activity.ActivityFilter.Params;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.activity.ActivityRows.row;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ActivityQueryService} GERÇEK H2 üstünde (2026-11-05, aktivite zenginleştirmesi E): listenin yeni süzgeçleri
 * (durum CSV, izleme, takım kesişimi, kişi, grup, neden + eski satırın hata sınıfı, zaman), kapsam yalıtımı, sıralama /
 * sayfa sınırı ve özetin "kendi süzgeci hariç" sayımları + sabit sorgu sayısı. {@code NOT_SUPPORTED}: JDBC yazımları
 * test işlemi dışında görünür olsun.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivityQueryServiceTest {

    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    CountingDataSource counting;
    ActivityQueryService svc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        ActivityRows.extendSchema(jdbc);
        jdbc.update("DELETE FROM activity_log");
        counting = new CountingDataSource(dataSource);
        svc = new ActivityQueryService(new JdbcTemplate(counting));

        row("2026-11-05T10:00:00", "HTTP", 1L, "SUCCESS").team(1L).actor("scheduler").save(jdbc);
        row("2026-11-05T10:01:00", "HTTP", 1L, "ERROR").team(1L).reason("DNS_NXDOMAIN").save(jdbc);
        row("2026-11-05T10:02:00", "HTTP", 2L, "TIMEOUT").team(1L).reason("READ_TIMEOUT").save(jdbc);
        row("2026-11-05T10:03:00", "CERT", 5L, "ERROR").team(1L).errorClass("NETWORK").save(jdbc);       // eski satır: neden yok
        row("2026-11-05T10:04:00", "PORT", 3L, "WARNING").team(2L).save(jdbc);                          // başka takım
        row("2026-11-05T10:05:00", "HTTP", 1L, "SUCCESS").team(1L).action("MANUAL_CHECK").actor("Ahmet.Y").save(jdbc);
        row("2026-11-05T10:06:00", "HTTP", 1L, "ERROR").team(1L).action("ALERT_TRIGGERED").save(jdbc);
        row("2026-11-05T10:07:00", "HTTP", 1L, "SUCCESS").team(1L).action("CONFIG_CHANGED").actor("ahmet.y").save(jdbc);
        row("2026-11-05T10:08:00", "HTTP", 1L, "SUCCESS").team(1L).action("NOC_NOTIFIED").save(jdbc);
        row("2026-11-05T10:09:00", "CERT", 5L, "SUCCESS").team(1L).action("TLS_GRADE_DROPPED").save(jdbc);
        row("2026-11-05T10:10:00", "DNS", 9L, "SUCCESS").team(null).save(jdbc);                          // takımsız
    }

    private ActivityFilter f(Params p, boolean scoped, List<Long> scope) {
        return ActivityFilter.parse(p, scoped, scope);
    }

    private static Params params(String status, String monitor, Long teamId, String actor, String group, String reason) {
        return new Params(null, null, monitor, status, null, null, teamId, actor, group, reason, null);
    }

    private List<Long> ids(ActivityQueryService.PageResult r) {
        return r.rows().stream().map(ActivityLog::getId).toList();
    }

    private List<String> times(ActivityQueryService.PageResult r) {
        return r.rows().stream().map(a -> a.getActivityTime().substring(11, 16)).toList();
    }

    @Test
    @DisplayName("kapsam: [1] → yalnız takım 1; global → tümü (takımsız dahil); teamId kapsamla KESİŞİR (genişletmez)")
    void scopeAndTeamIntersection() {
        assertThat(svc.list(f(params(null, null, null, null, null, null), true, List.of(1L)), 0, 50).total()).isEqualTo(9);
        assertThat(svc.list(f(params(null, null, null, null, null, null), false, List.of()), 0, 50).total()).isEqualTo(11);
        assertThat(svc.list(f(params(null, null, 2L, null, null, null), true, List.of(1L)), 0, 50).total())
                .as("takım 1 kullanıcısı teamId=2 ile başka takımı göremez").isZero();
        assertThat(svc.list(f(params(null, null, 2L, null, null, null), false, List.of()), 0, 50).total()).isEqualTo(1);
    }

    @Test
    @DisplayName("durum CSV ERROR,TIMEOUT → ikisi de (KPI 'Hata' tıklaması TIMEOUT'u kaybetmez); en yeni üstte")
    void statusCsv() {
        ActivityQueryService.PageResult r = svc.list(f(params("ERROR,TIMEOUT", null, null, null, null, null), true, List.of(1L)), 0, 50);
        assertThat(times(r)).containsExactly("10:06", "10:03", "10:02", "10:01");
    }

    @Test
    @DisplayName("monitor=HTTP:1 → yalnız o izleme; kişi tam eşleşme büyük/küçük harf duyarsız")
    void monitorAndActor() {
        assertThat(svc.list(f(params(null, "HTTP:1", null, null, null, null), false, List.of()), 0, 50).total()).isEqualTo(6);
        assertThat(times(svc.list(f(params(null, null, null, "AHMET.y", null, null), false, List.of()), 0, 50)))
                .containsExactly("10:07", "10:05");
        assertThat(svc.list(f(params(null, null, null, "ahmet", null, null), false, List.of()), 0, 50).total())
                .as("kısmi ad eşleşmez").isZero();
    }

    @Test
    @DisplayName("grup süzgeci SQL'i Java groupOf ile birebir: her eylem kendi grubunda, diğer = kalanlar")
    void groupSqlMirrorsGroupOf() {
        Map<String, Long> expected = new java.util.HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT action FROM activity_log")) {
            expected.merge(ActivityFilter.groupOf((String) r.get("action")), 1L, Long::sum);
        }
        for (String g : ActivityFilter.GROUPS) {
            long n = svc.list(f(params(null, null, null, null, g, null), false, List.of()), 0, 50).total();
            assertThat(n).as(g).isEqualTo(expected.getOrDefault(g, 0L));
        }
        assertThat(expected).containsEntry("check", 7L).containsEntry("alarm", 1L).containsEntry("notification", 1L)
                .containsEntry("change", 1L).containsEntry("other", 1L);
        assertThat(svc.list(f(params(null, null, null, null, "alarm,change", null), false, List.of()), 0, 50).total()).isEqualTo(2);
    }

    @Test
    @DisplayName("neden: kod failure_reason ile; class:NETWORK yalnız nedeni boş eski satırın hata sınıfı ile eşleşir")
    void reasonAndLegacyClass() {
        assertThat(times(svc.list(f(params(null, null, null, null, null, "DNS_NXDOMAIN,READ_TIMEOUT"), false, List.of()), 0, 50)))
                .containsExactly("10:02", "10:01");
        assertThat(times(svc.list(f(params(null, null, null, null, null, "class:NETWORK"), false, List.of()), 0, 50)))
                .containsExactly("10:03");
        assertThat(svc.list(f(params(null, null, null, null, null, "DNS_NXDOMAIN,class:NETWORK"), false, List.of()), 0, 50).total())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("zaman aralığı (serbest ISO, ofsetli) + sayfa sınırı: aynı saniyede iki satır yinelenmez / kaybolmaz")
    void rangeAndPaging() {
        row("2026-11-05T10:09:00", "HTTP", 7L, "SUCCESS").team(1L).save(jdbc);   // 10:09'da ikinci satır
        Params p = new Params(null, null, null, null, "2026-11-05T13:03:00+03:00", "2026-11-05T10:09:00Z", null, null, null, null, null);
        ActivityFilter fl = f(p, false, List.of());
        ActivityQueryService.PageResult p0 = svc.list(fl, 0, 4), p1 = svc.list(fl, 1, 4);
        assertThat(p0.total()).isEqualTo(8);
        java.util.Set<Long> seen = new java.util.HashSet<>(ids(p0));
        seen.addAll(ids(p1));
        assertThat(seen).hasSize(8);
        assertThat(times(p0)).first().isEqualTo("10:09");
        assertThat(svc.list(fl, 5, 4).rows()).isEmpty();
    }

    @Test
    @DisplayName("özet: durum sayımı durum hariç; tür sayımı tür hariç grup DAHİL; grup sayımı grup hariç tür DAHİL; 3 sorgu")
    void summaryExclusionsAndQueryCount() {
        Params p = new Params("HTTP", null, null, "SUCCESS", null, null, null, null, "check", null, null);
        counting.reset();
        ActivityQueryService.SummaryData d = svc.summary(f(p, true, List.of(1L)), "2026-11-05T00:00:00");
        assertThat(counting.reset()).as("özet sorgu sayısı").isEqualTo(3);
        // durum hariç: HTTP + check → 10:00 S, 10:01 E, 10:02 T, 10:05 S
        assertThat(d.statusCounts()).containsEntry("SUCCESS", 2L).containsEntry("ERROR", 1L).containsEntry("TIMEOUT", 1L);
        assertThat(d.lastActivity()).isEqualTo("2026-11-05T10:05:00");
        // tür hariç (durum SUCCESS + check dahil): HTTP 2 (10:00, 10:05); CERT'in SUCCESS'i TLS_GRADE_DROPPED (diğer) → yok
        assertThat(d.typeCounts()).containsExactly(Map.entry("HTTP", 2L));
        // grup hariç (tür HTTP + SUCCESS dahil): check 2, change 1 (10:07), notification 1 (10:08)
        assertThat(d.groupCounts()).containsExactly(Map.entry("check", 2L), Map.entry("alarm", 0L),
                Map.entry("notification", 1L), Map.entry("change", 1L), Map.entry("other", 0L));
        // seri durum hariç, saat başı
        long hourTotal = d.hourRows().stream().mapToLong(r -> ((Number) r[2]).longValue()).sum();
        assertThat(hourTotal).isEqualTo(4);
        assertThat(d.hourRows()).allMatch(r -> "2026-11-05T10".equals(r[0]));
    }

    @Test
    @DisplayName("liste: sayım + sayfa = 2 sorgu; boş sonuçta yalnız sayım")
    void listQueryCount() {
        counting.reset();
        svc.list(f(params(null, null, null, null, null, null), false, List.of()), 0, 50);
        assertThat(counting.reset()).isEqualTo(2);
        svc.list(f(params("FOO", null, null, null, null, null), false, List.of()), 0, 50);
        assertThat(counting.reset()).isEqualTo(1);
    }

    @Test
    @DisplayName("satır eşlemesi: SELECT * varlığa adla eşlenir (eklenen kolonlar varlıkta yoksa yok sayılır)")
    void rowMapping() {
        ActivityLog a = svc.list(f(params(null, "CERT:5", null, null, null, "class:NETWORK"), false, List.of()), 0, 1).rows().get(0);
        assertThat(a.getMonitorType()).isEqualTo("CERT");
        assertThat(a.getMonitorId()).isEqualTo(5L);
        assertThat(a.getErrorClass()).isEqualTo("NETWORK");
        assertThat(a.getTeamId()).isEqualTo(1L);
        assertThat(a.getActivityTime()).isEqualTo("2026-11-05T10:03:00");
    }
}
