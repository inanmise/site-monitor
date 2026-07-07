package com.certmonitor.service;

import com.certmonitor.model.AlertEvent;
import com.certmonitor.model.DnsRecord;
import com.certmonitor.repository.AlertEventRepository;
import com.certmonitor.repository.DnsRecordRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * İzleme kesintisi teyit durum makinesi — ACCESSIBILITY (uptime), PORT_DOWN
 * ve DNS_FAILURE için ortak; DNS_CHANGED için teyitsiz anında alarm yolu.
 *
 * Sweep bir hedefi DOWN gördüğünde alarm HEMEN üretilmez: 30 sn arayla 3
 * ardışık re-check ile kalıcılık teyit edilir (re-check mantığı SweepItem'ın
 * kind'e özgü {@code recheck} supplier'ındadır — bu servis kind-agnostiktir).
 * Üçü de başarısızsa EscalationService.processConfirmedOutage ile alarm
 * oluşturulur (takım bildirimi + mail + webhook). Sorun düzeldiğinde alarm
 * otomatik kapanır (DNS_CHANGED hariç — değişiklik "düzelmez", manuel kapanır).
 *
 * Agregasyon: bir domain, bir kind için HERHANGİ bir monitörü down ise DOWN
 * sayılır; otomatik çözülme ancak o kind'in TÜM monitörleri up olduğunda olur.
 *
 * Replica güvenliği: sweep'ler her replikada çalışır (scheduler lock'suz);
 * alarm pipeline'ı scheduler_lock tablosunda "mon-alert:<tip>:<domain>"
 * satırıyla tek-yazar olacak şekilde korunur.
 *
 * Restart semantiği (bilinçli): teyit durumu in-memory'dir. Restart'ta
 * kaybolur; ilk sweep DOWN'ı yeniden görür ve teyit baştan başlar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringOutageService {

    private final AlertEventRepository alertEventRepo;
    private final EscalationService escalationService;
    private final JdbcTemplate jdbcTemplate;
    private final DnsRecordRepository dnsRecordRepo;
    private final AppSettingsService appSettings;

    @Value("${cert.monitor.uptime.alert-enabled:true}")
    private boolean uptimeAlertEnabled;

    @Value("${cert.monitor.port.alert-enabled:true}")
    private boolean portAlertEnabled;

    @Value("${cert.monitor.dns.alert-enabled:true}")
    private boolean dnsAlertEnabled;

    @Value("${cert.monitor.keyword.alert-enabled:true}")
    private boolean keywordAlertEnabled;

    @Value("${cert.monitor.ping.alert-enabled:true}")
    private boolean pingAlertEnabled;

    /** Teyit zinciri parametreleri — üç teyitli tip için ortaktır. */
    @Value("${cert.monitor.uptime.confirm-attempts:3}")
    private int confirmAttempts;

    @Value("${cert.monitor.uptime.confirm-delay-ms:30000}")
    private long confirmDelayMs;

    // Monitör host'un kendi ağ kesintisinde alarm seli olmasın — cert sweep'teki
    // bulk-failure bastırmasının aynası (domain granülaritesinde).
    @Value("${cert.monitor.network.error-rate-threshold:0.50}")
    private double bulkRateThreshold;

    @Value("${cert.monitor.network.min-errors:3}")
    private int bulkMinErrors;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String HOSTNAME = resolveHostname();
    private static final String INSTANCE_ID = HOSTNAME + "-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    /** Tip+domain kilidi TTL'i — teyit zinciri + alarm gönderimi 2 dk'yı aşmaz. */
    private static final int LOCK_TTL_SECONDS = 120;

    /**
     * Bir sweep'in tek monitör sonucu. {@code recheck} supplier'ı kind'e özgü
     * canlı re-check'i (ve kendi geçmiş persist'ini) kapsüller; dönen map en az
     * {"status": "up"|"down", "error": ...} içerir. {@code detail} görüntü
     * anahtarıdır ("443" | "8443/TCP" | "A"); {@code ctxExtra} mail context'ine
     * merge edilen kind'e özgü alanlardır (port/protocol/record_type).
     */
    public record SweepItem(String alertType, String domain, String detail,
                            boolean up, String error,
                            Map<String, Object> ctxExtra,
                            Supplier<Map<String, Object>> recheck) {}

    /** Başarılı sorguda tespit edilen gerçek DNS kayıt değişikliği (CHANGED).
     *  teamId: standalone monitör için takım (alarmı doğru takıma yönlendirir); envanter-türevinde null. */
    public record DnsChange(String domain, String recordType,
                            String previousValue, String newValue, String detectedAt, Long teamId,
                            Supplier<Map<String, Object>> recheck) {}

    /** Teyit re-check'leri için küçük daemon havuzu — eşzamanlı çok-domain DOWN'da
     *  teyit zincirleri paralel ilerlesin (tek-thread'de seri kuyruk → alarm gecikmesi).
     *  Re-check'ler bağımsız; çift-zincir guard'ı için {@link #inFlight} kullanılır. */
    private final ScheduledExecutorService confirmExecutor =
            Executors.newScheduledThreadPool(4, r -> {
                Thread t = new Thread(r, "monitoring-confirm");
                t.setDaemon(true);
                return t;
            });

    /** "tip:domain:detail" — aynı hedef için çift teyit zinciri başlatma guard'ı. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** Recovery period: "tip:domain" → ardışık başarılı ("up") kontrol sayacı. Açık alarm,
     *  recoveryChecks kadar ardışık başarılı kontrol gelene dek KAPANMAZ; arada bir DOWN sayacı
     *  sıfırlar. In-memory → restart'ta sıfırlanır (recovery yeniden başlar). */
    private final Map<String, Integer> recoveryUpCount = new ConcurrentHashMap<>();

    /** Aktif recovery re-check havuzu (keyword/ping: recoveryIntervalSeconds set) — confirm havuzunun eşi. */
    private final ScheduledExecutorService recoveryExecutor =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "monitoring-recovery");
                t.setDaemon(true);
                return t;
            });

    /** "tip:domain" — aktif recovery döngüsü çift-başlatma + iptal guard'ı. */
    private final Set<String> recoveryInFlight = ConcurrentHashMap.newKeySet();

    @PreDestroy
    void shutdown() {
        confirmExecutor.shutdownNow();
        recoveryExecutor.shutdownNow();
    }

    /** Uptime/Port/DNS-failure sweep'leri her tur sonunda bir kez çağırır. */
    public void handleSweepResults(String alertType, List<SweepItem> items) {
        if (!alertEnabled(alertType) || items == null || items.isEmpty()) return;

        // Domain bazlı agregasyon: any-down = domain down; all-up = recovered
        Map<String, List<SweepItem>> byDomain = new LinkedHashMap<>();
        for (SweepItem it : items) {
            byDomain.computeIfAbsent(it.domain(), d -> new ArrayList<>()).add(it);
        }

        long downDomains = byDomain.values().stream()
                .filter(list -> list.stream().anyMatch(it -> !it.up()))
                .count();
        if (downDomains >= bulkMinErrors
                && (double) downDomains / byDomain.size() >= bulkRateThreshold) {
            log.warn("{} sweep: {}/{} domain DOWN — monitör host ağ kesintisi şüphesi, "
                    + "alarmlar bu sweep'te bastırıldı", alertType, downDomains, byDomain.size());
            return;
        }

        Set<String> domainsWithOpenAlert = alertEventRepo.findOpenByDomainIn(byDomain.keySet()).stream()
                .filter(e -> alertType.equals(e.getAlertType()))
                .map(AlertEvent::getDomain)
                .collect(java.util.stream.Collectors.toSet());

        for (Map.Entry<String, List<SweepItem>> entry : byDomain.entrySet()) {
            String domain = entry.getKey();
            List<SweepItem> domainItems = entry.getValue();
            boolean anyDown = domainItems.stream().anyMatch(it -> !it.up());
            boolean hasOpenAlert = domainsWithOpenAlert.contains(domain);

            if (!anyDown) {
                if (hasOpenAlert) {
                    // Tüm monitörler up — RECOVERY PERIOD: recoveryChecks kadar ardışık başarılı kontrolde alarm kapanır.
                    String rkey = alertType + ":" + domain;
                    int required = recoveryChecksFor(domainItems);
                    Long recIntervalMs = recoveryIntervalMsFor(domainItems);
                    if (recIntervalMs != null) {
                        // AKTİF recovery (keyword/ping, recoveryIntervalSeconds set): pasif sayacı kullanma,
                        // recIntervalMs arayla required denemeyle aktif olarak doğrula.
                        recoveryUpCount.remove(rkey);
                        startRecovery(alertType, domain, domainItems, required, recIntervalMs);
                    } else if (required <= 1) {
                        recoveryUpCount.remove(rkey);
                        withLock(alertType, domain, () ->
                                escalationService.resolveMonitoringAlertsForDomain(domain, alertType));
                    } else {
                        int up = recoveryUpCount.merge(rkey, 1, Integer::sum);
                        if (up >= required) {
                            recoveryUpCount.remove(rkey);
                            log.info("Recovery tamamlandı: {} [{}] — {}/{} ardışık başarılı kontrol, alarm kapatılıyor",
                                    domain, alertType, up, required);
                            withLock(alertType, domain, () ->
                                    escalationService.resolveMonitoringAlertsForDomain(domain, alertType));
                        } else {
                            log.info("Recovery sürüyor: {} [{}] — {}/{} ardışık başarılı kontrol (kapatma bekliyor)",
                                    domain, alertType, up, required);
                        }
                    }
                } else {
                    recoveryUpCount.remove(alertType + ":" + domain);   // açık alarm yok → bayat sayaç temizle
                    recoveryInFlight.remove(alertType + ":" + domain);
                }
            } else if (hasOpenAlert) {
                // Kesinti SÜRÜYOR — recovery penceresini SIFIRLA (pasif + aktif) + günlük re-alert yolu
                recoveryUpCount.remove(alertType + ":" + domain);
                recoveryInFlight.remove(alertType + ":" + domain);   // aktif recovery döngüsünü iptal et
                SweepItem firstDown = domainItems.stream().filter(it -> !it.up()).findFirst().orElseThrow();
                withLock(alertType, domain, () ->
                        escalationService.processConfirmedOutage(domain, alertType,
                                levelFor(alertType), sweepContext(firstDown)));
            } else {
                for (SweepItem it : domainItems) {
                    if (!it.up()) startConfirmation(it);
                }
            }
        }
    }

    /**
     * DNS sweep girişi: çözümleme hataları + kayıt DEĞİŞİKLİKLERİ teyitli makineden geçer (3× ardışık
     * doğrulama; değer baseline'a dönerse "geçici dalgalanma" → iptal). DNS_CHANGED teyit sonrası YÜKSEK
     * alarm açar ama handleSweepResults'a girmediğinden OTOMATİK KAPANMAZ (manuel kapanır). Ayrıca açık
     * unacked DNS_CHANGED alarmlarının günlük re-alert kadansını yönetir (sonraki sweep'ler changed=false görür).
     */
    public void handleDnsSweep(List<SweepItem> failureItems, List<SweepItem> slowItems,
                               List<DnsChange> changes,
                               List<SweepItem> unexpectedItems, List<SweepItem> inconsistentItems) {
        handleSweepResults(EscalationService.TYPE_DNS_FAILURE, failureItems);
        handleSweepResults(EscalationService.TYPE_DNS_SLOW, slowItems);   // yavaş/timeout'lu çözümleme — kendi teyit zinciri (3×60sn ctxExtra'dan)
        handleSweepResults(EscalationService.TYPE_DNS_UNEXPECTED, unexpectedItems);   // beklenen-değer kilidi (state; değer beklenene dönünce oto-kapanır)
        handleSweepResults(EscalationService.TYPE_DNS_INCONSISTENT, inconsistentItems);   // çoklu-resolver tutarsızlık (state; teyitli; resolver'lar aynılaşınca oto-kapanır)
        if (!appSettings.getBoolean("cert.monitor.dns.alert-enabled", dnsAlertEnabled)) return;

        Set<String> changedThisSweep = new HashSet<>();
        if (changes != null) {
            for (DnsChange c : changes) {
                changedThisSweep.add(c.domain());
                // DNS_CHANGED artık 3× teyitli (diğer DNS alarmları gibi): startConfirmation zinciri;
                // recheck baseline'a dönerse "geçici dalgalanma" → iptal. Teyit sonrası processConfirmedOutage
                // (HIGH) açar; handleSweepResults'a girmez → OTOMATİK KAPANMAZ (manuel). changeCtx old/new_values taşır.
                startConfirmation(new SweepItem(EscalationService.TYPE_DNS_CHANGED,
                        c.domain(), c.recordType(), false, "changed", changeCtx(c), c.recheck()));
            }
        }

        // Günlük re-alert: açık + unacked DNS_CHANGED, bu sweep'te değişikliği olmayanlar
        if (failureItems == null || failureItems.isEmpty()) return;
        Set<String> sweptDomains = failureItems.stream()
                .map(SweepItem::domain).collect(java.util.stream.Collectors.toSet());
        alertEventRepo.findOpenByDomainIn(sweptDomains).stream()
                .filter(e -> EscalationService.TYPE_DNS_CHANGED.equals(e.getAlertType()))
                .filter(e -> !Boolean.TRUE.equals(e.getAcknowledged()))
                .filter(e -> !changedThisSweep.contains(e.getDomain()))
                .forEach(e -> {
                    Map<String, Object> ctx = reconstructChangeCtx(e.getDomain());
                    withLock(EscalationService.TYPE_DNS_CHANGED, e.getDomain(), () ->
                            escalationService.processConfirmedOutage(e.getDomain(),
                                    EscalationService.TYPE_DNS_CHANGED, "HIGH", ctx));
                });
    }

    /** Per-monitor teyit override'ı (keyword/ping ctxExtra'sından) — yoksa global varsayılan. */
    private int effAttempts(SweepItem item) {
        Object v = item.ctxExtra() != null ? item.ctxExtra().get("monitor_confirm_attempts") : null;
        return v instanceof Number n && n.intValue() >= 0 ? n.intValue() : confirmAttempts;   // 0 = immediate
    }
    private long effDelayMs(SweepItem item) {
        Object v = item.ctxExtra() != null ? item.ctxExtra().get("monitor_confirm_interval_ms") : null;
        return v instanceof Number n && n.longValue() > 0 ? n.longValue() : confirmDelayMs;
    }

    /** Recovery period: per-monitor recoveryChecks (ctxExtra) varsa onu, yoksa global varsayılanı
     *  (cert.monitor.uptime.recovery-checks, default 1 = ilk başarılı kontrolde kapat) döner. */
    private int recoveryChecksFor(List<SweepItem> items) {
        for (SweepItem it : items) {
            Object v = it.ctxExtra() != null ? it.ctxExtra().get("monitor_recovery_checks") : null;
            if (v instanceof Number n && n.intValue() > 0) return n.intValue();
        }
        return Math.max(1, appSettings.getInt("cert.monitor.uptime.recovery-checks", 1));
    }

    /** Aktif recovery aralığı (ms): per-monitor recoveryIntervalSeconds (ctxExtra monitor_recovery_interval_ms)
     *  varsa onu döner; yoksa null (→ pasif recovery). Yalnız keyword/ping sweep'lerinde set edilir. */
    private Long recoveryIntervalMsFor(List<SweepItem> items) {
        for (SweepItem it : items) {
            Object v = it.ctxExtra() != null ? it.ctxExtra().get("monitor_recovery_interval_ms") : null;
            if (v instanceof Number n && n.longValue() > 0) return n.longValue();
        }
        return null;
    }

    void startConfirmation(SweepItem item) {
        String key = item.alertType() + ":" + item.domain() + ":" + item.detail();
        if (!inFlight.add(key)) {
            log.debug("Teyit zaten devam ediyor, atlanıyor: {}", key);
            return;
        }
        String firstFailureAt = now();
        List<Map<String, Object>> attempts = new ArrayList<>(); // tek thread'li executor → senkronizasyon gereksiz
        if (effAttempts(item) <= 0) {
            // Immediate (confirmation period = 0) — DOWN tespitinde incident'ı HEMEN aç, teyit bekleme.
            log.warn("{} DOWN tespit edildi: {} [{}] — immediate mod (teyit yok), incident hemen açılıyor",
                    item.alertType(), item.domain(), item.detail());
            Map<String, Object> ctx = buildOutageContext(item, firstFailureAt, attempts);
            withLock(item.alertType(), item.domain(), () ->
                    escalationService.processConfirmedOutage(item.domain(), item.alertType(),
                            levelFor(item.alertType()), ctx));
            inFlight.remove(key);
            return;
        }
        log.info("{} DOWN tespit edildi: {} [{}] — {} sn arayla {} doğrulama denemesi başlatıldı",
                item.alertType(), item.domain(), item.detail(), effDelayMs(item) / 1000, effAttempts(item));
        confirmExecutor.schedule(
                () -> runConfirmAttempt(key, item, firstFailureAt, attempts, 1),
                effDelayMs(item), TimeUnit.MILLISECONDS);
    }

    void runConfirmAttempt(String key, SweepItem item, String firstFailureAt,
                           List<Map<String, Object>> attempts, int n) {
        try {
            Map<String, Object> r = item.recheck().get();
            Map<String, Object> attempt = new LinkedHashMap<>();
            attempt.put("attempt", n);
            attempt.put("checked_at", now());
            attempt.put("status", r.get("status"));
            attempt.put("error", r.get("error"));
            attempts.add(attempt);

            if ("up".equals(r.get("status"))) {
                log.info("Geçici dalgalanma: {} — {}. doğrulama denemesinde düzeldi, alarm üretilmedi", key, n);
                inFlight.remove(key);
                return;
            }
            if (n < effAttempts(item)) {
                confirmExecutor.schedule(
                        () -> runConfirmAttempt(key, item, firstFailureAt, attempts, n + 1),
                        effDelayMs(item), TimeUnit.MILLISECONDS);
                return;
            }

            log.warn("{} kesintisi TEYİT EDİLDİ: {} [{}] — {}/{} doğrulama denemesi başarısız",
                    item.alertType(), item.domain(), item.detail(), effAttempts(item), effAttempts(item));
            Map<String, Object> ctx = buildOutageContext(item, firstFailureAt, attempts);
            withLock(item.alertType(), item.domain(), () ->
                    escalationService.processConfirmedOutage(item.domain(), item.alertType(),
                            levelFor(item.alertType()), ctx));
            inFlight.remove(key);
        } catch (Exception e) {
            log.error("Teyit başarısız oldu: {} — {}", key, e.getMessage(), e);
            inFlight.remove(key);
        }
    }

    /** Aktif recovery döngüsü (keyword/ping): alarm açıkken tüm monitörler up görülünce başlar; required kadar
     *  ardışık başarılı re-check (intervalMs arayla) sağlanınca alarmı kapatır; arada DOWN görülürse iptal olur. */
    void startRecovery(String alertType, String domain, List<SweepItem> items, int required, long intervalMs) {
        String key = alertType + ":" + domain;
        if (required <= 1) {
            recoveryInFlight.remove(key);   // tek kontrol yeterli → beklemeden kapat
            withLock(alertType, domain, () ->
                    escalationService.resolveMonitoringAlertsForDomain(domain, alertType));
            return;
        }
        if (!recoveryInFlight.add(key)) {
            log.debug("Aktif recovery zaten sürüyor, atlanıyor: {}", key);
            return;
        }
        log.info("Recovery başladı (aktif): {} [{}] — {} sn arayla {} doğrulama denemesi",
                domain, alertType, intervalMs / 1000, required - 1);
        recoveryExecutor.schedule(
                () -> runRecoveryAttempt(key, alertType, domain, items, required, intervalMs, 1),
                intervalMs, TimeUnit.MILLISECONDS);
    }

    void runRecoveryAttempt(String key, String alertType, String domain, List<SweepItem> items,
                            int required, long intervalMs, int n) {
        if (!recoveryInFlight.contains(key)) return;   // arada DOWN → dışarıdan iptal edilmiş
        try {
            boolean allUp = true;
            for (SweepItem it : items) {
                Map<String, Object> r = it.recheck().get();
                if (!"up".equals(r.get("status"))) { allUp = false; break; }
            }
            if (!allUp) {
                log.info("Recovery kesildi (yeniden DOWN): {} [{}] — {}. denemede", domain, alertType, n);
                recoveryInFlight.remove(key);
                return;   // alarm açık kalır; sonraki sweep kesinti-sürüyor yolunu işletir
            }
            int done = n + 1;   // ilk başarılı sweep (tetikleyici) = 1, sonrası aktif re-check'ler
            if (done >= required) {
                log.info("Recovery tamamlandı (aktif): {} [{}] — {}/{} ardışık başarılı, alarm kapatılıyor",
                        domain, alertType, done, required);
                recoveryInFlight.remove(key);
                withLock(alertType, domain, () ->
                        escalationService.resolveMonitoringAlertsForDomain(domain, alertType));
                return;
            }
            recoveryExecutor.schedule(
                    () -> runRecoveryAttempt(key, alertType, domain, items, required, intervalMs, n + 1),
                    intervalMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("Recovery re-check hatası: {} [{}] — {}", domain, alertType, e.getMessage(), e);
            recoveryInFlight.remove(key);
        }
    }

    private boolean alertEnabled(String alertType) {
        return switch (alertType) {
            case EscalationService.TYPE_PORT_DOWN   ->
                    appSettings.getBoolean("cert.monitor.port.alert-enabled", portAlertEnabled);
            case EscalationService.TYPE_DNS_FAILURE,
                 EscalationService.TYPE_DNS_CHANGED,
                 EscalationService.TYPE_DNS_SLOW,
                 EscalationService.TYPE_DNS_UNEXPECTED,
                 EscalationService.TYPE_DNS_INCONSISTENT ->
                    appSettings.getBoolean("cert.monitor.dns.alert-enabled", dnsAlertEnabled);
            case EscalationService.TYPE_KEYWORD     ->
                    appSettings.getBoolean("cert.monitor.keyword.alert-enabled", keywordAlertEnabled);
            case EscalationService.TYPE_PING_DOWN   ->
                    appSettings.getBoolean("cert.monitor.ping.alert-enabled", pingAlertEnabled);
            default                                 ->
                    appSettings.getBoolean("cert.monitor.uptime.alert-enabled", uptimeAlertEnabled);
        };
    }

    static String levelFor(String alertType) {
        return (EscalationService.TYPE_DNS_CHANGED.equals(alertType)
                || EscalationService.TYPE_DNS_SLOW.equals(alertType)
                || EscalationService.TYPE_DNS_UNEXPECTED.equals(alertType)
                || EscalationService.TYPE_DNS_INCONSISTENT.equals(alertType)) ? "HIGH" : "CRITICAL";
    }

    private Map<String, Object> sweepContext(SweepItem item) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("detail", item.detail());
        ctx.put("first_failure_at", now());
        ctx.put("last_error", item.error());
        ctx.put("confirm_attempt_count", effAttempts(item));
        ctx.put("confirm_delay_ms", effDelayMs(item));
        if (item.ctxExtra() != null) ctx.putAll(item.ctxExtra());
        return ctx;
    }

    private Map<String, Object> buildOutageContext(SweepItem item, String firstFailureAt,
                                                   List<Map<String, Object>> attempts) {
        String lastError = attempts.isEmpty() ? item.error()
                : String.valueOf(attempts.get(attempts.size() - 1).getOrDefault("error", item.error()));
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("detail", item.detail());
        ctx.put("first_failure_at", firstFailureAt);
        ctx.put("last_error", lastError);
        ctx.put("confirm_attempts", attempts);
        ctx.put("confirm_attempt_count", effAttempts(item));
        ctx.put("confirm_delay_ms", effDelayMs(item));
        if (item.ctxExtra() != null) ctx.putAll(item.ctxExtra());
        return ctx;
    }

    private Map<String, Object> changeCtx(DnsChange c) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("record_type", c.recordType());
        ctx.put("old_values", splitValues(c.previousValue()));
        ctx.put("new_values", splitValues(c.newValue()));
        ctx.put("changed_at", c.detectedAt());
        // Standalone monitör: alarmı takıma yönlendir (processConfirmedOutage ctx team_id'yi kullanır).
        // NOT: günlük re-alert reconstructChangeCtx'ten gelir (team_id taşımaz) → standalone re-alert
        // alıcısı global'e düşer; açılan event'in teamId'si (çözüm bildirimi) doğru kalır.
        if (c.teamId() != null) ctx.put("team_id", c.teamId());
        return ctx;
    }

    /** Açık DNS_CHANGED alarmının günlük re-alert'i için son changed kaydından ctx kur. */
    private Map<String, Object> reconstructChangeCtx(String domain) {
        try {
            List<DnsRecord> rows = dnsRecordRepo.findChangedByDomain(domain, PageRequest.of(0, 1));
            if (!rows.isEmpty()) {
                DnsRecord r = rows.get(0);
                Map<String, Object> ctx = new LinkedHashMap<>();
                ctx.put("record_type", r.getRecordType());
                ctx.put("old_values", splitValues(r.getPreviousValue()));
                ctx.put("new_values", splitValues(r.getValue()));
                ctx.put("changed_at", r.getCheckedAt());
                return ctx;
            }
        } catch (Exception e) {
            log.debug("DNS_CHANGED re-alert context'i kurulamadı: {} — {}", domain, e.getMessage());
        }
        return Map.of();
    }

    private static List<String> splitValues(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        return Arrays.stream(joined.split("\n")).filter(s -> !s.isBlank()).toList();
    }

    /**
     * Tip+domain bazlı distributed kilit — alarm pipeline'ını replikalar
     * arasında tek-yazar yapar. SchedulerService.tryAcquireSchedulerLock
     * pattern'inin kopyası (oraya inject etmek döngüsel bağımlılık yaratır).
     * Kilit başka replikada ise sessiz skip; tablo erişilemezse degrade → izin ver.
     */
    void withLock(String alertType, String domain, Runnable action) {
        String lockName = "mon-alert:" + alertType + ":" + domain;
        boolean acquired;
        try {
            String nowIso = now();
            String until = ISO.format(Instant.now().plusSeconds(LOCK_TTL_SECONDS));
            jdbcTemplate.update(
                    "DELETE FROM scheduler_lock WHERE name = ? AND locked_until < ?", lockName, nowIso);
            jdbcTemplate.update(
                    "INSERT INTO scheduler_lock(name, locked_by, locked_until) VALUES(?, ?, ?)",
                    lockName, INSTANCE_ID, until);
            acquired = true;
        } catch (Exception e) {
            if (e.getMessage() != null
                    && (e.getMessage().contains("UNIQUE") || e.getMessage().contains("unique")
                        || e.getMessage().contains("duplicate"))) {
                log.debug("İzleme alarm kilidi başka replikada: {} — atlanıyor", lockName);
                return;
            }
            log.warn("Distributed lock table unavailable (HA degraded): {}", e.getMessage());
            acquired = false; // degrade: kilitsiz devam — tek instance kurulumlarda güvenli
        }
        try {
            action.run();
        } finally {
            if (acquired) {
                try {
                    jdbcTemplate.update(
                            "DELETE FROM scheduler_lock WHERE name = ? AND locked_by = ?",
                            lockName, INSTANCE_ID);
                } catch (Exception e) {
                    log.warn("İzleme alarm kilidi bırakılamadı '{}': {}", lockName, e.getMessage());
                }
            }
        }
    }

    private String now() {
        return ISO.format(Instant.now());
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
