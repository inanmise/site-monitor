package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.AlertStormMember;
import com.sitemonitor.model.StormPushCoverage;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Fırtına push'u ↔ üye alarm bağı (2026-10-04, kullanıcı isteği: "push alarmı fırtınaya devredilse bile fırtına ile giden
 * push mesajı ilgili alarmla ilişkilendirilsin — alarmın geçmişinden fırtına push'unun ne zaman iletildiğini göreyim").
 *
 * <p><b>Yazma</b> ({@link #record}): {@link UserPushService#enqueueStormNoticeLocalized} bildirim kararını verdikten SONRA
 * (gönderim satırları ya da kanal karar satırı yazıldı) kapsanan üye alarmları TEK JDBC batch ile yazar —
 * {@code ON CONFLICT DO NOTHING}, hata yutulur: gözlem kaydı push kararını asla değiştirmez ya da geciktirmez (outbox
 * kuyruğu kayıttan önce tetiklenmiştir). Takım yalıtımı: bildirimin takımından başka bir takıma damgalı alarm kapsanmaz.
 * Çözüm bildirimi yalnız KURTULAN üyeleri kapsar (çözüm postasının günlük satırıyla aynı küme); açılış ve günlük tekrar o
 * anda listelenen (hâlâ düşük) üyeleri.
 *
 * <p><b>Okuma.</b> Alarm → kapsayan fırtına push'ları ({@link #coverageForAlerts}) ve bildirim → kapsadığı alarmlar
 * ({@link #alarmsForNotices}); ikisi de SAYFA başına sabit sayıda sorgudur (satır başına sorgu yok). Bu özellikten önce
 * gönderilmiş bildirimlerde kayıt yoktur: kapsam {@code alert_storm_members} (katılım..ayrılış penceresi) ve bildirimin
 * ilk satırının anından TAHMİN edilir ({@code inferred = true}). Bir bildirimin (anahtar × takım) EN AZ bir kayıt satırı
 * varsa o bildirim için tahmin yapılmaz — kaydı olmayan alarm gerçekten kapsanmamıştır (bildirimden sonra katıldı).
 */
@Slf4j
@Service
public class StormPushCoverageService {

    /** Tek cümle, tek batch — çakışma (aynı bildirim + alarm) sessizce yutulur; H2 (PostgreSQL modu) ve PostgreSQL ortak. */
    public static final String SQL_INSERT = "INSERT INTO storm_push_coverage(storm_id, team_id, push_key, notice_trigger, "
            + "alert_event_id, created_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";

    static final String KEY_PREFIX = "storm:";
    static final String RESOLVED_PREFIX = "storm-resolved:";
    static final int IN_CHUNK = 500;
    /** Bildirim grubu sorgusunda tek cümledeki en çok fırtına (LIKE önekleri). */
    static final int STORM_CHUNK = 50;
    /** Push geçmişim satırında listelenen en çok alarm (kalanı yalnız sayı). */
    static final int VISIBLE_ALARM_CAP = 10;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final AlertEventRepository alertEventRepo;
    private final AlertStormRepository stormRepo;
    private final UserPushDeliveryRepository deliveryRepo;

    @Autowired
    public StormPushCoverageService(JdbcTemplate jdbc, AlertEventRepository alertEventRepo, AlertStormRepository stormRepo,
                                    UserPushDeliveryRepository deliveryRepo) {
        this.jdbc = jdbc;
        this.alertEventRepo = alertEventRepo;
        this.stormRepo = stormRepo;
        this.deliveryRepo = deliveryRepo;
    }

    // ── Değer tipleri ─────────────────────────────────────────────────────────────────────────────────────────────

    /** Bildirim anahtarının çözümü: fırtına, tetik (INITIAL / DAILY_REALERT / RESOLVE), günlük tekrarın günü. */
    public record NoticeKey(long stormId, String trigger, String day) { }

    /** Bir alarmı kapsayan fırtına bildirimi. {@code coveredAt}: kayıt anı ya da (tahminde) bildirimin ilk satırının anı. */
    public record PushRef(long stormId, Long teamId, String pushKey, String trigger, String day, String coveredAt,
                          boolean inferred) { }

    /** Fırtına üyeliği (kalıcı tablo; eski fırtınada alarmın kendi {@code storm_id}'sinden sentetik). */
    public record Membership(long stormId, long alertEventId, String joinedAt, String joinKind, String leftAt, String leaveKind) { }

    /** Alarmın kapsamı: kapsayan bildirimler (zaman sırası), üyelikleri, henüz duyurulmadığı AÇIK fırtınalar. */
    public record AlarmCoverage(List<PushRef> pushes, List<Membership> memberships, List<Long> awaitingStorms) {
        static final AlarmCoverage EMPTY = new AlarmCoverage(List.of(), List.of(), List.of());
    }

    /** Toplu sonuç + yüklenen fırtınalar (çağıran ayrıca okumasın). */
    public record Result(Map<Long, AlarmCoverage> byAlert, Map<Long, AlertStorm> storms) {
        static final Result EMPTY = new Result(Map.of(), Map.of());
        public AlarmCoverage of(Long alertId) { return alertId == null ? AlarmCoverage.EMPTY : byAlert.getOrDefault(alertId, AlarmCoverage.EMPTY); }
    }

    /** Bildirim (anahtar × takım) + ilk satırının anı — tahmin bu anla yapılır. */
    public record NoticeRef(String pushKey, Long teamId, String createdAt) { }

    /** Bildirimin kapsadığı alarm. */
    public record CoveredAlarm(long alertEventId, boolean inferred) { }

    private record NoticeGroup(String pushKey, Long teamId, String createdAt) { }

    // ── Yazma (alarm yolu) ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Bildirimin kapsadığı üye alarmları yazar — TEK batch, hata yutulur (0 döner). Push kararını DEĞİŞTİRMEZ.
     *
     * @return yazılmaya çalışılan satır sayısı (çakışmalar dahil); hata ya da boş küme 0
     */
    public int record(Long stormId, Long teamId, String pushKey, String stormTrigger, List<AlertEvent> members) {
        if (stormId == null || teamId == null || pushKey == null || members == null || members.isEmpty()) return 0;
        String trigger = normTrigger(stormTrigger);
        if (trigger == null) return 0;
        try {
            boolean resolve = StormPushCoverage.TRIGGER_RESOLVE.equals(trigger);
            String at = ISO.format(Instant.now());
            List<Object[]> rows = new ArrayList<>(members.size());
            Set<Long> seen = new HashSet<>();
            for (AlertEvent m : members) {
                if (m == null || m.getId() == null || !seen.add(m.getId())) continue;
                // Takım yalıtımı: TEAM:A bildirimi TEAM:B'ye damgalı alarmı ASLA kapsamaz (savunma; dağıtım zaten takım bazlı).
                if (m.getTeamId() != null && !teamId.equals(m.getTeamId())) continue;
                // Çözüm bildirimi yalnız KURTULANLARI anlatır ("N monitör kurtarıldı"); hâlâ-düşük üye için bu bir çözüm değil.
                if (resolve && !Boolean.TRUE.equals(m.getResolved())) continue;
                rows.add(new Object[]{stormId, teamId, pushKey, trigger, m.getId(), at});
            }
            if (rows.isEmpty()) return 0;
            jdbc.batchUpdate(SQL_INSERT, rows);
            return rows.size();
        } catch (Exception e) {
            log.warn("Fırtına #{} push kapsamı yazılamadı ({}): {}", stormId, pushKey, e.getMessage());
            return 0;
        }
    }

    static String normTrigger(String t) {
        if (t == null) return null;
        return switch (t) {
            case "INITIAL" -> StormPushCoverage.TRIGGER_INITIAL;
            case "DAILY_REALERT" -> StormPushCoverage.TRIGGER_DAILY_REALERT;
            case "RESOLVE" -> StormPushCoverage.TRIGGER_RESOLVE;
            default -> null;
        };
    }

    // ── Anahtar ───────────────────────────────────────────────────────────────────────────────────────────────────

    /** {@code storm:<id>:INITIAL}, {@code storm:<id>:DAILY_REALERT[:<gün>]}, {@code storm-resolved:<id>}; başkası null. */
    public static NoticeKey parseKey(String key) {
        if (key == null) return null;
        try {
            if (key.startsWith(RESOLVED_PREFIX)) {
                return new NoticeKey(Long.parseLong(key.substring(RESOLVED_PREFIX.length())), StormPushCoverage.TRIGGER_RESOLVE, null);
            }
            if (key.startsWith(KEY_PREFIX)) {
                String[] p = key.split(":");
                if (p.length < 3) return null;
                String trig = p[2];
                if (!StormPushCoverage.TRIGGER_INITIAL.equals(trig) && !StormPushCoverage.TRIGGER_DAILY_REALERT.equals(trig)) return null;
                return new NoticeKey(Long.parseLong(p[1]), trig, p.length > 3 ? p[3] : null);
            }
        } catch (NumberFormatException ignored) {
            // biçim dışı anahtar — fırtına bildirimi sayılmaz
        }
        return null;
    }

    public static boolean isStormNoticeKey(String key) { return parseKey(key) != null; }

    // ── Tahmin kuralı (kayıt öncesi bildirimler) ──────────────────────────────────────────────────────────────────

    /**
     * Kaydı olmayan (özellikten önceki) bildirim bu üyeyi kapsıyor muydu? {@code pushAt}: bildirimin ilk satırının anı.
     * <ul>
     *   <li>Üye bildirimden ÖNCE katılmış olmalı (açılış bildirimi açılıştaki üyeleri kapsar).</li>
     *   <li>Açılış / günlük tekrar: bildirim anında hâlâ fırtınada ve düşük (ayrılmamış, kapanmamış).</li>
     *   <li>Çözüm: üye kurtulmuş (ayrılış RECOVERED ya da bildirimden önce kapanmış; başka ayrılış türü kurtuluş değil).</li>
     * </ul>
     */
    public static boolean infers(String trigger, String pushAt, Membership m, AlertEvent e) {
        if (trigger == null || pushAt == null) return false;
        String joined = m != null && m.joinedAt() != null ? m.joinedAt() : (e != null ? e.getCreatedAt() : null);
        if (joined == null || cmp(joined, pushAt) > 0) return false;
        if (StormPushCoverage.TRIGGER_RESOLVE.equals(trigger)) {
            if (m != null && AlertStormMember.LEAVE_RECOVERED.equals(m.leaveKind())) return true;
            if (m != null && m.leaveKind() != null) return false;
            return e != null && Boolean.TRUE.equals(e.getResolved()) && e.getResolvedAt() != null
                    && cmp(e.getResolvedAt(), pushAt) <= 0;
        }
        if (m != null && m.leftAt() != null && cmp(m.leftAt(), pushAt) < 0) return false;
        return e == null || !Boolean.TRUE.equals(e.getResolved()) || e.getResolvedAt() == null
                || cmp(e.getResolvedAt(), pushAt) >= 0;
    }

    /** ISO damgaları saniye hassasiyetinde karşılaştırır (kesir / 'Z' farkı yok sayılır). */
    static int cmp(String a, String b) {
        return norm(a).compareTo(norm(b));
    }

    private static String norm(String s) {
        if (s == null) return "";
        String x = s.replace(' ', 'T');
        return x.length() > 19 ? x.substring(0, 19) : x;
    }

    /** Alarmın push takımı: olay damgası → fırtınanın takımı → çağıranın yedeği (envanterin SY takımı). */
    static Long alarmTeam(AlertEvent e, AlertStorm s, Function<AlertEvent, Long> fallback) {
        if (e != null && e.getTeamId() != null) return e.getTeamId();
        if (s != null && s.getTeamId() != null) return s.getTeamId();
        return fallback == null || e == null ? null : fallback.apply(e);
    }

    // ── Alarm → kapsayan bildirimler ──────────────────────────────────────────────────────────────────────────────

    /**
     * Alarmlar için kapsayan fırtına bildirimleri — sabit sayıda sorgu (kayıt, üyelik, fırtına, bildirim grupları,
     * kayıtlı anahtarlar). Hata yutulur: boş sonuç (ekran "kayıt yok" gösterir, alarm görünümü bozulmaz).
     *
     * @param teamFallback olay ve fırtına takımı yoksa alarmın push takımı (null = bilinmiyor → tahmin yapılmaz)
     */
    public Result coverageForAlerts(Collection<AlertEvent> alerts, Function<AlertEvent, Long> teamFallback) {
        Map<Long, AlertEvent> byId = new LinkedHashMap<>();
        if (alerts != null) for (AlertEvent e : alerts) if (e != null && e.getId() != null) byId.put(e.getId(), e);
        if (byId.isEmpty()) return Result.EMPTY;
        try {
            Map<Long, List<PushRef>> pushes = new LinkedHashMap<>();
            Set<Long> stormIds = new LinkedHashSet<>();
            for (Map<String, Object> r : inQuery("SELECT alert_event_id, storm_id, team_id, push_key, notice_trigger, created_at "
                    + "FROM storm_push_coverage WHERE alert_event_id IN ", byId.keySet())) {
                long aid = lng(r.get("alert_event_id"));
                long sid = lng(r.get("storm_id"));
                String key = str(r.get("push_key"));
                NoticeKey k = parseKey(key);
                pushes.computeIfAbsent(aid, x -> new ArrayList<>()).add(new PushRef(sid, lngOrNull(r.get("team_id")), key,
                        str(r.get("notice_trigger")), k == null ? null : k.day(), str(r.get("created_at")), false));
                stormIds.add(sid);
            }

            Map<Long, List<Membership>> mem = new LinkedHashMap<>();
            for (Map<String, Object> r : inQuery("SELECT storm_id, alert_event_id, joined_at, join_kind, left_at, leave_kind "
                    + "FROM alert_storm_members WHERE alert_event_id IN ", byId.keySet())) {
                Membership m = membership(r);
                mem.computeIfAbsent(m.alertEventId(), x -> new ArrayList<>()).add(m);
            }
            for (AlertEvent e : byId.values()) {   // eski fırtına (üyelik tablosundan önce): alarmın kendi bağı
                if (e.getStormId() == null) continue;
                List<Membership> ms = mem.computeIfAbsent(e.getId(), x -> new ArrayList<>());
                if (ms.stream().noneMatch(m -> m.stormId() == e.getStormId()))
                    ms.add(new Membership(e.getStormId(), e.getId(), e.getCreatedAt(), null, null, null));
            }
            Set<Long> memberStorms = new LinkedHashSet<>();
            for (List<Membership> ms : mem.values()) for (Membership m : ms) memberStorms.add(m.stormId());
            stormIds.addAll(memberStorms);

            Map<Long, AlertStorm> storms = new HashMap<>();
            if (!stormIds.isEmpty()) for (AlertStorm s : stormRepo.findAllById(stormIds)) if (s.getId() != null) storms.put(s.getId(), s);

            if (!memberStorms.isEmpty()) {
                Map<Long, List<NoticeGroup>> groups = noticeGroups(memberStorms);
                if (!groups.isEmpty()) {
                    Set<String> recorded = recordedNotices(memberStorms);
                    for (Map.Entry<Long, List<Membership>> en : mem.entrySet()) {
                        AlertEvent e = byId.get(en.getKey());
                        List<PushRef> mine = pushes.computeIfAbsent(en.getKey(), x -> new ArrayList<>());
                        for (Membership m : en.getValue()) {
                            Long team = alarmTeam(e, storms.get(m.stormId()), teamFallback);
                            if (team == null) continue;
                            for (NoticeGroup g : groups.getOrDefault(m.stormId(), List.of())) {
                                if (!team.equals(g.teamId())) continue;
                                if (recorded.contains(noticeKey(g.pushKey(), g.teamId()))) continue;   // kayıt esas
                                NoticeKey k = parseKey(g.pushKey());
                                if (k == null || !infers(k.trigger(), g.createdAt(), m, e)) continue;
                                if (mine.stream().anyMatch(p -> p.pushKey().equals(g.pushKey()) && Objects.equals(p.teamId(), g.teamId()))) continue;
                                mine.add(new PushRef(m.stormId(), g.teamId(), g.pushKey(), k.trigger(), k.day(), g.createdAt(), true));
                            }
                        }
                    }
                }
            }

            Map<Long, AlarmCoverage> out = new LinkedHashMap<>();
            for (Long aid : byId.keySet()) {
                List<PushRef> ps = new ArrayList<>(pushes.getOrDefault(aid, List.of()));
                ps.sort(Comparator.comparing((PushRef p) -> norm(p.coveredAt())).thenComparing(PushRef::pushKey));
                List<Membership> ms = mem.getOrDefault(aid, List.of());
                List<Long> awaiting = new ArrayList<>();
                for (Membership m : ms) {
                    AlertStorm s = storms.get(m.stormId());
                    if (s == null || Boolean.TRUE.equals(s.getResolved()) || m.leftAt() != null) continue;
                    boolean announced = ps.stream().anyMatch(p -> p.stormId() == m.stormId()
                            && !StormPushCoverage.TRIGGER_RESOLVE.equals(p.trigger()));
                    if (!announced && !awaiting.contains(m.stormId())) awaiting.add(m.stormId());
                }
                if (!ps.isEmpty() || !ms.isEmpty()) out.put(aid, new AlarmCoverage(List.copyOf(ps), List.copyOf(ms), List.copyOf(awaiting)));
            }
            return new Result(out, storms);
        } catch (Exception ex) {
            log.warn("Fırtına push kapsamı okunamadı ({} alarm): {}", byId.size(), ex.getMessage());
            return Result.EMPTY;
        }
    }

    // ── Bildirim → kapsadığı alarmlar ─────────────────────────────────────────────────────────────────────────────

    /** {@code pushKey|teamId} — bildirim (anahtar × takım) kimliği. */
    public static String noticeKey(String pushKey, Long teamId) {
        return pushKey + "|" + (teamId == null ? "" : teamId);
    }

    /**
     * Bildirimlerin kapsadığı alarmlar — sabit sayıda sorgu. Kayıt yoksa (özellikten önceki bildirim) üyelik penceresinden
     * tahmin ({@code inferred}). Sonuç anahtarı {@link #noticeKey}; hata yutulur (boş).
     */
    public Map<String, List<CoveredAlarm>> alarmsForNotices(Collection<NoticeRef> notices) {
        Map<String, NoticeRef> byKey = new LinkedHashMap<>();
        if (notices != null) for (NoticeRef n : notices) {
            if (n == null || parseKey(n.pushKey()) == null) continue;
            byKey.putIfAbsent(noticeKey(n.pushKey(), n.teamId()), n);
        }
        if (byKey.isEmpty()) return Map.of();
        try {
            Map<String, List<CoveredAlarm>> out = new LinkedHashMap<>();
            Set<String> pushKeys = new LinkedHashSet<>();
            for (NoticeRef n : byKey.values()) pushKeys.add(n.pushKey());
            Set<String> recorded = new HashSet<>();
            for (Map<String, Object> r : inQuery("SELECT push_key, team_id, alert_event_id FROM storm_push_coverage WHERE push_key IN ",
                    pushKeys)) {
                String key = str(r.get("push_key"));
                Long team = lngOrNull(r.get("team_id"));
                recorded.add(noticeKey(key, team));
                for (NoticeRef n : byKey.values()) {
                    if (!n.pushKey().equals(key) || (n.teamId() != null && !n.teamId().equals(team))) continue;
                    out.computeIfAbsent(noticeKey(n.pushKey(), n.teamId()), x -> new ArrayList<>())
                            .add(new CoveredAlarm(lng(r.get("alert_event_id")), false));
                }
            }

            // Kaydı olmayan (özellikten önceki) bildirimler: üyelik penceresinden tahmin.
            List<NoticeRef> legacy = new ArrayList<>();
            for (NoticeRef n : byKey.values()) {
                boolean has = n.teamId() == null
                        ? recorded.stream().anyMatch(k -> k.startsWith(n.pushKey() + "|"))
                        : recorded.contains(noticeKey(n.pushKey(), n.teamId()));
                if (!has) legacy.add(n);
            }
            if (!legacy.isEmpty()) {
                Set<Long> stormIds = new LinkedHashSet<>();
                for (NoticeRef n : legacy) stormIds.add(parseKey(n.pushKey()).stormId());
                Map<Long, List<Membership>> byStorm = new HashMap<>();
                Set<Long> eventIds = new LinkedHashSet<>();
                for (Map<String, Object> r : inQuery("SELECT storm_id, alert_event_id, joined_at, join_kind, left_at, leave_kind "
                        + "FROM alert_storm_members WHERE storm_id IN ", stormIds)) {
                    Membership m = membership(r);
                    byStorm.computeIfAbsent(m.stormId(), x -> new ArrayList<>()).add(m);
                    eventIds.add(m.alertEventId());
                }
                if (!eventIds.isEmpty()) {
                    Map<Long, AlertEvent> events = new HashMap<>();
                    for (AlertEvent e : alertEventRepo.findAllById(eventIds)) if (e.getId() != null) events.put(e.getId(), e);
                    Map<Long, AlertStorm> storms = new HashMap<>();
                    for (AlertStorm s : stormRepo.findAllById(stormIds)) if (s.getId() != null) storms.put(s.getId(), s);
                    for (NoticeRef n : legacy) {
                        NoticeKey k = parseKey(n.pushKey());
                        for (Membership m : byStorm.getOrDefault(k.stormId(), List.of())) {
                            AlertEvent e = events.get(m.alertEventId());
                            Long team = alarmTeam(e, storms.get(k.stormId()), null);
                            if (n.teamId() != null && !n.teamId().equals(team)) continue;
                            if (!infers(k.trigger(), n.createdAt(), m, e)) continue;
                            List<CoveredAlarm> list = out.computeIfAbsent(noticeKey(n.pushKey(), n.teamId()), x -> new ArrayList<>());
                            if (list.stream().noneMatch(c -> c.alertEventId() == m.alertEventId()))
                                list.add(new CoveredAlarm(m.alertEventId(), true));
                        }
                    }
                }
            }
            for (List<CoveredAlarm> l : out.values()) l.sort(Comparator.comparingLong(CoveredAlarm::alertEventId));
            return out;
        } catch (Exception ex) {
            log.warn("Fırtına bildirimlerinin kapsamı okunamadı ({} bildirim): {}", byKey.size(), ex.getMessage());
            return Map.of();
        }
    }

    // ── Alarm detayı (GET /api/admin/alerts/{id}/storm-push) ──────────────────────────────────────────────────────

    /** Ayarlar — yalnız {@code push_individual} bilgi alanı için; yokken (testler) varsayılan KAPALI. */
    @Autowired(required = false)
    private AppSettingsService appSettings;

    /** {@link #alarmDetail(AlertEvent, Long, boolean)} — push kipi ayardan ({@link StormService#KEY_PUSH_INDIVIDUAL}). */
    public Map<String, Object> alarmDetail(AlertEvent e, Long fallbackTeamId) {
        boolean individual = false;
        try { individual = appSettings != null && appSettings.getBoolean(StormService.KEY_PUSH_INDIVIDUAL, false); }
        catch (Exception ignored) { /* bilgi alanı */ }
        return alarmDetail(e, fallbackTeamId, individual);
    }

    /**
     * Alarm detayının "Fırtına push'u" bölümü: kapsayan her fırtına bildirimi (ilk / son gönderim, durum sayıları, alıcı
     * satırları — alarmın push bölümüyle aynı alanlar), tahmin bayrağı, fırtınaya devredilmiş ama henüz duyurulmamış AÇIK
     * fırtınalar ({@code pending}) ve üyelik özeti. Yetki ve takım kapsamı çağıran uçta (alarm detayıyla aynı kapı).
     *
     * @param fallbackTeamId olay ve fırtına takımı yoksa push takımı (envanterin SY takımı) — tahmin için
     * @param pushIndividual {@code site.monitor.storm.push-individual} (bilgi; arayüz metni seçer)
     */
    public Map<String, Object> alarmDetail(AlertEvent e, Long fallbackTeamId, boolean pushIndividual) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alert_id", e == null ? null : e.getId());
        out.put("push_individual", pushIndividual);
        if (e == null || e.getId() == null) {
            out.put("handed_over", false); out.put("items", List.of()); out.put("pending", List.of()); out.put("storms", List.of());
            return out;
        }
        Result r = coverageForAlerts(List.of(e), x -> fallbackTeamId);
        AlarmCoverage c = r.of(e.getId());

        Set<String> keys = new LinkedHashSet<>();
        for (PushRef p : c.pushes()) keys.add(p.pushKey());
        List<UserPushDelivery> rows = List.of();
        Map<String, Long> coveredCounts = Map.of();
        if (!keys.isEmpty()) {
            try { rows = deliveryRepo.findByDedupeKeyInOrderByIdAsc(keys); } catch (Exception ex) { rows = List.of(); }
            coveredCounts = recordedCounts(keys);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (PushRef p : c.pushes()) {
            List<UserPushDelivery> mine = new ArrayList<>();
            for (UserPushDelivery d : rows)
                if (p.pushKey().equals(d.getDedupeKey()) && (p.teamId() == null || p.teamId().equals(d.getTeamId()))) mine.add(d);
            items.add(pushItem(p, mine, coveredCounts.get(noticeKey(p.pushKey(), p.teamId()))));
        }
        out.put("items", items);

        boolean handedOver = false;
        try { handedOver = deliveryRepo.existsByAlertEventIdAndStatus(e.getId(), EscalationService.PUSH_SKIPPED_STORM); }
        catch (Exception ignored) { /* bilgi alanı — yoksa "devredilmedi" sayılır */ }
        out.put("handed_over", handedOver);

        List<Map<String, Object>> pending = new ArrayList<>();
        if (handedOver) {
            for (Long sid : c.awaitingStorms()) {
                AlertStorm s = r.storms().get(sid);
                Membership m = c.memberships().stream().filter(x -> x.stormId() == sid).findFirst().orElse(null);
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("storm_id", sid);
                p.put("team_id", s == null ? null : s.getTeamId());
                p.put("joined_at", m == null ? null : m.joinedAt());
                p.put("storm_created_at", s == null ? null : s.getCreatedAt());
                p.put("next_realert_at", s == null ? null : nextRealertAt(s));
                pending.add(p);
            }
        }
        out.put("pending", pending);

        List<Map<String, Object>> storms = new ArrayList<>();
        for (Membership m : c.memberships()) {
            AlertStorm s = r.storms().get(m.stormId());
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("storm_id", m.stormId());
            sm.put("team_id", s == null ? null : s.getTeamId());
            sm.put("active", s != null && !Boolean.TRUE.equals(s.getResolved()));
            sm.put("created_at", s == null ? null : s.getCreatedAt());
            sm.put("resolved_at", s == null ? null : s.getResolvedAt());
            sm.put("joined_at", m.joinedAt());
            sm.put("join_kind", m.joinKind());
            sm.put("left_at", m.leftAt());
            sm.put("leave_kind", m.leaveKind());
            sm.put("covered", c.pushes().stream().filter(p -> p.stormId() == m.stormId()).count());
            storms.add(sm);
        }
        out.put("storms", storms);
        return out;
    }

    /** Açık fırtınanın bir sonraki toplu tekrarı: son toplu bildirim (yoksa açılış) + {@link StormService#RE_ALERT_HOURS}. */
    static String nextRealertAt(AlertStorm s) {
        String base = s.getLastReAlertAt() != null ? s.getLastReAlertAt() : s.getCreatedAt();
        if (base == null) return null;
        try {
            return ISO.format(LocalDateTime.parse(norm(base)).toInstant(ZoneOffset.UTC).plus(Duration.ofHours(StormService.RE_ALERT_HOURS)));
        } catch (Exception ex) {
            return null;
        }
    }

    /** Bildirimin satırlarından özet + alıcı listesi (alarm push bölümüyle aynı alanlar: ad, kullanıcı adı, durum, zaman). */
    static Map<String, Object> pushItem(PushRef p, List<UserPushDelivery> rows, Long coveredAlarms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("storm_id", p.stormId());
        m.put("team_id", p.teamId());
        m.put("push_key", p.pushKey());
        m.put("trigger", p.trigger());
        m.put("day", p.day());
        m.put("inferred", p.inferred());
        m.put("covered_at", p.coveredAt());
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Map<String, Object>> recipients = new ArrayList<>();
        String firstCreated = null, firstSent = null, lastSent = null, decision = null, message = null;
        int sent = 0, failed = 0, pending = 0, notSent = 0;
        for (UserPushDelivery d : rows) {
            firstCreated = min(firstCreated, d.getCreatedAt());
            if (UserPushService.SYSTEM_USER.equals(d.getUsername())) {
                if (decision == null) decision = d.getStatus();
                continue;
            }
            String st = d.getStatus() == null ? "UNKNOWN" : d.getStatus();
            counts.merge(st, 1, Integer::sum);
            switch (st) {
                case "SENT" -> { sent++; firstSent = min(firstSent, d.getSentAt()); lastSent = max(lastSent, d.getSentAt()); }
                case "FAILED", "CIRCUIT_OPEN" -> failed++;
                case "PENDING" -> pending++;
                default -> notSent++;
            }
            if (message == null && d.getMessage() != null && !"en".equalsIgnoreCase(d.getPushLang())) message = d.getMessage();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", d.getId());
            r.put("username", d.getUsername());
            r.put("display_name", d.getDisplayName());
            r.put("status", st);
            r.put("sent_at", d.getSentAt());
            r.put("created_at", d.getCreatedAt());
            r.put("attempts", d.getAttempts());
            r.put("push_lang", d.getPushLang());
            recipients.add(r);
        }
        if (message == null) for (UserPushDelivery d : rows) if (d.getMessage() != null) { message = d.getMessage(); break; }
        m.put("first_created_at", firstCreated);
        m.put("first_sent_at", firstSent);
        m.put("last_sent_at", lastSent);
        m.put("recipient_total", recipients.size());
        m.put("sent", sent);
        m.put("failed", failed);
        m.put("pending", pending);
        m.put("not_sent", notSent);
        m.put("counts", counts);
        m.put("decision", decision);
        m.put("message", message);
        m.put("covered_alarms", coveredAlarms);
        m.put("outcome", sent > 0 ? "sent" : pending > 0 ? "pending" : failed > 0 ? "failed"
                : (decision != null || notSent > 0) ? "skipped" : "none");
        m.put("recipients", recipients);
        return m;
    }

    // ── Push geçmişim (kişinin kendi gözünden) ────────────────────────────────────────────────────────────────────

    /**
     * "Push bildirimlerim" sayfasını iki yönde zenginleştirir — TOPLU (sayfa başına sabit sorgu):
     * <ol>
     *   <li>Görünür {@code SKIPPED_STORM} karar satırı (takımın alarmı fırtınaya devredildi) → {@code storm_push}: kapsayan
     *       fırtına bildirimlerinde KİŞİNİN kendi satırı (zaman + durum) ya da neden almadığı (karar satırının nedeni /
     *       alıcı değildi / henüz gitmedi).</li>
     *   <li>Kişinin görünür fırtına bildirimi satırı → {@code storm_alarms}: kapsadığı alarmlardan kişinin görebildikleri
     *       (hedef adıyla, ilk {@value #VISIBLE_ALARM_CAP}), kalanı yalnız sayı ({@code hidden} = başka takımın alarmı).</li>
     * </ol>
     *
     * @param alarmVisible alarmın takımı → kişi görebilir mi (geçmişin bugünkü kuralı: üyelik ya da görüş kapsamı)
     */
    public void decorateViewerHistory(List<UserPushDelivery> rows, List<Map<String, Object>> out, String username,
                                      Predicate<Long> alarmVisible) {
        if (rows == null || out == null || rows.size() != out.size() || username == null) return;
        try {
            List<Integer> decisionIdx = new ArrayList<>();
            List<Integer> noticeIdx = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                UserPushDelivery d = rows.get(i);
                if (!Boolean.TRUE.equals(out.get(i).get("visible"))) continue;
                if (UserPushService.SYSTEM_USER.equals(d.getUsername()) && d.getAlertEventId() != null
                        && EscalationService.PUSH_SKIPPED_STORM.equals(d.getStatus())) decisionIdx.add(i);
                else if (parseKey(d.getDedupeKey()) != null) noticeIdx.add(i);
            }
            if (decisionIdx.isEmpty() && noticeIdx.isEmpty()) return;

            Map<Long, AlertEvent> events = new HashMap<>();
            Map<String, List<CoveredAlarm>> covered = Map.of();
            if (!noticeIdx.isEmpty()) {
                List<NoticeRef> refs = new ArrayList<>();
                for (int i : noticeIdx) { UserPushDelivery d = rows.get(i); refs.add(new NoticeRef(d.getDedupeKey(), d.getTeamId(), d.getCreatedAt())); }
                covered = alarmsForNotices(refs);
            }
            Set<Long> need = new LinkedHashSet<>();
            for (int i : decisionIdx) need.add(rows.get(i).getAlertEventId());
            for (List<CoveredAlarm> l : covered.values()) for (CoveredAlarm c : l) need.add(c.alertEventId());
            if (!need.isEmpty()) for (AlertEvent e : alertEventRepo.findAllById(need)) if (e.getId() != null) events.put(e.getId(), e);

            if (!decisionIdx.isEmpty()) {
                List<AlertEvent> alerts = new ArrayList<>();
                for (int i : decisionIdx) { AlertEvent e = events.get(rows.get(i).getAlertEventId()); if (e != null) alerts.add(e); }
                Result r = coverageForAlerts(alerts, null);
                Set<String> keys = new LinkedHashSet<>();
                for (AlarmCoverage c : r.byAlert().values()) for (PushRef p : c.pushes()) keys.add(p.pushKey());
                List<UserPushDelivery> viewerRows = keys.isEmpty() ? List.of()
                        : deliveryRepo.findStormNoticeRowsForViewer(keys, username);
                for (int i : decisionIdx) out.get(i).put("storm_push", viewerStormPush(r.of(rows.get(i).getAlertEventId()), viewerRows, username));
            }
            for (int i : noticeIdx) {
                UserPushDelivery d = rows.get(i);
                List<CoveredAlarm> list = covered.getOrDefault(noticeKey(d.getDedupeKey(), d.getTeamId()), List.of());
                out.get(i).put("storm_alarms", viewerStormAlarms(list, events, d.getTeamId(), alarmVisible));
            }
        } catch (Exception ex) {
            log.debug("Push geçmişi fırtına bağı kurulamadı: {}", ex.getMessage());
        }
    }

    /** Karar satırının kişiye özel fırtına push'u özeti. */
    static Map<String, Object> viewerStormPush(AlarmCoverage c, List<UserPushDelivery> viewerRows, String username) {
        List<Map<String, Object>> pushes = new ArrayList<>();
        boolean received = false, queued = false;
        String firstReason = null;
        for (PushRef p : c.pushes()) {
            UserPushDelivery own = null, system = null;
            for (UserPushDelivery d : viewerRows) {
                if (!p.pushKey().equals(d.getDedupeKey()) || (p.teamId() != null && !p.teamId().equals(d.getTeamId()))) continue;
                if (UserPushService.SYSTEM_USER.equals(d.getUsername())) { if (system == null) system = d; }
                else if (d.getUsername() != null && d.getUsername().equalsIgnoreCase(username) && own == null) own = d;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("storm_id", p.stormId());
            m.put("trigger", p.trigger());
            m.put("push_key", p.pushKey());
            m.put("inferred", p.inferred());
            m.put("delivery_id", own == null ? null : own.getId());
            m.put("status", own == null ? null : own.getStatus());
            m.put("at", own == null ? (system == null ? p.coveredAt() : system.getCreatedAt())
                    : (own.getSentAt() != null ? own.getSentAt() : own.getCreatedAt()));
            String reason = own != null ? ("SENT".equals(own.getStatus()) || "PENDING".equals(own.getStatus()) ? null : own.getStatus())
                    : system != null ? system.getStatus() : "NOT_RECIPIENT";
            m.put("reason", reason);
            if (own != null && "SENT".equals(own.getStatus())) received = true;
            if (own != null && "PENDING".equals(own.getStatus())) queued = true;
            if (firstReason == null && reason != null) firstReason = reason;
            pushes.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        String state;
        if (received) state = "received";
        else if (queued) state = "queued";
        else if (pushes.isEmpty() && !c.awaitingStorms().isEmpty()) state = "waiting";
        else state = "not_received";
        out.put("state", state);
        out.put("reason", "not_received".equals(state) ? (firstReason != null ? firstReason : "NO_STORM_PUSH") : null);
        out.put("pushes", pushes);
        out.put("awaiting_storm_ids", c.awaitingStorms());
        return out;
    }

    /** Fırtına bildirimi satırının kapsadığı alarmlardan kişinin görebildikleri (hedefli) + gizli sayı. */
    static Map<String, Object> viewerStormAlarms(List<CoveredAlarm> list, Map<Long, AlertEvent> events, Long noticeTeam,
                                                 Predicate<Long> alarmVisible) {
        List<Map<String, Object>> visible = new ArrayList<>();
        int visibleCount = 0, hidden = 0;
        boolean inferred = false;
        for (CoveredAlarm c : list) {
            inferred |= c.inferred();
            AlertEvent e = events.get(c.alertEventId());
            Long team = e != null && e.getTeamId() != null ? e.getTeamId() : noticeTeam;
            boolean ok = e != null && team != null && alarmVisible != null && alarmVisible.test(team);
            if (!ok) { hidden++; continue; }
            visibleCount++;
            if (visible.size() < VISIBLE_ALARM_CAP) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getId());
                m.put("target", e.getDomain());
                m.put("alert_type", e.getAlertType());
                m.put("resolved", Boolean.TRUE.equals(e.getResolved()));
                visible.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", list.size());
        out.put("visible_count", visibleCount);
        out.put("hidden", hidden);
        out.put("inferred", inferred);
        out.put("alarms", visible);
        return out;
    }

    // ── Teslimat günlüğü (yönetici) ───────────────────────────────────────────────────────────────────────────────

    /** Fırtına bildirimi satırının kapsadığı alarmlar (kapsam süzgeciyle); bildirim satırı değilse null. */
    public Map<String, Object> noticeCoverage(UserPushDelivery d, Predicate<Long> alarmTeamAllowed) {
        NoticeKey k = d == null ? null : parseKey(d.getDedupeKey());
        if (k == null) return null;
        List<CoveredAlarm> list = alarmsForNotices(List.of(new NoticeRef(d.getDedupeKey(), d.getTeamId(), d.getCreatedAt())))
                .getOrDefault(noticeKey(d.getDedupeKey(), d.getTeamId()), List.of());
        Map<Long, AlertEvent> events = new HashMap<>();
        if (!list.isEmpty()) {
            Set<Long> ids = new LinkedHashSet<>();
            for (CoveredAlarm c : list) ids.add(c.alertEventId());
            try { for (AlertEvent e : alertEventRepo.findAllById(ids)) if (e.getId() != null) events.put(e.getId(), e); }
            catch (Exception ignored) { /* alarm okunamadı → gizli sayılır */ }
        }
        List<Map<String, Object>> alarms = new ArrayList<>();
        int hidden = 0;
        boolean inferred = false;
        for (CoveredAlarm c : list) {
            inferred |= c.inferred();
            AlertEvent e = events.get(c.alertEventId());
            Long team = e != null && e.getTeamId() != null ? e.getTeamId() : d.getTeamId();
            if (e == null || alarmTeamAllowed == null || !alarmTeamAllowed.test(team)) { hidden++; continue; }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("domain", e.getDomain());
            m.put("alert_type", e.getAlertType());
            m.put("alert_level", e.getAlertLevel());
            m.put("resolved", Boolean.TRUE.equals(e.getResolved()));
            m.put("team_id", e.getTeamId());
            m.put("inferred", c.inferred());
            alarms.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("storm_id", k.stormId());
        out.put("trigger", k.trigger());
        out.put("day", k.day());
        out.put("total", list.size());
        out.put("hidden", hidden);
        out.put("inferred", inferred);
        out.put("alarms", alarms);
        return out;
    }

    /** Kapsayan bildirim + o bildirimin teslimat satırları (teslimat günlüğünde {@code SKIPPED_STORM} satırı için). */
    public record CoveringPush(PushRef ref, List<UserPushDelivery> rows) { }

    /** Alarmı kapsayan bildirimler (satırlarıyla) + henüz duyurulmadığı açık fırtınalar. */
    public record AlarmPushes(List<CoveringPush> pushes, List<Long> awaitingStorms) {
        static final AlarmPushes EMPTY = new AlarmPushes(List.of(), List.of());
    }

    /** {@code SKIPPED_STORM} karar satırının alarmını kapsayan fırtına bildirimleri + satırları (teslimat günlüğü). */
    public AlarmPushes coveringPushes(AlertEvent e) {
        if (e == null || e.getId() == null) return AlarmPushes.EMPTY;
        AlarmCoverage c = coverageForAlerts(List.of(e), null).of(e.getId());
        if (c.pushes().isEmpty()) return new AlarmPushes(List.of(), c.awaitingStorms());
        Set<String> keys = new LinkedHashSet<>();
        for (PushRef p : c.pushes()) keys.add(p.pushKey());
        List<UserPushDelivery> rows;
        try { rows = deliveryRepo.findByDedupeKeyInOrderByIdAsc(keys); } catch (Exception ex) { rows = List.of(); }
        List<CoveringPush> out = new ArrayList<>();
        for (PushRef p : c.pushes()) {
            List<UserPushDelivery> mine = new ArrayList<>();
            for (UserPushDelivery d : rows)
                if (p.pushKey().equals(d.getDedupeKey()) && (p.teamId() == null || p.teamId().equals(d.getTeamId()))) mine.add(d);
            out.add(new CoveringPush(p, mine));
        }
        return new AlarmPushes(out, c.awaitingStorms());
    }

    // ── Yardımcı sorgular ─────────────────────────────────────────────────────────────────────────────────────────

    /** Fırtınaların bildirim grupları (anahtar × takım × ilk satır anı) — LIKE önekleri tek cümlede, {@value #STORM_CHUNK}'lik parçalar. */
    private Map<Long, List<NoticeGroup>> noticeGroups(Collection<Long> stormIds) {
        Map<Long, List<NoticeGroup>> out = new HashMap<>();
        List<Long> ids = new ArrayList<>(stormIds);
        for (int i = 0; i < ids.size(); i += STORM_CHUNK) {
            List<Long> part = ids.subList(i, Math.min(ids.size(), i + STORM_CHUNK));
            StringBuilder where = new StringBuilder();
            List<Object> args = new ArrayList<>(part.size() * 2);
            for (Long id : part) {
                if (where.length() > 0) where.append(" OR ");
                where.append("dedupe_key LIKE ? OR dedupe_key = ?");
                args.add(KEY_PREFIX + id + ":%");
                args.add(RESOLVED_PREFIX + id);
            }
            for (Map<String, Object> r : jdbc.queryForList("SELECT dedupe_key, team_id, MIN(created_at) AS first_at "
                    + "FROM user_push_deliveries WHERE " + where + " GROUP BY dedupe_key, team_id", args.toArray())) {
                String key = str(r.get("dedupe_key"));
                NoticeKey k = parseKey(key);
                if (k == null) continue;
                out.computeIfAbsent(k.stormId(), x -> new ArrayList<>())
                        .add(new NoticeGroup(key, lngOrNull(r.get("team_id")), str(r.get("first_at"))));
            }
        }
        return out;
    }

    /** Kaydı olan bildirimler ({@link #noticeKey}) — bunlar için tahmin yapılmaz. */
    private Set<String> recordedNotices(Collection<Long> stormIds) {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> r : inQuery("SELECT DISTINCT push_key, team_id FROM storm_push_coverage WHERE storm_id IN ", stormIds))
            out.add(noticeKey(str(r.get("push_key")), lngOrNull(r.get("team_id"))));
        return out;
    }

    /** Bildirim başına kayıtlı kapsanan alarm sayısı ({@link #noticeKey}). */
    private Map<String, Long> recordedCounts(Collection<String> pushKeys) {
        Map<String, Long> out = new HashMap<>();
        try {
            List<String> keys = new ArrayList<>(pushKeys);
            for (int i = 0; i < keys.size(); i += IN_CHUNK) {
                List<String> part = keys.subList(i, Math.min(keys.size(), i + IN_CHUNK));
                for (Map<String, Object> r : jdbc.queryForList("SELECT push_key, team_id, COUNT(*) AS c FROM storm_push_coverage "
                        + "WHERE push_key IN (" + placeholders(part.size()) + ") GROUP BY push_key, team_id", part.toArray()))
                    out.put(noticeKey(str(r.get("push_key")), lngOrNull(r.get("team_id"))), lng(r.get("c")));
            }
        } catch (Exception ex) {
            log.debug("Fırtına push kapsam sayıları okunamadı: {}", ex.getMessage());
        }
        return out;
    }

    /** {@code prefix + "(?, ?, …)"} — {@value #IN_CHUNK}'lik parçalar, sonuçlar birleştirilir. */
    private List<Map<String, Object>> inQuery(String prefix, Collection<?> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<Object> list = new ArrayList<>(values);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += IN_CHUNK) {
            List<Object> part = list.subList(i, Math.min(list.size(), i + IN_CHUNK));
            List<Map<String, Object>> rows = jdbc.queryForList(prefix + "(" + placeholders(part.size()) + ")", part.toArray());
            if (rows != null) out.addAll(rows);
        }
        return out;
    }

    private static String placeholders(int n) { return String.join(",", Collections.nCopies(n, "?")); }

    private static Membership membership(Map<String, Object> r) {
        return new Membership(lng(r.get("storm_id")), lng(r.get("alert_event_id")), str(r.get("joined_at")),
                str(r.get("join_kind")), str(r.get("left_at")), str(r.get("leave_kind")));
    }

    private static long lng(Object o) {
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(o));
    }

    private static Long lngOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return Long.valueOf(n.longValue());
        try { return Long.valueOf(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static String min(String a, String b) {
        if (b == null) return a;
        if (a == null) return b;
        return cmp(a, b) <= 0 ? a : b;
    }

    private static String max(String a, String b) {
        if (b == null) return a;
        if (a == null) return b;
        return cmp(a, b) >= 0 ? a : b;
    }
}
