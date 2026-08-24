package com.sitemonitor.service;

import com.sitemonitor.config.CorrelationIdFilter;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Denetim (audit) yazım servisi — kullanıcı/sistem EYLEMLERİ (izleme-check sonuçları DEĞİL; o activity_log).
 *
 * <b>Kurcalanamazlık:</b> tüm yazımlar tek {@link #persist} hunisinden geçer; JVM-içi kilit altında bir monoton
 * {@code seq} + zincir hash'i ({@code row_hash = SHA-256(değişmez alanlar + prev_hash)}) üretilir. Geo/PTR
 * alanları hash'e girmez (async backfill zinciri kırmaz). DB yazımı hata verirse kayıt bir fallback JSONL
 * dosyaya yazılır + ERROR loglanır ve <b>çağrıya istisna fırlatılmaz</b> → asıl işlem asla düşmez, kayıt kaybolmaz.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepo;
    private final GeoIpService geoIpService;
    @org.springframework.context.annotation.Lazy
    private final NewDeviceNotifier newDeviceNotifier;
    private final ClientIpResolver clientIpResolver;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String GENESIS = "GENESIS";
    private static final char SEP = '';   // unit separator — canonical alan ayırıcı

    /** Zincir sırası + son-hash okuması yarış olmadan yürüsün diye tek JVM kilidi (tek pod → maliyet önemsiz). */
    private final ReentrantLock chainLock = new ReentrantLock();

    @Value("${site.monitor.audit.brute-force-window-seconds:600}")
    private int bruteForceWindowSeconds;

    @Value("${site.monitor.audit.geo-velocity-window-seconds:3600}")
    private int geoVelocityWindowSeconds;

    @Value("${site.monitor.audit.office-start-hour:8}")
    private int officeStartHour;

    @Value("${site.monitor.audit.office-end-hour:20}")
    private int officeEndHour;

    /** Mesai penceresinin hesaplandığı saat dilimi — kurumun dilimi (crontab'larla aynı). */
    @Value("${site.monitor.audit.timezone:Europe/Istanbul}")
    private String auditTimezone;

    @Value("${site.monitor.audit.fallback-file:logs/audit-fallback.jsonl}")
    private String fallbackFile;

    /**
     * Fallback dosyasındaki (DB'ye yazılamamış) denetim kaydı sayısı — 0 ise sorun yok.
     *
     * <p>Neden gerekiyor: bu dosya denetim izinin son çaresi ama <b>geri okuyan hiçbir kod yoktu</b>.
     * DB yazımı başarısız olduğunda kayıt sessizce dosyaya düşüyor, kimse haberdar olmuyor ve o
     * kayıtlar denetim zincirine hiç girmiyordu. Sayaç en azından "kaybımız var" demeyi mümkün
     * kılar; açılışta bir kez WARN'lanır ve sistem durumundan okunabilir.
     *
     * <p>Dosya okunamıyorsa {@code -1} döner (bilinmiyor ≠ temiz).
     */
    public long pendingFallbackAuditCount() {
        try {
            Path p = Path.of(fallbackFile);
            if (!Files.exists(p)) return 0L;
            try (var lines = Files.lines(p, StandardCharsets.UTF_8)) {
                return lines.filter(l -> !l.isBlank()).count();
            }
        } catch (Exception e) {
            log.debug("Denetim fallback dosyası okunamadı ({}): {}", fallbackFile, e.getMessage());
            return -1L;
        }
    }

    /** Açılışta bir kez uyar — dosyada kayıt varsa denetim izinde BOŞLUK var demektir. */
    @jakarta.annotation.PostConstruct
    void warnIfFallbackPending() {
        long n = pendingFallbackAuditCount();
        if (n > 0) {
            log.warn("⚠ Denetim izinde BOŞLUK: {} kayıt DB'ye yazılamamış ve {} dosyasında bekliyor. "
                    + "Bu kayıtlar denetim zincirinde YOK; dosya elle incelenmeli.", n, fallbackFile);
        }
    }

    // ── Merkezi persist (kilit + hash zinciri + fallback) ────────────────────────

    /** TÜM denetim yazımlarının tek hunisi. Başarısızsa fallback dosyaya yazar, istisna fırlatmaz. */
    AuditLog persist(AuditLog e) {
        chainLock.lock();
        try {
            AuditLog last = auditLogRepo.findTopByOrderBySeqDesc().orElse(null);
            long seq = (last != null && last.getSeq() != null) ? last.getSeq() + 1 : 1L;
            String prev = (last != null && last.getRowHash() != null) ? last.getRowHash() : GENESIS;
            e.setSeq(seq);
            e.setPrevHash(prev);
            e.setRowHash(sha256(canonical(e) + SEP + prev));
            return auditLogRepo.save(e);
        } catch (Exception ex) {
            writeFallback(e, ex);
            return null;
        } finally {
            chainLock.unlock();
        }
    }

    /** Hash kapsamı: DEĞİŞMEZ çekirdek alanlar. Geo/PTR (async backfill) ve id/rowHash HARİÇ. */
    private static String canonical(AuditLog e) {
        StringBuilder b = new StringBuilder(256);
        append(b, e.getSeq()); append(b, e.getEventTime()); append(b, e.getEventType());
        append(b, e.getActor()); append(b, e.getActorId()); append(b, e.getActorTeamId());
        append(b, e.getActorRole()); append(b, e.getIpAddress()); append(b, e.getUserAgent());
        append(b, e.getSessionId()); append(b, e.getResourceType()); append(b, e.getResourceId());
        append(b, e.getDetail()); append(b, e.getChanges()); append(b, e.getOutcome());
        append(b, e.getFailureReason()); append(b, e.getAnomalyFlags()); append(b, e.getCorrelationId());
        return b.toString();
    }
    private static void append(StringBuilder b, Object o) { b.append(o == null ? "" : o.toString()).append(SEP); }

    static String sha256(String in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(in.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte x : h) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void writeFallback(AuditLog e, Exception cause) {
        log.error("AUDIT DB yazımı BAŞARISIZ (event={} actor={}) — fallback dosyaya yazılıyor: {}",
                e.getEventType(), e.getActor(), cause.toString());
        try {
            Path p = Path.of(fallbackFile);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, fallbackJson(e) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception fe) {
            log.error("AUDIT fallback dosya yazımı DA başarısız — kayıt kaybı: {}", fe.toString());
        }
    }

    private static String fallbackJson(AuditLog e) {
        return "{\"event_time\":\"" + n(e.getEventTime()) + "\",\"event_type\":\"" + n(e.getEventType())
                + "\",\"actor\":\"" + n(e.getActor()) + "\",\"resource_type\":\"" + n(e.getResourceType())
                + "\",\"resource_id\":\"" + n(e.getResourceId()) + "\",\"outcome\":\"" + n(e.getOutcome()) + "\"}";
    }
    private static String n(String s) { return s == null ? "" : s.replace("\"", "'"); }

    // ── Bütünlük doğrulama ───────────────────────────────────────────────────────

    public record ChainVerification(boolean ok, Long brokenSeq, Long brokenId, long checked) {}

    /** Zinciri baştan yeniden hesaplayıp saklanan hash'lerle karşılaştırır; ilk kırık satırı döner. */
    public ChainVerification verifyChain() {
        String prev = GENESIS;
        long checked = 0;
        int page = 0, size = 1000;
        while (true) {
            List<AuditLog> rows = auditLogRepo.findBySeqNotNullOrderBySeqAsc(PageRequest.of(page, size));
            if (rows.isEmpty()) break;
            for (AuditLog r : rows) {
                String expectHash = sha256(canonical(r) + SEP + prev);
                String storedPrev = r.getPrevHash() == null ? GENESIS : r.getPrevHash();
                if (!prev.equals(storedPrev) || expectHash == null || !expectHash.equals(r.getRowHash())) {
                    return new ChainVerification(false, r.getSeq(), r.getId(), checked);
                }
                prev = r.getRowHash();
                checked++;
            }
            page++;
        }
        return new ChainVerification(true, null, null, checked);
    }

    // ── Login / Logout ─────────────────────────────────────────────────────────

    /**
     * @param countSince     ISO timestamp; non-null iken sayım max(tenMinAgo, countSince)'dan başlar.
     * @param failuresNeeded pencerede kaç hatanın BRUTE_FORCE tetiklediği (varsayılan 5).
     */
    public AuditLog recordLogin(String actor, Long actorId, Long actorTeamId, String actorRole,
                                String ipAddress, String userAgent, String sessionId,
                                boolean success, String failureReason, String countSince,
                                int failuresNeeded) {
        AuditLog entry = new AuditLog();
        entry.setEventType(success ? "LOGIN" : "LOGIN_FAILED");
        entry.setEventTime(now());
        entry.setActor(actor);
        entry.setActorId(actorId);
        entry.setActorTeamId(actorTeamId);
        entry.setActorRole(actorRole);
        entry.setIpAddress(ipAddress);
        entry.setUserAgent(userAgent);
        entry.setSessionId(sessionId);
        entry.setOutcome(success ? "SUCCESS" : "FAILURE");
        entry.setFailureReason(failureReason);
        if (actor != null && !actor.isBlank()) {
            entry.setResourceType("USER");
            entry.setResourceId(actor);
        }

        List<String> anomalies = new ArrayList<>();
        if (isOffHours()) anomalies.add("OFF_HOURS");

        if (success && actor != null) {
            if (!geoIpService.isPrivateIp(ipAddress) && !auditLogRepo.existsSuccessfulLoginFromIp(actor, ipAddress))
                anomalies.add("UNUSUAL_IP");

            String oneHourAgo = ISO.format(Instant.now().minusSeconds(geoVelocityWindowSeconds));
            List<AuditLog> recent = auditLogRepo.findRecentSuccessfulLogins(actor, oneHourAgo);
            if (!recent.isEmpty()) {
                AuditLog prev = recent.get(0);
                if (prev.getIpCountry() != null && !ipAddress.equals(prev.getIpAddress())
                        && !"Private".equals(prev.getIpCountry()))
                    anomalies.add("GEO_VELOCITY");
            }

            String tenMinAgo = ISO.format(Instant.now().minusSeconds(bruteForceWindowSeconds));
            long prevFails = auditLogRepo.countRecentFailedLogins(actor, tenMinAgo);
            if (prevFails >= 5) anomalies.add("BRUTE_FORCE");
        }

        if (!success && actor != null && !actor.isBlank()) {
            // failureReason burada MAKİNE-OKUR kod önekidir (BAD_PASSWORD / UNKNOWN_USER /
            // TEMP_PASSWORD_EXPIRED …). Depolanan failure_reason "<KOD>: <insan metni>" olur →
            // anomali detektörü öneki parse eder (failure-reason dağılımı + enumeration sinyali).
            String code = (failureReason != null && !failureReason.isBlank()) ? failureReason : "LOGIN_FAILED";
            String tenMinAgo = ISO.format(Instant.now().minusSeconds(bruteForceWindowSeconds));
            String since = (countSince != null && countSince.compareTo(tenMinAgo) > 0) ? countSince : tenMinAgo;
            long prevFails = auditLogRepo.countRecentFailedLogins(actor, since);
            int attemptNum = (int) prevFails + 1;
            if (prevFails >= failuresNeeded - 1) {
                anomalies.add("BRUTE_FORCE");
                entry.setFailureReason(
                    code + ": brute-force attempt #" + attemptNum + "/" + failuresNeeded + " for '" + actor + "'");
                log.warn("Brute force detected: user='{}' attempt={}/{} IP={}", actor, attemptNum, failuresNeeded, ipAddress);
            } else {
                entry.setFailureReason(
                    code + ": attempt #" + attemptNum + "/" + failuresNeeded + " for '" + actor + "'");
            }
        } else {
            entry.setFailureReason(failureReason);
        }

        if (!anomalies.isEmpty()) {
            entry.setAnomalyFlags(String.join(",", anomalies));
            if (success) log.warn("Security anomaly on login: user={} flags={} IP={}", actor, anomalies, ipAddress);
        }

        AuditLog saved = persist(entry);
        if (saved != null) {
            enrichGeoAsync(saved.getId(), ipAddress);
            // E1: daha önce görülmemiş bir cihazdan giriş → kullanıcıya bilgi maili.
            // Best-effort ve @Async: bildirim GİRİŞİ engellemez, hatası yutulur.
            if (success) {
                newDeviceNotifier.notifyIfNewDevice(saved.getId(), actor, userAgent,
                        ipAddress, entry.getEventTime());
            }
        }
        return saved;
    }

    /** Kimlik doğrulamadan önce IP rate-limit'e takılan giriş — BLOCKED. */
    public void recordRateLimited(String actor, String ipAddress, String userAgent) {
        AuditLog entry = new AuditLog();
        entry.setEventType("LOGIN_FAILED");
        entry.setEventTime(now());
        entry.setActor(actor);
        entry.setIpAddress(ipAddress);
        entry.setUserAgent(userAgent);
        entry.setOutcome("BLOCKED");
        if (actor != null && !actor.isBlank()) {
            entry.setResourceType("USER");
            entry.setResourceId(actor);
        }
        entry.setFailureReason("Rate limited: too many login attempts from " + ipAddress
                + (actor != null && !actor.isBlank() ? " (targeting '" + actor + "')" : ""));
        entry.setAnomalyFlags("RATE_LIMITED");
        AuditLog saved = persist(entry);
        if (saved != null) enrichGeoAsync(saved.getId(), ipAddress);
        log.warn("Rate-limited login blocked: IP={} actor={}", ipAddress, actor);
    }

    public void recordLogout(String actor, Long actorId, String ipAddress, String sessionId) {
        AuditLog entry = new AuditLog();
        entry.setEventType("LOGOUT");
        entry.setEventTime(now());
        entry.setActor(actor);
        entry.setActorId(actorId);
        entry.setIpAddress(ipAddress);
        entry.setSessionId(sessionId);
        entry.setOutcome("SUCCESS");
        if (actor != null && !actor.isBlank()) {
            entry.setResourceType("USER");
            entry.setResourceId(actor);
        }
        persist(entry);
    }

    // ── Yönetim eylemleri ────────────────────────────────────────────────────────

    public void recordAction(String eventType, String actor, Long actorId, Long actorTeamId,
                             String actorRole, String resourceType, String resourceId,
                             String detail, String ipAddress, String userAgent, String sessionId) {
        recordActionFull(eventType, actor, actorId, actorTeamId, actorRole, resourceType, resourceId,
                detail, null, ipAddress, userAgent, sessionId, null);
    }

    /** HTTP bağlamından okuyan kolay overload. */
    public void recordAction(String eventType, HttpSession session, HttpServletRequest request,
                             String resourceType, String resourceId, String detail) {
        recordAction(eventType, session, request, resourceType, resourceId, detail, null);
    }

    /** Request'i thread'den çözer (controller imzalarına HttpServletRequest eklemeye gerek kalmaz).
     *  before/after diff (changes) opsiyonel. */
    public void recordAction(String eventType, HttpSession session, String resourceType,
                             String resourceId, String detail, String changes) {
        recordAction(eventType, session, currentRequest(), resourceType, resourceId, detail, changes);
    }

    private static HttpServletRequest currentRequest() {
        try {
            var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) return sra.getRequest();
        } catch (Exception ignored) { }
        return null;
    }

    /** before/after diff (changes) ile — güncelleme eylemleri için (Batch B). */
    public void recordAction(String eventType, HttpSession session, HttpServletRequest request,
                             String resourceType, String resourceId, String detail, String changes) {
        recordActionFull(eventType,
                strAttr(session, "username"), longAttr(session, "userId"), longAttr(session, "teamId"),
                strAttr(session, "systemRole"), resourceType, resourceId, detail, changes,
                resolveIp(request), resolveUa(request), session != null ? session.getId() : null,
                CorrelationIdFilter.get(request));
    }

    private void recordActionFull(String eventType, String actor, Long actorId, Long actorTeamId,
                                  String actorRole, String resourceType, String resourceId, String detail,
                                  String changes, String ipAddress, String userAgent, String sessionId,
                                  String correlationId) {
        AuditLog entry = new AuditLog();
        entry.setEventType(eventType);
        entry.setEventTime(now());
        entry.setActor(actor);
        entry.setActorId(actorId);
        entry.setActorTeamId(actorTeamId);
        entry.setActorRole(actorRole);
        entry.setIpAddress(ipAddress);
        entry.setUserAgent(userAgent);
        entry.setSessionId(sessionId);
        entry.setResourceType(resourceType);
        entry.setResourceId(resourceId);
        entry.setDetail(detail);
        entry.setChanges(changes);
        entry.setCorrelationId(correlationId);
        entry.setOutcome("SUCCESS");
        if (isOffHours()) entry.setAnomalyFlags("OFF_HOURS");
        persist(entry);
    }

    // ── Güvenlik olayları (403 / IDOR / auth-required / expired-token) ────────────

    public void recordSecurityEvent(String eventType, HttpServletRequest request, HttpSession session,
                                    String resourceType, String resourceId, String reason) {
        AuditLog e = new AuditLog();
        e.setEventType(eventType);
        e.setEventTime(now());
        if (session != null) {
            e.setActor(strAttr(session, "username"));
            e.setActorId(longAttr(session, "userId"));
            e.setActorTeamId(longAttr(session, "teamId"));
            e.setActorRole(strAttr(session, "systemRole"));
            e.setSessionId(session.getId());
        }
        if (e.getActor() == null) e.setActor("anonymous");
        e.setIpAddress(resolveIp(request));
        e.setUserAgent(resolveUa(request));
        e.setResourceType(resourceType);
        e.setResourceId(resourceId);
        e.setOutcome("BLOCKED");
        e.setFailureReason(reason != null && reason.length() > 200 ? reason.substring(0, 200) : reason);
        e.setCorrelationId(CorrelationIdFilter.get(request));
        e.setAnomalyFlags(isOffHours() ? "OFF_HOURS" : null);
        persist(e);
    }

    // ── Sistem olayları (startup/shutdown/schema-patch/retention) ────────────────

    public void recordSystemEvent(String eventType, String resourceType, String resourceId, String detail) {
        AuditLog e = new AuditLog();
        e.setEventType(eventType);
        e.setEventTime(now());
        e.setActor("SYSTEM");
        e.setActorRole("SYSTEM");
        e.setResourceType(resourceType);
        e.setResourceId(resourceId);
        e.setDetail(detail);
        e.setOutcome("SUCCESS");
        persist(e);
    }

    // ── Denetim özet istatistikleri (dashboard) — CACHE'li ───────────────────────
    // /audit/stats her istekte 10 aggregate sorgu (2× count + dağılımlar + SUBSTRING günlük
    // histogram) çalıştırıyordu; audit_log milyonlarca satıra çıkınca her açılış pahalı. 60 sn
    // Caffeine cache ile pencere başına 1 hesap (auth controller'da kalır, cache'lenmez).
    @Cacheable("audit-stats")
    public Map<String, Object> buildStats() {
        String last24h = ISO.format(Instant.now().minusSeconds(86_400));
        String last7d  = ISO.format(Instant.now().minusSeconds(7 * 86_400L));
        String last14d = ISO.format(Instant.now().minusSeconds(14 * 86_400L));

        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("total_24h",         auditLogRepo.countEventsSince(last24h));
        stats.put("anomalies_24h",     auditLogRepo.countAnomaliesSince(last24h));
        stats.put("failed_logins_24h", auditLogRepo.countFailedLoginsSince(last24h));
        stats.put("total_7d",          auditLogRepo.countEventsSince(last7d));
        stats.put("anomalies_7d",      auditLogRepo.countAnomaliesSince(last7d));
        stats.put("failed_logins_7d",  auditLogRepo.countFailedLoginsSince(last7d));
        stats.put("by_event_type_7d",  toKvList(auditLogRepo.countByEventTypeSince(last7d)));
        stats.put("by_outcome_7d",     toKvList(auditLogRepo.countByOutcomeSince(last7d)));
        stats.put("top_actors_7d",     toKvList(auditLogRepo.topActorsSince(last7d, PageRequest.of(0, 10))));
        stats.put("by_day_14d",        toKvList(auditLogRepo.countByDaySince(last14d)));
        return stats;
    }

    private static List<Map<String, Object>> toKvList(List<Object[]> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("key", r[0] == null ? "" : r[0].toString());
            m.put("count", ((Number) r[1]).longValue());
            out.add(m);
        }
        return out;
    }

    // ── Geo zenginleştirme (hash'e girmeyen kolonlar; async) ─────────────────────

    @Async("certCheckExecutor")
    public void enrichGeoAsync(Long auditLogId, String ip) {
        try {
            GeoIpService.GeoInfo geo = geoIpService.lookup(ip);
            String host = reverseDns(ip);
            auditLogRepo.updateGeo(auditLogId, geo.country(), geo.city(), geo.org(), host);
        } catch (Exception e) {
            log.debug("Geo enrichment failed for id={}: {}", auditLogId, e.getMessage());
        }
    }

    private String reverseDns(String ip) {
        if (ip == null || ip.isBlank() || geoIpService.isPrivateIp(ip)) return null;
        try {
            String host = java.net.InetAddress.getByName(ip).getCanonicalHostName();
            return (host != null && !host.equalsIgnoreCase(ip)) ? host : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────

    /**
     * "Mesai dışı" (OFF_HOURS) anomalisi — hafta içi {@code [start, end)} saatleri MESAİ,
     * geri kalan her şey mesai dışıdır (hafta sonunun tamamı dahil).
     *
     * <p><b>SAAT DİLİMİ KURUMUN DİLİMİDİR, UTC DEĞİL.</b> Eskiden hesap {@code ZoneOffset.UTC}
     * ile yapılıyordu ve kurum Europe/Istanbul (UTC+3) olduğu için pencere 3 saat kayıyordu:
     * "08:00–20:00" ayarı fiilen 11:00–23:00 İstanbul demek oluyordu. Sonuçları GERÇEKTİ —
     * sabah 08:00'deki normal giriş "mesai dışı" damgalanıyor, akşam 22:00'deki giriş
     * damgalanmıyordu. Hafta günü de kayıyordu: cumartesi 01:00 İstanbul, UTC'de hâlâ cuma.
     *
     * <p>Saf ve parametreli tutulması bilinçli: {@code now()} çağıran bir mantık ancak süitin
     * KOŞTUĞU saate göre test edilebilirdi (CI runner UTC, geliştirici makinesi Istanbul —
     * projede yaşanmış tuzak). Sınırlar burada tablo testiyle pinleniyor.
     */
    static boolean isOffHours(ZonedDateTime local, int startHour, int endHour) {
        int dow = local.getDayOfWeek().getValue();      // 1=Pazartesi … 6=Cumartesi, 7=Pazar
        if (dow >= 6) return true;                      // hafta sonunun TAMAMI mesai dışı
        int hour = local.getHour();
        return hour < startHour || hour >= endHour;     // end HARİÇ: 20:00 artık mesai dışıdır
    }

    private boolean isOffHours() {
        return isOffHours(ZonedDateTime.now(auditZone()), officeStartHour, officeEndHour);
    }

    /** Geçersiz/eksik ayarda UTC'ye düşmek sessiz bir 3 saatlik kayma olurdu — İstanbul'a düşülür. */
    private java.time.ZoneId auditZone() {
        try {
            return java.time.ZoneId.of(auditTimezone);
        } catch (Exception e) {
            log.warn("Geçersiz denetim saat dilimi '{}' — Europe/Istanbul kullanılıyor", auditTimezone);
            return java.time.ZoneId.of("Europe/Istanbul");
        }
    }

    public String resolveIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }

    public static String normalizeIp(String ip) {
        if (ip == null) return "unknown";
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "0000:0000:0000:0000:0000:0000:0000:0001".equals(ip))
            return "127.0.0.1";
        if (ip.startsWith("::ffff:") && ip.length() > 7) return ip.substring(7);
        return ip;
    }

    public String resolveUa(HttpServletRequest request) {
        return request != null ? request.getHeader("User-Agent") : null;
    }

    private String strAttr(HttpSession s, String key) {
        Object v = s != null ? s.getAttribute(key) : null;
        return v != null ? v.toString() : null;
    }

    private Long longAttr(HttpSession s, String key) {
        Object v = s != null ? s.getAttribute(key) : null;
        if (v == null) return null;
        if (v instanceof Long l) return l;
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }

    private String now() {
        return ISO.format(Instant.now());
    }
}
