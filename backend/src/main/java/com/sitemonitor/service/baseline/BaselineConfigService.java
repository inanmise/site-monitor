package com.sitemonitor.service.baseline;

import com.sitemonitor.model.MonitorBaselineConfig;
import com.sitemonitor.repository.MonitorBaselineConfigRepository;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dinamik taban çizgisi izleme-başına yapılandırmasının okunması / kaydedilmesi / doğrulanması (2026-11-12). Türden
 * bağımsız: anahtar {@code (monitorType, monitorId)}. Kaydet geçiş bilgisi döndürür ({@link SaveResult}) — çağıran denetleyici
 * aç↔kapa / kip değişiminde o izlemenin açık SLOW alarmlarını kapatır (bant kipinin zincir anahtarı sabit eşiğinkinden
 * farklı — açık alarm askıda kalmasın) ve KAPATMADA örnekleri temizler. Doğrulama {@link BaselineParams} sınırlarıyla
 * birebir; alan hataları {@code {field → message}}.
 */
@Slf4j
@Service
public class BaselineConfigService {

    private final MonitorBaselineConfigRepository repo;
    private final BaselineSampleStore store;
    private final BaselineService baselineService;

    public BaselineConfigService(MonitorBaselineConfigRepository repo, BaselineSampleStore store,
                                 BaselineService baselineService) {
        this.repo = repo;
        this.store = store;
        this.baselineService = baselineService;
    }

    /** Kaydetme sonucu: geçiş (çağıran alarm kapatır) + alan hataları. */
    public record SaveResult(boolean wasEnabled, boolean nowEnabled, Map<String, String> errors) {
        public boolean ok() {
            return errors == null || errors.isEmpty();
        }

        /** Aç↔kapa ya da kip değişimi: açık SLOW alarmlarının kapatılması gereken geçiş. */
        public boolean transition() {
            return wasEnabled != nowEnabled;
        }
    }

    /** Yapılandırmayı (ya da öntanımlıyı) + örnek sayısını döndürür (API gösterimi). */
    @Transactional(readOnly = true)
    public Map<String, Object> view(String type, long monitorId) {
        MonitorBaselineConfig c = repo.findByMonitorTypeAndMonitorId(type, monitorId).orElse(null);
        BaselineParams p = c != null ? c.toParams() : BaselineParams.defaults();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", c != null && c.isEnabled());
        out.put("direction", p.direction());
        out.put("k_warning", p.kWarning());
        out.put("k_critical", p.kCritical());
        out.put("window", p.window());
        out.put("min_samples", p.minSamples());
        out.put("season", p.season());
        out.put("sample_count", store.count(type, monitorId));
        out.put("defaults", c == null);
        // Hazırlık (hata taraması 2026-11-13 #8): toplam örnek sayısı "hazır" demeye yetmez (mevsimselde yalnız eşleşen saatler
        // sayılır, ana anahtar kapalı olabilir). Ana anahtar bellekten; hazırlık GERÇEK değerlendirme penceresinden (tek okuma,
        // örnek yazmaz) — yalnız kayıtlı ve açık yapılandırmada.
        if (baselineService != null) {
            out.put("global_enabled", baselineService.switchOn());
            if (c != null && c.isEnabled()) {
                BaselineMath.Verdict v = baselineService.readiness(type, monitorId, p);
                out.put("ready_state", v.evaluated() ? "ready"
                        : BaselineMath.NOTE_INSUFFICIENT_SAMPLES.equals(v.note()) ? "insufficient" : "building");
                out.put("ready_samples", v.samples());
                out.put("ready_min", v.minSamples());
            }
        }
        return out;
    }

    /**
     * Yapılandırmayı kaydeder. Gönderilen alanlar: {@code enabled} (zorunlu bool), {@code direction, k_warning, k_critical,
     * window, min_samples, season} (isteğe bağlı — boş = öntanımlı). Geçersiz değer → alan hatası (hiçbir şey yazılmaz).
     * Kapatmada örnekler silinir; her kayıt önbelleği bayatlatır.
     */
    @Transactional
    public SaveResult save(String type, long monitorId, Map<String, Object> body) {
        Map<String, String> errors = validate(body);
        MonitorBaselineConfig existing = repo.findByMonitorTypeAndMonitorId(type, monitorId).orElse(null);
        boolean wasEnabled = existing != null && existing.isEnabled();
        if (!errors.isEmpty()) return new SaveResult(wasEnabled, wasEnabled, errors);

        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        MonitorBaselineConfig c = existing != null ? existing : new MonitorBaselineConfig();
        if (existing == null) {
            c.setMonitorType(type);
            c.setMonitorId(monitorId);
            c.setCreatedAt(Instant.now().toString());
        }
        c.setEnabled(enabled);
        c.setDirection(str(body.get("direction")));
        c.setKWarning(dbl(body.get("k_warning")));
        c.setKCritical(dbl(body.get("k_critical")));
        c.setWindowSize(intg(body.get("window")));
        c.setMinSamples(intg(body.get("min_samples")));
        c.setSeason(str(body.get("season")));
        c.setUpdatedAt(Instant.now().toString());
        repo.save(c);

        if (!enabled) store.deleteForMonitor(type, monitorId);   // kapatıldı → eski ölçüler yeni dönemde karışmasın
        if (baselineService != null) baselineService.invalidate();
        return new SaveResult(wasEnabled, enabled, Map.of());
    }

