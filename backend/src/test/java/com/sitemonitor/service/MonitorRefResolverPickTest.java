package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alarm Geçmişi satırının izleme adı (2026-11-19, yayın sonrası düzeltme): seçim yalnız "ilk aday" geri dönüşüyle
 * yapıldıysa (birden çok aday, bağlam kimliği / damga / envanter takımı eşleşmedi) belirsiz sayılır — yanlış izleme
 * adlandırılmasın.
 */
class MonitorRefResolverPickTest {

    private static final List<Object[]> TWO = List.of(new Object[]{ "Giriş akışı", 1L, 9L }, new Object[]{ "Giriş akışı", 2L, 5L });

    @Test
    @DisplayName("ilk aday geri dönüşü yalnız birden çok aday ve hiçbir eşleşme yokken belirsiz")
    void pickedByFallbackRule() {
        // tek aday → belirsizlik yok
        assertThat(MonitorRefResolver.pickedByFallback(List.<Object[]>of(new Object[]{ "x", 1L, 9L }), null, null, Set.of())).isFalse();
        assertThat(MonitorRefResolver.pickedByFallback(null, null, null, Set.of())).isFalse();
        // iki aday, hiçbir eşleşme → belirsiz
        assertThat(MonitorRefResolver.pickedByFallback(TWO, null, null, Set.of())).isTrue();
        assertThat(MonitorRefResolver.pickedByFallback(TWO, 99L, null, Set.of(7L))).isTrue();
        // bağlam kimliği adaylardan biri → kesin
        assertThat(MonitorRefResolver.pickedByFallback(TWO, 2L, null, Set.of())).isFalse();
        // damgalı olay → takım kuralı kesin (eşleşme yoksa seçim zaten null)
        assertThat(MonitorRefResolver.pickedByFallback(TWO, null, 5L, Set.of())).isFalse();
        // envanter takımı adaylardan birinin takımı → kesin
        assertThat(MonitorRefResolver.pickedByFallback(TWO, null, null, Set.of(5L))).isFalse();
        // pickMonitor ile aynı sıra: belirsizken seçilen ilk aday
        assertThat(MonitorRefResolver.pickMonitor(TWO, null, null, Set.of())[1]).isEqualTo(1L);
    }

    @Test
    @DisplayName("Pick: eski 5 bileşenli kurucu kesin seçim üretir")
    void legacyConstructorIsCertain() {
        assertThat(new MonitorRefResolver.Pick("a", "http", "http", 1L, 2L).certain()).isTrue();
        assertThat(new MonitorRefResolver.Pick("a", "http", "http", 1L, 2L, false).certain()).isFalse();
    }
}
