package com.sitemonitor.service.access;

import com.sitemonitor.service.cache.CacheBus;
import com.sitemonitor.service.cache.CacheBusNames;
import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TeamAccessGrant;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.TeamAccessGrantRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Takım erişim paylaşımı (2026-10-12, kullanıcı isteği) — kayıt, doğrulama ve OTURUMA yansıtma.
 *
 * <p><b>Anlık görüntü</b> ({@code site.monitor.team-access.cache-ms}, 30 sn): kullanıcı → (paylaşan takım → en yüksek
 * düzey). TEAM paylaşımları alıcı takımın AKTİF üyelerine açılır (birincil ya da çoklu üyelik — {@code memberTeamIds} ile
 * aynı). Pasif kişi, pasif / silinmiş paylaşan takım ve süresi dolmuş paylaşım UYGULANMAZ. Okuma hatası önceki görüntüyü
 * korur (yoksa boş — kimseye paylaşım yok; kapalı düşer). Kayıt sonrası bu podun görüntüsü düşer, diğer podlar ≤ 30 sn.
 *
 * <p><b>Oturum</b> ({@link #sync}, her geçerli istekte {@code AuthInterceptor}): {@code grantViewTeamIds} ⊇
 * {@code grantExecTeamIds} ⊇ {@code grantEditTeamIds} ⊇ {@code grantDeleteTeamIds} (kümülatif) — yalnız değişince yazılır
 * (JDBC oturum deposunda her istekte yazma olmasın). Kapsam yardımcıları {@link SessionScope} içinde.
 *
 * <p><b>Yönetim</b>: paylaşan takımı YÖNETEN (global admin, takımı yöneten müdür / TEAM_ADMIN —
 * {@link SessionScope#canManage}). Alıcı takım paylaşan takımdan farklı olmalı; kişi aktif olmalı; takım başına en çok
 * {@value #MAX_PER_OWNER} paylaşım.
 */
@Slf4j
@Service
public class TeamAccessGrantService {

    public static final int MAX_PER_OWNER = 200;
    public static final int NOTE_MAX = 500;
    /** Bitiş günü üst sınırı — daha ileri tarih kolona (UTC ISO, 19 karakter) sığmazdı. */
    public static final int MAX_EXPIRY_YEAR = 2099;
    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TeamAccessGrantRepository repo;
    private final TeamRepository teamRepo;
    private final AppUserRepository userRepo;

    @Value("${site.monitor.team-access.cache-ms:30000}")
    long cacheMs = 30_000;

    /** Saat kaynağı — testte ilerletilir. */
    LongSupplier clock = System::currentTimeMillis;

    /** Değişmez görüntü: kullanıcı → (paylaşan takım → düzey). */
    record Snapshot(Map<Long, Map<Long, TeamAccessLevel>> byUser, long builtAt) { }

    /**
     * Görüntü REFERANSI (inceleme 5): yapım yalnız başladığı andaki referans hâlâ yerindeyse karşılaştır-değiştir ile saklanır.
     * {@link #invalidate} her zaman YENİ bir (bayat) nesne koyar — sürüm denetimiyle yazma arasına giren bir kayıt da yapımı
     * geçersiz kılar (eskiden kontrol ile atama atomik değildi; kayıttan önce okunmuş görüntü 30 sn kalabiliyordu).
     */
    private final java.util.concurrent.atomic.AtomicReference<Snapshot> snapshot = new java.util.concurrent.atomic.AtomicReference<>();
    /**
     * Kayıt sürümü (2026-10-12): {@link #invalidate} artırır. Bir yapım başladıktan SONRA kayıt değiştiyse o yapım saklanmaz —
     * aksi hâlde silinen paylaşımı okuyan yarım kalmış yapım bayat görüntüyü 30 sn daha yerleştirirdi.
     */
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();

    public TeamAccessGrantService(TeamAccessGrantRepository repo, TeamRepository teamRepo, AppUserRepository userRepo) {
        this.repo = repo;
        this.teamRepo = teamRepo;
        this.userRepo = userRepo;
    }

    // ── Okuma (oturum) ───────────────────────────────────────────────────────

    /** Kullanıcının paylaşımla eriştiği takımlar ve düzeyleri (yoksa boş). */
    public Map<Long, TeamAccessLevel> levelsFor(Long userId) {
        if (userId == null) return Map.of();
        return current().byUser().getOrDefault(userId, Map.of());
    }

    /**
     * Oturumun paylaşım özniteliklerini görüntüyle eşitler — YALNIZ değiştiyse yazar. Kimliği doğrulanmamış oturumda
     * öznitelikler kaldırılır. Okuma hatası isteği düşürmez (çağıran yakalar; önceki değer kalır).
     */
    public void sync(HttpSession session) {
        if (session == null) return;
        try {
            Object uid = session.getAttribute("userId");
            Long userId = uid instanceof Number n ? Long.valueOf(n.longValue()) : null;
            Map<Long, TeamAccessLevel> levels = Boolean.TRUE.equals(session.getAttribute("authenticated"))
                    ? levelsFor(userId) : Map.of();
            put(session, SessionScope.ATTR_GRANT_VIEW, teamsAtLeast(levels, TeamAccessLevel.VIEW));
            put(session, SessionScope.ATTR_GRANT_EXECUTE, teamsAtLeast(levels, TeamAccessLevel.EXECUTE));
            put(session, SessionScope.ATTR_GRANT_EDIT, teamsAtLeast(levels, TeamAccessLevel.EDIT));
            put(session, SessionScope.ATTR_GRANT_DELETE, teamsAtLeast(levels, TeamAccessLevel.DELETE));
        } catch (IllegalStateException alreadyInvalidated) {
            // eşzamanlı istek oturumu kapatmış
        }
    }

    static List<Long> teamsAtLeast(Map<Long, TeamAccessLevel> levels, TeamAccessLevel min) {
        List<Long> out = new ArrayList<>();
        for (Map.Entry<Long, TeamAccessLevel> e : levels.entrySet()) if (e.getValue().atLeast(min)) out.add(e.getKey());
        out.sort(Long::compare);
        return out;
    }

    private static void put(HttpSession session, String attr, List<Long> teams) {
        Object cur = session.getAttribute(attr);
        if (teams.isEmpty()) {
            if (cur != null) session.removeAttribute(attr);
        } else if (!Objects.equals(cur, teams)) {
            session.setAttribute(attr, new ArrayList<>(teams));
        }
    }

    /**
     * Bu podun görüntüsünü düşürür (kayıt sonrası; testler); öteki pod'lar önbellek veri yoluyla (2026-10-22, çok pod R2-A,
     * MP-30: {@link CacheBus}, ad {@link CacheBusNames#TEAM_GRANTS}) en geç bir yoklama aralığında (~2 sn) — eskiden geri
     * alınan / düşürülen paylaşım öteki pod'da {@code cacheMs} (30 sn) boyunca düzenleme / silme yetkisi veriyordu.
     */
    public void invalidate() {
        invalidateLocal();
        CacheBus b = cacheBus;
        if (b != null) b.bump(CacheBusNames.TEAM_GRANTS);
    }

    /** Pod'lar arası boşaltma (MP-30) — yoksa yalnız bu pod. */
    private volatile CacheBus cacheBus;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCacheBus(CacheBus bus) {
        this.cacheBus = bus;
        if (bus != null) bus.register(CacheBusNames.TEAM_GRANTS, this::invalidateLocal);
    }

    /** Yalnız bu pod (öteki pod'un yayını uygulanırken; yeniden yayın yok). */
    void invalidateLocal() {
        generation.incrementAndGet();
        // Görüntü SİLİNMEZ, bayat işaretlenir: bir sonraki okuma yeniden kurar; o kurulum hata verirse önceki görüntü
        // kullanılır (eskiden null'a düşüp HERKESİN paylaşımı 30 sn boşalıyordu).
        snapshot.getAndUpdate(s -> new Snapshot(s != null ? s.byUser() : Map.of(), Long.MIN_VALUE / 2));
    }

    Snapshot current() {
        Snapshot s = snapshot.get();
        long now = clock.getAsLong();
        if (s != null && now - s.builtAt() < cacheMs) return s;
        synchronized (this) {
            Snapshot prev = snapshot.get();
            if (prev != null && now - prev.builtAt() < cacheMs) return prev;
            long gen = generation.get();
            Snapshot built;
            try {
                built = build(now);
            } catch (Exception e) {
                log.warn("Takım erişim paylaşımları tazelenemedi ({}): önceki görüntü kullanılıyor", e.toString());
                built = prev != null ? new Snapshot(prev.byUser(), now) : new Snapshot(Map.of(), now);
            }
            // Yapım sırasında kayıt değiştiyse saklanmaz (bu istek sonucu kullanır; sonraki okuma yeniden kurar)
            if (generation.get() == gen) snapshot.compareAndSet(prev, built);
            return built;
        }
    }

    Snapshot build(long now) {
        List<TeamAccessGrant> all = repo.findAllByOrderByIdAsc();
        if (all.isEmpty()) return new Snapshot(Map.of(), now);
        String nowIso = ISO.format(Instant.ofEpochMilli(now));
        List<TeamAccessGrant> live = new ArrayList<>();
        Set<Long> teamIds = new LinkedHashSet<>();
        Set<Long> userIds = new LinkedHashSet<>();
        for (TeamAccessGrant g : all) {
            if (TeamAccessLevel.parse(g.getLevel()) == null) continue;
            if (g.getExpiresAt() != null && g.getExpiresAt().compareTo(nowIso) <= 0) continue;
            live.add(g);
            teamIds.add(g.getOwnerTeamId());
            if (g.getGranteeTeamId() != null) teamIds.add(g.getGranteeTeamId());
            if (g.getGranteeUserId() != null) userIds.add(g.getGranteeUserId());
        }
        if (live.isEmpty()) return new Snapshot(Map.of(), now);
        Map<Long, Team> teams = new HashMap<>();
        for (Team t : teamRepo.findAllById(teamIds)) if (t != null && t.getId() != null) teams.put(t.getId(), t);
        Set<Long> activeUsers = new LinkedHashSet<>();
        if (!userIds.isEmpty()) {
            for (AppUser u : userRepo.findAllById(userIds)) if (u != null && !Boolean.FALSE.equals(u.getActive())) activeUsers.add(u.getId());
        }
        // TEAM paylaşımlarının alıcı takımlarının AKTİF üyeleri (tek sorgu)
        Set<Long> granteeTeams = new LinkedHashSet<>();
        for (TeamAccessGrant g : live) {
            if (TeamAccessGrant.TYPE_TEAM.equals(g.getGranteeType()) && g.getGranteeTeamId() != null
                    && activeTeam(teams.get(g.getGranteeTeamId()))) granteeTeams.add(g.getGranteeTeamId());
        }
        Map<Long, Set<Long>> membersByTeam = new HashMap<>();
        if (!granteeTeams.isEmpty()) {
            for (Object[] r : userRepo.findActiveMemberRowsOfTeams(granteeTeams)) {
                if (r == null || !(r[0] instanceof Number idN)) continue;
                long id = idN.longValue();
                if (r.length > 5 && r[5] instanceof Number p && granteeTeams.contains(p.longValue()))
                    membersByTeam.computeIfAbsent(p.longValue(), k -> new LinkedHashSet<>()).add(id);
                if (r.length > 6 && r[6] instanceof Number t && granteeTeams.contains(t.longValue()))
                    membersByTeam.computeIfAbsent(t.longValue(), k -> new LinkedHashSet<>()).add(id);
            }
        }
        Map<Long, Map<Long, TeamAccessLevel>> byUser = new HashMap<>();
        for (TeamAccessGrant g : live) {
            if (!activeTeam(teams.get(g.getOwnerTeamId()))) continue;   // pasif / silinmiş takım paylaşamaz
            TeamAccessLevel level = TeamAccessLevel.parse(g.getLevel());
            Collection<Long> users;
            if (TeamAccessGrant.TYPE_USER.equals(g.getGranteeType())) {
                users = g.getGranteeUserId() != null && activeUsers.contains(g.getGranteeUserId())
                        ? List.of(g.getGranteeUserId()) : List.of();
            } else {
                users = membersByTeam.getOrDefault(g.getGranteeTeamId(), Set.of());
            }
            for (Long u : users) {
                byUser.computeIfAbsent(u, k -> new HashMap<>()).merge(g.getOwnerTeamId(), level, TeamAccessLevel::max);
            }
        }
        Map<Long, Map<Long, TeamAccessLevel>> frozen = new HashMap<>();
        byUser.forEach((u, m) -> frozen.put(u, Map.copyOf(m)));
        return new Snapshot(Map.copyOf(frozen), now);
    }

    private static boolean activeTeam(Team t) {
        return t != null && !Boolean.FALSE.equals(t.getActive());
    }

    // ── Yönetim ──────────────────────────────────────────────────────────────

    /** İstek gövdesinin doğrulanmış hâli. */
    public record Input(Long ownerTeamId, String granteeType, Long granteeTeamId, Long granteeUserId,
                        TeamAccessLevel level, String expiresAt, String note) { }

    /**
     * Gövdeyi doğrular: alan alan {@link GlobalExceptionHandler.FieldValidationException} (400 + alan). {@code expires_on}
     * İstanbul tarihi (yyyy-MM-dd) — o günün SONUNA kadar geçerli; geçmiş tarih reddedilir.
     */
    public Input validate(Long ownerTeamId, String granteeType, Long granteeTeamId, Long granteeUserId, String level,
                          String expiresOn, String note) {
        if (ownerTeamId == null || teamRepo.findById(ownerTeamId).filter(TeamAccessGrantService::activeTeam).isEmpty()) {
            throw field("owner_team_id", Msg.t("Paylaşan takım bulunamadı ya da pasif — etkin bir takım seçin.",
                    "The sharing team doesn't exist or is passive — choose an active team."));
        }
        String type = granteeType == null ? "" : granteeType.trim().toUpperCase(java.util.Locale.ROOT);
        Long gTeam = null;
        Long gUser = null;
        if (TeamAccessGrant.TYPE_TEAM.equals(type)) {
            if (granteeTeamId == null || teamRepo.findById(granteeTeamId).filter(TeamAccessGrantService::activeTeam).isEmpty()) {
                throw field("grantee_team_id", Msg.t("Erişim verilecek takımı seçin (etkin bir takım olmalı).",
                        "Choose the team to give access to (it must be an active team)."));
            }
            if (granteeTeamId.equals(ownerTeamId)) {
                throw field("grantee_team_id", Msg.t("Takım kendi kendisiyle paylaşılamaz — başka bir takım seçin.",
                        "A team can't be shared with itself — choose another team."));
            }
            gTeam = granteeTeamId;
        } else if (TeamAccessGrant.TYPE_USER.equals(type)) {
            AppUser u = granteeUserId == null ? null : userRepo.findById(granteeUserId).orElse(null);
            if (u == null || Boolean.FALSE.equals(u.getActive())) {
                throw field("grantee_user_id", Msg.t("Erişim verilecek kişiyi seçin (aktif bir kullanıcı olmalı).",
                        "Choose the person to give access to (it must be an active user)."));
            }
            gUser = granteeUserId;
        } else {
            throw field("grantee_type", Msg.t("Paylaşım türünü seçin: takım ya da kişi.",
                    "Choose who to share with: a team or a person."));
        }
        return new Input(ownerTeamId, type, gTeam, gUser, level(level), expiresAt(expiresOn), note(note));
    }

    /** Düzey — tanınmazsa 400 {@code level}. */
    public TeamAccessLevel level(String level) {
        TeamAccessLevel lv = TeamAccessLevel.parse(level);
        if (lv == null) {
            throw field("level", Msg.t("Erişim düzeyini seçin: görüntüleme, çalıştırma, düzenleme ya da tam (silme dahil).",
                    "Choose the access level: view, run, edit or full (including delete)."));
        }
        return lv;
    }

    /** {@code expires_on} (İstanbul günü, o günün SONUNA kadar) → UTC ISO; boş → null (süresiz); geçmiş → 400. */
    public String expiresAt(String expiresOn) {
        if (expiresOn == null || expiresOn.isBlank()) return null;
        LocalDate d;
        try {
            d = LocalDate.parse(expiresOn.trim());
        } catch (DateTimeParseException e) {
            throw field("expires_on", Msg.t("Bitiş tarihi yyyy-AA-GG biçiminde olmalı.", "The end date must be yyyy-MM-dd."));
        }
        if (d.getYear() > MAX_EXPIRY_YEAR) {
            throw field("expires_on", Msg.t("Bitiş tarihi en geç " + MAX_EXPIRY_YEAR + "-12-31 olabilir; süresiz paylaşım için boş bırakın.",
                    "The end date can be " + MAX_EXPIRY_YEAR + "-12-31 at the latest; leave it empty for a share with no end date."));
        }
        Instant end = d.plusDays(1).atStartOfDay(IST).toInstant();
        if (!end.isAfter(Instant.ofEpochMilli(clock.getAsLong()))) {
            throw field("expires_on", Msg.t("Bitiş tarihi geçmişte — bugün ya da sonrası bir tarih seçin.",
                    "The end date is in the past — choose today or a later date."));
        }
        return ISO.format(end);
    }

    /** Not — kırpılır, boş → null, en çok {@value #NOTE_MAX} karakter. */
    public String note(String note) {
        String n = note == null ? null : note.trim();
        if (n != null && n.isEmpty()) n = null;
        if (n != null && n.length() > NOTE_MAX) {
            throw field("note", Msg.t("Not en çok " + NOTE_MAX + " karakter olabilir.", "The note can be at most " + NOTE_MAX + " characters."));
        }
        return n;
    }

    /** Yeni paylaşım. Aynı alıcı zaten varsa 409 {@code GRANT_EXISTS}; takım başına tavan 409 {@code GRANT_LIMIT}. */
    public TeamAccessGrant create(Input in, String actor) {
        String key = TeamAccessGrant.keyOf(in.granteeType(), in.granteeTeamId(), in.granteeUserId());
        if (repo.findByOwnerTeamIdAndGranteeKey(in.ownerTeamId(), key).isPresent()) {
            throw new GlobalExceptionHandler.CodedConflictException("GRANT_EXISTS", Msg.t(
                    "Bu takım ya da kişi zaten erişime sahip — mevcut paylaşımı düzenleyin.",
                    "This team or person already has access — edit the existing share instead."));
        }
        if (repo.countLiveByOwnerTeamId(in.ownerTeamId(), ISO.format(Instant.ofEpochMilli(clock.getAsLong()))) >= MAX_PER_OWNER) {
            throw new GlobalExceptionHandler.CodedConflictException("GRANT_LIMIT", Msg.t(
                    "Bir takım en çok " + MAX_PER_OWNER + " paylaşım yapabilir — kullanılmayanları kaldırın.",
                    "A team can have at most " + MAX_PER_OWNER + " shares — remove the ones you no longer need."));
        }
        String now = ISO.format(Instant.ofEpochMilli(clock.getAsLong()));
        TeamAccessGrant g = new TeamAccessGrant();
        g.setOwnerTeamId(in.ownerTeamId());
        g.setGranteeType(in.granteeType());
        g.setGranteeTeamId(in.granteeTeamId());
        g.setGranteeUserId(in.granteeUserId());
        g.setGranteeKey(key);
        g.setLevel(in.level().name());
        g.setExpiresAt(in.expiresAt());
        g.setNote(in.note());
        g.setCreatedAt(now);
        g.setCreatedBy(actor);
        g.setUpdatedAt(now);
        g.setUpdatedBy(actor);
        TeamAccessGrant saved;
        try {
            saved = repo.saveAndFlush(g);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Aynı alıcıya eşzamanlı ikinci ekleme (benzersiz kısıt) — genel DUPLICATE_RECORD değil, aynı açık kod
            throw new GlobalExceptionHandler.CodedConflictException("GRANT_EXISTS", Msg.t(
                    "Bu takım ya da kişi zaten erişime sahip — mevcut paylaşımı düzenleyin.",
                    "This team or person already has access — edit the existing share instead."));
        }
        invalidate();
        return saved;
    }

    /** Düzey / bitiş / not güncellemesi — sahip ve alıcı DEĞİŞMEZ (değiştirmek = sil + yeni). */
    public TeamAccessGrant update(TeamAccessGrant g, TeamAccessLevel level, String expiresAt, String note, String actor) {
        // Süresi dolmuş paylaşımı YENİDEN CANLANDIRMAK yeni paylaşım gibidir (inceleme 5): takım başına tavan burada da
        String nowIso = ISO.format(Instant.ofEpochMilli(clock.getAsLong()));
        boolean wasExpired = g.getExpiresAt() != null && g.getExpiresAt().compareTo(nowIso) <= 0;
        boolean becomesLive = expiresAt == null || expiresAt.compareTo(nowIso) > 0;
        if (wasExpired && becomesLive && repo.countLiveByOwnerTeamId(g.getOwnerTeamId(), nowIso) >= MAX_PER_OWNER) {
            throw new GlobalExceptionHandler.CodedConflictException("GRANT_LIMIT", Msg.t(
                    "Bir takım en çok " + MAX_PER_OWNER + " paylaşım yapabilir — süresi dolmuş paylaşımı canlandırmadan önce kullanılmayanları kaldırın.",
                    "A team can have at most " + MAX_PER_OWNER + " shares — remove the ones you no longer need before reviving an expired share."));
        }
        g.setLevel(level.name());
        g.setExpiresAt(expiresAt);
        g.setNote(note);
        g.setUpdatedAt(ISO.format(Instant.ofEpochMilli(clock.getAsLong())));
        g.setUpdatedBy(actor);
        TeamAccessGrant saved = repo.saveAndFlush(g);
        invalidate();
        return saved;
    }

    public void delete(TeamAccessGrant g) {
        repo.delete(g);
        invalidate();
    }

    /** Takım silinince (sahip ya da alıcı). */
    public int deleteForTeam(Long teamId) {
        if (teamId == null) return 0;
        int n = repo.deleteByTeam(teamId);
        if (n > 0) invalidate();
        return n;
    }

    /** Kullanıcı silinince (o kişiye verilen paylaşımlar). */
    public int deleteForUser(Long userId) {
        if (userId == null) return 0;
        int n = repo.deleteByGranteeUser(userId);
        if (n > 0) invalidate();
        return n;
    }

    /** Süresi dolmuş mu (görünüm için). */
    public boolean expired(TeamAccessGrant g) {
        return g.getExpiresAt() != null
                && g.getExpiresAt().compareTo(ISO.format(Instant.ofEpochMilli(clock.getAsLong()))) <= 0;
    }

    /** {@code expires_at} → İstanbul günü (yyyy-MM-dd; bitişin bir önceki günü — "o günün sonuna kadar"). */
    public static String expiresOn(String expiresAt) {
        if (expiresAt == null) return null;
        try {
            Instant end = java.time.LocalDateTime.parse(expiresAt).toInstant(ZoneOffset.UTC);
            return end.atZone(IST).toLocalDate().minusDays(1).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static GlobalExceptionHandler.FieldValidationException field(String name, String msg) {
        return new GlobalExceptionHandler.FieldValidationException(name, msg);
    }

    /** Görünüm satırı (alıcı adları çağırandan). */
    public Map<String, Object> row(TeamAccessGrant g, String ownerName, String granteeName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("owner_team_id", g.getOwnerTeamId());
        m.put("owner_team_name", ownerName);
        m.put("grantee_type", g.getGranteeType());
        m.put("grantee_team_id", g.getGranteeTeamId());
        m.put("grantee_user_id", g.getGranteeUserId());
        m.put("grantee_name", granteeName);
        m.put("level", g.getLevel());
        m.put("expires_at", g.getExpiresAt());
        m.put("expires_on", expiresOn(g.getExpiresAt()));
        m.put("expired", expired(g));
        m.put("note", g.getNote());
        m.put("created_at", g.getCreatedAt());
        m.put("created_by", g.getCreatedBy());
        m.put("updated_at", g.getUpdatedAt());
        m.put("updated_by", g.getUpdatedBy());
        return m;
    }
}