    /** İzleme silinince / hedefi değişince: yapılandırma + örnekleri temizle. */
    @Transactional
    public void remove(String type, long monitorId) {
        repo.deleteByMonitorTypeAndMonitorId(type, monitorId);
        store.deleteForMonitor(type, monitorId);
        if (baselineService != null) baselineService.invalidate();
    }

    /** Hedef (URL/host) değişti ama izleme duruyor: yalnız örnekleri temizle (yapılandırma kalır). */
    public void clearSamples(String type, long monitorId) {
        store.deleteForMonitor(type, monitorId);
    }

    // ── Doğrulama ────────────────────────────────────────────────────────────

    private Map<String, String> validate(Map<String, Object> body) {
        Map<String, String> e = new LinkedHashMap<>();
        if (!(body.get("enabled") instanceof Boolean)) {
            e.put("enabled", Msg.t("Açık/kapalı değeri gerekli.", "The enabled flag is required."));
            return e;   // gerisi anlamsız
        }
        Double kw = dbl(body.get("k_warning")), kc = dbl(body.get("k_critical"));
        checkRange(e, "k_warning", kw, BaselineParams.K_MIN, BaselineParams.K_MAX);
        checkRange(e, "k_critical", kc, BaselineParams.K_MIN, BaselineParams.K_MAX);
        if (kw != null && kc != null && kc < kw)
            e.put("k_critical", Msg.t("Kritik katsayı uyarı katsayısından küçük olamaz.",
                    "The critical multiplier cannot be smaller than the warning multiplier."));
        Integer w = intg(body.get("window")), ms = intg(body.get("min_samples"));
        checkRange(e, "window", w == null ? null : w.doubleValue(), BaselineParams.WINDOW_MIN, BaselineParams.WINDOW_MAX);
        int hi = w != null && w >= BaselineParams.WINDOW_MIN && w <= BaselineParams.WINDOW_MAX ? w : BaselineParams.WINDOW_MAX;
        checkRange(e, "min_samples", ms == null ? null : ms.doubleValue(), BaselineParams.MIN_SAMPLES_MIN, hi);
        String dir = str(body.get("direction"));
        if (dir != null && !List.of(BaselineParams.DIRECTION_UP, BaselineParams.DIRECTION_DOWN, BaselineParams.DIRECTION_BOTH).contains(dir))
            e.put("direction", Msg.t("Geçersiz yön.", "Invalid direction."));
        String se = str(body.get("season"));
        if (se != null && !List.of(BaselineParams.SEASON_NONE, BaselineParams.SEASON_DAILY, BaselineParams.SEASON_WEEKLY).contains(se))
            e.put("season", Msg.t("Geçersiz mevsim.", "Invalid season."));
        return e;
    }

    private static void checkRange(Map<String, String> e, String field, Double v, double lo, double hi) {
        if (v == null) return;   // boş = öntanımlı
        if (v < lo || v > hi)
            e.put(field, Msg.t(field + " değeri " + trim(lo) + " ile " + trim(hi) + " arasında olmalı.",
                    field + " must be between " + trim(lo) + " and " + trim(hi) + "."));
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString().trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static Double dbl(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s && !s.isBlank()) try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException ignore) {
            return Double.NaN;   // geçersiz metin → aralık dışı sayılır
        }
        return null;
    }

    private static Integer intg(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s && !s.isBlank()) try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException ignore) {
            return Integer.MIN_VALUE;   // geçersiz → aralık dışı
        }
        return null;
    }

    /** İzlemenin örnek sayısı (tanılama). */
    public int sampleCount(String type, long monitorId) {
        return store.count(type, monitorId);
    }

    /** Kalem hatalarını {field,message} listesine çevirir (denetleyici VALIDATION_FAILED için). */
    public static List<Map<String, String>> fieldErrors(Map<String, String> errors) {
        List<Map<String, String>> out = new ArrayList<>();
        errors.forEach((f, m) -> out.add(Map.of("field", f, "message", m)));
        return out;
    }
}
