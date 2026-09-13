package com.sitemonitor.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: ISSUE-001 — toplu envanter ucunun {@code @CacheEvict}'i, araya giren bir javadoc yüzünden
 * özel yardımcı {@code applyBulkContacts}'a kaymıştı (2026-08-25). Spring'in cache proxy'si özel/sınıf-içi
 * çağrıyı yakalamaz → toplu pasifleştirme/silme/kademe sonrası sertifika listesi 5 dk bayat kalıyordu.
 * Found by /qa on 2026-09-13
 * Report: .gstack/qa-reports/qa-report-localhost-2026-09-13-r2.md
 *
 * <p>Kapı: cache ek'i taşıyan HER metot public olmalı (proxy ancak public metodu sarar) ve
 * {@code bulkInventoryAction} eviction taşımalı.
 */
class CacheEvictPlacementRegressionTest {

    private static final List<Class<?>> CONTROLLERS = List.of(
            AdminController.class, CertificateController.class);

    @Test
    @DisplayName("KAPI: @CacheEvict/@Cacheable/@Caching yalnız PUBLIC metotta durur (özel metotta proxy çalışmaz)")
    void cacheAnnotationsOnlyOnPublicMethods() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> c : CONTROLLERS) {
            for (Method m : c.getDeclaredMethods()) {
                boolean annotated = m.isAnnotationPresent(CacheEvict.class) || m.isAnnotationPresent(Cacheable.class)
                        || m.isAnnotationPresent(Caching.class);
                if (annotated && !Modifier.isPublic(m.getModifiers())) {
                    offenders.add(c.getSimpleName() + "#" + m.getName());
                }
            }
        }
        assertThat(offenders).as("cache ek'i özel metotta — proxy devreye girmez, eviction sessizce kaybolur").isEmpty();
    }

    @Test
    @DisplayName("POST /admin/inventory/bulk cert cache'lerini boşaltır (deactivate/delete/set-tier/set-team sonrası liste taze)")
    void bulkInventoryActionEvictsCertificateCaches() throws Exception {
        Method m = AdminController.class.getMethod("bulkInventoryAction",
                java.util.Map.class, jakarta.servlet.http.HttpSession.class, jakarta.servlet.http.HttpServletRequest.class);
        CacheEvict evict = m.getAnnotation(CacheEvict.class);
        assertThat(evict).isNotNull();
        assertThat(evict.value()).contains("cert-latest", "cert-warnings", "cert-stats", "renewal-advice");
        assertThat(evict.allEntries()).isTrue();
    }
}
