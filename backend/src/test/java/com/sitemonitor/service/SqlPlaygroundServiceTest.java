package com.sitemonitor.service;

import com.sitemonitor.model.SqlQueryHistory;
import com.sitemonitor.repository.SqlQueryHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SqlPlaygroundServiceTest {

    @Mock JdbcTemplate jdbc;
    @Mock SqlQueryHistoryRepository historyRepo;

    private SqlPlaygroundService service;

    @BeforeEach
    void setUp() {
        service = new SqlPlaygroundService(jdbc, historyRepo);
    }

    // ── execute ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SELECT returns rows + duration + records history")
    void execute_select_returnsRows() {
        List<Map<String, Object>> rows = List.of(Map.of("id", 1, "name", "alice"));
        when(jdbc.queryForList(contains("SELECT"))).thenReturn(rows);

        Map<String, Object> result = service.execute("SELECT id, name FROM teams", "n34567");

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("rowCount")).isEqualTo(1);
        assertThat((Long) result.get("durationMs")).isGreaterThanOrEqualTo(0L);
        verify(historyRepo).save(any(SqlQueryHistory.class));
    }

    @Test
    @DisplayName("UPDATE rejected — write keywords forbidden")
    void execute_update_rejected() {
        assertThatThrownBy(() -> service.execute("UPDATE teams SET name='x' WHERE id=1", "admin"))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(historyRepo);
    }

    @Test
    @DisplayName("DELETE rejected")
    void execute_delete_rejected() {
        assertThatThrownBy(() -> service.execute("DELETE FROM teams", "admin"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DROP rejected")
    void execute_drop_rejected() {
        assertThatThrownBy(() -> service.execute("DROP TABLE teams", "admin"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TRUNCATE rejected")
    void execute_truncate_rejected() {
        assertThatThrownBy(() -> service.execute("TRUNCATE teams", "admin"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Multi-statement rejected")
    void execute_multistatement_rejected() {
        assertThatThrownBy(() -> service.execute("SELECT 1; DELETE FROM teams", "admin"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("statement");
    }

    @Test
    @DisplayName("WITH CTE pure SELECT allowed")
    void execute_withCte_allowed() {
        when(jdbc.queryForList(contains("WITH"))).thenReturn(List.of());
        service.execute("WITH t AS (SELECT 1 AS x) SELECT * FROM t", "admin");
        verify(jdbc).queryForList(anyString());
    }

    @Test
    @DisplayName("WITH CTE containing DML rejected")
    void execute_withCteDml_rejected() {
        assertThatThrownBy(() -> service.execute(
            "WITH t AS (DELETE FROM teams RETURNING *) SELECT * FROM t", "admin"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Missing LIMIT auto-appends LIMIT 1000")
    void execute_autoLimitAppended() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        service.execute("SELECT * FROM teams", "admin");

        verify(jdbc).queryForList(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue()).containsIgnoringCase("limit 1000");
    }

    @Test
    @DisplayName("LIMIT > 1000 capped to 1000")
    void execute_limitExceeds_capped() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        service.execute("SELECT * FROM teams LIMIT 5000", "admin");

        verify(jdbc).queryForList(sqlCaptor.capture());
        String executed = sqlCaptor.getValue();
        assertThat(executed).containsIgnoringCase("LIMIT 1000");
        assertThat(executed).doesNotContain("5000");
    }

    @Test
    @DisplayName("History recorded on SQL failure too")
    void execute_recordsHistory_onFailure() {
        when(jdbc.queryForList(anyString())).thenThrow(new DataAccessException("boom") {});

        Map<String, Object> result = service.execute("SELECT 1/0", "admin");

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("boom");
        verify(historyRepo).save(any(SqlQueryHistory.class));
    }

    @Test
    @DisplayName("Empty SQL rejected")
    void execute_empty_rejected() {
        assertThatThrownBy(() -> service.execute("   ", "admin"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("boş");
    }

    @Test
    @DisplayName("Line comments stripped before validation")
    void execute_lineCommentsStripped() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        service.execute("-- only a comment line\nSELECT 1", "admin");
        verify(jdbc).queryForList(anyString());
    }

    @Test
    @DisplayName("Trailing semicolon allowed (single statement)")
    void execute_trailingSemi_allowed() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        service.execute("SELECT 1;", "admin");
        verify(jdbc).queryForList(anyString());
    }

    @Test
    @DisplayName("Yasak kelimeyi İÇEREN sütun adları reddedilmez (kelime sınırı)")
    void execute_columnNamesContainingKeywords_allowed() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        // insertion_date/updated_at/created_at → insert/update/create alt-dizisi içerir
        // ama \b sınırı nedeniyle FORBIDDEN eşleşmemeli (yanlış pozitif olmamalı).
        service.execute("SELECT insertion_date, updated_at, created_at FROM teams", "admin");
        verify(jdbc).queryForList(anyString());
    }

    // ── listTables / listColumns ──────────────────────────────────────────────

    @Test
    @DisplayName("listTables queries information_schema")
    void listTables_queries() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("table_name", "teams")));
        List<Map<String, Object>> result = service.listTables();
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("listColumns rejects invalid identifier")
    void listColumns_invalidName_rejected() {
        assertThatThrownBy(() -> service.listColumns("teams; DROP TABLE x"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("listColumns accepts valid identifier")
    void listColumns_validName_returns() {
        when(jdbc.queryForList(anyString(), eq("teams")))
            .thenReturn(List.of(Map.of("column_name", "id")));
        List<Map<String, Object>> result = service.listColumns("teams");
        assertThat(result).hasSize(1);
    }
}
