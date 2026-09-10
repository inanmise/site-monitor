package com.sitemonitor.service;

import com.sitemonitor.util.Msg;
import com.sitemonitor.config.TunableThreadPoolTaskExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Genel Ayarlar → "Görev Havuzu" grubunu {@code certCheckExecutor}'a CANLI uygular.
 *
 * <p>Eskiden havuz (core/max) ve kuyruk yalnız {@code EXECUTOR_*} env'inden okunuyordu: prod'da
 * değiştirmek Helm upgrade + pod yeniden başlatma demekti ve pod values sürüklenmesi
 * ({@code dbPoolMax}, {@code LOG_LEVEL}) yüzünden düz upgrade risksiz değildi. Şimdi üç değer
 * {@link AppSettingsCatalog}'da; kaydedilince {@link AppSettingsChangedEvent} bu servisi
 * uyandırır ve executor yeniden boyutlanır — yeniden başlatma yok. Çok-pod'da diğer pod'lar
 * periyodik tazelemeyle aynı olayı alır.
 *
 * <p>Ayar YOKSA (override kaldırıldı) env/properties varsayılanına döner — yani
 * {@code AppSettingsService.getInt(key, fallback)} zaten Environment'ı okuduğundan burada
 * fallback executor'ın o anki değeridir; boş ayar = varsayılan anlamı korunur.
 */
@Slf4j
@Service
public class ExecutorTuningService {

    public static final String CORE_KEY  = "site.monitor.executor.core-size";
    public static final String MAX_KEY   = "site.monitor.executor.max-size";
    public static final String QUEUE_KEY = "site.monitor.executor.queue-capacity";

    private final AppSettingsService appSettings;
    private final TunableThreadPoolTaskExecutor executor;

    public ExecutorTuningService(AppSettingsService appSettings,
                                 @Qualifier("certCheckExecutor") TunableThreadPoolTaskExecutor executor) {
        this.appSettings = appSettings;
        this.executor = executor;
    }

    /** Boot: DB'de saklı override varsa env değerinin üstüne uygula (yeniden başlatmada kaybolmaz). */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        apply("boot");
    }

    @EventListener
    public void onSettingsChanged(AppSettingsChangedEvent ev) {
        if (!ev.touches(CORE_KEY) && !ev.touches(MAX_KEY) && !ev.touches(QUEUE_KEY)) return;
        apply(ev.source());
    }

    /**
     * Etkin değerleri okur, geçersiz kombinasyonu (core&lt;1, max&lt;core, kuyruk&lt;1) UYGULAMAZ
     * ve uyarır (kayıt yolu zaten reddeder; bu, DB'ye elle yazılmış bozuk değere karşı savunma).
     *
     * @return değişiklik uygulandıysa true
     */
    public synchronized boolean apply(String source) {
        int curCore  = executor.getCorePoolSize();
        int curMax   = executor.getMaxPoolSize();
        int curQueue = executor.getQueueCapacity();

        int core  = appSettings.getInt(CORE_KEY,  curCore);
        int max   = appSettings.getInt(MAX_KEY,   curMax);
        int queue = appSettings.getInt(QUEUE_KEY, curQueue);

        String problem = validate(core, max, queue);
        if (problem != null) {
            log.warn("certCheckExecutor tuning SKIPPED ({}): {} — keeping core {} / max {} / queue {}",
                    source, problem, curCore, curMax, curQueue);
            return false;
        }
        boolean changed = false;
        if (core != curCore || max != curMax) {
            executor.applyPoolSizes(core, max);
            changed = true;
        }
        if (queue != curQueue) {
            executor.applyQueueCapacity(queue);
            changed = true;
        }
        if (changed) {
            log.info("certCheckExecutor retuned ({}): core {}→{} / max {}→{} / queue {}→{} (live, no restart)",
                    source, curCore, core, curMax, max, curQueue, queue);
        }
        return changed;
    }

    /** Ortak kural — {@link AppSettingsService#save} de kayıt öncesi bunu çağırır. Null = geçerli. */
    public static String validate(int core, int max, int queue) {
        if (core < 1)   return Msg.t("core-size >= 1 olmalı (", "core-size must be >= 1 (") + core + ")";
        if (max < core) return Msg.t("max-size core-size'dan küçük olamaz (core ", "max-size cannot be smaller than core-size (core ") + core + ", max " + max + ")";
        if (queue < 1)  return Msg.t("queue-capacity >= 1 olmalı (", "queue-capacity must be >= 1 (") + queue + ")";
        return null;
    }
}
