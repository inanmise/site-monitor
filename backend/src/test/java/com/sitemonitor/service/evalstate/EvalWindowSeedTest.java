package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.MonitoringOutageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod Sürüm 3-2 A — pencere tohumu TEK kaynak ({@link EvalWindowSeed}, tasarım R3-2 §1.3). Kâhin: 3-1
 * {@code EvalStateVerifier.measureWindowSeed}'in tür başına döngüsü (aşağıda AYNEN) — rastgele satırlarda sayılar birebir;
 * yükleyicinin {@code marks} kuralı (alan adı başına ağ DOWN'ların VEYA'sı + en yeni an); 60 dk kesimi MOS'unkiyle aynı.
 */
class EvalWindowSeedTest {

    /** 3-1 doğrulayıcısındaki satır biçimi (yalnız döngünün okuduğu alanlar). */
    record OracleRow(String alertType, String domain, boolean up, boolean networkDown, long observedMs) {}

    /** 3-1 {@code measureWindowSeed} döngüsü — DEĞİŞTİRMEYİN (kâhin): {seedDown, seedTotal}. */
    static int[] oracle(String type, long cutoff, Iterable<OracleRow> all) {
        Map<String, Boolean> byDomain = new HashMap<>();
        for (OracleRow r : all) {
            if (!type.equals(r.alertType()) || r.domain() == null || r.observedMs() < cutoff) continue;
            byDomain.merge(r.domain(), !r.up() && r.networkDown(), Boolean::logicalOr);
        }
        int seedDown = (int) byDomain.values().stream().filter(Boolean::booleanValue).count();
        int seedTotal = byDomain.size();
        return new int[] {seedDown, seedTotal};
    }

    static final List<String> TYPES = List.of("PORT_DOWN", "KEYWORD", "ACCESSIBILITY", "DNS_FAILURE");

