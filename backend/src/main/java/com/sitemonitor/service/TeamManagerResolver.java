package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Takım Müdürü — TEK kişi — sunucu tarafı TEK çözücü (kod denetimi 2026-09-29, A1-O3/O4).
 *
 * <p>{@code frontend/src/utils/teamManager.js} ({@code resolveTeamManagerEntry}) kuralının Java ikizi; iki uygulama
 * {@code TeamManagerResolverTest} ↔ {@code teamManager.test.js} aynı fikstürlerle aynı kişiyi seçer. Eskiden 7/24
 * (NOC) postası ({@code NocCallListService.adChainManager}) dört noktada UI'dan sapıyordu: üye olan adayı düşürüyor,
 * PO/TECH rollü adayı elemiyor, yalnız PO/lider kalınca yukarı çıkmıyor, sicil eşleşmesini saymıyordu → aynı takım
 * için gece nöbetçi yanlış kişiyi arayabiliyordu.
 *
 * <p>Kural:
 * <ol>
 *   <li>Elle atanmış müdür ({@code teams.manager_id}) VARSA ve kişi aktifse o kazanır ({@code manual=true}, ekranda
 *       "(elle)"). Kişi bulunamıyor/pasifse türetmeye düşülür — pasif biri takımın müdürü olamaz.</li>
 *   <li>Adaylar = takım ÜYELERİNİN bağlı olduğu kişiler ({@code manager_id}; yoksa {@code manager_sicil} → sicili
 *       TEKİL eşleşen aktif kullanıcı → yoksa sicil metni). Müdürün kendisi üye olsa da adaydır.</li>
 *   <li>Takım lideri ve yönetici olmayan roller (PO, TECH) aday değildir.</li>
 *   <li>Başka bir adayın yönetim zincirinde ÜSTÜ olan aday düşer (bölüm başkanı, müdürün üstü).</li>
 *   <li>Kalanlar: en yakın kademe (MANAGER &lt; bilinmeyen &lt; BOLUM_BASKANI &lt; CLEVEL), sonra en çok doğrudan
 *       bağlısı olan, sonra ad (Türkçe sıralama).</li>
 *   <li>Hiç aday kalmazsa elenen (PO/lider) adayların bir üst yöneticisiyle aynı kural yeniden denenir.</li>
 * </ol>
 * Türetilen müdür takımın ÜYESİ DEĞİLDİR: üye listesine/sayaca eklenmez (ürün kararı 2026-09-26).
 *
 * <p>Kullanıcı erişimi {@link UserLookup} ile soyutlanır: bellekteki tam harita (takım sayaçları, rehber) ya da
 * repository (NOC — her postada tüm kullanıcıları yüklememek için). Pasif kullanıcı hiçbir yolda kullanıcı sayılmaz
 * (UI de yalnız aktif kullanıcılarla türetir).
 */
public final class TeamManagerResolver {

    /** Seçilen müdür: {@code userId} null ise yalnız sicil biliniyor (uygulamada kaydı yok). */
    public record Entry(String label, Long userId, boolean manual) {}

    public interface UserLookup {
        /** Aktif kullanıcı; yoksa/pasifse null. */
        AppUser byId(Long id);
        /** Sicili (boşluk/harf duyarsız) taşıyan AKTİF kullanıcılar — birden fazlaysa çözücü bağ kurmaz. */
        List<AppUser> byEmployeeId(String sicil);

        /** Bellekteki haritadan (id → kullanıcı); pasifler dışarıda bırakılır. */
        static UserLookup ofUsers(Collection<AppUser> users) {
            Map<Long, AppUser> byId = new LinkedHashMap<>();
            for (AppUser u : users) if (u != null && u.getId() != null && Boolean.TRUE.equals(u.getActive())) byId.put(u.getId(), u);
            return new UserLookup() {
                @Override public AppUser byId(Long id) { return id == null ? null : byId.get(id); }
                @Override public List<AppUser> byEmployeeId(String sicil) {
                    String s = normSicil(sicil);
                    List<AppUser> out = new ArrayList<>();
                    if (s.isEmpty()) return out;
                    for (AppUser u : byId.values()) if (normSicil(u.getEmployeeId()).equals(s)) out.add(u);
                    return out;
                }
            };
        }
    }

    private static final Set<String> NON_MANAGER_ROLES = Set.of("PO", "TECH");
    private static final Map<String, Integer> RANK = Map.of("MANAGER", 0, "BOLUM_BASKANI", 2, "CLEVEL", 3);
    private static final int MAX_CHAIN = 8;
    private static final Collator TR = Collator.getInstance(Locale.forLanguageTag("tr"));

    private TeamManagerResolver() {}

    /** Takımın müdürü: elle atama → türetme. {@code members} takımın üyeleri (pasifler burada elenir). */
    public static Optional<Entry> resolve(Team team, Collection<AppUser> members, UserLookup users) {
        if (team != null && team.getManagerId() != null) {
            AppUser m = users.byId(team.getManagerId());
            if (m != null) return Optional.of(new Entry(displayName(m), m.getId(), true));
        }
        return derive(members, users, team != null ? team.getLeaderId() : null);
    }

