package com.sitemonitor.service.techerror;

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
 */
public final class TechErrorReportSettings {

    private TechErrorReportSettings() {}

    public static final String KEY_TEAM_IDS = "site.monitor.tech-error-report.team-ids";
    public static final String GROUP = "tech-error-report";
    public static final int MAX_TEAMS = 500;

    public static boolean isKey(String key) {
        return KEY_TEAM_IDS.equals(key);
    }

    /** CSV: pozitif tam sayı kimlikler, en çok {@link #MAX_TEAMS}; biçimsiz değer 400. Boş değer çağırana gelmez. */
    public static void validate(String key, String raw) {
        if (!isKey(key) || raw == null || raw.isBlank()) return;
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