    @RepeatedTest(25)
    @DisplayName("Rastgele satırlar: tür başına (ağ DOWN, toplam) 3-1 döngüsüyle BİREBİR (doğrulayıcı çıktısı değişmez)")
    void sameCountsAsVerifierLoop() {
        Random rnd = new Random();
        long seed = rnd.nextLong();
        rnd = new Random(seed);
        long now = 50_000_000L;
        List<OracleRow> oracleRows = new ArrayList<>();
        List<EvalWindowSeed.Row> rows = new ArrayList<>();
        int n = rnd.nextInt(300);
        for (int i = 0; i < n; i++) {
            String type = rnd.nextInt(10) == 0 ? null : TYPES.get(rnd.nextInt(TYPES.size()));
            String domain = rnd.nextInt(15) == 0 ? null : "d" + rnd.nextInt(25) + ".example";
            boolean up = rnd.nextBoolean();
            boolean net = rnd.nextBoolean();
            long observed = now - rnd.nextInt(2 * 3_600_000);
            oracleRows.add(new OracleRow(type == null ? "?" : type, domain, up, net, observed));
            rows.add(new EvalWindowSeed.Row(type == null ? "?" : type, domain, up, net, observed));
        }
        for (String type : TYPES) {
            for (long window : new long[] {0L, 60_000L, 300_000L, 1_800_000L, EvalWindowSeed.WINDOW_CAP_MS, 7_200_000L}) {
                long cutoff = now - window;
                int[] expected = oracle(type, cutoff, oracleRows);
                EvalWindowSeed.Sample s = EvalWindowSeed.sample(EvalWindowSeed.marksOfType(rows, type, cutoff), cutoff);
                assertThat(new int[] {s.networkDown(), s.total()}).as("tohum %d, tür %s, pencere %d", seed, type, window)
                        .isEqualTo(expected);
                // Tüm türlerin işaretleri aynı kesimle aynı sonucu verir
                EvalWindowSeed.Sample s2 = EvalWindowSeed.sample(EvalWindowSeed.marks(rows, cutoff).get(type), cutoff);
                assertThat(new int[] {s2.networkDown(), s2.total()}).isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("marks: alan adı başına ağ DOWN'ların VEYA'sı (up=false ∧ network_down), an = en yeni gözlem; kesimden eski / adsız satır yok")
    void marksRule() {
        List<EvalWindowSeed.Row> rows = List.of(
                new EvalWindowSeed.Row("PORT_DOWN", "a.example", true, false, 1_000L),
                new EvalWindowSeed.Row("PORT_DOWN", "a.example", false, true, 900L),     // ağ DOWN, daha eski
                new EvalWindowSeed.Row("PORT_DOWN", "b.example", false, false, 950L),    // DOWN ama ağ değil
                new EvalWindowSeed.Row("PORT_DOWN", "c.example", true, true, 960L),      // up → ağ DOWN değil (bayrak yok sayılır)
                new EvalWindowSeed.Row("PORT_DOWN", "old.example", false, true, 10L),    // kesimden eski
                new EvalWindowSeed.Row("PORT_DOWN", null, false, true, 999L),            // adsız
                new EvalWindowSeed.Row("KEYWORD", "a.example", false, true, 980L));
        Map<String, Map<String, EvalLoad.SeedMark>> m = EvalWindowSeed.marks(rows, 100L);
        assertThat(m.keySet()).containsExactlyInAnyOrder("PORT_DOWN", "KEYWORD");
        assertThat(m.get("PORT_DOWN")).containsOnlyKeys("a.example", "b.example", "c.example");
        assertThat(m.get("PORT_DOWN").get("a.example")).isEqualTo(new EvalLoad.SeedMark(true, 1_000L));
        assertThat(m.get("PORT_DOWN").get("b.example")).isEqualTo(new EvalLoad.SeedMark(false, 950L));
        assertThat(m.get("PORT_DOWN").get("c.example")).isEqualTo(new EvalLoad.SeedMark(false, 960L));
        assertThat(EvalWindowSeed.sample(m.get("PORT_DOWN"), 100L)).isEqualTo(new EvalWindowSeed.Sample(1, 3));
        assertThat(EvalWindowSeed.sample(m.get("PORT_DOWN"), 955L)).as("an kesimi örnekte de uygulanır")
                .isEqualTo(new EvalWindowSeed.Sample(1, 2));
        assertThat(EvalWindowSeed.sample(null, 0L)).isEqualTo(new EvalWindowSeed.Sample(0, 0));
        assertThat(EvalWindowSeed.marks(null, 0L)).isEmpty();
        assertThat(EvalWindowSeed.marks(Arrays.asList(null, null), 0L)).isEmpty();
        assertThat(EvalWindowSeed.marksOfType(rows, null, 0L)).isEmpty();
    }

    @Test
    @DisplayName("İnceleme M-2: yükleyici tohumu TÜR penceresiyle — a/b/c'nin yavaş izlemesi 40 dk önce ağ DOWN, hızlısı 1 dk "
            + "önce UP, d UP: tür penceresiyle (0, 4) (VERIFY'ın ölçtüğü), tek 60 dk kesimle (3, 4) olurdu (bastırmaya girerdi)")
    void loaderSeedUsesTheTypeWindow() {
        long now = 100_000_000L;
        String port = "PORT_DOWN";
        List<EvalWindowSeed.Row> rows = new ArrayList<>();
        for (String h : List.of("a", "b", "c")) {
            rows.add(new EvalWindowSeed.Row(port, h + ".example", false, true, now - 40 * 60_000L));
            rows.add(new EvalWindowSeed.Row(port, h + ".example", true, false, now - 60_000L));
        }
        rows.add(new EvalWindowSeed.Row(port, "d.example", true, false, now - 60_000L));
        Map<String, Long> intervals = Map.of(port, 30_000L);   // tur 30 sn → pencere 5 dk (taban)
        long typeWindow = EvalWindowSeed.DEFAULT_WINDOW_LENGTH.windowMs(port, 30_000L);
        assertThat(typeWindow).isEqualTo(300_000L);
        long cutoff = now - typeWindow;

        Map<String, EvalLoad.SeedMark> loaded = EvalWindowSeed.loadMarks(rows, now, intervals,
                EvalWindowSeed.DEFAULT_WINDOW_LENGTH).get(port);
        assertThat(EvalWindowSeed.sample(loaded, cutoff)).as("yükleyici = VERIFY kuralı")
                .isEqualTo(new EvalWindowSeed.Sample(0, 4));
        assertThat(EvalWindowSeed.sample(EvalWindowSeed.marksOfType(rows, port, cutoff), cutoff))
                .isEqualTo(new EvalWindowSeed.Sample(0, 4));
        assertThat(EvalWindowSeed.sample(EvalWindowSeed.marks(rows, now - EvalWindowSeed.WINDOW_CAP_MS).get(port), cutoff))
                .as("eski kural (tek 60 dk kesim) — hata buydu").isEqualTo(new EvalWindowSeed.Sample(3, 4));
    }

    @RepeatedTest(25)
    @DisplayName("loadMarks(…).get(t) = marksOfType(satırlar, t, şimdi − pencere(t, aralık(t))) — rastgele satır + aralık; "
            + "aralık yok = taban; pencere [0, 60 dk]'ya sıkışır; kuralı istisna veren tür tohumlanmaz")
    void loadMarksIsMarksOfTypeWithTheTypeCutoff() {
        Random rnd = new Random();
        long seed = rnd.nextLong();
        rnd = new Random(seed);
        long now = 80_000_000L;
        List<EvalWindowSeed.Row> rows = new ArrayList<>();
        int n = rnd.nextInt(300);
        for (int i = 0; i < n; i++) {
            String type = rnd.nextInt(12) == 0 ? null : TYPES.get(rnd.nextInt(TYPES.size()));
            String domain = rnd.nextInt(15) == 0 ? null : "d" + rnd.nextInt(25) + ".example";
            rows.add(new EvalWindowSeed.Row(type, domain, rnd.nextBoolean(), rnd.nextBoolean(),
                    now - rnd.nextInt(2 * 3_600_000)));
        }
        Map<String, Long> intervals = new HashMap<>();
        for (String t : TYPES) {
            int pick = rnd.nextInt(5);
            if (pick == 0) continue;   // tür satırı yok → taban
            intervals.put(t, switch (pick) {
                case 1 -> 15_000L;
                case 2 -> 200_000L;
                case 3 -> 1_500_000L;   // 3 × aralık > 60 dk → sıkışır
                default -> -5L;
            });
        }
        EvalWindowSeed.WindowLength rule = (type, interval) -> {
            if ("DNS_FAILURE".equals(type) && interval == 200_000L) throw new IllegalStateException("kural");
            return EvalWindowSeed.DEFAULT_WINDOW_LENGTH.windowMs(type, interval);
        };
        Map<String, Map<String, EvalLoad.SeedMark>> loaded = EvalWindowSeed.loadMarks(rows, now, intervals, rule);
        for (String type : TYPES) {
            long interval = Math.max(0L, intervals.getOrDefault(type, 0L));
            if ("DNS_FAILURE".equals(type) && interval == 200_000L) {
                assertThat(loaded).as("tohum %d: kural düştü → tür yok", seed).doesNotContainKey(type);
                continue;
            }
            long window = Math.min(EvalWindowSeed.WINDOW_CAP_MS, Math.max(300_000L, 3 * interval));
            Map<String, EvalLoad.SeedMark> expected = EvalWindowSeed.marksOfType(rows, type, now - window);
            assertThat(loaded.getOrDefault(type, Map.of())).as("tohum %d, tür %s", seed, type).isEqualTo(expected);
        }
        assertThat(EvalWindowSeed.loadMarks(null, now, intervals, rule)).isEmpty();
        assertThat(EvalWindowSeed.loadMarks(rows, now, null, null))
                .as("aralık / kural yok = taban, varsayılan kural").isEqualTo(EvalWindowSeed.loadMarks(rows, now, Map.of(),
                        EvalWindowSeed.DEFAULT_WINDOW_LENGTH));
        assertThat(EvalWindowSeed.cutoffOf("X", now, Map.of(), (t, i) -> -1L)).as("negatif pencere → 0").isEqualTo(now);
        assertThat(EvalWindowSeed.cutoffOf("X", now, Map.of(), (t, i) -> Long.MAX_VALUE))
                .isEqualTo(now - EvalWindowSeed.WINDOW_CAP_MS);
    }

    @Test
    @DisplayName("Varsayılan pencere kuralı EvalStateSource varsayılanının BİREBİR aynası (MOS kendi tabanıyla geçersiz kılar)")
    void defaultWindowLengthMirrorsSource() {
        EvalStateSource src = new LoadFakeSource();
        for (long iv : new long[] {-5L, 0L, 1L, 60_000L, 100_000L, 100_001L, 1_200_000L, 1_200_001L, 10_000_000L}) {
            assertThat(EvalWindowSeed.DEFAULT_WINDOW_LENGTH.windowMs("PORT_DOWN", iv)).as("aralık %d", iv)
                    .isEqualTo(src.windowLengthMsFor("PORT_DOWN", iv));
        }
    }

    @Test
    @DisplayName("Kesim: EvalWindowSeed.WINDOW_CAP_MS = MonitoringOutageService.SUPPRESSION_WINDOW_CAP_MS (60 dk)")
    void capMirrorsMos() throws Exception {
        Field f = MonitoringOutageService.class.getDeclaredField("SUPPRESSION_WINDOW_CAP_MS");
        f.setAccessible(true);
        assertThat(EvalWindowSeed.WINDOW_CAP_MS).isEqualTo(f.getLong(null)).isEqualTo(3_600_000L);
    }
}
