package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code findOne} / {@code findUser} TEK kayıt şartı (2026-09-26): AD'de aynı {@code cn} iki kayıtta
 * (ör. eski/pasif hesap başka OU'da) bulunursa ilk eşleşme KULLANILMAZ — müdür provizyonu yanlış
 * hesabın gruplarını yazmasın.
 */
class LdapDirectoryUniqueHitTest {

    @Test
    @DisplayName("yalnız tam olarak bir eşleşme geçerli; 0, 2+ ve sınır aşımı (null yer tutucu) geçersiz")
    void uniqueHit() {
        assertThat(LdapDirectoryService.uniqueHit(List.of("a"))).isTrue();
        assertThat(LdapDirectoryService.uniqueHit(List.of())).isFalse();
        assertThat(LdapDirectoryService.uniqueHit(List.of("a", "b"))).isFalse();
        assertThat(LdapDirectoryService.uniqueHit(Arrays.asList("a", null))).isFalse();
        assertThat(LdapDirectoryService.uniqueHit(null)).isFalse();
    }
}
