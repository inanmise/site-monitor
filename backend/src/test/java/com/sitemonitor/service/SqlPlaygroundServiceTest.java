package com.sitemonitor.service;

import com.sitemonitor.model.SqlQueryHistory;
import com.sitemonitor.repository.SqlQueryHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        service.execute("WITH t AS (SELECT 1 AS x) SELECT * FROM t", "admin");
        // "Izin verildi" yetmez: HANGI SQL kostugu da dogrulanmali. Eskiden yalniz
        // `verify(jdbc).queryForList(anyString())` vardi — sanitizasyon SELECT'i bozsa,
        // yanlis ifadeyi calistirsa ya da DIS TAVANI atlasa test yine yesil kalirdi.
        verify(jdbc).queryForList(cap.capture());
        assertThat(cap.getValue()).contains("WITH t AS").contains("SELECT * FROM t").endsWith("LIMIT 1000");
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
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        service.execute("-- only a comment line\nSELECT 1", "admin");
        // "Izin verildi" yetmez: HANGI SQL kostugu da dogrulanmali. Eskiden yalniz
        // `verify(jdbc).queryForList(anyString())` vardi — sanitizasyon SELECT'i bozsa,
        // yanlis ifadeyi calistirsa ya da DIS TAVANI atlasa test yine yesil kalirdi.
        verify(jdbc).queryForList(cap.capture());
        // Yorum satiri ATILIR, SELECT KALIR — yorumla birlikte sorgu da silinseydi bos/bozuk
        // bir ifade calisirdi ve eski iddia bunu goremezdi.
        assertThat(cap.getValue()).contains("SELECT 1").doesNotContain("only a comment line");
    }

    @Test
    @DisplayName("Trailing semicolon allowed (single statement)")
    void execute_trailingSemi_allowed() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        service.execute("SELECT 1;", "admin");
        // "Izin verildi" yetmez: HANGI SQL kostugu da dogrulanmali. Eskiden yalniz
        // `verify(jdbc).queryForList(anyString())` vardi — sanitizasyon SELECT'i bozsa,
        // yanlis ifadeyi calistirsa ya da DIS TAVANI atlasa test yine yesil kalirdi.
        verify(jdbc).queryForList(cap.capture());
        // Sondaki `;` atilir — birakilsaydi sarmalanan ifade sozdizimi hatasi verirdi.
        assertThat(cap.getValue()).contains("SELECT 1").doesNotContain("1;");
    }

    @Test
    @DisplayName("Yasak kelimeyi İÇEREN sütun adları reddedilmez (kelime sınırı)")
    void execute_columnNamesContainingKeywords_allowed() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        // insertion_date/updated_at/created_at → insert/update/create alt-dizisi içerir
        // ama \b sınırı nedeniyle FORBIDDEN eşleşmemeli (yanlış pozitif olmamalı).
        service.execute("SELECT insertion_date, updated_at, created_at FROM teams", "admin");
        // "Izin verildi" yetmez: HANGI SQL kostugu da dogrulanmali. Eskiden yalniz
        // `verify(jdbc).queryForList(anyString())` vardi — sanitizasyon SELECT'i bozsa,
        // yanlis ifadeyi calistirsa ya da DIS TAVANI atlasa test yine yesil kalirdi.
        verify(jdbc).queryForList(cap.capture());
        // Sutun adlari BOZULMADAN gecmeli: sanitizasyon "insert" alt-dizisini kirpsaydi
        // sorgu sessizce baska bir sey calistirirdi.
        assertThat(cap.getValue())
                .contains("insertion_date").contains("updated_at").contains("created_at");
    }

    // ── listTables / listColumns ──────────────────────────────────────────────

    // ── Tehlikeli fonksiyon engeli (SELECT-only kuralının YAKALAYAMADIĞI yüzey) ──

    /**
     * Bu sorguların hepsi geçerli SELECT'tir ve DML/DDL anahtar kelimesi İÇERMEZ — yani
     * SELECT-only kuralını ve FORBIDDEN listesini sorunsuz geçerler. Onları durduran tek şey
     * FORBIDDEN_FUNCTIONS listesidir ve o liste hiçbir testle korunmuyordu.
     */
    @ParameterizedTest(name = "yasak fonksiyon reddedilir: {0}")
    @ValueSource(strings = {
            "SELECT pg_read_file('/etc/passwd')",                       // sunucu dosyası okuma
            "SELECT pg_read_binary_file('/etc/shadow')",
            "SELECT pg_ls_dir('/')",                                    // dizin listeleme
            "SELECT pg_stat_file('/etc/hostname')",
            "SELECT lo_import('/etc/passwd')",                          // large-object
            "SELECT lo_export(1, '/tmp/x')",
            "SELECT dblink_connect('host=10.0.0.1 user=x')",            // iç-ağ bağlantısı
            "SELECT * FROM dblink('dbname=x', 'SELECT 1') AS t(a int)",
            "SELECT pg_sleep(30)",                                      // kaynak tüketimi
            "SELECT pg_terminate_backend(123)",                         // backend kontrolü
            "SELECT pg_cancel_backend(123)",
            "SELECT pg_reload_conf()",
            "SELECT set_config('x', 'y', false)",
    })
    void execute_forbiddenFunctions_rejected(String sql) {
        assertThatThrownBy(() -> service.execute(sql, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        // Reddedilen sorgu veritabanına HİÇ gitmez.
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest(name = "büyük/küçük harf ve boşluk kaçamağı işe yaramaz: {0}")
    @ValueSource(strings = {
            "SELECT PG_READ_FILE('/etc/passwd')",
            "select Pg_Sleep(10)",
            "SELECT   pg_ls_dir  ( '/' )",
    })
    void execute_forbiddenFunctions_caseAndSpacingEvasionFails(String sql) {
        assertThatThrownBy(() -> service.execute(sql, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Kelime sınırı: adında yasak fonksiyon GEÇEN sütun reddedilmez")
    void execute_columnNamedLikeForbiddenFunction_allowed() {
        // Engel listesi kelime sınırıyla eşleşir; "my_pg_sleep_log" gibi meşru bir ad
        // reddedilirse kullanıcı sebebini anlayamaz. FORBIDDEN listesinde aynı güvence
        // zaten test edilmiş; fonksiyon listesi için de aynısı geçerli olmalı.
        when(jdbc.queryForList(contains("SELECT"))).thenReturn(List.of());

        Map<String, Object> r = service.execute("SELECT my_pg_sleep_log FROM audit_log", "admin");

        assertThat(r.get("ok")).isEqualTo(true);
    }

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

    // ── Denetim 5. tur, bulgu 9 + 33 ──────────────────────────────────────────

    @Test
    @DisplayName("Bulgu 9: SELECT ... INTO reddedilir — SELECT ile baslayan bir DDL+DML'dir")
    void execute_selectInto_rejected() {
        assertThatThrownBy(() -> service.execute("SELECT * INTO yeni_tablo FROM teams", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, never()).queryForList(anyString());
    }

    @Test
    @DisplayName("Bulgu 9: 'into' kelimesi sutun adinin PARCASI ise engellenmez (yanlis pozitif yok)")
    void execute_columnContainingInto_allowed() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        service.execute("SELECT into_date FROM teams", "admin");
        // "Izin verildi" yetmez: HANGI SQL kostugu da dogrulanmali. Eskiden yalniz
        // `verify(jdbc).queryForList(anyString())` vardi — sanitizasyon SELECT'i bozsa,
        // yanlis ifadeyi calistirsa ya da DIS TAVANI atlasa test yine yesil kalirdi.
        verify(jdbc).queryForList(cap.capture());
        assertThat(cap.getValue()).contains("into_date").endsWith("LIMIT 1000");
    }

    @Test
    @DisplayName("Bulgu 33: IC LIMIT tavanin ALTINDA olsa da DIS tavan eklenir")
    void execute_innerLimitBelowCap_stillWrapped() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);

        service.execute("SELECT * FROM a, b WHERE a.id IN (SELECT id FROM c LIMIT 5)", "admin");

        verify(jdbc).queryForList(cap.capture());
        String executed = cap.getValue();
        assertThat(executed)
                .as("dis tavan yoksa kartezyen carpim milyonlarca satiri bellege alir: %s", executed)
                .startsWith("SELECT * FROM (")
                .endsWith("LIMIT 1000");
    }

    // ── Kod incelemesi 2026-09-09: paylaşılan JdbcTemplate'e dokunulmaz ───────

    @Test
    @DisplayName("execute PAYLAŞILAN JdbcTemplate'in zaman aşımını DEĞİŞTİRMEZ (retention/scheduler 30 sn tavana düşmesin)")
    void execute_doesNotMutateSharedJdbcTemplate() {
        when(jdbc.queryForList(contains("SELECT"))).thenReturn(List.of());
        service.execute("SELECT 1", "n1");
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).setQueryTimeout(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("DataSource varsa oyun alanı kendi ayrı, 30 sn zaman aşımlı JdbcTemplate örneğini kullanır (tek sefer kurulur)")
    void playgroundTemplate_isSeparateTimedInstance() {
        javax.sql.DataSource ds = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        when(jdbc.getDataSource()).thenReturn(ds);

        JdbcTemplate t1 = service.playgroundTemplate();
        JdbcTemplate t2 = service.playgroundTemplate();

        assertThat(t1).isNotSameAs(jdbc).isSameAs(t2);
        assertThat(t1.getQueryTimeout()).isEqualTo(30);
        assertThat(t1.getDataSource()).isSameAs(ds);
    }
}
