package com.sitemonitor.controller;

import com.sitemonitor.config.CorrelationIdFilter;
import com.sitemonitor.service.ClientIpResolver;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.alarmfeed.AlarmFeedBlocks;
import com.sitemonitor.service.alarmfeed.AlarmFeedRequestLog;
import com.sitemonitor.service.alarmfeed.AlarmFeedService;
import com.sitemonitor.service.alarmfeed.AlarmFeedSettings;
import com.sitemonitor.service.alarmfeed.IpAllowList;
import com.sitemonitor.service.lockout.RateLimitCounterService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Merkezi izleme alarm akışı — HERKESE AÇIK uç (2026-10-30, kullanıcı kararı: belirteç yok). Kurum içi izleme ekibi her dakika
 * çağırır ve SiteMonitor alarmlarını kendi kolon biçiminde alır ({@link com.sitemonitor.service.alarmfeed.AlarmFeedRows}).
 *
 * <p>Sıra: otomatik IP engeli (sürüyorsa veritabanına dokunmadan 429 {@code ALARM_FEED_IP_BLOCKED}) → oran sınırı (IP başına,
 * kümede ortak; dakikadaki çağrı engel eşiğini aşarsa süreli engel yazılır, {@link AlarmFeedBlocks}) → akış açık mı (kapalı: 404 {@code ALARM_FEED_DISABLED}) → IP izin listesi
 * (403 {@code ALARM_FEED_IP_DENIED}) → yanıt. Veri okunamazsa 503 {@code ALARM_FEED_UNAVAILABLE} — asla boş liste (merkezi
 * sistem "alarm yok" sanırdı). Her çağrı (reddedilenler dahil) çağrı günlüğüne yazılır ({@link AlarmFeedRequestLog}).
 *
 * <p>Biçim: varsayılan JSON DİZİ (pandas: {@code pd.DataFrame(r.json(), columns=[...])}); {@code ?format=csv} → CSV. Bilinmeyen
 * biçim JSON'a düşer. Yanıt başlıkları: {@code X-Feed-Rows}, {@code X-Feed-Open}, {@code X-Feed-Resolved},
 * {@code X-Feed-Truncated}, {@code X-Feed-Generated-At}.
 */
@Slf4j
@RestController
public class AlarmFeedController {

    public static final String PATH = "/api/public/alarm-feed";
    static final String RATE_SCOPE = "alarm-feed-ip";

    private final AlarmFeedService feed;
    private final AlarmFeedRequestLog requestLog;
    private final ClientIpResolver ipResolver;
    private RateLimitCounterService limiter;
    private AlarmFeedBlocks blocks;

    public AlarmFeedController(AlarmFeedService feed, AlarmFeedRequestLog requestLog, ClientIpResolver ipResolver) {
        this.feed = feed;
        this.requestLog = requestLog;
        this.ipResolver = ipResolver;
    }

    @Autowired(required = false)
    void setLimiter(RateLimitCounterService limiter) {
        this.limiter = limiter;
    }

    @Autowired(required = false)
    void setBlocks(AlarmFeedBlocks blocks) {
        this.blocks = blocks;
    }

