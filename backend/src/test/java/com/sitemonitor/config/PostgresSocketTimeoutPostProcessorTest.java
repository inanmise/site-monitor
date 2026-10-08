package com.sitemonitor.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI (2026-10-08, "zaman aşımı olmayan servis çağrısı" denetimi): pgjdbc {@code socketTimeout} varsayılanı 0 (sınırsız)
 * — sessizce düşen bir DB bağlantısı kullanımdaki sorguyu ve iş parçacığını sonsuza dek tutuyordu. İşlemci yalnız
 * PostgreSQL URL'sinde ve operatör kendi değerini vermemişken ekler; H2'de (testler) hiçbir şey yapmaz.
 */
class PostgresSocketTimeoutPostProcessorTest {

    private static HikariConfig cfg(String url) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(url);
        return c;
    }

    @Test
    @DisplayName("PostgreSQL URL'sine socketTimeout (sn) eklenir")
    void postgresUrl_getsSocketTimeout() {
        HikariConfig c = cfg("jdbc:postgresql://db:5432/sitemonitor");
        assertThat(PostgresSocketTimeoutPostProcessor.apply(c, 1800)).isTrue();
        assertThat(c.getDataSourceProperties().getProperty("socketTimeout")).isEqualTo("1800");
    }

    @Test
    @DisplayName("H2 (testler) ve diğer sürücüler: dokunulmaz — H2 bilinmeyen bağlantı ayarını reddederdi")
    void nonPostgres_untouched() {
        HikariConfig h2 = cfg("jdbc:h2:mem:testdb;MODE=PostgreSQL");
        assertThat(PostgresSocketTimeoutPostProcessor.apply(h2, 1800)).isFalse();
        assertThat(h2.getDataSourceProperties()).isEmpty();
        HikariConfig none = new HikariConfig();
        assertThat(PostgresSocketTimeoutPostProcessor.apply(none, 1800)).isFalse();
    }

    @Test
    @DisplayName("Operatörün değeri ASLA ezilmez (URL'de ya da data-source-properties'te); 0 = kapalı")
    void explicitValueOrDisabled_untouched() {
        HikariConfig inUrl = cfg("jdbc:postgresql://db:5432/x?socketTimeout=60");
        assertThat(PostgresSocketTimeoutPostProcessor.apply(inUrl, 1800)).isFalse();
        assertThat(inUrl.getDataSourceProperties()).isEmpty();

        HikariConfig inProps = cfg("jdbc:postgresql://db:5432/x");
        inProps.addDataSourceProperty("sockettimeout", "120");
        assertThat(PostgresSocketTimeoutPostProcessor.apply(inProps, 1800)).isFalse();
        assertThat(inProps.getDataSourceProperties().getProperty("sockettimeout")).isEqualTo("120");

        HikariConfig off = cfg("jdbc:postgresql://db:5432/x");
        assertThat(PostgresSocketTimeoutPostProcessor.apply(off, 0)).isFalse();
        assertThat(off.getDataSourceProperties()).isEmpty();
    }

    @Test
    @DisplayName("BeanPostProcessor: Hikari veri kaynağına ayardaki değeri uygular (havuz başlatılmadan); diğer bean'ler aynen döner")
    void postProcessor_appliesConfiguredValue_withoutStartingPool() {
        MockEnvironment env = new MockEnvironment().withProperty(PostgresSocketTimeoutPostProcessor.SETTING_KEY, "900");
        PostgresSocketTimeoutPostProcessor pp = new PostgresSocketTimeoutPostProcessor(env);
        try (HikariDataSource ds = new HikariDataSource()) {
            ds.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/never");
            assertThat(pp.postProcessBeforeInitialization(ds, "dataSource")).isSameAs(ds);
            assertThat(ds.getDataSourceProperties().getProperty("socketTimeout")).isEqualTo("900");
            assertThat(ds.isRunning()).as("havuz başlatılmamalı").isFalse();
        }
        Object other = new Object();
        assertThat(pp.postProcessBeforeInitialization(other, "x")).isSameAs(other);
        // Ayar yoksa varsayılan 30 dk.
        assertThat(new PostgresSocketTimeoutPostProcessor(new MockEnvironment()).seconds())
                .isEqualTo(PostgresSocketTimeoutPostProcessor.DEFAULT_SECONDS).isEqualTo(1800L);
    }
}
