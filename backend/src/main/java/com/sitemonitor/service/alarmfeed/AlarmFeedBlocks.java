package com.sitemonitor.service.alarmfeed;

import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.SchedulerService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Alarm akışının otomatik IP engeli (2026-10-31, kullanıcı isteği: "aynı IP dakikada yoğun çağırırsa doğrudan engelle,
 * kendimizi koruyalım"). Aynı IP'nin dakikadaki çağrısı (kümede ortak sayaç, {@code RateLimitCounterService}) eşiği aşınca
 * {@code alarm_feed_blocks}'a süreli engel yazılır; engel sürerken o IP'nin her isteği <b>veritabanına dokunmadan</b>
 * (pod belleği) 429 {@code ALARM_FEED_IP_BLOCKED} alır — sel sırasında sayaç yazımı, günlük, akış hesabı yapılmaz.
 *
 * <p><b>Küme.</b> Engel satırı tekil (IP birincil anahtar); yeni engel koşullu yazılır (okunan bitiş anına CAS) — yalnız
 * yazan pod denetime {@code ALARM_FEED_IP_BLOCKED} yazar. Diğer pod'lar etkin engelleri en çok {@link #REFRESH_MS} aralıkla
 * (yalnız istek gelirken, tek uçuş) okur; elle kaldırma da böyle yayılır. Bu pod'un kendi yazımları (engel / kaldırma)
 * sorgu başladıktan sonra yapıldıysa yenileme onları ezmez ({@code recentWrites}). Veritabanına yazılamazsa engel bu pod'un
 * belleğinde yine uygulanır (sel altında asla açık kalmaz) ve veritabanı dönünce yazılır.
 *
 * <p><b>Süre.</b> Taban süre ayardan ({@code block-minutes}); "tekrarında katla" açıkken son engeli 24 saat içinde bitmiş IP'nin
 * yeni engeli öncekinin iki katı (en çok {@link AlarmFeedSettings#BLOCK_MINUTES_MAX} dk). Elle kaldırılan engel katlamayı
 * sıfırlar (yönetici affetti).
 *
 * <p><b>Denetim bütçesi.</b> IP döndüren bir sel her IP için bir denetim kaydı + WARN üretmesin: pod başına dakikada en çok
 * {@link #AUDIT_PER_MINUTE} engel tek tek denetlenir, fazlası sayılır ve bir sonraki engelde TEK özet kaydıyla yazılır
 * (engellerin tamamı yine {@code alarm_feed_blocks} tablosunda ve "Engellenen IP'ler" listesinde).
 */
@Slf4j
@Service
public class AlarmFeedBlocks {

    /** Etkin engel listesinin pod belleğinde tazelenme aralığı (ms). */
    static final long REFRESH_MS = 5_000L;
    /** Katlama penceresi: son engel bu süre içinde bittiyse yeni engel iki katı. */
    static final long ESCALATE_WINDOW_MS = 24L * 3600_000L;
    static final int ACTIVE_READ_MAX = 1_000;
    static final int HISTORY_MAX = 100;
    static final int LOCAL_MAX = 10_000;
    static final int PERSIST_PER_REFRESH = 100;
    static final int QUERY_TIMEOUT_S = 3;
    /** Pod başına dakikada tek tek denetlenen engel sayısı. */
    static final int AUDIT_PER_MINUTE = 10;

    public static final String STATE_ACTIVE = "active";
    public static final String STATE_EXPIRED = "expired";
    public static final String STATE_UNBLOCKED = "unblocked";

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    static final String SQL_ACTIVE = "SELECT client_ip, blocked_until_ms FROM alarm_feed_blocks "
            + "WHERE blocked_until_ms > ? ORDER BY blocked_until_ms DESC FETCH FIRST " + ACTIVE_READ_MAX + " ROWS ONLY";
    static final String SQL_READ = "SELECT blocked_until_ms, minutes, block_count, unblocked_at_ms FROM alarm_feed_blocks "
            + "WHERE client_ip = ?";
    static final String SQL_INSERT = "INSERT INTO alarm_feed_blocks (client_ip, blocked_at_ms, blocked_until_ms, minutes, hits, "
            + "threshold, block_count, last_user_agent, blocked_by_pod, unblocked_at_ms, unblocked_by, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, NULL, NULL, ?)";
    /** Yeni engel — okunan (bitmiş) bitiş anına koşullu: iki pod aynı anda yazamaz. */
    static final String SQL_REBLOCK = "UPDATE alarm_feed_blocks SET blocked_at_ms = ?, blocked_until_ms = ?, minutes = ?, "
            + "hits = ?, threshold = ?, block_count = COALESCE(block_count, 0) + 1, last_user_agent = ?, blocked_by_pod = ?, "
            + "unblocked_at_ms = NULL, unblocked_by = NULL, updated_at = ? WHERE client_ip = ? AND blocked_until_ms = ?";
    static final String SQL_UNBLOCK = "UPDATE alarm_feed_blocks SET blocked_until_ms = ?, unblocked_at_ms = ?, unblocked_by = ?, "
            + "updated_at = ? WHERE client_ip = ? AND blocked_until_ms > ?";
    private static final String LIST_COLS = "SELECT client_ip, blocked_at_ms, blocked_until_ms, minutes, hits, threshold, "
            + "block_count, last_user_agent, blocked_by_pod, unblocked_at_ms, unblocked_by FROM alarm_feed_blocks ";
    /** Etkin engellerin HEPSİ (en çok {@link #ACTIVE_READ_MAX}; +1 = kesildi işareti). */
    static final String SQL_LIST_ACTIVE = LIST_COLS + "WHERE blocked_until_ms > ? ORDER BY blocked_at_ms DESC, client_ip "
            + "FETCH FIRST " + (ACTIVE_READ_MAX + 1) + " ROWS ONLY";
    /** Geçmiş (biten / kaldırılan), en yeni {@link #HISTORY_MAX}. */
    static final String SQL_LIST_HISTORY = LIST_COLS + "WHERE blocked_until_ms <= ? ORDER BY blocked_at_ms DESC, client_ip "
            + "FETCH FIRST " + HISTORY_MAX + " ROWS ONLY";

    /** Bir engel kararı: bitiş anı, süre, bu çağrı YENİ engel mi yazdı, toplam engel sayısı (0 = bilinmiyor). */
    public record Placed(long until, int minutes, boolean newBlock, int blockCount) {}

    /** Ekran listesi: satırlar (etkinler önce), etkin sayısı, etkin liste kesildi mi. */
    public record Listing(List<Map<String, Object>> rows, int active, boolean activeTruncated) {}

    private final JdbcTemplate jdbc;
    LongSupplier clock = System::currentTimeMillis;
    private AuditService auditService;

    /** Etkin engeller (IP → bitiş, epoch ms) — veritabanının son okunuşu + bu pod'un yazdıkları. */
    private volatile Map<String, Long> active = new ConcurrentHashMap<>();
    /** Veritabanına yazılamadığı için yalnız bu pod'da uygulanan engeller (veritabanı dönünce yazılır). */
    private final Map<String, Long> localOnly = new ConcurrentHashMap<>();
    /** Bu pod'un yazımları (IP → [sıra, bitiş]; bitiş 0 = kaldırıldı) — yenileme sorgusundan SONRAKİLER ezilmez. */
    private final Map<String, long[]> recentWrites = new ConcurrentHashMap<>();
    private long writeSeq;
    private final Object writeLock = new Object();
    private volatile long loadedAt = Long.MIN_VALUE / 2;
    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile long lastWarnMs;
    private long auditWindowStart = Long.MIN_VALUE / 2;
    private int auditsInWindow;
    private int auditsSuppressed;

    @Autowired
    public AlarmFeedBlocks(JdbcTemplate jdbc) {
        // Kendi şablonu: istek iş parçacığı kilit beklemesinde takılmasın (paylaşılan şablona dokunulmaz).
        JdbcTemplate own = jdbc == null || jdbc.getDataSource() == null ? jdbc : new JdbcTemplate(jdbc.getDataSource());
        if (own != null && own != jdbc) own.setQueryTimeout(QUERY_TIMEOUT_S);
        this.jdbc = own;
    }

    /** Test: verilen şablon olduğu gibi kullanılır (sorgu kancası / hata enjeksiyonu için). */
    AlarmFeedBlocks(JdbcTemplate jdbc, boolean useAsIs) {
        this.jdbc = jdbc;
    }

    @Autowired(required = false)
    void setAuditService(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * IP şu an engelli mi? Engelliyse bitiş anı (epoch ms), değilse 0. Veritabanına en çok {@link #REFRESH_MS}'de bir gider
     * (tek uçuş); okuma hatası son bilinen listeyi korur.
     */
    public long blockedUntil(String ip, long now) {
        refreshMaybe(now);
        String k = key(ip);
        Long u = active.get(k);
        if (u != null && u > now) return u;
        Long l = localOnly.get(k);
        if (l != null && l > now) return l;
        return 0L;
    }

    private void refreshMaybe(long now) {
        if (jdbc == null || now - loadedAt < REFRESH_MS) return;
        if (!loading.compareAndSet(false, true)) return;
        boolean ok = false;
        try {
            long seqAtStart;
            synchronized (writeLock) {
                seqAtStart = writeSeq;
            }
            Map<String, Long> fresh = new ConcurrentHashMap<>();
            jdbc.query(SQL_ACTIVE, rs -> {
                fresh.put(rs.getString(1), rs.getLong(2));
            }, now);
            synchronized (writeLock) {
                // Sorgu başladıktan SONRA bu pod'un yaptığı engel / kaldırma yeni anlık görüntüde olmayabilir — üstüne uygula.
                for (Map.Entry<String, long[]> w : recentWrites.entrySet()) {
                    if (w.getValue()[0] <= seqAtStart) continue;
                    if (w.getValue()[1] > 0) fresh.put(w.getKey(), w.getValue()[1]);
                    else fresh.remove(w.getKey());
                }
                recentWrites.entrySet().removeIf(w -> w.getValue()[0] <= seqAtStart);
                active = fresh;
            }
            ok = true;
        } catch (Exception e) {
            warn("Alarm akışı etkin IP engelleri okunamadı — son bilinen liste kullanılıyor: {}", e);
        } finally {
            // Damga denemeden SONRAKİ an: veritabanı yanıt vermezken her istek ayrı bir bekleme başlatmasın
            loadedAt = Math.max(now, clock.getAsLong());
            loading.set(false);
        }
        if (ok) {
            localOnly.entrySet().removeIf(e -> e.getValue() <= now);
            persistLocal(now);
        }
    }

    /** Bu pod'un yazımını kaydeder ve belleğe uygular (yenilemeyle yarışmaz). {@code until == 0} = kaldırıldı. */
    private void recordWrite(String k, long until) {
        synchronized (writeLock) {
            writeSeq++;
            recentWrites.put(k, new long[]{writeSeq, until});
            if (recentWrites.size() > LOCAL_MAX) recentWrites.clear();
            if (until > 0) active.put(k, until);
            else active.remove(k);
        }
    }

    /**
     * Eşiği aşan IP'yi engeller (zaten engelliyse o engeli döndürür). Asla istisna atmaz; veritabanına yazılamazsa engel bu
     * pod'un belleğinde uygulanır.
     *
     * @param hits      engeli tetikleyen dakikadaki çağrı sayısı
     * @param request   denetim kaydı için (null olabilir)
     */
    public Placed place(String ip, int hits, String userAgent, AlarmFeedSettings.Config c, long now,
                        HttpServletRequest request) {
        String k = key(ip);
        int base = c.blockMinutes();
        if (jdbc != null) {
            try {
                Placed p = write(k, hits, userAgent, c.blockThreshold(), base, c.blockEscalate(), now);
                if (p != null) {
                    if (p.newBlock()) announce(k, p, hits, c.blockThreshold(), userAgent, request, now);
                    return p;
                }
            } catch (Exception e) {
                warn("Alarm akışı IP engeli veritabanına yazılamadı — engel bu pod'da uygulanıyor: {}", e);
            }
        }
        long until = now + base * 60_000L;
        if (localOnly.size() >= LOCAL_MAX) localOnly.entrySet().removeIf(e -> e.getValue() <= now);
        if (localOnly.size() >= LOCAL_MAX) localOnly.clear();
        localOnly.put(k, until);
        Placed p = new Placed(until, base, true, 0);
        announce(k, p, hits, c.blockThreshold(), userAgent, request, now);
        return p;
    }

    /**
     * Veritabanına yazar: süren engel varsa onu döndürür (yeni değil), yoksa yeni engel (INSERT ya da okunan bitişe CAS).
     * Yarış üç kez kaybedilirse null (çağıran yerel engele düşer).
     */
    private Placed write(String k, int hits, String userAgent, int threshold, int base, boolean escalate, long now) {
        String at = ISO.format(Instant.ofEpochMilli(now));
        String pod = fit(SchedulerService.hostname(), 80);
        String ua = fit(userAgent, 500);
        for (int attempt = 0; attempt < 4; attempt++) {
            Row r = read(k);
            if (r != null && r.until > now) {
                recordWrite(k, r.until);
                return new Placed(r.until, r.minutes == null ? base : r.minutes, false, r.count == null ? 0 : r.count);
            }
            if (attempt == 3) break;
            int minutes = minutesFor(r, base, escalate, now);
            long until = now + minutes * 60_000L;
            if (r == null) {
                try {
                    jdbc.update(SQL_INSERT, k, now, until, minutes, hits, threshold, ua, pod, at);
                } catch (DataIntegrityViolationException dup) {
                    continue;   // başka pod az önce yazdı — yeniden oku
                }
                recordWrite(k, until);
                return new Placed(until, minutes, true, 1);
            }
            int n = jdbc.update(SQL_REBLOCK, now, until, minutes, hits, threshold, ua, pod, at, k, r.until);
            if (n == 1) {
                recordWrite(k, until);
                return new Placed(until, minutes, true, (r.count == null ? 0 : r.count) + 1);
            }
        }
        return null;
    }

    /** Veritabanı dönünce yalnız bellekteki engelleri yazar (denetim zaten yazıldı); en çok {@link #PERSIST_PER_REFRESH}. */
    private void persistLocal(long now) {
        if (localOnly.isEmpty()) return;
        int n = 0;
        String pod = fit(SchedulerService.hostname(), 80);
        String at = ISO.format(Instant.ofEpochMilli(now));
        for (Map.Entry<String, Long> e : new ArrayList<>(localOnly.entrySet())) {
            if (n++ >= PERSIST_PER_REFRESH) break;
            long until = e.getValue();
            if (until <= now) {
                localOnly.remove(e.getKey(), until);
                continue;
            }
            try {
                int minutes = (int) Math.max(1L, (until - now + 59_999L) / 60_000L);
                Row r = read(e.getKey());
                boolean done;
                if (r == null) {
                    try {
                        jdbc.update(SQL_INSERT, e.getKey(), now, until, minutes, null, null, null, pod, at);
                        done = true;
                    } catch (DataIntegrityViolationException dup) {
                        done = false;   // sonraki yenilemede yeniden okunur
                    }
                } else if (r.until >= until) {
                    done = true;   // veritabanında aynı ya da daha uzun engel var
                } else {
                    done = jdbc.update(SQL_REBLOCK, now, until, minutes, null, null, null, pod, at, e.getKey(), r.until) == 1;
                }
                if (done) {
                    localOnly.remove(e.getKey(), until);
                    recordWrite(e.getKey(), Math.max(until, r == null ? 0L : r.until));
                }
            } catch (Exception ex) {
                warn("Alarm akışı yerel IP engeli veritabanına yazılamadı — sonra yeniden denenir: {}", ex);
                return;
            }
        }
    }

    /** Taban süre; katlama açıkken 24 saat içinde biten (elle kaldırılmamış) engelin iki katı, en çok 24 saat. */
    static int minutesFor(Row r, int base, boolean escalate, long now) {
        if (r == null || !escalate || r.unblockedAt != null || r.minutes == null) return base;
        if (r.until <= now - ESCALATE_WINDOW_MS) return base;
        long doubled = Math.max(base, (long) r.minutes * 2L);
        return (int) Math.min(AlarmFeedSettings.BLOCK_MINUTES_MAX, doubled);
    }

    /** WARN + denetim — pod başına dakikada {@link #AUDIT_PER_MINUTE}; fazlası sonraki dakikadaki ilk engelde tek özetle. */
    private void announce(String ip, Placed p, int hits, int threshold, String ua, HttpServletRequest request, long now) {
        int carried;
        boolean individual;
        synchronized (this) {
            carried = 0;
            if (now - auditWindowStart >= 60_000L) {
                auditWindowStart = now;
                auditsInWindow = 0;
                carried = auditsSuppressed;
                auditsSuppressed = 0;
            }
            individual = ++auditsInWindow <= AUDIT_PER_MINUTE;
            if (!individual) auditsSuppressed++;
        }
        if (carried > 0) summary(carried, request);
        if (!individual) return;
        log.warn("Alarm akışı: {} IP'si dakikada {} çağrıyla eşiği ({}) aştı — {} dk engellendi{}", ip, hits, threshold,
                p.minutes(), p.blockCount() > 1 ? " (" + p.blockCount() + ". engel)" : "");
        if (auditService == null) return;
        String reason = "Dakikada " + hits + " çağrı (eşik " + threshold + ") — " + p.minutes() + " dk engel, bitiş "
                + ISO.format(Instant.ofEpochMilli(p.until())) + " UTC" + (p.blockCount() > 1 ? ", " + p.blockCount() + ". engel" : "");
        try {
            if (request != null) {
                auditService.recordSecurityEvent("ALARM_FEED_IP_BLOCKED", request, null, "ALARM_FEED", ip, reason);
                return;
            }
            auditService.recordAction("ALARM_FEED_IP_BLOCKED", "alarm-feed-client", null, null, "EXTERNAL", "ALARM_FEED", ip,
                    AuditDetail.of("client_ip", ip, "hits", hits, "threshold", threshold, "minutes", p.minutes(),
                            "blocked_until", ISO.format(Instant.ofEpochMilli(p.until())), "block_count", p.blockCount()),
                    ip, ua, null);
        } catch (Exception e) {
            warn("Alarm akışı IP engeli denetime yazılamadı: {}", e);
        }
    }

    private void summary(int count, HttpServletRequest request) {
        log.warn("Alarm akışı: önceki dakikada {} IP daha engellendi (tek tek denetlenmedi — Engellenen IP'ler listesinde)", count);
        if (auditService == null) return;
        String reason = "Önceki dakikada " + count + " IP daha engellendi (dakikada " + AUDIT_PER_MINUTE
                + " engelden fazlası tek tek denetlenmez; tamamı Engellenen IP'ler listesinde)";
        try {
            if (request != null) {
                auditService.recordSecurityEvent("ALARM_FEED_IP_BLOCKED", request, null, "ALARM_FEED", "*", reason);
                return;
            }
            auditService.recordAction("ALARM_FEED_IP_BLOCKED", "alarm-feed-client", null, null, "EXTERNAL", "ALARM_FEED", "*",
                    AuditDetail.of("suppressed_blocks", count), null, null, null);
        } catch (Exception e) {
            warn("Alarm akışı IP engeli özeti denetime yazılamadı: {}", e);
        }
    }

    /**
     * Engeli elle kaldırır. Döner: bir engel kaldırıldı mı (veritabanında ya da yalnız bu pod'un belleğinde).
     *
     * @throws org.springframework.dao.DataAccessException veritabanı okunamazsa (yönetici ekranı hatayı görmeli)
     */
    public boolean unblock(String ip, String by, long now) {
        String k = key(ip);
        boolean local = localOnly.remove(k) != null;
        int n = jdbc == null ? 0 : jdbc.update(SQL_UNBLOCK, now, now, fit(by, 200), ISO.format(Instant.ofEpochMilli(now)), k, now);
        recordWrite(k, 0L);
        return n > 0 || local;
    }

    /**
     * Ekran listesi: etkin engellerin hepsi (en çok {@link #ACTIVE_READ_MAX}, kesildiyse işaretli) + en yeni
     * {@link #HISTORY_MAX} geçmiş; bu pod'un yalnız bellekteki etkin engelleri de (aynı IP'nin bitmiş satırının yerine).
     */
    public Listing list(long now) {
        List<Map<String, Object>> activeRows = new ArrayList<>();
        List<Map<String, Object>> history = new ArrayList<>();
        if (jdbc != null) {
            jdbc.query(SQL_LIST_ACTIVE, rs -> {
                activeRows.add(row(rs, now));
            }, now);
            jdbc.query(SQL_LIST_HISTORY, rs -> {
                history.add(row(rs, now));
            }, now);
        }
        boolean truncated = activeRows.size() > ACTIVE_READ_MAX;
        if (truncated) activeRows.subList(ACTIVE_READ_MAX, activeRows.size()).clear();
        Set<String> activeIps = new HashSet<>();
        for (Map<String, Object> m : activeRows) activeIps.add((String) m.get("ip"));
        List<Map<String, Object>> locals = new ArrayList<>();
        for (Map.Entry<String, Long> e : localOnly.entrySet()) {
            if (e.getValue() <= now || activeIps.contains(e.getKey())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ip", e.getKey());
            m.put("blocked_at", null);
            m.put("blocked_until", iso(e.getValue()));
            m.put("state", STATE_ACTIVE);
            m.put("remaining_seconds", (e.getValue() - now + 999) / 1000);
            m.put("local_only", true);
            locals.add(m);
            activeIps.add(e.getKey());
        }
        List<Map<String, Object>> out = new ArrayList<>(locals);
        out.addAll(activeRows);
        for (Map<String, Object> m : history) if (!activeIps.contains((String) m.get("ip"))) out.add(m);
        return new Listing(out, locals.size() + activeRows.size(), truncated);
    }

    private static Map<String, Object> row(java.sql.ResultSet rs, long now) throws java.sql.SQLException {
        String ip = rs.getString("client_ip");
        long until = rs.getLong("blocked_until_ms");
        Long unblockedAt = rs.getObject("unblocked_at_ms", Long.class);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ip", ip);
        m.put("blocked_at", iso(rs.getObject("blocked_at_ms", Long.class)));
        m.put("blocked_until", iso(until));
        m.put("state", until > now ? STATE_ACTIVE : unblockedAt != null ? STATE_UNBLOCKED : STATE_EXPIRED);
        m.put("remaining_seconds", until > now ? (until - now + 999) / 1000 : 0L);
        m.put("minutes", rs.getObject("minutes", Integer.class));
        m.put("hits", rs.getObject("hits", Integer.class));
        m.put("threshold", rs.getObject("threshold", Integer.class));
        m.put("block_count", rs.getObject("block_count", Integer.class));
        m.put("last_user_agent", rs.getString("last_user_agent"));
        m.put("blocked_by_pod", rs.getString("blocked_by_pod"));
        m.put("unblocked_at", iso(unblockedAt));
        m.put("unblocked_by", rs.getString("unblocked_by"));
        m.put("local_only", false);
        return m;
    }

    /** Bu pod'un bildiği etkin engel sayısı (bellek; istek yolunda tazelenir). */
    public int activeCount(long now) {
        int n = 0;
        for (Long u : active.values()) if (u > now) n++;
        for (Map.Entry<String, Long> e : localOnly.entrySet()) {
            if (e.getValue() > now && !active.containsKey(e.getKey())) n++;
        }
        return n;
    }

    // ── yardımcılar ─────────────────────────────────────────────────────────────────────────────

    record Row(long until, Integer minutes, Integer count, Long unblockedAt) {}

    private Row read(String ip) {
        return jdbc.query(SQL_READ, rs -> rs.next() ? new Row(rs.getLong(1), rs.getObject(2, Integer.class),
                rs.getObject(3, Integer.class), rs.getObject(4, Long.class)) : null, ip);
    }

    static String key(String ip) {
        String s = ip == null || ip.isBlank() ? "?" : ip.trim();
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    /** Epoch ms → UTC ISO (saniye, bölgesiz). */
    public static String isoUtc(long ms) {
        return ISO.format(Instant.ofEpochMilli(ms));
    }

    private static String iso(Long ms) {
        return ms == null || ms <= 0 ? null : ISO.format(Instant.ofEpochMilli(ms));
    }

    private static String fit(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void warn(String msg, Exception e) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs < 60_000L) return;
        lastWarnMs = now;
        log.warn(msg, e.toString());
    }
}
