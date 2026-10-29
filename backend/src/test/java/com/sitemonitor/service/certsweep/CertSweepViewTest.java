package com.sitemonitor.service.certsweep;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parçalı sertifika taramasının gözlem kuralları (çok pod R3-4, paket C1): turun kipi (parçalı / klasik / bilinmiyor — asla
 * tahmin yok), açık koşunun ölçülmüş pod payı (uçuştaki alan adları oranı seyreltmez), aykırı pod kuralının gösterim kopyası
 * (tasarım §2.6) ve 24 saatlik terk edilmiş koşu sayısı.
 */
class CertSweepViewTest {

    static final long NOW = 1_800_000_000_000L;

    static CertSweepStatus.Run run(String id, String state, long createdMs) {
        return new CertSweepStatus.Run(id, "hourly", "pod-a-0000000a", state, true, 400, 20, 20, createdMs, createdMs, null,
                null, null, null, null, null);
    }

    static CertSweepStatus status(List<CertSweepStatus.Run> runs, CertSweepStatus.Run open, List<CertSweepStatus.ChunkGroup> groups) {
        return new CertSweepStatus(NOW, false, runs, open, groups);
    }

    @Test
    @DisplayName("modeOf: koşu kimliği listede → parçalı; liste sınırın altında ve yok → klasik; sınır dolu, tur listenin en "
            + "eskisinden açıkça sonra başladı → klasik; aksi / kimlik yok / durum okunamadı → bilinmiyor")
    void modeOf() {
        CertSweepStatus few = status(List.of(run("r1", "DONE", NOW - 3_600_000)), null, List.of());
        assertThat(CertSweepView.modeOf("r1", "2026-10-29T08:00:00", 40_000L, few, 5)).isEqualTo(CertSweepView.MODE_CHUNKED);
        assertThat(CertSweepView.modeOf("r9", "2026-10-29T08:00:00", 40_000L, few, 5)).isEqualTo(CertSweepView.MODE_CLASSIC);
        assertThat(CertSweepView.modeOf(null, "2026-10-29T08:00:00", 40_000L, few, 5)).isEqualTo(CertSweepView.MODE_UNKNOWN);
        assertThat(CertSweepView.modeOf("r1", "2026-10-29T08:00:00", 40_000L, null, 5)).isEqualTo(CertSweepView.MODE_UNKNOWN);

        long oldest = CertSweepView.parseIso("2026-10-29T06:00:00");
        List<CertSweepStatus.Run> full = new ArrayList<>();
        for (int i = 0; i < 5; i++) full.add(run("c" + i, "DONE", oldest + i * 600_000L));
        CertSweepStatus capped = status(full, null, List.of());
        // 08:00 bitti, 40 sn sürdü → 07:59:20 başladı ≥ 06:00 + 60 sn → parçalı olsaydı listede olurdu → klasik
        assertThat(CertSweepView.modeOf("x", "2026-10-29T08:00:00", 40_000L, capped, 5)).isEqualTo(CertSweepView.MODE_CLASSIC);
        // 05:30'da başlayan tur listenin en eskisinden ÖNCE → listede olmayabilir → bilinmiyor (tahmin yok)
        assertThat(CertSweepView.modeOf("x", "2026-10-29T05:31:00", 60_000L, capped, 5)).isEqualTo(CertSweepView.MODE_UNKNOWN);
        // saat kayma payının içinde → bilinmiyor
        assertThat(CertSweepView.modeOf("x", "2026-10-29T06:00:30", 0L, capped, 5)).isEqualTo(CertSweepView.MODE_UNKNOWN);
        assertThat(CertSweepView.modeOf("x", "bozuk", 0L, capped, 5)).isEqualTo(CertSweepView.MODE_UNKNOWN);
        assertThat(CertSweepView.parseIso("2026-10-29T06:00:00.123")).isEqualTo(oldest);
    }

