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
 * {@link #invalidate} çağırır, öteki pod'lar en çok 30 sn'de görür). Okuma düşerse {@value #FAILED_RETRY_MS} ms sonra yeniden
 * denenir; açılıştan beri HİÇ okunamadıysa durum {@link State#UNKNOWN} (çağıran SLOW kalemi üretmez). Değerlendirme YALNIZ lider üzerinde çağrılır (SLOW
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
    /** En az bir yapılandırma okuması BAŞARILI oldu mu — olmadıysa açık izlemeler BİLİNMİYOR ({@link State#UNKNOWN}). */
    private volatile boolean everLoaded;
    /** Son okuma başarısız mı — öyleyse yeniden deneme {@link #FAILED_RETRY_MS} sonra (30 sn değil). */
    private volatile boolean lastFailed;
    private volatile long lastFailureWarnAt;
    private final Object refreshLock = new Object();

    /** Başarısız yapılandırma okumasının yeniden deneme aralığı (ms) — hata taraması 2026-11-13 #10. */
    static final long FAILED_RETRY_MS = 5_000L;

    /**
     * Bir izlemenin taban çizgisi durumu (hata taraması 2026-11-13 #10). {@link #UNKNOWN} = açılıştan beri yapılandırma hiç
     * okunamadı: hangi izlemenin bant kullandığı bilinmez — çağıran SLOW kalemi ÜRETMEZ (ne açar ne kapatır), sabit eşiğin
     * sentetik "up"ı açık bant alarmını sahte kapatmasın. Başarılı bir anlık görüntü varken okuma hatası onu korur (bilinir).
     */
    public enum State { DISABLED, ENABLED, UNKNOWN }

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

    /** Ana anahtar açık mı (CANLI) — API / diyalog "ana anahtar kapalı" bandı için. */
    public boolean switchOn() {
        return globalEnabled();
    }

    /** Açık yapılandırmayı döndürür (yoksa / kapalıysa / bilinmiyorsa null) — 30 sn'lik bellek anlık görüntüsünden. */
    public MonitorBaselineConfig config(String type, long monitorId) {
        if (!globalEnabled() || configRepo == null || !BaselineTypes.supported(type)) return null;
        return snapshot().get(key(type, monitorId));
    }

    /**
     * Bu izlemenin taban çizgisi durumu: ana anahtar kapalı / bean yok / desteklenmeyen tür → {@link State#DISABLED}; hiç
     * başarılı okuma yoksa {@link State#UNKNOWN}; aksi hâlde anlık görüntüde açık mı.
     */
    public State state(String type, long monitorId) {
        if (!globalEnabled() || configRepo == null || !BaselineTypes.supported(type)) return State.DISABLED;
        Map<String, MonitorBaselineConfig> snap = snapshot();
        if (!everLoaded) return State.UNKNOWN;
        return snap.containsKey(key(type, monitorId)) ? State.ENABLED : State.DISABLED;
    }

    private Map<String, MonitorBaselineConfig> snapshot() {
        long ttl = lastFailed ? FAILED_RETRY_MS : Math.max(1000, cacheMs);
        if (System.currentTimeMillis() - loadedAt > ttl) return refresh();
        return enabledSnapshot;
    }

    private Map<String, MonitorBaselineConfig> refresh() {
        synchronized (refreshLock) {
            long ttl = lastFailed ? FAILED_RETRY_MS : Math.max(1000, cacheMs);
            if (System.currentTimeMillis() - loadedAt <= ttl) return enabledSnapshot;
            try {
                List<MonitorBaselineConfig> rows = configRepo.findByEnabledTrue();
                Map<String, MonitorBaselineConfig> m = new HashMap<>(rows.size() * 2);
                for (MonitorBaselineConfig c : rows) {
                    if (c.isEnabled() && c.getMonitorType() != null && c.getMonitorId() != null)
                        m.put(key(c.getMonitorType(), c.getMonitorId()), c);
                }
                enabledSnapshot = Map.copyOf(m);
                everLoaded = true;
                lastFailed = false;
            } catch (RuntimeException e) {
                lastFailed = true;
                long now = System.currentTimeMillis();
                if (now - lastFailureWarnAt > 60_000L) {
                    lastFailureWarnAt = now;
                    log.warn("Taban çizgisi yapılandırması okunamadı ({}) — {}; {} sn sonra yeniden denenecek", e.getClass().getSimpleName(),
                            everLoaded ? "önceki anlık görüntü korunuyor"
                                    : "hiç okunamadığı için bant izlemelerinde yavaşlık kalemi üretilmiyor",
                            FAILED_RETRY_MS / 1000);
                }
            }
            loadedAt = System.currentTimeMillis();
            return enabledSnapshot;
        }
    }

    /** Yapılandırma kaydedilince / silinince aynı pod'da anlık görüntüyü hemen bayatlatır. */
    public void invalidate() {
        loadedAt = 0;
    }

    /** Bu izlemede taban çizgisi AÇIK mı (30 sn bellek anlık görüntüsü) — ölçülemeyen kontrolde de SLOW builder'ı bant yoluna sokmak için. */
    public boolean enabledFor(String type, long monitorId) {
        return config(type, monitorId) != null;
    }

    /** Taban çizgisi açık izleme var mı (bellek) — çağıran gereksiz işten kaçınır. */
    public boolean anyEnabled() {
        if (!globalEnabled() || configRepo == null) return false;
        return !snapshot().isEmpty();
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
     * aynı saatine ± {@value BaselineSampleStore#SEASON_TOLERANCE_MIN_MS} ms düşen ve EN AZ BİR PERİYOT önceki örnekler
     * ({@link BaselineSampleStore#readSeasonal} — bugünün / bu haftanın son yarım saati "aynı saat" sayılmaz; hata taraması
     * 2026-11-13 #3). Tolerans SABİT (±30 dk) — zamanlanmış değerlendirme ve recheck AYNI pencereyi görsün.
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
     * Kayıtlı yapılandırmanın hazırlığı (diyalog — hata taraması 2026-11-13 #8): GERÇEK değerlendirme penceresi okunur (mevsimsel
     * seçimde yalnız eşleşen saatler) ve karar matematiğinin örnek sayısı / notu döner; örnek YAZMAZ. Değer önemsizdir (örnek
     * seçimi değere bağlı değil).
     */
    public Verdict readiness(String type, long monitorId, BaselineParams params) {
        BaselineParams p = (params == null ? BaselineParams.defaults() : params).normalized();
        History h = readHistory(type, monitorId, System.currentTimeMillis(), p, null);
        return BaselineMath.evaluate(0, h, p);
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

    /**
     * Günlük budama ({@code BaselinePruneJob}, küme genelinde tek pod) — izleme başına en yeni
     * {@link BaselineSampleStore#KEEP_PER_MONITOR} satırı tutar; MEVSİMSEL izlemede ayrıca mevsimsel okumanın ihtiyaç duyduğu
     * süreyi korur ({@link BaselineSampleStore#seasonalKeepFromMs}) — yalnız satır sayısına göre budama kısa aralıklı izlemede
     * dünü / geçen haftayı silip tabanı kalıcı olarak değerlendirilemez bırakıyordu. Ana anahtar kapalıyken de koşar (örnekler
     * saklı kalır). {@code stop}: pod kapanıyor / bütçe doldu → yarıda bırakır (ertesi gün kalanı budar).
     */
    public BaselineSampleStore.PruneResult prune(java.util.function.BooleanSupplier stop) {
        return store.prune(BaselineSampleStore.KEEP_PER_MONITOR, seasonNeeds(), System.currentTimeMillis(), stop);
    }

    /** En uzun koruma: yapılandırması okunamayan / bulunmayan izleme için güvenli varsayılan (haftalık, en büyük pencere). */
    static final BaselineSampleStore.SeasonNeed LONGEST_NEED =
            new BaselineSampleStore.SeasonNeed(BaselineSampleStore.WEEK_MS, BaselineParams.WINDOW_MAX);

    /**
     * Budama planı — yapılandırma tablosundan TAZE okunur (ana anahtardan bağımsız): açık ve mevsimsiz → {@code null} (yalnız
     * satır sınırı); açık ve mevsimsel → periyot + pencere; açık yapılandırması olmayan (silinmiş / kapalı ama örneği kalmış)
     * izleme ya da okuma hatası → {@link #LONGEST_NEED} (veri silmede şüphede koru).
     */
    BaselineSampleStore.SeasonNeeds seasonNeeds() {
        if (configRepo == null) return (t, id) -> LONGEST_NEED;
        Map<String, BaselineSampleStore.SeasonNeed> needs = new HashMap<>();
        java.util.Set<String> plain = new java.util.HashSet<>();
        try {
            for (MonitorBaselineConfig c : configRepo.findByEnabledTrue()) {
                if (c == null || c.getMonitorType() == null || c.getMonitorId() == null) continue;
                String k = key(c.getMonitorType(), c.getMonitorId());
                BaselineParams p = c.toParams().normalized();
                if (!p.seasonal()) { plain.add(k); continue; }
                long period = BaselineParams.SEASON_WEEKLY.equals(p.season()) ? BaselineSampleStore.WEEK_MS : BaselineSampleStore.DAY_MS;
                needs.put(k, new BaselineSampleStore.SeasonNeed(period, p.window()));
            }
        } catch (RuntimeException e) {
            log.warn("Taban çizgisi budama planı okunamadı ({}) — her izleme en uzun mevsimsel süreyle korunuyor",
                    e.getClass().getSimpleName());
            return (t, id) -> LONGEST_NEED;
        }
        return (t, id) -> {
            String k = key(t, id);
            if (plain.contains(k)) return null;
            BaselineSampleStore.SeasonNeed n = needs.get(k);
            return n != null ? n : LONGEST_NEED;
        };
    }
}
