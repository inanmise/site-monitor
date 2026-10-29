package com.sitemonitor.service;

import com.sitemonitor.service.certsweep.CertChunkRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link CertChunkRunner} köprüsü (2026-10-29, çok pod Sürüm 3-4 paket B; tasarım R3-4 §4.6 madde 4). Ölçüm
 * {@code SchedulerService}'in çitli bölgesinde ({@code R3-4 cert run}) yaşayan KLASİK toplamadır
 * ({@code gatherCertResults}) — parça işçisi (paket A) ve koordinatör ({@code CertRunCoordinator}) aynı kodu koşsun (L-3); bu
 * bileşen yalnız sözleşmeyi uygular ve çağrıyı bir {@link ObjectProvider} üzerinden iletir (bağımlılık döngüsü kurmaz,
 * {@code SchedulerService}'in kurucusu değişmez).
 *
 * <p><b>Lider payı alt gruplar hâlinde (inceleme SW-L3, 2026-10-29).</b> Bu pod tarama lideriyken bir PARÇANIN satırları en çok
 * {@code max(1, çekirdek / 2)}'lik alt gruplar hâlinde ölçülür (her alt grup aynı klasik toplama, sıra korunur) — liderin bir
 * parçası {@code certCheckExecutor}'ın bütün çekirdeğini tutmaz, klasik izleme taramaları ve erişilebilirlik turu en çok yarım
 * çekirdek kadar sertifika kontrolünün arkasında bekler. İki istisna: turun KOORDİNATÖRÜ kendi devraldığı / yeniden ölçtüğü alan
 * adlarını ölçerken (aynı iş parçacığı, {@link SchedulerService#isCoordinatingCertRun()}) bütün çekirdeği kullanır — klasik
 * turun lider pod'daki davranışı; çekirdek sayısı okunamazsa ({@code ≤ 0}) bölünmez. Takipçi pod'lar değişmedi.
 *
 * <p>Kapalı ≡ v20.170.0 (L-1): açılışta hiçbir iş yapmaz; yöntemlerini ancak anahtar açıkken işçi ve koordinatör çağırır.
 */
@Component
public class SchedulerCertChunks implements CertChunkRunner {

    private final Supplier<SchedulerService> scheduler;

    @Autowired
    public SchedulerCertChunks(ObjectProvider<SchedulerService> scheduler) {
        this.scheduler = () -> {
            try {
                return scheduler.getIfAvailable();
            } catch (RuntimeException e) {
                return null;
            }
        };
    }

    /** Testler / elle kurulum: doğrudan zamanlayıcı. */
    public SchedulerCertChunks(SchedulerService scheduler) {
        this.scheduler = () -> scheduler;
    }

    @Override
    public List<Map<String, Object>> checkDomains(List<Map<String, Object>> rows, String runId) {
        if (rows == null || rows.isEmpty()) return new ArrayList<>();
        SchedulerService s = scheduler.get();
        if (s == null) throw new IllegalStateException("SchedulerService yok — parça ölçülemedi");
        int batch = leaderSubBatch(s);
        if (batch <= 0 || rows.size() <= batch) return s.gatherCertResults(rows, runId);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (int from = 0; from < rows.size(); from += batch) {
            if (from > 0 && s.certRunClosing()) break;               // kapanış: kalan alt gruplar gönderilmez, liste kısa
            List<Map<String, Object>> part = new ArrayList<>(rows.subList(from, Math.min(rows.size(), from + batch)));
            List<Map<String, Object>> got = s.gatherCertResults(part, runId);
            if (got != null) out.addAll(got);
            if (got == null || got.size() < part.size()) break;      // kapanışta klasik toplama kısa döndü
        }
        return out;
    }

    /**
     * Lider pod'da bir parçanın alt grup boyutu (SW-L3): {@code max(1, çekirdek / 2)}; 0 = bölme (takipçi, turun koordinatörü
     * kendi iş parçacığında, çekirdek okunamadı ya da okuma hatası).
     */
    static int leaderSubBatch(SchedulerService s) {
        try {
            if (!s.sweepLeaderHint() || s.isCoordinatingCertRun()) return 0;
            int core = s.certCheckCoreSize();
            return core <= 0 ? 0 : Math.max(1, core / 2);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public boolean closing() {
        SchedulerService s = scheduler.get();
        return s == null || s.certRunClosing();
    }

    @Override
    public long networkErrorCount(List<Map<String, Object>> results) {
        return SchedulerService.networkErrorCount(results);
    }

    @Override
    public int executorCoreSize() {
        SchedulerService s = scheduler.get();
        if (s == null) return 0;
        try {
            return s.certCheckCoreSize();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
