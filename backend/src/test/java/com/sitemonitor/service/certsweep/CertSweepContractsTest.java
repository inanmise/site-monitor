package com.sitemonitor.service.certsweep;

import com.sitemonitor.service.checks.CheckJobRunner;
import com.sitemonitor.service.checks.CheckResultCodec;
import com.sitemonitor.service.health.ClusterHealthStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Çok pod Sürüm 3-4 P0 — sözleşme kayıtları: koşu / parça tanımı (L-4: her alan adı tam bir parçada), klasik satır gidiş-dönüşü
 * (toplama aynı {@code checkAsync} bağımsız değişkenlerini görür), sonuç JSON'u (3-3'ün tek kodlayıcısıyla birebir aynı tür
 * kuralı — L-3), parça durumu (kayıp parça kuralı), küme durumu yardımcıları ve {@code workers_json}.
 */
class CertSweepContractsTest {

    private static Map<String, Object> row(String domain, Integer port, boolean proxy, String tls, Integer timeout) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", domain);
        r.put("port", port);
        r.put("use_proxy", proxy);
        r.put("tls_mode", tls);
        if (timeout != null) r.put("timeout_seconds", timeout);
        r.put("check_interval_hours", 6);
        return r;
    }

    private static CertChunkSpec.Entry e(int i) {
        return new CertChunkSpec.Entry(i, "d" + i + ".example.com", 443, false, "", null);
    }

    @Test
    @DisplayName("klasik satır → Entry → satır: toplamanın okuduğu beş değer AYNEN (boş tls_mode, null port, zaman aşımı yalnız doluysa); sıklık taşınmaz")
    void entryRoundTrip() {
        Map<String, Object> a = row("a.example.com", 8443, true, "browser", 15);
        CertChunkSpec.Entry ea = CertChunkSpec.Entry.of(7, a);
        assertThat(ea).isEqualTo(new CertChunkSpec.Entry(7, "a.example.com", 8443, true, "browser", 15));
        Map<String, Object> ra = ea.toRow();
        assertThat(ra).containsExactly(Map.entry("domain", "a.example.com"), Map.entry("port", 8443),
                Map.entry("use_proxy", true), Map.entry("tls_mode", "browser"), Map.entry("timeout_seconds", 15));

        Map<String, Object> b = row("b.example.com", null, false, "", null);
        b.put("use_proxy", null);
        CertChunkSpec.Entry eb = CertChunkSpec.Entry.of(0, b);
        assertThat(eb.port()).isNull();
        assertThat(eb.useProxy()).isFalse();
        assertThat(eb.tlsMode()).isEmpty();
        assertThat(eb.toRow()).doesNotContainKey("timeout_seconds").doesNotContainKey("check_interval_hours")
                .containsEntry("tls_mode", "").containsEntry("use_proxy", false);
        assertThat(eb.toRow().get("port")).isNull();

        Map<String, Object> c = row("c.example.com", 443, false, null, null);
        assertThat(CertChunkSpec.Entry.of(1, c).toRow()).containsEntry("tls_mode", null);

        Map<String, Object> bad = row("x.example.com", 443, false, "", null);
        bad.put("port", "443");
        assertThatThrownBy(() -> CertChunkSpec.Entry.of(0, bad)).as("klasik toplamadaki gibi tür hatası")
                .isInstanceOf(ClassCastException.class);
        assertThatThrownBy(() -> new CertChunkSpec.Entry(-1, "x", 443, false, "", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CertChunkSpec.Entry(0, " ", 443, false, "", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("koşu tanımı: her sıra numarası TAM bir parçada; eksik / yinelenen / aralık dışı sıra, yinelenen parça no, ikinci görüş işareti → hata")
    void runSpecCoversEveryDomainOnce() {
        CertRunSpec ok = new CertRunSpec("abc123def456", ClusterHealthStore.KIND_HOURLY, "pod-a-1234", true, 4, 2,
                List.of(CertChunkSpec.of(0, List.of(e(2), e(0))), CertChunkSpec.of(1, List.of(e(1), e(3)))));
        assertThat(ok.chunksTotal()).isEqualTo(2);
        assertThat(ok.chunks().get(0).domainCount()).isEqualTo(2);
        assertThat(ok.toString()).contains("chunks=2").doesNotContain("d0.example.com");

        assertThatThrownBy(() -> new CertRunSpec("r", "hourly", "p", true, 4, 2,
                List.of(CertChunkSpec.of(0, List.of(e(0), e(1))), CertChunkSpec.of(1, List.of(e(2))))))
                .as("eksik 3").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CertRunSpec("r", "hourly", "p", true, 3, 2,
                List.of(CertChunkSpec.of(0, List.of(e(0), e(1))), CertChunkSpec.of(1, List.of(e(1), e(2))))))
                .as("yinelenen 1").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CertRunSpec("r", "hourly", "p", true, 2, 2,
                List.of(CertChunkSpec.of(0, List.of(e(0), e(5))))))
                .as("aralık dışı").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CertRunSpec("r", "hourly", "p", true, 2, 1,
                List.of(CertChunkSpec.of(0, List.of(e(0))), CertChunkSpec.of(0, List.of(e(1))))))
                .as("yinelenen parça no").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CertRunSpec("r", "hourly", "p", true, 1, 1,
                List.of(new CertChunkSpec(0, List.of(e(0)), "pod-b"))))
                .as("ilk parçada avoidWorker").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CertRunSpec("r", "weekly", "p", true, 1, 1, List.of(CertChunkSpec.of(0, List.of(e(0))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CertRunSpec("x".repeat(41), "full", "p", true, 1, 1, List.of(CertChunkSpec.of(0, List.of(e(0))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CertRunSpec("r", "stale", "p", false, 1, 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CertChunkSpec.of(0, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(CertRunSpec.KINDS).containsExactlyInAnyOrder("hourly", "stale", "full");
    }

    /** Değer türlerini de taşıyan karşılaştırma (Integer ≠ Long, ArrayList ≠ List.of). */
    private static Object typed(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> x : m.entrySet()) out.put(String.valueOf(x.getKey()), typed(x.getValue()));
            return List.of(v.getClass().getName(), out);
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(typed(o));
            return List.of(v.getClass().getName(), out);
        }
        return v == null ? "null" : List.of(v.getClass().getName(), String.valueOf(v));
    }

    private static Map<String, Object> sample(String domain, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", domain);
        m.put("status", status);
        m.put("warning", !"valid".equals(status));
        m.put("days_remaining", 42);
        m.put("elapsed_ms", 412L);
        m.put("big", 9_000_000_000L);
        m.put("rate", 0.25);
        m.put("whole", 2.0);
        m.put("fingerprint", null);
        m.put("san", List.of(domain, "www." + domain));
        m.put("chain", List.of(Map.of("subject", "CN=" + domain), new LinkedHashMap<>(Map.of("days", 3))));
        m.put("error_class", "error".equals(status) ? "NETWORK" : null);
        m.put("text", "çğıöşü İ \"tırnak\" \\ ters");
        m.put("run_id", "abc123def456");
        return m;
    }

    @Test
    @DisplayName("sonuç JSON'u: her öğe 3-3'ün CheckResultCodec gidiş-dönüşüyle TÜRÜ DAHİL aynı (Long → Integer normalleştirmesi, ArrayList, LinkedHashMap)")
    void resultsRoundTripMatchesCodec() {
        ClaimedChunk chunk = new ClaimedChunk(9, "abc123def456", 3, List.of(e(5), e(2), e(8)), 1, "tok", 1_000, 61_000);
        List<Map<String, Object>> results = List.of(sample("d5.example.com", "valid"), sample("d2.example.com", "error"),
                sample("d8.example.com", "warning"));
        ChunkCompletion c = ChunkCompletion.of(chunk, "pod-b-9", results, 1);
        assertThat(c.id()).isEqualTo(9);
        assertThat(c.claimToken()).isEqualTo("tok");
        assertThat(c.worker()).isEqualTo("pod-b-9");
        assertThat(c.checked()).isEqualTo(3);
        assertThat(c.errors()).isEqualTo(1);
        assertThat(c.warnings()).isEqualTo(2);
        assertThat(c.networkErrors()).isEqualTo(1);
        assertThat(c.toString()).doesNotContain("d5.example.com").doesNotContain("tok");

        List<ChunkCompletion.Result> back = ChunkCompletion.decodeResults(c.resultsJson());
        assertThat(back).extracting(ChunkCompletion.Result::index).containsExactly(5, 2, 8);
        for (int k = 0; k < results.size(); k++) {
            Map<String, Object> viaCodec = CheckResultCodec.decode(CheckResultCodec.encode(results.get(k)).json());
            assertThat(typed(back.get(k).result())).as("öğe " + k).isEqualTo(typed(viaCodec));
            assertThat(back.get(k).truncated()).isFalse();
            assertThat(back.get(k).usable()).isTrue();
            assertThat(back.get(k).toString()).doesNotContain("example.com");
        }
        assertThat(back.get(0).result().get("elapsed_ms")).isInstanceOf(Integer.class).isEqualTo(412);
        assertThat(back.get(0).result().get("big")).isInstanceOf(Long.class);
        assertThat(back.get(0).result().get("rate")).isEqualTo(0.25);
        assertThat(back.get(0).result()).containsEntry("fingerprint", null).containsEntry("run_id", "abc123def456");
        back.get(0).result().put("deployment_status", "OK");   // değiştirilebilir (saveResult haritaya yazar)
    }

    @Test
    @DisplayName("sonuç JSON'u: çok uzun metin kırpılır (t = true → kullanılamaz), null sonuç / işaretli sonuç kullanılamaz, bozuk JSON / öğe atlanır")
    void resultsEdgeCases() {
        Map<String, Object> huge = sample("h.example.com", "valid");
        huge.put("blob", "x".repeat(CheckResultCodec.MAX_RESULT_CHARS + 10));
        String json = ChunkCompletion.encodeResults(List.of(
                new ChunkCompletion.Result(0, huge, false),
                new ChunkCompletion.Result(1, null, false),
                new ChunkCompletion.Result(2, CheckJobRunner.failed(new IllegalStateException("x")), false),
                new ChunkCompletion.Result(3, sample("ok.example.com", "valid"), true)));
        List<ChunkCompletion.Result> back = ChunkCompletion.decodeResults(json);
        assertThat(back).extracting(ChunkCompletion.Result::index).containsExactly(0, 1, 2, 3);
        assertThat(back.get(0).truncated()).isTrue();
        assertThat(back.get(0).usable()).isFalse();
        assertThat(back.get(1).result()).containsEntry(CheckJobRunner.KEY_FAILED, "NullResult");
        assertThat(back.get(1).usable()).isFalse();
        assertThat(back.get(2).usable()).isFalse();
        assertThat(back.get(3).truncated()).as("girdi işareti yok sayılır — kodlayıcı belirler").isFalse();
        assertThat(back.get(3).usable()).isTrue();

        assertThat(ChunkCompletion.decodeResults(null)).isEmpty();
        assertThat(ChunkCompletion.decodeResults("  ")).isEmpty();
        assertThat(ChunkCompletion.decodeResults("{bozuk")).isEmpty();
        assertThat(ChunkCompletion.decodeResults("{\"i\":1}")).as("dizi değil").isEmpty();
        assertThat(ChunkCompletion.decodeResults("[{\"i\":\"x\",\"r\":{}},{\"i\":-1,\"r\":{}},{\"i\":4,\"r\":[1]},5,"
                + "{\"i\":6,\"t\":true,\"r\":{\"a\":1}}]"))
                .extracting(ChunkCompletion.Result::index).containsExactly(6);
        assertThat(ChunkCompletion.encodeResults(null)).isEqualTo("[]");
        assertThat(ChunkCompletion.encodeResults(List.of())).isEqualTo("[]");
    }

    @Test
    @DisplayName("tamamlama: sonuç sayısı parçadan farklıysa (kapanışta erken duran toplama) kurulamaz")
    void completionRequiresAFullChunk() {
        ClaimedChunk chunk = new ClaimedChunk(1, "r", 0, List.of(e(0), e(1)), 1, "t", 0, 1);
        assertThatThrownBy(() -> ChunkCompletion.of(chunk, "w", List.of(sample("d0.example.com", "valid")), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChunkCompletion.of(chunk, "w", null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(chunk.rows()).extracting(r -> r.get("domain")).containsExactly("d0.example.com", "d1.example.com");
        assertThat(chunk.toString()).doesNotContain("d0.example.com").doesNotContain("=t,");
    }

    @Test
    @DisplayName("parça durumu: kayıp = deneme ≥ 2 ve (bekliyor ya da kirası dolmuş); canlı kira, ilk deneme, DONE / CANCELLED kayıp değil")
    void lostChunkRule() {
        long now = 100_000;
        ChunkState pending1 = state("PENDING", 1, null);
        ChunkState pending2 = state("PENDING", 2, null);
        ChunkState claimedLive2 = state("CLAIMED", 2, now + 1);
        ChunkState claimedExpired2 = state("CLAIMED", 2, now - 1);
        ChunkState claimedExpired1 = state("CLAIMED", 1, now - 1);
        ChunkState done2 = state("DONE", 2, null);
        ChunkState cancelled = state("CANCELLED", 2, null);
        assertThat(pending1.lost(now)).isFalse();
        assertThat(pending2.lost(now)).isTrue();
        assertThat(claimedLive2.lost(now)).isFalse();
        assertThat(claimedExpired2.lost(now)).isTrue();
        assertThat(claimedExpired1.lost(now)).as("yeniden sahiplenilebilir").isFalse();
        assertThat(claimedExpired1.leaseExpired(now)).isTrue();
        assertThat(done2.lost(now)).isFalse();
        assertThat(cancelled.lost(now)).isFalse();
        assertThat(pending1.open()).isTrue();
        assertThat(done2.open()).isFalse();
        assertThat(cancelled.cancelled()).isTrue();
        assertThat(pending1.ageMs(now)).isEqualTo(now - 1_000);
    }

    private static ChunkState state(String st, int attempts, Long leaseUntil) {
        return new ChunkState(1, 0, st, attempts, 20, 1_000, null, null, leaseUntil, null, null, null, null, null);
    }

    @Test
    @DisplayName("küme durumu: parça / alan adı sayıları, ölçülen alan adı, en eski bekleyen yaş, pod başına pay (yalnız DONE ağ hatası)")
    void statusHelpers() {
        CertSweepStatus.Run open = new CertSweepStatus.Run("r1", "hourly", "pod-a-1", "RUNNING", true, 100, 5, 20,
                10_000, 50_000, null, null, null, null, null, null);
        CertSweepStatus.Run old = new CertSweepStatus.Run("r0", "stale", "pod-b-1", "DONE", false, 40, 2, 20,
                1_000, 2_000, 4_500L, "DECIDED", 1, 0, 0, "{\"pod-b-1\":[2,40,1]}");
        CertSweepStatus s = new CertSweepStatus(60_000, false, List.of(open, old), open, List.of(
                new CertSweepStatus.ChunkGroup("PENDING", null, 1, 20, 0, 12_000L),
                new CertSweepStatus.ChunkGroup("CLAIMED", "pod-b-1", 1, 20, 0, 11_000L),
                new CertSweepStatus.ChunkGroup("DONE", "pod-a-1", 2, 40, 4, 10_000L),
                new CertSweepStatus.ChunkGroup("DONE", "pod-b-1", 1, 20, 1, 10_000L)));
        assertThat(s.chunks("DONE")).isEqualTo(3);
        assertThat(s.domains("PENDING")).isEqualTo(20);
        assertThat(s.domainsMeasured()).isEqualTo(60);
        assertThat(s.oldestPendingAgeMs()).isEqualTo(48_000L);
        assertThat(s.perWorker()).containsExactly(
                new CertSweepStatus.WorkerTally("pod-a-1", 2, 40, 4),
                new CertSweepStatus.WorkerTally("pod-b-1", 2, 40, 1));
        assertThat(s.perWorker().get(0).networkErrorRate()).isEqualTo(0.1);
        assertThat(open.open()).isTrue();
        assertThat(open.durationMs()).isNull();
        assertThat(old.open()).isFalse();
        assertThat(old.durationMs()).isEqualTo(3_500L);
        assertThat(old.workers()).containsExactly(new CertSweepStatus.WorkerTally("pod-b-1", 2, 40, 1));
        assertThat(new CertSweepStatus(1, false, null, null, null).oldestPendingAgeMs()).isNull();
        assertThat(new CertSweepStatus.WorkerTally("x", 0, 0, 0).networkErrorRate()).isZero();
    }

    @Test
    @DisplayName("workers_json: gidiş-dönüş, alan adına göre sıra, boş kimlik atlanır, 4 000 karakteri aşarsa en az alan adlılar düşer, bozuk → boş")
    void workersJson() {
        List<CertSweepStatus.WorkerTally> in = List.of(
                new CertSweepStatus.WorkerTally("pod-b-2", 3, 60, 2),
                new CertSweepStatus.WorkerTally("pod-a-1", 5, 100, 0),
                new CertSweepStatus.WorkerTally(" ", 1, 1, 1),
                new CertSweepStatus.WorkerTally("pod-c-3", 1, 20, 0));
        String json = CertSweepStatus.encodeWorkers(in);
        assertThat(json).isEqualTo("{\"pod-a-1\":[5,100,0],\"pod-b-2\":[3,60,2],\"pod-c-3\":[1,20,0]}");
        assertThat(CertSweepStatus.decodeWorkers(json)).extracting(CertSweepStatus.WorkerTally::worker)
                .containsExactly("pod-a-1", "pod-b-2", "pod-c-3");

        List<CertSweepStatus.WorkerTally> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) many.add(new CertSweepStatus.WorkerTally("pod-" + "x".repeat(40) + "-" + i, 1, 1_000 - i, 0));
        String capped = CertSweepStatus.encodeWorkers(many);
        assertThat(capped.length()).isLessThanOrEqualTo(CertSweepStatus.MAX_WORKERS_JSON_CHARS);
        List<CertSweepStatus.WorkerTally> kept = CertSweepStatus.decodeWorkers(capped);
        assertThat(kept).isNotEmpty().hasSizeLessThan(200);
        assertThat(kept.get(0).domains()).as("en büyük kalır").isEqualTo(1_000);

        assertThat(CertSweepStatus.encodeWorkers(null)).isEqualTo("{}");
        assertThat(CertSweepStatus.decodeWorkers(null)).isEmpty();
        assertThat(CertSweepStatus.decodeWorkers("[1,2]")).isEmpty();
        assertThat(CertSweepStatus.decodeWorkers("{\"a\":[1,2],\"b\":\"x\",\"c\":[1,\"2\",3],\"d\":[1,2,3]}"))
                .containsExactly(new CertSweepStatus.WorkerTally("d", 1, 2, 3));
    }
}
