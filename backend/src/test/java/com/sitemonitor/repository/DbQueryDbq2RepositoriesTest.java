package com.sitemonitor.repository;

import com.sitemonitor.model.DbQueryCheck;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.model.MonitorChangeLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dbq2 (2026-11-05) sorguları gerçek JPA / H2 üzerinde: bağlantı sağlığı (son durum LATERAL, 24 sa toplamı, son hatanın kodu),
 * kart değer eğilimi (saatlik kova, zamanlanmış + çalışmış satırlar), alarm postası son zamanlanmış çalıştırmaları, grafik
 * değişiklik işaretleri. Yerli sorgular H2'de de çalışmalı (türetilmiş tablo kovası; {@code GROUP BY 1, 2} yok).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DbQueryDbq2RepositoriesTest {

    @Autowired DbQueryMonitorRepository monitors;
    @Autowired DbQueryCheckRepository checks;
    @Autowired MonitorChangeLogRepository changes;
    @Autowired AlertEventRepository alerts;

    @AfterEach
    void cleanUp() {
        checks.deleteAll();
        monitors.deleteAll();
        changes.deleteAll();
        alerts.deleteAll();
    }

    private DbQueryMonitor mon(String name) {
        DbQueryMonitor m = new DbQueryMonitor();
        m.setTeamId(1L);
        m.setName(name);
        m.setActive(true);
        m.setSqlText("SELECT 1");
        return monitors.save(m);
    }

    private DbQueryCheck check(long monitorId, String at, String status, boolean ok, Double value, Boolean manual,
                               Boolean recheck, Integer ms, String reason) {
        DbQueryCheck c = new DbQueryCheck();
        c.setMonitorId(monitorId);
        c.setCheckedAt(at);
        c.setOk(ok);
        c.setStatus(status);
        c.setMetricValue(value);
        c.setManual(manual);
        c.setRecheck(recheck);
        c.setDurationMs(ms);
        c.setFailureReason(reason);
        c.setBreachLevel("BREACH".equals(status) ? "WARNING" : "NONE");
        return checks.save(c);
    }

    @Test
    @DisplayName("sağlık: izleme başına SON ZAMANLANMIŞ çalıştırmanın durumu (elle / teyit hariç, hiç çalışmamış → null); 24 sa toplamı (tüm çalıştırmalar; başarısızlık yalnız bağlantı sınıfı); son hatanın kodu eşit zamandan")
    void connectionHealthQueries() {
        long a = mon("A").getId(), b = mon("B").getId(), c = mon("C").getId();
        check(a, "2026-11-05T08:00:00", "OK", true, 1.0, null, null, 100, null);
        check(a, "2026-11-05T09:00:00", "BREACH", true, 9.0, false, false, 300, null);
        check(b, "2026-11-04T07:00:00", "OK", true, 1.0, null, null, 50, null);           // pencere dışı
        check(b, "2026-11-05T08:30:00", "FAIL", false, null, false, false, 200, "DB_TIMEOUT");
        check(b, "2026-11-05T08:35:00", "OK", true, 1.0, true, false, 40, null);           // elle — son durum değil
        check(a, "2026-11-05T09:10:00", "OK", true, 1.0, false, true, 60, null);           // teyit — son durum değil
        check(c, "2026-11-05T08:20:00", "FAIL", false, null, null, null, 0, "DB_SQL_ERROR"); // bağlantı sağlam, sorgu bozuk
        Map<Long, String> last = new HashMap<>();
        for (Object[] r : checks.latestStatusFor(List.of(a, b, c))) last.put(((Number) r[0]).longValue(), (String) r[1]);
        assertThat(last).containsEntry(a, "BREACH").containsEntry(b, "FAIL").containsEntry(c, "FAIL").hasSize(3);
        long d = mon("D").getId();
        for (Object[] r : checks.latestStatusFor(List.of(d))) assertThat(r[1]).isNull();   // hiç çalışmamış → null

        Object[] agg = checks.connectionRunStats(List.of(a, b, c), "2026-11-05T00:00:00",
                com.sitemonitor.service.dbquery.DbConnectionProfileService.CONNECTION_CODES).get(0);
        assertThat(((Number) agg[0]).longValue()).isEqualTo(6);
        assertThat(((Number) agg[1]).longValue()).isEqualTo(1);    // yalnız DB_TIMEOUT (bağlantı sınıfı)
        assertThat(((Number) agg[2]).doubleValue()).isEqualTo(116.66666666666667, org.assertj.core.data.Offset.offset(0.01));
        assertThat(((Number) agg[3]).longValue()).isEqualTo(1);    // DB_SQL_ERROR → diğer

        assertThat(checks.failureReasonsAt(List.of(a, b), "2026-11-05T08:30:00")).containsExactly("DB_TIMEOUT");
        assertThat(checks.failureReasonsAt(List.of(a, b), "2026-11-05T09:00:00")).isEmpty();   // ok satırı sayılmaz
    }

    @Test
    @DisplayName("değer eğilimi: saatlik ortalama — yalnız zamanlanmış, sorgusu çalışmış, değeri olan satırlar; izleme kümesiyle sınırlı")
    void valueTrendBuckets() {
        long a = mon("A").getId(), b = mon("B").getId(), other = mon("X").getId();
        check(a, "2026-11-05T08:10:00", "OK", true, 10.0, null, null, 1, null);
        check(a, "2026-11-05T08:40:00", "BREACH", true, 20.0, false, false, 1, null);
        check(a, "2026-11-05T08:45:00", "OK", true, 999.0, true, false, 1, null);     // elle — dışarıda
        check(a, "2026-11-05T08:50:00", "OK", true, 999.0, false, true, 1, null);     // teyit — dışarıda
        check(a, "2026-11-05T09:05:00", "FAIL", false, null, false, false, 1, "DB_AUTH");
        check(a, "2026-11-05T09:15:00", "UNKNOWN", true, 30.0, null, null, 1, null);
        check(a, "2026-11-04T01:00:00", "OK", true, 5.0, null, null, 1, null);        // pencere dışı
        check(b, "2026-11-05T08:20:00", "OK", true, null, null, null, 1, null);       // değer yok
        check(other, "2026-11-05T08:20:00", "OK", true, 7.0, null, null, 1, null);
        Map<String, Double> byKey = new HashMap<>();
        for (Object[] r : checks.valueTrendBuckets(List.of(a, b), "2026-11-05T00:00:00")) {
            assertThat(((Number) r[0]).longValue()).isEqualTo(a);
            byKey.put(r[1].toString(), ((Number) r[2]).doubleValue());
        }
        assertThat(byKey).containsOnly(Map.entry("2026-11-05T08", 15.0), Map.entry("2026-11-05T09", 30.0));
    }

    @Test
    @DisplayName("alarm postası: son ZAMANLANMIŞ çalıştırmalar yeniden eskiye, sınırla; elle / teyit satırı yok")
    void recentScheduledSummary() {
        long a = mon("A").getId();
        check(a, "2026-11-05T08:00:00", "OK", true, 1.0, null, null, 1, null);
        check(a, "2026-11-05T08:05:00", "BREACH", true, 9.0, false, false, 1, null);
        check(a, "2026-11-05T08:06:00", "BREACH", true, 9.5, true, false, 1, null);   // elle
        check(a, "2026-11-05T08:07:00", "BREACH", true, 9.6, false, true, 1, null);   // teyit
        check(a, "2026-11-05T08:10:00", "FAIL", false, null, false, false, 1, "DB_TIMEOUT");
        List<Object[]> rows = checks.recentScheduledSummary(a, 2);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[0]).isEqualTo("2026-11-05T08:10:00");
        assertThat(rows.get(0)[1]).isEqualTo("FAIL");
        assertThat(rows.get(0)[3]).isEqualTo(false);
        assertThat(rows.get(0)[5]).isEqualTo("DB_TIMEOUT");
        assertThat(rows.get(1)[0]).isEqualTo("2026-11-05T08:05:00");
        assertThat(((Number) rows.get(1)[2]).doubleValue()).isEqualTo(9.0);
        assertThat(rows.get(1)[4]).isEqualTo("WARNING");
        assertThat(rows.get(0)).hasSize(8);   // 8. kolon sql_hash (dbq2 inceleme D9: seri sorgu değişince kesilir)
    }

    @Test
    @DisplayName("grafik değişiklik işaretleri: aralıktaki satırlar yeniden eskiye, sınırla; başka kaynak / tür yok")
    void changeMarks() {
        save("DBQUERY", 7L, 1, "CREATE", "2026-11-01T10:00:00", null);
        save("DBQUERY", 7L, 2, "UPDATE", "2026-11-04T12:00:00", "{\"sqlText\":{\"from\":\"a\",\"to\":\"b\"}}");
        save("DBQUERY", 7L, 3, "UPDATE", "2026-11-06T12:00:00", "{}");    // aralık dışı
        save("DBQUERY", 8L, 1, "UPDATE", "2026-11-04T12:00:00", "{}");    // başka izleme
        save("HTTP", 7L, 1, "UPDATE", "2026-11-04T12:00:00", "{}");       // başka tür
        List<Object[]> rows = changes.changeMarks("DBQUERY", 7L, "2026-11-01T00:00:00", "2026-11-05T00:00:00", 200);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[0]).isEqualTo("2026-11-04T12:00:00");
        assertThat(rows.get(0)[1]).isEqualTo("UPDATE");
        assertThat(rows.get(0)[2].toString()).contains("sqlText");
        assertThat(rows.get(1)[1]).isEqualTo("CREATE");
        assertThat(changes.changeMarks("DBQUERY", 7L, "2026-11-01T00:00:00", "2026-11-05T00:00:00", 1)).hasSize(1);
    }

    @Test
    @DisplayName("D12: grafik alarm dönemleri veritabanında sınırlı — en yeni N satır (yeniden eskiye, id eşitlik kırıcı), aralık kuralı aynı")
    void overlappingNewestIsLimited() {
        for (int i = 0; i < 5; i++) alert("Bekleyen", "DBQUERY_FAIL", String.format("2026-11-04T0%d:00:00", i), true, String.format("2026-11-04T0%d:30:00", i));
        alert("Bekleyen", "DBQUERY_FAIL", "2026-11-04T04:00:00", false, null);              // aynı an, daha büyük id
        alert("Bekleyen", "DBQUERY_FAIL", "2026-11-01T00:00:00", true, "2026-11-01T01:00:00"); // aralık dışı
        alert("Baska", "DBQUERY_FAIL", "2026-11-04T03:00:00", false, null);                 // başka anahtar
        List<com.sitemonitor.model.AlertEvent> top = alerts.findOverlappingNewest("Bekleyen", List.of("DBQUERY_FAIL"),
                "2026-11-03T00:00:00", "2026-11-05T00:00:00", org.springframework.data.domain.PageRequest.of(0, 3));
        assertThat(top).hasSize(3);
        assertThat(top).extracting(com.sitemonitor.model.AlertEvent::getCreatedAt)
                .containsExactly("2026-11-04T04:00:00", "2026-11-04T04:00:00", "2026-11-04T03:00:00");
        assertThat(top.get(0).getResolved()).isFalse();   // aynı anda açılan iki alarmdan id'si büyük olan önce
        assertThat(alerts.findOverlappingNewest("Bekleyen", List.of("DBQUERY_FAIL"), "2026-11-03T00:00:00",
                "2026-11-05T00:00:00", org.springframework.data.domain.PageRequest.of(0, 100))).hasSize(6);
    }

    private void alert(String domain, String type, String at, boolean resolved, String resolvedAt) {
        com.sitemonitor.model.AlertEvent a = new com.sitemonitor.model.AlertEvent();
        a.setDomain(domain);
        a.setAlertType(type);
        a.setAlertLevel("HIGH");
        a.setCreatedAt(at);
        a.setResolved(resolved);
        a.setResolvedAt(resolvedAt);
        alerts.save(a);
    }

    private void save(String kind, long id, int seq, String event, String at, String json) {
        MonitorChangeLog c = new MonitorChangeLog();
        c.setResourceKind(kind);
        c.setResourceId(id);
        c.setSeq(seq);
        c.setEventType(event);
        c.setCreatedAt(at);
        c.setChanges(json);
        changes.save(c);
    }
}
