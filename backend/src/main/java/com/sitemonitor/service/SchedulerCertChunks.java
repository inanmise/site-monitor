package com.sitemonitor.service;

import com.sitemonitor.service.certsweep.CertChunkRunner;
import com.sitemonitor.service.certsweep.CertChunking;
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
 * çekirdek kadar sertifika kontrolünün arkasında bekler. İstisnalar: turun KOORDİNATÖRÜ kendi devraldığı / yeniden ölçtüğü alan
 * adlarını ölçerken (aynı iş parçacığı, {@link SchedulerService#isCoordinatingCertRun()}) ve bu pod'un işçisi BU POD'UN koordine
 * ettiği koşan turun parçasını ölçerken ({@link SchedulerService#coordinatesCertRun(String)}, FX2-WORKER L-3) bütün çekirdeği
 * kullanır — klasik turun lider pod'daki davranışı (klasik tur bütün envanteri bir kerede kuyruğa koyar; bölmek turu ~3 kat
 * uzatıp {@code cert-check} kilidini o kadar tutardı); çekirdek sayısı okunamazsa ({@code ≤ 0}) bölünmez. Başka pod'un turunun
 * parçaları — liderin "işi kimse almıyor" rahatlamasıyla aldıkları dahil — bölünmeye devam eder: rahatlama bütçesi
 * ({@code CertChunking.Tuning.leaderReliefBudget}) alt gruplanmış görevlerle çekirdeği aşmayacak biçimde hesaplanır
 * (FX2-WORKER L-2). Alt grup boyu {@link com.sitemonitor.service.certsweep.CertChunking#leaderSubBatch(int)} (tek kaynak).
 * Takipçi pod'lar değişmedi.
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
        int batch = leaderSubBatch(s, runId);
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

    /** Koşu kimliği bilinmeden ({@link #leaderSubBatch(SchedulerService, String)}, kimlik null). */
    static int leaderSubBatch(SchedulerService s) {
        return leaderSubBatch(s, null);
    }

    /**
     * Lider pod'da bir parçanın alt grup boyutu (SW-L3): {@link CertChunking#leaderSubBatch(int)} = {@code max(1, çekirdek / 2)};
     * 0 = bölme (takipçi, turun koordinatörü kendi iş parçacığında, parça BU pod'un koşan turunun — FX2-WORKER L-3 —, çekirdek
     * okunamadı ya da okuma hatası).
     */
    static int leaderSubBatch(SchedulerService s, String runId) {
        try {
            if (!s.sweepLeaderHint() || s.isCoordinatingCertRun() || s.coordinatesCertRun(runId)) return 0;
            return CertChunking.leaderSubBatch(s.certCheckCoreSize());
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
