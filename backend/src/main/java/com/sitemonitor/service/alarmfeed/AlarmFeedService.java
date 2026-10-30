package com.sitemonitor.service.alarmfeed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AlertOwnership;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.service.noc.NocMonitorDirectory;
import com.sitemonitor.service.noc.NocType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Merkezi izleme alarm akışının verisi (2026-10-30, kullanıcı isteği: izleme ekibi her dakika SiteMonitor'den açık alarmları ve
 * çözülenleri kendi kolon biçimlerinde çeker).
 *
 * <p><b>Ne döner.</b> Kapsamdaki (seviye / takım / izleme türü süzgeci) bütün AÇIK alarmlar STATUS=OPEN ve son N dakikada
 * kapanan "bildirilmiş" seviyeler STATUS=RESOLVED. Her çağrı o anki durumun TAMAMIDIR (bir dakika kaçsa da veri kaybolmaz;
 * tekrarları ekip EVENTKEY ile ayıklar).
 *
 * <p><b>Seviye değişimi.</b> Ekibin olay anahtarı seviyeyi içerir; alarm Warning → Critical olunca merkezi sistemde YENİ olay
 * doğar. {@code alarm_feed_severities} hangi (alarm, seviye) çiftinin açık bildirildiğini tutar: seviye değişince, alarm
 * çözülünce, alarm kapsamdan çıkınca ya da silinince eski çift kapatılır ve (silinme hariç) çözülme penceresi boyunca o eski
 * seviyeyle STATUS=RESOLVED döner — merkezi sistemde hiçbir olay sahipsiz açık kalmaz. "Clear" kipinde seviye değişiminin
 * kapanışı gönderilmez (ekip kapanışı RCI ile eşleştirir; Clear satırı o RCI'nın bütün olaylarını kapatırdı).
 *
 * <p><b>Bilinmeyen ≠ boş.</b> Alarm, envanter ya da takım okunamazsa yanıt kurulmaz ({@link IllegalStateException} → 503):
 * boş liste merkezi sistemde "hiç alarm yok" sanılırdı. İzleme adları isteğe bağlıdır (okunamazsa satırda olmaz).
 *
 * <p>Pod başına {@code cache-ms} (5 sn) önbellek: aynı dakika içindeki çağrılar aynı gövdeyi alır ve durum tablosu bir kez
 * yazılır. Önizleme ({@code dryRun}) durumu YAZMAZ.
 */
@Slf4j
@Service
public class AlarmFeedService {

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Bir yanıtta taranan en çok alarm (açık + penceredeki çözülenler). */
    static final int SCAN_MAX = 20_000;
    static final int CHUNK = 500;
    /** Kapanmış durum satırları pencere + bu süre sonra silinir. */
    static final long PRUNE_AFTER_MS = 2L * 24 * 3600_000L;
    static final long PRUNE_EVERY_MS = 3600_000L;

    static final String SQL_STATE_READ = "SELECT id, alert_event_id, level, sent_at, closed_at, closed_reason "
            + "FROM alarm_feed_severities WHERE closed_at IS NULL OR closed_at >= ?";
    static final String SQL_STATE_INSERT = "INSERT INTO alarm_feed_severities (alert_event_id, level, sent_at) VALUES (?, ?, ?)";
    static final String SQL_STATE_CLOSE = "UPDATE alarm_feed_severities SET closed_at = ?, closed_reason = ? "
            + "WHERE id = ? AND closed_at IS NULL";
    static final String SQL_STATE_REOPEN = "UPDATE alarm_feed_severities SET closed_at = NULL, closed_reason = NULL "
            + "WHERE id = ? AND closed_at IS NOT NULL";
    static final String SQL_STATE_PRUNE = "DELETE FROM alarm_feed_severities WHERE closed_at IS NOT NULL AND closed_at < ?";

    private final AlertEventRepository alertRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final TeamRepository teamRepo;
    private final JdbcTemplate jdbc;
    private final AppSettingsService settings;

    private NocMonitorDirectory directory;

    @Value("${site.monitor.alarm-feed.cache-ms:5000}")
    long cacheMs = 5_000;

    LongSupplier clock = System::currentTimeMillis;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Object buildLock = new Object();
    private volatile Cached cached;
    private volatile long lastPruneMs;

    record Cached(String key, long atMs, Feed feed) {}

    /** Bir yanıtın satırları + sayaçları. */
    public record Feed(List<Map<String, Object>> rows, int open, int resolved, boolean truncated, String generatedAt) {}

    public AlarmFeedService(AlertEventRepository alertRepo, CertificateInventoryRepository inventoryRepo,
                            TeamRepository teamRepo, JdbcTemplate jdbc, AppSettingsService settings) {
        this.alertRepo = alertRepo;
        this.inventoryRepo = inventoryRepo;
        this.teamRepo = teamRepo;
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Autowired(required = false)
    void setDirectory(NocMonitorDirectory directory) {
        this.directory = directory;
    }

    public AlarmFeedSettings.Config config() {
        return AlarmFeedSettings.read(settings);
    }

    /** Uygulamanın dış adresi (bağlantılar için); yalnız http(s), sondaki eğik çizgi atılır; yoksa null. */
    public String baseUrl() {
        String b = settings == null ? null : settings.getString("site.monitor.app.base-url", "");
        if (b == null) return null;
        b = b.strip();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        String low = b.toLowerCase(Locale.ROOT);
        if (!(low.startsWith("http://") || low.startsWith("https://")) || b.length() > 300) return null;
        return b;
    }

    /** Akış yanıtı (pod başına kısa önbellekli; durum tablosu yazılır). */
    public Feed feed(AlarmFeedSettings.Config c) {
        String key = c.fingerprint() + "|" + baseUrl();
        long now = clock.getAsLong();
        Cached hit = cached;
        if (hit != null && hit.key().equals(key) && now - hit.atMs() < cacheMs) return hit.feed();
        synchronized (buildLock) {
            hit = cached;
            now = clock.getAsLong();
            if (hit != null && hit.key().equals(key) && now - hit.atMs() < cacheMs) return hit.feed();
            Feed f = build(c, false);
            cached = new Cached(key, clock.getAsLong(), f);
            return f;
        }
    }

    /** Önizleme: aynı kurallar, durum YAZILMAZ, önbellek kullanılmaz. */
    public Feed preview(AlarmFeedSettings.Config c) {
        return build(c, true);
    }

    /** Ayar değişince önbellek bırakılır (yeni ayar hemen görünsün). */
    public void invalidate() {
        cached = null;
    }

    public String toJson(Feed f) {
        try {
            return mapper.writeValueAsString(f.rows());
        } catch (Exception e) {
            throw new IllegalStateException("Alarm akışı JSON'a çevrilemedi", e);
        }
    }

    public static String toCsv(Feed f) {
        StringBuilder b = new StringBuilder();
        b.append(com.sitemonitor.util.Csv.row(AlarmFeedRows.COLUMNS.toArray()));   // Csv.row CRLF ile biter
        for (Map<String, Object> r : f.rows()) {
            Object[] cells = new Object[AlarmFeedRows.COLUMNS.size()];
            for (int i = 0; i < cells.length; i++) cells[i] = r.get(AlarmFeedRows.COLUMNS.get(i));
            b.append(com.sitemonitor.util.Csv.row(cells));
        }
        return b.toString();
    }

    // ── Kurulum ─────────────────────────────────────────────────────────────────────────────────

    /** Durum satırı (bellekte; plan uygulanınca veritabanına yansır). */
    static final class State {
        Long id;
        final long alertId;
        final String level;
        String sentAt;
        String closedAt;
        String reason;

        State(Long id, long alertId, String level, String sentAt, String closedAt, String reason) {
            this.id = id;
            this.alertId = alertId;
            this.level = level;
            this.sentAt = sentAt;
            this.closedAt = closedAt;
            this.reason = reason;
        }
    }

    /** Bir alarmın kapsam değerlendirmesi için toplanmış hali. */
    record Item(AlertEvent event, AlarmFeedRows.Alarm alarm, String level, String family, Set<Long> owners) {}

    Feed build(AlarmFeedSettings.Config c, boolean dryRun) {
        long nowMs = clock.getAsLong();
        String now = ISO.format(Instant.ofEpochMilli(nowMs));
        String since = ISO.format(Instant.ofEpochMilli(nowMs - c.resolvedMinutes() * 60_000L));
        String base = baseUrl();

        // 1) Alarmlar: açıklar + penceredeki çözülenler (zorunlu — okunamazsa istisna yükselir).
        List<AlertEvent> events = alertRepo.findOpenOrResolvedSince(since, PageRequest.of(0, SCAN_MAX + 1));
        boolean truncated = events.size() > SCAN_MAX;
        if (truncated) events = new ArrayList<>(events.subList(0, SCAN_MAX));

        // 2) Durum: açık ya da pencerede kapanmış bildirim satırları.
        Map<Long, List<State>> state = readState(since);

        // 3) Durumda olup alarm listesinde olmayanlar (kapsam dışı / eski kapanış / silinmiş) — toplu okunur.
        Map<Long, AlertEvent> byId = new LinkedHashMap<>();
        for (AlertEvent e : events) if (e.getId() != null) byId.put(e.getId(), e);
        List<Long> extraIds = new ArrayList<>();
        for (Long id : state.keySet()) if (!byId.containsKey(id)) extraIds.add(id);
        for (int i = 0; i < extraIds.size(); i += CHUNK) {
            for (AlertEvent e : alertRepo.findAllById(extraIds.subList(i, Math.min(extraIds.size(), i + CHUNK)))) {
                if (e.getId() != null) byId.put(e.getId(), e);
            }
        }

        // 4) Takımlar (zorunlu) + envanter (zorunlu) + izleme adları (isteğe bağlı).
        Map<Long, AlarmFeedRows.TeamInfo> teams = teams();
        Map<String, CertificateInventory> inv = inventory(byId.values());
        NocMonitorDirectory.Snapshot snap = null;
        if (directory != null) {
            try {
                snap = directory.snapshot();
            } catch (Exception ex) {
                log.debug("Alarm akışı: izleme dizini okunamadı: {}", ex.toString());
            }
        }

        Map<Long, Item> items = new LinkedHashMap<>();
        for (AlertEvent e : byId.values()) items.put(e.getId(), item(e, inv, teams, snap));

        // 5) Kapsam.
        List<Item> open = new ArrayList<>();
        List<Item> resolvedInWindow = new ArrayList<>();
        Set<Long> inScopeIds = new LinkedHashSet<>();
        for (AlertEvent e : events) {
            Item it = items.get(e.getId());
            if (it == null || !inScope(it, c)) continue;
            if (Boolean.TRUE.equals(e.getResolved())) {
                resolvedInWindow.add(it);
            } else {
                open.add(it);
            }
            inScopeIds.add(e.getId());
        }

        // 6) Durum planı (bellekte) + veritabanı işlemleri.
        List<Runnable> ops = new ArrayList<>();
        Set<String> openKeys = new LinkedHashSet<>();
        for (Item it : open) {
            long id = it.event().getId();
            List<State> rows = state.computeIfAbsent(id, k -> new ArrayList<>());
            State cur = null;
            for (State s : rows) if (s.level.equals(it.level())) cur = s;
            if (cur == null) {
                State s = new State(null, id, it.level(), now, null, null);
                rows.add(s);
                ops.add(() -> insert(s));
            } else if (cur.closedAt != null) {
                State s = cur;
                s.closedAt = null;
                s.reason = null;
                ops.add(() -> reopen(s));
            }
            for (State s : rows) {
                if (!s.level.equals(it.level()) && s.closedAt == null) close(s, now, AlarmFeedRows.REASON_LEVEL_CHANGE, ops);
            }
            openKeys.add(id + "|" + it.level());
        }
        Set<Long> openIds = new LinkedHashSet<>();
        for (Item it : open) openIds.add(it.event().getId());
        for (Map.Entry<Long, List<State>> en : state.entrySet()) {
            long id = en.getKey();
            if (openIds.contains(id)) continue;
            AlertEvent e = byId.get(id);
            for (State s : en.getValue()) {
                if (s.closedAt != null) continue;
                if (e == null) {
                    close(s, now, AlarmFeedRows.REASON_GONE, ops);
                } else if (Boolean.TRUE.equals(e.getResolved())) {
                    String at = e.getResolvedAt();
                    close(s, at != null && at.compareTo(since) >= 0 && at.compareTo(now) <= 0 ? at : now,
                            AlarmFeedRows.REASON_RESOLVED, ops);
                } else {
                    close(s, now, AlarmFeedRows.REASON_SCOPE, ops);
                }
            }
        }
        if (!dryRun) runOps(ops);

        // 7) Satırlar.
        List<Map<String, Object>> openRows = new ArrayList<>();
        open.sort(Comparator.comparingInt((Item it) -> -AlarmFeedSettings.rank(it.level()))
                .thenComparing((Item it) -> nz(it.event().getCreatedAt()), Comparator.reverseOrder())
                .thenComparing((Item it) -> it.event().getId(), Comparator.reverseOrder()));
        for (Item it : open) {
            openRows.add(AlarmFeedRows.row(it.alarm(), c, base, it.level(), true, null, null, null));
        }

        record Closed(Item item, String level, String closedAt, String reason) {}
        List<Closed> closed = new ArrayList<>();
        Set<String> closedKeys = new LinkedHashSet<>();
        Set<Long> clearDone = new LinkedHashSet<>();
        for (Map.Entry<Long, List<State>> en : state.entrySet()) {
            Item it = items.get(en.getKey());
            if (it == null) continue;
            for (State s : en.getValue()) {
                if (s.closedAt == null || s.closedAt.compareTo(since) < 0) continue;
                if (AlarmFeedRows.REASON_GONE.equals(s.reason)) continue;
                if (openKeys.contains(s.alertId + "|" + s.level)) continue;
                if (c.clearMode()) {
                    // Clear kipi: seviye değişiminin kapanışı gönderilmez; alarm başına TEK Clear satırı.
                    if (AlarmFeedRows.REASON_LEVEL_CHANGE.equals(s.reason) || openIds.contains(s.alertId)) continue;
                    if (!clearDone.add(s.alertId)) continue;
                }
                if (closedKeys.add(s.alertId + "|" + s.level)) closed.add(new Closed(it, s.level, s.closedAt, s.reason));
            }
        }
        // Hiç bildirilmemiş ama pencerede çözülmüş kapsamdaki alarmlar da (bilgi amaçlı) çözüldü olarak döner.
        for (Item it : resolvedInWindow) {
            long id = it.event().getId();
            if (c.clearMode()) {
                if (!clearDone.add(id)) continue;
            } else if (state.getOrDefault(id, List.of()).stream().anyMatch(s -> s.closedAt != null)) {
                continue;   // bildirilmiş seviyeleri yukarıda döndü
            }
            if (closedKeys.add(id + "|" + it.level())) {
                closed.add(new Closed(it, it.level(), it.event().getResolvedAt(), AlarmFeedRows.REASON_RESOLVED));
            }
        }
        closed.sort(Comparator.comparing((Closed x) -> nz(x.closedAt()), Comparator.reverseOrder())
                .thenComparing((Closed x) -> x.item().event().getId(), Comparator.reverseOrder()));
        List<Map<String, Object>> resolvedRows = new ArrayList<>();
        for (Closed x : closed) {
            String newLevel = AlarmFeedRows.REASON_LEVEL_CHANGE.equals(x.reason()) && openIds.contains(x.item().event().getId())
                    ? x.item().level() : null;
            resolvedRows.add(AlarmFeedRows.row(x.item().alarm(), c, base, x.level(), false, x.closedAt(), x.reason(), newLevel));
        }

        // 8) Tavan: kapanışlar önce korunur (kaybolan "çözüldü" merkezi sistemde olayı sonsuza dek açık bırakırdı).
        int cap = c.maxRows();
        if (openRows.size() + resolvedRows.size() > cap) {
            truncated = true;
            if (resolvedRows.size() > cap) resolvedRows = new ArrayList<>(resolvedRows.subList(0, cap));
            int room = cap - resolvedRows.size();
            if (openRows.size() > room) openRows = new ArrayList<>(openRows.subList(0, room));
            log.warn("Alarm akışı satır tavanına ({}) ulaştı — en düşük öncelikli açık alarmlar yanıta girmedi", cap);
        }
        List<Map<String, Object>> rows = new ArrayList<>(openRows.size() + resolvedRows.size());
        rows.addAll(openRows);
        rows.addAll(resolvedRows);

        if (!dryRun) pruneMaybe(nowMs, c);
        return new Feed(List.copyOf(rows), openRows.size(), resolvedRows.size(), truncated, now);
    }

    static boolean inScope(Item it, AlarmFeedSettings.Config c) {
        if (!c.levelIncluded(it.level())) return false;
        if (!c.types().isEmpty() && (it.family() == null || !c.types().contains(it.family()))) return false;
        if (!c.teamIds().isEmpty()) {
            boolean any = false;
            for (Long t : it.owners()) if (c.teamIds().contains(t)) { any = true; break; }
            return any;
        }
        return true;
    }

    private Item item(AlertEvent e, Map<String, CertificateInventory> inv, Map<Long, AlarmFeedRows.TeamInfo> teams,
                      NocMonitorDirectory.Snapshot snap) {
        boolean inventoryRouted = AlertOwnership.routesLikeInventory(e);
        CertificateInventory row = inventoryRouted ? inv.get(lower(e.getDomain())) : null;
        String monitorName = null;
        Long dirTeam = null;
        if (snap != null) {
            try {
                NocType nt = NocType.forAlertType(e.getAlertType());
                NocMonitorDirectory.Row r = nt == null ? null : snap.forAlert(nt, e.getDomain(), monitorIdOf(e.getContextJson()));
                if (r != null) {
                    monitorName = r.name();
                    dirTeam = r.teamId();
                }
            } catch (Exception ex) {
                log.debug("Alarm akışı: izleme çözülemedi ({}): {}", e.getId(), ex.toString());
            }
        }
        // Sahibi takım: damga → (envanter gibi yönlenen alarmda) envanter SY → UG; bağımsız alarm envanteri ALMAZ
        // (AlertOwnership, 7/24 konsoluyla aynı kural). Damgasız bağımsız alarmda izleme satırının takımı yalnız GÖSTERİM içindir.
        Long team = e.getTeamId();
        if (team == null && row != null) team = row.getTeamId() != null ? row.getTeamId() : row.getUgTeamId();
        Long ug = row != null ? row.getUgTeamId() : null;
        if (ug != null && ug.equals(team)) ug = null;
        Set<Long> owners = new LinkedHashSet<>(AlertOwnership.ownerTeamIds(e, row == null ? null : row.getTeamId(),
                row == null ? null : row.getUgTeamId()));
        Long shownTeam = team != null ? team : dirTeam;
        if (team == null && dirTeam != null) owners.add(dirTeam);

        String level = AlarmFeedSettings.canonicalLevel(e.getAlertLevel());
        AlarmFeedRows.Alarm a = new AlarmFeedRows.Alarm(e.getId(), e.getAlertType(), level, e.getDomain(), e.getMessage(),
                e.getDaysRemaining(), e.getCreatedAt(), Boolean.TRUE.equals(e.getResolved()), e.getResolvedAt(),
                e.getResolvedBy(), Boolean.TRUE.equals(e.getAcknowledged()), e.getAcknowledgedAt(), e.getStormId(),
                monitorName, teamInfo(shownTeam, teams), teamInfo(ug, teams));
        return new Item(e, a, level, MonitorTypeCatalog.typeOfAlert(e.getAlertType()), owners);
    }

    private static AlarmFeedRows.TeamInfo teamInfo(Long id, Map<Long, AlarmFeedRows.TeamInfo> teams) {
        if (id == null) return null;
        AlarmFeedRows.TeamInfo t = teams.get(id);
        return t != null ? t : new AlarmFeedRows.TeamInfo(id, null, List.of());
    }

    private Map<Long, AlarmFeedRows.TeamInfo> teams() {
        Map<Long, AlarmFeedRows.TeamInfo> out = new HashMap<>();
        for (Team t : teamRepo.findAll()) {
            if (t.getId() == null) continue;
            out.put(t.getId(), new AlarmFeedRows.TeamInfo(t.getId(), t.getName(), emails(t.getEmail())));
        }
        return out;
    }

    /** Takım adres alanı (virgül / noktalı virgül / boşlukla ayrılmış) → geçerli görünen, tekil adresler. */
    static List<String> emails(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank() || raw.length() > 4000) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (String p : raw.split("[,;\\s]+")) {
            String e = p.trim();
            if (e.isEmpty() || e.length() > 254) continue;
            int at = e.indexOf('@');
            if (at <= 0 || at != e.lastIndexOf('@') || e.indexOf('.', at) < 0 || e.endsWith(".")) continue;
            if (seen.add(e.toLowerCase(Locale.ROOT))) out.add(e);
        }
        return out;
    }

    private Map<String, CertificateInventory> inventory(Collection<AlertEvent> events) {
        Set<String> domains = new LinkedHashSet<>();
        for (AlertEvent e : events) {
            if (e.getDomain() != null && AlertOwnership.routesLikeInventory(e)) domains.add(e.getDomain());
        }
        Map<String, CertificateInventory> out = new HashMap<>();
        List<String> list = new ArrayList<>(domains);
        for (int i = 0; i < list.size(); i += CHUNK) {
            for (CertificateInventory r : inventoryRepo.findByDomainIn(list.subList(i, Math.min(list.size(), i + CHUNK)))) {
                if (r != null && r.getDomain() != null) out.putIfAbsent(lower(r.getDomain()), r);
            }
        }
        return out;
    }

    // ── Durum tablosu ───────────────────────────────────────────────────────────────────────────

    private Map<Long, List<State>> readState(String since) {
        Map<Long, List<State>> out = new LinkedHashMap<>();
        jdbc.query(SQL_STATE_READ, rs -> {
            long alertId = rs.getLong("alert_event_id");
            State s = new State(rs.getLong("id"), alertId, AlarmFeedSettings.canonicalLevel(rs.getString("level")),
                    rs.getString("sent_at"), rs.getString("closed_at"), rs.getString("closed_reason"));
            out.computeIfAbsent(alertId, k -> new ArrayList<>()).add(s);
        }, since);
        return out;
    }

    /**
     * Bellekte kapatır ve koşullu UPDATE'i plana ekler. Yalnız okunmuş (kimlikli) satır kapanır: yeni satır yalnız açık
     * alarmın GÜNCEL seviyesi için eklenir ve aynı yanıtta kapanmaz.
     */
    private void close(State s, String at, String reason, List<Runnable> ops) {
        s.closedAt = at;
        s.reason = reason;
        Long id = s.id;
        if (id != null) ops.add(() -> jdbc.update(SQL_STATE_CLOSE, at, reason, id));
    }

    private void runOps(List<Runnable> ops) {
        for (Runnable op : ops) {
            try {
                op.run();
            } catch (Exception ex) {
                log.warn("Alarm akışı seviye durumu yazılamadı — bir sonraki çağrıda yeniden denenir: {}", ex.toString());
            }
        }
    }

    private void insert(State s) {
        try {
            jdbc.update(SQL_STATE_INSERT, s.alertId, s.level, s.sentAt);
        } catch (DataIntegrityViolationException dup) {
            // Başka bir pod aynı (alarm, seviye) çiftini az önce yazdı — aynı anlam, yeniden açmayı dene.
            jdbc.update("UPDATE alarm_feed_severities SET closed_at = NULL, closed_reason = NULL "
                    + "WHERE alert_event_id = ? AND level = ? AND closed_at IS NOT NULL", s.alertId, s.level);
        }
    }

    private void reopen(State s) {
        if (s.id != null) jdbc.update(SQL_STATE_REOPEN, s.id);
    }

    private void pruneMaybe(long nowMs, AlarmFeedSettings.Config c) {
        if (nowMs - lastPruneMs < PRUNE_EVERY_MS) return;
        lastPruneMs = nowMs;
        try {
            long keepMs = c.resolvedMinutes() * 60_000L + PRUNE_AFTER_MS;
            int n = jdbc.update(SQL_STATE_PRUNE, ISO.format(Instant.ofEpochMilli(nowMs - keepMs)));
            if (n > 0) log.debug("Alarm akışı: {} eski seviye durumu silindi", n);
        } catch (Exception ex) {
            log.debug("Alarm akışı seviye durumu temizlenemedi: {}", ex.toString());
        }
    }

    // ── küçük yardımcılar ─────────────────────────────────────────────────────────────────────

    /** Bağlam JSON'undan {@code monitor_id} (yoksa / bozuksa null). */
    @SuppressWarnings("unchecked")
    Object monitorIdOf(String contextJson) {
        if (contextJson == null || contextJson.isBlank() || contextJson.length() > 200_000) return null;
        try {
            Map<String, Object> m = mapper.readValue(contextJson, Map.class);
            return m.get("monitor_id");
        } catch (Exception e) {
            return null;
        }
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
