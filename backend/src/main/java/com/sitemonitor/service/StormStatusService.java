package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.AlertStormMember;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormMemberRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;

/**
 * Takım bazlı alarm fırtınası GÖZLEMİ (2026-09-30): "hangi takım eşiğe ne kadar yakın, açık fırtına var mı, kim/ne zaman
 * eşiği aştı, fırtına nasıl kapandı, geçmişte hangi takım kaç fırtına yaşadı" — hem kullanıcı sayfası ({@code ?tab=storms})
 * hem Ayarlar → Alarm Fırtınası canlı durum paneli buradan okur. Salt okunur; karar mantığı {@link StormService}'te.
 *
 * <p>Görüş kapsamı çağıranın verdiği {@code canViewTeam} yüklemiyle uygulanır (Alarm Geçmişi ile aynı kural: global
 * görüntüleyici / 7-24 operatörü hepsini, diğerleri yalnız görebildiği takımları görür). Takımsız eski fırtınalar
 * (kuruluş geneli ACCOUNT / grup) yalnız her şeyi görene listelenir.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StormStatusService {

    public static final String STATUS_STORM = "STORM";   // takımın açık fırtınası var
    public static final String STATUS_NEAR  = "NEAR";    // penceredeki düşük hedef sayısı eşiğin bir altında (ya da üstünde ama fırtına açılmamış)
    public static final String STATUS_WATCH = "WATCH";   // pencerede en az bir düşük hedef var
    public static final String STATUS_CALM  = "CALM";    // pencerede düşük hedef yok

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    static final int HISTORY_MAX_PAGE = 100;

    private final StormService stormService;
    private final AlertStormRepository stormRepo;
    private final AlertStormMemberRepository memberRepo;
    private final AlertEventRepository alertEventRepo;
    private final TeamRepository teamRepo;
    private final JdbcTemplate jdbcTemplate;

    // ── Durum ──────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Sunucu tarafı ezber (2026-10-01, performans): durum ekranı ve Ayarlar paneli 30 sn'de bir sorar; aynı görüş kapsamı
     * (her şeyi gören → "ALL", diğerleri sıralı görüş takımları) {@value #STATUS_MEMO_MS} ms içinde aynı yanıtı alır.
     * "Yenile" düğmesi {@code fresh=true} ile ezberi atlar. Pod yeniden başlayınca sıfırlanır (PublicStatsController deseni).
     */
    static final long STATUS_MEMO_MS = 10_000;
    private static final int STATUS_MEMO_MAX = 500;
    private final java.util.concurrent.ConcurrentHashMap<String, Object[]> statusMemo = new java.util.concurrent.ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public Map<String, Object> status(String scopeKey, Predicate<Long> canViewTeam, boolean seesAll, boolean fresh) {
        long nowMs = System.currentTimeMillis();
        if (!fresh && scopeKey != null) {
            Object[] hit = statusMemo.get(scopeKey);
            if (hit != null && nowMs - (long) hit[0] < STATUS_MEMO_MS) return (Map<String, Object>) hit[1];
        }
        Map<String, Object> out = status(canViewTeam, seesAll);
        if (scopeKey != null) {
            if (statusMemo.size() >= STATUS_MEMO_MAX) statusMemo.clear();   // sınırlı: kapsam çeşitliliği patlarsa sıfırla
            statusMemo.put(scopeKey, new Object[]{nowMs, out});
        }
        return out;
    }

    /** Takım başına canlı durum + açık fırtınalar (ezbersiz). */
    public Map<String, Object> status(Predicate<Long> canViewTeam, boolean seesAll) {
        String now = ISO.format(Instant.now());
        int window = stormService.windowMinutes();
        String since = ISO.format(Instant.now().minusSeconds(window * 60L));

        // Takımlar TEK sorguda: ad haritası + görüş kapsamındaki aktif takımlar (eskiden iki ayrı tam okuma).
        List<Team> allTeams = allTeams();
        Map<Long, String> names = new HashMap<>();
        for (Team t : allTeams) if (t.getId() != null) names.put(t.getId(), t.getName());
        List<Team> teams = new ArrayList<>();
        for (Team t : allTeams) if (t.getId() != null && !Boolean.FALSE.equals(t.getActive()) && canViewTeam.test(t.getId())) teams.add(t);
        teams.sort(java.util.Comparator.comparing(t -> t.getName() == null ? "" : t.getName(), String.CASE_INSENSITIVE_ORDER));
        Map<Long, Long> activeByTeam = stormService.activeMonitorsByTeam();   // 1 sorgu / 60 sn (eskiden takım × 10 COUNT)

        // Pencere içi açık DOWN alarmları takıma göre
        Map<Long, List<AlertEvent>> windowByTeam = new HashMap<>();
        for (AlertEvent e : alertEventRepo.findOpenDownSince(EscalationService.DOWN_ALERT_TYPES, since)) {
            if (e == null || e.getTeamId() == null) continue;
            windowByTeam.computeIfAbsent(e.getTeamId(), k -> new ArrayList<>()).add(e);
        }
        // Açık fırtınalar takıma göre (eski takımsız satırlar ayrı)
        List<AlertStorm> open = stormRepo.findByResolvedFalse();
        Map<Long, List<AlertStorm>> openByTeam = new HashMap<>();
        List<AlertStorm> legacyOpen = new ArrayList<>();
        for (AlertStorm s : open) {
            Long tid = teamOf(s);
            if (tid == null) legacyOpen.add(s); else openByTeam.computeIfAbsent(tid, k -> new ArrayList<>()).add(s);
        }
        // Son 30 gün: takım başına fırtına sayısı ve son açılış
        Map<Long, Object[]> recent = new HashMap<>();
        try {
            for (Object[] r : stormRepo.lastStormPerTeam(ISO.format(Instant.now().minus(Duration.ofDays(30)))))
                if (r != null && r[0] != null) recent.put(((Number) r[0]).longValue(), r);
        } catch (Exception e) { log.debug("Son fırtına özeti okunamadı: {}", e.getMessage()); }

        // Açık fırtınaların tetikleyenleri ve canlı üyeleri — İKİ sorgu toplam (eskiden fırtına başına 2).
        List<AlertStorm> shown = new ArrayList<>();
        for (Team t : teams) shown.addAll(openByTeam.getOrDefault(t.getId(), List.of()));
        if (seesAll) shown.addAll(legacyOpen);
        DtoCtx ctx = new DtoCtx(triggersOf(shown), liveOf(shown));

        List<Map<String, Object>> rows = new ArrayList<>();
        int storming = 0, near = 0;
        for (Team t : teams) {
            Long id = t.getId();
            long activeMonitors = activeByTeam.getOrDefault(id, 0L);
            int threshold = stormService.thresholdForTotal(activeMonitors);
            List<AlertEvent> win = windowByTeam.getOrDefault(id, List.of());
            int targets = StormService.distinctTargets(win);
            List<AlertStorm> storms = openByTeam.getOrDefault(id, List.of());
            String status = !storms.isEmpty() ? STATUS_STORM
                    : targets > 0 && targets >= threshold - 1 ? STATUS_NEAR
                    : targets > 0 ? STATUS_WATCH : STATUS_CALM;
            if (STATUS_STORM.equals(status)) storming++;
            if (STATUS_NEAR.equals(status)) near++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("team_id", id);
            row.put("team_name", t.getName());
            row.put("status", status);
            row.put("threshold", threshold);
            row.put("active_monitors", activeMonitors);
            row.put("window_minutes", window);
            row.put("window_targets", targets);
            row.put("window_alerts", win.size());
            row.put("window_items", windowItems(win));
            Object[] r = recent.get(id);
            row.put("last_storm_at", r != null ? r[1] : null);
            row.put("storms_30d", r != null && r[2] != null ? ((Number) r[2]).longValue() : 0L);
            List<Map<String, Object>> dto = new ArrayList<>();
            for (AlertStorm s : storms) dto.add(stormDto(s, names, now, ctx));
            row.put("storms", dto);
            rows.add(row);
        }

        List<Map<String, Object>> legacy = new ArrayList<>();
        if (seesAll) for (AlertStorm s : legacyOpen) legacy.add(stormDto(s, names, now, ctx));

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("enabled", stormService.isEnabled());
        settings.put("threshold_unit", stormService.thresholdUnit());
        settings.put("threshold_value", stormService.thresholdValue());
        settings.put("window_minutes", window);
        settings.put("quiet_minutes", stormService.quietMinutes());
        // Kural özeti (2026-10-01): sayfa açıklaması ve "Fırtına kuralları" kartı GERÇEK ayarlardan okunur
        settings.put("per_group", stormService.perGroup());
        settings.put("push_individual", stormService.pushIndividual());   // 2026-10-03: push alarm başına mı, toplu mu
        settings.put("re_alert_hours", StormService.RE_ALERT_HOURS);
        settings.put("min_threshold", StormService.MIN_THRESHOLD);
        settings.put("percent_min_targets", StormService.PERCENT_MIN_TARGETS);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("teams", rows.size());
        totals.put("storming", storming);
        totals.put("near", near);
        totals.put("open_storms", rows.stream().mapToInt(x -> ((List<?>) x.get("storms")).size()).sum() + legacy.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated_at", now);
        out.put("settings", settings);
        out.put("totals", totals);
        out.put("teams", rows);
        out.put("legacy_open", legacy);
        return out;
    }

    // ── Geçmiş ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Geçmiş fırtınalar (yeniden eskiye). {@code teamId} verilirse yalnız o takım (görüş kapsamında olmalı); verilmezse
     * görüş kapsamındaki takımlar; her şeyi gören için takımsız eski satırlar da listelenir.
     */
    public Map<String, Object> history(Predicate<Long> canViewTeam, boolean seesAll, List<Long> viewTeamIds,
                                       Long teamId, String from, String to, boolean onlyResolved, int page, int size) {
        int sz = Math.max(1, Math.min(HISTORY_MAX_PAGE, size));
        int pg = Math.max(0, page);
        boolean noFilter;
        Collection<Long> teamIds;
        if (teamId != null) {
            if (!canViewTeam.test(teamId)) return emptyPage(pg, sz);
            noFilter = false; teamIds = List.of(teamId);
        } else if (seesAll) {
            noFilter = true; teamIds = List.of(-1L);
        } else {
            List<Long> ids = new ArrayList<>();
            if (viewTeamIds != null) for (Long id : viewTeamIds) if (id != null && canViewTeam.test(id)) ids.add(id);
            if (ids.isEmpty()) return emptyPage(pg, sz);
            noFilter = false; teamIds = ids;
        }
        String since = dayStart(from), until = dayEnd(to);
        Page<AlertStorm> res = stormRepo.findHistory(noFilter, teamIds, since, until, onlyResolved, PageRequest.of(pg, sz));
        String now = ISO.format(Instant.now());
        Map<Long, String> names = teamNames();
        Map<Long, long[]> counts = memberCounts(res.getContent());
        DtoCtx ctx = new DtoCtx(triggersOf(res.getContent()), null);   // sayfa başına 1 sorgu (eskiden satır başına)
        List<Map<String, Object>> items = new ArrayList<>();
        for (AlertStorm s : res.getContent()) {
            Map<String, Object> d = stormDto(s, names, now, ctx);
            long[] c = counts.get(s.getId());
            d.put("members_total", c != null ? c[0] : (s.getMemberCount() != null ? s.getMemberCount() : 0));
            d.put("members_recovered", c != null ? c[1] : null);
            items.add(d);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", res.getTotalElements());
        out.put("page", pg);
        out.put("size", sz);
        out.put("total_pages", res.getTotalPages());
        return out;
    }

    private Map<String, Object> emptyPage(int pg, int sz) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", List.of()); out.put("total", 0L); out.put("page", pg); out.put("size", sz); out.put("total_pages", 0);
        return out;
    }

    // ── Ayrıntı ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Tek fırtına: anlık görüntü + üyeler (kalıcı üyelik tablosu ∪ hâlâ bağlı olaylar) + bildirim özeti. Kapsam dışı → null. */
    public Map<String, Object> detail(Long id, Predicate<Long> canViewTeam, boolean seesAll) {
        AlertStorm s = stormRepo.findById(id).orElse(null);
        if (s == null) return null;
        Long tid = teamOf(s);
        if (tid == null ? !seesAll : !canViewTeam.test(tid)) return null;
        String now = ISO.format(Instant.now());
        Map<Long, String> names = teamNames();
        boolean open = !Boolean.TRUE.equals(s.getResolved());
        Map<String, Object> d = stormDto(s, names, now, new DtoCtx(triggersOf(List.of(s)), open ? liveOf(List.of(s)) : null));

        // Üyeler: kalıcı tablo + (eski fırtınalar için) hâlâ storm_id ile bağlı olaylar. storm_id ile bağlı olanlar
        // bir kez okunur; yalnız kalıcı tabloda kalan (kapanışta bağı çözülmüş) olaylar ayrıca yüklenir.
        Map<Long, AlertStormMember> byEvent = new LinkedHashMap<>();
        try { for (AlertStormMember m : memberRepo.findByStormIdOrderByJoinedAtAsc(id)) byEvent.put(m.getAlertEventId(), m); }
        catch (Exception e) { log.debug("Storm #{} üyeleri okunamadı: {}", id, e.getMessage()); }
        Set<Long> ids = new LinkedHashSet<>(byEvent.keySet());
        Map<Long, AlertEvent> events = new HashMap<>();
        for (AlertEvent e : alertEventRepo.findByStormId(id)) if (e.getId() != null) { ids.add(e.getId()); events.put(e.getId(), e); }
        List<Long> missing = new ArrayList<>();
        for (Long eid : ids) if (!events.containsKey(eid)) missing.add(eid);
        if (!missing.isEmpty()) for (AlertEvent e : alertEventRepo.findAllById(missing)) events.put(e.getId(), e);

        List<Map<String, Object>> members = new ArrayList<>();
        int recovered = 0, down = 0;
        for (Long eid : ids) {
            AlertEvent e = events.get(eid);
            AlertStormMember m = byEvent.get(eid);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("event_id", eid);
            row.put("domain", e != null ? e.getDomain() : null);
            row.put("alert_type", e != null ? e.getAlertType() : null);
            row.put("alert_level", e != null ? e.getAlertLevel() : null);
            row.put("team_id", e != null ? e.getTeamId() : null);
            row.put("team_name", e != null && e.getTeamId() != null ? names.get(e.getTeamId()) : null);
            row.put("created_at", e != null ? e.getCreatedAt() : null);
            row.put("resolved", e != null && Boolean.TRUE.equals(e.getResolved()));
            row.put("resolved_at", e != null ? e.getResolvedAt() : null);
            row.put("acknowledged", e != null && Boolean.TRUE.equals(e.getAcknowledged()));
            row.put("join_kind", m != null ? m.getJoinKind() : null);
            row.put("joined_at", m != null ? m.getJoinedAt() : (e != null ? e.getCreatedAt() : null));
            row.put("announced_at", m != null ? m.getAnnouncedAt() : null);
            row.put("left_at", m != null ? m.getLeftAt() : null);
            row.put("leave_kind", m != null ? m.getLeaveKind() : null);
            row.put("trigger", s.getTriggerEventId() != null && s.getTriggerEventId().equals(eid));
            if (e != null && Boolean.TRUE.equals(e.getResolved())) recovered++; else if (e != null) down++;
            members.add(row);
        }
        d.put("members", members);
        d.put("members_total", members.size());
        d.put("members_recovered", recovered);
        d.put("members_down", down);
        Map<String, Object> notifications = notificationSummary(id, !ids.isEmpty());
        notifications.put("storm_pushes", stormPushes(s, ids, byEvent, events));   // 2026-10-04, ek alan
        d.put("notifications", notifications);
        return d;
    }

    /**
     * Fırtına push'ları (2026-10-04, ek alan): bildirim (anahtar × takım) başına tetik, ilk satır / ilk-son gönderim anı,
     * alıcı ve gönderilen sayısı, kanal kararı ve KAPSANAN alarm sayısı — {@code storm_push_coverage}'dan; kaydı olmayan
     * (özellikten önceki) bildirimde üyelik penceresinden tahmin ({@code inferred}, bkz. {@link StormPushCoverageService#infers}).
     * İki sorgu; hata yutulur (boş liste — ayrıntı penceresinin geri kalanı etkilenmez).
     */
    List<Map<String, Object>> stormPushes(AlertStorm s, Collection<Long> memberIds, Map<Long, AlertStormMember> byEvent,
                                          Map<Long, AlertEvent> events) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (s == null || s.getId() == null) return out;
        Long id = s.getId();
        try {
            Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT dedupe_key, team_id, username, status, created_at, sent_at FROM user_push_deliveries "
                  + "WHERE dedupe_key LIKE ? OR dedupe_key = ? ORDER BY id", "storm:" + id + ":%", "storm-resolved:" + id);
            if (rows == null || rows.isEmpty()) return out;
            for (Map<String, Object> r : rows) {
                String key = r.get("dedupe_key") == null ? null : String.valueOf(r.get("dedupe_key"));
                StormPushCoverageService.NoticeKey k = StormPushCoverageService.parseKey(key);
                if (k == null) continue;
                Long team = r.get("team_id") instanceof Number n ? Long.valueOf(n.longValue()) : null;
                Map<String, Object> g = groups.computeIfAbsent(StormPushCoverageService.noticeKey(key, team), x -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("push_key", key); m.put("trigger", k.trigger()); m.put("day", k.day()); m.put("team_id", team);
                    m.put("first_created_at", null); m.put("first_sent_at", null); m.put("last_sent_at", null);
                    m.put("recipients", 0); m.put("sent", 0); m.put("decision", null);
                    m.put("covered_alarms", 0L); m.put("inferred", false);
                    return m;
                });
                String created = r.get("created_at") == null ? null : String.valueOf(r.get("created_at"));
                if (created != null && (g.get("first_created_at") == null || created.compareTo((String) g.get("first_created_at")) < 0))
                    g.put("first_created_at", created);
                if (UserPushService.SYSTEM_USER.equals(r.get("username"))) {
                    if (g.get("decision") == null) g.put("decision", r.get("status"));
                    continue;
                }
                g.put("recipients", (Integer) g.get("recipients") + 1);
                if ("SENT".equals(r.get("status"))) {
                    g.put("sent", (Integer) g.get("sent") + 1);
                    String sent = r.get("sent_at") == null ? null : String.valueOf(r.get("sent_at"));
                    if (sent != null && (g.get("first_sent_at") == null || sent.compareTo((String) g.get("first_sent_at")) < 0)) g.put("first_sent_at", sent);
                    if (sent != null && (g.get("last_sent_at") == null || sent.compareTo((String) g.get("last_sent_at")) > 0)) g.put("last_sent_at", sent);
                }
            }
            Map<String, Long> recorded = new HashMap<>();
            for (Map<String, Object> r : jdbcTemplate.queryForList(
                    "SELECT push_key, team_id, COUNT(*) AS c FROM storm_push_coverage WHERE storm_id = ? GROUP BY push_key, team_id", id)) {
                Long team = r.get("team_id") instanceof Number n ? Long.valueOf(n.longValue()) : null;
                long c = r.get("c") instanceof Number n ? n.longValue() : 0L;
                recorded.put(StormPushCoverageService.noticeKey(String.valueOf(r.get("push_key")), team), c);
            }
            for (Map.Entry<String, Map<String, Object>> en : groups.entrySet()) {
                Map<String, Object> g = en.getValue();
                Long rec = recorded.get(en.getKey());
                if (rec != null) { g.put("covered_alarms", rec); continue; }
                // Kayıt öncesi bildirim: üyelik penceresinden tahmin (alarmın push takımı = bildirimin takımı).
                long n = 0;
                for (Long eid : memberIds) {
                    AlertEvent e = events.get(eid);
                    AlertStormMember am = byEvent.get(eid);
                    StormPushCoverageService.Membership m = am == null ? null : new StormPushCoverageService.Membership(
                            id, eid, am.getJoinedAt(), am.getJoinKind(), am.getLeftAt(), am.getLeaveKind());
                    Long team = StormPushCoverageService.alarmTeam(e, s, null);
                    if (team == null || !team.equals(g.get("team_id"))) continue;
                    if (StormPushCoverageService.infers((String) g.get("trigger"), (String) g.get("first_created_at"), m, e)) n++;
                }
                g.put("covered_alarms", n);
                g.put("inferred", true);
            }
            out.addAll(groups.values());
            out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("first_created_at"))));
        } catch (Exception e) {
            log.debug("Storm #{} push listesi okunamadı: {}", id, e.getMessage());
        }
        return out;
    }

    /** Fırtına postaları (üye alarm günlüklerinde STORM_* tetikleri), fırtınaya devir satırları ve fırtına push'ları. */
    /**
     * Üye alarm kümesi SABİT alt sorgu (2026-10-01): eskiden üye sayısı kadar {@code ?} ile IN listesi kuruluyordu —
     * her fırtınada farklı plan, büyük fırtınada bağ değişkeni sınırı. Şimdi iki bağ değişkeni, tek plan.
     */
    static final String MEMBER_EVENTS_SUBQUERY = "(SELECT alert_event_id FROM alert_storm_members WHERE storm_id = ? "
            + "UNION SELECT id FROM alert_events WHERE storm_id = ?)";

    private Map<String, Object> notificationSummary(Long stormId, boolean hasMembers) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("initial", 0L); out.put("realert", 0L); out.put("resolve", 0L); out.put("suppressed", 0L); out.put("push", 0L);
        out.put("last_mail_at", null);
        if (!hasMembers) return out;
        try {
            Object[] args = { stormId, stormId };
            for (Map<String, Object> r : jdbcTemplate.queryForList(
                    "SELECT trigger, COUNT(*) AS c, MAX(sent_at) AS last FROM notification_logs WHERE alert_event_id IN " + MEMBER_EVENTS_SUBQUERY + " "
                  + "AND trigger IN ('STORM_INITIAL','STORM_REALERT','STORM_RESOLVE') GROUP BY trigger", args)) {
                String trg = String.valueOf(r.get("trigger"));
                long c = ((Number) r.get("c")).longValue();
                switch (trg) {
                    case "STORM_INITIAL" -> out.put("initial", c);
                    case "STORM_REALERT" -> out.put("realert", c);
                    case "STORM_RESOLVE" -> out.put("resolve", c);
                    default -> { }
                }
                Object last = r.get("last");
                if (last != null && (out.get("last_mail_at") == null || String.valueOf(last).compareTo(String.valueOf(out.get("last_mail_at"))) > 0))
                    out.put("last_mail_at", String.valueOf(last));
            }
            Long sup = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM notification_logs WHERE alert_event_id IN " + MEMBER_EVENTS_SUBQUERY + " AND trigger = 'STORM' "
                  + "AND email_status LIKE ?", Long.class, stormId, stormId, "SKIPPED: f%rt%na #" + stormId + " %");
            out.put("suppressed", sup != null ? sup : 0L);
        } catch (Exception e) { log.debug("Storm #{} bildirim özeti okunamadı: {}", stormId, e.getMessage()); }
        try {
            Long push = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_push_deliveries WHERE dedupe_key LIKE ? OR dedupe_key = ?",
                    Long.class, "storm:" + stormId + ":%", "storm-resolved:" + stormId);
            out.put("push", push != null ? push : 0L);
        } catch (Exception e) { log.debug("Storm #{} push özeti okunamadı: {}", stormId, e.getMessage()); }
        // 2026-10-03: push fırtınaya devredilmeyince (varsayılan) üye alarmların push'u BİREYSEL gider — toplu push sayacı 0
        // kalır, ayrıntı penceresi "push gitmedi" gibi okunmasın diye üye alarmlara GİDEN (SENT) kişi push'ları ayrıca sayılır.
        out.put("push_members", 0L);
        try {
            Long members = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_push_deliveries WHERE alert_event_id IN " + MEMBER_EVENTS_SUBQUERY
                  + " AND username <> '-' AND status = 'SENT'", Long.class, stormId, stormId);
            out.put("push_members", members != null ? members : 0L);
        } catch (Exception e) { log.debug("Storm #{} üye push özeti okunamadı: {}", stormId, e.getMessage()); }
        return out;
    }

    // ── Analiz ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Son {@code days} günde açılan fırtınalar: gün serisi (takıma göre yığılı), takım özeti, kapanış nedeni / kök neden dağılımı. */
    public Map<String, Object> analytics(Predicate<Long> canViewTeam, boolean seesAll, Long teamId, int days) {
        int d = Math.max(1, Math.min(365, days));
        LocalDate today = LocalDate.now(ISTANBUL);
        LocalDate first = today.minusDays(d - 1L);
        String since = ISO.format(first.atStartOfDay(ISTANBUL).toInstant());
        Map<Long, String> names = teamNames();
        String now = ISO.format(Instant.now());

        List<AlertStorm> storms = new ArrayList<>();
        List<AlertStorm> window = teamId != null
                ? stormRepo.findByTeamIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(teamId, since)   // eski takımsız satırlar takım süzgecine zaten girmez
                : stormRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(since);
        for (AlertStorm s : window) {
            Long tid = teamOf(s);
            if (teamId != null && !teamId.equals(tid)) continue;
            if (tid == null ? !seesAll : !canViewTeam.test(tid)) continue;
            storms.add(s);
        }

        // Gün kovaları (İstanbul takvimi) — takıma göre yığılı
        Map<LocalDate, Map<Long, Integer>> perDay = new TreeMap<>();
        for (int i = 0; i < d; i++) perDay.put(first.plusDays(i), new LinkedHashMap<>());
        Map<Long, List<AlertStorm>> byTeam = new LinkedHashMap<>();
        Map<String, Integer> reasons = new LinkedHashMap<>();
        Map<String, Integer> rootCauses = new LinkedHashMap<>();
        int[] hours = new int[24];
        long durSum = 0; int durN = 0; long memberSum = 0; int memberN = 0; int sealed = 0;
        for (AlertStorm s : storms) {
            Instant at = parse(s.getCreatedAt());
            if (at != null) {
                LocalDateTime local = LocalDateTime.ofInstant(at, ISTANBUL);
                Map<Long, Integer> bucket = perDay.get(local.toLocalDate());
                if (bucket != null) bucket.merge(teamOf(s) != null ? teamOf(s) : -1L, 1, Integer::sum);
                hours[local.getHour()]++;
            }
            byTeam.computeIfAbsent(teamOf(s) != null ? teamOf(s) : -1L, k -> new ArrayList<>()).add(s);
            String reason = Boolean.TRUE.equals(s.getResolved()) ? (s.getResolveReason() != null ? s.getResolveReason() : "UNKNOWN") : "OPEN";
            reasons.merge(reason, 1, Integer::sum);
            if (StormService.RESOLVE_SEALED.equals(s.getResolveReason())) sealed++;
            rootCauses.merge(s.getRootCause() != null ? s.getRootCause() : "MIXED", 1, Integer::sum);
            Long dur = durationMs(s, now);
            if (dur != null && Boolean.TRUE.equals(s.getResolved())) { durSum += dur; durN++; }
            if (s.getMemberCount() != null) { memberSum += s.getMemberCount(); memberN++; }
        }

        List<Map<String, Object>> series = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<Long, Integer>> e : perDay.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", e.getKey().toString());
            row.put("total", e.getValue().values().stream().mapToInt(Integer::intValue).sum());
            Map<String, Integer> bt = new LinkedHashMap<>();
            for (Map.Entry<Long, Integer> t : e.getValue().entrySet()) bt.put(String.valueOf(t.getKey()), t.getValue());
            row.put("by_team", bt);
            series.add(row);
        }
        List<Map<String, Object>> teams = new ArrayList<>();
        for (Map.Entry<Long, List<AlertStorm>> e : byTeam.entrySet()) {
            List<AlertStorm> ls = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("team_id", e.getKey() < 0 ? null : e.getKey());
            row.put("team_name", e.getKey() < 0 ? null : names.get(e.getKey()));
            row.put("storms", ls.size());
            row.put("open", ls.stream().filter(x -> !Boolean.TRUE.equals(x.getResolved())).count());
            row.put("sealed", ls.stream().filter(x -> StormService.RESOLVE_SEALED.equals(x.getResolveReason())).count());
            row.put("floor", ls.stream().filter(x -> StormService.RESOLVE_FLOOR.equals(x.getResolveReason())).count());
            long ds = 0; int dn = 0; int peak = 0; long ms = 0; int mn = 0;
            for (AlertStorm x : ls) {
                Long dur = durationMs(x, now);
                if (dur != null && Boolean.TRUE.equals(x.getResolved())) { ds += dur; dn++; }
                if (x.getPeakTargets() != null) peak = Math.max(peak, x.getPeakTargets());
                if (x.getMemberCount() != null) { ms += x.getMemberCount(); mn++; }
            }
            row.put("avg_duration_ms", dn > 0 ? ds / dn : null);
            row.put("avg_members", mn > 0 ? Math.round(ms * 10.0 / mn) / 10.0 : null);
            row.put("max_peak_targets", peak);
            row.put("last_storm_at", ls.isEmpty() ? null : ls.get(0).getCreatedAt());
            teams.add(row);
        }
        teams.sort((a, b) -> Integer.compare((int) b.get("storms"), (int) a.get("storms")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("from", first.toString());
        out.put("to", today.toString());
        out.put("total", storms.size());
        out.put("open", storms.stream().filter(x -> !Boolean.TRUE.equals(x.getResolved())).count());
        out.put("sealed", sealed);
        out.put("avg_duration_ms", durN > 0 ? durSum / durN : null);
        out.put("avg_members", memberN > 0 ? Math.round(memberSum * 10.0 / memberN) / 10.0 : null);
        out.put("series", series);
        out.put("teams", teams);
        out.put("reasons", reasons);
        out.put("root_causes", rootCauses);
        out.put("hours", hours);
        List<Map<String, Object>> recent = new ArrayList<>();
        List<AlertStorm> recentStorms = storms.subList(0, Math.min(10, storms.size()));
        DtoCtx ctx = new DtoCtx(triggersOf(recentStorms), null);
        for (AlertStorm s : recentStorms) recent.add(stormDto(s, names, now, ctx));
        out.put("recent", recent);
        return out;
    }

    // ── Yardımcılar ────────────────────────────────────────────────────────────────────────────────────────────────

    /** Fırtına DTO'su: satır + anlık görüntü + türetilmiş alanlar (mühür, süre, sonraki tekrar). {@code live} → hâlâ bağlı üyelerden düşük hedef sayısı. */
    /** DTO bağlamı — tetikleyenler ve (canlı ise) fırtına başına üyeler ÖNCEDEN toplu yüklenir; {@code live == null} → canlı alan yok. */
    record DtoCtx(Map<Long, AlertEvent> triggers, Map<Long, List<AlertEvent>> live) {}

    private Map<Long, AlertEvent> triggersOf(java.util.Collection<AlertStorm> storms) {
        Set<Long> ids = new LinkedHashSet<>();
        for (AlertStorm s : storms) if (s.getTriggerEventId() != null) ids.add(s.getTriggerEventId());
        Map<Long, AlertEvent> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        try { for (AlertEvent e : alertEventRepo.findAllById(ids)) if (e.getId() != null) out.put(e.getId(), e); }
        catch (Exception e) { log.debug("Tetikleyen alarmlar okunamadı: {}", e.getMessage()); }
        return out;
    }

    /** Açık fırtınaların üyeleri dar izdüşümle, TEK sorgu → hafif AlertEvent (yalnız hedef/tür/seviye/durum). */
    private Map<Long, List<AlertEvent>> liveOf(java.util.Collection<AlertStorm> storms) {
        Set<Long> ids = new LinkedHashSet<>();
        for (AlertStorm s : storms) if (s.getId() != null) ids.add(s.getId());
        Map<Long, List<AlertEvent>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        try {
            for (Object[] r : stormRepo.liveMembers(ids)) {
                if (r == null || r[0] == null) continue;
                AlertEvent e = new AlertEvent();
                e.setStormId(((Number) r[0]).longValue());
                e.setId(r[1] != null ? ((Number) r[1]).longValue() : null);
                e.setDomain((String) r[2]); e.setAlertType((String) r[3]); e.setAlertLevel((String) r[4]);
                e.setCreatedAt((String) r[5]); e.setResolved(Boolean.TRUE.equals(r[6]));
                out.computeIfAbsent(e.getStormId(), k -> new ArrayList<>()).add(e);
            }
        } catch (Exception e) { log.debug("Açık fırtına üyeleri okunamadı: {}", e.getMessage()); }
        return out;
    }

    Map<String, Object> stormDto(AlertStorm s, Map<Long, String> names, String now, DtoCtx ctx) {
        Map<String, Object> d = new LinkedHashMap<>();
        Long tid = teamOf(s);
        d.put("id", s.getId());
        d.put("team_id", tid);
        d.put("team_name", tid != null ? names.get(tid) : null);
        d.put("group_name", s.getGroupName() != null ? s.getGroupName() : groupOf(s));
        d.put("scope_key", s.getScopeKey());
        d.put("scope_type", s.getScopeType());
        d.put("legacy", !StormService.isTeamScoped(s));
        d.put("resolved", Boolean.TRUE.equals(s.getResolved()));
        d.put("created_at", s.getCreatedAt());
        d.put("resolved_at", s.getResolvedAt());
        d.put("resolve_reason", s.getResolveReason());
        d.put("duration_ms", durationMs(s, now));
        d.put("root_cause", s.getRootCause());
        d.put("member_count", s.getMemberCount());
        d.put("notified_teams", s.getNotifiedTeams());
        d.put("threshold_unit", s.getThresholdUnit());
        d.put("threshold_value", s.getThresholdValue());
        d.put("threshold_effective", s.getThresholdEffective());
        d.put("window_minutes", s.getWindowMinutes());
        d.put("quiet_minutes", s.getQuietMinutes());
        d.put("targets_at_open", s.getTargetsAtOpen());
        d.put("peak_targets", s.getPeakTargets());
        d.put("trigger_event_id", s.getTriggerEventId());
        d.put("legacy_storm_id", s.getLegacyStormId());
        d.put("last_member_at", s.getLastMemberAt() != null ? s.getLastMemberAt() : s.getCreatedAt());
        d.put("last_re_alert_at", s.getLastReAlertAt());
        boolean open = !Boolean.TRUE.equals(s.getResolved());
        int quiet = open || s.getQuietMinutes() == null ? stormService.quietMinutes() : s.getQuietMinutes();   // mühür açık fırtınada GÜNCEL ayarla ölçülür
        Instant clock = parse(s.getLastMemberAt() != null ? s.getLastMemberAt() : s.getCreatedAt());
        d.put("seal_at", clock != null ? ISO.format(clock.plusSeconds(quiet * 60L)) : null);
        d.put("sealed", open && stormService.isSealed(s, now));
        Instant last = parse(s.getLastReAlertAt() != null ? s.getLastReAlertAt() : s.getCreatedAt());
        d.put("next_re_alert_at", open && last != null ? ISO.format(last.plusSeconds(24 * 3600L)) : null);
        AlertEvent trg = s.getTriggerEventId() != null && ctx != null && ctx.triggers() != null ? ctx.triggers().get(s.getTriggerEventId()) : null;
        if (trg != null) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", trg.getId()); t.put("domain", trg.getDomain()); t.put("alert_type", trg.getAlertType());
            t.put("alert_level", trg.getAlertLevel()); t.put("created_at", trg.getCreatedAt());
            t.put("resolved", Boolean.TRUE.equals(trg.getResolved()));
            d.put("trigger", t);
        }
        if (ctx != null && ctx.live() != null) {
            List<AlertEvent> members = ctx.live().getOrDefault(s.getId(), List.of());
            List<AlertEvent> down = members.stream().filter(m -> !Boolean.TRUE.equals(m.getResolved())).toList();
            d.put("active_down", StormService.distinctTargets(down));
            d.put("active_members", down.size());
            d.put("recovered_members", members.size() - down.size());
            int threshold = s.getThresholdEffective() != null ? s.getThresholdEffective()
                    : stormService.thresholdForTotal(tid != null ? stormService.activeMonitorsByTeam().getOrDefault(tid, 0L) : 0L);
            d.put("resolve_floor", Math.max(2, (threshold + 1) / 2));
            d.put("active_items", windowItems(down));
        }
        return d;
    }

    private static List<Map<String, Object>> windowItems(List<AlertEvent> events) {
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        for (AlertEvent e : events) {
            if (e == null) continue;
            if (n++ >= 25) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId()); m.put("domain", e.getDomain()); m.put("alert_type", e.getAlertType());
            m.put("alert_level", e.getAlertLevel()); m.put("created_at", e.getCreatedAt());
            m.put("storm_id", e.getStormId());
            out.add(m);
        }
        return out;
    }

    private Map<Long, long[]> memberCounts(List<AlertStorm> storms) {
        Map<Long, long[]> out = new HashMap<>();
        List<Long> ids = new ArrayList<>();
        for (AlertStorm s : storms) if (s.getId() != null) ids.add(s.getId());
        if (ids.isEmpty()) return out;
        try {
            for (Object[] r : memberRepo.countByStorms(ids)) {
                if (r == null || r[0] == null) continue;
                out.put(((Number) r[0]).longValue(), new long[]{ r[1] != null ? ((Number) r[1]).longValue() : 0L, r[2] != null ? ((Number) r[2]).longValue() : 0L });
            }
        } catch (Exception e) { log.debug("Fırtına üye sayıları okunamadı: {}", e.getMessage()); }
        return out;
    }

    private List<Team> allTeams() {
        try { return teamRepo.findAll(); }
        catch (Exception e) { log.debug("Takımlar okunamadı: {}", e.getMessage()); return List.of(); }
    }

    private Map<Long, String> teamNames() {
        Map<Long, String> names = new HashMap<>();
        for (Team t : allTeams()) if (t.getId() != null) names.put(t.getId(), t.getName());
        return names;
    }

    static Long teamOf(AlertStorm s) {
        if (s.getTeamId() != null) return s.getTeamId();
        return StormService.teamOfScope(s.getScopeKey());
    }

    private static String groupOf(AlertStorm s) {
        String k = s.getScopeKey();
        if (k == null) return null;
        int i = k.indexOf(StormService.GROUP_SCOPE_SEP);
        return i < 0 ? null : k.substring(i + StormService.GROUP_SCOPE_SEP.length());
    }

    static Long durationMs(AlertStorm s, String now) {
        Instant a = parse(s.getCreatedAt());
        Instant b = parse(Boolean.TRUE.equals(s.getResolved()) && s.getResolvedAt() != null ? s.getResolvedAt() : now);
        if (a == null || b == null) return null;
        return Math.max(0, Duration.between(a, b).toMillis());
    }

    static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return LocalDateTime.parse(iso.length() > 19 ? iso.substring(0, 19) : iso).toInstant(ZoneOffset.UTC); }
        catch (Exception e) { return null; }
    }

    /** Tarih (yyyy-MM-dd, İstanbul günü) → UTC ISO gün başı; boş → null. */
    static String dayStart(String day) {
        if (day == null || day.isBlank()) return null;
        try { return ISO.format(LocalDate.parse(day.trim()).atStartOfDay(ISTANBUL).toInstant()); } catch (Exception e) { return null; }
    }

    static String dayEnd(String day) {
        if (day == null || day.isBlank()) return null;
        try { return ISO.format(LocalDate.parse(day.trim()).plusDays(1).atStartOfDay(ISTANBUL).toInstant().minusSeconds(1)); } catch (Exception e) { return null; }
    }
}
