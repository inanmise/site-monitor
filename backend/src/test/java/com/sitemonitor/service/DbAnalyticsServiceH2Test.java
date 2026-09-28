package com.sitemonitor.service;

import com.sitemonitor.repository.SqlQueryHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DbAnalyticsService GERÇEK bir JDBC sürücüsüyle, pg_* kataloğu OLMAYAN bir veritabanında (H2, PostgreSQL uyumluluk
 * modu — testlerin ve yetkisiz rolün durumu). Mock JdbcTemplate yalnız "istisna fırlatırsa" yolunu sınar; burada
 * sürücünün kendi hatası (tablo yok, sözdizimi farkı) servisten sızmıyor mu, sonuç "bilinmiyor" (null) mu, ona bakılır.
 */
class DbAnalyticsServiceH2Test {

    @Test
    @DisplayName("H2 (pg_stat_* yok): getOverview patlamaz; pg kaynakları null = bilinmiyor, yanıt süresi yine ölçülür")
    @SuppressWarnings("unchecked")
    void getOverview_withoutPgCatalog_degradesToUnknown() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:dbanalytics_h2;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        SqlQueryHistoryRepository repo = mock(SqlQueryHistoryRepository.class);
        when(repo.findByExecutedAtGreaterThanEqualOrderByExecutedAtDesc(any(), any())).thenReturn(List.of());
        DbAnalyticsService service = new DbAnalyticsService(new JdbcTemplate(ds), repo);

        Map<String, Object> d = assertDoesNotThrow(() -> service.getOverview(7));
        Map<String, Object> sum = (Map<String, Object>) d.get("summary");
        Map<String, Object> conn = (Map<String, Object>) d.get("connections");

        assertThat((Boolean) sum.get("pgss")).isFalse();
        assertThat(d.get("db_stats")).isNull();
        assertThat(conn.get("states")).isNull();
        assertThat(conn.get("active")).isNull();
        assertThat(sum.get("table_count")).isNull();
        assertThat((List<?>) d.get("table_sizes")).isEmpty();
        assertThat(((Number) conn.get("response_ms")).longValue()).isGreaterThanOrEqualTo(0L);   // SELECT 1 H2'de de çalışır
        assertThat((List<?>) d.get("series")).hasSize(7);
    }
}
