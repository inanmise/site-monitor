package com.sitemonitor.service.alarmfeed;

import com.sitemonitor.model.AlarmFeedRequest;
import com.sitemonitor.repository.AlarmFeedRequestRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Alarm akışının dış çağrı günlüğü (2026-10-30). Her çağrı {@code alarm_feed_requests}'e bir satır; yanıt gövdesi
 * (saklama açıksa) gzip'li ve SHA-256 özetiyle {@code alarm_feed_responses}'a BİR kez; çağıran IP {@code alarm_feed_callers}'a.
 *
 * <p><b>Denetim.</b> Bir IP'nin İLK KABUL edilen çağrısı {@code ALARM_FEED_NEW_CALLER} ({@code first_ok_at}'ı koşullu
 * yazan pod yazar — çok pod'da da bir kez; önce reddedilip sonra izin listesine eklenen IP de denetime düşer); reddedilen çağrılar {@code ALARM_FEED_ACCESS_DENIED} — IP + neden başına 10 dakikada bir (dakikalık bir istemci denetim
 * günlüğünü boğmasın; ayrıntının tamamı çağrı günlüğündedir).
 *
 * <p><b>Akışı asla durdurmaz.</b> Günlük yazılamazsa istemci yanıtını yine alır; hata sayılır (Ayarlar sayfasında görünür) ve
 * dakikada bir WARN yazılır. Oran sınırına takılan çağrılar IP başına dakikada BİR satır yazar (sel günlüğü doldurmasın).
 */
@Slf4j
@Service
public class AlarmFeedRequestLog {

    static final DateTimeFormatter ISO_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    static final long DENIED_AUDIT_EVERY_MS = 10 * 60_000L;
    static final long RATE_LOG_EVERY_MS = 60_000L;
    static final int THROTTLE_MAP_MAX = 2_000;
    static final int KNOWN_HASHES = 64;
    /** Aynı gövdenin {@code last_used_at}'i pod başına en çok bu sıklıkla tazelenir. */
    static final long BODY_TOUCH_EVERY_MS = 10 * 60_000L;
    /** Pod başına dakikada en çok bu kadar REDDEDİLEN çağrı günlüğe yazılır (IP döndüren sel tabloları / denetimi doldurmasın). */
    static final int REFUSED_LOG_PER_MINUTE = 60;
    /** Pod başına dakikada en çok bu kadar ALARM_FEED_ACCESS_DENIED denetim olayı. */
    static final int DENIED_AUDIT_PER_MINUTE = 10;

    public static final String OUTCOME_OK = "OK";
    public static final String OUTCOME_DISABLED = "DISABLED";
    public static final String OUTCOME_IP_DENIED = "IP_DENIED";
    public static final String OUTCOME_RATE_LIMITED = "RATE_LIMITED";
    public static final String OUTCOME_ERROR = "ERROR";

    static final String SQL_RESPONSE_INSERT = "INSERT INTO alarm_feed_responses (hash, body, content_type, raw_bytes, "
            + "row_count, created_at, last_used_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
    static final String SQL_RESPONSE_TOUCH = "UPDATE alarm_feed_responses SET last_used_at = ? WHERE hash = ?";
    static final String SQL_CALLER_UPDATE = "UPDATE alarm_feed_callers SET last_seen_at = ?, call_count = call_count + 1, "
            + "last_user_agent = ?, last_outcome = ? WHERE client_ip = ?";
    static final String SQL_CALLER_INSERT = "INSERT INTO alarm_feed_callers (client_ip, first_seen_at, last_seen_at, "
            + "call_count, last_user_agent, last_outcome) VALUES (?, ?, ?, 1, ?, ?)";
    /** İlk kabul edilen çağrı anı — koşullu (yalnız boşsa); 1 satır = bu pod yeni çağıranı denetler. */
    static final String SQL_CALLER_FIRST_OK = "UPDATE alarm_feed_callers SET first_ok_at = ? "
            + "WHERE client_ip = ? AND first_ok_at IS NULL";

