package com.sitemonitor.service;

import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.model.DbQueryCheck;
import com.sitemonitor.model.DbQueryMonitor;
import com.sitemonitor.repository.DbConnectionProfileRepository;
import com.sitemonitor.repository.DbQueryCheckRepository;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import com.sitemonitor.service.dbquery.DbQueryAlertRowsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sorgu alarmı postasının parçaları (2026-10-31): canlı izleme / bağlantı bilgisi, alarm anındaki satırlar (yalnız GÖNDERİLEN
 * postada — günlük kopyasında yalnız sayı), özel konu; bağlantının kullanıcı adı / parolası hiçbir zaman.
 */
class EscalationDbQueryMailPartsTest {

    EscalationService esc;
    DbQueryMonitorRepository monitors;
    DbConnectionProfileRepository profiles;
    DbQueryCheckRepository checks;
    DbQueryAlertRowsService rows;

    static final DbQueryAlertRowsService.Rows ROWS = new DbQueryAlertRowsService.Rows("2026-10-31T09:00:00",
            List.of("id", "tutar"), List.of(List.of("1", "100"), List.of("2", "250")), List.of(), 25, false, false);

    @BeforeEach
    void setUp() throws Exception {
        Constructor<?> c = Arrays.stream(EscalationService.class.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = new Object[c.getParameterCount()];
        Class<?>[] pt = c.getParameterTypes();
        for (int i = 0; i < pt.length; i++) args[i] = pt[i].isPrimitive() ? null : mock(pt[i]);
        esc = (EscalationService) c.newInstance(args);
        monitors = mock(DbQueryMonitorRepository.class);
        profiles = mock(DbConnectionProfileRepository.class);
        checks = mock(DbQueryCheckRepository.class);
        rows = mock(DbQueryAlertRowsService.class);
        ReflectionTestUtils.setField(esc, "dbQueryMonitorRepo", monitors);
        ReflectionTestUtils.setField(esc, "dbConnectionProfileRepo", profiles);
        ReflectionTestUtils.setField(esc, "dbQueryCheckRepo", checks);
        ReflectionTestUtils.setField(esc, "dbQueryAlertRows", rows);

        DbQueryMonitor m = new DbQueryMonitor();
        m.setId(42L);
        m.setName("Bekleyen Siparişler");
        m.setPurpose("  Birikme olmasın ");
        m.setSqlText("SELECT id, tutar FROM siparis");
        m.setProfileId(3L);
        m.setSlowQueryMs(1000);
        m.setMailSubjectTemplate("{seviye} · {ad} — {satir} satır ({baglanti})");
        when(monitors.findById(42L)).thenReturn(Optional.of(m));
        DbConnectionProfile p = new DbConnectionProfile();
        p.setId(3L);
        p.setName("ERP Üretim");
        p.setDbType("ORACLE");
        p.setHost("erpdb.corp.local");
        p.setPort(1521);
        p.setDatabaseName("ERP");
        p.setUsername("svc_monitor");
        p.setPasswordEnc("enc:v1:gizli");
        when(profiles.findById(3L)).thenReturn(Optional.of(p));
        when(rows.capture(anyLong(), anyLong())).thenReturn(Optional.of(ROWS));
    }

    static Map<String, Object> freshCtx() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("monitor_id", 42L);
        c.put("row_count", 25);
        c.put("checked_at", "2026-10-31T09:00:00");
        c.put(EscalationService.CTX_DBQUERY_FRESH, "2026-10-31T09:00:00");
        return c;
    }

