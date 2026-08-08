package com.sitemonitor.service.retention;

import com.sitemonitor.model.RetentionRun;
import com.sitemonitor.repository.RetentionRunItemRepository;
import com.sitemonitor.repository.RetentionRunRepository;
import com.sitemonitor.service.AppSettingsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetentionServiceTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock AppSettingsService appSettings;
    @Mock RetentionRunRepository runRepo;
    @Mock RetentionRunItemRepository itemRepo;

    RetentionService service;

    @BeforeEach
    void setUp() {
        service = new RetentionService(jdbcTemplate, appSettings, runRepo, itemRepo,
                new RetentionMetrics(new SimpleMeterRegistry()));
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(runRepo.save(any())).thenAnswer(i -> { RetentionRun r = i.getArgument(0); r.setId(1L); return r; });
    }

    @Test
    @DisplayName("EN KRİTİK: dry-run HİÇBİR satır silmez — yalnız COUNT çalışır")
    void dryRunNeverDeletes() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), (Object[]) any())).thenReturn(42L);

        var run = service.dryRun();

        assertThat(run.dryRun()).isTrue();
        assertThat(run.totalRows()).isPositive();
        // Tek bir DELETE bile üretilmemeli.
        verify(jdbcTemplate, never()).update(anyString(), (Object[]) any());
        verify(jdbcTemplate, never()).update(anyString());
        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    @DisplayName("Legal hold açıkken hiçbir sorgu çalışmaz ve tüm politikalar 'legal-hold' ile atlanır")
    void legalHoldStopsEverything() {
        when(appSettings.getBoolean(eq(RetentionCatalog.HOLD_KEY), anyBoolean())).thenReturn(true);

        var run = service.runCleanup();

        assertThat(run.holdActive()).isTrue();
        assertThat(run.totalRows()).isZero();
        assertThat(run.items()).isNotEmpty()
                .allMatch(i -> "legal-hold".equals(i.skipped()));
        verify(jdbcTemplate, never()).update(anyString(), (Object[]) any());
    }

    @Test
    @DisplayName("Batch'li silme: dilim < batch dönene dek döner, sonra ANALYZE (toplam doğru)")
    void batchedDeleteLoopsThenAnalyze() {
        when(jdbcTemplate.update(contains("port_checks"), (Object[]) any())).thenReturn(10000, 10000, 3000);
        var policy = RetentionCatalog.byId("series-port").orElseThrow();

        var item = service.runOne(policy, false);

        assertThat(item.rows()).isEqualTo(23000);
        verify(jdbcTemplate, times(3)).update(contains("port_checks"), (Object[]) any());
        verify(jdbcTemplate).execute("ANALYZE port_checks");
    }

    @Test
    @DisplayName("incident_records opt-in kapalıyken (0 gün) hiç sorgu çalışmaz")
    void optInPolicySkippedWhenZero() {
        var policy = RetentionCatalog.byId("incident-records").orElseThrow();
        assertThat(service.effectiveDays(policy)).isZero();

        var item = service.runOne(policy, false);

        assertThat(item.skipped()).isEqualTo("opt-in-kapali");
        assertThat(item.rows()).isZero();
        verify(jdbcTemplate, never()).update(anyString(), (Object[]) any());
    }

    @Test
    @DisplayName("Taban sınırı: ayar minDays'in altına inse bile etkin değer tabana kırpılır")
    void minDaysFloorIsEnforced() {
        var series = RetentionCatalog.byId("series-ping").orElseThrow();
        when(appSettings.getInt(eq(series.settingKey()), anyInt())).thenReturn(3);   // taban 30

        assertThat(service.effectiveDays(series)).isEqualTo(series.minDays());
    }

    @Test
    @DisplayName("Bir politikanın hatası diğerlerini DURDURMAZ; hata satır bazında kaydedilir")
    void oneFailureDoesNotAbortTheRun() {
        when(jdbcTemplate.update(contains("audit_log"), (Object[]) any()))
                .thenThrow(new RuntimeException("tablo kilitli"));
        when(jdbcTemplate.update(argThat(s -> s != null && !s.contains("audit_log")), (Object[]) any())).thenReturn(0);

        var run = service.runCleanup();

        assertThat(run.failedCount()).isEqualTo(1);
        assertThat(run.items()).anyMatch(i -> "audit-log".equals(i.policyId()) && i.failed());
        // Diğer politikalar yine koşmuş olmalı.
        assertThat(run.items()).hasSize(RetentionCatalog.executable().size());
    }

    @Test
    @DisplayName("Öksüz temizliği parametresiz çalışır (cutoff yok)")
    void orphanRuleUsesNoParameters() {
        var policy = RetentionCatalog.byId("alert-comments-orphan").orElseThrow();
        assertThat(policy.paramCount()).isZero();
        assertThat(service.cutoffFor(policy)).isNull();

        service.runOne(policy, false);

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture());       // parametresiz overload
        assertThat(sql.getValue())
                .contains("DELETE FROM alert_comments")
                .contains("NOT IN (SELECT id FROM alert_events)")
                .doesNotContain("?");   // cutoff parametresi yok
    }

    @Test
    @DisplayName("Kontrol Geçmişi kırpması katalogdan okunur (ekran ile silme aynı kaynaktan)")
    void historyRetentionComesFromCatalog() {
        when(appSettings.getInt(eq("site.monitor.series.ping.retention-days"), anyInt())).thenReturn(45);

        assertThat(service.historyRetentionDays("ping", 180)).isEqualTo(45);
        assertThat(service.historyRetentionDays("bilinmeyen", 180)).isEqualTo(180);
    }
}
