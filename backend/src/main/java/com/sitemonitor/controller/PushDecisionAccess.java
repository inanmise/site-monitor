package com.sitemonitor.controller;

import com.sitemonitor.service.UserPushRecipientResolver;
import com.sitemonitor.service.UserPushService;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kişi bazlı push kararlarını KİM görür? (2026-09-28) — "Kim bilgilendirilir?" simülatörü ve Ayarlar → Webhook
 * Bildirimleri → "Kim alır?" aracı için TEK kural. Karar satırları kişisel veri taşır (org rolü, AD unvanı,
 * bildirimleri kapatıp kapatmadığı); telefon ve webhook adresi zaten hiçbir satırda YOKTUR.
 *
 * <ul>
 *   <li><b>FULL</b> — takımın BÜTÜN üyeleri: global yönetici her takımda ({@code GLOBAL_ADMIN}); takımı YÖNETEN
 *       TEAM_ADMIN ya da kapsamlı müdür (AD ADMIN) yalnız yönetim kapsamındaki takımlarda ({@code TEAM_MANAGER}).
 *       Rol adına bakılmaz — kapsamlı müdür de "ADMIN" rolündedir ({@link SessionScope#isGlobalAdmin} /
 *       {@link SessionScope#canManage}; müdür tuzağı).</li>
 *   <li><b>SELF</b> — takımın üyesi (USER, AUDIT ya da yönetmediği takımdaki yönetici): yalnız KENDİ satırı
 *       ({@code MEMBER_SELF}) — "Ben bu senaryoda push alır mıyım?".</li>
 *   <li><b>NONE</b> — üyesi de yöneticisi de olmadığı takım ({@code NOT_MEMBER}): kişi satırı yok; kanal durumu
 *       (açık/kapalı, sessiz saat — kişisel değil) yine döner.</li>
 * </ul>
 *
 * <p>{@code settings}: Ayarlar → Webhook Bildirimleri'ne erişim — ekranın "şuradan düzeltin" bağlantısı için:
 * {@code FULL} (global yönetici; servis adresi dâhil), {@code LIMITED} (kapsamlı müdür; adres/başlıklar
 * {@code AppSettingsCatalog.GLOBAL_ONLY}), {@code NONE} (diğerleri — yöneticiye başvurur).
 */
@Slf4j
public final class PushDecisionAccess {

    private PushDecisionAccess() {}

    public enum Level { FULL, SELF, NONE }

    public record Access(Level level, String reason, String settings) {}

    public static Access of(HttpSession session, Long teamId) {
        if (SessionScope.isGlobalAdmin(session)) return new Access(Level.FULL, "GLOBAL_ADMIN", "FULL");
        String settings = SessionScope.isScopedAdmin(session) ? "LIMITED" : "NONE";
        if (teamId == null) return new Access(Level.NONE, "NOT_MEMBER", settings);
        if (SessionScope.canManage(session, teamId)) return new Access(Level.FULL, "TEAM_MANAGER", settings);
        boolean member = SessionScope.isMemberOf(session, teamId) || teamId.equals(SessionScope.primaryTeamId(session));
        return member ? new Access(Level.SELF, "MEMBER_SELF", settings) : new Access(Level.NONE, "NOT_MEMBER", settings);
    }

    /**
     * Simülatörün push ayağı. Kişi kararları {@link UserPushRecipientResolver#explain} — Ayarlar'daki "Kim alır?" ile
     * AYNI servis, AYNI karar kodları (kural kopyası yok); kanal kapıları {@link UserPushService#scenarioChannel}
     * (gerçek gönderimin kodu). Yanıt anahtarları snake_case.
     */
    public static Map<String, Object> pushLeg(HttpSession session, Long teamId, String level, boolean standaloneMonitor,
                                              UserPushRecipientResolver resolver, UserPushService pushService) {
        Access access = of(session, teamId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("push_access", access.level().name());
        out.put("push_access_reason", access.reason());
        out.put("push_settings", access.settings());
        String me = username(session);
        if (me != null) out.put("push_viewer", me);
        try {
            Map<String, Object> channel = pushService.scenarioChannel(teamId, level, standaloneMonitor);
            if (channel != null) out.put("push_channel", channel);
        } catch (RuntimeException e) {
            // Kanal durumu okunamadı: kişi kararları yine gösterilir, ekran "durum bilinmiyor" der (sessiz "açık" değil).
            log.warn("push kanal durumu okunamadı (takım {}): {}", teamId, e.toString());
        }
        if (access.level() == Level.NONE) return out;
        try {
            List<UserPushRecipientResolver.Explanation> rows = resolver.explain(teamId, level);
            if (rows == null) rows = List.of();
            if (access.level() == Level.SELF) {
                rows = rows.stream().filter(r -> me != null && me.equalsIgnoreCase(r.username())).toList();
            }
            out.put("push", rows);
        } catch (RuntimeException e) {
            out.put("push_error", e.getMessage());
        }
        return out;
    }

    private static String username(HttpSession session) {
        Object u = session == null ? null : session.getAttribute("username");
        String s = u == null ? null : u.toString().trim();
        return s == null || s.isEmpty() ? null : s;
    }
}