    @GetMapping(PATH)
    public ResponseEntity<String> alarms(@RequestParam(value = "format", required = false) String format,
                                         HttpServletRequest request) {
        long started = System.currentTimeMillis();
        String ip = clientIp(request);
        AlarmFeedRequestLog.Call call = new AlarmFeedRequestLog.Call(started, ip, request.getRemoteAddr(),
                request.getHeader("X-Forwarded-For"), request.getHeader(HttpHeaders.USER_AGENT), request.getMethod(),
                request.getQueryString(), CorrelationIdFilter.get(request), SchedulerService.hostname());
        boolean csv = format != null && "csv".equals(format.trim().toLowerCase(Locale.ROOT));
        String fmt = csv ? "csv" : "json";

        AlarmFeedSettings.Config c = feed.config();
        boolean blocking = c.blockEnabled() && blocks != null;

        // 0) Süren otomatik engel: pod belleğinden karar — sayaç, akış, veritabanı yok (sel altında kendimizi koruruz).
        if (blocking) {
            long until = blocks.blockedUntil(ip, started);
            if (until > started) return blocked(call, ip, until, null, c, fmt, request);
        }

        // 1) Oran sınırı (kümede ortak sayaç; sayaç yazılamazsa bu pod'un belleği — RateLimitCounterService).
        if (limiter != null) {
            RateLimitCounterService.State st = limiter.hit(RateLimitCounterService.key(RATE_SCOPE, ip),
                    new RateLimitCounterService.Limit(60_000L, c.ratePerMinute(), 60_000L));
            // 1a) Dakikadaki çağrı engel eşiğini aştı → süreli engel (kümede bir kez yazılır, denetlenir). Sayaç SİLİNMEZ:
            //     silinseydi engeli henüz öğrenmemiş pod'lar sayımı sıfırdan başlatıp seli yeniden geçirirdi; pencere
            //     (60 sn) zaten kendiliğinden sıfırlanır. Muaf listedeki IP engellenmez (yalnız 429).
            if (blocking && st.attempts() > c.blockThreshold() && !IpAllowList.listed(c.blockExemptIps(), ip)) {
                AlarmFeedBlocks.Placed p = blocks.place(ip, st.attempts(), call.userAgent(), c, started, request);
                return blocked(call, ip, p.until(), p, c, fmt, request);
            }
            if (st.attempts() > c.ratePerMinute()) {
                long wait = Math.max(1L, (st.blockedUntil() - System.currentTimeMillis() + 999) / 1000);
                String body = error("RATE_LIMITED", Msg.t(
                        "Dakikalık çağrı sınırı aşıldı (" + c.ratePerMinute() + " / dk) — " + wait + " sn sonra yeniden deneyin.",
                        "Per-minute call limit exceeded (" + c.ratePerMinute() + " / min) — retry in " + wait + " s."),
                        request, Map.of("retry_after_seconds", wait));
                log(call, HttpStatus.TOO_MANY_REQUESTS, AlarmFeedRequestLog.OUTCOME_RATE_LIMITED, body, null, fmt, null,
                        c, request);
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, Long.toString(wait))
                        .contentType(MediaType.APPLICATION_JSON).body(body);
            }
        }

        // 2) Kapalı.
        if (!c.enabled()) {
            String body = error("ALARM_FEED_DISABLED", Msg.t(
                    "Alarm akışı kapalı. SiteMonitor yöneticisi Ayarlar → Platform → Alarm akışı'ndan açabilir.",
                    "The alarm feed is turned off. A SiteMonitor administrator can turn it on in Settings → Platform → Alarm feed."),
                    request, Map.of());
            log(call, HttpStatus.NOT_FOUND, AlarmFeedRequestLog.OUTCOME_DISABLED, body, null, fmt, null, c, request);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(body);
        }

        // 3) IP izin listesi (boş = herkes).
        if (!IpAllowList.allowed(c.allowedIps(), ip)) {
            String body = error("ALARM_FEED_IP_DENIED", Msg.t(
                    "Bu IP adresinin (" + ip + ") alarm akışını çağırma izni yok. SiteMonitor yöneticisinden IP izin "
                            + "listesine eklenmesini isteyin.",
                    "This IP address (" + ip + ") is not allowed to call the alarm feed. Ask a SiteMonitor administrator to "
                            + "add it to the IP allow list."),
                    request, Map.of());
            log(call, HttpStatus.FORBIDDEN, AlarmFeedRequestLog.OUTCOME_IP_DENIED, body, null, fmt, null, c, request);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body(body);
        }