    private final AlarmFeedRequestRepository requests;
    private final JdbcTemplate jdbc;
    private AuditService auditService;

    LongSupplier clock = System::currentTimeMillis;

    private final AtomicLong writeFailures = new AtomicLong();
    private volatile long lastFailureWarnMs;
    private final Map<String, Long> deniedAudited = new ConcurrentHashMap<>();
    private final Map<String, Long> rateLogged = new ConcurrentHashMap<>();
    /** Bu pod'un ilk kabul anını yazdığı / gördüğü IP'ler (ek ifade olmasın; sınırlı). */
    private final Map<String, Boolean> knownOk = new ConcurrentHashMap<>();
    /** Bu pod'un bildiği gövde özetleri → son tazeleme anı (erişim sırasıyla, en çok {@link #KNOWN_HASHES}). */
    private final LinkedHashMap<String, Long> knownHashes = new LinkedHashMap<>(16, 0.75f, true);
    private final AtomicLong suppressed = new AtomicLong();
    private long refusedWindowStart;
    private int refusedInWindow;
    private long deniedWindowStart;
    private int deniedInWindow;
    private volatile long lastSuppressWarnMs;

    public AlarmFeedRequestLog(AlarmFeedRequestRepository requests, JdbcTemplate jdbc) {
        this.requests = requests;
        this.jdbc = jdbc;
    }

    @Autowired(required = false)
    void setAuditService(AuditService auditService) {
        this.auditService = auditService;
    }

    /** Bir çağrının günlüğe geçecek bilgisi. */
    public record Call(long startedMs, String clientIp, String remoteAddr, String forwardedFor, String userAgent,
                       String method, String query, String requestId, String servedBy) {}

    /** Yanıt (gövde yoksa null). */
    public record Result(int status, String outcome, String body, String contentType, Integer rowCount, Integer openCount,
                         Integer resolvedCount, Boolean truncated, String format, String error) {}

    /** Bu pod'da günlük yazımı kaç kez düştü (açılıştan beri). */
    public long writeFailures() {
        return writeFailures.get();
    }

    /** Bu pod'da dakikalık üst sınır yüzünden günlüğe yazılmayan reddedilmiş çağrı sayısı (açılıştan beri). */
    public long suppressedRefusals() {
        return suppressed.get();
    }

    /**
     * Çağrıyı yazar. Asla istisna atmaz.
     *
     * @param storeBody gövde saklansın mı (ayar)
     */
    public void record(Call call, Result res, boolean storeBody, jakarta.servlet.http.HttpServletRequest request) {
        long now = clock.getAsLong();
        boolean ok = OUTCOME_OK.equals(res.outcome());
        if (!ok) {
            if (OUTCOME_RATE_LIMITED.equals(res.outcome())
                    && !firstInWindow(rateLogged, call.clientIp(), now, RATE_LOG_EVERY_MS)) {
                return;   // sel: IP başına dakikada bir satır
            }
            if (!refusedBudget(now)) {
                suppressed.incrementAndGet();
                if (now - lastSuppressWarnMs >= 60_000L) {
                    lastSuppressWarnMs = now;
                    log.warn("Alarm akışı: dakikada {}'dan fazla reddedilen çağrı — fazlası günlüğe yazılmadı (bu pod)",
                            REFUSED_LOG_PER_MINUTE);
                }
                return;
            }
        }
        try {
            AlarmFeedRequest r = new AlarmFeedRequest();
            r.setRequestedAt(ISO_MS.format(Instant.ofEpochMilli(call.startedMs())));
            r.setClientIp(call.clientIp());
            r.setRemoteAddr(call.remoteAddr());
            r.setForwardedFor(call.forwardedFor());
            r.setUserAgent(call.userAgent());
            r.setMethod(call.method());
            r.setQuery(call.query());
            r.setRequestId(call.requestId());
            r.setServedBy(call.servedBy());
            r.setStatus(res.status());
            r.setOutcome(res.outcome());
            r.setRowCount(res.rowCount());
            r.setOpenCount(res.openCount());
            r.setResolvedCount(res.resolvedCount());
            r.setTruncated(res.truncated());
            r.setFormat(res.format());
            r.setError(res.error());
            r.setDurationMs(Math.max(0L, now - call.startedMs()));
            if (res.body() != null) {
                byte[] raw = res.body().getBytes(StandardCharsets.UTF_8);
                r.setResponseBytes((long) raw.length);
                String hash = sha256(raw);
                r.setResponseHash(hash);
                // Yalnız kabul edilen çağrının gövdesi saklanır: ret gövdesi sabit metindir (durum + kod satırda var).
                if (storeBody && ok) storeBody(hash, raw, res.contentType(), res.rowCount(), now);
            }
            requests.save(r);
        } catch (Exception e) {
            failed("çağrı satırı", e, now);
        }
        touchCaller(call, res.outcome(), now, ok);
        if (!OUTCOME_OK.equals(res.outcome()) && !OUTCOME_ERROR.equals(res.outcome())) {
            auditDenied(call, res.outcome(), now, request);
        }
    }

