package com.sitemonitor.service.techerror;

import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.service.AppSettingsService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Kanal teknik hata raporu modülünün TAKIM bazlı görünürlüğü (2026-10-17, S6). Ayar
 * {@link TechErrorReportSettings#KEY_TEAM_IDS} boşsa herkes görür (eski davranış, byte-identical); doluysa yalnız listedeki
 * takımlar — kurum geneli görüntüleyici (global yönetici, AUDIT) her zaman. Ayar {@code AppSettingsService}'in kendi
 * önbelleğinden okunur (sorgu yok); {@link #current()} statik erişimi denetleyicinin statik kapsam yardımcıları içindir —
 * bean yoksa (dilim testleri) her şey görünür.
 */
@Service
public class TechErrorVisibilityService {

    private static volatile TechErrorVisibilityService instance;

    private final AppSettingsService settings;

    public TechErrorVisibilityService(AppSettingsService settings) {
        this.settings = settings;
        instance = this;
    }

    /** Uygulamadaki tek örnek (statik kapsam yardımcıları için); yoksa null = sınırsız görünürlük. */
    public static TechErrorVisibilityService current() {
        return instance;
    }

    /** Test kancası. */
    public static void setCurrent(TechErrorVisibilityService s) {
        instance = s;
    }

    /** Açık takımlar; {@code null} = herkes. Okuma hatası = herkes (modül bir ayar hatasıyla kapanmasın). */
    public Set<Long> enabledTeamIds() {
        try {
            return TechErrorReportSettings.enabledTeamIds(settings.getString(TechErrorReportSettings.KEY_TEAM_IDS, ""));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public boolean restricted() {
        return enabledTeamIds() != null;
    }

    public boolean teamVisible(Long teamId) {
        Set<Long> enabled = enabledTeamIds();
        return enabled == null || (teamId != null && enabled.contains(teamId));
    }

    /** Oturum modülü görür mü: kurum geneli görüntüleyici her zaman; diğerleri kapsamlarındaki bir takım açıksa. */
    public boolean sessionSees(HttpSession session) {
        if (session == null) return false;
        Set<Long> enabled = enabledTeamIds();
        if (enabled == null) return true;
        if (SessionScope.isGlobalViewer(session)) return true;
        for (Long t : sessionTeamIds(session)) if (enabled.contains(t)) return true;
        return false;
    }

    /** Oturumun görüş + yönetim + üyelik takımları (denetleyicinin {@code visibleTeamIds}'i ile aynı küme). */
    static Set<Long> sessionTeamIds(HttpSession session) {
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
        TechErrorVisibilityService s = instance;
        return s == null || s.teamVisible(teamId);
    }
}
