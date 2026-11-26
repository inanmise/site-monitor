package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Nöbet rutinleri modülünün TAKIM bazlı görünürlüğü ve yönetici anahtarları (2026-11-26; {@code TechErrorVisibilityService}
 * deseni). {@link #KEY_TEAM_IDS} boşsa her takım görür; doluysa yalnız listedeki takımlar — kurum geneli görüntüleyici
 * (global yönetici, AUDIT) her zaman. Oturum kümesine erişim PAYLAŞIMI ve 7/24 operatörlüğü EKLENMEZ (modül takım belgesi).
 * Ayar {@code AppSettingsService} önbelleğinden okunur (sorgu yok); bean yoksa (dilim testleri) her şey görünür.
 */
@Service
public class OnCallRoutineVisibility {

    public static final String KEY_TEAM_IDS = "site.monitor.oncall-routine.team-ids";
    public static final String KEY_PUSH_REMINDERS = "site.monitor.oncall-routine.push-reminders";
    public static final String KEY_COMPLETION_NOTICE = "site.monitor.oncall-routine.completion-notice";
    public static final String KEY_HANDOVER_NOTICE = "site.monitor.oncall-routine.handover-notice";
    public static final String KEY_RETENTION_DAYS = "site.monitor.oncall-routine.retention-days";
    /** Seçilebilecek en çok takım. */
    public static final int MAX_TEAMS = 500;

    private static volatile OnCallRoutineVisibility instance;

    private final AppSettingsService settings;

    public OnCallRoutineVisibility(AppSettingsService settings) {
        this.settings = settings;
        instance = this;
    }

    public static OnCallRoutineVisibility current() {
        return instance;
    }

    /** Test kancası. */
    public static void setCurrent(OnCallRoutineVisibility v) {
        instance = v;
    }

    /** Açık takımlar; {@code null} = herkes. Okuma hatası = herkes (modül bir ayar hatasıyla kapanmasın). */
    public Set<Long> enabledTeamIds() {
        try {
            return parseLenient(settings.getString(KEY_TEAM_IDS, ""));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public boolean teamVisible(Long teamId) {
        Set<Long> enabled = enabledTeamIds();
        return enabled == null || (teamId != null && enabled.contains(teamId));
    }

    /** Oturum modülü görür mü: kurum geneli görüntüleyici her zaman; diğerleri KENDİ kapsamlarındaki bir takım açıksa. */
    public boolean sessionSees(HttpSession session) {
        if (session == null) return false;
        Set<Long> enabled = enabledTeamIds();
        if (enabled == null) return true;
        if (SessionScope.isGlobalViewer(session)) return true;
        for (Long t : ownTeamIds(session)) if (enabled.contains(t)) return true;
        return false;
    }

    /** Görüş + yönetim + üyelik takımları — paylaşım ve 7/24 YOK. */
    public static Set<Long> ownTeamIds(HttpSession session) {
        Set<Long> out = new LinkedHashSet<>();
        List<Long> v = SessionScope.viewTeamIds(session);
        if (v != null) out.addAll(v);
        List<Long> m = SessionScope.manageTeamIds(session);
        if (m != null) out.addAll(m);
        out.addAll(SessionScope.memberTeamIds(session));
        out.remove(null);
        return out;
    }

    /** Statik yardımcı: bean varsa takım görünürlüğü, yoksa true. */
    public static boolean visible(Long teamId) {
        OnCallRoutineVisibility s = instance;
        return s == null || s.teamVisible(teamId);
    }

    /** Yönetici anahtarı açık mı (varsayılan AÇIK; ayar servisi yoksa ya da okunamazsa açık). */
    public static boolean switchOn(AppSettingsService settings, String key) {
        if (settings == null) return true;
        try {
            return settings.getBoolean(key, true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    // ── Ayrıştırma / doğrulama ───────────────────────────────────────────────────────────────────

    public static Set<Long> parseLenient(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Set<Long> out = new LinkedHashSet<>();
        for (String p : raw.split("[,;\\s]+")) {
            if (p.isBlank()) continue;
            try {
                long v = Long.parseLong(p.trim());
                if (v > 0) out.add(v);
            } catch (NumberFormatException ignored) {
                // bozuk parça atlanır (okuma toleranslı)
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** Katı: bir parça bile biçimsizse null. */
    public static Set<Long> parseStrict(String raw) {
        Set<Long> out = new LinkedHashSet<>();
        if (raw == null) return out;
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

    public static boolean isTeamIdsKey(String key) {
        return KEY_TEAM_IDS.equals(key);
    }

    /** {@code AppSettingsService.save} kancası — hangi uçtan yazılırsa yazılsın aynı biçim kuralı. */
    public static void validate(String key, String raw) {
        if (!isTeamIdsKey(key) || raw == null || raw.isBlank()) return;
        Set<Long> ids = parseStrict(raw);
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
}
