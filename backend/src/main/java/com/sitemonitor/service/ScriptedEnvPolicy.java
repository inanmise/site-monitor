package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Sentetik izleme (k6) ortam değişkeni politikası (2026-10-08, güvenlik denetimi: "doğrulanmadan alınan veri var mı?").
 *
 * <p><b>Sorun:</b> monitör formundaki env adları hiç denetlenmiyordu ve k6 alt sürecine OLDUĞU GİBİ geçiyordu. Bir
 * kullanıcı {@code K6_OUT} (çıktıyı dosyaya/uzak uca yazdırma), {@code K6_*} seçenekleri, {@code HTTPS_PROXY} (kendi
 * vekiliyle {@code --blacklist-ip} SSRF korumasını atlatma: k6 hedefe değil vekile bağlanır), {@code SSL_CERT_FILE},
 * {@code PATH}, {@code LD_*} ya da {@code GOMAXPROCS}/{@code GOMEMLIMIT} (sert CPU/bellek tavanını ezme) verebiliyordu.
 *
 * <p><b>İki katman, "mevcut bozulmasın" kuralıyla:</b>
 * <ul>
 *   <li><b>Kayıt (yeni girdi):</b> ad {@code ^[A-Za-z_][A-Za-z0-9_]{0,63}$} olmalı ve sistem adlarından biri olmamalı
 *       ({@link #reservedName}); en çok {@link #MAX_VARS} değişken, değer başına {@link #MAX_VALUE_CHARS} karakter →
 *       400 {@code VALIDATION_FAILED} + {@code fields.env}. Güncellemede monitörde ZATEN kayıtlı adlar (ve değişmemiş
 *       değerleri) bu biçim kurallarından muaftır — eski bir kayıt yalnız başka bir alanı düzenlendi diye
 *       kilitlenmez. Koşumu gerçekten etkileyen adlar ({@link #droppedAtRuntime}) ise her zaman reddedilir.</li>
 *   <li><b>Koşum (saklı eski kayıtlar):</b> {@link #droppedAtRuntime} adları sessizce DÜŞÜRÜLÜR ve bir kez WARN
 *       loglanır; koşum düşmez. Yalnız k6/Go/yükleyici/vekil/CA davranışını değiştiren adlar düşer — {@code NODE_ENV},
 *       {@code MY_PROXY_URL} gibi script'in yalnız {@code __ENV} ile okuduğu eski adlar çalışmaya devam eder.</li>
 * </ul>
 * Test ("Test Çalıştır") koşumu kayıt yazmaz: yalnız koşumun yok sayacağı adları reddeder ki test sonucu kaydedilmiş
 * monitörün koşumuyla aynı olsun.
 */
@Slf4j
public final class ScriptedEnvPolicy {

    private ScriptedEnvPolicy() {}

    /** {@code fields} anahtarı — monitör formundaki env tablosu. */
    public static final String FIELD = "env";
    /** Monitör başına en çok env değişkeni (yeni kayıtta). */
    public static final int MAX_VARS = 50;
    /**
     * Değer başına en çok karakter. 4096 yerine 8192: istemci sertifikası + anahtar PEM'i (mTLS testleri) tek değerde
     * 3–6 KB tutuyor. 50 × 8192 karakter Linux'un süreç argüman+ortam sınırının (ARG_MAX ≈ 2 MB) altında kalır.
     */
    public static final int MAX_VALUE_CHARS = 8192;

    private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
    /** Go çalışma zamanı/araç zinciri adları: GO + harf/rakam, alt çizgisiz (GOMAXPROCS, GOGC, GODEBUG …).
     *  {@code GOOGLE_API_KEY} gibi alt çizgili adlar serbest. */
    private static final Pattern GO_STYLE = Pattern.compile("^GO[A-Z0-9]*$");
    private static final List<String> RESERVED_PREFIXES = List.of("K6_", "SSL_", "LD_", "DYLD_", "JAVA_", "NODE_");
    private static final Set<String> RESERVED_EXACT = Set.of("PATH", "HOME", "TMPDIR");

    // Koşumda düşürülen adlar — süreç gerçekten OKUR (Linux'ta ad büyük/küçük harf duyarlıdır; vekil adlarını Go
    // küçük harfle de okur).
    private static final Set<String> GO_RUNTIME = Set.of(
            "GOMAXPROCS", "GOMEMLIMIT", "GOGC", "GODEBUG", "GOTRACEBACK", "GORACE", "GOROOT", "GOCOVERDIR", "GOFIPS140");
    private static final Set<String> PROXY_VARS = Set.of("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "ALL_PROXY", "FTP_PROXY");
    private static final Set<String> RUNTIME_EXACT = Set.of("PATH", "HOME", "TMPDIR", "SSL_CERT_FILE", "SSL_CERT_DIR");

    /** Kayıtta beklenen ad biçimi. */
    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Kayıtta reddedilen sistem adı mı (büyük/küçük harf duyarsız). */
    public static boolean reservedName(String name) {
        if (name == null) return false;
        String u = name.trim().toUpperCase(Locale.ROOT);
        if (u.isEmpty()) return false;
        if (RESERVED_EXACT.contains(u) || u.contains("PROXY") || GO_STYLE.matcher(u).matches()) return true;
        for (String p : RESERVED_PREFIXES) if (u.startsWith(p)) return true;
        return false;
    }

    /**
     * k6 alt sürecine GEÇİRİLMEYEN ad mı: k6 seçenekleri ({@code K6_*}), Go çalışma zamanı ayarları, dinamik yükleyici
     * ({@code LD_*}/{@code DYLD_*}), vekil ve CA değişkenleri, {@code PATH/HOME/TMPDIR} ve sürece hiç verilemeyen adlar
     * ('=' ya da NUL içeren — {@code ProcessBuilder} bunlarda istisna fırlatıp koşumu düşürürdü).
     */
    public static boolean droppedAtRuntime(String name) {
        if (name == null || name.isBlank()) return true;
        if (name.indexOf('=') >= 0 || name.indexOf('\0') >= 0) return true;
        String n = name.trim();
        if (n.startsWith("K6_") || n.startsWith("LD_") || n.startsWith("DYLD_")) return true;
        if (GO_RUNTIME.contains(n) || RUNTIME_EXACT.contains(n)) return true;
        return PROXY_VARS.contains(n.toUpperCase(Locale.ROOT));
    }

    /**
     * Kayıt doğrulaması (oluşturma / güncelleme).
     *
     * @param incoming istekteki {@code env} dizisi (ham gövde değeri; liste değilse dokunulmaz)
     * @param legacy   monitörde ZATEN kayıtlı ad → saklı değer (sırlarda şifreli metin); oluşturmada boş
     */
    public static void validateForSave(Object incoming, Map<String, String> legacy) {
        if (!(incoming instanceof List<?> list)) return;
        Map<String, String> old = legacy == null ? Map.of() : legacy;
        int count = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> e)) continue;
            Object nm = e.get("name");
            if (nm == null || nm.toString().isBlank()) continue;
            count++;
            String name = nm.toString().trim();
            boolean known = old.containsKey(name);
            rejectIfDropped(name);
            if (!known && !validName(name)) {
                throw fail(Msg.t(
                        "Ortam değişkeni adı geçersiz: '" + shown(name) + "'. Ad harf ya da alt çizgiyle başlamalı, yalnız İngilizce harf, rakam ve alt çizgi içermeli (en çok 64 karakter; ör. BASE_URL).",
                        "Invalid environment variable name: '" + shown(name) + "'. The name must start with a letter or underscore and contain only English letters, digits and underscores (at most 64 characters; e.g. BASE_URL)."));
            }
            if (!known && reservedName(name)) throw reserved(name);
            Object val = e.get("value");
            String value = val == null ? "" : val.toString();
            if (value.length() > MAX_VALUE_CHARS) {
                boolean secret = Boolean.TRUE.equals(e.get("secret")) || "true".equals(String.valueOf(e.get("secret")));
                boolean unchanged = known && !secret && value.equals(old.get(name));
                if (!unchanged) {
                    throw fail(Msg.t(
                            "'" + shown(name) + "' ortam değişkeninin değeri çok uzun (" + value.length() + " karakter; en çok " + MAX_VALUE_CHARS + "). Değeri kısaltın.",
                            "The value of the '" + shown(name) + "' environment variable is too long (" + value.length() + " characters; at most " + MAX_VALUE_CHARS + "). Shorten the value."));
                }
            }
        }
        int limit = Math.max(MAX_VARS, old.size());
        if (count > limit) {
            throw fail(Msg.t(
                    "Çok fazla ortam değişkeni: " + count + " (en çok " + limit + "). Kullanılmayan satırları kaldırıp tekrar deneyin.",
                    "Too many environment variables: " + count + " (at most " + limit + "). Remove unused rows and try again."));
        }
    }

    /** Test koşumu ("Test Çalıştır") doğrulaması: yalnız koşumun yok sayacağı adlar reddedilir (bkz. sınıf notu). */
    public static void validateForTest(Object incoming) {
        if (!(incoming instanceof List<?> list)) return;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> e)) continue;
            Object nm = e.get("name");
            if (nm == null || nm.toString().isBlank()) continue;
            rejectIfDropped(nm.toString().trim());
        }
    }

    private static void rejectIfDropped(String name) {
        if (droppedAtRuntime(name)) throw reserved(name);
    }

    private static FieldValidationException reserved(String name) {
        return fail(Msg.t(
                "'" + shown(name) + "' ortam değişkeni sistem tarafından ayrılmıştır (k6/Go çalışma ayarı, vekil, sertifika ya da süreç ortamı) ve kullanılamaz. Satırı kaldırın ya da script'te farklı bir ad kullanın; vekil için formdaki Vekil seçeneğini kullanın.",
                "The '" + shown(name) + "' environment variable is reserved by the system (k6/Go runtime setting, proxy, certificate or process environment) and can’t be used. Remove the row or use a different name in the script; for a proxy, use the Proxy option in the form."));
    }

    private static FieldValidationException fail(String message) {
        return new FieldValidationException(FIELD, message);
    }

    /** İletide gösterilecek ad: kontrol karakterleri temizlenir, 64 karakterde kesilir. */
    private static String shown(String name) {
        String s = name == null ? "" : name.replaceAll("\\p{Cntrl}", "?");
        return s.length() > 64 ? s.substring(0, 64) + "…" : s;
    }

    // ── Koşum süzgeci ────────────────────────────────────────────────────────────────────────────

    /** Aynı (bağlam, ad) için WARN bir kez; sınırlı küme (bellek koruması) — dolunca DEBUG'a düşer. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final int MAX_WARNED = 5_000;

    /**
     * Koşum öncesi süzgeç: {@link #droppedAtRuntime} adları listeden çıkarılır (koşum düşmez), her (bağlam, ad) için
     * bir kez WARN. Değer ASLA loglanmaz (sır olabilir).
     *
     * @param label log bağlamı (ör. "izleme #12 (Ödeme akışı)"); null → "k6"
     */
    public static List<ScriptedCheckerService.EnvVar> forRuntime(List<ScriptedCheckerService.EnvVar> env, String label) {
        if (env == null || env.isEmpty()) return env;
        List<ScriptedCheckerService.EnvVar> out = null;
        for (int i = 0; i < env.size(); i++) {
            ScriptedCheckerService.EnvVar v = env.get(i);
            if (v != null && !droppedAtRuntime(v.name())) {
                if (out != null) out.add(v);
                continue;
            }
            if (out == null) out = new ArrayList<>(env.subList(0, i));
            if (v != null && v.name() != null && !v.name().isBlank()) warnDropped(label, v.name());
        }
        return out == null ? env : out;
    }

    private static void warnDropped(String label, String name) {
        String ctx = label == null ? "k6" : label;
        String key = ctx + "|" + name;
        if (WARNED.size() < MAX_WARNED && WARNED.add(key)) {
            log.warn("[K6] Ayrılmış ortam değişkeni yok sayıldı ({}): '{}' — k6/Go/vekil/sertifika ya da süreç ortamı ayarı "
                    + "kullanıcı env'inden verilemez (2026-10-08); koşum bu değişken olmadan sürer. Monitör formundan kaldırın.",
                    ctx, shown(name));
        } else {
            log.debug("[K6] Ayrılmış ortam değişkeni yok sayıldı ({}): '{}'", ctx, shown(name));
        }
    }
}