    @Test
    @DisplayName("doneTallies: yalnız DONE gruplar — CLAIMED (uçuştaki) alan adları oranı seyreltmez; pod'a göre toplanır")
    void doneTallies() {
        CertSweepStatus st = status(List.of(), run("o", "RUNNING", NOW - 30_000), List.of(
                new CertSweepStatus.ChunkGroup("DONE", "a", 2, 40, 4, NOW - 30_000),
                new CertSweepStatus.ChunkGroup("CLAIMED", "a", 3, 60, 0, NOW - 30_000),
                new CertSweepStatus.ChunkGroup("DONE", "b", 5, 100, 1, NOW - 30_000),
                new CertSweepStatus.ChunkGroup("PENDING", null, 4, 80, 0, NOW - 30_000)));
        List<CertSweepStatus.WorkerTally> t = CertSweepView.doneTallies(st);
        assertThat(t).extracting(CertSweepStatus.WorkerTally::worker).containsExactly("b", "a");
        assertThat(t.get(1).domains()).isEqualTo(40);
        assertThat(t.get(1).networkErrors()).isEqualTo(4);
        assertThat(CertSweepView.doneTallies(null)).isEmpty();
    }

    @Test
    @DisplayName("outliers (§2.6 gösterim kopyası): yüksek oranlı tek pod, diğerleri ≤ eşik/2 ve yeterli örnek → aykırı; gerçek kısmi "
            + "kesinti (her yerden başarısız) aykırı DEĞİL; az örnek / az hata / tek pod → yok")
    void outliers() {
        var a = new CertSweepStatus.WorkerTally("a", 20, 400, 200);   // %50
        var b = new CertSweepStatus.WorkerTally("b", 20, 400, 8);     // %2
        var c = new CertSweepStatus.WorkerTally("c", 20, 400, 4);
        assertThat(CertSweepView.outliers(List.of(a, b, c), 3, 0.5)).containsExactly("a");
        // diğerleri de bozuk (genel kesinti) → aykırı yok
        var b2 = new CertSweepStatus.WorkerTally("b", 20, 400, 300);
        assertThat(CertSweepView.outliers(List.of(a, b2, c), 3, 0.5)).isEmpty();
        // hata sayısı min-errors'un altında → yok
        var tiny = new CertSweepStatus.WorkerTally("a", 1, 4, 2);
        assertThat(CertSweepView.outliers(List.of(tiny, b), 3, 0.5)).isEmpty();
        // diğerlerinin örneği 2 × min-errors'tan az → yok
        var few = new CertSweepStatus.WorkerTally("b", 1, 5, 0);
        assertThat(CertSweepView.outliers(List.of(a, few), 3, 0.5)).isEmpty();
        assertThat(CertSweepView.outliers(List.of(a), 3, 0.5)).isEmpty();
        assertThat(CertSweepView.outliers(null, 3, 0.5)).isEmpty();
    }

    @Test
    @DisplayName("abandonedSince: pencere içindeki ABANDONED koşular; okunan sınır dolu ve en eskisi pencerenin içinde → alt sınır")
    void abandonedSince() {
        long since = NOW - 24L * 3_600_000L;
        CertSweepStatus st = status(List.of(run("1", "ABANDONED", NOW - 3_600_000), run("2", "DONE", NOW - 7_200_000),
                run("3", "ABANDONED", NOW - 30L * 3_600_000)), null, List.of());
        assertThat(CertSweepView.abandonedSince(st, since, 20)).containsExactly(1, 1);
        List<CertSweepStatus.Run> full = new ArrayList<>();
        for (int i = 0; i < 3; i++) full.add(run("x" + i, "ABANDONED", NOW - i * 60_000L));
        assertThat(CertSweepView.abandonedSince(status(full, null, List.of()), since, 3)).containsExactly(3, 0);
        assertThat(CertSweepView.abandonedSince(null, since, 3)).containsExactly(0, 0);
    }
}
