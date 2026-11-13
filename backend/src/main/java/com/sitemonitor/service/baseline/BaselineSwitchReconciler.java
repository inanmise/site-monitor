package com.sitemonitor.service.baseline;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.MonitorBaselineConfig;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.MonitorBaselineConfigRepository;
import com.sitemonitor.service.AppSettingsChangedEvent;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.SilentAlarmCloser;
import com.sitemonitor.service.schedule.ClusterLockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Dinamik taban ANA ANAHTARI kapatılınca askıda kalan bant alarmlarının sessiz kapanışı (hata taraması 2026-11-13 #4).
 * Anahtar kapalıyken {@code BaselineService.config} null döner ve SLOW builder sabit eşik yoluna döner. Port / keyword / ping /
 * sentetik / sayfa hızı / DNS'te sabit yol bir kalem üretir (özellik kapalıyken sentetik "up") ve açık alarm normal kurtarmayla
 * kapanır; HTTP'de sabit yavaşlık KAPALIYSA {@code httpSlowSweepItem} hiç kalem üretmez — bant alarmı hiçbir yolla kapanmazdı.
 * Bu bileşen yalnız o durumu kapatır: taban yapılandırması etkin olan ve sabit yavaşlığı kapalı HTTP izlemelerinin açık
 * {@code HTTP_SLOW} alarmları {@link SilentAlarmCloser} ile koşullu ve sessiz kapanır ({@value #CLOSED_BY}). Sabit eşik açık
 * izlemede sabit yol karar verir — dokunulmaz.
 *
 * <p>Ayar kaydında (o pod, kilit meşgulse sınırlı yeniden deneme), başka pod'un kaydını tazelemede ilk gördüğünde (anahtar
 * açık → kapalı geçişi, tek deneme) ve açılışta koşar. Küme genelinde {@code scheduler_lock} {@value #LOCK} altında tek pod;
 * idempotent (kapanmış alarm yeniden yazılmaz, push yalnız kapatanda). Anahtar açıksa ya da etkin HTTP yapılandırması yoksa
 * kilit / sorgu yok. Adlar KALICI.</p>
 */
@Slf4j
@Component
public class BaselineSwitchReconciler {

    /** Kilit adı — KALICI. */
    public static final String LOCK = "baseline-switch-reconcile";
    /** Kapanış nedeni (Alarm Geçmişi "Çözen"). */
    public static final String CLOSED_BY = "Sistem (dinamik taban kapatıldı)";
    static final int ATTEMPTS = 6;
    long retryDelayMs = 10_000L;

    private final BaselineService baselineService;
    private final MonitorBaselineConfigRepository configRepo;
    private final HttpMonitorRepository httpRepo;
    private final AlertEventRepository alertRepo;
    private final SilentAlarmCloser closer;

    @Autowired(required = false)
    private ClusterLockService locks;

    /** Bu pod'un son gördüğü ana anahtar değeri (null = henüz bakılmadı) — tazeleme yolunda yalnız açık → kapalı geçişi koşar. */
    private volatile Boolean lastSwitch;

    /** Arka plan yürütücüsü (ayar kaydının işlemini bekletmesin) — paket-özel: testte eşzamanlı yürütücüyle değiştirilir. */
    Executor executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "baseline-switch-reconcile");
        t.setDaemon(true);
        return t;
    });

    public BaselineSwitchReconciler(BaselineService baselineService, MonitorBaselineConfigRepository configRepo,
                                    HttpMonitorRepository httpRepo, AlertEventRepository alertRepo, SilentAlarmCloser closer) {
        this.baselineService = baselineService;
        this.configRepo = configRepo;
        this.httpRepo = httpRepo;
        this.alertRepo = alertRepo;
        this.closer = closer;
    }

    void setClusterLockService(ClusterLockService locks) {
        this.locks = locks;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSettingsChanged(AppSettingsChangedEvent ev) {
        if (ev == null || !ev.touches(BaselineService.KEY_ENABLED)) return;
        boolean on = baselineService.switchOn();
        Boolean prev = lastSwitch;
        lastSwitch = on;
        if (on) return;
        boolean save = !"refresh".equals(ev.source());
        // Tazeleme: yalnız bu pod'un ilk gördüğü açık → kapalı geçişi (her tazelemede yeniden sorgulanmasın)
        if (!save && Boolean.FALSE.equals(prev)) return;
        try {
            executor.execute(save ? () -> reconcileWithRetry("ayar değişikliği") : () -> reconcile("ayar tazelemesi"));
        } catch (Exception e) {
            log.warn("Dinamik taban kapanış uzlaştırması kuyruğa alınamadı: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        lastSwitch = baselineService.switchOn();
        if (Boolean.TRUE.equals(lastSwitch)) return;
        try {
            executor.execute(() -> reconcile("açılış"));
        } catch (Exception e) {
            log.debug("Dinamik taban açılış uzlaştırması kuyruğa alınamadı: {}", e.getMessage());
        }
    }

    void reconcileWithRetry(String reason) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (reconcile(reason) >= 0) return;
            if (locks == null || locks.acquiringStopped() || attempt == ATTEMPTS) break;
            try {
                Thread.sleep(Math.max(0L, retryDelayMs));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Dinamik taban kapanış uzlaştırması kilit alamadı ({}) — sonraki açılış ya da ayar kaydı telafi eder", reason);
    }

    /**
     * @return kapatılan alarm sayısı; {@code -1} = kilit başka pod'da / pod kapanıyor (hiçbir şey yapılmadı)
     */
    int reconcile(String reason) {
        try {
            if (baselineService.switchOn()) return 0;   // anahtar yeniden açıldı: bant yolu karar verir
            List<MonitorBaselineConfig> http = configRepo.findByEnabledTrue().stream()
                    .filter(c -> BaselineTypes.HTTP.equals(c.getMonitorType()) && c.getMonitorId() != null).toList();
            if (http.isEmpty()) return 0;
            if (locks == null) return closeHanging(http, reason);
            var hold = locks.tryAcquire(LOCK, Duration.ofMinutes(2), ClusterLockService.hardCapFor(LOCK));
            if (hold.isEmpty()) return -1;
            try {
                return closeHanging(http, reason);
            } finally {
                locks.release(hold.get());
            }
        } catch (Exception e) {
            log.warn("Dinamik taban kapanış uzlaştırması başarısız ({}): {}", reason, e.getMessage());
            return 0;
        }
    }

    private int closeHanging(List<MonitorBaselineConfig> http, String reason) {
        int n = 0;
        for (MonitorBaselineConfig c : http) {
            HttpMonitor m = httpRepo.findById(c.getMonitorId()).orElse(null);
            // Sabit yavaşlık açıksa sabit yol karar verir (kalem üretir, alarm normal kurtarmayla kapanır) — dokunma
            if (m == null || m.getUrl() == null || Boolean.TRUE.equals(m.getSlowResponseEnabled())) continue;
            Map<String, Object> owner = new HashMap<>();
            owner.put("monitor_id", m.getId());
            if (m.getTeamId() != null) owner.put("team_id", m.getTeamId());
            for (AlertEvent e : alertRepo.findOpenAlerts(m.getUrl(), EscalationService.TYPE_HTTP_SLOW)) {
                if (!EscalationService.closableBy(e, owner)) continue;
                if (closer.closeIfOpen(e, CLOSED_BY)) n++;
            }
        }
        if (n > 0) log.info("Dinamik taban kapatıldı ({}): sabit yavaşlığı kapalı {} HTTP izlemesinin açık bant alarmı sessizce kapandı",
                reason, n);
        return n;
    }
}
