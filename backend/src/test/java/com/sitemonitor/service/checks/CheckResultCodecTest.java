package com.sitemonitor.service.checks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod Sürüm 3-3 P0 — şekilli sonucun JSON kodlaması ({@link CheckResultCodec}), işaret haritaları
 * ({@link CheckJobRunner}) ve satır kayıtlarının günlüğe sonuç sızdırmaması.
 */
class CheckResultCodecTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("gidiş-dönüş: elle kontrol gelen kutusuyla aynı Jackson kuralı — sayı / mantıksal / null / liste / iç harita, anahtar sırası korunur")
    void roundTrip() {
        Map<String, Object> r = map("status", "down", "error", null, "response_ms", 1234L, "http_status", 503,
                "na", false, "problem_pages", List.of("https://a/x", "https://a/y"), "nested", map("k", 1.5),
                "checked_at", "2026-10-29T10:00:00");
        CheckResultCodec.Encoded e = CheckResultCodec.encode(r);
        assertThat(e.truncated()).isFalse();
        Map<String, Object> back = CheckResultCodec.decode(e.json());
        assertThat(back.keySet()).containsExactlyElementsOf(r.keySet());
        assertThat(back).containsEntry("status", "down").containsEntry("error", null)
                .containsEntry("http_status", 503).containsEntry("na", false)
                .containsEntry("checked_at", "2026-10-29T10:00:00");
        assertThat(((Number) back.get("response_ms")).longValue()).isEqualTo(1234L);
        assertThat(back.get("problem_pages")).isEqualTo(List.of("https://a/x", "https://a/y"));
        assertThat(back.get("nested")).isEqualTo(map("k", 1.5));
        assertThat(e.toString()).as("kodlanmış sonucun toString'i metni yazmaz").doesNotContain("https://a/x");
    }

    @Test
    @DisplayName("boyut: 256 K'yı aşan sonuçta uzun metinler 4 K'ya kırpılır (truncated); yine sığmazsa failed=ResultTooLarge")
    void sizeCap() {
        String big = "ç".repeat(300_000);
        CheckResultCodec.Encoded e = CheckResultCodec.encode(map("status", "up", "output_tail", big));
        assertThat(e.truncated()).isTrue();
        assertThat(e.json().length()).isLessThanOrEqualTo(CheckResultCodec.MAX_RESULT_CHARS);
        Map<String, Object> back = CheckResultCodec.decode(e.json());
        assertThat(back).containsEntry("status", "up");
        assertThat((String) back.get("output_tail")).hasSize(CheckResultCodec.CLIP_CHARS + 1).endsWith("…");
        assertThat(CheckJobRunner.carriesResult(back)).isTrue();

        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) many.add("y".repeat(CheckResultCodec.CLIP_CHARS));
        CheckResultCodec.Encoded tooBig = CheckResultCodec.encode(map("rows", many));
        assertThat(tooBig.truncated()).isTrue();
        Map<String, Object> marker = CheckResultCodec.decode(tooBig.json());
        assertThat(marker).containsEntry(CheckJobRunner.KEY_FAILED, CheckResultCodec.TOO_LARGE);
        assertThat(CheckJobRunner.carriesResult(marker)).isFalse();
    }

    @Test
    @DisplayName("null sonuç / boş / bozuk JSON asla fırlatmaz: null → failed=NullResult, boş → boş harita, bozuk → failed=ResultUnreadable")
    void neverThrows() {
        assertThat(CheckResultCodec.decode(CheckResultCodec.encode(null).json()))
                .containsEntry(CheckJobRunner.KEY_FAILED, "NullResult");
        assertThat(CheckResultCodec.decode(null)).isEmpty();
        assertThat(CheckResultCodec.decode("  ")).isEmpty();
        assertThat(CheckResultCodec.decode("{bozuk")).containsEntry(CheckJobRunner.KEY_FAILED, CheckResultCodec.UNREADABLE);
        Map<String, Object> m = CheckResultCodec.decode("{\"a\":1}");
        m.put("b", 2);   // değiştirilebilir kopya
        assertThat(m).containsEntry("b", 2);
    }

    @Test
    @DisplayName("işaretler: failed (yalnız sınıf adı, ileti YOK), worker_timeout, inactive kalem üretmez; gerçek sonuç üretir")
    void markers() {
        Map<String, Object> f = CheckJobRunner.failed(new IllegalStateException("gizli parola=hunter2"));
        assertThat(f).containsExactly(Map.entry(CheckJobRunner.KEY_FAILED, "IllegalStateException"));
        assertThat(CheckResultCodec.encode(f).json()).doesNotContain("hunter2");
        assertThat(CheckJobRunner.failed(null)).containsEntry(CheckJobRunner.KEY_FAILED, "Unknown");
        assertThat(CheckJobRunner.carriesResult(f)).isFalse();
        assertThat(CheckJobRunner.carriesResult(CheckJobRunner.workerTimeout())).isFalse();
        assertThat(CheckJobRunner.carriesResult(CheckJobRunner.inactive())).isFalse();
        assertThat(CheckJobRunner.carriesResult(null)).isFalse();
        assertThat(CheckJobRunner.carriesResult(Map.of())).isFalse();
        assertThat(CheckJobRunner.carriesResult(map("status", "up", "error", null))).isTrue();
        assertThat(CheckJobRunner.carriesResult(map("status", "skipped", "worker_timeout", false))).isTrue();
    }

    @Test
    @DisplayName("satır kayıtları: CheckResultRow.toString sonucu yazmaz; yaş bitişten (yoksa oluşturmadan); anahtar \"tür:izleme\"")
    void rowsNeverLeakResult() {
        CheckResultRow r = new CheckResultRow(9L, "page", 77L, 1_000L, 2_000L, 5_000L, "pod-a-1",
                "{\"problem_rows\":\"GİZLİ SAYFA METNİ\"}", false);
        assertThat(r.toString()).doesNotContain("GİZLİ").contains("id=9").contains("page");
        assertThat(r.ageMs(6_500L)).isEqualTo(1_500L);
        assertThat(new CheckResultRow(9L, "page", 77L, 1_000L, null, null, null, null, false).ageMs(6_500L)).isEqualTo(5_500L);
        assertThat(r.ageMs(0L)).isZero();
        assertThat(r.monitorKey()).isEqualTo("page:77");
        assertThat(r.result()).containsEntry("problem_rows", "GİZLİ SAYFA METNİ");
        CheckJobStore.Completion c = new CheckJobStore.Completion(9L, "tok", "{\"x\":\"GİZLİ\"}", false, "pod-a-1");
        assertThat(c.toString()).doesNotContain("GİZLİ").doesNotContain("tok");
    }

    @Test
    @DisplayName("kuyruk özeti: durum / tür sayıları, satırlar kopyalanır")
    void queueStats() {
        List<CheckJobStore.QueueStat> rows = new ArrayList<>();
        rows.add(new CheckJobStore.QueueStat(CheckJobStore.STATE_PENDING, "port", 3, 10L, null));
        rows.add(new CheckJobStore.QueueStat(CheckJobStore.STATE_PENDING, "ping", 2, 12L, null));
        rows.add(new CheckJobStore.QueueStat(CheckJobStore.STATE_DONE, "port", 1, 5L, 9L));
        CheckJobStore.QueueStats s = new CheckJobStore.QueueStats(100L, rows, 90L, 91L, 0L);
        rows.clear();
        assertThat(s.rows()).hasSize(3);
        assertThat(s.count(CheckJobStore.STATE_PENDING)).isEqualTo(5);
        assertThat(s.count(CheckJobStore.STATE_PENDING, "port")).isEqualTo(3);
        assertThat(s.count(CheckJobStore.STATE_CLAIMED)).isZero();
        assertThat(s.types()).containsExactly("ping", "port");
        assertThat(new CheckJobStore.QueueStats(1L, null, null, null, 0L).rows()).isEmpty();
    }
}