        // 4) Yanıt.
        AlarmFeedService.Feed f;
        String payload;
        try {
            f = feed.feed(c);
            payload = csv ? AlarmFeedService.toCsv(f) : feed.toJson(f);
        } catch (Exception e) {
            log.warn("Alarm akışı yanıtı kurulamadı — 503 döndü (boş liste DEĞİL): {}", e.toString());
            String body = error("ALARM_FEED_UNAVAILABLE", Msg.t(
                    "Alarm verisi şu an okunamadı; boş liste döndürülmedi. Bir dakika sonra yeniden deneyin.",
                    "Alarm data can't be read right now; no empty list was returned. Try again in a minute."),
                    request, Map.of());
            log(call, HttpStatus.SERVICE_UNAVAILABLE, AlarmFeedRequestLog.OUTCOME_ERROR, body, null, fmt,
                    e.getClass().getSimpleName(), c, request);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON).body(body);
        }
        MediaType type = csv ? new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8)
                : new MediaType("application", "json", java.nio.charset.StandardCharsets.UTF_8);
        log(call, HttpStatus.OK, AlarmFeedRequestLog.OUTCOME_OK, payload, f, fmt, null, c, request);
        return ResponseEntity.ok()
                .contentType(type)
                .header("X-Feed-Rows", Integer.toString(f.rows().size()))
                .header("X-Feed-Open", Integer.toString(f.open()))
                .header("X-Feed-Resolved", Integer.toString(f.resolved()))
                .header("X-Feed-Truncated", Boolean.toString(f.truncated()))
                .header("X-Feed-Generated-At", f.generatedAt() + "Z")   // UTC (satırlar Türkiye saati)
                .body(payload);
    }

    /** 429 {@code ALARM_FEED_IP_BLOCKED} + {@code Retry-After}; günlükte BLOCKED (IP başına dakikada bir satır). */
    private ResponseEntity<String> blocked(AlarmFeedRequestLog.Call call, String ip, long until, AlarmFeedBlocks.Placed placed,
                                           AlarmFeedSettings.Config c, String fmt, HttpServletRequest request) {
        long wait = Math.max(1L, (until - System.currentTimeMillis() + 999) / 1000);
        String untilUtc = AlarmFeedBlocks.isoUtc(until);
        long mins = (wait + 59) / 60;
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("retry_after_seconds", wait);
        extra.put("blocked_until", untilUtc + "Z");
        String body = error("ALARM_FEED_IP_BLOCKED", Msg.t(
                "Bu IP adresi (" + ip + ") alarm akışını çok sık çağırdığı için geçici olarak engellendi (eşik dakikada "
                        + c.blockThreshold() + " çağrı) — yaklaşık " + mins + " dk sonra yeniden deneyin. Çağrı sıklığını "
                        + "dakikada bire indirin; engelin hemen kalkması gerekiyorsa SiteMonitor yöneticisine başvurun.",
                "This IP address (" + ip + ") is temporarily blocked from the alarm feed for calling too often (threshold "
                        + c.blockThreshold() + " calls per minute) — retry in about " + mins + " min. Reduce the call rate to "
                        + "once a minute; ask a SiteMonitor administrator if the block must be lifted now."),
                request, extra);
        String contentType = "application/json";
        requestLog.record(call, new AlarmFeedRequestLog.Result(HttpStatus.TOO_MANY_REQUESTS.value(),
                AlarmFeedRequestLog.OUTCOME_BLOCKED, body, contentType, null, null, null, null, fmt, null,
                placed != null && placed.newBlock()), c.storeBody(), request);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(wait))
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private void log(AlarmFeedRequestLog.Call call, HttpStatus status, String outcome, String body, AlarmFeedService.Feed f,
                     String fmt, String error, AlarmFeedSettings.Config c, HttpServletRequest request) {
        String contentType = f == null ? "application/json" : ("csv".equals(fmt) ? "text/csv" : "application/json");
        requestLog.record(call, new AlarmFeedRequestLog.Result(status.value(), outcome, body, contentType,
                f == null ? null : f.rows().size(), f == null ? null : f.open(), f == null ? null : f.resolved(),
                f == null ? null : f.truncated(), fmt, error), c.storeBody(), request);
    }

    private String clientIp(HttpServletRequest request) {
        try {
            String ip = ipResolver == null ? null : ipResolver.resolve(request);
            return ip == null || ip.isBlank() ? request.getRemoteAddr() : ip.trim();
        } catch (Exception e) {
            return request.getRemoteAddr();
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Projenin hata gövdesi sözleşmesi: {@code {success:false, code, error, request_id}} (+ ek alanlar). */
    private static String error(String code, String message, HttpServletRequest request, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("code", code);
        m.put("error", message);
        m.put("request_id", CorrelationIdFilter.get(request));
        m.putAll(extra);
        try {
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"success\":false,\"code\":\"" + code + "\"}";
        }
    }
}
