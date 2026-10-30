package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.util.Msg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * k6 motoru ayarları (2026-10-29, k6 0.49 → 2.3 geçişi, Yayın A) — Ayarlar → Platform → k6 motoru.
 *
 * <p>Hepsi GLOBAL_ONLY ({@link AppSettingsCatalog#GLOBAL_ONLY}): bütün senaryo izlemelerinin hangi motorla, hangi TLS
 * kaçışlarıyla ve hangi User-Agent ile koşacağını belirler. Doğrulama {@link AppSettingsService#save} içinde (hangi uçtan
 * yazılırsa yazılsın aynı kural); çalışma anı okuması GEVŞEKTİR — bozuk bir parça atlanır, koşum durmaz.
 */
public final class K6EngineSettings {

    private K6EngineSettings() {}

    public static final String GROUP = "k6-engine";

    /** Go TLS / x509 kaçışları (GODEBUG), virgülle {@code anahtar=0|1}. Boş = yok. */
    public static final String KEY_GODEBUG = "site.monitor.scripted.godebug";
    /** k6 koşumlarının varsayılan User-Agent'ı. Boş = k6'nın kendi varsayılanı. */
    public static final String KEY_USER_AGENT = "site.monitor.scripted.user-agent";
    /** Uyumluluk taramasının DIŞINDA bırakılan izleme kimlikleri (virgülle). Yan etkili betikler için. */
    public static final String KEY_SCAN_EXCLUDED = "site.monitor.scripted.compat-scan.excluded-ids";
    /** Uyumluluk taraması geçmişinin saklama süresi (gün) — RetentionCatalog {@code k6-compat-scans}. */
    public static final String KEY_SCAN_RETENTION = "site.monitor.scripted.compat-scan.retention-days";

    /** Sayfanın yazdığı anahtarlar (yedek / aday ikili yolları dahil) — denetleyici yalnız bunları kabul eder. */
    public static final List<String> KEYS = List.of(
            ScriptedCheckerService.K6_BIN_ROLLBACK_KEY,
            ScriptedCheckerService.K6_BIN_CANDIDATE_KEY,
            KEY_GODEBUG, KEY_USER_AGENT, KEY_SCAN_EXCLUDED);

    /**
     * İzinli GODEBUG anahtarları — yalnız İSTEMCİ tarafında eski TLS / sertifika davranışını geri getirenler. Değer 0 ya da
     * 1. Kaynak: go.dev/doc/godebug (Go 1.26 — k6 v2.3.0 sürüm tarball'ı go1.26.8 ile derlenmiş, 2026-10-29 ölçüldü).
     * {@code tlsrsakex} ve {@code tls3des} Go 1.27'de KALDIRILDI: Go 1.27 ile derlenmiş bir k6'da etkisizdir.
     */
    static final Map<String, String> GODEBUG_KEYS = orderedMap(
            "tlsrsakex", "RSA anahtar değişimli şifre takımları (1 = aç)",
            "tls3des", "3DES şifre takımları (1 = aç)",
            "tlssha1", "TLS 1.2'de SHA-1 imzalar (1 = aç)",
            "x509negativeserial", "negatif seri numaralı sertifikalar (1 = kabul et)",
            "rsa1024min", "1024 bitten kısa RSA anahtarları (0 = kabul et)",
            "tlsmlkem", "ML-KEM anahtar paylaşımı (0 = kapat — büyük ClientHello'yu bozan cihazlar için)",
            "tlssecpmlkem", "SecP256r1/SecP384r1 ML-KEM anahtar paylaşımı (0 = kapat)");

    /**
     * "Kapalı" işareti (2026-10-30, inceleme L1): ayar ekranında boşaltılan yedek / aday yolu, GODEBUG ve User-Agent bu
     * değerle YAZILIR. Boş değer kaydedilince geçersiz kılma silinir ve {@code application.properties} varsayılanı (aday
     * ya da ortam değişkeni) geri gelirdi — "boşalttım" diyen yönetici değeri geri
     * görürdü. Okuyan her yer {@link #isOff} ile boş sayar; ekrana boş gider ({@link #shown}).
     */
    public static final String OFF = "none";

    /** Boş ya da {@link #OFF} → "tanımlı değil". */
    public static boolean isOff(String raw) {
        return raw == null || raw.isBlank() || OFF.equalsIgnoreCase(raw.trim());
    }

    /** Ekrana / API'ye giden değer: {@link #OFF} boş görünür. */
    public static String shown(String raw) {
        return isOff(raw) ? "" : raw.trim();
    }

    static final int MAX_GODEBUG_CHARS = 300;
    static final int MAX_USER_AGENT_CHARS = 200;
    static final int MAX_EXCLUDED_IDS = 500;

    public static boolean isKey(String key) {
        return KEY_GODEBUG.equals(key) || KEY_USER_AGENT.equals(key) || KEY_SCAN_EXCLUDED.equals(key)
                || ScriptedCheckerService.K6_BIN_ROLLBACK_KEY.equals(key)
                || ScriptedCheckerService.K6_BIN_CANDIDATE_KEY.equals(key);
    }

    /** Kayıt doğrulaması — kural dışı değer {@link FieldValidationException} (400 VALIDATION_FAILED + alan). */
    public static void validate(String key, String raw) {
        if (raw == null || raw.isEmpty()) return;
        if (!KEY_SCAN_EXCLUDED.equals(key) && isOff(raw)) return;
        switch (key) {
            case KEY_GODEBUG -> parseGodebugStrict(raw);
            case KEY_USER_AGENT -> validateUserAgent(raw);
            case KEY_SCAN_EXCLUDED -> parseIdsStrict(raw);
            default -> validateBinPath(key, raw);
        }
    }

    // ── GODEBUG ──────────────────────────────────────────────────────────────────────────────────────

    /** İzinli GODEBUG anahtarları + açıklamaları (sıralı; yönetici ekranı). */
    public static Map<String, String> godebugKeys() {
        return GODEBUG_KEYS;
    }

    /** Kayıttan önce normalize: sıkı kurallar (hatada {@link FieldValidationException}), anahtar küçük harf, boşluksuz. */
    public static String normalizeGodebug(String raw) {
        return parseGodebugStrict(raw);
    }

    /** Sıkı ayrıştırma: her parça izinli {@code anahtar=0|1}; tekrar eden anahtar hatadır. Döner: normalize metin. */
    static String parseGodebugStrict(String raw) {
        if (raw.length() > MAX_GODEBUG_CHARS) {
            throw new FieldValidationException("godebug", Msg.t(
                    "GODEBUG çok uzun (en çok " + MAX_GODEBUG_CHARS + " karakter).",
                    "GODEBUG is too long (" + MAX_GODEBUG_CHARS + " characters at most)."));
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            String k = eq < 0 ? p : p.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String v = eq < 0 ? "" : p.substring(eq + 1).trim();
            if (!GODEBUG_KEYS.containsKey(k)) {
                throw new FieldValidationException("godebug", Msg.t(
                        "İzin verilmeyen GODEBUG anahtarı: " + clip(k) + ". İzinliler: " + String.join(", ", GODEBUG_KEYS.keySet()) + ".",
                        "GODEBUG key not allowed: " + clip(k) + ". Allowed: " + String.join(", ", GODEBUG_KEYS.keySet()) + "."));
            }
            if (!"0".equals(v) && !"1".equals(v)) {
                throw new FieldValidationException("godebug", Msg.t(
                        "GODEBUG değeri 0 ya da 1 olmalı: " + k + "=" + clip(v),
                        "GODEBUG value must be 0 or 1: " + k + "=" + clip(v)));
            }
            if (out.put(k, v) != null) {
                throw new FieldValidationException("godebug", Msg.t(
                        "GODEBUG anahtarı iki kez yazılmış: " + k, "GODEBUG key given twice: " + k));
            }
        }
        return join(out);
    }

    /** Çalışma anı: yalnız izinli ve geçerli parçalar (ilk değer kazanır); hiçbir koşulda fırlatmaz. */
    public static String effectiveGodebug(String raw) {
        if (isOff(raw) || raw.length() > MAX_GODEBUG_CHARS) return "";
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : raw.split(",")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String k = p.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String v = p.substring(eq + 1).trim();
            if (GODEBUG_KEYS.containsKey(k) && ("0".equals(v) || "1".equals(v))) out.putIfAbsent(k, v);
        }
        return join(out);
    }

    // ── User-Agent ───────────────────────────────────────────────────────────────────────────────────

    static void validateUserAgent(String raw) {
        String v = raw.trim();
        if (v.length() > MAX_USER_AGENT_CHARS) {
            throw new FieldValidationException("user_agent", Msg.t(
                    "User-Agent çok uzun (en çok " + MAX_USER_AGENT_CHARS + " karakter).",
                    "User-Agent is too long (" + MAX_USER_AGENT_CHARS + " characters at most)."));
        }
        if (!printableAscii(v)) {
            throw new FieldValidationException("user_agent", Msg.t(
                    "User-Agent yalnız yazdırılabilir ASCII karakter içerebilir (satır sonu, sekme, Türkçe harf olmaz).",
                    "User-Agent may contain printable ASCII characters only (no line breaks, tabs or non-ASCII letters)."));
        }
    }

    /** Çalışma anı: geçerliyse kırpılmış değer, değilse boş (k6 varsayılanı). */
    public static String effectiveUserAgent(String raw) {
        if (isOff(raw)) return "";
        String v = raw.trim();
        if (v.isEmpty() || v.length() > MAX_USER_AGENT_CHARS || !printableAscii(v)) return "";
        return v;
    }

    private static boolean printableAscii(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    // ── Tarama dışı izlemeler ─────────────────────────────────────────────────────────────────────────

    static Set<Long> parseIdsStrict(String raw) {
        Set<Long> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            long id;
            try {
                id = Long.parseLong(p);
            } catch (NumberFormatException e) {
                throw new FieldValidationException("excluded_ids", Msg.t(
                        "Tarama dışı izleme listesi yalnız izleme kimlikleri içerebilir: " + clip(p),
                        "The excluded monitor list may contain monitor ids only: " + clip(p)));
            }
            if (id <= 0) {
                throw new FieldValidationException("excluded_ids", Msg.t(
                        "Geçersiz izleme kimliği: " + p, "Invalid monitor id: " + p));
            }
            out.add(id);
            if (out.size() > MAX_EXCLUDED_IDS) {
                throw new FieldValidationException("excluded_ids", Msg.t(
                        "Tarama dışı en çok " + MAX_EXCLUDED_IDS + " izleme olabilir.",
                        "At most " + MAX_EXCLUDED_IDS + " monitors can be excluded."));
            }
        }
        return out;
    }

    /** Çalışma anı: geçerli kimlikler (bozuk parçalar atlanır). */
    public static Set<Long> effectiveExcludedIds(String raw) {
        Set<Long> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0 && out.size() < MAX_EXCLUDED_IDS) out.add(id);
            } catch (NumberFormatException ignored) {
                // gevşek okuma
            }
        }
        return out;
    }

    public static String joinIds(java.util.Collection<Long> ids) {
        List<String> out = new ArrayList<>();
        for (Long id : ids) if (id != null && id > 0) out.add(String.valueOf(id));
        return String.join(",", out);
    }

    // ── İkili yolları ───────────────────────────────────────────────────────────────────────────────

    static final int MAX_BIN_CHARS = 300;

    /** Yol: tek satır, boşluk / tırnak / kabuk karakteri yok (komut kabuksuz çalışır, ama değer günlüğe ve ekrana gider). */
    static void validateBinPath(String key, String raw) {
        String v = raw.trim();
        String field = ScriptedCheckerService.K6_BIN_ROLLBACK_KEY.equals(key) ? "rollback_bin" : "candidate_bin";
        boolean ok = !v.isEmpty() && v.length() <= MAX_BIN_CHARS;
        for (int i = 0; ok && i < v.length(); i++) {
            char c = v.charAt(i);
            ok = c > 0x20 && c < 0x7F && "\"'`$;|&<>".indexOf(c) < 0;
        }
        if (!ok) {
            throw new FieldValidationException(field, Msg.t(
                    "İkili yolu geçersiz: boşluk, tırnak ya da kabuk karakteri içermeyen tek bir yol yazın (ör. /opt/k6-candidate/k6).",
                    "Invalid binary path: enter a single path without spaces, quotes or shell characters (e.g. /opt/k6-candidate/k6)."));
        }
    }

    private static String clip(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static String join(Map<String, String> m) {
        List<String> parts = new ArrayList<>();
        m.forEach((k, v) -> parts.add(k + "=" + v));
        return String.join(",", parts);
    }

    private static Map<String, String> orderedMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }
}
