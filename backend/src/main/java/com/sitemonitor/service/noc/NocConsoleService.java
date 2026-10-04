package com.sitemonitor.service.noc;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.NocCallLog;
import com.sitemonitor.model.NocDelivery;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.NocCallLogRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.util.TtlMemo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * 7/24 KONSOLU (2026-10-04, kullanıcı isteği: "tüm şirkette ne tür alarmlar, ne tür bildirimler var; bildirimleri direkt
 * görüp ilgili takımların aranmasını sağlamalı; giden bildirimlerin 7/24'e de gittiğini gözlemleyebilmeli; 7/24'e giden
 * alarmları filtreleyebilmeli").
 *
 * <h2>Veri</h2>
 * Kurum geneli: AÇIK alarmların tamamı + pencerede (1 sa / 24 sa / 7 g) açılanlar, en yeni önce, en çok {@link #MAX_ROWS}.
 * Her satır: sahibi takım, izleme (ad + tür + derin bağlantı), seviye, tip, kanal özeti (takım e-postası / webhook / push
 * / 7/24), 7/24'e iletim anı, son arama (kim arandı, ne zaman, sonuç, kim girdi) + arama sayısı.
 *
 * <h2>Maliyet</h2>
 * Satır başına sorgu YOK: alarmlar tek sorgu; 7/24 teslimi, son arama, arama sayısı, e-posta/webhook sayıları, push
 * sayıları, envanter ve takımlar KAYNAK BAŞINA tek sorgu (500'lük parçalar); izleme adları tek dizin anlık görüntüsüyle
 * (tür başına bir tablo okuması). Ham küme pencere başına {@code site.monitor.noc.console-cache-ms} (15 sn) bellekte —
 * konsol kurum genelidir, görüntüleyiciye göre süzülmez (yalnız tüm alarmları görebilene açık), dolayısıyla tek kopya
 * herkese hizmet eder. Süzgeç/sayfalama bellekteki küme üzerinde. {@code fresh} belleği en çok 5 sn'de bir atlar.
 */
@Slf4j
@Service
public class NocConsoleService {

    public static final int MAX_ROWS = 1000;
    static final int CHUNK = 500;
    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;
    static final long FRESH_MIN_GAP_MS = 5_000;
    public static final Map<String, Duration> WINDOWS = Map.of(
            "1h", Duration.ofHours(1), "24h", Duration.ofHours(24), "7d", Duration.ofDays(7));
    public static final String DEFAULT_WINDOW = "24h";
    static final List<String> LEVELS = List.of("CRITICAL", "HIGH", "WARNING");

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final AlertEventRepository alertRepo;
    private final NocAlertFacts nocFacts;
    private final NocCallLogRepository callRepo;
    private final NotificationLogRepository notificationLogRepo;
    private final UserPushDeliveryRepository pushRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final TeamRepository teamRepo;
    private final NocMonitorDirectory directory;

    @Value("${site.monitor.noc.console-cache-ms:15000}")
    long cacheMs = 15_000;

    LongSupplier clock = System::currentTimeMillis;
    private final TtlMemo<Base> memo;
    private final Map<String, Long> lastFresh = new java.util.concurrent.ConcurrentHashMap<>();

    public NocConsoleService(AlertEventRepository alertRepo, NocAlertFacts nocFacts, NocCallLogRepository callRepo,
                             NotificationLogRepository notificationLogRepo, UserPushDeliveryRepository pushRepo,
                             CertificateInventoryRepository inventoryRepo, TeamRepository teamRepo,
                             NocMonitorDirectory directory) {
        this.alertRepo = alertRepo;
        this.nocFacts = nocFacts;
        this.callRepo = callRepo;
        this.notificationLogRepo = notificationLogRepo;
        this.pushRepo = pushRepo;
        this.inventoryRepo = inventoryRepo;
        this.teamRepo = teamRepo;
        this.directory = directory;
        this.memo = new TtlMemo<>(8, () -> clock.getAsLong());
    }

    /** Süzgeçler (hepsi isteğe bağlı). {@code noc}: sent | not_sent · {@code called}: yes | no · {@code state}: open | resolved. */
    public record Query(String window, Long teamId, String level, String type, String noc, String called, String state,
                        String q, int page, int size, boolean fresh) {}

    /** Süzgece hazır satır: istemciye giden DTO + süzgeç anahtarları. */
    record Item(Map<String, Object> dto, Long teamId, Long ugTeamId, String level, String family, boolean open,
                boolean nocSent, long calls, String search, String createdAt, long id) {}

    /** Bellekteki ham küme. */
    record Base(List<Item> items, boolean truncated, long calledLastHour, String generatedAt) {}

    // ── Uç ───────────────────────────────────────────────────────────────────

    public Map<String, Object> console(Query in, boolean canWrite) {
        // Map.of null anahtarda NPE atar — parametresiz istek (varsayılan pencere) buraya null getirir.
        String window = in.window() != null && WINDOWS.containsKey(in.window().trim()) ? in.window().trim() : DEFAULT_WINDOW;
        Base base = base(window, in.fresh());

        List<Item> all = base.items();
        List<Item> filtered = new ArrayList<>();
        for (Item it : all) if (matches(it, in)) filtered.add(it);
        filtered.sort(PRIORITY);

        int size = in.size() <= 0 ? DEFAULT_SIZE : Math.min(in.size(), MAX_SIZE);
        int pages = Math.max(1, (filtered.size() + size - 1) / size);
        int page = Math.max(0, Math.min(in.page(), pages - 1));
        List<Map<String, Object>> pageItems = new ArrayList<>();
        for (Item it : filtered.subList(Math.min(filtered.size(), page * size), Math.min(filtered.size(), (page + 1) * size))) {
            Map<String, Object> row = new LinkedHashMap<>(it.dto());   // paylaşılan bellek kopyası değişmesin
            row.put("can_call", canWrite);
            pageItems.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", window);
        out.put("generated_at", base.generatedAt());
        out.put("kpis", kpis(all, base.calledLastHour()));
        out.put("facets", facets(all));
        out.put("items", pageItems);
        out.put("total", filtered.size());
        out.put("page", page);
        out.put("size", size);
        out.put("truncated", base.truncated());
        out.put("max_rows", MAX_ROWS);
        out.put("can_write", canWrite);
        return out;
    }

    /** Öncelik: açık + 7/24'e gitmiş + aranmamış → açık → kapalı; her grupta en yeni önce. */
    static final Comparator<Item> PRIORITY = Comparator
            .comparingInt((Item it) -> it.open() ? (it.nocSent() && it.calls() == 0 ? 0 : 1) : 2)
            .thenComparing(Item::createdAt, Comparator.nullsLast(Comparator.<String>reverseOrder()))
            .thenComparing(Item::id, Comparator.<Long>reverseOrder());

    static boolean matches(Item it, Query in) {
        if (in.teamId() != null && !in.teamId().equals(it.teamId()) && !in.teamId().equals(it.ugTeamId())) return false;
        if (notBlank(in.level()) && !in.level().trim().equalsIgnoreCase(it.level())) return false;
        if (notBlank(in.type()) && !in.type().trim().equalsIgnoreCase(it.family())) return false;
        if ("sent".equalsIgnoreCase(trim(in.noc())) && !it.nocSent()) return false;
        if ("not_sent".equalsIgnoreCase(trim(in.noc())) && it.nocSent()) return false;
        if ("yes".equalsIgnoreCase(trim(in.called())) && it.calls() == 0) return false;
        if ("no".equalsIgnoreCase(trim(in.called())) && it.calls() > 0) return false;
        if ("open".equalsIgnoreCase(trim(in.state())) && !it.open()) return false;
        if ("resolved".equalsIgnoreCase(trim(in.state())) && it.open()) return false;
        if (notBlank(in.q())) {
            String needle = fold(in.q().trim());
            if (it.search() == null || !it.search().contains(needle)) return false;
        }
        return true;
    }

    static Map<String, Object> kpis(List<Item> all, long calledLastHour) {
        long open = 0, nocSent = 0, notCalled = 0, openCritical = 0;
        for (Item it : all) {
            if (it.open()) open++;
            if (it.nocSent()) nocSent++;
            if (it.open() && it.nocSent() && it.calls() == 0) notCalled++;
            if (it.open() && "CRITICAL".equalsIgnoreCase(it.level())) openCritical++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("open", open);
        m.put("open_critical", openCritical);
        m.put("noc_sent", nocSent);
        m.put("not_called", notCalled);
        m.put("called_last_hour", calledLastHour);
        return m;
    }

    static Map<String, Object> facets(List<Item> all) {
        Map<Long, long[]> teams = new HashMap<>();
        Map<Long, String> teamNames = new HashMap<>();
        Map<String, Long> types = new TreeMap<>();
        Map<String, Long> levels = new LinkedHashMap<>();
        for (String l : LEVELS) levels.put(l, 0L);
        for (Item it : all) {
            if (it.teamId() != null) {
                teams.computeIfAbsent(it.teamId(), k -> new long[1])[0]++;
                Object n = it.dto().get("team_name");
                if (n != null) teamNames.put(it.teamId(), n.toString());
            }
            if (it.family() != null) types.merge(it.family(), 1L, Long::sum);
            if (it.level() != null) levels.merge(it.level().toUpperCase(Locale.ROOT), 1L, Long::sum);
        }
        List<Map<String, Object>> teamList = new ArrayList<>();
        teams.forEach((id, c) -> {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", id);
            t.put("name", teamNames.getOrDefault(id, "#" + id));
            t.put("count", c[0]);
            teamList.add(t);
        });
        teamList.sort(Comparator.comparing((Map<String, Object> t) -> String.valueOf(t.get("name")).toLowerCase(Locale.forLanguageTag("tr"))));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("teams", teamList);
        m.put("types", types);
        m.put("levels", levels);
        return m;
    }

    // ── Ham küme ─────────────────────────────────────────────────────────────

    Base base(String window, boolean fresh) {
        String key = "W:" + window;
        boolean bypass = false;
        if (fresh) {
            long now = clock.getAsLong();
            Long last = lastFresh.get(key);
            if (last == null || now - last >= FRESH_MIN_GAP_MS) {
                bypass = true;
                lastFresh.put(key, now);
            }
        }
        return memo.get(key, cacheMs, bypass, () -> build(window));
    }

    Base build(String window) {
        long nowMs = clock.getAsLong();
        Instant now = Instant.ofEpochMilli(nowMs);
        String since = ISO.format(now.minus(WINDOWS.get(window)));
        List<AlertEvent> events = alertRepo.findOpenOrCreatedSince(since, PageRequest.of(0, MAX_ROWS + 1));
        boolean truncated = events.size() > MAX_ROWS;
        if (truncated) events = new ArrayList<>(events.subList(0, MAX_ROWS));

        List<Long> ids = new ArrayList<>();
        Set<String> domains = new LinkedHashSet<>();
        for (AlertEvent e : events) {
            if (e.getId() != null) ids.add(e.getId());
            if (e.getDomain() != null) domains.add(e.getDomain());
        }

        Map<Long, NocDelivery> noc = nocFacts.openDeliveries(ids);
        Map<Long, NocCallLog> lastCall = latestCalls(ids);
        Map<Long, Long> callCounts = callCounts(ids);
        Map<Long, long[]> mail = mailCounts(ids);
        Map<Long, long[]> push = pushCounts(ids);
        Map<String, CertificateInventory> inv = inventory(domains);
        Map<Long, String> teamNames = new HashMap<>();
        try {
            for (Team t : teamRepo.findAll()) if (t.getId() != null) teamNames.put(t.getId(), t.getName());
        } catch (Exception ex) {
            log.debug("7/24 konsolu: takım adları okunamadı: {}", ex.toString());
        }
        NocMonitorDirectory.Snapshot snap = directory.snapshot();

        List<Item> items = new ArrayList<>(events.size());
        for (AlertEvent e : events) {
            if (e.getId() == null) continue;
            items.add(item(e, snap, inv.get(lower(e.getDomain())), noc.get(e.getId()), lastCall.get(e.getId()),
                    callCounts.getOrDefault(e.getId(), 0L), mail.get(e.getId()), push.get(e.getId()), teamNames));
        }

        long calledLastHour = 0;
        try {
            calledLastHour = callRepo.countByContactedAtGreaterThanEqual(ISO.format(now.minus(Duration.ofHours(1))));
        } catch (Exception ex) {
            log.debug("7/24 konsolu: son bir saatin arama sayısı alınamadı: {}", ex.toString());
        }
        return new Base(List.copyOf(items), truncated, calledLastHour, ISO.format(now));
    }

    private Item item(AlertEvent e, NocMonitorDirectory.Snapshot snap, CertificateInventory inv, NocDelivery d,
                      NocCallLog call, long calls, long[] mail, long[] push, Map<Long, String> teamNames) {
        NocType nt = NocType.forAlertType(e.getAlertType());
        NocMonitorDirectory.Row row = null;
        try {
            row = nt == null ? null : snap.forAlert(nt, e.getDomain(), NocNotificationService.monitorIdOf(e.getContextJson()));
        } catch (Exception ex) {
            log.debug("7/24 konsolu: izleme çözülemedi ({}): {}", e.getId(), ex.toString());
        }
        // Sahibi takım: damgalı teamId → izleme satırının etkin takımı → envanter SY → UG (NocCallLogService.owningTeam sırası)
        Long team = e.getTeamId();
        if (team == null && row != null) team = row.teamId();
        if (team == null && inv != null) team = inv.getTeamId() != null ? inv.getTeamId() : inv.getUgTeamId();
        Long ug = inv != null ? inv.getUgTeamId() : (row != null ? row.ugTeamId() : null);
        if (ug != null && ug.equals(team)) ug = null;

        String family = MonitorTypeCatalog.typeOfAlert(e.getAlertType());
        if (family == null) family = "other";
        boolean open = !Boolean.TRUE.equals(e.getResolved());
        boolean nocSent = d != null && NocAlertFacts.sent(d.getStatus());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("alert_type", e.getAlertType());
        m.put("family", family);
        m.put("level", e.getAlertLevel());
        m.put("noc_level", NocNotificationService.nocLevel(e.getAlertType(), e.getAlertLevel()));
        m.put("domain", e.getDomain());
        m.put("message", clip(e.getMessage(), 300));
        m.put("created_at", e.getCreatedAt());
        m.put("resolved", !open);
        m.put("resolved_at", e.getResolvedAt());
        m.put("acknowledged", Boolean.TRUE.equals(e.getAcknowledged()));
        m.put("acknowledged_at", e.getAcknowledgedAt());
        m.put("storm_id", e.getStormId());
        m.put("team_id", team);
        m.put("team_name", team == null ? null : teamNames.get(team));
        m.put("ug_team_id", ug);
        m.put("ug_team_name", ug == null ? null : teamNames.get(ug));
        // İzleme: ad + derin bağlantı parçaları (istemci bağlantıyı kurar: SSL → pano + alan adı, diğerleri ?tab=&monitor=)
        Map<String, Object> mon = new LinkedHashMap<>();
        mon.put("type", nt == null ? null : nt.name());
        mon.put("tab", nt == null ? null : nt.tab);
        mon.put("id", row == null || nt == NocType.SSL ? null : row.id());
        mon.put("name", row == null ? null : row.name());
        mon.put("noc_notify", row != null && row.nocNotify());
        m.put("monitor", mon);
        // Kanallar: takım e-postası (7/24 satırları HARİÇ), kontak webhook'u, kişisel push, 7/24
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("email_sent", mail == null ? 0L : mail[0]);
        ch.put("email_failed", mail == null ? 0L : mail[1]);
        ch.put("webhook_sent", mail == null ? 0L : mail[2]);
        ch.put("push_sent", push == null ? 0L : push[0]);
        ch.put("push_failed", push == null ? 0L : push[1]);
        ch.put("noc", nocSent);
        m.put("channels", ch);
        if (nocSent) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("sent_at", NocAlertFacts.sentAt(d));
            n.put("via_storm", NocAlertFacts.viaStorm(d.getStatus()));
            n.put("groups", d.getGroupNames());
            m.put("noc", n);
        } else {
            m.put("noc", null);
        }
        m.put("call_count", calls);
        if (call != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("contacted_name", call.getContactedName());
            c.put("contacted_at", call.getContactedAt());
            c.put("outcome", call.getOutcome());
            c.put("channel", call.getChannel());
            c.put("created_by_name", call.getCreatedByName());
            m.put("last_call", c);
        } else {
            m.put("last_call", null);
        }

        StringBuilder search = new StringBuilder();
        for (Object s : new Object[]{e.getDomain(), row == null ? null : row.name(), m.get("team_name"), m.get("ug_team_name"),
                e.getAlertType()}) {
            if (s != null) search.append(fold(s.toString())).append('\n');
        }
        String level = e.getAlertLevel() == null ? null : e.getAlertLevel().toUpperCase(Locale.ROOT);
        return new Item(m, team, ug, level, family, open, nocSent, calls, search.toString(), e.getCreatedAt(), e.getId());
    }

    // ── Toplu okumalar (kaynak başına TEK sorgu; düşerse boş) ────────────────

    private Map<Long, NocCallLog> latestCalls(List<Long> ids) {
        Map<Long, NocCallLog> out = new HashMap<>();
        try {
            for (List<Long> part : chunks(ids))
                for (NocCallLog c : callRepo.findLatestByAlertIds(part))
                    if (c != null && c.getAlertId() != null) out.putIfAbsent(c.getAlertId(), c);
        } catch (Exception ex) {
            log.debug("7/24 konsolu: son aramalar alınamadı: {}", ex.toString());
        }
        return out;
    }

    private Map<Long, Long> callCounts(List<Long> ids) {
        Map<Long, Long> out = new HashMap<>();
        try {
            for (List<Long> part : chunks(ids))
                for (Object[] r : callRepo.countGroupedByAlertIds(part))
                    if (r != null && r[0] instanceof Number id && r[1] instanceof Number n) out.put(id.longValue(), n.longValue());
        } catch (Exception ex) {
            log.debug("7/24 konsolu: arama sayıları alınamadı: {}", ex.toString());
        }
        return out;
    }

    /** [sent, failed, webhookSent] — takım bildirimleri (7/24 satırları hariç, {@code countByAlertIds}). */
    private Map<Long, long[]> mailCounts(List<Long> ids) {
        Map<Long, long[]> out = new HashMap<>();
        try {
            for (List<Long> part : chunks(ids))
                for (Object[] r : notificationLogRepo.countByAlertIds(part)) {
                    if (r == null || !(r[0] instanceof Number id)) continue;
                    out.put(id.longValue(), new long[]{num(r, 1), num(r, 2), num(r, 3)});
                }
        } catch (Exception ex) {
            log.debug("7/24 konsolu: e-posta sayıları alınamadı: {}", ex.toString());
        }
        return out;
    }

    /** [sent, failed] — kişisel push teslimleri. */
    private Map<Long, long[]> pushCounts(List<Long> ids) {
        Map<Long, long[]> out = new HashMap<>();
        try {
            for (List<Long> part : chunks(ids))
                for (Object[] r : pushRepo.countByAlertEventIdInGroupByStatus(part)) {
                    if (r == null || !(r[0] instanceof Number id)) continue;
                    long[] v = out.computeIfAbsent(id.longValue(), k -> new long[2]);
                    String st = String.valueOf(r[1]);
                    long n = num(r, 2);
                    if ("SENT".equals(st)) v[0] += n;
                    else if ("FAILED".equals(st) || "CIRCUIT_OPEN".equals(st)) v[1] += n;
                }
        } catch (Exception ex) {
            log.debug("7/24 konsolu: push sayıları alınamadı: {}", ex.toString());
        }
        return out;
    }

    private Map<String, CertificateInventory> inventory(Collection<String> domains) {
        Map<String, CertificateInventory> out = new HashMap<>();
        if (domains.isEmpty()) return out;
        try {
            List<String> list = new ArrayList<>(domains);
            for (int i = 0; i < list.size(); i += CHUNK)
                for (CertificateInventory inv : inventoryRepo.findByDomainIn(list.subList(i, Math.min(list.size(), i + CHUNK))))
                    if (inv != null && inv.getDomain() != null) out.putIfAbsent(lower(inv.getDomain()), inv);
        } catch (Exception ex) {
            log.debug("7/24 konsolu: envanter okunamadı: {}", ex.toString());
        }
        return out;
    }

    private static List<List<Long>> chunks(List<Long> ids) {
        List<List<Long>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += CHUNK) out.add(ids.subList(i, Math.min(ids.size(), i + CHUNK)));
        return out;
    }

    private static long num(Object[] r, int i) {
        return r.length > i && r[i] instanceof Number n ? n.longValue() : 0L;
    }

    /** Türkçe duyarsız arama katlaması: İ/I/ı → i, sonra küçük harf ("TAKIM" = "takım" = "takim"). */
    static String fold(String s) {
        return s.replace('İ', 'i').replace('I', 'i').toLowerCase(Locale.ROOT).replace('ı', 'i');
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** Test kancası. */
    void clearMemo() {
        memo.clear();
        lastFresh.clear();
    }
}
