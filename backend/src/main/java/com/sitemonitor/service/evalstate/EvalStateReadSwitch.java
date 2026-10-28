package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.AppSettingsService;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Devirde kalıcı değerlendirme durumunu OKUMA anahtarı (2026-10-28, çok pod Sürüm 3-2 P0; kullanıcı kararı D-1) — ayar
 * {@value #KEY} ({@code AppSettingsCatalog}, BOOL, grup {@code monitoring}, GLOBAL_ONLY, 10 sn ayar yenilemesiyle CANLI;
 * {@code application.properties} {@code ${EVAL_STATE_READ_ENABLED:false}}). VARSAYILAN KAPALI.
 *
 * <p><b>Kapalı ≡ v20.168.1 (J-1):</b> hiçbir arma, okuma, işaretli kalp atışı, devir boşaltması ya da takipçi listesi
 * okuması yapılmaz. Okuyanlar (paket A kaydedici, B MOS, C takipçi teyit listesi) değeri HER kararda bu sınıftan alır — iç
 * bellek okuması ({@code AppSettingsService.getBoolean}), veritabanı yok, günlük yok.
 *
 * <p><b>Okuma hatası = KAPALI</b> (güvenli taraf): servis yok, istisna, tanınmayan değer → false. "Açık" yalnız
 * {@code Boolean.parseBoolean} doğru dediğinde.
 */
public final class EvalStateReadSwitch {

    /** Ayar anahtarı — kalıcı. */
    public static final String KEY = "site.monitor.eval-state.read-enabled";

    /** Ayar yoksa / okunamazsa: kapalı. */
    public static final boolean DEFAULT = false;

    private EvalStateReadSwitch() {
    }

    /** Ayar şu an açık mı — servis null ya da okuma istisnası → false (asla fırlatmaz). */
    public static boolean enabled(AppSettingsService settings) {
        if (settings == null) return DEFAULT;
        try {
            return settings.getBoolean(KEY, DEFAULT);
        } catch (RuntimeException e) {
            return DEFAULT;
        }
    }

    /**
     * Tembel tedarikçi — Spring bağımlılık döngüsü kurmadan (kaydedicinin {@code ObjectProvider} kalıbı): servis ilk
     * çağrıda bir kez çözülür; çözülemezse (yok / {@code BeansException} / null sağlayıcı) o çağrı false döner ve sonraki
     * çağrı yeniden dener. Her çağrı {@link #enabled(AppSettingsService)} kuralıyla okur.
     */
    public static BooleanSupplier supplier(ObjectProvider<AppSettingsService> provider) {
        AtomicReference<AppSettingsService> ref = new AtomicReference<>();
        return () -> {
            AppSettingsService s = ref.get();
            if (s == null) {
                if (provider == null) return DEFAULT;
                try {
                    s = provider.getIfAvailable();
                } catch (RuntimeException e) {
                    return DEFAULT;
                }
                if (s == null) return DEFAULT;
                ref.set(s);
            }
            return enabled(s);
        };
    }
}
