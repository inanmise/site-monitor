package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saatlik rollup, günlük rollup ile AYNI hunidir — tek fark hedef tablo, kova kolonu ve kova
 * genişliğidir. Bu testin varlık sebebi: ham kontrol serileri kısaltıldığında (Faz 5) olayın
 * hangi SAATTE olduğu yalnız {@code monitor_check_hourly}'de kalır. İki yol zamanla ayrışırsa
 * (biri düzeltilip diğeri unutulursa) saatlik özet sessizce yanlış/eksik dolar ve bunu ham veri
 * silindikten SONRA fark ederiz — geri dönüşü yok. Metinsel eşitlik bu sürüklenmeyi derleme
 * zamanında yakalar.
 *
 * <p>Not: gerçek çalıştırma testi H2'de yapılamaz ({@code ON CONFLICT ... DO UPDATE} Postgres'e
 * özgüdür); doğrulama canlı Postgres üzerinde geriye-doldurma sonrası ham↔özet karşılaştırmasıyla
 * yapılır.
 */
class RollupSqlShapeTest {

    @Test
    @DisplayName("saatlik ve günlük upsert SQL'i yalnız hedef/kova/genişlikte ayrılır")
    void hourlyMatchesDailyExceptBucket() {
        String daily = SchedulerService.upsertSql(
                "monitor_check_daily", "day", 10, "PORT", "port_checks", "open", "response_ms");
        String hourly = SchedulerService.upsertSql(
                "monitor_check_hourly", "hour_bucket", 13, "PORT", "port_checks", "open", "response_ms");

        String normalized = hourly
                .replace("monitor_check_hourly", "monitor_check_daily")
                .replace("hour_bucket", "day")
                .replace("substr(checked_at,1,13)", "substr(checked_at,1,10)");
        assertThat(normalized).isEqualTo(daily);
    }

    @Test
    @DisplayName("uptime yolu da aynı şekilde tek farkla türetilir")
    void hourlyUptimeMatchesDaily() {
        String daily = SchedulerService.uptimeUpsertSql("monitor_check_daily", "day", 10);
        String hourly = SchedulerService.uptimeUpsertSql("monitor_check_hourly", "hour_bucket", 13);

        String normalized = hourly
                .replace("monitor_check_hourly", "monitor_check_daily")
                .replace("hour_bucket", "day")
                .replace("substr(checked_at,1,13)", "substr(checked_at,1,10)");
        assertThat(normalized).isEqualTo(daily);
    }

    @Test
    @DisplayName("upsert idempotenttir: çakışmada satır eklemez, ÜZERİNE yazar")
    void upsertIsIdempotent() {
        // Geriye-doldurma birden çok kez çalıştırılabilmeli (ör. yarıda kesilirse) — DO NOTHING
        // olsaydı ilk eksik çalıştırmanın kısmi sonucu kalıcılaşırdı.
        String sql = SchedulerService.upsertSql(
                "monitor_check_hourly", "hour_bucket", 13, "HTTP", "http_checks", "ok", "response_ms");
        assertThat(sql).contains("ON CONFLICT (monitor_type, monitor_key, hour_bucket) DO UPDATE SET");
        assertThat(sql).contains("total_checks = EXCLUDED.total_checks");
        assertThat(sql).doesNotContain("DO NOTHING");
    }

    @Test
    @DisplayName("kova genişliği 13 = YYYY-MM-DDTHH (saat), 10 = YYYY-MM-DD (gün)")
    void bucketWidthsMatchIsoPrefixes() {
        assertThat("2026-08-09T14:31:07".substring(0, 13)).isEqualTo("2026-08-09T14");
        assertThat("2026-08-09T14:31:07".substring(0, 10)).isEqualTo("2026-08-09");
    }
}
