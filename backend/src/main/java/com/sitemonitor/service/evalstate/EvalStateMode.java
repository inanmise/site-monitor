package com.sitemonitor.service.evalstate;

import java.util.Locale;

/**
 * Kalıcı değerlendirme durumunun çalışma kipi (2026-10-23, çok pod Sürüm 3-1, plan 3a) — ayar
 * {@value #SETTING_KEY} ({@code AppSettingsCatalog}, ENUM, grup {@code monitoring}, GLOBAL_ONLY, 10 sn ayar yenilemesiyle
 * CANLI). Hiçbir kip alarm / kurtarma / bastırma / teyit kararını değiştirmez (değişmez I-1): kararlar 3-1'de her zaman
 * bellekten verilir; tablolar yalnız aynası.
 *
 * <ul>
 *   <li>{@link #OFF} — kayıt yok, yazıcı iş parçacığı iş yapmaz (DB yükü için acil kapatma).</li>
 *   <li>{@link #WRITE} — tarama lideri bellekteki değerleri tablolara yazar (ikili yazım), doğrulama yok.</li>
 *   <li>{@link #VERIFY} — {@link #WRITE} + 5 dakikada bir bellek ↔ tablo karşılaştırması (3-2 için kanıt).
 *       VARSAYILAN.</li>
 * </ul>
 * Ayar adı ve değerleri KALICIDIR (veritabanında saklanır) — yeniden adlandırılmaz.
 */
public enum EvalStateMode {
    OFF("off"),
    WRITE("write"),
    VERIFY("verify");

    /** Ayar anahtarı — kalıcı. */
    public static final String SETTING_KEY = "site.monitor.eval-state.mode";

    /** Ayar yoksa / okunamazsa / tanınmazsa geçerli kip (tasarım R3-1 §2.4). */
    public static final EvalStateMode DEFAULT = VERIFY;

    private final String key;

    EvalStateMode(String key) {
        this.key = key;
    }

    /** Ayarda saklanan değer ({@code off | write | verify}). */
    public String key() {
        return key;
    }

    /** Kayıt yapılır mı ({@link #WRITE} ya da {@link #VERIFY}). */
    public boolean writes() {
        return this != OFF;
    }

    /** Doğrulama turu koşar mı. */
    public boolean verifies() {
        return this == VERIFY;
    }

    /**
     * Ayar değerini çözer: boşluk ve büyük/küçük harf yok sayılır; null / boş / tanınmayan değer → {@link #DEFAULT}.
     * Asla fırlatmaz.
     */
    public static EvalStateMode parse(String raw) {
        EvalStateMode m = parseOrNull(raw);
        return m == null ? DEFAULT : m;
    }

    /**
     * Değer boş değil ama tanınmıyor mu (yazım hatası, ör. {@code "kapali"}) — kaydedici bir kez WARN yazar (R3-1 FX-STATE);
     * null / boş değer "ayarlanmamış" sayılır (uyarı yok).
     */
    public static boolean unrecognized(String raw) {
        return raw != null && !raw.isBlank() && parseOrNull(raw) == null;
    }

    private static EvalStateMode parseOrNull(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        for (EvalStateMode m : values()) {
            if (m.key.equals(v)) return m;
        }
        return null;
    }
}
