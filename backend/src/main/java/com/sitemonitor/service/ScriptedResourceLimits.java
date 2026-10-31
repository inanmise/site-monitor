package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * k6 alt sürecinin Go çalışma zamanı tavanları — {@code site.monitor.scripted.mem-limit} ({@code GOMEMLIMIT}) ve
 * {@code site.monitor.scripted.max-procs} ({@code GOMAXPROCS}) doğrulaması (2026-10-31, k6 inceleme A5 / G4).
 *
 * <p>Neden: değer eskiden doğrulanmadan olduğu gibi ortama konuyordu. Go {@code GOMEMLIMIT}'i yalnız
 * {@code B|KiB|MiB|GiB|TiB} birimleriyle (ya da {@code off}) kabul eder; {@code 512MB} gibi bir değer k6'yı AÇILIŞTA
 * düşürür → her koşum ERROR → bütün sentetik izlemeler teyit zincirine girer → kurum genelinde sahte alarm. Sürüm yoklaması
 * bu değişkeni almadığı için "k6 hazır" görünmeye devam ederdi.
 *
 * <p>Kayıt ({@link AppSettingsService#save}) KATI: bozuk değer 400 + alan hatası. Çalışma anı GEVŞEK: kayıtta kalmış bozuk
 * bir değer (bu sürümden önce yazılmış) ortama KONMAZ ve pod başına bir kez WARN — koşum tavansız sürer.
 */
@Slf4j
public final class ScriptedResourceLimits {

    private ScriptedResourceLimits() {}

    public static final String KEY_MEM_LIMIT = "site.monitor.scripted.mem-limit";
    public static final String KEY_MAX_PROCS = "site.monitor.scripted.max-procs";

    /** Alan hatası adları (arayüz alanın altında gösterir). */
    public static final String FIELD_MEM_LIMIT = "scripted_mem_limit";
    public static final String FIELD_MAX_PROCS = "scripted_max_procs";

    /** Go {@code GOMEMLIMIT} biçimi: tam sayı + ikili birim. */
    private static final Pattern MEM = Pattern.compile("^\\d{1,15}(?:B|KiB|MiB|GiB|TiB)$");
    private static final Pattern INT = Pattern.compile("^\\d{1,6}$");

    public static boolean isKey(String key) {
        return KEY_MEM_LIMIT.equals(key) || KEY_MAX_PROCS.equals(key);
    }

    /** Tavan YOK anlamına gelen değer mi (boş / "0" / "off")? */
    static boolean unlimited(String raw) {
        if (raw == null) return true;
        String v = raw.trim();
        return v.isEmpty() || "0".equals(v) || "off".equalsIgnoreCase(v);
    }

    /** Biçim geçerli mi (boş / 0 / off dahil)? */
    public static boolean validMemLimit(String raw) {
        if (unlimited(raw)) return true;
        return raw.trim().length() <= 20 && MEM.matcher(raw.trim()).matches();
    }

    public static boolean validMaxProcs(String raw) {
        if (raw == null || raw.isBlank()) return true;
        String v = raw.trim();
        return v.length() <= 6 && INT.matcher(v).matches();
    }

    /** Kayıt doğrulaması — bozuk değerde 400 alan hatası. */
    public static void validate(String key, String val) {
        if (KEY_MEM_LIMIT.equals(key) && !validMemLimit(val)) {
            throw new FieldValidationException(FIELD_MEM_LIMIT, Msg.t(
                    "Bellek sınırı tanınmadı — Go biçiminde yazın: sayı + birim (B, KiB, MiB, GiB, TiB; ör. 256MiB), "
                            + "ya da sınır istemiyorsanız 0 / off. \"512MB\" gibi bir değer k6'yı açılışta düşürür.",
                    "Memory limit not recognised — use the Go format: a number plus a unit (B, KiB, MiB, GiB, TiB; e.g. "
                            + "256MiB), or 0 / off for no limit. A value such as \"512MB\" makes k6 fail at start."));
        }
        if (KEY_MAX_PROCS.equals(key) && !validMaxProcs(val)) {
            throw new FieldValidationException(FIELD_MAX_PROCS, Msg.t(
                    "Azami süreç tam sayı olmalı (ör. 1); 0 sınırı kaldırır.",
                    "Maximum processes must be a whole number (e.g. 1); 0 removes the limit."));
        }
    }

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final int MAX_WARNED = 100;

    /** Ortama konacak {@code GOMEMLIMIT} değeri; tavansız ya da bozuksa null (bozuksa pod başına bir kez WARN). */
    public static String effectiveMemLimit(String raw) {
        if (unlimited(raw)) return null;
        String v = raw.trim();
        if (validMemLimit(v)) return v;
        warnOnce(KEY_MEM_LIMIT, v);
        return null;
    }

    /** Ortama konacak {@code GOMAXPROCS} değeri; tavansız ya da bozuksa null. */
    public static String effectiveMaxProcs(String raw) {
        if (raw == null || raw.isBlank() || "0".equals(raw.trim())) return null;
        String v = raw.trim();
        if (validMaxProcs(v)) return v;
        warnOnce(KEY_MAX_PROCS, v);
        return null;
    }

    private static void warnOnce(String key, String value) {
        String shown = value.length() > 40 ? value.substring(0, 40) + "…" : value;
        if (WARNED.size() < MAX_WARNED && WARNED.add(key + "=" + shown)) {
            log.warn("[K6] Kayıtlı '{}' değeri geçersiz ('{}') — k6 ortamına konmadı, koşum bu tavan olmadan sürer. "
                    + "Ayarlar → Genel → Senaryo izleme'den düzeltin.", key, shown);
        }
    }
}
