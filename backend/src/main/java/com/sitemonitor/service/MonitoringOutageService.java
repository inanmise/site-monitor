package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DnsRecord;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
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
    private final DnsMonitorRepository dnsMonitorRepo;
    private final AppSettingsService appSettings;
    private final com.sitemonitor.repository.NetworkOutageEventRepository networkOutageRepo;

    @Value("${site.monitor.uptime.alert-enabled:true}")
    private boolean uptimeAlertEnabled;

    @Value("${site.monitor.port.alert-enabled:true}")
    private boolean portAlertEnabled;

    @Value("${site.monitor.dns.alert-enabled:true}")
    private boolean dnsAlertEnabled;

    @Value("${site.monitor.keyword.alert-enabled:true}")
    private boolean keywordAlertEnabled;

    @Value("${site.monitor.ping.alert-enabled:true}")
    private boolean pingAlertEnabled;

    /** Teyit zinciri parametreleri — üç teyitli tip için ortaktır. */
    @Value("${site.monitor.uptime.confirm-attempts:3}")
    private int confirmAttempts;

    @Value("${site.monitor.uptime.confirm-delay-ms:30000}")
    private long confirmDelayMs;

    // Monitör host'un kendi ağ kesintisinde alarm seli olmasın — cert sweep'teki
    // bulk-failure bastırmasının aynası (domain granülaritesinde).
    @Value("${site.monitor.network.error-rate-threshold:0.50}")
    private double bulkRateThreshold;

    @Value("${site.monitor.network.min-errors:3}")
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
                            Long notificationGroupId,
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

    /** Aktif bir teyit zincirinin anlık durumu — UI'da "Teyit denemesi X/N" göstermek için (2026-08-03).
     *  attempt=0: ilk deneme henüz koşmadı; nextAttemptAtMs: planlanan sonraki re-check zamanı. */
    public record ConfirmState(String alertType, String domain, String detail,
                               int attempt, int totalAttempts, String startedAt, long nextAttemptAtMs) {}

    /** "tip:domain:detail" → ConfirmState — hem çift-zincir guard'ı hem canlı teyit durumu (activeConfirmations). */
    private final ConcurrentHashMap<String, ConfirmState> inFlight = new ConcurrentHashMap<>();

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

    // ── Ağ-sınıfı hata ayrımı ───────────────────────────────────────────────────
    //
    // 2026-08 bulgusu: bu sınıflandırma YOKTU. Bastırma "düşük olan her monitörü" sayıyordu ve
    // sertifika sweep'i ile arasında sessiz bir asimetri vardı (o yalnız error_class ∈ {DNS,
    // NETWORK} sayar). Sonuç ölçüldü: üç keyword monitörünün üçü de AĞ YÜZÜNDEN DEĞİL düşüktü
    // (ikisinin URL'inde boşluk vardı — iki aydır her turda ayrıştırma hatası; üçüncüsünü
    // SsrfGuard bilerek reddediyordu), ama sezgi "ağ kesintisi" deyip HER sweep'te tüm alarmları
    // bastırıyordu. Yani yapılandırması bozuk birkaç monitör, sağlam monitörlerin GERÇEK
    // kesintisini süresiz olarak maskeleyebiliyordu.
    //
    // Yön seçimi: ağ desenleri AÇIK LİSTE, yapılandırma/politika desenleri ise onları EZER.
    // Tanınmayan bir hata ağ sayılmaz — yani yeni bir hata sınıfı çıkarsa sonuç "bastırma yok,
    // alarm var" olur. Bu, güvenli tarafa düşmektir: kör kalmaktansa fazladan alarm.

    /** Yapılandırma/politika hataları — bunlar ASLA ağ kesintisi kanıtı değildir. */
    private static final List<String> CONFIG_ERROR_MARKS = List.of(
            "illegal character",        // URL'de boşluk vb. → URISyntaxException (iki aylık saha vakası)
            "urisyntax", "malformed",
            "izin verilmeyen hedef",    // SsrfGuard politikası — bilinçli ret
            "no protocol", "unknown protocol", "invalid uri");

    /** DNS çözümleme hataları — sertifika sweep'inde de kesinti sayılır. */
    private static final List<String> DNS_ERROR_MARKS = List.of(
            "unknownhost", "çözümlenemeyen host", "could not find host",
            "name or service not known", "nodename nor servname", "temporary failure in name resolution");

    /** Taşıma katmanı hataları. */
    private static final List<String> NETWORK_ERROR_MARKS = List.of(
            "timed out", "timeout", "connection refused", "connectexception",
            "no route to host", "connection reset", "terminated the handshake",
            "network is unreachable", "socketexception", "i/o timeout", "broken pipe");

    /**
     * Bu hata bir AĞ/DNS kesintisi kanıtı mı? (Bastırma oranına yalnız bunlar girer.)
     *
     * @param error  checker'ın yazdığı mesaj; null/boş ⇒ sayılmaz (sebep bilinmiyorsa kanıt da yok)
     * @param domain kontrol edilen hedef — {@code UnknownHostException.getMessage()} SADECE host
     *               adını döndürdüğü için "hata metni = hedef" durumu DNS'tir. Bu dal olmadan
     *               gerçek DNS kesintilerinin büyük kısmı sınıflandırılamadan elenirdi
     *               (canlı veride {@code uptime_checks.error = 'www.akbank.com'} biçiminde 1000+ satır).
     */
    static boolean isOutageClass(String error, String domain) {
        if (error == null || error.isBlank()) return false;
        String e = error.toLowerCase(Locale.ROOT);
        for (String m : CONFIG_ERROR_MARKS) if (e.contains(m)) return false;   // yapılandırma EZER
        if (domain != null && !domain.isBlank() && e.trim().equals(domain.trim().toLowerCase(Locale.ROOT))) {
            return true;                                                        // çıplak host = UnknownHost
        }
        for (String m : DNS_ERROR_MARKS)     if (e.contains(m)) return true;
        for (String m : NETWORK_ERROR_MARKS) if (e.contains(m)) return true;
        return false;
    }

    // ── Bastırmanın görünürlüğü ─────────────────────────────────────────────────
    //
    // Eskiden bastırma her sweep'te bir WARN satırı basıp SESSİZCE dönüyordu: olay kaydı yok,
    // bildirim yok, sayaç yok. Tek kanıt, kimsenin okumadığı log dosyasında 46 kez tekrar eden
    // aynı satırdı. Sertifika sweep'i ise aynı durumda NetworkOutageEvent üretiyor. Bu fark
    // kapatılıyor: aynı tabloya, kaynağı belli olacak şekilde yazılır ve log YALNIZ duruma
    // girerken/çıkarken basar.

    /** alertType → o tip için bastırma şu anda AÇIK mı (log ve olay kaydı yalnız geçişte). */
    private final Map<String, Boolean> suppressionActive = new ConcurrentHashMap<>();

    private void noteSuppression(String alertType, int networkDown, int totalDomains) {
        double rate = totalDomains == 0 ? 0 : (double) networkDown / totalDomains;
        boolean wasActive = Boolean.TRUE.equals(suppressionActive.put(alertType, true));
        if (wasActive) {
            log.debug("{} sweep: bastırma sürüyor ({}/{} ağ-sınıfı DOWN)", alertType, networkDown, totalDomains);
            return;
        }
        log.warn("⚠ {} sweep: {}/{} domain AĞ-SINIFI hatayla DOWN (oran={}) — ağ kesintisi şüphesi, "
                + "alarmlar bastırılıyor", alertType, networkDown, totalDomains, String.format("%.2f", rate));
        try {
            com.sitemonitor.model.NetworkOutageEvent ev = new com.sitemonitor.model.NetworkOutageEvent();
            ev.setDetectedAt(ISO.format(Instant.now()));
            ev.setNetworkErrors(networkDown);
            ev.setTotalChecks(totalDomains);
            ev.setErrorRate(rate);
            ev.setThreshold(bulkRateThreshold);
            ev.setStatus("ONGOING");
            ev.setSource(alertType);
            networkOutageRepo.save(ev);
        } catch (Exception e) {
            log.warn("{} bastırma olayı kaydedilemedi: {}", alertType, e.getMessage());
        }
    }

    private void clearSuppression(String alertType) {
        if (!Boolean.TRUE.equals(suppressionActive.put(alertType, false))) return;   // zaten kapalıydı
        log.info("{} sweep: ağ kesintisi şüphesi kalktı — alarm işleme normale döndü", alertType);
        try {
            networkOutageRepo.findFirstBySourceAndStatusOrderByIdDesc(alertType, "ONGOING").ifPresent(ev -> {
                String now = ISO.format(Instant.now());
                ev.setResolvedAt(now);
                ev.setStatus("RESOLVED");
                try {
                    ev.setDurationMs(Instant.from(ISO.parse(now)).toEpochMilli()
                                   - Instant.from(ISO.parse(ev.getDetectedAt())).toEpochMilli());
                } catch (Exception ignored) { /* süre null kalır */ }
                networkOutageRepo.save(ev);
            });
        } catch (Exception e) {
            log.warn("{} bastırma olayı kapatılamadı: {}", alertType, e.getMessage());
        }
    }

    /** Uptime/Port/DNS-failure sweep'leri her tur sonunda bir kez çağırır. */
    public void handleSweepResults(String alertType, List<SweepItem> items) {
        if (!alertEnabled(alertType) || items == null || items.isEmpty()) return;

        // Domain bazlı agregasyon: any-down = domain down; all-up = recovered
        Map<String, List<SweepItem>> byDomain = new LinkedHashMap<>();
        for (SweepItem it : items) {
            byDomain.computeIfAbsent(it.domain(), d -> new ArrayList<>()).add(it);
        }

        // Bastırma YALNIZ ağ-sınıfı hatalara bakar — sertifika sweep'indeki kuralın aynısı
        // (SchedulerService: error_class ∈ {DNS, NETWORK}). Gerekçe için bkz. isOutageClass:
        // yapılandırma hatası olan bir monitör SONSUZA DEK düşük kalır ve oranı kalıcı şişirir.
        long networkDown = byDomain.entrySet().stream()
                .filter(e -> e.getValue().stream()
                        .anyMatch(it -> !it.up() && isOutageClass(it.error(), e.getKey())))
                .count();
        if (networkDown >= bulkMinErrors
                && (double) networkDown / byDomain.size() >= bulkRateThreshold) {
            noteSuppression(alertType, (int) networkDown, byDomain.size());
            return;
        }
        clearSuppression(alertType);

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
                // Kesinti SÜRÜYOR — recovery penceresini SIFIRLA (pasif + aktif) + günlük re-alert yolu.
                // BİLİNÇLİ: açık alarm varken teyit zinciri (startConfirmation) YENİDEN başlatılmaz — sorun
                // zaten teyitli ve alarmlı; sonraki sweep'ler re-alert kadansını işletir. 30sn'lik teyit
                // re-check'leri yalnız İLK tespit → alarm açılana kadarki pencerede koşar.
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
        if (!appSettings.getBoolean("site.monitor.dns.alert-enabled", dnsAlertEnabled)) return;

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
                    // Son changed kaydı BİR kez çekilir: hem monitör-bazlı bastırma kararı hem ctx için.
                    DnsRecord lastChanged = lastChangedRecord(e.getDomain());
                    if (dnsChangeRealertSuppressed(e.getDomain(), lastChanged)) return;
                    Map<String, Object> ctx = reconstructChangeCtx(lastChanged);
                    withLock(EscalationService.TYPE_DNS_CHANGED, e.getDomain(), () ->
                            escalationService.processConfirmedOutage(e.getDomain(),
                                    EscalationService.TYPE_DNS_CHANGED, "HIGH", ctx));
                });
    }

    /** Günlük DNS_CHANGED re-alert'i monitör bazında bastırılmalı mı?
     *  (a) monitörde değişiklik alarmı kapalıysa, (b) son değişen değerlerin TAMAMI beklenen setteyse
     *  (iç/dış IP flip'i) re-alert atılmaz. Kayıt/monitör bulunamazsa mevcut davranış korunur (re-alert atılır). */
    private boolean dnsChangeRealertSuppressed(String domain, DnsRecord lastChanged) {
        if (lastChanged == null) return false;
        try {
            DnsMonitor mon = dnsMonitorRepo.findById(lastChanged.getMonitorId()).orElse(null);
            if (mon == null) return false;
            if (Boolean.FALSE.equals(mon.getDnsChangeAlertEnabled())) {
                log.info("DNS_CHANGED re-alert suppressed for {} (alert-disabled)", domain);
                return true;
            }
            if (DnsCheckerService.withinExpected(mon.getExpectedValue(), splitValues(lastChanged.getValue()))) {
                log.info("DNS_CHANGED re-alert suppressed for {} (expected-flip)", domain);
                return true;
            }
        } catch (Exception ex) {
            log.debug("DNS_CHANGED re-alert bastırma kontrolü başarısız: {} — {}", domain, ex.getMessage());
        }
        return false;
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
     *  (site.monitor.uptime.recovery-checks, default 1 = ilk başarılı kontrolde kapat) döner. */
    private int recoveryChecksFor(List<SweepItem> items) {
        for (SweepItem it : items) {
            Object v = it.ctxExtra() != null ? it.ctxExtra().get("monitor_recovery_checks") : null;
            if (v instanceof Number n && n.intValue() > 0) return n.intValue();
        }
        return Math.max(1, appSettings.getInt("site.monitor.uptime.recovery-checks", 1));
    }

    /** Aktif recovery aralığı (ms): per-monitor recoveryIntervalSeconds (ctxExtra monitor_recovery_interval_ms)
     *  varsa onu döner; yoksa null (→ pasif recovery). Keyword/ping/port sweep'lerinde set edilir.
     *  ACCESSIBILITY (envanter-kaynaklı uptime) için per-monitor override yoksa AKTİF recovery varsayılanı
     *  uygulanır — pasif "3 ardışık temiz 5-dk sweep" gereksinimi flapping host'ta asla tamamlanmıyordu
     *  (her down-sweep sayacı sıfırlıyordu). Aktif döngü (Port'un çalışan yolu) sweep-sınırı dalgalanmasına
     *  dayanıklıdır; erişim döndüğünde alarm ~recoveryChecks×interval içinde kapanır. */
    private Long recoveryIntervalMsFor(List<SweepItem> items) {
        for (SweepItem it : items) {
            Object v = it.ctxExtra() != null ? it.ctxExtra().get("monitor_recovery_interval_ms") : null;
            if (v instanceof Number n && n.longValue() > 0) return n.longValue();
        }
        if (!items.isEmpty() && EscalationService.TYPE_ACCESSIBILITY.equals(items.get(0).alertType())) {
            int ms = appSettings.getInt("site.monitor.uptime.recovery-interval-ms", 30000);
            return ms > 0 ? (long) ms : null;
        }
        return null;
    }

    /**
     * Çift-zincir guard'ının anahtarı.
     *
     * <p>Genel kural {@code tip:domain:detail}'dir çünkü bir domain'in birden çok izlenen yüzü
     * olabilir (port 443 / 8443, DNS A / MX) ve bunlar AYRI kesintilerdir; detail orada sabit bir
     * ayırıcıdır ("443", "A").
     *
     * <p>SENTETİKTE DEĞİL: {@code scriptedDetail} check sayaçlarını ve hata metnini taşır
     * ("FAIL — 0✓/2✗ · Request Failed … request timeout"), yani her sweep'te değişebilir. Anahtara
     * girince {@code putIfAbsent} guard'ı tutmuyor, aynı monitör için paralel teyit zincirleri
     * başlıyor ve her biri ayrı bir k6 permit'i yiyerek havuzu doyuruyordu. Monitör adı zaten
     * tekil olduğundan sentetikte detail'e gerek yok — detail gösterimde AYNEN korunur.
     */
    private static String confirmKey(SweepItem item) {
        // SCRIPTED_SLOW da ayni sebeple detail'siz: detail olculen sure ("8123 ms"), her sweep'te degisir.
        if (EscalationService.isScripted(item.alertType()))
            return item.alertType() + ":" + item.domain();
        return item.alertType() + ":" + item.domain() + ":" + item.detail();
    }

    void startConfirmation(SweepItem item) {
        String key = confirmKey(item);
        String firstFailureAt = now();
        ConfirmState initial = new ConfirmState(item.alertType(), item.domain(), item.detail(),
                0, effAttempts(item), firstFailureAt, System.currentTimeMillis() + effDelayMs(item));
        if (inFlight.putIfAbsent(key, initial) != null) {
            log.debug("Teyit zaten devam ediyor, atlanıyor: {}", key);
            return;
        }
        List<Map<String, Object>> attempts = new ArrayList<>(); // tek thread'li executor → senkronizasyon gereksiz
        if (effAttempts(item) <= 0) {
            // Immediate (confirmation period = 0) — DOWN tespitinde incident'ı HEMEN aç, teyit bekleme.
            log.warn("{} DOWN tespit edildi: {} [{}] — immediate mod (teyit yok), incident hemen açılıyor",
                    item.alertType(), item.domain(), item.detail());
            // try/catch/finally ZORUNLU (2026-08-20 bellek denetimi). withLock yalnız KİLİT bırakmayı
            // finally'ye alır, action.run()'ı sarmaz → escalation (veya buildOutageContext) fırlatırsa
            // istisna buradan dışarı çıkardı. İki sonucu vardı: (1) inFlight.remove atlanır, putIfAbsent
            // yeniden-giriş guard'ı olduğu için o anahtar kalıcı ölür ve monitörün kesinti teyidi bir daha
            // HİÇ başlamaz (restart'a kadar sessiz alarm körlüğü); (2) startConfirmation sweep'in domain
            // döngüsünden çağrıldığı için sweep'in KALAN domain'leri de işlenmeden düşerdi.
            // Aşağıdaki yapı N-denemeli yolla (runConfirmAttempt) simetriktir: logla, yut, anahtarı bırak.
            try {
                Map<String, Object> ctx = buildOutageContext(item, firstFailureAt, attempts);
                withLock(item.alertType(), item.domain(), () ->
                        escalationService.processConfirmedOutage(item.domain(), item.alertType(),
                                levelFor(item.alertType()), ctx));
            } catch (Exception e) {
                log.error("Immediate teyit başarısız oldu: {} — {}", key, e.getMessage(), e);
            } finally {
                inFlight.remove(key);
            }
            return;
        }
        log.info("{} DOWN tespit edildi: {} [{}] — {} sn arayla {} doğrulama denemesi başlatıldı",
                item.alertType(), item.domain(), item.detail(), effDelayMs(item) / 1000, effAttempts(item));
        confirmExecutor.schedule(
                () -> runConfirmAttempt(key, item, firstFailureAt, attempts, 1),
                effDelayMs(item), TimeUnit.MILLISECONDS);
    }

    /** Aktif teyit zincirleri (UI: "Teyit denemesi X/N"). domainFilter null → tümü. */
    public List<Map<String, Object>> activeConfirmations(String domainFilter) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConfirmState s : inFlight.values()) {
            if (domainFilter != null && !domainFilter.equalsIgnoreCase(s.domain())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("alert_type",      s.alertType());
            m.put("domain",          s.domain());
            m.put("detail",          s.detail());
            m.put("attempt",         s.attempt());
            m.put("total_attempts",  s.totalAttempts());
            m.put("started_at",      s.startedAt());
            m.put("next_attempt_at", ISO.format(Instant.ofEpochMilli(s.nextAttemptAtMs())));
            out.add(m);
        }
        return out;
    }

    void runConfirmAttempt(String key, SweepItem item, String firstFailureAt,
                           List<Map<String, Object>> attempts, int n) {
        try {
            // Canlı durum: bu deneme koşuyor; sonraki (varsa) effDelayMs sonra — UI 30sn poll'unda "X/N" görünür.
            inFlight.computeIfPresent(key, (k, s) -> new ConfirmState(s.alertType(), s.domain(), s.detail(),
                    n, s.totalAttempts(), s.startedAt(), System.currentTimeMillis() + effDelayMs(item)));
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
            // "skipped" = doğrulama YÜRÜTÜLEMEDİ (ör. k6 havuzu dolu). Kanıt yok ⇒ zinciri iptal et.
            // Kesinti gerçekse sonraki sweep zinciri yeniden açar; tersini yapmak (kanıtsız DOWN
            // saymak) altyapı darlığını KRİTİK alarma çevirirdi.
            if ("skipped".equals(r.get("status"))) {
                log.warn("Teyit denemesi yürütülemedi ({}. deneme): {} — {} · zincir iptal edildi, "
                        + "sonraki sweep yeniden değerlendirecek", n, key, r.get("error"));
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
            case EscalationService.TYPE_PORT_DOWN,
                 EscalationService.TYPE_PORT_SLOW   ->
                    appSettings.getBoolean("site.monitor.port.alert-enabled", portAlertEnabled);
            case EscalationService.TYPE_DNS_FAILURE,
                 EscalationService.TYPE_DNS_CHANGED,
                 EscalationService.TYPE_DNS_SLOW,
                 EscalationService.TYPE_DNS_UNEXPECTED,
                 EscalationService.TYPE_DNS_INCONSISTENT ->
                    appSettings.getBoolean("site.monitor.dns.alert-enabled", dnsAlertEnabled);
            case EscalationService.TYPE_KEYWORD,
                 EscalationService.TYPE_KEYWORD_SLOW,
                 EscalationService.TYPE_KEYWORD_SSL,
                 EscalationService.TYPE_KEYWORD_DOMAIN_EXPIRY ->
                    appSettings.getBoolean("site.monitor.keyword.alert-enabled", keywordAlertEnabled);
            case EscalationService.TYPE_PING_DOWN   ->
                    appSettings.getBoolean("site.monitor.ping.alert-enabled", pingAlertEnabled);
            case EscalationService.TYPE_HTTP_DOWN,
                 EscalationService.TYPE_HTTP_SSL,
                 EscalationService.TYPE_DOMAIN_EXPIRY ->
                    appSettings.getBoolean("site.monitor.http.alert-enabled", true);
            case EscalationService.TYPE_PAGE_DOWN,
                 EscalationService.TYPE_PAGE_INTEGRITY ->
                    appSettings.getBoolean("site.monitor.page.alert-enabled", true);
            case EscalationService.TYPE_PAGESPEED_DOWN,
                 EscalationService.TYPE_PAGESPEED_SLOW ->
                    appSettings.getBoolean("site.monitor.pagespeed.alert-enabled", true);
            // AYRI anahtar (diğer 8 türle parite). Eskiden motorun kendisine
            // (`scripted.enabled`) bakılıyordu: "bu hafta k6 alarmı sussun" demek sentetik
            // izlemeyi TAMAMEN durdurmak anlamına geliyordu — kontrol serisi ve uptime de kesiliyordu.
            case EscalationService.TYPE_SCRIPTED_FAIL,
                 EscalationService.TYPE_SCRIPTED_SLOW ->
                    appSettings.getBoolean("site.monitor.scripted.alert-enabled", true)
                    && appSettings.getBoolean("site.monitor.scripted.enabled", true);
            case EscalationService.TYPE_DOMAINMON_EXPIRY,
                 EscalationService.TYPE_DOMAINMON_UNKNOWN,
                 EscalationService.TYPE_DOMAINMON_STATUS,
                 EscalationService.TYPE_DOMAINMON_CHANGED ->
                    appSettings.getBoolean("site.monitor.domain.alert-enabled", true);
            default                                 ->
                    appSettings.getBoolean("site.monitor.uptime.alert-enabled", uptimeAlertEnabled);
        };
    }

    static String levelFor(String alertType) {
        return (EscalationService.TYPE_DNS_CHANGED.equals(alertType)
                || EscalationService.TYPE_DNS_SLOW.equals(alertType)
                || EscalationService.TYPE_DNS_UNEXPECTED.equals(alertType)
                || EscalationService.TYPE_DNS_INCONSISTENT.equals(alertType)
                || EscalationService.TYPE_HTTP_SSL.equals(alertType)
                || EscalationService.TYPE_DOMAIN_EXPIRY.equals(alertType)
                || EscalationService.isKeywordAux(alertType)
                || EscalationService.TYPE_PORT_SLOW.equals(alertType)
                || EscalationService.TYPE_SCRIPTED_SLOW.equals(alertType)
                || EscalationService.TYPE_PAGE_INTEGRITY.equals(alertType)
                // Sayfa yavaş ama AYAKTA → HIGH; CRITICAL yalnız gerçekten alınamadığında.
                || EscalationService.TYPE_PAGESPEED_SLOW.equals(alertType)
                || EscalationService.isDomainMon(alertType)) ? "HIGH" : "CRITICAL";
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
        // K5 damgasi: alarm acilirken AlertEvent'e yazilir (re-alert ctx'i tasimasa da
        // damga olayda kalir -- bkz. yukaridaki team_id notu).
        if (c.notificationGroupId() != null)
            ctx.put("notification_group_id", c.notificationGroupId());
        return ctx;
    }

    /** Açık DNS_CHANGED alarmının günlük re-alert'i için domain'in son changed kaydı (yoksa null).
     *  Manuel "Tekrar Bildir" yolu (EscalationService.reNotifyAsync) ile ORTAK statik yardımcıya delege. */
    private DnsRecord lastChangedRecord(String domain) {
        return DnsCheckerService.lastChangedRecord(dnsRecordRepo, domain);
    }

    /** Son changed kaydından re-alert ctx'i kur (kayıt yoksa boş ctx — mail generic mesaja düşer). */
    private Map<String, Object> reconstructChangeCtx(DnsRecord r) {
        return DnsCheckerService.changeCtxOf(r);
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
