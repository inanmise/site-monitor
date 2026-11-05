package com.sitemonitor.service.forward;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.AlertForward;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertForwardRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.InactiveRecipientGuard;
import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.service.NotificationGroupService;
import com.sitemonitor.service.cache.CacheBus;
import com.sitemonitor.service.cache.CacheBusNames;
import com.sitemonitor.service.noc.NocMonitorDirectory;
import com.sitemonitor.service.noc.NocType;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Alarm iletimi (2026-11-05, kullanıcı isteği) — kayıt, doğrulama ve gönderim yollarının BELLEKTEN okuduğu yönlendirme.
 *
 * <p><b>Anlık görüntü.</b> Tüm {@code alert_forwards} satırları + çözümlenmiş alıcılar: TEAM türünde alıcı takımın alarm adresi
 * (varsayılan bildirim grubu, yoksa {@code Team.email} — virgül / noktalı virgülle bölünmüş, teslim edilebilir), MEMBERS türünde
 * seçilen kullanıcıların e-postası (yalnız AKTİF ve alıcı takımın üyesi olanlar — birincil ya da ek üyelik); pasif kişiye ait
 * adresler {@link InactiveRecipientGuard} ile düşer. Cert kapsam öğelerinin alan adı da tutulur (alarm eşleşmesi alan adıyla).
 * TTL {@code site.monitor.alert-forward.cache-ms} (60 sn, önyükleme anahtarı); okuma hatası ÖNCEKİ görüntüyü korur.
 *
 * <p><b>Bellek okuması.</b> {@link #anyFor} ve {@link #targetsFor} veritabanına GİTMEZ: görüntü bayatsa arka planda (tek
 * daemon iş parçacığı {@code alert-forward-refresh}) tazelenir, o çağrı mevcut görüntüyle yanıtlanır. Görüntü açılışta
 * ({@code ApplicationReadyEvent}) ve her TTL'de bir kurulur; her yazmada (oluştur / değiştir / sil / takım taşıma-silme /
 * kullanıcı silme) yerel bayat + önbellek veri yolu ({@link CacheBusNames#ALERT_FORWARDS}) — öteki pod'lar bir yoklamada
 * (~2 sn) yeniden kurar. Takım üyeliği / takım e-postası / bildirim grubu değişikliği TTL ile yansır (kabul).
 *
 * <p><b>Yönetim.</b> Yalnız sahip takımı YÖNETEN ekler / değiştirir (denetleyici {@code SessionScope.canManage}); alıcı takım
 * etkin ve sahip takımdan farklı, MEMBERS 1–{@value #MAX_MEMBERS} aktif üye, TYPES 1–11 bilinen tür, MONITORS
 * 1–{@value #MAX_MONITORS} sahip takımın izlemesi, takım başına en çok {@value #MAX_PER_TEAM} iletim. Düzenlemede DEĞİŞMEYEN
 * değer yeniden doğrulanmaz (silinmiş bir izlemenin öğesi, pasifleşmiş üye kalabilir).
 */
@Slf4j
@Service
public class AlertForwardService implements AlertForwardRouting {

    public static final int MAX_PER_TEAM = 100;
    public static final int MAX_MEMBERS = 50;
    public static final int MAX_MONITORS = 200;
    public static final int NOTE_MAX = 500;
    /** Bitiş günü üst sınırı — erişim paylaşımıyla aynı (kolon UTC ISO, 19 karakter). */
    public static final int MAX_EXPIRY_YEAR = 2099;
    /** Tür anahtarları ({@code MonitorTypeCatalog.ORDER}); "cert" = envanter kaydı (sertifika + erişilebilirlik alarmları). */
    public static final List<String> TYPE_KEYS = MonitorTypeCatalog.ORDER;
    /** IN sorgularının dilim boyutu. */
    static final int CHUNK = 500;
    /** Tek CSV'den okunan en çok öğe (bozuk / elle yazılmış satır sınırsız döngü üretmesin). */
    static final int CSV_MAX_ITEMS = 1000;
    /** İstek gövdesindeki metin alanlarının üst sınırı (düzenli ifade / ayrıştırma öncesi). */
    static final int RAW_TEXT_MAX = 4000;

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final AlertForwardRepository repo;
    private final TeamRepository teamRepo;

    @Autowired(required = false)
    private NotificationGroupService groups;
    @Autowired(required = false)
    private InactiveRecipientGuard inactiveGuard;
    @Autowired(required = false)
    private NocMonitorDirectory directory;

    @Value("${site.monitor.alert-forward.cache-ms:60000}")
    long cacheMs = 60_000;

    /** Saat kaynağı — testte ilerletilir. */
    LongSupplier clock = System::currentTimeMillis;

    public AlertForwardService(AlertForwardRepository repo, TeamRepository teamRepo) {
        this.repo = repo;
        this.teamRepo = teamRepo;
    }

    void setGroups(NotificationGroupService g) { this.groups = g; }
    void setInactiveGuard(InactiveRecipientGuard g) { this.inactiveGuard = g; }
    void setDirectory(NocMonitorDirectory d) { this.directory = d; }

    // ── Tür eşlemesi ─────────────────────────────────────────────────────────

    /** Alarm tipi → tür anahtarı ({@code NocType.forAlertType}; SSL → "cert"). Bilinmeyen → null (yalnız ALL kapsamı tutar). */
    public static String typeKeyOfAlert(String alertType) {
        NocType t = NocType.forAlertType(alertType);
        return keyOf(t);
    }

    /** NocType → tür anahtarı. */
    public static String keyOf(NocType t) {
        if (t == null) return null;
        return t == NocType.SSL ? "cert" : t.name().toLowerCase(Locale.ROOT);
    }

    /** Tür anahtarı → NocType ("cert" → SSL); bilinmeyen → null. */
    public static NocType nocTypeOf(String key) {
        if (key == null) return null;
        String k = key.trim().toLowerCase(Locale.ROOT);
        if (!TYPE_KEYS.contains(k)) return null;
        return "cert".equals(k) ? NocType.SSL : NocType.parse(k);
    }

    // ── Anlık görüntü ────────────────────────────────────────────────────────

    /** Görüntüdeki tek iletim (çözümlenmiş alıcılarla). {@code monitorKeys}: "tür:id", cert için "cert:alanadı". */
    record Fwd(long id, long ownerTeamId, String scopeKind, Set<String> types, Set<String> monitorKeys,
               boolean wholeTeam, long recipientTeamId, boolean recipientActive, List<Long> memberIds,
               List<String> emails, boolean enabled, String expiresAt) { }

    /** Değişmez görüntü. {@code teamNames}: sahip + alıcı takım adları. */
    record Snapshot(List<Fwd> all, Map<Long, List<Fwd>> byOwner, Map<Long, Fwd> byId, Map<Long, String> teamNames,
                    long builtAt) {
        static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), Map.of(), Map.of(), Long.MIN_VALUE / 2);

        Snapshot stale() {
            return new Snapshot(all, byOwner, byId, teamNames, Long.MIN_VALUE / 2);
        }
    }

    /** Görüntü referansı — yapım yalnız başladığı andaki referans hâlâ yerindeyse saklanır (erişim paylaşımı deseni). */
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    /** Kayıt sürümü: her boşaltma artırır; yapım sırasında değiştiyse o yapım saklanmaz. */
    private final AtomicLong generation = new AtomicLong();
    /** Arka plan tazeleyici (açılışta kurulur; elle kurulan testlerde yok — bellek okuması o zaman tazeleme TETİKLEMEZ). */
    private volatile ScheduledExecutorService refresher;
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private volatile boolean closing;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (closing || refresher != null) return;
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "alert-forward-refresh");
            t.setDaemon(true);
            return t;
        });
        ex.setRemoveOnCancelPolicy(true);
        ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        refresher = ex;
        long period = Math.max(1_000L, cacheMs);
        try {
            ex.execute(this::refreshQuietly);   // ilk görüntü hemen (alarm yolları bekletilmez)
            ex.scheduleWithFixedDelay(this::refreshQuietly, period, period, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.warn("Alarm iletimi tazeleyicisi başlatılamadı: {}", e.toString());
        }
    }

    /** Kapanış: bayrak + tazeleyici durur (bekleme yok — paylaşılan kapanış bütçesine dokunmaz). */
    @EventListener(ContextClosedEvent.class)
    public void onClosed() {
        closing = true;
        ScheduledExecutorService ex = refresher;
        if (ex != null) ex.shutdownNow();
    }

    private void refreshQuietly() {
        refreshQueued.set(false);
        if (closing) return;
        try {
            current();
        } catch (RuntimeException e) {
            log.debug("Alarm iletimi görüntüsü tazelenemedi: {}", e.toString());
        }
    }

    /** Bellek okuması bayat görüntü gördü → arka planda tek tazeleme (birleştirilir; DB bu iş parçacığında okunmaz). */
    private void kickRefresh() {
        ScheduledExecutorService ex = refresher;
        if (ex == null || closing || !refreshQueued.compareAndSet(false, true)) return;
        try {
            ex.execute(this::refreshQuietly);
        } catch (RuntimeException e) {
            refreshQueued.set(false);
        }
    }

    /** Gönderim yolu okuması — veritabanına GİTMEZ. Hiç kurulmamışsa boş (iletim yok) + arka planda kurulum. */
    private Snapshot memory() {
        Snapshot s = snapshot.get();
        if (s == null || clock.getAsLong() - s.builtAt() >= cacheMs) kickRefresh();
        return s == null ? Snapshot.EMPTY : s;
    }

    /** Yönetim yolu okuması — bayatsa bu iş parçacığında yeniden kurar; kurulamazsa önceki (yoksa null = okunamadı). */
    Snapshot current() {
        Snapshot s = snapshot.get();
        long now = clock.getAsLong();
        if (s != null && now - s.builtAt() < cacheMs) return s;
        return rebuild(now);
    }

    private synchronized Snapshot rebuild(long now) {
        Snapshot prev = snapshot.get();
        if (prev != null && now - prev.builtAt() < cacheMs) return prev;
        long gen = generation.get();
        Snapshot built;
        try {
            built = build(now);
        } catch (Exception e) {
            log.warn("Alarm iletimleri tazelenemedi ({}): önceki görüntü kullanılıyor", e.toString());
            if (prev == null) return null;
            // 5 sn sonra yeniden denensin (yazmalar zaten bayat işaretler)
            Snapshot keep = new Snapshot(prev.all(), prev.byOwner(), prev.byId(), prev.teamNames(),
                    now - Math.max(0L, cacheMs - 5_000L));
            if (generation.get() == gen) snapshot.compareAndSet(prev, keep);
            return keep;
        }
        // Yapım sırasında kayıt değiştiyse saklanmaz (bu çağrı sonucu kullanır; sonraki okuma yeniden kurar)
        if (generation.get() == gen) snapshot.compareAndSet(prev, built);
        return built;
    }

    /** Bu podun görüntüsünü bayat işaretler (öteki pod'un yayını da bunu çağırır — yeniden yayın YOK; ucuz, DB okumaz). */
    void invalidateLocal() {
        generation.incrementAndGet();
        // SİLİNMEZ, bayat işaretlenir: yeniden kurulum hata verirse önceki görüntü kullanılır
        snapshot.getAndUpdate(s -> s == null ? null : s.stale());
        kickRefresh();
    }

    /** Bu pod + öteki pod'lar (önbellek veri yolu). */
    public void invalidate() {
        invalidateLocal();
        CacheBus b = cacheBus;
        if (b != null) b.bump(CacheBusNames.ALERT_FORWARDS);
    }

    /**
     * Yazma sonrası: bu pod hemen + işlem bitince yeniden (işlem içinde başlayan bir kurulum taahhüt öncesi veriyi okumuş
     * olabilir) + öteki pod'lar (veri yolu işlem bitince yayınlar).
     */
    private void changed() {
        invalidateLocal();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    invalidateLocal();
                }
            });
        }
        CacheBus b = cacheBus;
        if (b != null) b.bump(CacheBusNames.ALERT_FORWARDS);
    }

    private volatile CacheBus cacheBus;

    @Autowired(required = false)
    void setCacheBus(CacheBus bus) {
        this.cacheBus = bus;
        if (bus != null) bus.register(CacheBusNames.ALERT_FORWARDS, this::invalidateLocal);
    }

    /** Üye satırı (fotoğrafsız projeksiyon). {@code teams} = birincil + ek üyelikler. */
    public record Member(long id, String email, boolean active, Set<Long> teams, String displayName, String username) {
        public boolean inTeam(Long teamId) {
            return teamId != null && teams.contains(teamId);
        }
        public String name() {
            return displayName != null && !displayName.isBlank() ? displayName : username;
        }
    }

    /** Kullanıcı kimlikleri → üye satırları (dilimli tek sorgu). Bulunamayan kimlik haritada yok. */
    public Map<Long, Member> members(Collection<Long> ids) {
        Map<Long, Member> out = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        List<Long> all = new ArrayList<>(new LinkedHashSet<>(ids));
        Map<Long, Set<Long>> teams = new HashMap<>();
        Map<Long, Object[]> first = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i += CHUNK) {
            for (Object[] r : repo.findMemberRows(all.subList(i, Math.min(all.size(), i + CHUNK)))) {
                if (r == null || r.length < 7 || !(r[0] instanceof Number n)) continue;
                long id = n.longValue();
                first.putIfAbsent(id, r);
                Set<Long> ts = teams.computeIfAbsent(id, k -> new LinkedHashSet<>());
                if (r[3] instanceof Number p) ts.add(p.longValue());
                if (r[4] instanceof Number m) ts.add(m.longValue());
            }
        }
        for (Map.Entry<Long, Object[]> e : first.entrySet()) {
            Object[] r = e.getValue();
            out.put(e.getKey(), new Member(e.getKey(), r[1] == null ? null : r[1].toString(), !Boolean.FALSE.equals(r[2]),
                    Set.copyOf(teams.getOrDefault(e.getKey(), Set.of())),
                    r[5] == null ? null : r[5].toString(), r[6] == null ? null : r[6].toString()));
        }
        return out;
    }

    Snapshot build(long now) {
        List<AlertForward> rows = repo.findAllByOrderByIdAsc();
        if (rows.isEmpty()) return new Snapshot(List.of(), Map.of(), Map.of(), Map.of(), now);
        Set<Long> teamIds = new LinkedHashSet<>();
        Set<Long> userIds = new LinkedHashSet<>();
        Set<Long> invIds = new LinkedHashSet<>();
        for (AlertForward f : rows) {
            if (f.getOwnerTeamId() != null) teamIds.add(f.getOwnerTeamId());
            if (f.getRecipientTeamId() != null) teamIds.add(f.getRecipientTeamId());
            if (AlertForward.RECIPIENT_MEMBERS.equals(f.getRecipientKind())) userIds.addAll(parseIds(f.getRecipientUserIds()));
            if (AlertForward.SCOPE_MONITORS.equals(f.getScopeKind())) {
                for (MonitorRef m : parseMonitors(f.getScopeMonitors())) if ("cert".equals(m.type())) invIds.add(m.id());
            }
        }
        Map<Long, Team> teams = new HashMap<>();
        for (Team t : teamRepo.findAllById(teamIds)) if (t != null && t.getId() != null) teams.put(t.getId(), t);
        Map<Long, Member> mem = members(userIds);
        Map<Long, String> invDomains = new HashMap<>();
        if (!invIds.isEmpty()) {
            List<Long> ids = new ArrayList<>(invIds);
            for (int i = 0; i < ids.size(); i += CHUNK) {
                for (Object[] r : repo.findInventoryDomains(ids.subList(i, Math.min(ids.size(), i + CHUNK)))) {
                    if (r != null && r.length >= 2 && r[0] instanceof Number n && r[1] != null) {
                        invDomains.put(n.longValue(), r[1].toString().trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        Map<Long, List<String>> teamEmails = new HashMap<>();
        List<Fwd> all = new ArrayList<>();
        for (AlertForward f : rows) {
            if (f.getId() == null || f.getOwnerTeamId() == null || f.getRecipientTeamId() == null) continue;
            long rt = f.getRecipientTeamId();
            Team recipient = teams.get(rt);
            boolean recipientActive = activeTeam(recipient);
            boolean whole = !AlertForward.RECIPIENT_MEMBERS.equals(f.getRecipientKind());
            List<Long> memberIds = new ArrayList<>();
            List<String> emails;
            if (whole) {
                emails = recipientActive ? teamEmails.computeIfAbsent(rt, k -> teamAlarmEmails(recipient)) : List.of();
            } else {
                List<String> e = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (Long uid : parseIds(f.getRecipientUserIds())) {
                    Member m = mem.get(uid);
                    if (m == null || !m.active() || !m.inTeam(rt)) continue;
                    memberIds.add(uid);
                    String addr = normEmail(m.email());
                    if (addr != null && deliverable(addr) && !inactiveOnly(addr) && seen.add(addr.toLowerCase(Locale.ROOT))) e.add(addr);
                }
                emails = e;
            }
            Set<String> types = new LinkedHashSet<>();
            Set<String> keys = new LinkedHashSet<>();
            if (AlertForward.SCOPE_TYPES.equals(f.getScopeKind())) types.addAll(parseTypes(f.getScopeTypes()));
            if (AlertForward.SCOPE_MONITORS.equals(f.getScopeKind())) {
                for (MonitorRef m : parseMonitors(f.getScopeMonitors())) {
                    if ("cert".equals(m.type())) {
                        String d = invDomains.get(m.id());
                        if (d != null && !d.isBlank()) keys.add("cert:" + d);
                    } else {
                        keys.add(m.type() + ":" + m.id());
                    }
                }
            }
            all.add(new Fwd(f.getId(), f.getOwnerTeamId(), nz(f.getScopeKind()), Set.copyOf(types), Set.copyOf(keys), whole, rt,
                    recipientActive, List.copyOf(memberIds), List.copyOf(emails), f.isEnabled(), f.getExpiresAt()));
        }
        Map<Long, List<Fwd>> byOwner = new HashMap<>();
        Map<Long, Fwd> byId = new HashMap<>();
        for (Fwd f : all) {
            byOwner.computeIfAbsent(f.ownerTeamId(), k -> new ArrayList<>()).add(f);
            byId.put(f.id(), f);
        }
        Map<Long, List<Fwd>> frozen = new HashMap<>();
        byOwner.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        Map<Long, String> names = new HashMap<>();
        teams.forEach((id, t) -> { if (t.getName() != null) names.put(id, t.getName()); });
        return new Snapshot(List.copyOf(all), Map.copyOf(frozen), Map.copyOf(byId), Map.copyOf(names), now);
    }

    // ── Yönlendirme (yalnız bellek) ──────────────────────────────────────────

    private static boolean live(Fwd f, String nowIso) {
        return f.enabled() && f.recipientActive() && (f.expiresAt() == null || f.expiresAt().compareTo(nowIso) > 0);
    }

    private String nowIso() {
        return ISO.format(Instant.ofEpochMilli(clock.getAsLong()));
    }

    @Override
    public boolean anyFor(Collection<Long> ownerTeamIds) {
        if (ownerTeamIds == null || ownerTeamIds.isEmpty()) return false;
        Snapshot s = memory();
        if (s.all().isEmpty()) return false;
        String nowIso = nowIso();
        for (Long owner : ownerTeamIds) {
            if (owner == null) continue;
            for (Fwd f : s.byOwner().getOrDefault(owner, List.of())) {
                if (live(f, nowIso) && !ownerTeamIds.contains(f.recipientTeamId())) return true;
            }
        }
        return false;
    }

    @Override
    public List<ForwardTarget> targetsFor(AlertRef ref) {
        if (ref == null || ref.ownerTeamIds() == null || ref.ownerTeamIds().isEmpty()) return List.of();
        Snapshot s = memory();
        if (s.all().isEmpty()) return List.of();
        Set<Long> owners = new LinkedHashSet<>();
        for (Long o : ref.ownerTeamIds()) if (o != null) owners.add(o);
        if (owners.isEmpty()) return List.of();
        String type = typeKeyOfAlert(ref.alertType());
        String key = monitorKey(type, ref.domain(), ref.monitorId());
        String nowIso = nowIso();
        TreeMap<Long, Acc> acc = new TreeMap<>();
        for (Long owner : owners) {
            for (Fwd f : s.byOwner().getOrDefault(owner, List.of())) {
                if (!live(f, nowIso) || owners.contains(f.recipientTeamId())) continue;
                if (!matches(f, type, key)) continue;
                acc.computeIfAbsent(f.recipientTeamId(), k -> new Acc()).add(f, s.teamNames());
            }
        }
        List<ForwardTarget> out = new ArrayList<>();
        for (Map.Entry<Long, Acc> e : acc.entrySet()) {
            ForwardTarget t = e.getValue().toTarget(e.getKey(), s.teamNames().get(e.getKey()));
            if (t.emails().isEmpty() && t.memberUserIds().isEmpty() && !t.wholeTeam()) continue;
            out.add(t);
        }
        return out;
    }

    /** Alarmın kapsam anahtarı: cert → "cert:alanadı"; diğerleri bağlamdaki izleme kimliğiyle "tür:id"; yoksa null. */
    static String monitorKey(String type, String domain, Long monitorId) {
        if (type == null) return null;
        if ("cert".equals(type)) {
            if (domain == null || domain.isBlank()) return null;
            return "cert:" + domain.trim().toLowerCase(Locale.ROOT);
        }
        return monitorId == null ? null : type + ":" + monitorId;
    }

    /** Kapsam eşleşmesi: ALL her alarm; TYPES tür kümede (bilinmeyen tür eşleşmez); MONITORS (tür, kimlik) kümede. */
    static boolean matches(Fwd f, String type, String key) {
        return switch (f.scopeKind()) {
            case AlertForward.SCOPE_ALL -> true;
            case AlertForward.SCOPE_TYPES -> type != null && f.types().contains(type);
            case AlertForward.SCOPE_MONITORS -> key != null && f.monitorKeys().contains(key);
            default -> false;
        };
    }

    /** Alıcı takım başına birleştirici: e-postalar / üyeler tekil, wholeTeam = herhangi bir TEAM iletimi. */
    private static final class Acc {
        final List<String> ownerNames = new ArrayList<>();
        final Set<Long> ownerSeen = new HashSet<>();
        final Set<Long> forwardIds = new java.util.TreeSet<>();
        final List<String> emails = new ArrayList<>();
        final Set<String> emailSeen = new HashSet<>();
        final List<Long> members = new ArrayList<>();
        final Set<Long> memberSeen = new HashSet<>();
        boolean whole;

        void add(Fwd f, Map<Long, String> names) {
            if (ownerSeen.add(f.ownerTeamId())) {
                String n = names.get(f.ownerTeamId());
                ownerNames.add(n != null ? n : "Takım #" + f.ownerTeamId());
            }
            forwardIds.add(f.id());
            for (String e : f.emails()) if (emailSeen.add(e.toLowerCase(Locale.ROOT))) emails.add(e);
            for (Long m : f.memberIds()) if (memberSeen.add(m)) members.add(m);
            whole |= f.wholeTeam();
        }

        ForwardTarget toTarget(long recipientTeamId, String name) {
            return new ForwardTarget(recipientTeamId, name != null ? name : "Takım #" + recipientTeamId, ownerNames,
                    new ArrayList<>(forwardIds), emails, members, whole);
        }
    }

    // ── Yönetim yardımcıları (denetleyici) ───────────────────────────────────

    /** İletimin alarm gönderdiği adres sayısı (görüntüden); görüntü okunamadıysa ya da iletim görüntüde yoksa null. */
    public Integer addressCount(Long forwardId) {
        if (forwardId == null) return null;
        Snapshot s;
        try {
            s = current();
        } catch (RuntimeException e) {
            return null;
        }
        if (s == null) return null;
        Fwd f = s.byId().get(forwardId);
        return f == null ? null : f.emails().size();
    }

    /** Takımın alarm adresleri — TEAM türü iletimin gönderdiği liste (bilgi postası da bunu kullanır). Pasif adresler düşer. */
    public List<String> teamAlarmEmails(Long teamId) {
        if (teamId == null) return List.of();
        Team t = teamRepo.findById(teamId).orElse(null);
        return activeTeam(t) ? teamAlarmEmails(t) : List.of();
    }

    private List<String> teamAlarmEmails(Team t) {
        if (t == null || t.getId() == null) return List.of();
        List<String> raw;
        NotificationGroupService.Override ov = groups == null ? null : groups.overrideFor(t.getId(), null);
        raw = ov != null && ov.applies() ? ov.emails() : splitEmails(t.getEmail());
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String r : raw) {
            String e = normEmail(r);
            if (e == null || !deliverable(e) || inactiveOnly(e)) continue;
            if (seen.add(e.toLowerCase(Locale.ROOT))) out.add(e);
        }
        return out;
    }

    /** Seçilen üyelerin (aktif, alıcı takımda) teslim edilebilir e-postaları — bilgi postası için. */
    public List<String> memberEmails(Long recipientTeamId, Collection<Long> userIds) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Member m : members(userIds).values()) {
            if (!m.active() || !m.inTeam(recipientTeamId)) continue;
            String e = normEmail(m.email());
            if (e == null || !deliverable(e) || inactiveOnly(e)) continue;
            if (seen.add(e.toLowerCase(Locale.ROOT))) out.add(e);
        }
        return out;
    }

    private boolean inactiveOnly(String email) {
        InactiveRecipientGuard g = inactiveGuard;
        try {
            return g != null && g.isInactiveOnlyEmail(email);
        } catch (RuntimeException e) {
            return false;   // süzgeç okunamadı: gönderim hunisi zaten süzer (doSend)
        }
    }

    /** Virgül / noktalı virgül / boşlukla ayrılmış adresler (sınırlı, düzenli ifade yok). */
    static List<String> splitEmails(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String s = raw.length() > RAW_TEXT_MAX ? raw.substring(0, RAW_TEXT_MAX) : raw;
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i <= s.length(); i++) {
            char c = i < s.length() ? s.charAt(i) : ',';
            if (c == ',' || c == ';' || Character.isWhitespace(c)) {
                if (cur.length() > 0) out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        return out;
    }

    static String normEmail(String e) {
        if (e == null) return null;
        String t = e.trim();
        return t.isEmpty() ? null : t;
    }

    /** Kasten gevşek teslim edilebilirlik: tek '@', boşluksuz, alan adında nokta, en az 2 karakterlik son ek. */
    static boolean deliverable(String e) {
        if (e == null || e.length() > 254) return false;
        int at = e.indexOf('@');
        if (at <= 0 || at != e.lastIndexOf('@') || at == e.length() - 1) return false;
        for (int i = 0; i < e.length(); i++) {
            char c = e.charAt(i);
            if (Character.isWhitespace(c) || c == ',' || c == ';') return false;
        }
        String domain = e.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        return dot > 0 && domain.length() - dot - 1 >= 2;
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

    /** İzleme referansı — {@code type} tür anahtarı, {@code id} izleme kimliği (cert: envanter kimliği). */
    public record MonitorRef(String type, long id) {
        public String key() {
            return type + ":" + id;
        }
    }

    static List<Long> parseIds(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Long> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int n = 0;
        for (String part : csv.split(",", CSV_MAX_ITEMS + 1)) {
            if (++n > CSV_MAX_ITEMS) break;
            String p = part.trim();
            if (p.isEmpty()) continue;
            try {
                long v = Long.parseLong(p);
                if (v > 0 && seen.add(v)) out.add(v);
            } catch (NumberFormatException ignored) {
                // bozuk öğe atlanır
            }
        }
        return out;
    }

    static List<String> parseTypes(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        Set<String> got = new HashSet<>();
        int n = 0;
        for (String part : csv.split(",", CSV_MAX_ITEMS + 1)) {
            if (++n > CSV_MAX_ITEMS) break;
            String k = part.trim().toLowerCase(Locale.ROOT);
            if (TYPE_KEYS.contains(k)) got.add(k);
        }
        List<String> out = new ArrayList<>();
        for (String k : TYPE_KEYS) if (got.contains(k)) out.add(k);
        return out;
    }

    static List<MonitorRef> parseMonitors(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<MonitorRef> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int n = 0;
        for (String part : csv.split(",", CSV_MAX_ITEMS + 1)) {
            if (++n > CSV_MAX_ITEMS) break;
            String p = part.trim();
            int c = p.indexOf(':');
            if (c <= 0) continue;
            String type = p.substring(0, c).trim().toLowerCase(Locale.ROOT);
            if (!TYPE_KEYS.contains(type)) continue;
            try {
                long id = Long.parseLong(p.substring(c + 1).trim());
                if (id > 0 && seen.add(type + ":" + id)) out.add(new MonitorRef(type, id));
            } catch (NumberFormatException ignored) {
                // bozuk öğe atlanır
            }
        }
        return out;
    }

    static String typesCsv(Collection<String> types) {
        if (types == null || types.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (String k : TYPE_KEYS) if (types.contains(k)) out.add(k);
        return String.join(",", out);
    }

    static String monitorsCsv(Collection<MonitorRef> refs) {
        if (refs == null || refs.isEmpty()) return null;
        List<MonitorRef> sorted = new ArrayList<>(refs);
        sorted.sort((a, b) -> {
            int c = Integer.compare(TYPE_KEYS.indexOf(a.type()), TYPE_KEYS.indexOf(b.type()));
            return c != 0 ? c : Long.compare(a.id(), b.id());
        });
        List<String> out = new ArrayList<>();
        for (MonitorRef r : sorted) out.add(r.key());
        return String.join(",", out);
    }

    static String idsCsv(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return null;
        List<Long> sorted = new ArrayList<>(new LinkedHashSet<>(ids));
        Collections.sort(sorted);
        List<String> out = new ArrayList<>();
        for (Long id : sorted) out.add(String.valueOf(id));
        return String.join(",", out);
    }

    /** Kayıtlı satırın listeleri (görünüm). */
    public static List<String> typesOf(AlertForward f) { return parseTypes(f.getScopeTypes()); }
    public static List<MonitorRef> monitorsOf(AlertForward f) { return parseMonitors(f.getScopeMonitors()); }
    public static List<Long> userIdsOf(AlertForward f) { return parseIds(f.getRecipientUserIds()); }

    // ── Yönetim: doğrulama + kayıt ───────────────────────────────────────────

    /**
     * Kayıt sonucu. {@code before} = düzenlemede ESKİ hâlin kopyası (yeni kayıtta null); {@code changed} false ise HİÇBİR
     * şey yazılmadı (değişmeyen gövde). {@code newWholeTeam}: TEAM türünde yeni alıcı takım (bilgi postası takıma);
     * {@code newMemberIds}: MEMBERS türünde yeni eklenen üyeler.
     */
    public record Saved(AlertForward forward, AlertForward before, boolean changed, boolean newWholeTeam,
                        List<Long> newMemberIds) { }

    /**
     * Yeni iletim. {@code userIds} = istekteki {@code recipient_user_ids}'in çözümlenmiş iç kimlikleri (denetleyici opak
     * kimliği çözer; alan yoksa null). Sahip takımın satırı kilitlenir (tavan ve "zaten var" kesin).
     */
    @Transactional
    public Saved create(Map<String, Object> body, List<Long> userIds, String actor) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Long owner = longOf(b.get("owner_team_id"));
        Team ownerTeam = owner == null ? null : teamRepo.findById(owner).orElse(null);
        if (!activeTeam(ownerTeam)) {
            throw field("owner_team_id", Msg.t("İletimi ekleyen (sahip) takım bulunamadı ya da pasif — etkin bir takım seçin.",
                    "The owner team wasn't found or is passive — choose an active team."));
        }
        String scopeKind = scopeKind(b.get("scope_kind"));
        List<String> types = AlertForward.SCOPE_TYPES.equals(scopeKind) ? validateTypes(b.get("scope_types")) : List.of();
        List<MonitorRef> monitors = AlertForward.SCOPE_MONITORS.equals(scopeKind)
                ? validateMonitors(owner, b.get("scope_monitors"), Set.of()) : List.of();
        String recipientKind = recipientKind(b.get("recipient_kind"));
        Long rt = validateRecipientTeam(owner, b.get("recipient_team_id"));
        List<Long> members = AlertForward.RECIPIENT_MEMBERS.equals(recipientKind)
                ? validateMembers(rt, userIds, Set.of()) : List.of();
        String expiresAt = expiresAt(str(b.get("expires_on")));
        String note = note(b.get("note"));
        boolean enabled = !b.containsKey("enabled") || bool(b.get("enabled"));

        if (repo.lockTeamRow(owner).isEmpty()) {
            throw field("owner_team_id", Msg.t("İletimi ekleyen (sahip) takım bulunamadı — listeyi yenileyin.",
                    "The owner team wasn't found — refresh the list."));
        }
        if (repo.countByOwnerTeamId(owner) >= MAX_PER_TEAM) {
            throw new GlobalExceptionHandler.CodedConflictException("FORWARD_LIMIT", Msg.t(
                    "Bir takım en çok " + MAX_PER_TEAM + " alarm iletimi tanımlayabilir — kullanılmayanları kaldırın.",
                    "A team can have at most " + MAX_PER_TEAM + " alarm forwards — remove the ones you no longer need."));
        }
        AlertForward f = new AlertForward();
        f.setOwnerTeamId(owner);
        f.setScopeKind(scopeKind);
        f.setScopeTypes(AlertForward.SCOPE_TYPES.equals(scopeKind) ? typesCsv(types) : null);
        f.setScopeMonitors(AlertForward.SCOPE_MONITORS.equals(scopeKind) ? monitorsCsv(monitors) : null);
        f.setRecipientKind(recipientKind);
        f.setRecipientTeamId(rt);
        f.setRecipientUserIds(AlertForward.RECIPIENT_MEMBERS.equals(recipientKind) ? idsCsv(members) : null);
        f.setEnabled(enabled);
        f.setExpiresAt(expiresAt);
        f.setNote(note);
        requireUnique(f, null);
        String now = nowIso();
        f.setCreatedAt(now);
        f.setCreatedBy(actor);
        f.setUpdatedAt(now);
        f.setUpdatedBy(actor);
        AlertForward saved = repo.saveAndFlush(f);
        changed();
        boolean whole = AlertForward.RECIPIENT_TEAM.equals(recipientKind);
        return new Saved(saved, null, true, whole, whole ? List.of() : members);
    }

    /**
     * Düzenleme — yalnız gönderilen alanlar; DEĞİŞMEYEN değer yeniden doğrulanmaz. {@code owner_team_id} değiştirilemez
     * (400). {@code version} gönderildiyse kayıtlı sürümle eşleşmeli (409 CONCURRENT_MODIFICATION). Değişiklik yoksa hiçbir
     * şey yazılmaz ({@code changed=false}).
     */
    @Transactional
    public Saved update(Long id, Map<String, Object> body, List<Long> userIds, String actor) {
        Map<String, Object> b = body == null ? Map.of() : body;
        AlertForward f = repo.findById(id).orElseThrow(AlertForwardService::notFound);
        if (b.containsKey("version") && b.get("version") != null) {
            Long v = longOf(b.get("version"));
            if (v == null || !Objects.equals(v, f.getVersion())) throw concurrent();
        }
        if (b.containsKey("owner_team_id")) {
            Long o = longOf(b.get("owner_team_id"));
            if (!Objects.equals(o, f.getOwnerTeamId())) {
                throw field("owner_team_id", Msg.t("Sahip takım değiştirilemez — iletimi kaldırıp yeni takımda yeniden ekleyin.",
                        "The owner team can't be changed — remove the forward and add it again under the other team."));
            }
        }
        AlertForward before = copy(f);
        Long owner = f.getOwnerTeamId();

        // Kapsam
        String scopeKind = b.containsKey("scope_kind") ? scopeKind(b.get("scope_kind")) : f.getScopeKind();
        List<String> types = parseTypes(f.getScopeTypes());
        List<MonitorRef> monitors = parseMonitors(f.getScopeMonitors());
        if (AlertForward.SCOPE_TYPES.equals(scopeKind)) {
            if (b.containsKey("scope_types")) {
                List<String> sent = typeList(b.get("scope_types"));
                if (!new HashSet<>(sent).equals(new HashSet<>(types)) || !AlertForward.SCOPE_TYPES.equals(f.getScopeKind())) {
                    types = validateTypes(b.get("scope_types"));
                }
            } else if (!AlertForward.SCOPE_TYPES.equals(f.getScopeKind()) || types.isEmpty()) {
                types = validateTypes(null);   // türe geçildi ama tür gönderilmedi → 400
            }
        }
        if (AlertForward.SCOPE_MONITORS.equals(scopeKind)) {
            if (b.containsKey("scope_monitors")) {
                Set<String> kept = new HashSet<>();
                if (AlertForward.SCOPE_MONITORS.equals(f.getScopeKind())) for (MonitorRef m : monitors) kept.add(m.key());
                monitors = validateMonitors(owner, b.get("scope_monitors"), kept);
            } else if (!AlertForward.SCOPE_MONITORS.equals(f.getScopeKind()) || monitors.isEmpty()) {
                monitors = validateMonitors(owner, null, Set.of());
            }
        }

        // Alıcı
        String recipientKind = b.containsKey("recipient_kind") ? recipientKind(b.get("recipient_kind")) : f.getRecipientKind();
        Long rt = f.getRecipientTeamId();
        boolean teamChanged = false;
        if (b.containsKey("recipient_team_id")) {
            Long sent = longOf(b.get("recipient_team_id"));
            if (!Objects.equals(sent, rt)) {
                rt = validateRecipientTeam(owner, b.get("recipient_team_id"));
                teamChanged = true;
            }
        }
        List<Long> oldMembers = AlertForward.RECIPIENT_MEMBERS.equals(f.getRecipientKind()) ? parseIds(f.getRecipientUserIds()) : List.of();
        List<Long> members = oldMembers;
        if (AlertForward.RECIPIENT_MEMBERS.equals(recipientKind)) {
            if (userIds != null) {
                Set<Long> kept = teamChanged || !AlertForward.RECIPIENT_MEMBERS.equals(f.getRecipientKind())
                        ? Set.of() : new HashSet<>(oldMembers);
                members = validateMembers(rt, userIds, kept);
            } else if (teamChanged || !AlertForward.RECIPIENT_MEMBERS.equals(f.getRecipientKind()) || members.isEmpty()) {
                members = validateMembers(rt, null, Set.of());   // üye gönderilmedi → 400
            }
        } else {
            members = List.of();
        }

        String expiresAt = f.getExpiresAt();
        if (b.containsKey("expires_on")) {
            String sent = str(b.get("expires_on"));
            String cur = com.sitemonitor.service.access.TeamAccessGrantService.expiresOn(f.getExpiresAt());
            if (!Objects.equals(blankToNull(sent), cur)) expiresAt = expiresAt(sent);
        }
        String note = b.containsKey("note") ? note(b.get("note")) : f.getNote();
        boolean enabled = b.containsKey("enabled") ? bool(b.get("enabled")) : f.isEnabled();

        String newTypes = AlertForward.SCOPE_TYPES.equals(scopeKind) ? typesCsv(types) : null;
        String newMonitors = AlertForward.SCOPE_MONITORS.equals(scopeKind) ? monitorsCsv(monitors) : null;
        String newUsers = AlertForward.RECIPIENT_MEMBERS.equals(recipientKind) ? idsCsv(members) : null;
        boolean changed = !Objects.equals(scopeKind, f.getScopeKind()) || !Objects.equals(newTypes, f.getScopeTypes())
                || !Objects.equals(newMonitors, f.getScopeMonitors()) || !Objects.equals(recipientKind, f.getRecipientKind())
                || !Objects.equals(rt, f.getRecipientTeamId()) || !Objects.equals(newUsers, f.getRecipientUserIds())
                || enabled != f.isEnabled() || !Objects.equals(expiresAt, f.getExpiresAt()) || !Objects.equals(note, f.getNote());
        if (!changed) return new Saved(f, before, false, false, List.of());

        boolean scopeOrRecipientChanged = !Objects.equals(scopeKind, f.getScopeKind()) || !Objects.equals(newTypes, f.getScopeTypes())
                || !Objects.equals(newMonitors, f.getScopeMonitors()) || !Objects.equals(recipientKind, f.getRecipientKind())
                || !Objects.equals(rt, f.getRecipientTeamId()) || !Objects.equals(newUsers, f.getRecipientUserIds());
        f.setScopeKind(scopeKind);
        f.setScopeTypes(newTypes);
        f.setScopeMonitors(newMonitors);
        f.setRecipientKind(recipientKind);
        f.setRecipientTeamId(rt);
        f.setRecipientUserIds(newUsers);
        f.setEnabled(enabled);
        f.setExpiresAt(expiresAt);
        f.setNote(note);
        if (scopeOrRecipientChanged) {
            repo.lockTeamRow(owner);
            requireUnique(f, f.getId());
        }
        f.setUpdatedAt(nowIso());
        f.setUpdatedBy(actor);
        AlertForward saved = repo.saveAndFlush(f);
        changed();

        // Yeni alıcılar (bilgi postası / görüntüleme erişimi): takım değiştiyse hepsi; aksi yeni eklenen üyeler.
        boolean newWhole = AlertForward.RECIPIENT_TEAM.equals(recipientKind)
                && (teamChanged || !AlertForward.RECIPIENT_TEAM.equals(before.getRecipientKind()));
        List<Long> newMembers = new ArrayList<>();
        if (AlertForward.RECIPIENT_MEMBERS.equals(recipientKind)) {
            boolean wasWholeSameTeam = AlertForward.RECIPIENT_TEAM.equals(before.getRecipientKind()) && !teamChanged;
            if (!wasWholeSameTeam) {
                Set<Long> had = teamChanged ? Set.of() : new HashSet<>(oldMembers);
                for (Long m : members) if (!had.contains(m)) newMembers.add(m);
            }
        }
        return new Saved(saved, before, true, newWhole, newMembers);
    }

    /** Kaldır. */
    @Transactional
    public void delete(Long id) {
        AlertForward f = repo.findById(id).orElseThrow(AlertForwardService::notFound);
        repo.delete(f);
        changed();
    }

    // ── Takım / kullanıcı yaşam döngüsü ─────────────────────────────────────

    /** Takım silinince: sahibi ya da alıcısı o takım olan iletimler (çağıranın işleminde). */
    @Transactional
    public int deleteForTeam(Long teamId) {
        if (teamId == null) return 0;
        int n = repo.deleteByTeam(teamId);
        if (n > 0) {
            log.info("Takım {} silindi: {} alarm iletimi (sahibi ya da alıcısı) silindi", teamId, n);
            changed();
        }
        return n;
    }

    /** Takım taşıma sonucu. */
    public record Moved(int moved, int removed) { }

    /**
     * Takım taşıma ({@code TeamAdminService.moveAll}): {@code from}'a GELEN iletimler ve sahibi {@code from} alıcısı {@code to}
     * olanlar (taşınınca kendi kendine iletim olurdu) silinir; sahibi {@code from} olanlar {@code to}'ya geçer.
     */
    @Transactional
    public Moved moveTeam(Long from, Long to) {
        if (from == null || to == null || from.equals(to)) return new Moved(0, 0);
        int removed = repo.deleteByOwnerAndRecipient(from, to);
        removed += repo.deleteIncoming(from);
        int moved = repo.moveOwner(from, to, nowIso());
        if (moved > 0 || removed > 0) changed();
        return new Moved(moved, removed);
    }

    /** Takımın iletim sayıları (silme etkisi — bilgi). */
    public Map<String, Object> impactOf(Long teamId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("owned", teamId == null ? 0 : repo.countByOwnerTeamId(teamId));
        m.put("incoming", teamId == null ? 0 : repo.countByRecipientTeamId(teamId));
        return m;
    }

    /** Kullanıcı silinince: seçilen üye listelerinden çıkarılır (iletim kalır — üyesi kalmamışsa "alıcı yok" durumu). */
    @Transactional
    public int removeUser(Long userId) {
        if (userId == null) return 0;
        int n = 0;
        for (AlertForward f : repo.findByRecipientKindOrderByIdAsc(AlertForward.RECIPIENT_MEMBERS)) {
            List<Long> ids = parseIds(f.getRecipientUserIds());
            if (!ids.contains(userId)) continue;
            List<Long> rest = new ArrayList<>(ids);
            rest.remove(userId);
            f.setRecipientUserIds(idsCsv(rest));
            f.setUpdatedAt(nowIso());
            repo.save(f);
            n++;
        }
        if (n > 0) changed();
        return n;
    }

    // ── Doğrulama ────────────────────────────────────────────────────────────

    String scopeKind(Object raw) {
        String k = str(raw) == null ? "" : str(raw).trim().toUpperCase(Locale.ROOT);
        if (AlertForward.SCOPE_ALL.equals(k) || AlertForward.SCOPE_TYPES.equals(k) || AlertForward.SCOPE_MONITORS.equals(k)) return k;
        throw field("scope_kind", Msg.t("Kapsamı seçin: tüm izlemeler, seçilen türler ya da seçilen izlemeler.",
                "Choose the scope: all monitors, selected types or selected monitors."));
    }

    String recipientKind(Object raw) {
        String k = str(raw) == null ? "" : str(raw).trim().toUpperCase(Locale.ROOT);
        if (AlertForward.RECIPIENT_TEAM.equals(k) || AlertForward.RECIPIENT_MEMBERS.equals(k)) return k;
        throw field("recipient_kind", Msg.t("Alıcıyı seçin: takımın tamamı ya da takımın seçilen üyeleri.",
                "Choose the recipient: the whole team or selected members of the team."));
    }

    /** Ham tür listesi (doğrulamasız) — değişiklik karşılaştırması için. */
    private List<String> typeList(Object raw) {
        if (!(raw instanceof Collection<?> c)) return List.of();
        List<String> out = new ArrayList<>();
        int n = 0;
        for (Object o : c) {
            if (++n > CSV_MAX_ITEMS) break;
            String s = str(o);
            if (s != null) out.add(s.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    List<String> validateTypes(Object raw) {
        String msg = Msg.t("En az bir izleme türü seçin (" + TYPE_KEYS.size() + " türden biri ya da birkaçı).",
                "Choose at least one monitor type (one or more of the " + TYPE_KEYS.size() + " types).");
        if (!(raw instanceof Collection<?> c) || c.isEmpty()) throw field("scope_types", msg);
        if (c.size() > CSV_MAX_ITEMS) throw field("scope_types", msg);
        Set<String> got = new HashSet<>();
        for (Object o : c) {
            String s = str(o);
            String k = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
            if (k.length() > 40 || !TYPE_KEYS.contains(k)) {
                String shown = k.length() > 40 ? k.substring(0, 40) + "…" : k;
                throw field("scope_types", Msg.t("Bilinmeyen izleme türü: '" + shown + "'. Listeden bir tür seçin.",
                        "Unknown monitor type: '" + shown + "'. Choose a type from the list."));
            }
            got.add(k);
        }
        List<String> out = new ArrayList<>();
        for (String k : TYPE_KEYS) if (got.contains(k)) out.add(k);
        return out;
    }

    /**
     * İzleme listesi: 1–{@value #MAX_MONITORS} öğe, her öğe sahip takımın izlemesi (bağımsız izlemede takımı = sahip;
     * envanter kökenli Port/DNS ve cert'te envanterin SY ya da UG takımı = sahip). {@code kept} anahtarları (düzenlemede
     * zaten kayıtlı olanlar) yeniden doğrulanmaz.
     */
    List<MonitorRef> validateMonitors(Long owner, Object raw, Set<String> kept) {
        String countMsg = Msg.t("En az bir, en çok " + MAX_MONITORS + " izleme seçin.",
                "Choose at least one and at most " + MAX_MONITORS + " monitors.");
        if (!(raw instanceof Collection<?> c) || c.isEmpty()) throw field("scope_monitors", countMsg);
        if (c.size() > MAX_MONITORS) throw field("scope_monitors", countMsg);
        List<MonitorRef> refs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object o : c) {
            MonitorRef r = monitorRefOf(o);
            if (r == null) {
                throw field("scope_monitors", Msg.t("İzleme listesinde geçersiz bir öğe var — listeyi yenileyip yeniden seçin.",
                        "The monitor list has an invalid item — refresh the list and choose again."));
            }
            if (seen.add(r.key())) refs.add(r);
        }
        if (refs.size() > MAX_MONITORS) throw field("scope_monitors", countMsg);
        // Yeni öğeler sahip takımın izlemesi mi — tür başına TEK okuma
        Map<String, List<MonitorRef>> byType = new LinkedHashMap<>();
        for (MonitorRef r : refs) if (!kept.contains(r.key())) byType.computeIfAbsent(r.type(), k -> new ArrayList<>()).add(r);
        if (!byType.isEmpty()) {
            if (directory == null) {
                throw field("scope_monitors", Msg.t("İzleme listesi şu an okunamıyor — birkaç saniye sonra yeniden deneyin.",
                        "The monitor list can't be read right now — try again in a few seconds."));
            }
            for (Map.Entry<String, List<MonitorRef>> e : byType.entrySet()) {
                NocType t = nocTypeOf(e.getKey());
                Map<Long, NocMonitorDirectory.Row> rows = new HashMap<>();
                try {
                    for (NocMonitorDirectory.Row row : directory.ofType(t)) rows.put(row.id(), row);
                } catch (RuntimeException ex) {
                    log.warn("Alarm iletimi: {} izlemeleri okunamadı: {}", t, ex.toString());
                    throw field("scope_monitors", Msg.t("İzleme listesi şu an okunamıyor — birkaç saniye sonra yeniden deneyin.",
                            "The monitor list can't be read right now — try again in a few seconds."));
                }
                for (MonitorRef r : e.getValue()) {
                    NocMonitorDirectory.Row row = rows.get(r.id());
                    if (row == null || !(Objects.equals(owner, row.teamId()) || Objects.equals(owner, row.ugTeamId()))) {
                        throw field("scope_monitors", Msg.t("Seçilen izlemelerden biri (" + r.key() + ") bu takıma ait değil ya da silinmiş — listeyi yenileyip yeniden seçin.",
                                "One of the selected monitors (" + r.key() + ") doesn't belong to this team or was deleted — refresh the list and choose again."));
                    }
                }
            }
        }
        return refs;
    }

    static MonitorRef monitorRefOf(Object o) {
        if (!(o instanceof Map<?, ?> m)) return null;
        String type = str(m.get("type"));
        Long id = longOf(m.get("id"));
        if (type == null || id == null || id <= 0) return null;
        String k = type.trim().toLowerCase(Locale.ROOT);
        if (k.length() > 40 || !TYPE_KEYS.contains(k)) return null;
        return new MonitorRef(k, id);
    }

    Long validateRecipientTeam(Long owner, Object raw) {
        Long rt = longOf(raw);
        Team t = rt == null ? null : teamRepo.findById(rt).orElse(null);
        if (!activeTeam(t)) {
            throw field("recipient_team_id", Msg.t("Alarmların iletileceği takımı seçin (etkin bir takım olmalı).",
                    "Choose the team the alerts are forwarded to (it must be an active team)."));
        }
        if (rt.equals(owner)) {
            throw field("recipient_team_id", Msg.t("Takım alarmlarını kendisine iletemez — başka bir takım seçin.",
                    "A team can't forward its alerts to itself — choose another team."));
        }
        return rt;
    }

    /**
     * Üye listesi: 1–{@value #MAX_MEMBERS} kişi, her biri AKTİF ve alıcı takımın üyesi. {@code kept} (düzenlemede zaten
     * kayıtlı olanlar) yeniden doğrulanmaz. Çözülemeyen opak kimlik denetleyicide null olarak gelir → 400.
     */
    List<Long> validateMembers(Long recipientTeamId, List<Long> userIds, Set<Long> kept) {
        String countMsg = Msg.t("Takımdan en az bir, en çok " + MAX_MEMBERS + " üye seçin.",
                "Choose at least one and at most " + MAX_MEMBERS + " members of the team.");
        if (userIds == null || userIds.isEmpty()) throw field("recipient_user_ids", countMsg);
        if (userIds.size() > CSV_MAX_ITEMS) throw field("recipient_user_ids", countMsg);
        List<Long> ids = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long id : userIds) {
            if (id == null) {
                throw field("recipient_user_ids", Msg.t("Seçilen kişilerden biri bulunamadı — listeyi yenileyip yeniden seçin.",
                        "One of the selected people wasn't found — refresh the list and choose again."));
            }
            if (seen.add(id)) ids.add(id);
        }
        if (ids.size() > MAX_MEMBERS) throw field("recipient_user_ids", countMsg);
        List<Long> fresh = new ArrayList<>();
        for (Long id : ids) if (!kept.contains(id)) fresh.add(id);
        if (!fresh.isEmpty()) {
            Map<Long, Member> mem = members(fresh);
            for (Long id : fresh) {
                Member m = mem.get(id);
                if (m == null || !m.active() || !m.inTeam(recipientTeamId)) {
                    String who = m == null ? "#" + id : m.name();
                    throw field("recipient_user_ids", Msg.t("Seçilen kişi (" + who + ") aktif değil ya da alıcı takımın üyesi değil — takımdan aktif bir üye seçin.",
                            "The selected person (" + who + ") isn't active or isn't a member of the recipient team — choose an active member of the team."));
                }
            }
        }
        return ids;
    }

    /** {@code expires_on} (İstanbul günü, o günün SONUNA kadar) → UTC ISO; boş → null (süresiz); geçmiş → 400. */
    public String expiresAt(String expiresOn) {
        if (expiresOn == null || expiresOn.isBlank()) return null;
        if (expiresOn.length() > 40) {
            throw field("expires_on", Msg.t("Bitiş tarihi yyyy-AA-GG biçiminde olmalı.", "The end date must be yyyy-MM-dd."));
        }
        LocalDate d;
        try {
            d = LocalDate.parse(expiresOn.trim());
        } catch (DateTimeParseException e) {
            throw field("expires_on", Msg.t("Bitiş tarihi yyyy-AA-GG biçiminde olmalı.", "The end date must be yyyy-MM-dd."));
        }
        if (d.getYear() > MAX_EXPIRY_YEAR) {
            throw field("expires_on", Msg.t("Bitiş tarihi en geç " + MAX_EXPIRY_YEAR + "-12-31 olabilir; süresiz iletim için boş bırakın.",
                    "The end date can be " + MAX_EXPIRY_YEAR + "-12-31 at the latest; leave it empty for a forward with no end date."));
        }
        Instant end = d.plusDays(1).atStartOfDay(IST).toInstant();
        if (!end.isAfter(Instant.ofEpochMilli(clock.getAsLong()))) {
            throw field("expires_on", Msg.t("Bitiş tarihi geçmişte — bugün ya da sonrası bir tarih seçin.",
                    "The end date is in the past — choose today or a later date."));
        }
        return ISO.format(end);
    }

    String note(Object raw) {
        String n = str(raw);
        if (n != null) n = n.trim();
        if (n != null && n.isEmpty()) n = null;
        if (n != null && n.length() > NOTE_MAX) {
            throw field("note", Msg.t("Not en çok " + NOTE_MAX + " karakter olabilir.", "The note can be at most " + NOTE_MAX + " characters."));
        }
        return n;
    }

    /** Aynı sahip + aynı alıcı (tür, takım, üye kümesi) + aynı kapsam zaten var → 409 FORWARD_EXISTS. */
    private void requireUnique(AlertForward f, Long selfId) {
        String key = canonical(f);
        for (AlertForward o : repo.findByOwnerTeamIdInOrderByIdAsc(List.of(f.getOwnerTeamId()))) {
            if (selfId != null && selfId.equals(o.getId())) continue;
            if (key.equals(canonical(o))) {
                throw new GlobalExceptionHandler.CodedConflictException("FORWARD_EXISTS", Msg.t(
                        "Bu alıcıya aynı kapsamla bir iletim zaten var — mevcut iletimi düzenleyin.",
                        "A forward with the same scope already goes to this recipient — edit the existing forward instead."),
                        Map.of("forward_id", o.getId()));
            }
        }
    }

    static String canonical(AlertForward f) {
        String kind = nz(f.getScopeKind());
        String scope = switch (kind) {
            case AlertForward.SCOPE_TYPES -> nz(typesCsv(parseTypes(f.getScopeTypes())));
            case AlertForward.SCOPE_MONITORS -> nz(monitorsCsv(parseMonitors(f.getScopeMonitors())));
            default -> "";
        };
        String users = AlertForward.RECIPIENT_MEMBERS.equals(f.getRecipientKind()) ? nz(idsCsv(parseIds(f.getRecipientUserIds()))) : "";
        return f.getOwnerTeamId() + "|" + kind + "|" + scope + "|" + nz(f.getRecipientKind()) + "|" + f.getRecipientTeamId() + "|" + users;
    }

    /** Süresi dolmuş mu (görünüm). */
    public boolean expired(AlertForward f) {
        return f.getExpiresAt() != null && f.getExpiresAt().compareTo(nowIso()) <= 0;
    }

    static AlertForward copy(AlertForward f) {
        AlertForward c = new AlertForward();
        c.setId(f.getId());
        c.setVersion(f.getVersion());
        c.setOwnerTeamId(f.getOwnerTeamId());
        c.setScopeKind(f.getScopeKind());
        c.setScopeTypes(f.getScopeTypes());
        c.setScopeMonitors(f.getScopeMonitors());
        c.setRecipientKind(f.getRecipientKind());
        c.setRecipientTeamId(f.getRecipientTeamId());
        c.setRecipientUserIds(f.getRecipientUserIds());
        c.setEnabled(f.isEnabled());
        c.setExpiresAt(f.getExpiresAt());
        c.setNote(f.getNote());
        c.setCreatedAt(f.getCreatedAt());
        c.setCreatedBy(f.getCreatedBy());
        c.setUpdatedAt(f.getUpdatedAt());
        c.setUpdatedBy(f.getUpdatedBy());
        return c;
    }

    // ── Küçük yardımcılar ────────────────────────────────────────────────────

    static boolean activeTeam(Team t) {
        return t != null && !Boolean.FALSE.equals(t.getActive());
    }

    private static GlobalExceptionHandler.FieldValidationException field(String name, String msg) {
        return new GlobalExceptionHandler.FieldValidationException(name, msg);
    }

    static java.util.NoSuchElementException notFound() {
        return new java.util.NoSuchElementException(Msg.t(
                "Alarm iletimi bulunamadı; silinmiş olabilir. Listeyi yenileyip yeniden deneyin.",
                "The alarm forward wasn't found; it may have been deleted. Refresh the list and try again."));
    }

    private static GlobalExceptionHandler.CodedConflictException concurrent() {
        return new GlobalExceptionHandler.CodedConflictException("CONCURRENT_MODIFICATION", Msg.t(
                "Bu iletim siz düzenlerken başka biri tarafından değiştirildi — listeyi yenileyip yeniden deneyin.",
                "Someone else changed this forward while you were editing it — refresh the list and try again."));
    }

    static String str(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.length() > RAW_TEXT_MAX ? s.substring(0, RAW_TEXT_MAX) : s;
    }

    public static Long longOf(Object o) {
        if (o instanceof Number n) return Long.valueOf(n.longValue());
        if (o instanceof String s && !s.isBlank() && s.length() <= 19) {
            try { return Long.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static boolean bool(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) return true;
            if ("false".equalsIgnoreCase(s.trim())) return false;
        }
        throw field("enabled", Msg.t("Etkin alanı açık ya da kapalı (true / false) olmalı.", "The enabled field must be on or off (true / false)."));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