    private void storeBody(String hash, byte[] raw, String contentType, Integer rows, long now) {
        Long touched;
        synchronized (knownHashes) {
            touched = knownHashes.get(hash);
        }
        if (touched != null && now - touched < BODY_TOUCH_EVERY_MS) return;
        String at = ISO.format(Instant.ofEpochMilli(now));
        try {
            int n = jdbc.update(SQL_RESPONSE_TOUCH, at, hash);   // var olan gövde: son kullanım tazelenir
            if (n == 0) {
                try {
                    jdbc.update(SQL_RESPONSE_INSERT, hash, gzip(raw), contentType, (long) raw.length, rows, at, at);
                } catch (DataIntegrityViolationException dup) {
                    // başka bir pod aynı gövdeyi az önce yazdı — aynı içerik
                }
            }
            synchronized (knownHashes) {
                knownHashes.put(hash, now);
                while (knownHashes.size() > KNOWN_HASHES) {
                    String eldest = knownHashes.keySet().iterator().next();
                    knownHashes.remove(eldest);
                }
            }
        } catch (Exception e) {
            failed("yanıt gövdesi", e, now);
        }
    }

    private synchronized boolean refusedBudget(long now) {
        if (now - refusedWindowStart >= 60_000L) {
            refusedWindowStart = now;
            refusedInWindow = 0;
        }
        return ++refusedInWindow <= REFUSED_LOG_PER_MINUTE;
    }

    private synchronized boolean deniedAuditBudget(long now) {
        if (now - deniedWindowStart >= 60_000L) {
            deniedWindowStart = now;
            deniedInWindow = 0;
        }
        return ++deniedInWindow <= DENIED_AUDIT_PER_MINUTE;
    }

    private void touchCaller(Call call, String outcome, long now, boolean ok) {
        String ip = call.clientIp() == null || call.clientIp().isBlank() ? "?" : AlarmFeedRequest.fit(call.clientIp(), 64);
        String at = ISO.format(Instant.ofEpochMilli(now));
        String ua = AlarmFeedRequest.fit(call.userAgent(), 500);
        try {
            int n = jdbc.update(SQL_CALLER_UPDATE, at, ua, outcome, ip);
            if (n == 0) {
                try {
                    jdbc.update(SQL_CALLER_INSERT, ip, at, at, ua, outcome);
                } catch (DataIntegrityViolationException dup) {
                    jdbc.update(SQL_CALLER_UPDATE, at, ua, outcome, ip);
                }
            }
            // Yeni IP denetimi İLK KABUL edilen çağrıda (reddedilen yeni IP ALARM_FEED_ACCESS_DENIED ile görünür). Bu pod'un
            // zaten kabul ettiği IP için ek ifade yok.
            if (!ok || knownOk.containsKey(ip)) return;
            int first = jdbc.update(SQL_CALLER_FIRST_OK, at, ip);
            if (knownOk.size() >= THROTTLE_MAP_MAX) knownOk.clear();
            knownOk.put(ip, Boolean.TRUE);
            if (first > 0 && auditService != null) {
                auditService.recordAction("ALARM_FEED_NEW_CALLER", "alarm-feed-client", null, null, "EXTERNAL",
                        "ALARM_FEED", ip, AuditDetail.of("client_ip", ip, "outcome", outcome,
                                "remote_addr", call.remoteAddr(), "forwarded_for", call.forwardedFor()),
                        ip, call.userAgent(), null);
            }
        } catch (Exception e) {
            failed("çağıran kaydı", e, now);
        }
    }

