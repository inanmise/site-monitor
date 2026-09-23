package com.sitemonitor.controller;

import jakarta.servlet.http.HttpSession;

import java.util.List;

/**
 * Team-scoping helpers backed by session attributes set in
 * {@code AuthController.populateSession} (Faz 3b).
 *
 * <ul>
 *   <li>{@code viewTeamIds} — read scope. {@code null} = unrestricted (global admin / AUDIT);
 *       a list (possibly empty) = visible teams only.</li>
 *   <li>{@code manageTeamIds} — write scope. {@code null} = unrestricted (global admin);
 *       a list (possibly empty) = manageable teams only (empty = none, e.g. müdür/USER).</li>
 * </ul>
 *
 * A "müdür" is an AD-authenticated ADMIN: it has a non-null {@code viewTeamIds}, so it is
 * NOT a global admin even though its systemRole is ADMIN.
 */
public final class SessionScope {

    private SessionScope() {}

    @SuppressWarnings("unchecked")
    public static List<Long> viewTeamIds(HttpSession session) {
        Object o = session != null ? session.getAttribute("viewTeamIds") : null;
        return (o instanceof List) ? (List<Long>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Long> manageTeamIds(HttpSession session) {
        Object o = session != null ? session.getAttribute("manageTeamIds") : null;
        return (o instanceof List) ? (List<Long>) o : null;
    }

    /**
     * ÜYELİK kapsamı — "bu kullanıcı hangi takımların ÜYESİ?". ASLA {@code null} dönmez.
     *
     * <p>{@code viewTeamIds}/{@code manageTeamIds}'ten farkı: view müdürde ASTLARIN takımlarını
     * içerir (görüş alanı, üyelik değil), manage ise USER'da BOŞTUR (yönetim yetkisi, üyelik
     * değil). "Takımın her üyesi kendi takımının şablonunu düzenler" kuralı ancak bu kapsamla
     * yazılabilir.
     *
     * <p><b>ROLLING-DEPLOY GERİ DÜŞÜŞÜ — opsiyonel değil.</b> Spring Session JDBC oturumları
     * pod ölümünden sağ çıkarıyor: yeni sürüme geçerken ESKİ pod'un ürettiği oturumlarda bu
     * nitelik YOKTUR. O oturumları kilitlemek yerine birincil takıma düşüyoruz — çok-takımlı
     * bir kullanıcı birkaç dakika yalnız ana takımının şablonlarını düzenleyebilir, ama kimse
     * "yetkiniz yok" duvarına toslamaz. Nitelik bir sonraki girişte doğru dolar.
     */
    @SuppressWarnings("unchecked")
    public static List<Long> memberTeamIds(HttpSession session) {
        if (session == null) return List.of();
        Object o = session.getAttribute("memberTeamIds");
        if (o instanceof List) return (List<Long>) o;
        Object primary = session.getAttribute("teamId");
        return primary instanceof Number n ? List.of(n.longValue()) : List.of();
    }

    /** Kullanıcı bu takımın ÜYESİ mi? (yetki değil, üyelik sorusu) */
    public static boolean isMemberOf(HttpSession session, Long teamId) {
        return teamId != null && memberTeamIds(session).contains(teamId);
    }

    /** True only for the unrestricted (local/bootstrap) ADMIN — never for a scoped müdür. */
    public static boolean isGlobalAdmin(HttpSession session) {
        return session != null
                && "ADMIN".equals(session.getAttribute("systemRole"))
                && session.getAttribute("viewTeamIds") == null;
    }

    /** Global read (sees all teams): global admin or AUDIT (both have null view scope).
     *  Role is checked too, so a non-privileged session without a view scope is never
     *  treated as a global viewer (defence in depth — populateSession always sets it). */
    public static boolean isGlobalViewer(HttpSession session) {
        if (session == null) return false;
        Object role = session.getAttribute("systemRole");
        return ("ADMIN".equals(role) || "AUDIT".equals(role))
                && session.getAttribute("viewTeamIds") == null;
    }

    /** Whether the caller may manage (write) a resource owned by {@code teamId}.
     *  Only the global admin manages with a null scope; any other role must have the
     *  team explicitly in its manage scope. */
    public static boolean canManage(HttpSession session, Long teamId) {
        if (isGlobalAdmin(session)) return true;          // global manage
        List<Long> m = manageTeamIds(session);
        return m != null && teamId != null && m.contains(teamId);
    }

    /**
     * Sistem ayarı yüzeyleri (SMTP/LDAP/genel/marka/sır araçları/fırtına/anomali/DB) için ek kapı:
     * <b>kapsamlı ADMIN (müdür) geçemez</b> — matris izni ({@code permissionService.require}) ayrıca
     * sorulur.
     *
     * <p>ADMIN rolü matriste her izni taşır; AD-kaynaklı müdür de ADMIN rolüyle gelir ama
     * {@code viewTeamIds} dolu olduğundan global değildir. Yalnız matris izniyle kapılı ayar uçları
     * müdürü global admin sanıyordu: LDAP bind/SMTP host'unu kendi sunucusuna çevirip "test" ile
     * saklı (çözülmüş) kimlik bilgisini alabiliyor, CA paketini ve SSRF allow-internal ayarını
     * değiştirebiliyordu. Frontend sekmeyi zaten gizliyordu; backend artık aynı sınırı uygular.
     * Diğer rollere verilen açık matris grant'ları etkilenmez.
     */
    public static void requireNotScopedAdmin(HttpSession session, String resourceKey) {
        if (isScopedAdmin(session)) {
            throw new SecurityException(com.sitemonitor.util.Msg.t(
                    "Bu ayar yalnız global yönetici tarafından değiştirilebilir: ",
                    "Only a global administrator can change this setting: ") + resourceKey);
        }
    }

    /**
     * Kapsamlı müdür: rol ADMIN ama {@code viewTeamIds} dolu (AD'den gelen takım-kapsamlı yönetici).
     * 2026-09-10 ürün kararı: Ayarlar'ı GÖRÜR ve operasyonel bölümleri düzenler; yalnız
     * {@link #requireNotScopedAdmin} ile kapılı dört sır yüzeyi (SMTP, LDAP, Secret Decryptor,
     * Veritabanı) ve {@code AppSettingsCatalog.GLOBAL_ONLY} anahtarları global yöneticide kalır.
     */
    public static boolean isScopedAdmin(HttpSession session) {
        return session != null && "ADMIN".equals(session.getAttribute("systemRole"))
                && session.getAttribute("viewTeamIds") != null;
    }

    /**
     * İstek bağlamındaki oturum GLOBAL yönetici mi? {@code AppSettingsCatalog.GLOBAL_ONLY}
     * anahtarlarının tek kapısı.
     *
     * <p>Kapı eskiden {@link #isScopedAdminInRequest} ile kuruluyordu, ama o yalnız "rol ADMIN
     * ama takım-kapsamlı" durumunu yakalıyor; TEAM_ADMIN ve USER için FALSE dönüyordu. İzin
     * matrisinden {@code settings.general/edit} verilen herhangi bir role SSRF/CA/push-URL
     * anahtarlarını yazma yolu açık kalıyordu.
     *
     * <p><b>HTTP bağlamı YOKSA true döner:</b> zamanlayıcı, açılış ve diğer sistem çağrıları
     * oturum taşımaz; bu kapı KULLANICI isteklerini süzmek içindir, sistem yazmalarını değil.
     * Bağlam VAR ama oturum yoksa (kimliksiz istek) false.
     */
    public static boolean isGlobalAdminInRequest() {
        try {
            var ra = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (ra instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
                HttpSession s = sra.getRequest().getSession(false);
                return s != null && isGlobalAdmin(s);
            }
        } catch (Exception ignored) { /* bağlam yok → sistem çağrısı */ }
        return true;
    }

    /** {@link #isScopedAdmin} — istek bağlamındaki oturum için (servis katmanı, HttpSession parametresi yokken). */
    public static boolean isScopedAdminInRequest() {
        try {
            var ra = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (ra instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
                return isScopedAdmin(sra.getRequest().getSession(false));
            }
        } catch (Exception ignored) { /* bağlam yok → kapsamlı değil */ }
        return false;
    }

    /** Whether the caller may VIEW a resource owned by {@code teamId}. Global viewer (admin/AUDIT)
     *  sees all; otherwise the team must be in the read scope. */
    public static boolean canView(HttpSession session, Long teamId) {
        if (isGlobalViewer(session)) return true;
        List<Long> v = viewTeamIds(session);
        return v != null && teamId != null && v.contains(teamId);
    }
}
