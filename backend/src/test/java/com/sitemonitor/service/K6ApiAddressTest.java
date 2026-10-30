package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * k6 REST API adresi kuralı.
 *
 * <p>k6 2.0'dan ESKİ sürümler her koşumda {@code localhost:6565}'te bir REST API açıyordu; eşzamanlı koşumlar portta
 * çakıştığı için o sürümlere {@code --address 127.0.0.1:0} veriliyordu. k6 2.0+ API'yi varsayılanda AÇMAZ ve
 * {@code --address} vermek onu AÇAR (gereksiz bir dinleyen port — 2026-10-29 ölçüldü). 2026-10-30'dan (Yayın C) beri
 * desteklenen en eski sürüm 2.0 ({@link K6Profile#MIN_SUPPORTED_MAJOR}); bu kapı hiçbir k6 komutunun {@code --address}
 * eklemediğini ve eski ayarın ({@code site.monitor.scripted.k6-api-address}) okunmadığını denetler.
 */
class K6ApiAddressTest {

    private static final Path SERVICE =
            Path.of("src/main/java/com/sitemonitor/service/ScriptedCheckerService.java");

    @Test
    @DisplayName("Hiçbir k6 komutu --address eklemez (2.x'te REST API'yi açardı)")
    void noK6CommandAddsApiAddress() throws Exception {
        String src = Files.readString(SERVICE);
        assertThat(src).contains("\"run\", \"--quiet\"").contains("\"archive\", \"--quiet\"");
        assertThat(src).doesNotContain("args.add(\"--address\")")
                .doesNotContain("addApiAddress(")
                .doesNotContain("k6-api-address");
    }

    @Test
    @DisplayName("Eski k6-api-address ayarı katalogdan kalktı (DB'de kalan satır okunmaz)")
    void apiAddressSettingRemoved() {
        assertThat(AppSettingsCatalog.byKey("site.monitor.scripted.k6-api-address")).isNull();
    }
}
