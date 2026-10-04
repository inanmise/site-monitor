package com.sitemonitor.service.noc;

import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.model.NocSettings;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.NocSettingsRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * 7/24 İZLEME EKİBİ TAKIMLARI → 7/24 OPERATÖRLERİ (2026-10-04, kullanıcı isteği: "7/24 izleme ekibini takım olarak
 * işaretleyebileyim; bu ekipler tüm izlemeleri görsün, alarmlara not düşsün, arama kaydı girsin").
 *
 * <h2>Model</h2>
 * Global yönetici Ayarlar → 7/24 İzleme Ekibi'nde bir ya da birkaç takımı işaretler ({@code noc_settings.operator_team_ids}).
 * O takımların AKTİF üyeleri (birincil takım VEYA çoklu üyelik — {@code memberTeamIds} ile aynı üyelik) rolleri
 * DEĞİŞMEDEN 7/24 operatörüdür:
 * <ul>
 *   <li>İzleme ile ilgili HER ŞEYİ tüm takımlar için OKUR ({@link SessionScope#seesAllMonitoring} /
 *       {@link SessionScope#canViewMonitoring} kullanan uçlar). Yazma kapsamı aynen kalır.</li>
 *   <li>Arama kaydı girer ({@code noc_calls.write}) ve her alarma yorum/not düşer.</li>
 *   <li>Denetim kaydı, sistem sağlığı, ayarlar, kullanıcı/takım yönetimi, SQL — AUDIT rolüne ait hiçbir alan açılmaz.</li>
 * </ul>
 *
 * <h2>Tazelik</h2>
 * Bayrak oturuma her istekte {@link #sync} ile yazılır; kaynak ≤ {@code site.monitor.noc.operator-cache-ms} (30 sn) yaşlı
 * bellek anlık görüntüsüdür (iki sorgu: ayar satırı + üyelik projeksiyonu — fotoğraf/koleksiyon yüklenmez). Bu podda
 * yapılan kayıt önbelleği hemen düşürür; öteki pod'lar en geç bir TTL sonra görür. Takım listeden çıkarılınca, kullanıcı
 * takımdan ayrılınca ya da pasife alınınca operatörlük bir sonraki tazelemede KALKAR — yeniden giriş gerekmez.
 * Silinen ya da pasif takımlar etkin kümeden kendiliğinden düşer.
 *
 * <p><b>Kapalı düşer:</b> okuma hatasında önceki görüntü korunur; hiç görüntü yoksa kimse operatör sayılmaz.
 */
@Slf4j
@Service
public class NocOperatorService {

    /** Bir kayıtta işaretlenebilecek en çok takım (kolon 2000 karakter — tavan okunur bir ekran için). */
    public static final int MAX_TEAMS = 50;
    /** Önizlemede adıyla listelenen en çok kişi (toplam sayı ayrıca döner). */
    public static final int PREVIEW_USERS = 200;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final NocSettingsRepository settingsRepo;
    private final TeamRepository teamRepo;
    private final AppUserRepository userRepo;

    @Value("${site.monitor.noc.operator-cache-ms:30000}")
    long cacheMs = 30_000;

    /** Saat kaynağı — testte ilerletilir. */
    LongSupplier clock = System::currentTimeMillis;

    /** Değişmez anlık görüntü: etkin 7/24 takımları + kullanıcı → üyesi olduğu 7/24 takımları. */
    record Snapshot(List<Long> teamIds, Map<Long, List<Long>> operatorTeams, long builtAt) {
        static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), Long.MIN_VALUE);
    }

    private volatile Snapshot snapshot;

    public NocOperatorService(NocSettingsRepository settingsRepo, TeamRepository teamRepo, AppUserRepository userRepo) {
        this.settingsRepo = settingsRepo;
        this.teamRepo = teamRepo;
        this.userRepo = userRepo;
    }

    // ── Sorgu ────────────────────────────────────────────────────────────────

    /** Etkin 7/24 takımları (kayıtlı ∩ var olan ∩ aktif), kayıt sırasıyla. */
    public List<Long> teamIds() {
        return current().teamIds();
    }

    /** Kullanıcı 7/24 operatörü mü (etkin bir 7/24 takımının aktif üyesi). */
    public boolean isOperator(Long userId) {
        return userId != null && current().operatorTeams().containsKey(userId);
    }

    /** Kullanıcının üyesi olduğu etkin 7/24 takımları (operatör değilse boş). */
    public List<Long> operatorTeamsOf(Long userId) {
        if (userId == null) return List.of();
        return current().operatorTeams().getOrDefault(userId, List.of());
    }

    /** Etkin operatör sayısı (ayar ekranı özeti). */
    public int operatorCount() {
        return current().operatorTeams().size();
    }

    /**
     * Oturumun operatör özniteliklerini önbellekle eşitler — YALNIZ değiştiyse yazar (JDBC oturum deposunda her
     * istekte yazma olmasın). Kimliği doğrulanmamış / kullanıcı kimliği olmayan oturumda öznitelikler kaldırılır.
     */
    public void sync(HttpSession session) {
        if (session == null) return;
        try {
            Object uid = session.getAttribute("userId");
            Long userId = uid instanceof Number n ? Long.valueOf(n.longValue()) : null;
            List<Long> teams = Boolean.TRUE.equals(session.getAttribute("authenticated")) ? operatorTeamsOf(userId) : List.of();
            boolean op = !teams.isEmpty();
            boolean wasOp = Boolean.TRUE.equals(session.getAttribute(SessionScope.ATTR_NOC_OPERATOR));
            if (op) {
                if (!wasOp) session.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
                if (!Objects.equals(session.getAttribute(SessionScope.ATTR_NOC_TEAM_IDS), teams))
                    session.setAttribute(SessionScope.ATTR_NOC_TEAM_IDS, new ArrayList<>(teams));
            } else {
                if (wasOp || session.getAttribute(SessionScope.ATTR_NOC_OPERATOR) != null)
                    session.removeAttribute(SessionScope.ATTR_NOC_OPERATOR);
                if (session.getAttribute(SessionScope.ATTR_NOC_TEAM_IDS) != null)
                    session.removeAttribute(SessionScope.ATTR_NOC_TEAM_IDS);
            }
            if (op != wasOp) {
                log.info("7/24 operatörlüğü {}: user={} takımlar={}", op ? "verildi" : "kaldırıldı",
                        session.getAttribute("username"), teams);
            }
        } catch (IllegalStateException alreadyInvalidated) {
            // eşzamanlı istek oturumu kapatmış — yapılacak bir şey yok
        }
    }

    // ── Ayar ekranı ──────────────────────────────────────────────────────────

    /** Kayıtlı seçim + künye (ayar ekranı). Silinmiş takımlar düşer; pasif takım {@code active=false} ile döner. */
    public Map<String, Object> settingsDto() {
        NocSettings s = settingsRepo.findById(NocSettings.SINGLETON_ID).orElse(null);
        List<Long> stored = NocGroupIds.parse(s == null ? null : s.getOperatorTeamIds());
        Map<Long, Team> teams = teamsById(stored);
        List<Long> existing = stored.stream().filter(teams::containsKey).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("team_ids", existing);
        m.put("teams", existing.stream().map(id -> teamDto(teams.get(id))).toList());
        m.put("operator_count", operatorCount());
        m.put("updated_at", s == null ? null : s.getOperatorTeamsUpdatedAt());
        m.put("updated_by_name", s == null ? null : s.getOperatorTeamsUpdatedByName());
        m.put("max_teams", MAX_TEAMS);
        return m;
    }

    /**
     * Seçim önizlemesi: takım başına aktif üye sayısı + etkilenecek aktif kullanıcılar (ad + 7/24 takımları), en çok
     * {@link #PREVIEW_USERS} kişi; {@code user_count} toplam (tekil). Pasif kullanıcı ve pasif takım SAYILMAZ.
     */
    public Map<String, Object> preview(Collection<Long> rawIds) {
        List<Long> ids = NocGroupIds.parse(NocGroupIds.format(rawIds));
        Map<Long, Team> teams = teamsById(ids);
        List<Long> active = ids.stream().filter(id -> teams.containsKey(id) && !Boolean.FALSE.equals(teams.get(id).getActive())).toList();
        Map<Long, Member> members = members(active);
        Map<Long, Integer> perTeam = new LinkedHashMap<>();
        for (Long t : active) perTeam.put(t, 0);
        for (Member mb : members.values()) for (Long t : mb.teams) perTeam.merge(t, 1, Integer::sum);

        List<Map<String, Object>> teamRows = new ArrayList<>();
        for (Long id : ids) {
            Team t = teams.get(id);
            if (t == null) continue;
            Map<String, Object> row = teamDto(t);
            row.put("member_count", perTeam.getOrDefault(id, 0));
            teamRows.add(row);
        }
        List<Member> sorted = new ArrayList<>(members.values());
        sorted.sort(Comparator.comparing((Member mb) -> mb.name.toLowerCase(java.util.Locale.ROOT)).thenComparing(mb -> mb.id));
        List<Map<String, Object>> users = new ArrayList<>();
        for (Member mb : sorted.subList(0, Math.min(PREVIEW_USERS, sorted.size()))) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("user_id", mb.id);
            u.put("display_name", mb.name);
            u.put("username", mb.username);
            u.put("system_role", mb.role);
            u.put("team_ids", new ArrayList<>(mb.teams));
            u.put("team_names", mb.teams.stream().map(t -> teams.get(t) == null ? String.valueOf(t) : teams.get(t).getName()).toList());
            users.add(u);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teams", teamRows);
        out.put("user_count", members.size());
        out.put("users", users);
        out.put("truncated", members.size() > users.size());
        return out;
    }

    /** Kayıt sonucu (denetim diff'i için önce/sonra). */
    public record SaveResult(List<Long> before, List<Long> after, Map<Long, String> names) {}

    /**
     * Seçimi baştan yazar. Bilinmeyen takım kimliği 400; tavan {@link #MAX_TEAMS}. Bu podun önbelleği hemen düşer.
     * Yetki ÇAĞIRANDA (yalnız global yönetici).
     */
    public SaveResult save(Object rawTeamIds, String actor, String actorName) {
        List<Long> ids = parseIds(rawTeamIds);
        if (ids.size() > MAX_TEAMS) throw new IllegalArgumentException(Msg.t(
                "En fazla " + MAX_TEAMS + " takım işaretlenebilir", "At most " + MAX_TEAMS + " teams can be selected"));
        Map<Long, Team> teams = teamsById(ids);
        for (Long id : ids) {
            if (!teams.containsKey(id)) throw new IllegalArgumentException(Msg.t(
                    "Takım bulunamadı: " + id, "Team not found: " + id));
        }
        NocSettings s = settingsRepo.findById(NocSettings.SINGLETON_ID).orElseGet(NocSettings::new);
        s.setId(NocSettings.SINGLETON_ID);
        List<Long> before = NocGroupIds.parse(s.getOperatorTeamIds());
        s.setOperatorTeamIds(NocGroupIds.format(ids));
        s.setOperatorTeamsUpdatedAt(ISO.format(Instant.now()));
        s.setOperatorTeamsUpdatedBy(actor);
        s.setOperatorTeamsUpdatedByName(actorName);
        settingsRepo.save(s);
        invalidate();
        Map<Long, String> names = new HashMap<>();
        Set<Long> all = new LinkedHashSet<>(before);
        all.addAll(ids);
        for (Team t : teamRepo.findAllById(all)) if (t.getId() != null) names.put(t.getId(), t.getName());
        return new SaveResult(before, ids, names);
    }

    /** Gövde değeri (JSON dizi / virgüllü metin / tek sayı) → tekil pozitif kimlikler; sayı olmayan öğe 400. */
    public static List<Long> parseIds(Object raw) {
        if (raw == null) return List.of();
        List<Long> out = new ArrayList<>();
        Collection<?> items = raw instanceof Collection<?> c ? c
                : raw instanceof Number n ? List.of(n)
                : List.of((Object[]) raw.toString().split("[,;]"));
        for (Object o : items) {
            if (o == null || o.toString().isBlank()) continue;
            Long v;
            if (o instanceof Number n) v = n.longValue();
            else {
                try { v = Long.parseLong(o.toString().trim()); }
                catch (NumberFormatException e) {
                    throw new IllegalArgumentException(Msg.t("Geçersiz takım kimliği: " + o, "Invalid team id: " + o));
                }
            }
            if (v <= 0) throw new IllegalArgumentException(Msg.t("Geçersiz takım kimliği: " + o, "Invalid team id: " + o));
            out.add(v);
        }
        return NocGroupIds.parse(NocGroupIds.format(out));
    }

    /** Bu podun önbelleğini düşürür (kayıt sonrası; testler). */
    public void invalidate() {
        snapshot = null;
    }

    // ── İç ───────────────────────────────────────────────────────────────────

    Snapshot current() {
        Snapshot s = snapshot;
        long now = clock.getAsLong();
        if (s != null && now - s.builtAt() < cacheMs) return s;
        synchronized (this) {
            s = snapshot;
            if (s != null && now - s.builtAt() < cacheMs) return s;
            try {
                s = build(now);
            } catch (Exception e) {
                log.warn("7/24 operatör listesi tazelenemedi ({}): önceki görüntü kullanılıyor", e.toString());
                s = snapshot != null ? new Snapshot(snapshot.teamIds(), snapshot.operatorTeams(), now)
                                     : new Snapshot(List.of(), Map.of(), now);
            }
            snapshot = s;
            return s;
        }
    }

    private Snapshot build(long now) {
        NocSettings s = settingsRepo.findById(NocSettings.SINGLETON_ID).orElse(null);
        List<Long> stored = NocGroupIds.parse(s == null ? null : s.getOperatorTeamIds());
        if (stored.isEmpty()) return new Snapshot(List.of(), Map.of(), now);
        Map<Long, Team> teams = teamsById(stored);
        List<Long> effective = stored.stream()
                .filter(id -> teams.containsKey(id) && !Boolean.FALSE.equals(teams.get(id).getActive())).toList();
        if (effective.isEmpty()) return new Snapshot(List.of(), Map.of(), now);
        Map<Long, List<Long>> ops = new HashMap<>();
        for (Member mb : members(effective).values()) ops.put(mb.id, List.copyOf(mb.teams));
        return new Snapshot(List.copyOf(effective), Map.copyOf(ops), now);
    }

    /** Aktif üye (projeksiyon) — 7/24 takımları kümesiyle kesişen üyelikleri. */
    private static final class Member {
        final Long id;
        final String username;
        final String name;
        final String role;
        final Set<Long> teams = new TreeSet<>();

        Member(Long id, String username, String name, String role) {
            this.id = id; this.username = username; this.name = name; this.role = role;
        }
    }

    private Map<Long, Member> members(List<Long> teamIds) {
        Map<Long, Member> out = new LinkedHashMap<>();
        if (teamIds.isEmpty()) return out;
        Set<Long> wanted = new LinkedHashSet<>(teamIds);
        for (Object[] r : userRepo.findActiveMemberRowsOfTeams(wanted)) {
            if (r == null || !(r[0] instanceof Number idN)) continue;
            Long id = idN.longValue();
            Member mb = out.computeIfAbsent(id, k -> new Member(k, str(r[1]),
                    displayName(str(r[2]), str(r[3]), str(r[4]), str(r[1])), str(r[7])));
            if (r[5] instanceof Number p && wanted.contains(p.longValue())) mb.teams.add(p.longValue());
            if (r[6] instanceof Number t && wanted.contains(t.longValue())) mb.teams.add(t.longValue());
        }
        out.values().removeIf(mb -> mb.teams.isEmpty());
        return out;
    }

    private Map<Long, Team> teamsById(Collection<Long> ids) {
        Map<Long, Team> m = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return m;
        for (Team t : teamRepo.findAllById(ids)) if (t != null && t.getId() != null) m.put(t.getId(), t);
        return m;
    }

    private static Map<String, Object> teamDto(Team t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("active", !Boolean.FALSE.equals(t.getActive()));
        return m;
    }

    /** {@code NocCallListService.displayName} ile aynı kural (görünen ad → ad soyad → kullanıcı adı). */
    static String displayName(String display, String first, String last, String username) {
        if (display != null && !display.isBlank()) return display;
        String full = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
        return full.isEmpty() ? (username == null ? "" : username) : full;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
