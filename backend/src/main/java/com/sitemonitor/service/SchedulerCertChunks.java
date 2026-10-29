package com.sitemonitor.service;

import com.sitemonitor.service.certsweep.CertChunkRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
        if (rows == null || rows.isEmpty()) return new java.util.ArrayList<>();
        SchedulerService s = scheduler.get();
        if (s == null) throw new IllegalStateException("SchedulerService yok — parça ölçülemedi");
        return s.gatherCertResults(rows, runId);
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
