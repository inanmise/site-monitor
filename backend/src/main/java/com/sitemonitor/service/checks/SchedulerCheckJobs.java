package com.sitemonitor.service.checks;

import com.sitemonitor.service.SchedulerService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@link CheckJobRunner} + {@link CheckResultApplier} köprüsü (2026-10-29, çok pod Sürüm 3-3, paket B; tasarım R3-3 §4.1 /
 * §4.2). İş gövdeleri ve değerlendirme {@code SchedulerService}'in çitli bölgelerinde ({@code R3-3 jobs} / {@code R3-3 apply})
 * yaşar — klasik taramanın kurucularını ve kapılarını AYNEN kullansınlar diye (K-3); bu bileşen yalnız sözleşmeyi uygular ve
 * çağrıyı bir {@link ObjectProvider} üzerinden iletir (bağımlılık döngüsü kurmaz, sınıf başlığı değişmez).
 *
 * <p>Kapalı ≡ v20.169.0 (K-1): açılışta hiçbir iş yapmaz; yöntemleri ancak işçi (paket A) ve boşaltıcı (paket C) ana anahtar
 * açıkken çağırır.
 */
@Component
public class SchedulerCheckJobs implements CheckJobRunner, CheckResultApplier {

    private final ObjectProvider<SchedulerService> scheduler;

    public SchedulerCheckJobs(ObjectProvider<SchedulerService> scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public Map<String, Object> runCheckJob(String type, long monitorId) {
        SchedulerService s;
        try {
            s = scheduler.getIfAvailable();
        } catch (RuntimeException e) {
            return CheckJobRunner.failed(e);
        }
        if (s == null) return CheckJobRunner.failed(new IllegalStateException());
        try {
            Map<String, Object> out = s.runCheckJob(type, monitorId);
            return out != null ? out : CheckJobRunner.failed(new IllegalStateException());
        } catch (RuntimeException e) {
            return CheckJobRunner.failed(e);
        }
    }

    @Override
    public void apply(List<CheckResultRow> wave) {
        if (wave == null || wave.isEmpty()) return;
        SchedulerService s = scheduler.getIfAvailable();
        if (s != null) s.applyCheckResults(wave);
    }
}
