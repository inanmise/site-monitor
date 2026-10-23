package com.sitemonitor.service.schedule;

import com.sitemonitor.service.AppSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.BooleanSupplier;

/**
 * Lider erteleme / tur bırakma kuralları (2026-10-23, çok pod Sürüm 3-1 3e; tasarım R3-1 §3.2 / §3.3).
 *
 * <p>Tarama lideri izleme taramalarının hepsini koşturur; buna ek olarak paylaşılan zamanlayıcı havuzundaki "her pod" işlerini
 * de (saatlik / bayat sertifika taraması, gece temizliği, raporlar, TLS profili …) yarışı kazandığı her seferde üstlenir. Bu
 * sınıf liderin bu yan işleri BAŞKA canlı bir pod'a bırakmasının kuralıdır:
 * <ul>
 *   <li><b>Cron yan işi:</b> lider tetiği {@link #cronDeferMs()} (vars. 20 sn) erteler; ertelenen denemede tur başka pod'da
 *       alınmışsa gövde hiç koşmaz, alınmamışsa gövde (kilit + tur aynen) koşar — takipçi ölü / dolu / 20.167.0 olsa da tetik
 *       cron payı (60 sn) içinde koşar.</li>
 *   <li><b>Sabit periyotlu tur (bayat sertifika, TLS profili, yönetici özeti telafisi):</b> lider, turu son 2 periyotta BAŞKA
 *       bir sahibin aldığını görürse o tıkı bırakır ({@code SchedulerService.leaderYieldsRound}); kanıt yoksa bugünkü gibi
 *       sahiplenir (en geç 2 periyotta kendiliğinden geri döner).</li>
 * </ul>
 * Her iki kural da "başka canlı pod VAR" ({@link PodLiveness}, bilinmiyorsa YOK) ve bu pod tarama lideri
 * ({@code SchedulerService.sweepLeaderHint()}) iken uygulanır — tek pod'da hiçbir şey ertelenmez (değişmez I-3). Ayar
 * {@value SideJob#LEADER_YIELD_KEY} (CANLI, GLOBAL_ONLY) kapalıysa yerleşim birebir 20.167.0'dır.
 */
@Slf4j
@Component
public class SideJobPlacement {

    /** Ertelemenin varsayılanı (ms). */
    public static final long DEFAULT_CRON_DEFER_MS = 20_000L;

    /**
     * Ertelemenin üst sınırı (ms): ertelenen deneme + tur kaydının yeniden deneme beklemeleri (~30 sn) cron payının
     * ({@code SchedulerService.CRON_ROUND_SLACK_MS}, 60 sn) İÇİNDE kalmalı — gate {@code SideJobPlacementTest}.
     */
    public static final long MAX_CRON_DEFER_MS = 25_000L;

    private final BooleanSupplier yieldSetting;
    private final PodLiveness liveness;
    private final long cronDeferMs;

    @Autowired
    public SideJobPlacement(AppSettingsService settings, PodLiveness liveness,
                            @Value("${site.monitor.scheduler.leader-yield.cron-defer-ms:20000}") long cronDeferMs) {
        this(() -> settings.getBoolean(SideJob.LEADER_YIELD_KEY, SideJob.LEADER_YIELD_DEFAULT), liveness, cronDeferMs);
    }

    /** Testler: ayar tedarikçisi doğrudan verilir. */
    public SideJobPlacement(BooleanSupplier yieldSetting, PodLiveness liveness, long cronDeferMs) {
        this.yieldSetting = yieldSetting;
        this.liveness = liveness;
        this.cronDeferMs = Math.max(0L, Math.min(MAX_CRON_DEFER_MS, cronDeferMs));
        if (cronDeferMs > MAX_CRON_DEFER_MS) {
            log.warn("site.monitor.scheduler.leader-yield.cron-defer-ms={} cron payını aşar — {} ms kullanılıyor",
                    cronDeferMs, MAX_CRON_DEFER_MS);
        }
    }

    /** Lider erteleme / bırakma kuralları açık mı (CANLI ayar; okunamazsa varsayılan {@value SideJob#LEADER_YIELD_DEFAULT}). */
    public boolean yieldEnabled() {
        try {
            return yieldSetting == null ? SideJob.LEADER_YIELD_DEFAULT : yieldSetting.getAsBoolean();
        } catch (RuntimeException e) {
            return SideJob.LEADER_YIELD_DEFAULT;
        }
    }

    /** Canlı pod gözlemi (null olabilir — elle kurulum). */
    public PodLiveness liveness() {
        return liveness;
    }

    /** Ertelenen cron denemesinin gecikmesi (ms, {@link #MAX_CRON_DEFER_MS} ile sınırlı). */
    public long cronDeferMs() {
        return cronDeferMs;
    }

    /**
     * Lider bu yan işi başka pod'a bırakmalı mı: kural açık, bu pod tarama lideri ({@code leaderHint}) ve başka canlı pod KESİN
     * var. Sıra ucuzdan pahalıya — tek pod'da / takipçide heartbeat sorgusu atılmaz.
     */
    public boolean leaderShouldYield(boolean leaderHint) {
        if (!leaderHint) return false;
        if (!yieldEnabled()) return false;
        return liveness != null && liveness.anotherLivePod();
    }

    /** Veritabanına gitmeden son canlı pod gözlemi (null = bilinmiyor). */
    public Boolean cachedOtherLivePod() {
        return liveness == null ? null : liveness.cachedAnotherLivePod();
    }
}