    @Test
    @DisplayName("açılış (taze bağlam): izleme + bağlantı canlı okunur; satırlar alarmla saklanır, YALNIZ gönderim kopyasında; özel konu")
    void freshOpening() {
        var parts = esc.dbQueryMailParts(900L, EscalationService.TYPE_DBQUERY_THRESHOLD, "CRITICAL", freshCtx());
        assertThat(parts.extra()).containsEntry("monitor_name", "Bekleyen Siparişler").containsEntry("purpose", "Birikme olmasın")
                .containsEntry("dbquery_sql", "SELECT id, tutar FROM siparis").containsEntry("profile_name", "ERP Üretim")
                .containsEntry("db_type", "ORACLE").containsEntry("dbquery_target", "erpdb.corp.local:1521/ERP")
                .containsEntry("threshold_ms", 1000);
        assertThat(String.valueOf(parts.extra())).doesNotContain("svc_monitor").doesNotContain("gizli");
        verify(checks, never()).findTopByMonitorIdOrderByCheckedAtDesc(anyLong());
        verify(rows).capture(900L, 42L);

        Map<String, Object> send = parts.apply(freshCtx(), true);
        Map<String, Object> log = parts.apply(freshCtx(), false);
        assertThat(send).containsEntry(DbQueryAlertRowsService.CTX_ROWS, ROWS);
        assertThat(log).doesNotContainKey(DbQueryAlertRowsService.CTX_ROWS).containsEntry(DbQueryAlertRowsService.CTX_ROWS_WITHHELD, 2);
        assertThat(String.valueOf(log)).doesNotContain("250");

        assertThat(parts.subject()).isEqualTo("KRİTİK · Bekleyen Siparişler — 25 satır (ERP Üretim)");
    }

    @Test
    @DisplayName("sorgu hatası alarmında satır yok; izleme 'satırlar maile eklenmesin' derse satır okunmaz, not düşülür")
    void failAndRowsOff() {
        var fail = esc.dbQueryMailParts(901L, EscalationService.TYPE_DBQUERY_FAIL, "HIGH", freshCtx());
        assertThat(fail.rows()).isNull();
        verify(rows, never()).capture(901L, 42L);

        monitors.findById(42L).get().setMailIncludeRows(false);
        var off = esc.dbQueryMailParts(902L, EscalationService.TYPE_DBQUERY_THRESHOLD, "WARNING", freshCtx());
        assertThat(off.rows()).isNull();
        assertThat(off.extra()).containsEntry("dbquery_rows_off", true);
        verify(rows, never()).capture(902L, 42L);
    }

    @Test
    @DisplayName("anlık görüntü (yeniden uyarı / elle gönderim): değerler son çalıştırmadan — satırlarla tutarlı")
    void snapshotReadsLatestCheck() {
        DbQueryCheck last = new DbQueryCheck();
        last.setStatus("BREACH");
        last.setRowCount(31);
        last.setDurationMs(700);
        last.setCheckedAt("2026-10-31T11:00:00");
        last.setRuleResultsJson("[{\"label\":\"Bekleyen\",\"kind\":\"ROW_COUNT\",\"actual\":\"31\",\"expected\":\"≤ 10\","
                + "\"level\":\"CRITICAL\",\"passed\":false,\"evaluated\":true}]");
        when(checks.findTopByMonitorIdOrderByCheckedAtDesc(42L)).thenReturn(Optional.of(last));
        Map<String, Object> snap = new LinkedHashMap<>(Map.of("monitor_id", 42L, "row_count", 25));
        var parts = esc.dbQueryMailParts(903L, EscalationService.TYPE_DBQUERY_THRESHOLD, "CRITICAL", snap);
        assertThat(parts.extra()).containsEntry("row_count", 31).containsEntry("checked_at", "2026-10-31T11:00:00")
                .containsEntry("dbquery_status", "BREACH");
        assertThat(String.valueOf(parts.extra().get("rule_summary"))).contains("Bekleyen: gelen 31");
    }

    @Test
    @DisplayName("izleme / bağlantı okunamazsa posta yine kurulur (bilgi eksik, hata yok); özel konu yoksa null = standart konu")
    void failuresAreSwallowed() {
        when(monitors.findById(42L)).thenThrow(new IllegalStateException("db"));
        var parts = esc.dbQueryMailParts(904L, EscalationService.TYPE_DBQUERY_SLOW, "WARNING", freshCtx());
        assertThat(parts.subject()).isNull();
        assertThat(parts.extra()).doesNotContainKey("profile_name");
        assertThat(parts.apply(null, false)).isNotNull();
    }
}
