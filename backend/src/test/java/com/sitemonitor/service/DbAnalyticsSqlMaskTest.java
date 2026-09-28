package com.sitemonitor.service;

import com.sitemonitor.model.SqlQueryHistory;
import com.sitemonitor.repository.SqlQueryHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * KAPI (2026-09-28c, B2): Veritabanı Analitiği'nde SQL metni, hata iletisi ve çalıştıran kullanıcı adı yalnız global
 * admin + AUDIT'e. Payload GERÇEK {@link DbAnalyticsService#getOverview(int)} ile üretilir (Oyun Alanı geçmişi yolu ve
 * pg_stat_statements yolu); maskeli yük JSON'a çevrilip fixture metinlerinin HİÇBİR yerde geçmediği iddia edilir —
 * servise yeni bir SQL / kullanıcı adı taşıyan liste eklenirse bu test kırılır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DbAnalyticsSqlMaskTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SQL_A = "SELECT * FROM app_users WHERE email = 'kisi.a@example.com'";
    private static final String SQL_B = "SELECT gate_secret FROM gate_table";
    private static final String USER = "gate.kisi.a";
    private static final String ERR = "ERROR: permission denied for table gate_table [SQLSTATE: 42501]";
    private static final String PGSS = "SELECT gate_pgss_col FROM app_users WHERE id = $1";

    @Mock JdbcTemplate jdbcTemplate;
    @Mock SqlQueryHistoryRepository historyRepo;
    private DbAnalyticsService service;

    private static SqlQueryHistory q(String sql, long durMs, String error) {
        SqlQueryHistory h = new SqlQueryHistory();
        h.setExecutedBy(USER);
        h.setSqlText(sql);
        h.setDurationMs(durMs);
        h.setSuccess(error == null);
        h.setErrorMessage(error);
        h.setExecutedAt(ISO.format(Instant.now()));
        return h;
    }

    @BeforeEach
    void setUp() {
        service = new DbAnalyticsService(jdbcTemplate, historyRepo);
        when(historyRepo.findByExecutedAtGreaterThanEqualOrderByExecutedAtDesc(any(), any()))
                .thenReturn(List.of(q(SQL_A, 12, null), q(SQL_B, 900, ERR)));
    }

    private Map<String, Object> overview(boolean pgss) {
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Long.class))).thenReturn(pgss ? 1L : 0L);
        if (pgss) {
            when(jdbcTemplate.queryForList(contains("pg_stat_statements"))).thenAnswer(inv -> List.of(
                    new HashMap<>(Map.of("query", PGSS, "calls", 9L, "avg_ms", 1.5, "total_ms", 13L))));
        }
        return service.getOverview(7);
    }

    @Test
    @DisplayName("Oyun Alanı geçmişi yolu: maskeli yükte SQL / hata / kullanıcı adı YOK; sayılar, süreler, hata sınıfı + SQLSTATE kalır")
    @SuppressWarnings("unchecked")
    void historyPath_masked() {
        Map<String, Object> raw = overview(false);
        String visible = JSON.writeValueAsString(DbAnalyticsService.maskSqlText(raw, true));
        assertThat(visible).contains("gate_secret").contains(USER).contains("permission denied").contains("kisi.a@example.com");

        Map<String, Object> masked = DbAnalyticsService.maskSqlText(raw, false);
        String json = JSON.writeValueAsString(masked);
        for (String s : List.of("gate_secret", "gate_table", USER, "permission denied", "kisi.a@example.com", "app_users")) {
            assertThat(json).as("maskeli yükte %s kalmamalı", s).doesNotContain(s);
        }
        for (String f : DbAnalyticsService.SQL_TEXT_FIELDS) assertThat(json).doesNotContain("\"" + f + "\"");
        assertThat(masked).containsEntry(DbAnalyticsService.SQL_MASK_FLAG, true);
        Map<String, Object> failed = ((List<Map<String, Object>>) masked.get("failed")).get(0);
        assertThat(failed).containsEntry("error_kind", "denied").containsEntry("sql_state", "42501").containsKey("time");
        Map<String, Object> recent = ((List<Map<String, Object>>) masked.get("recent_queries")).get(0);
        assertThat(recent).containsKeys("duration_ms", "success", "time");
        assertThat(((Map<String, Object>) masked.get("summary")).get("user_count")).isEqualTo(1L);
        assertThat((List<?>) masked.get("top_users")).hasSize(1);   // satır kalır (sayılar), ad düşer
    }

    @Test
    @DisplayName("pg_stat_statements yolu (DB geneli normalize SQL) da aynı kurala tabi")
    void pgssPath_masked() {
        Map<String, Object> raw = overview(true);
        assertThat(JSON.writeValueAsString(raw)).contains("gate_pgss_col");   // pozitif kontrol
        String json = JSON.writeValueAsString(DbAnalyticsService.maskSqlText(raw, false));
        assertThat(json).doesNotContain("gate_pgss_col").contains("\"calls\":9");
    }

    @Test
    @DisplayName("maske paylaşılan önbellek nesnesini değiştirmez (kopya üzerinde)")
    void cachedOverviewIsNotMutated() {
        Map<String, Object> raw = overview(false);
        String before = JSON.writeValueAsString(raw);
        DbAnalyticsService.maskSqlText(raw, false);
        assertThat(JSON.writeValueAsString(raw)).isEqualTo(before);
    }

    @Test
    @DisplayName("errorKind / sqlState arayüzdeki dbModel kurallarıyla aynı")
    void errorClassification() {
        assertThat(DbAnalyticsService.errorKind("ERROR: canceling statement due to statement timeout")).isEqualTo("timeout");
        assertThat(DbAnalyticsService.errorKind("cannot execute UPDATE in a read-only transaction")).isEqualTo("readonly");
        assertThat(DbAnalyticsService.errorKind("syntax error at or near \"FORM\"")).isEqualTo("syntax");
        assertThat(DbAnalyticsService.errorKind("relation \"x\" does not exist")).isEqualTo("missing");
        assertThat(DbAnalyticsService.errorKind("boom")).isEqualTo("other");
        assertThat(DbAnalyticsService.errorKind("")).isNull();
        assertThat(DbAnalyticsService.sqlState("SQL state [42p01]")).isEqualTo("42P01");
        assertThat(DbAnalyticsService.sqlState("no code here")).isNull();
    }
}
