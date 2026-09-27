package com.sitemonitor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.SpringProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI — test bağlam önbelleği sınırı GERÇEKTEN okunuyor (2026-09-27).
 *
 * <p>CI'da test fork'u "Java heap space" ile düşünce önbellek sınırı {@code META-INF/spring.properties}'e yazıldı —
 * {@link SpringProperties} ise yalnız sınıf yolunun KÖKÜNDEKİ {@code spring.properties}'i okur; ayar sessizce
 * etkisiz kaldı ve CI aynı hatayla bir tur daha kaybetti. Bu test, sınırın Spring'in gördüğü değerde olduğunu pinler.
 */
class SpringTestPropertiesTest {

    @Test
    @DisplayName("spring.test.context.cache.maxSize Spring tarafından okunuyor ve sınırlı")
    void contextCacheLimitIsEffective() {
        String v = SpringProperties.getProperty("spring.test.context.cache.maxSize");
        assertThat(v).as("spring.properties sınıf yolunun kökünde olmalı (META-INF değil)").isNotNull();
        assertThat(Integer.parseInt(v.trim())).isBetween(1, 16);
    }
}
