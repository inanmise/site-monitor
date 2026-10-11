package com.sitemonitor.service.watchdog;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.util.Msg;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kendi kendini izleme ayarları (2026-10-11) — anahtarlar, varsayılanlar, aralıklar TEK yerde. Ayarlar → Genel →
 * "Kendi kendini izleme" grubu; alıcı / push / webhook anahtarları GLOBAL_ONLY. Kayıt doğrulaması
 * {@code AppSettingsService.validate} buradan çağırır (hangi uçtan yazılırsa yazılsın aynı kural); okurken de sınırlanır.
 */
public final class WatchdogSettings {

    private WatchdogSettings() {}

    public static final String PREFIX = "site.monitor.watchdog.";
    public static final String KEY_ENABLED = PREFIX + "enabled";
    public static final String KEY_RECIPIENTS = PREFIX + "recipients";
    public static final String KEY_PUSH_USERS = PREFIX + "push-users";
    public static final String KEY_WEBHOOK_URL = PREFIX + "webhook-url";
    public static final String KEY_WEBHOOK_TYPE = PREFIX + "webhook-type";
    public static final String KEY_REMINDER_HOURS = PREFIX + "reminder-hours";
    public static final String KEY_CONFIRM = PREFIX + "confirm-checks";
    public static final String KEY_STALE_RATIO = PREFIX + "stale-ratio-percent";
    public static final String KEY_CERT_STALE_HOURS = PREFIX + "cert-stale-hours";

    static final int MAX_RECIPIENTS = 50;
    private static final Pattern EMAIL = Pattern.compile("[^@\\s,;]{1,64}@[^@\\s,;]{1,255}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public static boolean isKey(String key) {
        return key != null && key.startsWith(PREFIX);
    }

    /** Aralık / biçim denetimi; geçersizse {@link IllegalArgumentException} (Türkçe / İngilizce). Boş = varsayılana dön. */
    public static void validate(String key, String val) {
        if (val == null || val.isBlank()) return;
        // Uzunluk ÖNCE (desenler doğrusal ama girdi yine de sınırlı olsun — "sınırsız veri üzerinde regex yok" kuralı)
        if (val.length() > 20_000) throw new IllegalArgumentException(Msg.t(
                "Kendi kendini izleme: değer çok uzun (en çok 20.000 karakter).",
                "Self-monitoring: the value is too long (20,000 characters at most)."));
        switch (key) {
            case KEY_REMINDER_HOURS -> range(key, val, 0, 168);
            case KEY_CONFIRM -> range(key, val, 1, 10);
            case KEY_STALE_RATIO -> range(key, val, 10, 100);
            case KEY_CERT_STALE_HOURS -> range(key, val, 1, 72);
            case KEY_RECIPIENTS -> {
                List<String> list = split(val);
                if (list.size() > MAX_RECIPIENTS) throw new IllegalArgumentException(Msg.t(
                        "Kendi kendini izleme: en fazla " + MAX_RECIPIENTS + " e-posta adresi girilebilir.",
                        "Self-monitoring: at most " + MAX_RECIPIENTS + " e-mail addresses are allowed."));
                for (String a : list) if (!EMAIL.matcher(a).matches()) throw new IllegalArgumentException(Msg.t(
                        "Kendi kendini izleme: geçersiz e-posta adresi: " + a + " — adresleri virgülle ayırın.",
                        "Self-monitoring: invalid e-mail address: " + a + " — separate addresses with commas."));
            }
            case KEY_PUSH_USERS -> {
                List<String> list = split(val);
                if (list.size() > MAX_RECIPIENTS) throw new IllegalArgumentException(Msg.t(
                        "Kendi kendini izleme: en fazla " + MAX_RECIPIENTS + " kullanıcı adı girilebilir.",
                        "Self-monitoring: at most " + MAX_RECIPIENTS + " user names are allowed."));
                for (String u : list) if (!USERNAME.matcher(u).matches()) throw new IllegalArgumentException(Msg.t(
                        "Kendi kendini izleme: geçersiz kullanıcı adı: " + u + " — sicil numaralarını virgülle ayırın.",
                        "Self-monitoring: invalid user name: " + u + " — separate user ids with commas."));
            }
            case KEY_WEBHOOK_URL -> {
                String v = val.trim().toLowerCase(Locale.ROOT);
                if (!v.startsWith("https://") && !v.startsWith("http://")) throw new IllegalArgumentException(Msg.t(
                        "Kendi kendini izleme: webhook adresi http:// ya da https:// ile başlamalı.",
                        "Self-monitoring: the webhook URL must start with http:// or https://."));
            }
            default -> { /* BOOL / ENUM katalog türüyle denetlenir */ }
        }
    }

    private static void range(String key, String val, int min, int max) {
        int n;
        try {
            n = Integer.parseInt(val.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(key + Msg.t(": tam sayı olmalı", ": must be a whole number"));
        }
        if (n < min || n > max) throw new IllegalArgumentException(Msg.t(
                key + ": " + min + " ile " + max + " arasında olmalı.", key + ": must be between " + min + " and " + max + "."));
    }

    /** Virgül / noktalı virgül / boşlukla ayrılmış liste — tekrarsız, sıralı, en çok {@link #MAX_RECIPIENTS}. */
    public static List<String> split(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) for (String p : raw.split("[,;\\s]+")) {
            String v = p.trim();
            if (!v.isEmpty()) out.add(v);
            if (out.size() > MAX_RECIPIENTS) break;
        }
        return new ArrayList<>(out);
    }

    // ── okuma (sınırlı) ──────────────────────────────────────────────────────────────────────────

    public static boolean enabled(AppSettingsService s) { return s == null || s.getBoolean(KEY_ENABLED, true); }

    public static int reminderHours(AppSettingsService s) { return clamp(s == null ? 6 : s.getInt(KEY_REMINDER_HOURS, 6), 0, 168); }

    public static int confirm(AppSettingsService s) { return clamp(s == null ? 2 : s.getInt(KEY_CONFIRM, 2), 1, 10); }

    public static int staleRatio(AppSettingsService s) { return clamp(s == null ? 50 : s.getInt(KEY_STALE_RATIO, 50), 10, 100); }

    public static int certStaleHours(AppSettingsService s) { return clamp(s == null ? 4 : s.getInt(KEY_CERT_STALE_HOURS, 4), 1, 72); }

    /** E-posta alıcıları: ayar boşsa sistem yöneticisi adresi(leri). */
    public static List<String> recipients(AppSettingsService s, String systemAdminFallback) {
        List<String> own = s == null ? List.of() : split(s.getString(KEY_RECIPIENTS, ""));
        if (!own.isEmpty()) return own;
        String admin = s == null ? systemAdminFallback : s.getString("site.monitor.system-admin.email", systemAdminFallback);
        return split(admin);
    }

    public static List<String> pushUsers(AppSettingsService s) { return s == null ? List.of() : split(s.getString(KEY_PUSH_USERS, "")); }

    public static String webhookUrl(AppSettingsService s) {
        String v = s == null ? "" : s.getString(KEY_WEBHOOK_URL, "");
        return v == null ? "" : v.trim();
    }

    public static String webhookType(AppSettingsService s) {
        String v = s == null ? "TEAMS" : s.getString(KEY_WEBHOOK_TYPE, "TEAMS");
        return "SLACK".equalsIgnoreCase(v == null ? "" : v.trim()) ? "SLACK" : "TEAMS";
    }

    static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
