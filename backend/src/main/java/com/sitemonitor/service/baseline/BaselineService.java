package com.sitemonitor.service.baseline;

import com.sitemonitor.model.MonitorBaselineConfig;
import com.sitemonitor.repository.MonitorBaselineConfigRepository;
import com.sitemonitor.service.baseline.BaselineMath.History;
import com.sitemonitor.service.baseline.BaselineMath.Verdict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dinamik taban çizgisi düzenleme katmanı (2026-11-12) — pod başına. Tarama lideri, taban çizgisi AÇIK bir izlemenin her
 * ZAMANLANMIŞ kontrolünde {@link #evaluateScheduled} çağırır: açık yapılandırmanın parametreleriyle en yeni örnekleri okur
 * ({@link BaselineSampleStore#readRecent}, GEÇERLİ değeri hariç), {@link BaselineMath} ile bantı değerlendirir, YENİ örneği
 * (değer + ihlal bayrağı) EKLER ve kararı döndürür. Teyit / kurtarma rechecklerinde {@link #evaluateRecheck}: örnek YAZMAZ
 * (recheck'ler tabandan dışlanır — zincir boyunca bant sabit kalır). Taban çizgisi KAPALI izlemede ({@code enabled=false}
 * ya da satır yok, ya da global anahtar kapalı) {@code null} döner → çağıran SLOW builder'ı SABİT eşik yolunu kullanır
 * (davranış birebir eskisi gibi).
 *
 * <p>Açık yapılandırmalar bellek anlık görüntüsünden okunur ({@code config-cache-ms}, öntanımlı 30 sn; kaydetme yolu
 * {@link #invalidate} çağırır, öteki pod'lar en çok 30 sn'de görür). Değerlendirme YALNIZ lider üzerinde çağrılır (SLOW
 * item'ları yalnız lider kurar), bu yüzden ayrı lider kapısı yoktur; global anahtar yine de bir emniyet valfidir.</p>
 */
@Slf4j
@Service
public class BaselineService {

    /**
     * SLOW builder'ına enjekte edilen bağlam anahtarları (TEK kaynak — hem enjekte eden sweep gövdesi hem okuyan builder
     * bunları kullanır). {@link #CTX_ON} varsa builder SABİT eşik yerine öğrenen bant yolunu kullanır.
     */
    public static final String CTX_ON = "baseline_on", CTX_EVALUATED = "baseline_evaluated", CTX_LEVEL = "baseline_level",
            CTX_MEAN = "baseline_mean", CTX_LOW = "baseline_low", CTX_HIGH = "baseline_high",
            CTX_DEVIATION = "baseline_deviation", CTX_NOTE = "baseline_note", CTX_VALUE = "baseline_value";

    /** Taban-çizgisi SLOW kaleminin KARARLI {@code detail} eki (teyit zinciri anahtarı her sweep'te değişmesin). */
    public static final String SLOW_DETAIL = "dinamik taban";

    /** Ana anahtar ayar anahtarı — KALICI ({@code AppSettingsCatalog}, BOOL, grup monitoring, GLOBAL_ONLY, CANLI). */
    public static final String KEY_ENABLED = "site.monitor.baseline.enabled";

    private final MonitorBaselineConfigRepository configRepo;
    private final BaselineSampleStore store;

    /** Ana anahtarın CANLI okunması (kaydetme → 30 sn ayar yenilemesi). Yoksa (birim test) {@link #globalEnabled} alanı. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.AppSettingsService appSettings;

    @Value("${site.monitor.baseline.enabled:true}")
    private boolean globalEnabled;
    @Value("${site.monitor.baseline.config-cache-ms:30000}")
    private long cacheMs;

    private volatile Map<String, MonitorBaselineConfig> enabledSnapshot = Map.of();
    private volatile long loadedAt;
    private final Object refreshLock = new Object();

    public BaselineService(MonitorBaselineConfigRepository configRepo, BaselineSampleStore store) {
        this.configRepo = configRepo;
        this.store = store;
    }

    private static String key(String type, long id) {
        return type + ":" + id;
    }

    /** Ana anahtar şu an açık mı — CANLI ({@link com.sitemonitor.service.AppSettingsService}); servis yoksa / okuma hatasında boot değeri. */
    private boolean globalEnabled() {
        if (appSettings == null) return globalEnabled;
        try {
            return appSettings.getBoolean(KEY_ENABLED, globalEnabled);
        } catch (RuntimeException e) {
            return globalEnabled;
        }
    }

    /** Açık yapılandırmayı döndürür (yoksa / kapalıysa null) — 30 sn'lik bellek anlık görüntüsünden. */
    public MonitorBaselineConfig config(String type, long monitorId) {
        if (!globalEnabled() || configRepo == null || !BaselineTypes.supported(type)) return null;
        Map<String, MonitorBaselineConfig> snap = enabledSnapshot;
        if (System.currentTimeMillis() - loadedAt > Math.max(1000, cacheMs)) snap = refresh();
        return snap.get(key(type, monitorId));
    }

    private Map<String, MonitorBaselineConfig> refresh() {
        synchronized (refreshLock) {
            if (System.currentTimeMillis() - loadedAt <= Math.max(1000, cacheMs)) return enabledSnapshot;
            try {
                List<MonitorBaselineConfig> rows = configRepo.findByEnabledTrue();
                Map<String, MonitorBaselineConfig> m = new HashMap<>(rows.size() * 2);
                for (MonitorBaselineConfig c : rows) {
                    if (c.isEnabled() && c.getMonitorType() != null && c.getMonitorId() != null)
                        m.put(key(c.getMonitorType(), c.getMonitorId()), c);
                }
                enabledSnapshot = Map.copyOf(m);
            } catch (RuntimeException e) {
                log.debug("Taban çizgisi yapılandırması yenilenemedi (önceki anlık görüntü korunuyor): {}", e.getMessage());
            }
            loadedAt = System.currentTimeMillis();
            return enabledSnapshot;
        }
    }

    /** Yapılandırma kaydedilince / silinince aynı pod'da anlık görüntüyü hemen bayatlatır. */
    public void invalidate() {
        loadedAt = 0;
    }

    /** Taban çizgisi açık izleme var mı (bellek) — çağıran gereksiz işten kaçınır. */
    public boolean anyEnabled() {
        if (!globalEnabled() || configRepo == null) return false;
        if (System.currentTimeMillis() - loadedAt > Math.max(1000, cacheMs)) refresh();
        return !enabledSnapshot.isEmpty();
    }

    /**
     * Zamanlanmış kontrolün kararı (lider). Taban çizgisi kapalıysa null. Açıksa: bantı değerlendirir, SONRA örneği
     * (değer + ihlal) ekler (taban kuruluyorsa da ekler — taban dolsun). {@code intervalSec} v1'de kullanılmaz (mevsimsel).
     */
    public Verdict evaluateScheduled(String type, long monitorId, long valueMs, long checkedMs, Integer intervalSec) {
        MonitorBaselineConfig cfg = config(type, monitorId);
        if (cfg == null) return null;
        BaselineParams params = cfg.toParams();
        History h = readHistory(type, monitorId, checkedMs, params, intervalSec);
        Verdict v = BaselineMath.evaluate(valueMs, h, params);
        store.append(type, monitorId, checkedMs, valueMs, v.breached());
        return v;
    }

    /**
     * Geçmiş okuma planı: mevsimsiz → en yeni N örnek ({@link BaselineSampleStore#readRecent}); mevsimsel → günün/haftanın
     * aynı saatine ± {@value BaselineSampleStore#SEASON_TOLERANCE_MIN_MS} ms düşen örnekler
     * ({@link BaselineSampleStore#readSeasonal}). Tolerans SABİT (±30 dk) — zamanlanmış değerlendirme ve recheck AYNI pencereyi
     * görsün (aralığa bağlasaydık uzun aralıklı izlemede recheck daha dar bir pencere okuyup teyidi zayıflatabilirdi).
     * {@code intervalSec} imza kararlılığı için tutulur (v1'de kullanılmaz). {@code beforeMs} zamanlanmışta kontrol anı,
     * recheck'te recheck anı.
     */
    private History readHistory(String type, long monitorId, long beforeMs, BaselineParams params, Integer intervalSec) {
        BaselineParams p = params.normalized();
        if (!p.seasonal())
            return store.readRecent(type, monitorId, beforeMs, BaselineSampleStore.recentNeed(p));
        long period = BaselineParams.SEASON_WEEKLY.equals(p.season()) ? BaselineSampleStore.WEEK_MS : BaselineSampleStore.DAY_MS;
        long nowPos = Math.floorMod(beforeMs, period);
        return store.readSeasonal(type, monitorId, beforeMs, p.window(), period, nowPos,
                BaselineSampleStore.SEASON_TOLERANCE_MIN_MS);
    }

    /**
     * Teyit / kurtarma recheck'inin kararı (lider). Örnek YAZMAZ (recheck tabandan dışlanır); bant zamanlanmış örneklerle
     * sabittir. Taban çizgisi kapalıysa null.
     */
    public Verdict evaluateRecheck(String type, long monitorId, long valueMs, long checkedMs, Integer intervalSec) {
        MonitorBaselineConfig cfg = config(type, monitorId);
        if (cfg == null) return null;
        BaselineParams params = cfg.toParams();
        History h = readHistory(type, monitorId, checkedMs, params, intervalSec);
        return BaselineMath.evaluate(valueMs, h, params);
    }

    /** İzleme silinince / hedefi değişince: örnekleri temizle (eski ölçüler yeni hedefe karışmasın). */
    public void clearSamples(String type, long monitorId) {
        store.deleteForMonitor(type, monitorId);
    }

    /** Günlük budama (lider) — izleme başına en yeni {@link BaselineSampleStore#KEEP_PER_MONITOR} satırı tutar. */
    public int prune() {
        return store.prune(BaselineSampleStore.KEEP_PER_MONITOR);
    }
}