    private void auditDenied(Call call, String outcome, long now, jakarta.servlet.http.HttpServletRequest request) {
        if (auditService == null) return;
        String key = call.clientIp() + "|" + outcome;
        if (!firstInWindow(deniedAudited, key, now, DENIED_AUDIT_EVERY_MS)) return;
        if (!deniedAuditBudget(now)) return;
        try {
            if (request != null) {
                // Güvenlik olayı: sonuç BLOCKED, IP / UA istekten (ClientIpResolver) — kategori "_DENIED" → Güvenlik.
                auditService.recordSecurityEvent("ALARM_FEED_ACCESS_DENIED", request, null, "ALARM_FEED",
                        call.clientIp(), outcome);
                return;
            }
            auditService.recordAction("ALARM_FEED_ACCESS_DENIED", "alarm-feed-client", null, null, "EXTERNAL",
                    "ALARM_FEED", call.clientIp(), AuditDetail.of("reason", outcome, "client_ip", call.clientIp(),
                            "remote_addr", call.remoteAddr(), "forwarded_for", call.forwardedFor(),
                            "request_id", call.requestId()),
                    call.clientIp(), call.userAgent(), null);
        } catch (Exception e) {
            failed("denetim kaydı", e, now);
        }
    }

    private static boolean firstInWindow(Map<String, Long> seen, String key, long now, long windowMs) {
        String k = key == null ? "?" : key;
        if (seen.size() > THROTTLE_MAP_MAX) seen.entrySet().removeIf(e -> now - e.getValue() >= windowMs);
        if (seen.size() > THROTTLE_MAP_MAX) seen.clear();
        Long prev = seen.get(k);
        if (prev != null && now - prev < windowMs) return false;
        seen.put(k, now);
        return true;
    }

    private void failed(String what, Exception e, long now) {
        writeFailures.incrementAndGet();
        if (now - lastFailureWarnMs >= 60_000L) {
            lastFailureWarnMs = now;
            log.warn("Alarm akışı çağrı günlüğü yazılamadı ({}) — istemci yanıtını aldı: {}", what, e.toString());
        }
    }

    // ── Gövde ───────────────────────────────────────────────────────────────────────────────────

    /** Saklanan gövdeyi açar; yoksa null. */
    public String body(String hash) {
        if (hash == null) return null;
        try {
            byte[] gz = jdbc.query("SELECT body FROM alarm_feed_responses WHERE hash = ?",
                    rs -> rs.next() ? rs.getBytes(1) : null, hash);
            return gz == null ? null : new String(gunzip(gz), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Alarm akışı yanıt gövdesi okunamadı", e);
        }
    }

    /** Saklanan gövdenin içerik türü; yoksa null. */
    public String contentType(String hash) {
        if (hash == null) return null;
        return jdbc.query("SELECT content_type FROM alarm_feed_responses WHERE hash = ?",
                rs -> rs.next() ? rs.getString(1) : null, hash);
    }

    static String sha256(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] gzip(byte[] raw) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 6));
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    static byte[] gunzip(byte[] gz) throws java.io.IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            // Sınır: yazılan gövde tavanı (satır tavanı × satır boyu) çok altında; bozuk / şişirilmiş veri belleği doldurmasın.
            return in.readNBytes(64 * 1024 * 1024);
        }
    }

    /** Test / tanı için. */
    Map<String, Object> debugState() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("write_failures", writeFailures.get());
        return m;
    }
}
