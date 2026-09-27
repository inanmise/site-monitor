package com.sitemonitor.controller;

import com.sitemonitor.repository.AppUserRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Ekip üyelerinin yaptıkları" görünürlük kapsamı (2026-09-25, kullanıcı kararı).
 *
 * <p>Monitor Changes ve Audit Log eskiden yalnız KAYDIN takımına (izlemenin takımı / aktörün o anki takımı)
 * bakıyordu. Takımı boş kalan satırlar (envanterden türeyen izlemeler, sentetik izleme geçmişi — yerelde
 * 136 değişikliğin 45'i) takım kullanıcısına hiç görünmüyordu, kendi ekip arkadaşı yapmış olsa bile.
 * Bu kapsam, satırı görünür kılan ikinci yolu ekler: AKTÖR kişinin takımlarından birinin üyesiyse.
 *
 * <p>{@code all} = sınırsız (global admin / AUDIT). Aksi hâlde {@code teamIds} + üyelerin kimlikleri ve
 * küçük harf kullanıcı adları. Boş listeler sorgularda IN () sözdizimi hatası vermesin diye KUKLA
 * değerle doldurulur ({@code -1L} / {@link #NO_NAME}) — ScriptedTemplateController'daki aynı tuzak.
 */
public record TeamActorScope(boolean all, List<Long> teamIds, List<Long> actorIds, List<String> actorNames) {

    private static final List<Long> NO_IDS = List.of(-1L);
    /** Boş ad listesinin kuklası: kullanıcı adı olamayacak bir değer (prod kapısı 2026-09-25, D-1 — eskiden `""` idi
     *  ve kullanıcı adı boş yazılmış LOGIN_FAILED satırlarıyla eşleşip takımsız kullanıcıya onların IP/UA'sını
     *  gösteriyordu). */
    static final String NO_NAME = "#no-member#";
    private static final List<String> NO_NAMES = List.of(NO_NAME);
    /** Sistem geneli roller — Denetim Logu ekip kapsamına üye olarak GİRMEZ (R2/D-2 kararı). */
    private static final Set<String> SYSTEM_WIDE_ROLES = Set.of("ADMIN", "AUDIT");

    /** Sınırsız kapsam (global admin / AUDIT). */
    public static TeamActorScope unrestricted() {
        return new TeamActorScope(true, NO_IDS, NO_IDS, NO_NAMES);
    }

    /** Verilen takımlar + onların üyeleri. {@code teams} boşsa kimseyi kapsamaz (kukla değerler). */
    public static TeamActorScope ofTeams(Collection<Long> teams, AppUserRepository users) {
        return ofTeams(teams, users, false);
    }

    /**
     * @param excludeSystemWide true → ADMIN / AUDIT rollü üyeler aktör listesine GİRMEZ (Denetim Logu, kullanıcı
     *        kararı 2026-09-25): rol taşımayan satırlarda (LOGOUT, LOGIN_FAILED, ACCOUNT_LOCKED …) da yöneticinin
     *        başka takımlara dair kayıtları o takıma akmasın (prod kapısı D-2).
     */
    public static TeamActorScope ofTeams(Collection<Long> teams, AppUserRepository users, boolean excludeSystemWide) {
        if (teams == null || teams.isEmpty()) return new TeamActorScope(false, NO_IDS, NO_IDS, NO_NAMES);
        Set<Long> ids = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        // users null: opsiyonel bağımlılık (dilimli test bağlamı) — kapsam yalnız takım koşuluna düşer.
        // Projeksiyon (id + küçük harf ad): tam varlık fotoğraf kolonunu ve takım koleksiyonunu da çekerdi (R9).
        List<Object[]> rows = users == null ? null : users.findMemberIdentities(teams);
        for (Object[] r : rows == null ? List.<Object[]>of() : rows) {
            if (r == null || r.length < 2) continue;
            if (excludeSystemWide && r.length > 2 && r[2] instanceof String role && SYSTEM_WIDE_ROLES.contains(role)) continue;
            if (r[0] instanceof Number n) ids.add(n.longValue());
            if (r[1] instanceof String name && !name.isBlank()) names.add(name.toLowerCase(Locale.ROOT));
        }
        return new TeamActorScope(false, new ArrayList<>(teams),
                ids.isEmpty() ? NO_IDS : new ArrayList<>(ids),
                names.isEmpty() ? NO_NAMES : new ArrayList<>(names));
    }

    /**
     * Satır bu kapsamda görünür mü? Bellekte süzülen küçük listeler (kaynak/aktör geçmişi, tekil kayıt) için;
     * sayfalanan listeler aynı kuralı SORGUDA uygular.
     */
    public boolean allows(Long rowTeamId, Long actorId, String actor) {
        if (all) return true;
        if (rowTeamId != null && teamIds.contains(rowTeamId)) return true;
        if (actorId != null && actorIds.contains(actorId)) return true;
        return actor != null && actorNames.contains(actor.toLowerCase(Locale.ROOT));
    }
}
