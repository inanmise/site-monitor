package com.sitemonitor.service.monitorcsv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bağımsız inceleme (2026-11-19): uçların 2xx yanıtındaki uyarılar (k6 kayıt uyarıları, dbquery {@code warnings} ve
 * {@code plan_warning} WARN) satır sonucuna taşınır — düşürülmez; sınırlı (≤ 3 madde, ≤ 600 karakter).
 */
class MonitoringControllerCsvWriterTest {

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    @Test
    @DisplayName("k6 metin uyarıları ve dbquery {code, message} uyarıları + WARN maliyet uyarısı tek metne")
    void warningsCarried() {
        MonitorCsvWriter.Result k6 = MonitoringControllerCsvWriter.fromResponse(ok(Map.of("id", 7,
                "warnings", List.of("Betik dosya okuyor: open()"))));
        assertThat(k6.ok()).isTrue();
        assertThat(k6.id()).isEqualTo(7L);
        assertThat(k6.message()).isEqualTo("Betik dosya okuyor: open()");

        Map<String, Object> dbq = new LinkedHashMap<>();
        dbq.put("id", 9);
        dbq.put("plan_warning", Map.of("verdict", "WARN", "message", "Sorgu maliyeti yüksek"));
        dbq.put("warnings", List.of(Map.of("code", "SELECT_STAR", "message", "SELECT * kullanılıyor")));
        assertThat(MonitoringControllerCsvWriter.fromResponse(ok(dbq)).message())
                .isEqualTo("Sorgu maliyeti yüksek · SELECT * kullanılıyor");
    }

    @Test
    @DisplayName("OK maliyet kararı ve boş uyarı listesi metin üretmez; uzun metin sınırlanır, madde sayısı 3")
    void warningsBounded() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", 1);
        d.put("plan_warning", Map.of("verdict", "OK", "message", "uygun"));
        d.put("warnings", List.of());
        assertThat(MonitoringControllerCsvWriter.fromResponse(ok(d)).message()).isNull();
        assertThat(MonitoringControllerCsvWriter.fromResponse(ok(Map.of("id", 2))).message()).isNull();

        String text = MonitoringControllerCsvWriter.warnings(Map.of("warnings",
                List.of("a".repeat(400), "b".repeat(400), "c", "d", "e")));
        assertThat(text).hasSize(MonitoringControllerCsvWriter.WARNING_MAX_CHARS).endsWith("…");
        assertThat(MonitoringControllerCsvWriter.warnings(Map.of("warnings", List.of("1", "2", "3", "4"))))
                .isEqualTo("1 · 2 · 3");
    }
}
