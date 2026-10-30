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
import com.sitemonitor.service.NotificationGroupService;
import com.sitemonitor.service.noc.NocMonitorDirectory;
import com.sitemonitor.service.noc.NocType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
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
 * <p><b>Ne döner.</b> Kapsamdaki (seviye / takım / izleme türü süzgeci) bütün AÇIK alarmlar STATUS=OPEN + kapanışlar
 * STATUS=RESOLVED. Her çağrı o anki durumun TAMAMIDIR (bir dakika kaçsa da veri kaybolmaz; tekrarları ekip EVENTKEY ile ayıklar).
 *
 * <p><b>Olay anahtarı.</b> Ekip anahtarı satırdan kurar ({@code kaynak|||RCI|||SEVERITY}). {@code alarm_feed_events} her alarm
 * için GÖNDERİLEN anahtarı ve satırın kopyasını tutar. Alarm açıkken anahtarı değişirse (seviye değişimi, yeniden adlandırma,
 * seviye eşlemesi / kaynak metni değişimi) eski anahtar kapatılır ve KOPYADAN kurulan "çözüldü" satırıyla bildirilir; alarm
 * çözülünce (gerçek çözülme anıyla), kapsamdan çıkınca ya da silinince de aynı. Bir kapanış en az bir yanıtta gönderilene
 * kadar ({@code closure_sent_at}) ve çözülme penceresi boyunca akışta kalır — akış kapalıyken kapanan alarm da bildirilir.
 * Aynı yanıtta aynı anahtarla AÇIK ve ÇÖZÜLDÜ satırı ASLA birlikte dönmez. "Clear" kipinde kapanış o RCI'da açık satır
 * yoksa (alarm başına bir) Clear olarak gider.
 *
 * <p><b>Bilinmeyen ≠ boş.</b> Alarm, envanter, takım ya da (takım süzgeci varken) izleme dizini okunamazsa yanıt kurulmaz
 * (istisna → uç 503): boş liste merkezi sistemde "hiç alarm yok" sanılırdı.
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
    /** Teslim edilmiş kapanışlar pencere + bu süre sonra silinir. */
    static final long PRUNE_AFTER_MS = 2L * 24 * 3600_000L;
    /** Hiç teslim edilemeyen kapanış (akış uzun süre kapalı / kimse çağırmadı) bu süre sonra bırakılır. */
    static final long UNDELIVERED_MAX_MS = 7L * 24 * 3600_000L;
    static final long PRUNE_EVERY_MS = 3600_000L;
    /** Akış kapalıyken durum satırları bu süre sonra silinir. */
    static final long DISABLED_KEEP_MS = 30L * 24 * 3600_000L;

    static final String SQL_STATE_READ = "SELECT id, alert_event_id, key_hash, level, sent_at, closed_at, closed_reason, "
            + "closure_sent_at, snapshot FROM alarm_feed_events "
            + "WHERE closed_at IS NULL OR closed_at >= ? OR closure_sent_at IS NULL OR closure_sent_at >= ?";
    static final String SQL_STATE_INSERT = "INSERT INTO alarm_feed_events (alert_event_id, key_hash, level, sent_at, snapshot) "
            + "VALUES (?, ?, ?, ?, ?)";
    static final String SQL_STATE_CLOSE = "UPDATE alarm_feed_events SET closed_at = ?, closed_reason = ?, closed_noted_at = ? "
            + "WHERE id = ? AND closed_at IS NULL";
    static final String SQL_STATE_REOPEN = "UPDATE alarm_feed_events SET closed_at = NULL, closed_reason = NULL, "
            + "closure_sent_at = NULL, closed_noted_at = NULL WHERE id = ? AND closed_at IS NOT NULL";
    static final String SQL_STATE_REOPEN_BY_KEY = "UPDATE alarm_feed_events SET closed_at = NULL, closed_reason = NULL, "
            + "closure_sent_at = NULL, closed_noted_at = NULL WHERE alert_event_id = ? AND key_hash = ? AND closed_at IS NOT NULL";
    static final String SQL_STATE_DELIVERED = "UPDATE alarm_feed_events SET closure_sent_at = ? "
            + "WHERE id = ? AND closed_at IS NOT NULL AND closure_sent_at IS NULL";
    /** Teslim edilmiş kapanış: hem kapanışı hem ilk gönderimi pencere + 2 günden eski. */
    static final String SQL_STATE_PRUNE_SENT = "DELETE FROM alarm_feed_events WHERE closed_at IS NOT NULL "
            + "AND closure_sent_at IS NOT NULL AND closed_at < ? AND closure_sent_at < ?";
    /** Hiç teslim edilemeyen kapanış: kapatıldığımız an (gerçek çözülme anı değil) 7 günden eski. */
    static final String SQL_STATE_PRUNE_UNDELIVERED = "DELETE FROM alarm_feed_events WHERE closed_at IS NOT NULL "
            + "AND closure_sent_at IS NULL AND COALESCE(closed_noted_at, closed_at) < ?";
    /** Akış kapalıyken: 30 günden eski bütün satırlar (takım adı / adres kopyaları süresiz kalmasın). */
    static final String SQL_STATE_PRUNE_DISABLED = "DELETE FROM alarm_feed_events WHERE sent_at < ?";

    private final AlertEventRepository alertRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final TeamRepository teamRepo;
    private final JdbcTemplate jdbc;
    private final AppSettingsService settings;

    private NocMonitorDirectory directory;
    private NotificationGroupService notificationGroups;

    @Value("${site.monitor.alarm-feed.cache-ms:5000}")
    long cacheMs = 5_000;

    /** RCI hedef özetinin anahtarı (HMAC) — sunucu gizli anahtarından türer; boşsa sabit (geliştirme). */
    @Value("${site.monitor.secret-key:}")
    void setSecretKey(String secretKey) {
        this.rciSalt = AlarmFeedRows.saltOf(secretKey);
    }

    private volatile byte[] rciSalt = AlarmFeedRows.saltOf("");

    LongSupplier clock = System::currentTimeMillis;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Object buildLock = new Object();
    private volatile Cached cached;
    private volatile long lastPruneMs;
    private volatile long lastUnusableWarnMs;

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

    @Autowired(required = false)
    void setNotificationGroups(NotificationGroupService notificationGroups) {
        this.notificationGroups = notificationGroups;
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
        final String keyHash;
        final String level;
        String sentAt;
        String closedAt;
        String reason;
        String closureSentAt;
        /** Ham kopya (yalnız kapanış kurulurken açılır — her yanıtta bütün açık satırların JSON'u ayrıştırılmasın). */
        final String snapshotJson;
        Map<String, Object> snapshot;

        State(Long id, long alertId, String keyHash, String level, String sentAt, String closedAt, String reason,
              String closureSentAt, String snapshotJson) {
            this.id = id;
            this.alertId = alertId;
            this.keyHash = keyHash;
            this.level = level;
            this.sentAt = sentAt;
            this.closedAt = closedAt;
            this.reason = reason;
            this.closureSentAt = closureSentAt;
            this.snapshotJson = snapshotJson;
        }
    }

    /** Bir alarmın kapsam değerlendirmesi için toplanmış hali. */
    record Item(AlertEvent event, AlarmFeedRows.Alarm alarm, String level, String family, Set<Long> owners) {}

    Feed build(AlarmFeedSettings.Config c, boolean dryRun) {
        long nowMs = clock.getAsLong();
        String now = ISO.format(Instant.ofEpochMilli(nowMs));
        String since = ISO.format(Instant.ofEpochMilli(nowMs - c.resolvedMinutes() * 60_000L));
        String base = baseUrl();

        // 1) Durum ÖNCE: açık, pencerede kapanmış, henüz gönderilmemiş ya da ilk gönderiminden bu yana pencere geçmemiş
        //    kapanışlar. Alarmlardan önce okunur: başka pod'un daha yeni kararı burada görünmediyse bu pod'un koşullu
        //    yazımları (kapat / yeniden aç) etkisiz kalır; alarmlar sonra okunduğu için güncel seviye kullanılır.
        Map<Long, List<State>> state = readState(since);

        // 2) Alarmlar: açıklar + penceredeki çözülenler (zorunlu — okunamazsa istisna yükselir).
        List<AlertEvent> events = alertRepo.findOpenOrResolvedSince(since, PageRequest.of(0, SCAN_MAX + 1));
        boolean truncated = events.size() > SCAN_MAX;
        if (truncated) events = new ArrayList<>(events.subList(0, SCAN_MAX));

        // 3) Durumda olup alarm listesinde olmayanlar (tarama tavanı / kapsam dışı / eski kapanış / silinmiş) — toplu okunur.
        Map<Long, AlertEvent> byId = new LinkedHashMap<>();
        for (AlertEvent e : events) if (e.getId() != null) byId.put(e.getId(), e);
        List<Long> extraIds = new ArrayList<>();
        for (Long id : state.keySet()) if (!byId.containsKey(id)) extraIds.add(id);
        for (int i = 0; i < extraIds.size(); i += CHUNK) {
            for (AlertEvent e : alertRepo.findAllById(extraIds.subList(i, Math.min(extraIds.size(), i + CHUNK)))) {
                if (e.getId() != null) byId.put(e.getId(), e);
            }
        }

        // 4) Takımlar (zorunlu) + envanter (zorunlu) + izleme adları (takım süzgeci varken zorunlu — damgasız bağımsız
        //    alarmın takımı dizinden gelir; okunamazsa kapsam kararı verilemez, yanlış "kapsam dışı" kapanışı olurdu).
        Map<Long, Team> teams = teams();
        Map<String, CertificateInventory> inv = inventory(byId.values());
        NocMonitorDirectory.Snapshot snap = null;
        if (directory != null) {
            try {
                snap = directory.snapshot();
            } catch (Exception ex) {
                if (!c.teamIds().isEmpty()) throw new IllegalStateException("izleme dizini okunamadı", ex);
                log.debug("Alarm akışı: izleme dizini okunamadı: {}", ex.toString());
            }
        }
        Map<String, List<String>> recipientMemo = new HashMap<>();
        Map<Long, Item> items = new LinkedHashMap<>();
        for (AlertEvent e : byId.values()) items.put(e.getId(), item(e, inv, teams, snap, recipientMemo));

        // 5) Kapsam. Tarama tavanı dışında kalmış AÇIK alarm da (durumda bildirilmişse) kapsamda sayılır.
        List<Item> open = new ArrayList<>();
        List<Item> resolvedInWindow = new ArrayList<>();
        for (Item it : items.values()) {
            if (!inScope(it, c)) continue;
            if (Boolean.TRUE.equals(it.event().getResolved())) {
                String at = it.event().getResolvedAt();
                if (at != null && at.compareTo(since) >= 0) resolvedInWindow.add(it);
            } else {
                open.add(it);
            }
        }

        // 6) Açık satırlar + durum planı.
        List<Runnable> ops = new ArrayList<>();
        Map<Long, String> openKeyOfAlert = new HashMap<>();
        Set<String> openEventKeys = new LinkedHashSet<>();
        Set<String> openRciKeys = new LinkedHashSet<>();
        Map<Long, String> newSeverityOfAlert = new HashMap<>();
        List<Map<String, Object>> openRows = new ArrayList<>();
        open.sort(Comparator.comparingInt((Item it) -> -AlarmFeedSettings.rank(it.level()))
                .thenComparing((Item it) -> nz(it.event().getCreatedAt()), Comparator.reverseOrder())
                .thenComparing((Item it) -> it.event().getId(), Comparator.reverseOrder()));
        for (Item it : open) {
            long id = it.event().getId();
            Map<String, Object> row = AlarmFeedRows.openRow(it.alarm(), c, base, rciSalt);
            String key = AlarmFeedRows.eventKey(row);
            String hash = AlarmFeedRows.keyHash(key);
            openRows.add(row);
            openEventKeys.add(key);
            openRciKeys.add(AlarmFeedRows.rciKey(row));
            openKeyOfAlert.put(id, hash);
            newSeverityOfAlert.put(id, (String) row.get("SEVERITY"));

            List<State> rows = state.computeIfAbsent(id, k -> new ArrayList<>());
            State cur = null;
            for (State s : rows) if (s.keyHash.equals(hash)) cur = s;
            if (cur == null) {
                Map<String, Object> snapshot = AlarmFeedRows.withSnapshotFields(row, it.alarm(), rciSalt);
                State s = new State(null, id, hash, it.level(), now, null, null, null, null);
                s.snapshot = snapshot;
                rows.add(s);
                String json = json(snapshot);
                ops.add(() -> insert(s, json));
            } else if (cur.closedAt != null) {
                State s = cur;
                s.closedAt = null;
                s.reason = null;
                s.closureSentAt = null;
                ops.add(() -> reopen(s));
            }
            for (State s : rows) {
                if (s.keyHash.equals(hash) || s.closedAt != null) continue;
                close(s, now, s.level.equals(it.level()) ? AlarmFeedRows.REASON_KEY_CHANGE : AlarmFeedRows.REASON_LEVEL_CHANGE, now, ops);
            }
        }
        Set<Long> openIds = openKeyOfAlert.keySet();
        for (Map.Entry<Long, List<State>> en : state.entrySet()) {
            long id = en.getKey();
            if (openIds.contains(id)) continue;
            AlertEvent e = byId.get(id);
            for (State s : en.getValue()) {
                if (s.closedAt != null) continue;
                if (e == null) {
                    close(s, now, AlarmFeedRows.REASON_GONE, now, ops);
                } else if (Boolean.TRUE.equals(e.getResolved())) {
                    // Gerçek çözülme anı (teslim edilene kadar akışta kalır — pencere dışı olsa da).
                    String at = e.getResolvedAt();
                    close(s, at != null && at.compareTo(now) <= 0 ? at : now, AlarmFeedRows.REASON_RESOLVED, now, ops);
                } else {
                    close(s, now, AlarmFeedRows.REASON_SCOPE, now, ops);
                }
            }
        }
        if (!dryRun) runOps(ops);

        // 7) Kapanış adayları — GÖNDERİLMİŞ kopyalardan. Pencere içinde kapanan, hiç gönderilmemiş ya da İLK gönderiminden
        //    bu yana bir pencere geçmemiş kapanış döner: bir yanıt kaybolsa ya da başka bir çağıran onu "tüketse" de merkezi
        //    sistem sonraki çağrılarda yine alır.
        record Cand(State s, Map<String, Object> row, String dedupeKey) {}
        List<Cand> cands = new ArrayList<>();
        List<State> unusable = new ArrayList<>();
        for (List<State> rows : state.values()) {
            for (State s : rows) {
                if (s.closedAt == null) continue;
                boolean live = s.closedAt.compareTo(since) >= 0 || s.closureSentAt == null
                        || s.closureSentAt.compareTo(since) >= 0;
                if (!live) continue;
                Map<String, Object> snap0 = snapshotOf(s);
                if (snap0 == null || snap0.isEmpty()) {
                    unusable.add(s);   // kopya okunamıyor: kurulamaz, sonsuza dek beklemesin (WARN aşağıda)
                    continue;
                }
                Item it = items.get(s.alertId);
                String newSev = AlarmFeedRows.REASON_LEVEL_CHANGE.equals(s.reason) ? newSeverityOfAlert.get(s.alertId) : null;
                String closerText = it == null ? null : AlarmFeedRows.closer(it.alarm());
                Map<String, Object> row = AlarmFeedRows.closure(snap0, c, s.closedAt, s.reason, newSev,
                        AlarmFeedRows.REASON_RESOLVED.equals(s.reason) ? closerText : null);
                // Clear kipinde eşleşme RCI ile (Clear o RCI'nın bütün olaylarını kapatır), durum kipinde tam anahtarla.
                cands.add(new Cand(s, row, c.clearMode() ? AlarmFeedRows.rciKey(row) : AlarmFeedRows.eventKey(row)));
            }
        }
        // Aynı anahtarda EN GÜNCEL kapanış temsil eder: hiç gönderilmemiş önce, sonra en geç kapanan, sonra gerçek çözülme.
        cands.sort(Comparator.comparing((Cand x) -> x.s().closureSentAt == null ? 0 : 1)
                .thenComparing((Cand x) -> nz(x.s().closedAt), Comparator.reverseOrder())
                .thenComparing((Cand x) -> AlarmFeedRows.REASON_RESOLVED.equals(x.s().reason) ? 0 : 1)
                .thenComparing((Cand x) -> x.s().alertId, Comparator.reverseOrder()));
        Set<String> openKeys = c.clearMode() ? openRciKeys : openEventKeys;
        Map<String, Cand> reps = new LinkedHashMap<>();
        Map<String, List<State>> dupOf = new HashMap<>();
        List<State> suppressedByOpen = new ArrayList<>();
        for (Cand x : cands) {
            if (openKeys.contains(x.dedupeKey())) {
                suppressedByOpen.add(x.s());   // aynı anahtar şu an AÇIK gidiyor: kapanış göndermek açık olayı kapatırdı
            } else if (reps.containsKey(x.dedupeKey())) {
                dupOf.computeIfAbsent(x.dedupeKey(), k -> new ArrayList<>()).add(x.s());
            } else {
                reps.put(x.dedupeKey(), x);
            }
        }
        // Hiç açık bildirilmemiş ama pencerede çözülmüş kapsamdaki alarmlar (bilgi) — durum satırı olmayanlar.
        List<Map<String, Object>> infoRows = new ArrayList<>();
        Set<String> infoKeys = new LinkedHashSet<>();
        for (Item it : resolvedInWindow) {
            if (!state.getOrDefault(it.event().getId(), List.of()).isEmpty()) continue;
            Map<String, Object> row = AlarmFeedRows.resolvedRow(it.alarm(), c, base, rciSalt);
            String k = c.clearMode() ? AlarmFeedRows.rciKey(row) : AlarmFeedRows.eventKey(row);
            if (openKeys.contains(k) || reps.containsKey(k) || !infoKeys.add(k)) continue;
            infoRows.add(row);
        }

        // 8) Tavan: önce kapanışların yarısı (hiç gönderilmemişler, en eskisi önce), sonra açıklar (öncelik sırasıyla),
        //    sonra kalan kapanışlar, en son bilgi satırları. Teslim edilmeyen kapanış sonraki çağrıda gider.
        List<Cand> repList = new ArrayList<>(reps.values());
        repList.sort(Comparator.comparing((Cand x) -> x.s().closureSentAt == null ? 0 : 1)
                .thenComparing((Cand x) -> nz(x.s().closedAt))
                .thenComparing((Cand x) -> x.s().alertId));
        int cap = c.maxRows();
        int total = repList.size() + openRows.size() + infoRows.size();
        List<Cand> keptReps = repList;
        List<Map<String, Object>> keptOpen = openRows;
        List<Map<String, Object>> keptInfo = infoRows;
        if (total > cap) {
            truncated = true;
            int firstShare = Math.min(repList.size(), cap / 2);
            int openRoom = Math.min(openRows.size(), cap - firstShare);
            int closureRoom = Math.min(repList.size(), cap - openRoom);
            int infoRoom = Math.max(0, cap - openRoom - closureRoom);
            keptReps = new ArrayList<>(repList.subList(0, closureRoom));
            keptOpen = new ArrayList<>(openRows.subList(0, openRoom));
            keptInfo = new ArrayList<>(infoRows.subList(0, Math.min(infoRows.size(), infoRoom)));
            log.warn("Alarm akışı satır tavanına ({}) ulaştı — {} satır bu yanıta girmedi (kapanışlar sonraki çağrıda gider)",
                    cap, total - cap);
        }

        // 9) Teslim işareti (ilk gönderim anı; yalnız boşsa yazılır): bu yanıtta GİDEN kapanışlar, temsilcisi giden
        //    yinelenenler, açık anahtarla bastırılanlar ve kopyası okunamayanlar.
        if (!unusable.isEmpty() && nowMs - lastUnusableWarnMs >= 3600_000L) {
            lastUnusableWarnMs = nowMs;
            log.warn("Alarm akışı: {} kapanışın gönderilmiş kopyası okunamadı — merkezi sisteme 'çözüldü' gönderilemedi",
                    unusable.size());
        }
        if (!dryRun) {
            List<State> delivered = new ArrayList<>(suppressedByOpen);
            delivered.addAll(unusable);
            for (Cand x : keptReps) {
                delivered.add(x.s());
                delivered.addAll(dupOf.getOrDefault(x.dedupeKey(), List.of()));
            }
            for (State st : delivered) {
                if (st.closureSentAt != null || st.id == null) continue;
                try {
                    jdbc.update(SQL_STATE_DELIVERED, now, st.id);
                } catch (Exception ex) {
                    log.warn("Alarm akışı kapanış teslim işareti yazılamadı — bir sonraki çağrıda yeniden gönderilir: {}",
                            ex.toString());
                }
            }
        }
        List<Cand> keptClosures = keptReps;

        List<Map<String, Object>> resolvedRows = new ArrayList<>();
        List<Cand> outClosures = new ArrayList<>(keptClosures);
        outClosures.sort(Comparator.comparing((Cand x) -> nz(x.s().closedAt), Comparator.reverseOrder())
                .thenComparing((Cand x) -> x.s().alertId, Comparator.reverseOrder()));
        for (Cand x : outClosures) resolvedRows.add(x.row());
        resolvedRows.addAll(keptInfo);

        List<Map<String, Object>> rows = new ArrayList<>(keptOpen.size() + resolvedRows.size());
        rows.addAll(keptOpen);
        rows.addAll(resolvedRows);

        if (!dryRun) pruneMaybe(nowMs, c);
        return new Feed(List.copyOf(rows), keptOpen.size(), resolvedRows.size(), truncated, now);
    }

    static boolean inScope(Item it, AlarmFeedSettings.Config c) {
        if (!c.levelIncluded(it.level())) return false;
        if (!c.types().isEmpty() && (it.family() == null || !c.types().contains(it.family()))) return false;
        if (!c.teamIds().isEmpty()) {
            for (Long t : it.owners()) if (c.teamIds().contains(t)) return true;
            return false;
        }
        return true;
    }

    private Item item(AlertEvent e, Map<String, CertificateInventory> inv, Map<Long, Team> teams,
                      NocMonitorDirectory.Snapshot snap, Map<String, List<String>> recipientMemo) {
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
        // (AlertOwnership, 7/24 konsoluyla aynı kural). Damgasız bağımsız alarmda izleme satırının takımı.
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
                monitorName, teamInfo(shownTeam, teams, shownTeam != null && shownTeam.equals(e.getTeamId())
                        ? e.getNotificationGroupId() : null, recipientMemo),
                teamInfo(ug, teams, null, recipientMemo));
        return new Item(e, a, level, MonitorTypeCatalog.typeOfAlert(e.getAlertType()), owners);
    }

    /**
     * Takımın alarm postalarının gittiği adresler — alarm yolunun zinciri: damgalı bildirim grubu → takımın varsayılan grubu
     * → takım e-postası ({@code NotificationGroupService.overrideFor}); grup servisi yoksa / okunamazsa takım e-postası.
     */
    private AlarmFeedRows.TeamInfo teamInfo(Long id, Map<Long, Team> teams, Long stampedGroupId,
                                            Map<String, List<String>> memo) {
        if (id == null) return null;
        Team t = teams.get(id);
        List<String> mails = memo.computeIfAbsent(id + "|" + stampedGroupId, k -> recipients(id, t, stampedGroupId));
        return new AlarmFeedRows.TeamInfo(id, t == null ? null : t.getName(), mails);
    }

    private List<String> recipients(Long teamId, Team t, Long stampedGroupId) {
        if (notificationGroups != null) {
            try {
                NotificationGroupService.Override ov = notificationGroups.overrideFor(teamId, stampedGroupId);
                if (ov != null && ov.applies()) return emails(String.join(",", ov.emails()));
            } catch (Exception ex) {
                log.debug("Alarm akışı: bildirim grubu okunamadı (takım {}): {}", teamId, ex.toString());
            }
        }
        return t == null ? List.of() : emails(t.getEmail());
    }

    private Map<Long, Team> teams() {
        Map<Long, Team> out = new HashMap<>();
        for (Team t : teamRepo.findAll()) if (t.getId() != null) out.put(t.getId(), t);
        return out;
    }

    /** Adres alanı (virgül / noktalı virgül / boşlukla ayrılmış) → geçerli görünen, tekil adresler. */
    static List<String> emails(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank() || raw.length() > 20_000) return out;
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
            State s = new State(rs.getLong("id"), alertId, rs.getString("key_hash"),
                    AlarmFeedSettings.canonicalLevel(rs.getString("level")), rs.getString("sent_at"),
                    rs.getString("closed_at"), rs.getString("closed_reason"), rs.getString("closure_sent_at"),
                    rs.getString("snapshot"));
            out.computeIfAbsent(alertId, k -> new ArrayList<>()).add(s);
        }, since, since);
        return out;
    }

    /** Durum satırının kopyası (ilk istekte açılır); okunamazsa null. */
    private Map<String, Object> snapshotOf(State s) {
        if (s.snapshot == null && s.snapshotJson != null) s.snapshot = snapshot(s.snapshotJson);
        return s.snapshot;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshot(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String json(Map<String, Object> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Bellekte kapatır ve koşullu UPDATE'i plana ekler. Yalnız okunmuş (kimlikli) satır kapanır: yeni satır yalnız açık
     * alarmın GÜNCEL anahtarı için eklenir ve aynı yanıtta kapanmaz.
     */
    private void close(State s, String at, String reason, String now, List<Runnable> ops) {
        s.closedAt = at;
        s.reason = reason;
        Long id = s.id;
        if (id != null) ops.add(() -> jdbc.update(SQL_STATE_CLOSE, at, reason, now, id));
    }

    private void runOps(List<Runnable> ops) {
        for (Runnable op : ops) {
            try {
                op.run();
            } catch (Exception ex) {
                log.warn("Alarm akışı olay durumu yazılamadı — bir sonraki çağrıda yeniden denenir: {}", ex.toString());
            }
        }
    }

    private void insert(State s, String snapshotJson) {
        try {
            jdbc.update(SQL_STATE_INSERT, s.alertId, s.keyHash, s.level, s.sentAt, snapshotJson);
        } catch (DataIntegrityViolationException dup) {
            // Başka bir pod aynı (alarm, anahtar) çiftini az önce yazdı — aynı anlam; kapalıysa yeniden aç.
            jdbc.update(SQL_STATE_REOPEN_BY_KEY, s.alertId, s.keyHash);
        }
    }

    private void reopen(State s) {
        if (s.id != null) jdbc.update(SQL_STATE_REOPEN, s.id);
    }

    private void pruneMaybe(long nowMs, AlarmFeedSettings.Config c) {
        if (nowMs - lastPruneMs < PRUNE_EVERY_MS) return;
        lastPruneMs = nowMs;
        prune(nowMs, c);
    }

    /**
     * Saatlik temizlik (her pod; silmeler idempotent) — akış kapalıyken de koşar: o zaman 30 günden eski bütün durum
     * satırları (takım adı / adres kopyaları) silinir. Açıkken yalnız bitmiş kapanışlar.
     */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 900_000L)
    public void scheduledPrune() {
        long nowMs = clock.getAsLong();
        lastPruneMs = nowMs;
        prune(nowMs, config());
    }

    void prune(long nowMs, AlarmFeedSettings.Config c) {
        try {
            if (!c.enabled()) {
                int n = jdbc.update(SQL_STATE_PRUNE_DISABLED, ISO.format(Instant.ofEpochMilli(nowMs - DISABLED_KEEP_MS)));
                if (n > 0) log.info("Alarm akışı kapalı: 30 günden eski {} olay durumu silindi", n);
                return;
            }
            String keep = ISO.format(Instant.ofEpochMilli(nowMs - (c.resolvedMinutes() * 60_000L + PRUNE_AFTER_MS)));
            int a = jdbc.update(SQL_STATE_PRUNE_SENT, keep, keep);
            int b = jdbc.update(SQL_STATE_PRUNE_UNDELIVERED, ISO.format(Instant.ofEpochMilli(nowMs - UNDELIVERED_MAX_MS)));
            if (b > 0) log.warn("Alarm akışı: {} kapanış 7 gün içinde hiç teslim edilemedi ve bırakıldı", b);
            if (a > 0) log.debug("Alarm akışı: {} eski olay durumu silindi", a);
        } catch (Exception ex) {
            log.debug("Alarm akışı olay durumu temizlenemedi: {}", ex.toString());
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
