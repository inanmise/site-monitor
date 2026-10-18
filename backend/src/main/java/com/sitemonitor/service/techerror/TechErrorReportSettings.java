package com.sitemonitor.service.techerror;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.util.Msg;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Kanal teknik hata raporu modül ayarları (2026-10-17, S6: "görünürlük EKİP bazında aç/kapat — seçim yoksa herkes").
 *
 * <p>{@link #KEY_TEAM_IDS}: modülü gören takımların kimlikleri (CSV). BOŞ = herkes (eski davranış). Doluysa yalnız bu
 * takımların üyeleri / yöneticileri / görüş kapsamındakiler modülü görür; kurum geneli görüntüleyiciler (global yönetici,
 * AUDIT) her zaman görür. Anahtar GLOBAL_ONLY: kapsamlı müdür salt okunur görür. Doğrulama hangi uçtan yazılırsa
 * yazılsın aynı ({@code AppSettingsService.validate} kancası).
 *
 * <p>Hatırlatmalar ve posta sınırları (12. sürüm, 2026-10-17 — kullanıcı onayı C9 / C10 / E21): dönem hatırlatması
 * takımları ({@link #KEY_REMINDER_TEAM_IDS}, BOŞ = hatırlatma KAPALI — takım başına açılır), hatırlatma günü, tekrar
 * aralığı, push, onay bekleyen rapor hatırlatması ve e-posta boyut sınırları. Hepsi GLOBAL_ONLY; aralıklar burada,
 * alanlar-arası kural ({@code mail-inline-mb < max-mail-mb}) {@link #mailLimitsProblem}'de — ikisi de
 * {@code AppSettingsService.save} içinden, hangi uçtan yazılırsa yazılsın.
 */
public final class TechErrorReportSettings {

    private TechErrorReportSettings() {}

    public static final String KEY_TEAM_IDS = "site.monitor.tech-error-report.team-ids";
    public static final String KEY_REMINDER_TEAM_IDS = "site.monitor.tech-error-report.reminder-team-ids";
    public static final String KEY_REMINDER_DAY = "site.monitor.tech-error-report.reminder-day";
    public static final String KEY_REMINDER_REPEAT_DAYS = "site.monitor.tech-error-report.reminder-repeat-days";
    public static final String KEY_REMINDER_PUSH = "site.monitor.tech-error-report.reminder-push";
    public static final String KEY_APPROVAL_REMINDER_DAYS = "site.monitor.tech-error-report.approval-reminder-days";
    public static final String KEY_MAX_MAIL_MB = "site.monitor.tech-error-report.max-mail-mb";
    public static final String KEY_MAIL_INLINE_MB = "site.monitor.tech-error-report.mail-inline-mb";
    public static final String GROUP = "tech-error-report";
    public static final int MAX_TEAMS = 500;

    /** Tam sayı ayarının aralığı ve varsayılanı (ön yüz aynı sınırları yanıttan okur). */
    public record IntRange(String key, int min, int max, int def) { }

    public static final IntRange REMINDER_DAY = new IntRange(KEY_REMINDER_DAY, 1, 28, 5);
    public static final IntRange REMINDER_REPEAT_DAYS = new IntRange(KEY_REMINDER_REPEAT_DAYS, 0, 14, 3);
    public static final IntRange APPROVAL_REMINDER_DAYS = new IntRange(KEY_APPROVAL_REMINDER_DAYS, 1, 14, 3);
    public static final IntRange MAX_MAIL_MB = new IntRange(KEY_MAX_MAIL_MB, 5, 50, 15);
    public static final IntRange MAIL_INLINE_MB = new IntRange(KEY_MAIL_INLINE_MB, 0, 20, 6);
    public static final java.util.List<IntRange> INT_RANGES = java.util.List.of(REMINDER_DAY, REMINDER_REPEAT_DAYS,
            APPROVAL_REMINDER_DAYS, MAX_MAIL_MB, MAIL_INLINE_MB);

    public static boolean isKey(String key) {
        return KEY_TEAM_IDS.equals(key) || KEY_REMINDER_TEAM_IDS.equals(key) || KEY_REMINDER_PUSH.equals(key) || range(key) != null;
    }

    static IntRange range(String key) {
        for (IntRange r : INT_RANGES) if (r.key().equals(key)) return r;
        return null;
    }

    /** CSV: pozitif tam sayı kimlikler, en çok {@link #MAX_TEAMS}; biçimsiz değer 400. Boş değer çağırana gelmez. */
    public static void validate(String key, String raw) {
        if (!isKey(key) || raw == null || raw.isBlank()) return;
        IntRange r = range(key);
        if (r != null) {
            Integer v = parseInt(raw);
            if (v == null || v < r.min() || v > r.max()) {
                throw new IllegalArgumentException(rangeMessage(r));
            }
            return;
        }
        if (KEY_REMINDER_PUSH.equals(key)) return;   // BOOL: tür denetimi AppSettingsService'te
        Set<Long> ids = parse(raw);
        if (ids == null) {
            throw new IllegalArgumentException(Msg.t(
                    "Takım listesi geçersiz: virgülle ayrılmış takım kimlikleri bekleniyor.",
                    "The team list is invalid: comma-separated team ids are expected."));
        }
        if (ids.size() > MAX_TEAMS) {
            throw new IllegalArgumentException(Msg.t(
                    "En çok " + MAX_TEAMS + " takım seçilebilir.", "At most " + MAX_TEAMS + " teams can be selected."));
        }
    }

    /** Aralık dışı değerin iletisi (ne · neden · ne yapmalı). */
    public static String rangeMessage(IntRange r) {
        String tr = switch (r.key()) {
            case KEY_REMINDER_DAY -> "Hatırlatma günü " + r.min() + " ile " + r.max() + " arasında olmalı (ayın kaçıncı günü; 29–31 her ayda yok).";
            case KEY_REMINDER_REPEAT_DAYS -> "Tekrar aralığı " + r.min() + " ile " + r.max() + " gün arasında olmalı (0 = yalnız bir kez).";
            case KEY_APPROVAL_REMINDER_DAYS -> "Onay hatırlatması " + r.min() + " ile " + r.max() + " gün arasında olmalı.";
            case KEY_MAX_MAIL_MB -> "E-posta boyut sınırı " + r.min() + " ile " + r.max() + " MB arasında olmalı; kurumsal posta sunucusunun kabul ettiği boyutu aşmayın.";
            case KEY_MAIL_INLINE_MB -> "Gövdedeki görsellerin bütçesi " + r.min() + " ile " + r.max() + " MB arasında olmalı (0 = görseller yalnız PDF'te).";
            default -> "Değer " + r.min() + " ile " + r.max() + " arasında olmalı.";
        };
        String en = switch (r.key()) {
            case KEY_REMINDER_DAY -> "The reminder day must be between " + r.min() + " and " + r.max() + " (day of the month; 29–31 don't exist in every month).";
            case KEY_REMINDER_REPEAT_DAYS -> "The repeat interval must be between " + r.min() + " and " + r.max() + " days (0 = only once).";
            case KEY_APPROVAL_REMINDER_DAYS -> "The approval reminder must be between " + r.min() + " and " + r.max() + " days.";
            case KEY_MAX_MAIL_MB -> "The e-mail size limit must be between " + r.min() + " and " + r.max() + " MB; don't exceed what the corporate mail server accepts.";
            case KEY_MAIL_INLINE_MB -> "The budget for images in the body must be between " + r.min() + " and " + r.max() + " MB (0 = images only in the PDF).";
            default -> "The value must be between " + r.min() + " and " + r.max() + ".";
        };
        return Msg.t(tr, en);
    }

    /**
     * Alanlar-arası kural: gövdedeki görsellerin bütçesi posta sınırından KÜÇÜK olmalı (PDF eki ile görseller aynı postada).
     * Sorun yoksa null; varsa iletisi. Çözülemeyen değer (null) kuralı atlar.
     */
    public static String mailLimitsProblem(Integer maxMb, Integer inlineMb) {
        if (maxMb == null || inlineMb == null) return null;
        if (inlineMb >= maxMb) {
            return Msg.t("Gövdedeki görsellerin bütçesi (" + inlineMb + " MB) e-posta boyut sınırından (" + maxMb
                            + " MB) küçük olmalı: PDF eki ile görseller aynı postada gider. Bütçeyi düşürün ya da sınırı yükseltin.",
                    "The budget for images in the body (" + inlineMb + " MB) must be smaller than the e-mail size limit (" + maxMb
                            + " MB): the PDF attachment and the images travel in the same e-mail. Lower the budget or raise the limit.");
        }
        return null;
    }

    /**
     * CANLI tam sayı ayarı (E21): ekrandan kaydedilmiş değer (DB override) aralık içindeyse o, yoksa {@code fallback}
     * (önyükleme değeri — {@code @Value} alanı: application.properties / ortam değişkeni; testler alanı doğrudan yazar).
     * Ayar servisi yoksa ya da okunamazsa {@code fallback}. Aralık DIŞI değer (bozuk override ya da aralık dışı ortam değeri)
     * katalog varsayılanına düşer — Ayarlar ekranının gösterdiği {@link #effectiveInt} ile aynı kural (inceleme 2026-10-18:
     * ortamdaki 60 MB işte kullanılırken ekran 15 gösteriyordu).
     */
    public static int liveInt(AppSettingsService settings, IntRange r, int fallback) {
        int boot = fallback >= r.min() && fallback <= r.max() ? fallback : r.def();
        if (settings == null) return boot;
        try {
            String raw = settings.getOverride(r.key());
            if (raw == null) return boot;
            Integer v = parseInt(raw);
            return v != null && v >= r.min() && v <= r.max() ? v : r.def();
        } catch (RuntimeException e) {
            return boot;
        }
    }

    /** Etkin tam sayı değeri (override → özellik → katalog varsayılanı), aralık dışıysa varsayılan. Ayarlar ekranı ve iş için. */
    public static int effectiveInt(AppSettingsService settings, IntRange r) {
        if (settings == null) return r.def();
        try {
            int v = settings.getInt(r.key(), r.def());
            return v >= r.min() && v <= r.max() ? v : r.def();
        } catch (RuntimeException e) {
            return r.def();
        }
    }

    /** Hatırlatma takımları: boş / okunamaz → boş küme (= hatırlatma KAPALI, opt-in). */
    public static Set<Long> reminderTeamIds(AppSettingsService settings) {
        if (settings == null) return Set.of();
        try {
            Set<Long> ids = enabledTeamIds(settings.getString(KEY_REMINDER_TEAM_IDS, ""));
            return ids == null ? Set.of() : ids;
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    public static boolean reminderPush(AppSettingsService settings) {
        if (settings == null) return true;
        try {
            return settings.getBoolean(KEY_REMINDER_PUSH, true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    static Integer parseInt(String raw) {
        if (raw == null) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * CSV → kimlik kümesi. Boş / null → {@code null} (= herkes). Biçimsiz bir parça → {@code null} DEĞİL, {@code parseStrict}
     * ile ayrılır: okuma yolunda bozuk bir değer modülü herkese kapatmasın diye biçimsiz parçalar atlanır.
     */
    public static Set<Long> enabledTeamIds(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Set<Long> out = new LinkedHashSet<>();
        for (String p : raw.split("[,;\\s]+")) {
            if (p.isBlank()) continue;
            try {
                long v = Long.parseLong(p.trim());
                if (v > 0) out.add(v);
            } catch (NumberFormatException ignored) {
                // bozuk parça: atla (okuma yolu toleranslı; kayıt yolu validate ile katı)
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** Katı ayrıştırma (kayıt): bir parça bile biçimsizse {@code null}. */
    static Set<Long> parse(String raw) {
        Set<Long> out = new LinkedHashSet<>();
        for (String p : raw.split("[,;\\s]+")) {
            if (p.isBlank()) continue;
            try {
                long v = Long.parseLong(p.trim());
                if (v <= 0) return null;
                out.add(v);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }
}
