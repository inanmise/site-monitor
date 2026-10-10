package com.sitemonitor.service.report.executive;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.util.Msg;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Aylık yönetici özeti ayarları — HEPSİ {@code AppSettingsCatalog.GLOBAL_ONLY} (kurum geneli rapor: kime gideceği ve
 * hangi hedefle ölçüleceği global yönetici kararıdır). Grup {@code executive-summary}; Genel Ayarlar'da gösterilmez,
 * Raporlar → Yönetici Özeti sayfası yönetir.
 *
 * <ul>
 *   <li>{@code enabled} — zamanlanmış gönderim; VARSAYILAN KAPALI (opt-in).</li>
 *   <li>{@code cron} — Spring cron (Europe/Istanbul), varsayılan her ayın 1'i 09:00.</li>
 *   <li>{@code recipients} — virgülle ek adresler; {@code include-global-admins} — aktif global yöneticilerin adresleri.</li>
 *   <li>{@code availability-target} — erişilebilirlik hedefi (%, 90–100, varsayılan 99,9).</li>
 *   <li>{@code renewal-target-days} — yenileme hedef süresi (gün, 0–365; 0 = Vade Takvimi'nin tier süresi).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ExecutiveSummarySettings {

    public static final String PREFIX = "site.monitor.executive-summary.";
    public static final String ENABLED_KEY = PREFIX + "enabled";
    public static final String CRON_KEY = PREFIX + "cron";
    public static final String RECIPIENTS_KEY = PREFIX + "recipients";
    public static final String INCLUDE_ADMINS_KEY = PREFIX + "include-global-admins";
    public static final String TARGET_KEY = PREFIX + "availability-target";
    public static final String RENEWAL_TARGET_KEY = PREFIX + "renewal-target-days";

    /** Katalog + GLOBAL_ONLY kapısının denetlediği anahtar kümesi (sıra = ayar ekranı sırası). */
    public static final List<String> KEYS = List.of(ENABLED_KEY, CRON_KEY, RECIPIENTS_KEY, INCLUDE_ADMINS_KEY,
            TARGET_KEY, RENEWAL_TARGET_KEY);

    public static final boolean DEFAULT_ENABLED = false;
    public static final String DEFAULT_CRON = "0 0 9 1 * *";
    public static final boolean DEFAULT_INCLUDE_ADMINS = true;
    public static final double DEFAULT_TARGET = 99.9;
    public static final int DEFAULT_RENEWAL_DAYS = 30;
    /** Açık adres listesi tavanı (BCC dilimlenir ama liste sınırsız büyümesin). */
    public static final int MAX_RECIPIENTS = 500;

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+$");

    private final AppSettingsService appSettings;

    public boolean enabled() { return appSettings.getBoolean(ENABLED_KEY, DEFAULT_ENABLED); }

    public String cron() {
        String v = appSettings.getString(CRON_KEY, DEFAULT_CRON);
        return v == null || v.isBlank() ? DEFAULT_CRON : v.trim();
    }

    public boolean includeGlobalAdmins() { return appSettings.getBoolean(INCLUDE_ADMINS_KEY, DEFAULT_INCLUDE_ADMINS); }

    public double availabilityTarget() {
        double v = appSettings.getDouble(TARGET_KEY, DEFAULT_TARGET);
        return v < 90 || v > 100 || Double.isNaN(v) ? DEFAULT_TARGET : v;
    }

    public int renewalTargetDays() {
        int v = appSettings.getInt(RENEWAL_TARGET_KEY, DEFAULT_RENEWAL_DAYS);
        return v < 0 || v > 365 ? DEFAULT_RENEWAL_DAYS : v;
    }

    /** Açık alıcılar (geçersiz biçimdekiler atlanır; küçük harf tekil). */
    public List<String> explicitRecipients() {
        return parseEmails(appSettings.getString(RECIPIENTS_KEY, ""));
    }

    static List<String> parseEmails(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (String part : csv.split("[,;\\s]+")) {
            String e = part.trim();
            if (e.isEmpty() || e.length() > 254 || !EMAIL.matcher(e).matches()) continue;
            if (seen.add(e.toLowerCase(Locale.ROOT))) out.add(e);
        }
        return out;
    }

    /** Ekran için düz harita (kaydedilmiş değerler + varsayılanlar). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled());
        m.put("cron", cron());
        m.put("recipients", String.join(", ", explicitRecipients()));
        m.put("include_global_admins", includeGlobalAdmins());
        m.put("availability_target", availabilityTarget());
        m.put("renewal_target_days", renewalTargetDays());
        return m;
    }

    /**
     * Gövdeyi ({@code enabled, cron, recipients, include_global_admins, availability_target, renewal_target_days})
     * doğrular ve TEK kayıtta yazar. Hatalı alan 400 {@code VALIDATION_FAILED} + {@code field}. Yalnız gövdede olan
     * alanlar yazılır. Dönen: değişen anahtarlar.
     */
    public Set<String> save(Map<String, Object> body, String actor) {
        Map<String, Object> in = body == null ? Map.of() : body;
        Map<String, String> values = new LinkedHashMap<>();
        if (in.containsKey("enabled")) values.put(ENABLED_KEY, String.valueOf(bool(in.get("enabled"))));
        if (in.containsKey("include_global_admins")) {
            values.put(INCLUDE_ADMINS_KEY, String.valueOf(bool(in.get("include_global_admins"))));
        }
        if (in.containsKey("cron")) {
            String v = in.get("cron") == null ? "" : String.valueOf(in.get("cron")).trim();
            validate(CRON_KEY, v);
            values.put(CRON_KEY, v);
        }
        if (in.containsKey("recipients")) {
            String v = in.get("recipients") == null ? "" : String.valueOf(in.get("recipients")).trim();
            validate(RECIPIENTS_KEY, v);
            values.put(RECIPIENTS_KEY, String.join(", ", parseEmails(v)));
        }
        if (in.containsKey("availability_target")) {
            String v = in.get("availability_target") == null ? "" : String.valueOf(in.get("availability_target")).trim()
                    .replace(',', '.');
            validate(TARGET_KEY, v);
            values.put(TARGET_KEY, v);
        }
        if (in.containsKey("renewal_target_days")) {
            String v = in.get("renewal_target_days") == null ? "" : String.valueOf(in.get("renewal_target_days")).trim();
            validate(RENEWAL_TARGET_KEY, v);
            values.put(RENEWAL_TARGET_KEY, v);
        }
        if (!values.isEmpty()) {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("values", new LinkedHashMap<>(values));
            appSettings.save(wrapped, actor);
        }
        return values.keySet();
    }

    public static boolean isKey(String key) {
        return key != null && KEYS.contains(key);
    }

    /**
     * Anahtar başına kural — {@code AppSettingsService.validate} de çağırır (hangi uçtan yazılırsa yazılsın aynı kural).
     * Boş değer = varsayılana dön (geçerli).
     */
    public static void validate(String key, String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) return;
        switch (key) {
            case CRON_KEY -> {
                try {
                    CronExpression.parse(v);
                } catch (Exception e) {
                    throw new FieldValidationException("cron", Msg.t(
                            "Zamanlama ifadesi geçersiz. Altı alanlı bir Spring cron ifadesi girin (ör. her ayın 1'i 09:00 için \"0 0 9 1 * *\").",
                            "The schedule expression is invalid. Enter a six-field Spring cron expression (for example \"0 0 9 1 * *\" for 09:00 on the 1st of each month)."));
                }
            }
            case RECIPIENTS_KEY -> {
                List<String> bad = new ArrayList<>();
                int ok = 0;
                for (String part : v.split("[,;\\s]+")) {
                    String e = part.trim();
                    if (e.isEmpty()) continue;
                    if (e.length() > 254 || !EMAIL.matcher(e).matches()) bad.add(e.length() > 60 ? e.substring(0, 60) + "…" : e);
                    else ok++;
                }
                if (!bad.isEmpty()) {
                    throw new FieldValidationException("recipients", Msg.t(
                            "Geçersiz e-posta adresi: " + String.join(", ", bad) + ". Adresleri virgülle ayırarak tam biçimde yazın (ad@alan.com).",
                            "Invalid e-mail address: " + String.join(", ", bad) + ". Write full addresses separated by commas (name@domain.com)."));
                }
                if (ok > MAX_RECIPIENTS) {
                    throw new FieldValidationException("recipients", Msg.t(
                            "En fazla " + MAX_RECIPIENTS + " adres girilebilir. Uzun listeler için bir dağıtım listesi adresi kullanın.",
                            "You can enter at most " + MAX_RECIPIENTS + " addresses. Use a distribution list address for long lists."));
                }
            }
            case TARGET_KEY -> {
                double d;
                try {
                    d = Double.parseDouble(v.replace(',', '.'));
                } catch (NumberFormatException e) {
                    d = Double.NaN;
                }
                if (Double.isNaN(d) || d < 90 || d > 100) {
                    throw new FieldValidationException("availability_target", Msg.t(
                            "Erişilebilirlik hedefi 90 ile 100 arasında bir yüzde olmalı (ör. 99,9).",
                            "The availability target must be a percentage between 90 and 100 (for example 99.9)."));
                }
            }
            case RENEWAL_TARGET_KEY -> {
                int n;
                try {
                    n = Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    n = -1;
                }
                if (n < 0 || n > 365) {
                    throw new FieldValidationException("renewal_target_days", Msg.t(
                            "Yenileme hedef süresi 0 ile 365 gün arasında bir tam sayı olmalı (0 = seviye bazlı yenileme süresi).",
                            "The renewal target must be a whole number of days between 0 and 365 (0 = the per-tier renewal lead time)."));
                }
            }
            default -> { /* BOOL anahtarları tipten doğrulanır */ }
        }
    }

    private static boolean bool(Object v) {
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }
}
