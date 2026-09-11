package com.sitemonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tablo kayıt defteri (SQL Playground "ne zaman oluştu / son değişim", 2026-09-11) — JdbcTemplate mock'lu saf testler.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchemaTableRegistryServiceTest {

    @Mock JdbcTemplate jdbc;
    private SchemaTableRegistryService service;

    @BeforeEach
    void setUp() {
        service = new SchemaTableRegistryService(jdbc);
        when(jdbc.queryForList(contains("information_schema.tables"), eq(String.class)))
                .thenReturn(List.of("audit_log", "teams"));
    }

    @Test
    @DisplayName("ilk tur: defter boş → tüm tablolar bootstrap=true, last_change_at NULL (≈ ilk görülme)")
    void firstTick_bootstrapsAllTables() {
        when(jdbc.queryForList(contains("pg_stat_user_tables"))).thenReturn(List.of(
                Map.of("relname", "audit_log", "activity", 120L), Map.of("relname", "teams", "activity", 3L)));
        when(jdbc.queryForList(contains("SELECT table_name, activity_counter FROM"))).thenReturn(List.of());

        int touched = service.tick();

        assertThat(touched).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> cap = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(startsWith("INSERT INTO schema_table_registry"), cap.capture());
        List<Object[]> rows = cap.getValue();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[0]).isEqualTo("audit_log");
        assertThat(rows.get(0)[3]).isEqualTo(true);       // bootstrap
        assertThat(rows.get(0)[4]).isEqualTo(120L);       // activity_counter
        assertThat(rows.get(0)[5]).isNull();              // last_change_at: bootstrap'ta bilinmiyor
        verify(jdbc, never()).batchUpdate(startsWith("UPDATE"), anyList());
    }

    @Test
    @DisplayName("sonraki tur: sayacı değişen tabloya last_change_at yazılır, değişmeyene dokunulmaz, yeni tablo bootstrap=false")
    void nextTick_marksChangedAndInsertsNew() {
        when(jdbc.queryForList(contains("information_schema.tables"), eq(String.class)))
                .thenReturn(List.of("audit_log", "teams", "deployment_history"));
        when(jdbc.queryForList(contains("pg_stat_user_tables"))).thenReturn(List.of(
                Map.of("relname", "audit_log", "activity", 121L), Map.of("relname", "teams", "activity", 3L),
                Map.of("relname", "deployment_history", "activity", 1L)));
        when(jdbc.queryForList(contains("SELECT table_name, activity_counter FROM"))).thenReturn(List.of(
                Map.of("table_name", "audit_log", "activity_counter", 120L),
                Map.of("table_name", "teams", "activity_counter", 3L)));

        int touched = service.tick();

        assertThat(touched).isEqualTo(2);   // 1 değişim + 1 yeni
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> upd = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(startsWith("UPDATE schema_table_registry SET activity_counter"), upd.capture());
        assertThat(upd.getValue()).hasSize(1);
        assertThat(upd.getValue().get(0)[0]).isEqualTo(121L);
        assertThat(upd.getValue().get(0)[3]).isEqualTo("audit_log");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> ins = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(startsWith("INSERT INTO schema_table_registry"), ins.capture());
        assertThat(ins.getValue().get(0)[0]).isEqualTo("deployment_history");
        assertThat(ins.getValue().get(0)[3]).isEqualTo(false);   // gerçek oluşturma anı
        assertThat(ins.getValue().get(0)[5]).isNotNull();        // last_change_at = şimdi
    }

    @Test
    @DisplayName("katalog yok (H2): pg_stat sorgusu patlar → sayaç izlenmez ama first_seen yazılır; hiçbir hata dışarı çıkmaz")
    void noCatalog_stillRecordsFirstSeen() {
        when(jdbc.queryForList(contains("pg_stat_user_tables"))).thenThrow(new RuntimeException("no such table"));
        when(jdbc.queryForList(contains("SELECT table_name, activity_counter FROM"))).thenReturn(List.of());
        assertThat(service.tick()).isEqualTo(2);
        when(jdbc.queryForList(contains("information_schema.tables"), eq(String.class))).thenThrow(new RuntimeException("db down"));
        assertThat(service.tick()).isZero();
    }

    @Test
    @DisplayName("all()/get(): satır sözleşmesi (first_seen_approx = bootstrap) ve iso() dönüşümleri")
    void allAndIso() {
        when(jdbc.queryForList(startsWith("SELECT table_name, first_seen_at"))).thenReturn(List.of(
                Map.of("table_name", "teams", "first_seen_at", "2026-09-11T00:00:00Z", "bootstrap", true,
                        "last_change_at", "2026-09-11T01:00:00Z")));
        Map<String, Object> row = service.get("teams");
        assertThat(row.get("first_seen_approx")).isEqualTo(true);
        assertThat(row.get("last_change_at")).isEqualTo("2026-09-11T01:00:00Z");
        assertThat(service.get("nope")).isNull();
        assertThat(SchemaTableRegistryService.iso(Timestamp.from(Instant.parse("2026-09-11T10:20:30Z")))).isEqualTo("2026-09-11T10:20:30Z");
        assertThat(SchemaTableRegistryService.iso("2026-01-01T00:00:00")).isEqualTo("2026-01-01T00:00:00");
        assertThat(SchemaTableRegistryService.iso(null)).isNull();
    }
}