    /** Yalnız türetme (elle atamaya bakmaz). */
    public static Optional<Entry> derive(Collection<AppUser> members, UserLookup users, Long leaderId) {
        List<AppUser> active = new ArrayList<>();
        if (members != null) for (AppUser u : members) if (u != null && Boolean.TRUE.equals(u.getActive())) active.add(u);
        Pick first = pick(collect(active, users), users, leaderId);
        Candidate chosen = first.chosen;
        if (chosen == null && !first.excluded.isEmpty()) {
            // Yalnız PO/lider adaylar kaldıysa bir kademe yukarı çık (lider takım üyesi değilse bu gerekir).
            List<AppUser> ups = new ArrayList<>();
            for (Candidate c : first.excluded) if (c.user != null) ups.add(c.user);
            chosen = pick(collect(ups, users), users, leaderId).chosen;
        }
        if (chosen == null) return Optional.empty();
        return Optional.of(new Entry(chosen.label, chosen.user != null ? chosen.user.getId() : null, false));
    }

    private static final class Candidate {
        final String key; final String label; final AppUser user; int count;
        Candidate(String key, String label, AppUser user) { this.key = key; this.label = label; this.user = user; }
    }
    private record Pick(Candidate chosen, List<Candidate> excluded) {}

    /** Kişinin müdürü (kullanıcı nesnesi) — manager_id, yoksa tekil eşleşen sicil. */
    private static AppUser managerUserOf(AppUser u, UserLookup users) {
        if (u == null) return null;
        if (u.getManagerId() != null) return users.byId(u.getManagerId());
        String s = normSicil(u.getManagerSicil());
        if (s.isEmpty()) return null;
        List<AppUser> hits = users.byEmployeeId(s);
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private static String keyOf(AppUser u, UserLookup users) {
        if (u == null) return null;
        if (u.getManagerId() != null) return "id:" + u.getManagerId();
        String s = normSicil(u.getManagerSicil());
        if (s.isEmpty()) return null;
        List<AppUser> hits = users.byEmployeeId(s);
        return hits.size() == 1 ? "id:" + hits.get(0).getId() : "sicil:" + s;
    }

    private static Set<String> ancestorKeys(AppUser user, UserLookup users) {
        Set<String> out = new HashSet<>();
        AppUser cur = user;
        for (int i = 0; i < MAX_CHAIN && cur != null; i++) {
            String k = keyOf(cur, users);
            if (k == null || out.contains(k)) break;
            out.add(k);
            cur = managerUserOf(cur, users);
        }
        return out;
    }

    private static List<Candidate> collect(List<AppUser> people, UserLookup users) {
        LinkedHashMap<String, Candidate> map = new LinkedHashMap<>();
        for (AppUser u : people) {
            String key = keyOf(u, users);
            if (key == null) continue;
            AppUser mgr = managerUserOf(u, users);
            // Sicil bir kullanıcıya çözüldüyse etiket de o kullanıcının adı (id ile gelenle aynı aday); çözülmediyse
            // sicil metni (uygulamada kaydı olmayan müdür).
            String label = mgr != null ? displayName(mgr) : normSicil(u.getManagerSicil());
            if (label == null || label.isEmpty()) continue;
            Candidate c = map.computeIfAbsent(key, k -> new Candidate(k, label, mgr));
            c.count++;
        }
        return new ArrayList<>(map.values());
    }

    private static Pick pick(List<Candidate> candidates, UserLookup users, Long leaderId) {
        List<Candidate> excluded = new ArrayList<>();
        List<Candidate> remaining = new ArrayList<>();
        for (Candidate c : candidates) {
            boolean isLeader = leaderId != null && c.user != null && leaderId.equals(c.user.getId());
            boolean nonManager = c.user != null && c.user.getOrgRole() != null
                    && NON_MANAGER_ROLES.contains(c.user.getOrgRole().toUpperCase(Locale.ROOT));
            if (isLeader || nonManager) excluded.add(c); else remaining.add(c);
        }
        // Başka bir adayın üstü olan aday düşer (ilk yönetici = zincirde en yakın olan).
        Set<String> above = new HashSet<>();
        for (Candidate c : remaining) if (c.user != null) above.addAll(ancestorKeys(c.user, users));
        remaining.removeIf(c -> above.contains(c.key));
        remaining.sort(Comparator.<Candidate>comparingInt(c -> rankOf(c.user))
                .thenComparingInt(c -> -c.count)
                .thenComparing(c -> c.label, TR));
        return new Pick(remaining.isEmpty() ? null : remaining.get(0), excluded);
    }

    private static int rankOf(AppUser u) {
        if (u == null || u.getOrgRole() == null) return 1;
        return RANK.getOrDefault(u.getOrgRole().toUpperCase(Locale.ROOT), 1);
    }

    static String normSicil(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    public static String displayName(AppUser u) {
        if (u == null) return null;
        if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) return u.getDisplayName();
        String full = ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                + (u.getLastName() != null ? u.getLastName() : "")).trim();
        return full.isEmpty() ? u.getUsername() : full;
    }

    /** Takım satırına eklenecek tel alanları ({@code manager_user_id}, {@code manager_display_name}, {@code manager_manual}). */
    public static Map<String, Object> toWire(Optional<Entry> e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("manager_user_id", e.map(Entry::userId).orElse(null));
        m.put("manager_display_name", e.map(Entry::label).orElse(null));
        m.put("manager_manual", e.map(Entry::manual).orElse(false));
        return m;
    }

    /** Üyelik yüklemi — birincil takım ya da çoklu üyelik (TeamDirectory / NOC ile aynı). */
    public static boolean isMember(AppUser u, Long teamId) {
        if (u == null || teamId == null) return false;
        return Objects.equals(teamId, u.getTeamId()) || (u.getTeamIds() != null && u.getTeamIds().contains(teamId));
    }
}
