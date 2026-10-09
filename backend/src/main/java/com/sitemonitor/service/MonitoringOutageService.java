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
 *
 * Kapanış = UZLAŞTIRMA (2026-09-29): her turda "DB'de açık alarm VAR + güncel sonuç sağlıklı" sorulur; geçiş
 * (DOWN→UP) aranmaz. recoveryChecks kadar ardışık sağlıklı tur (ya da aktif doğrulama zinciri) alarmı kapatır —
 * toplu-kesinti bastırması ve tür bildirimlerinin kapalı olması kapanışı DURDURMAZ, yalnız yeni alarmı durdurur.
 * Aynı anahtarı paylaşan kardeş izlemenin son gözlemi DOWN ise kapanış bekler (bkz. recoverDomain).
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

    /**
     * Teyit zinciri her denemeden ÖNCE hedefin hâlâ izlendiğini sorar (SchedulerService kaydeder).
     * {@code null} = her zaman izleniyor sayılır (eski davranış; birim testleri).
     *
     * <p>Neden: zincir 30 sn arayla N deneme sürer ve bu sürede kullanıcı monitörü/envanteri
     * silebilir ya da duraklatabilir. Silme/duraklatma yolları AÇIK alarmları sessizce kapatır
     * ama zincir bundan habersizdi: son deneme başarısız olunca artık VAR OLMAYAN hedef için
     * alarm açıyor, e-posta/push atıyordu — ne sweep (findByActiveTrue) ne recovery bir daha
     * dokunduğundan olay sonsuza kadar açık kalıyordu (QA 2026-09-10: #119 envanter, #120 HTTP).
     */
    private volatile java.util.function.Predicate<SweepItem> stillMonitored;

    public void setStillMonitored(java.util.function.Predicate<SweepItem> stillMonitored) {
        this.stillMonitored = stillMonitored;
    }

    boolean stillMonitored(SweepItem item) {
        java.util.function.Predicate<SweepItem> p = stillMonitored;
        if (p == null) return true;
        try { return p.test(item); }
        catch (Exception e) { return true; }   // kimlik çözümlenemezse eski davranış (fail-open)
    }

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
     *  teamId: standalone monitör için takım (alarmı doğru takıma yönlendirir); envanter-türevinde null.
     *  standalone: kullanıcının eklediği (bağımsız) monitör mü — diğer altı Port/DNS bağlamı gibi bağlama
     *  {@code "standalone": true} işareti olarak yazılır; takımı BOŞ bağımsız monitörün değişiklik alarmı da envanterdeki
     *  başka takıma düşmez (2026-09-28, O1). */
    public record DnsChange(String domain, String recordType,
                            String previousValue, String newValue, String detectedAt, Long teamId,
                            Long notificationGroupId,
                            Supplier<Map<String, Object>> recheck,
                            Boolean notifyEmail, Boolean notifyWebhook, Boolean standalone, Long monitorId,
                            String alertLevel) {
        /** Geriye uyumlu 12-arg kurucu (izlemenin seçili seviyesi bilinmiyor → WARNING; Y-A3-2 öncesi çağıranlar). */
        public DnsChange(String domain, String recordType, String previousValue, String newValue,
                         String detectedAt, Long teamId, Long notificationGroupId,
                         Supplier<Map<String, Object>> recheck, Boolean notifyEmail, Boolean notifyWebhook,
                         Boolean standalone, Long monitorId) {
            this(domain, recordType, previousValue, newValue, detectedAt, teamId, notificationGroupId, recheck,
                    notifyEmail, notifyWebhook, standalone, monitorId, null);
        }
        /** Geriye uyumlu 11-arg kurucu (izleme kimliği bilinmiyor — D-b2 öncesi çağıranlar). */
        public DnsChange(String domain, String recordType, String previousValue, String newValue,
                         String detectedAt, Long teamId, Long notificationGroupId,
                         Supplier<Map<String, Object>> recheck, Boolean notifyEmail, Boolean notifyWebhook,
                         Boolean standalone) {
            this(domain, recordType, previousValue, newValue, detectedAt, teamId, notificationGroupId, recheck,
                    notifyEmail, notifyWebhook, standalone, null, null);
        }
        /** Geriye uyumlu 10-arg kurucu (bağımsızlık bilinmiyor = işaret yok). */
        public DnsChange(String domain, String recordType, String previousValue, String newValue,
                         String detectedAt, Long teamId, Long notificationGroupId,
                         Supplier<Map<String, Object>> recheck, Boolean notifyEmail, Boolean notifyWebhook) {
            this(domain, recordType, previousValue, newValue, detectedAt, teamId, notificationGroupId, recheck,
                    notifyEmail, notifyWebhook, null);
        }
        /** Geriye uyumlu 8-arg kurucu (kanal bayrakları bilinmiyor = her ikisi de AÇIK). */
        public DnsChange(String domain, String recordType, String previousValue, String newValue,
                         String detectedAt, Long teamId, Long notificationGroupId,
                         Supplier<Map<String, Object>> recheck) {
            this(domain, recordType, previousValue, newValue, detectedAt, teamId, notificationGroupId, recheck, null, null, null);
        }
    }

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

    /**
     * "tip:domain" → KUŞAK numarası — aktif recovery döngüsünün çift-başlatma + iptal guard'ı.
     *
     * <p>Eskiden {@code Set} idi ve hem "zincir sürüyor" hem "dışarıdan iptal edildi" sinyalini
     * taşıyordu, ama planlanan görevin {@code ScheduledFuture}'ı tutulmadığı için gerçek iptal
     * YOKTU — yalnız görev girişindeki {@code contains(key)} bakışı vardı. İki yüzü:
     *
     * <p>(a) Sweep DOWN görüp anahtarı kaldırır, sonraki sweep UP görüp YENİDEN ekler; 1. zincirin
     * kuyrukta bekleyen görevi ateşlenir, {@code contains} artık TRUE olduğu için çalışır ve KENDİ
     * {@code n} sayacıyla {@code done >= required} koşulunu sağlayıp alarmı kapatır — arada gerçek
     * bir DOWN görülmüş olmasına rağmen.
     *
     * <p>(b) {@code contains} yalnız GİRİŞTE bakılıyordu; gövdedeki {@code recheck().get()} döngüsü
     * tüm monitörleri yeniden kontrol ettiği için saniyeler sürüyor ve o pencerede gelen iptali
     * hiç görmüyordu.
     *
     * <p>Kuşak numarası ikisini de kapatır: görev hem girişte hem {@code resolve} çağrısından hemen ÖNCE
     * kendi kuşağını doğrular.
     *
     * <p><b>BO0 (bug regresyon 2026-09-27) — canlılık.</b> İlk kuşak düzeltmesi iptalde anahtarı
     * {@code merge} ile ARTIRIP haritada BIRAKIYORDU; {@link #recoveryActive} ise "anahtar var = zincir
     * sürüyor" sayıyordu. Alarm açıkken tek bir DOWN turu geçtiyse, hedef düzeldiğinde
     * {@link #startRecovery} her turda "zaten sürüyor" deyip atlıyor, aktif kurtarma hiç başlamıyor ve alarm
     * SÜRESİZ açık kalıyordu (varsayılan ACCESSIBILITY kurulumu). Şimdi: harita YALNIZ gerçekten çalışan
     * zincirin kuşağını tutar (iptal = {@code remove}, eski semantik); kuşak numaraları ise anahtardan
     * bağımsız, uygulama genelinde tekdüze artan bir sayaçtan gelir → yeni bir zincir hiçbir eski görevin
     * numarasını YENİDEN KULLANAMAZ (anahtar silinip yeniden eklense bile). Kapı: MonitoringOutageServiceTest
     * {@code downRoundWhileAlarmOpen_thenRecovery_resolves} ve kardeşleri.
     */
    private final ConcurrentHashMap<String, Long> recoveryGeneration = new ConcurrentHashMap<>();

    /** Kuşak numarası kaynağı — anahtar başına değil GLOBAL, asla geri sarılmaz (BO0). */
    private final java.util.concurrent.atomic.AtomicLong recoveryGenerationSeq = new java.util.concurrent.atomic.AtomicLong();

    /** "tip:domain" → çalışan zincirin başlangıç anı (ms) — takılı zincir bekçisi (K2, 2026-09-29). */
    private final ConcurrentHashMap<String, Long> recoveryStartedAt = new ConcurrentHashMap<>();

    /** Zaman kaynağı (ms) — testler ilerletebilsin diye alan; üretimde duvar saati. */
    private java.util.function.LongSupplier nowMs = System::currentTimeMillis;

    /** Takılı zincir payı: zincir required × aralık + bu süre içinde bitmediyse ölü sayılır (yavaş k6/sayfa ölçümü dâhil). */
    static final long STALE_CHAIN_GRACE_MS = 15L * 60 * 1000;

    /** Zincir sürüyor mu (çalışan zincirin kuşak kaydı var mı)? */
    private boolean recoveryActive(String key) { return recoveryGeneration.containsKey(key); }

    /**
     * Yeni zincir kaydı: benzersiz kuşak numarasını döner; bu anahtarda zaten çalışan bir zincir varsa
     * {@code -1} (atomik — eşzamanlı sweep + manuel "Çalıştır" iki zincir açamaz).
     */
    private long beginRecoveryGeneration(String key) {
        long gen = recoveryGenerationSeq.incrementAndGet();
        if (recoveryGeneration.putIfAbsent(key, gen) != null) return -1L;
        recoveryStartedAt.put(key, nowMs.getAsLong());
        return gen;
    }

    /**
     * İptal (kurtarma penceresinde DOWN) ya da bitiş: kayıt SİLİNİR. Kuyrukta bekleyen eski görev artık kendi
     * kuşağını doğrulayamaz ve düşer; bir sonraki düzelme turu yeni (farklı numaralı) zincir başlatabilir.
     */
    private void endRecovery(String key) {
        recoveryGeneration.remove(key);
        recoveryStartedAt.remove(key);
    }

    /** Bu görev hâlâ GEÇERLİ kuşağa mı ait? Hem girişte hem alarm kapatmadan önce sorulur. */
    private boolean isCurrentGeneration(String key, long gen) {
        Long cur = recoveryGeneration.get(key);
        return cur != null && cur == gen;
    }

    @PreDestroy
    void shutdown() {
        confirmExecutor.shutdownNow();
        recoveryExecutor.shutdownNow();
        if (settingsReconcileExecutor instanceof java.util.concurrent.ExecutorService es) es.shutdownNow();
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
        outageReconciled.remove(alertType);   // kesinti yeniden açıldı → sonraki temiz turda DB'ye yine bakılsın
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

    /**
     * Kaynak başına "yeniden başlatma sonrası DB ile bir kez uzlaşıldı" işareti (QA ISSUE-001, 2026-09-21).
     * {@link #suppressionActive} bellek-içidir: kesinti kaydı yazıldıktan sonra uygulama yeniden başlarsa harita
     * boş gelir, {@link #clearSuppression} "zaten kapalıydı" diye erken döner ve DB'deki ONGOING kayıt süpürmeler
     * sağlıklı olsa da SONSUZA DEK açık kalır (canlıda 44+ saat "Still ongoing", DNS 11/11 Healthy). Bayrak kapalıyken
     * ilk sağlıklı turda DB'ye bir kez bakılır; sonraki turlar sorgu atmaz (kesinti açılınca işaret sıfırlanır).
     */
    private final Set<String> outageReconciled = ConcurrentHashMap.newKeySet();

    private void clearSuppression(String alertType) {
        boolean wasActive = Boolean.TRUE.equals(suppressionActive.put(alertType, false));
        if (!wasActive && !outageReconciled.add(alertType)) return;   // zaten kapalıydı ve DB bir kez uzlaştırıldı
        if (wasActive) {
            log.info("{} sweep: ağ kesintisi şüphesi kalktı — alarm işleme normale döndü", alertType);
        }
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
                if (!wasActive) {
                    log.info("{} sweep: yeniden başlatma öncesinden açık kalan ağ kesintisi kaydı kapatıldı (id={}, tespit={})",
                            alertType, ev.getId(), ev.getDetectedAt());
                }
            });
        } catch (Exception e) {
            log.warn("{} bastırma olayı kapatılamadı: {}", alertType, e.getMessage());
        }
    }

    /** Uptime/Port/DNS-failure sweep'leri her tur sonunda bir kez çağırır. */
    public void handleSweepResults(String alertType, List<SweepItem> items) {
        handleSweepResults(alertType, items, false);
    }

    /**
     * "Zamanlanmış sonuç yalnız tarama liderinden" kapısı (2026-10-09). {@code SchedulerService} açılışta takar; çok pod'da
     * kirayı (tarama liderliği) kaybeden pod'un yarıda kalmış taramasının sonucu alarm durumuna YAZILMAZ — yeni lider
     * aynı izlemeleri kendi turunda yoklar. Elle kontrol ({@code manual=true}) kapıdan geçmez (bugünkü davranış). Tek pod'da
     * ya da kapı takılmamışsa her şey eskisi gibi; kira sorgusu hata verirse kapı açık (izleme durmaz).
     */
    private volatile java.util.function.BooleanSupplier scheduledResultsFence;
    private final java.util.concurrent.atomic.AtomicLong fenceLogAt = new java.util.concurrent.atomic.AtomicLong();

    public void setScheduledResultsFence(java.util.function.BooleanSupplier fence) {
        this.scheduledResultsFence = fence;
    }

    /** Zamanlanmış sonuç işlenmeli mi (kapı yoksa ya da hata verirse evet). */
    private boolean scheduledResultsAllowed(String alertType, int count) {
        java.util.function.BooleanSupplier f = scheduledResultsFence;
        if (f == null) return true;
        boolean ok;
        try {
            ok = f.getAsBoolean();
        } catch (RuntimeException e) {
            return true;
        }
        if (!ok) {
            long last = fenceLogAt.get();
            if (System.currentTimeMillis() - last >= 60_000L && fenceLogAt.compareAndSet(last, System.currentTimeMillis())) {
                log.info("Zamanlanmış tarama sonucu işlenmedi: bu pod artık tarama lideri değil (tür={}, {} sonuç) — yeni lider yoklar",
                        alertType, count);
            }
        }
        return ok;
    }

    /**
     * @param manual kullanıcının "Şimdi kontrol et" (tekil ya da toplu) tuşundan gelen değerlendirme mi.
     *
     * <p><b>Elle kontrol ALARM AÇMAZ (ürün kararı 2026-09-29, prod olayı).</b> Eskiden elle yol zamanlayıcıyla aynı
     * hatta giriyor, yalnız ≥%50 toplu-kesinti bastırmasını ATLIYORDU — yani tam da "çok izleme birden düştü"
     * sinyalini yutan kapıyı kapatıyordu. Kapsamlı bir yönetici Sentetik İzleme'de toplu "Şimdi Kontrol Et (39)"
     * çalıştırınca başarısız her kontrol teyit zinciri açıp alarm üretti, açık (hayalet) alarmı olan izlemelerde
     * {@code processConfirmedOutage} anında yeniden uyarı / yarım ilk bildirim gönderdi ve yeni alarmlar
     * {@code StormService}'te kuruluş geneli fırtınaya dönüştü.
     *
     * <p>Kural: elle kontrol yalnız GÖZLEMDİR — sonuç kaydı (kontrol geçmişi) tetik ucunun işidir; burada yeni alarm,
     * teyit zinciri, eskalasyon, yeniden uyarı ve fırtına YOK, açık olayın bildirim sayaçları ilerlemez. Yalnız
     * KAPANIŞ uzlaştırması işler ({@link #reconcileRecoveries}): tamamen sağlıklı ve açık alarmı olan hedef normal
     * kurtarma kuralıyla kapanabilir (çözüm bildirimi mevcut kurala göre — yalnız açılışta SENT olanlara); DOWN sonuç
     * yalnız sürmekte olan kurtarma penceresini sıfırlar (yanlış kapanışa karşı). Ağ-kesintisi bastırma defterine
     * DOKUNULMAZ (tek elle kontrol sweep'in açtığı ONGOING kaydı kapatamaz). Başarısız elle kontrol sonraki zamanlanmış
     * sweep'in normal teyit kurallarıyla alarm açmasını ENGELLEMEZ. Kapı: {@code ManualCheckNoAlarmTest}.
     */
    public void handleSweepResults(String alertType, List<SweepItem> items, boolean manual) {
        if (items == null || items.isEmpty()) return;
        if (!manual && !scheduledResultsAllowed(alertType, items.size())) return;

        // Domain bazlı agregasyon: any-down = domain down; all-up = recovered
        Map<String, List<SweepItem>> byDomain = new LinkedHashMap<>();
        for (SweepItem it : items) {
            byDomain.computeIfAbsent(it.domain(), d -> new ArrayList<>()).add(it);
        }
        // Aynı anahtarı paylaşan izlemelerin son gözlemi (D2) — her kontrol bir gözlemdir; bastırma/kapalı bildirim
        // bunu değiştirmez.
        recordObservations(alertType, items);
        if (!manual) scheduledRoundSeen.add(alertType);   // D-6: bu türün kardeş gözlemleri en az bir kez dolduruldu

        // Elle kontrol (2026-09-29): yalnız kapanış uzlaştırması — yukarıdaki Javadoc.
        if (manual) {
            reconcileRecoveries(alertType, byDomain, "elle kontrol", true);
            return;
        }

        // K5 (prod 2026-09-29): tür için alarm bildirimleri KAPALIYKEN (…alert-enabled=false) metot eskiden en başta
        // dönüyordu. Kontroller sürdüğü hâlde (sentetik, HTTP, sayfa… türlerinde kontrol kapanmaz) o türün AÇIK alarmları
        // hiçbir zaman kapanmıyordu. Kapalı bildirim = yeni alarm / yeniden uyarı YOK; açık alarmın kapanışı ise sürer
        // (D-1: SESSİZ — bkz. closeAlarm).
        if (!alertEnabled(alertType)) {
            reconcileRecoveries(alertType, byDomain, "alarm bildirimleri kapalı", false);
            return;
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
            // K1 (prod 2026-09-29, kök neden): bastırma eskiden burada HİÇBİR alan adını işlemeden dönüyordu. Amacı
            // ağ kesintisi şüphesinde YENİ alarm selini durdurmaktır; ama SAĞLIKLI dönen ve açık alarmı olan hedefin
            // kurtarmasını da kesiyordu. Filoda sürekli ulaşılamayan hedefler varsa (pod'dan doğrudan erişilemeyen
            // host'lar, zaman aşımına düşen senaryolar) bastırma kalıcılaşıyor ve bu türün HİÇBİR alarmı kapanmıyordu —
            // izleme saatlerce/günlerce "OK" gösterirken alarm açık kalıyordu. Sağlıklı sonuç ağ kesintisine rağmen
            // gelmiş POZİTİF kanıttır: kurtarma sürer, yeni alarm/teyit/yeniden uyarı bastırılır.
            reconcileRecoveries(alertType, byDomain, "toplu kesinti bastırması", false);
            return;
        }
        clearSuppression(alertType);

        Map<String, AlertEvent> openByDomain = openAlertsByDomain(alertType, byDomain.keySet());

        for (Map.Entry<String, List<SweepItem>> entry : byDomain.entrySet()) {
            String domain = entry.getKey();
            // Zehirli kayıt yalıtımı (prod kapısı 2026-09-25, N6): withLock eylemin istisnasını dışarı yayar;
            // tek bir domain'in eskalasyonundaki deterministik bir istisna o türün KALAN domain'lerinde
            // re-alert, kurtarma ve teyit başlatmayı HER turda iptal ediyordu. Kardeş immediate yolu bu sınıfı
            // zaten kayıt başına yalıtıyordu.
            try {
                handleSweepDomain(alertType, domain, entry.getValue(), openByDomain.get(domain));
            } catch (Exception e) {
                log.error("Sweep sonucu işlenemedi, domain atlandı: {} [{}] — kalan domain'ler işlenmeye devam ediyor",
                        domain, alertType, e);
            }
        }
    }

    /**
     * {@link #handleSweepResults} döngüsünün TEK domain gövdesi — istisnası çağıranda yalıtılır (N6).
     *
     * <p><b>Y-1 (2026-09-29) — sahiplik.</b> Anahtar (alan adı + tür) takımlar arasında paylaşılabilir. Açık olay
     * varsa kalemler olayın SAHİBİNE ait olanlar (aynı takım / aynı envanter yönlendirmesi ya da olayı açan izlemenin
     * kendisi — {@link EscalationService#sameOwner}) ve olmayanlar diye ayrılır: yalnız sahibin DOWN kalemi "kesinti
     * sürüyor" sayılır (yeniden uyarı + kurtarma sıfırlama). Başka sahibin DOWN kalemi bu olayı ne açık tutar ne de onun
     * yeniden uyarısını tetikler (bildirim yanlış takıma giderdi); o izlemenin alarmı, açık olay kapandıktan sonraki ilk
     * DOWN turunda kendi bağlamıyla — kendi takımına — açılır.
     */
    private void handleSweepDomain(String alertType, String domain, List<SweepItem> domainItems, AlertEvent openEvent) {
        String rkey = alertType + ":" + domain;
        if (openEvent == null) {
            if (domainItems.stream().noneMatch(it -> !it.up())) {
                resetRecoveryCount(rkey);   // açık alarm yok → bayat sayaç temizle
                endRecovery(rkey);
            } else {
                resetRecoveryCount(rkey);   // yeni kesinti: önceki kurtarmadan kalan sayaç sayılmaz
                for (SweepItem it : domainItems) {
                    if (!it.up()) startConfirmation(it);
                }
            }
            return;
        }
        List<SweepItem> ownerDown = new ArrayList<>(), up = new ArrayList<>();
        int foreignDown = 0, foreignUp = 0;
        for (SweepItem it : domainItems) {
            // O-A3-1 (2026-09-29): Y-1'in KURTARMA yönü — başka sahibin UP kalemi bu olayın kurtarma kanıtı DEĞİLDİR
            // (ne sayaç artırır ne zincir başlatır). Eskiden yalnız DOWN dalı sahibe bakıyordu: yeniden başlatma sonrası
            // (kardeş gözlem haritası boş) B takımının sağlıklı turu A'nın olayını "✅ ÇÖZÜLDÜ" diye kapatıyor, A'nın
            // sonraki DOWN turu yeni INITIAL açıyordu. Kimliksiz kalem (envanter ACCESSIBILITY) sahip sayılır.
            if (!isOwnerItem(it, openEvent)) { if (it.up()) foreignUp++; else foreignDown++; continue; }
            if (it.up()) up.add(it);
            else ownerDown.add(it);
        }
        if (foreignUp > 0) {
            log.debug("{} [{}]: {} kalem başka sahibin izlemesi ve UP — açık olayın (#{}) kurtarma kanıtı sayılmaz",
                    domain, alertType, foreignUp, openEvent.getId());
        }
        if (!ownerDown.isEmpty()) {
            // Kesinti SÜRÜYOR — recovery penceresini SIFIRLA (pasif + aktif) + günlük re-alert yolu.
            // BİLİNÇLİ: açık alarm varken teyit zinciri (startConfirmation) YENİDEN başlatılmaz — sorun
            // zaten teyitli ve alarmlı; sonraki sweep'ler re-alert kadansını işletir. 30sn'lik teyit
            // re-check'leri yalnız İLK tespit → alarm açılana kadarki pencerede koşar.
            resetRecoveryCount(rkey);
            // İptal: çalışan zincirin kaydını SİL. Kuyrukta bekleyen eski görev artık kendi kuşağını
            // doğrulayamaz; kuşak numaraları global ve tekrarsız olduğundan sonraki UP turunun açacağı
            // yeni zincir de onu canlandıramaz. Anahtarı haritada bırakmak (eski merge) kurtarmayı
            // KALICI olarak kilitliyordu (BO0): startRecovery "zaten sürüyor" deyip hep atlıyordu.
            endRecovery(rkey);
            SweepItem firstDown = ownerDown.get(0);
            withLock(alertType, domain, () ->
                    escalationService.processConfirmedOutage(domain, alertType,
                            levelFor(alertType), sweepContext(firstDown)));
            return;
        }
        if (foreignDown > 0) {
            log.debug("{} [{}]: {} kalem başka sahibin izlemesi ve DOWN — açık olayı (#{}) etkilemez; o izlemenin alarmı "
                    + "bu olay kapandıktan sonra kendi takımıyla açılır", domain, alertType, foreignDown, openEvent.getId());
        }
        if (!up.isEmpty()) recoverDomain(alertType, domain, up, openEvent, false);
    }

    /** Kalem, açık olayın sahibine mi ait (Y-1)? Kimliksiz kalem (ör. envanter ACCESSIBILITY) sahip sayılır. */
    private static boolean isOwnerItem(SweepItem it, AlertEvent openEvent) {
        return EscalationService.sameOwner(openEvent, it.alertType(), it.ctxExtra());
    }

    /** Bu türün açık alarmları, alan adı başına EN YENİSİ (tek sorgu). */
    private Map<String, AlertEvent> openAlertsByDomain(String alertType, Collection<String> domains) {
        Map<String, AlertEvent> out = new HashMap<>();
        for (AlertEvent e : alertEventRepo.findOpenByDomainIn(domains)) {
            if (!alertType.equals(e.getAlertType()) || e.getDomain() == null) continue;
            out.merge(e.getDomain(), e, (a, b) ->
                    String.valueOf(a.getCreatedAt()).compareTo(String.valueOf(b.getCreatedAt())) >= 0 ? a : b);
        }
        return out;
    }

    /**
     * Yalnız KAPANIŞ uzlaştırması — bastırma (K1), kapalı bildirim (K5) ya da elle kontrol altında çağrılır: yeni alarm,
     * teyit zinciri ve yeniden uyarı YOK; açık olayın sahibine ait kalemleri sağlıklı hedef normal kurtarma yolundan
     * kapanır, sahibin DOWN kalemi sürmekte olan kurtarmayı iptal eder (ardışıklık şartı korunur). Başka sahibin DOWN
     * kalemi olayı etkilemez (Y-1). Alan adı başına yalıtılmış (N6).
     */
    private void reconcileRecoveries(String alertType, Map<String, List<SweepItem>> byDomain, String reason, boolean manual) {
        Map<String, AlertEvent> open;
        try {
            open = openAlertsByDomain(alertType, byDomain.keySet());
        } catch (Exception e) {
            log.warn("{} kapanış uzlaştırması atlandı ({}): açık alarmlar okunamadı — {}", alertType, reason, e.getMessage());
            return;
        }
        for (Map.Entry<String, List<SweepItem>> entry : byDomain.entrySet()) {
            String domain = entry.getKey();
            String rkey = alertType + ":" + domain;
            try {
                AlertEvent ev = open.get(domain);
                if (ev == null) {
                    // D-5: açık alarm yok → bayat kurtarma durumu kalmasın (eskiden yalnız DOWN'da siliniyordu).
                    resetRecoveryCount(rkey);
                    endRecovery(rkey);
                    continue;
                }
                List<SweepItem> up = new ArrayList<>();
                boolean ownerDown = false;
                for (SweepItem it : entry.getValue()) {
                    if (!isOwnerItem(it, ev)) continue;   // O-A3-1: yabancı sahibin kalemi (UP ya da DOWN) olayı etkilemez
                    if (it.up()) up.add(it);
                    else ownerDown = true;
                }
                if (ownerDown) {
                    resetRecoveryCount(rkey);
                    endRecovery(rkey);
                } else if (!up.isEmpty()) {
                    log.info("Kurtarma değerlendiriliyor ({}): {} [{}] — hedef sağlıklı, açık alarm var", reason, domain, alertType);
                    recoverDomain(alertType, domain, up, ev, manual);
                }
            } catch (Exception e) {
                log.error("Kapanış uzlaştırması işlenemedi, domain atlandı: {} [{}]", domain, alertType, e);
            }
        }
    }

    /** Elle sağlıklı sonucun sayaca girmesi için önceki SAYILMIŞ sağlıklı sonuçtan beri geçmesi gereken asgari süre (O-1). */
    static final long DEFAULT_MANUAL_RECOVERY_GAP_MS = 30_000L;

    /**
     * Sahibe ait kalemleri sağlıklı ve açık alarmı olan hedefin kurtarması — RECOVERY PERIOD: recoveryChecks kadar
     * ARDIŞIK başarılı kontrolde alarm kapanır.
     *
     * <p><b>Uzlaştırma (K2, prod 2026-09-29).</b> Eskiden aktif kurtarmada (izleme başına recoveryIntervalSeconds —
     * TÜM izleme türlerinde varsayılan 30 sn) pasif sayaç silinip karar yalnız bellekteki tek bir zamanlanmış zincire
     * bırakılıyordu. Zincir kaydı haritada kalıp görevi koşmazsa (kayıp görev / dolu havuz / beklenmedik Error),
     * sonraki her sağlıklı tur "zaten sürüyor" deyip atlıyor ve alarm uygulama yeniden başlatılana dek açık kalıyordu.
     * Artık her sağlıklı tur sayacı DA artırır: sweep'in kendisi taze bir kontroldür, N ardışık sağlıklı tur N
     * doğrulamayla aynı kanıttır. Aktif zincir yalnız hızlı yoldur; hangisi önce N'ye ulaşırsa kapatır (kapanış
     * atomik ve idempotent — markResolvedIfOpen). Karar DB'deki açık alarm + güncel sonuca dayanır, geçişe değil:
     * yeniden başlatma sonrası ilk sağlıklı turlar eski "hayalet" alarmı da aynı yoldan kapatır.
     *
     * <p><b>D2 + Y-1.</b> Anahtar birden çok izlemece paylaşılabilir. Bu turda olmayan ve son gözlemi DOWN olan kardeş
     * alarmı bekletir — YALNIZ olayın sahibine aitse (aynı takım; aynı host'ta aynı takımın 443 + 8443'ü). Başka takımın
     * ya da bağımsız↔türev karşı tarafın DOWN izlemesi bu olayı açık tutmaz (eskiden tutuyor, yeniden uyarıyı da bu
     * olayın takımına gönderiyordu).
     *
     * <p><b>O-1 — elle kontrol aralığı.</b> Elle sağlıklı sonuç sayaca ancak önceki sayılmış sağlıklı sonuçtan en az aktif
     * kurtarma aralığı (izleme ayarı; yoksa 30 sn) kadar sonra geliyorsa girer: elle kapanış, zamanlanmış hızlı yolun
     * kanıtı kadar kanıt ister (3 hızlı tıklama saniyeler içinde kapatıp ÇÖZÜLDÜ gönderiyordu).
     *
     * <p><b>D-6.</b> Yeniden başlatmadan sonra bu türün ilk zamanlanmış turu kardeş gözlemlerini doldurmadan, olayı AÇAN
     * izleme dışındaki bir izlemenin elle sağlıklı sonucu alarmı kapatmaz (DOWN kardeş henüz görülmemiş olabilir).
     */
    private void recoverDomain(String alertType, String domain, List<SweepItem> upItems, AlertEvent openEvent, boolean manual) {
        String rkey = alertType + ":" + domain;
        if (manual && !scheduledRoundSeen.contains(alertType)) {
            Long opener = EscalationService.contextMonitorId(openEvent);
            boolean fromOpener = opener != null && upItems.stream().anyMatch(it -> opener.equals(monitorIdOf(it)));
            if (opener != null && !fromOpener) {
                log.info("Elle kurtarma ertelendi: {} [{}] — yeniden başlatmadan sonra bu türün ilk zamanlanmış turu "
                        + "henüz koşmadı (kardeş izlemenin durumu bilinmiyor)", domain, alertType);
                return;
            }
        }
        SweepItem sibling = downSibling(alertType, domain, upItems, openEvent);
        if (sibling != null) {
            log.info("Recovery bekliyor: {} [{}] — aynı anahtarı paylaşan ve aynı sahibe ait başka izleme hâlâ DOWN "
                    + "(izleme {}, {}), alarm kapatılmıyor", domain, alertType, monitorIdOf(sibling), sibling.detail());
            resetRecoveryCount(rkey);
            endRecovery(rkey);
            return;
        }
        int required = recoveryChecksFor(upItems);
        Long recIntervalMs = recoveryIntervalMsFor(upItems);
        long now = nowMs.getAsLong();
        boolean count = true;
        if (manual) {
            long gap = recIntervalMs != null ? recIntervalMs : DEFAULT_MANUAL_RECOVERY_GAP_MS;
            Long last = recoveryLastCountedAt.get(rkey);
            if (last != null && now - last < gap) {
                count = false;
                log.info("Elle sağlıklı sonuç sayılmadı: {} [{}] — önceki sayılmış sonuçtan {} sn sonra (asgari {} sn)",
                        domain, alertType, (now - last) / 1000, gap / 1000);
            }
        }
        int up;
        if (count) {
            up = recoveryUpCount.merge(rkey, 1, Integer::sum);
            recoveryLastCountedAt.put(rkey, now);
        } else {
            up = recoveryUpCount.getOrDefault(rkey, 0);
        }
        if (required <= 1 || up >= required) {
            resetRecoveryCount(rkey);
            endRecovery(rkey);   // sürmekte olan aktif zincir artık kapatmaz (çift kapanış yok)
            if (required > 1) {
                log.info("Recovery tamamlandı: {} [{}] — {}/{} ardışık başarılı kontrol, alarm kapatılıyor",
                        domain, alertType, up, required);
            }
            closeAlarm(alertType, domain, configErrorReason(upItems));   // D-A3-6: yapılandırma hatası → sessiz
            return;
        }
        if (recIntervalMs != null) {
            // AKTİF recovery (recoveryIntervalSeconds set): recIntervalMs arayla aktif doğrulama — hızlı yol.
            startRecovery(alertType, domain, upItems, required, recIntervalMs);
        } else {
            log.info("Recovery sürüyor: {} [{}] — {}/{} ardışık başarılı kontrol (kapatma bekliyor)",
                    domain, alertType, up, required);
        }
    }

    /**
     * Otomatik kapanış — tür bildirimleri AÇIKSA normal çözüm yolu (ÇÖZÜLDÜ e-postası / webhook / push kuralları),
     * KAPALIYSA SESSİZ (D-1, 2026-09-29: yönetici "bu türün bildirimleri sussun" demişken çözüm postası gidiyordu;
     * push simetrisi resolveOpenAlertsSilently'de korunur).
     */
    private void closeAlarm(String alertType, String domain) {
        closeAlarm(alertType, domain, null);
    }

    /** Sweep bağlamı anahtarı: kalem yapılandırma hatasından (URL'de host yok vb.) sentetik "up" — kesinti DEĞİL, kurtarma da DEĞİL. */
    public static final String CTX_CONFIG_ERROR = "config_error";

    /** D-A3-6: kalemlerden biri yapılandırma hatası sentetik "up"ı ise sessiz kapanış gerekçesi, değilse null. */
    private static String configErrorReason(List<SweepItem> items) {
        if (items == null) return null;
        for (SweepItem it : items) {
            if (it != null && it.ctxExtra() != null && Boolean.TRUE.equals(it.ctxExtra().get(CTX_CONFIG_ERROR)))
                return "Sistem (yapılandırma hatası — izleme düzeltilmeli, hedef doğrulanmadı)";
        }
        return null;
    }

    /**
     * D-A3-6 (2026-09-29): yapılandırma hatasından gelen sentetik "up" kalemi (URL'de host yok → kontrol hiç yapılmadı)
     * açık alarmı SESSİZCE kapatır — yorumlar "sessizce kapanır" derken kalem normal çözüm yolundan geçip "✅ ÇÖZÜLDÜ —
     * sorun giderildi" e-postası + push'u üretiyordu; oysa hedef doğrulanmadı, izleme bozuk yapılandırıldı.
     * {@code resolvedSilently} işareti: fırtına çözümü bunu "kurtarıldı" saymaz, raporlar MTTR'a katmaz.
     */
    private void closeAlarm(String alertType, String domain, String silentReason) {
        if (silentReason != null) {
            withLock(alertType, domain, () -> escalationService.resolveOpenAlertsSilently(domain, Set.of(alertType), silentReason));
        } else if (alertEnabled(alertType)) {
            withLock(alertType, domain, () -> escalationService.resolveMonitoringAlertsForDomain(domain, alertType));
        } else {
            withLock(alertType, domain, () -> escalationService.resolveOpenAlertsSilently(domain, Set.of(alertType),
                    "Sistem (otomatik — bu türün alarm bildirimleri kapalı)"));
        }
    }

    // ── O-A3-5: bakım penceresinde açılan değişiklik alarmlarının ertelenmiş ilk bildirimi ─────────────────────────────

    /**
     * Bakım penceresinde açılmış (ilk bildirimi hiç gitmemiş) DNS_CHANGED / DOMAINMON_CHANGED olaylarının INITIAL'ını pencere
     * bitince TEK sefer gönderir. Karar {@code EscalationService.processConfirmedOutage}'da: bakım sürüyorsa yine bastırır,
     * gönderince {@code lastReAlertAt} damgalanır. Alan adı+tür kilidi ile tek yazar (çok pod). DNS günlük yeniden uyarı
     * döngüsü DNS_CHANGED için aynı yolu zaten işletir; alan adı izlemesinin böyle bir döngüsü olmadığından bu tik gerekir.
     * @return bildirim denemesi yapılan olay sayısı
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${site.monitor.maintenance.deferred-change-alert-ms:60000}", initialDelayString = "90000")
    public void notifyChangeAlertsDeferredByMaintenanceTick() {
        try { notifyChangeAlertsDeferredByMaintenance(); }
        catch (Exception e) { log.warn("Bakım sonrası değişiklik alarmı bildirimi atlandı: {}", e.getMessage()); }
    }

    int notifyChangeAlertsDeferredByMaintenance() {
        // Onaylı olay sorguda elenir (2026-10-09): onaylıya gönderim yok, damga da yok → her dakika boş kilit + değerlendirme.
        List<AlertEvent> pending = alertEventRepo.findUnacknowledgedOpenAwaitingInitial(MANUAL_CLOSE_TYPES);
        if (pending == null || pending.isEmpty()) return 0;
        int n = 0;
        for (AlertEvent e : pending) {
            if (e.getDomain() == null || e.getAlertType() == null) continue;
            if (!EscalationService.initialNotificationMissing(e, now())) continue;   // gönderim sürüyor olabilir (E9 payı)
            withLock(e.getAlertType(), e.getDomain(), () -> escalationService.completeDeferredInitialNotification(e));
            n++;
        }
        return n;
    }

    /** Ardışık sağlıklı sayacını ve son sayım anını birlikte sıfırlar (O-1). */
    private void resetRecoveryCount(String rkey) {
        recoveryUpCount.remove(rkey);
        recoveryLastCountedAt.remove(rkey);
    }

    /** "tip:domain" → son SAYILMIŞ sağlıklı sonucun anı (ms) — elle tıklama aralığı (O-1). */
    private final ConcurrentHashMap<String, Long> recoveryLastCountedAt = new ConcurrentHashMap<>();

    /** Yeniden başlatmadan beri en az bir ZAMANLANMIŞ turu işlenmiş türler (D-6). */
    private final Set<String> scheduledRoundSeen = ConcurrentHashMap.newKeySet();

    // ── D2: aynı anahtarı paylaşan izlemelerin son gözlemi ───────────────────────────────────

    /** Kardeş gözleminin geçerlilik süresi — günlük kadanslı izlemeleri (alan adı) de kapsar; daha eskisi yok sayılır. */
    static final long SIBLING_TTL_MS = 26L * 60 * 60 * 1000;

    /** Gözlem haritası budama aralığı (D-5): TTL'i aşan ya da artık izlenmeyen kayıtlar bu sıklıkla silinir. */
    static final long OBSERVATION_PRUNE_INTERVAL_MS = 10L * 60 * 1000;

    /**
     * Son gözlemi DOWN olan izleme (yalnız DOWN'lar tutulur; UP gözlem kaydı siler). {@code item} İNCE bir kopyadır —
     * yalnız tür, alan adı, detay ve sahiplik/canlılık için gereken bağlam anahtarları ({@code monitor_id},
     * {@code team_id}, {@code standalone}); izleme varlığını yakalayan recheck lambdası TUTULMAZ (D-5).
     */
    private record DownObservation(String domain, SweepItem item, long atMs) {}

    /** "tür|monitor_id" → son DOWN gözlemi. Kimliksiz kalemler (envanter ACCESSIBILITY) izlenmez — anahtarları tekildir. */
    private final ConcurrentHashMap<String, DownObservation> downObserved = new ConcurrentHashMap<>();

    private volatile long lastObservationPruneMs;

    private static Long monitorIdOf(SweepItem it) {
        Object v = it == null || it.ctxExtra() == null ? null : it.ctxExtra().get("monitor_id");
        return v instanceof Number n ? n.longValue() : null;
    }

    /** Gözlem için ince kopya — sahiplik + canlılık kararı için yeterli, recheck/varlık referansı yok. */
    private static SweepItem slim(SweepItem it) {
        Map<String, Object> ctx = new HashMap<>();
        for (String k : List.of("monitor_id", "team_id", "standalone")) {
            Object v = it.ctxExtra() == null ? null : it.ctxExtra().get(k);
            if (v != null) ctx.put(k, v);
        }
        return new SweepItem(it.alertType(), it.domain(), it.detail(), false, it.error(), ctx, null);
    }

    private void recordObservations(String alertType, List<SweepItem> items) {
        long now = nowMs.getAsLong();
        for (SweepItem it : items) {
            Long id = monitorIdOf(it);
            if (id == null || it.domain() == null) continue;
            String k = alertType + "|" + id;
            if (it.up()) downObserved.remove(k);
            else downObserved.put(k, new DownObservation(it.domain(), slim(it), now));
        }
        if (now - lastObservationPruneMs >= OBSERVATION_PRUNE_INTERVAL_MS) {
            lastObservationPruneMs = now;
            pruneObservations(now);
        }
    }

    /** D-5: TTL'i aşan ya da artık izlenmeyen (silinmiş / duraklatılmış) izlemenin DOWN kaydını siler. */
    int pruneObservations(long now) {
        int removed = 0;
        for (Map.Entry<String, DownObservation> e : downObserved.entrySet()) {
            DownObservation o = e.getValue();
            if (now - o.atMs() > SIBLING_TTL_MS || !stillMonitored(o.item())) {
                if (downObserved.remove(e.getKey(), o)) removed++;
            }
        }
        return removed;
    }

    /** Teyit zincirinde yeniden kontrol SAĞLIKLI döndü (geçici dalgalanma) → o izlemenin DOWN gözlemi düşer. */
    private void clearDownObservation(SweepItem item) {
        Long id = monitorIdOf(item);
        if (id != null) downObserved.remove(item.alertType() + "|" + id);
    }

    /**
     * Bu turda OLMAYAN, son gözlemi DOWN olan ve açık olayın SAHİBİNE ait kardeş izleme (aynı tür + alan adı) — yoksa
     * null. Başka sahibin izlemesi (Y-1), artık izlenmeyen (silinmiş / duraklatılmış / envanteri pasif) ya da gözlemi çok
     * eski kardeş engel sayılmaz; son ikisi temizlenir — aksi hâlde duraklatılan bir izleme paylaşılan alarmı sonsuza
     * dek açık tutardı.
     */
    private SweepItem downSibling(String alertType, String domain, List<SweepItem> batchItems, AlertEvent openEvent) {
        if (downObserved.isEmpty() || domain == null) return null;
        Set<Long> inBatch = new HashSet<>();
        for (SweepItem it : batchItems) { Long id = monitorIdOf(it); if (id != null) inBatch.add(id); }
        String prefix = alertType + "|";
        long now = nowMs.getAsLong();
        for (Map.Entry<String, DownObservation> e : downObserved.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            DownObservation o = e.getValue();
            if (!domain.equals(o.domain())) continue;
            if (inBatch.contains(monitorIdOf(o.item()))) continue;
            if (now - o.atMs() > SIBLING_TTL_MS || !stillMonitored(o.item())) {
                downObserved.remove(e.getKey(), o);
                continue;
            }
            if (openEvent != null && !isOwnerItem(o.item(), openEvent)) continue;   // Y-1: başka sahibin izlemesi
            return o.item();
        }
        return null;
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
        // Zamanlanmış DNS turu — değişiklik teyidi de alarm durumuna yazar: kirayı kaybeden pod'da hiçbiri işlenmez (2026-10-09).
        if (!scheduledResultsAllowed(EscalationService.TYPE_DNS_FAILURE, failureItems == null ? 0 : failureItems.size())) return;
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
                    // D-b2: olayı AÇAN izlemenin kaydı (aynı alan adında başka DNS izlemesinin değişikliği anlatılmasın).
                    Long opener = EscalationService.contextMonitorId(e);
                    DnsRecord lastChanged = DnsCheckerService.lastChangedRecord(dnsRecordRepo, e.getDomain(), opener);
                    // O-c2: AYNI SAHİBİN başka izlemesinin daha YENİ değişikliği varsa yeniden uyarı onu anlatır (aynı
                    // takımın ikinci izlemesinin değişikliği açanın kaydının arkasında kaybolmasın). Başka sahibin kaydı
                    // kullanılmaz — o izlemenin değişikliği kendi takımına olaysız bildirimle gitti (takım yalıtımı).
                    if (opener != null) lastChanged = newerSameOwnerChange(e, lastChanged);
                    if (dnsChangeRealertSuppressed(e.getDomain(), lastChanged)) return;
                    Map<String, Object> ctx = new LinkedHashMap<>(
                            withDnsChannelFlags(reconstructChangeCtx(lastChanged), lastChanged));
                    // Kayıt olayı açan izlemenin değilse (eski, kimliksiz olay / kayıt budanmış) sahiplik kanıtlanamaz →
                    // kimlik taşınmaz; sameOwner kimliksiz bağlamı sahip sayar (eski davranış — yeniden uyarı sürer).
                    if (opener == null || lastChanged == null || !opener.equals(lastChanged.getMonitorId()))
                        ctx.remove("monitor_id");
                    withLock(EscalationService.TYPE_DNS_CHANGED, e.getDomain(), () ->
                            escalationService.processConfirmedOutage(e.getDomain(),
                                    EscalationService.TYPE_DNS_CHANGED, levelFor(EscalationService.TYPE_DNS_CHANGED), ctx));
                });
    }

    /**
     * O-c2: alan adının EN YENİ değişiklik kaydı, olayın sahibiyle AYNI sahipteki bir izlemedense ve açanın kaydından
     * yeniyse onu döner; aksi hâlde {@code openerRecord}. Sahiplik izlemenin kendi bağlamından ({@code monitor_id}; bağımsızsa
     * {@code team_id} / {@code standalone}) — {@link EscalationService#sameOwner} ile.
     */
    private DnsRecord newerSameOwnerChange(AlertEvent e, DnsRecord openerRecord) {
        try {
            DnsRecord newest = DnsCheckerService.lastChangedRecord(dnsRecordRepo, e.getDomain());
            if (newest == null || newest == openerRecord || newest.getMonitorId() == null) return openerRecord;
            if (openerRecord != null && (newest.getMonitorId().equals(openerRecord.getMonitorId())
                    || String.valueOf(newest.getCheckedAt()).compareTo(String.valueOf(openerRecord.getCheckedAt())) <= 0))
                return openerRecord;
            DnsMonitor mon = dnsMonitorRepo.findById(newest.getMonitorId()).orElse(null);
            if (mon == null) return openerRecord;
            Map<String, Object> owner = new HashMap<>();
            owner.put("monitor_id", mon.getId());
            if (Boolean.TRUE.equals(mon.getStandalone())) {
                owner.put("standalone", true);
                if (mon.getTeamId() != null) owner.put("team_id", mon.getTeamId());
            }
            return EscalationService.sameOwner(e, EscalationService.TYPE_DNS_CHANGED, owner) ? newest : openerRecord;
        } catch (Exception ex) {
            log.debug("DNS_CHANGED en yeni kayıt karşılaştırması atlandı: {} — {}", e.getDomain(), ex.getMessage());
            return openerRecord;
        }
    }

    /** Günlük re-alert ctx'ine monitörün kanal bayraklarını + seçili alarm seviyesini basar (açılış yolu changeCtx ile parite, Y-A3-2). */
    private Map<String, Object> withDnsChannelFlags(Map<String, Object> ctx, DnsRecord lastChanged) {
        if (lastChanged == null || lastChanged.getMonitorId() == null) return ctx;
        try {
            DnsMonitor mon = dnsMonitorRepo.findById(lastChanged.getMonitorId()).orElse(null);
            if (mon == null) return ctx;
            return SchedulerService.chanCtx(ctx, mon);
        } catch (Exception ex) {
            return ctx;
        }
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
        // PAGESPEED_SLOW AYNI SINIFTA: detail'i pageSpeedBreachDetail uretir ve OLCULEN degeri tasir
        // ("TTFB 2955 ms"). Her sweep farkli bir anahtar dogurdugu icin cift-zincir guard'i (inFlight)
        // hic tutmuyordu: ihlal suren bir izleme icin her sweep 3 denemelik YENI bir zincir aciyordu,
        // her denemesi tam bir sayfa indirmesi (yuzlerce istek, on MB'lar). Sentetikte cozulen kusurun
        // ayni si; burada maliyeti daha agir cunku re-check tum alt kaynaklari yeniden cekiyor.
        if (EscalationService.isScripted(item.alertType())
                || EscalationService.TYPE_PAGESPEED_SLOW.equals(item.alertType()))
            return item.alertType() + ":" + item.domain();
        // O-A3-4 (2026-09-29): DNS_CHANGED anahtarına İZLEME KİMLİĞİ girer — çift kaynaklı alan adında (envanter türevi +
        // bağımsız, ya da iki takımın izlemesi) aynı tur içinde görülen aynı değişiklik iki AYRI teyit zinciri açar; eskiden
        // ikinci sahibin zinciri "teyit zaten devam ediyor" diye düşüyor, o takımın turu değişikliği zaten kaydettiği için
        // (taban ilerler) sinyal o takım için KALICI yutuluyordu. Teyit sonunda açık olay varsa O-c2 dalı sahibine olaysız
        // bildirim gönderir. Kimliksiz kalem eski anahtarda kalır.
        if (EscalationService.TYPE_DNS_CHANGED.equals(item.alertType())) {
            Long mid = monitorIdOf(item);
            if (mid != null) return item.alertType() + ":" + item.domain() + ":" + item.detail() + ":" + mid;
        }
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
        // Bu liste TEK bir teyit zincirine aittir ve senkronize değildir. GEREKÇE ÖNEMLİ:
        // eskiden burada "tek thread'li executor" yazıyordu — YANLIŞ. `confirmExecutor`
        // dört thread'li (bkz. :124). Güvenli olmasının sebebi havuzun boyutu değil, her
        // denemenin bir ÖNCEKİNİN İÇİNDEN planlanması: `schedule()` happens-before kurar,
        // yani aynı zincirin iki denemesi asla eşzamanlı koşmaz ve listeye tek thread dokunur.
        // Biri "havuz zaten tek thread" diye denemeleri PARALEL planlarsa bu liste yarışa girer;
        // o durumda burada eşzamanlı bir koleksiyon gerekir.
        List<Map<String, Object>> attempts = new ArrayList<>();
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
            if (!stillMonitored(item)) {
                log.info("Teyit zinciri iptal — hedef artık izlenmiyor (silindi/duraklatıldı): {} ({}. deneme)", key, n);
                inFlight.remove(key);
                return;
            }
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
                clearDownObservation(item);   // D2: bu izleme artık kardeşinin kapanışını bekletmez
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
            endRecovery(key);   // tek kontrol yeterli → beklemeden kapat
            closeAlarm(alertType, domain, configErrorReason(items));
            return;
        }
        // Takılı zincir bekçisi (K2): kaydı olup görevi süresinde bitmeyen zincir ölü sayılır ve yenisiyle değiştirilir —
        // eskiden "zaten sürüyor" deyip SÜRESİZ atlanıyordu. Eski kuşağın geç ateşlenen görevi kuşak doğrulamasında düşer.
        Long startedAt = recoveryStartedAt.get(key);
        if (recoveryActive(key) && startedAt != null
                && nowMs.getAsLong() - startedAt > (long) required * intervalMs + STALE_CHAIN_GRACE_MS) {
            log.warn("Aktif recovery zinciri takılı görünüyor (başlangıç {} sn önce): {} — yenisiyle değiştiriliyor",
                    (nowMs.getAsLong() - startedAt) / 1000, key);
            endRecovery(key);
        }
        final long gen = recoveryActive(key) ? -1L : beginRecoveryGeneration(key);
        if (gen < 0) {
            log.debug("Aktif recovery zaten sürüyor, atlanıyor: {}", key);
            return;
        }
        log.info("Recovery başladı (aktif): {} [{}] — {} sn arayla {} doğrulama denemesi",
                domain, alertType, intervalMs / 1000, required - 1);
        recoveryExecutor.schedule(
                () -> runRecoveryAttempt(key, gen, alertType, domain, items, required, intervalMs, 1),
                intervalMs, TimeUnit.MILLISECONDS);
    }

    void runRecoveryAttempt(String key, long gen, String alertType, String domain, List<SweepItem> items,
                            int required, long intervalMs, int n) {
        if (!isCurrentGeneration(key, gen)) return;   // arada DOWN / yeniden başlatma → bu zincir iptal
        try {
            boolean allUp = true;
            boolean skipped = false;
            for (SweepItem it : items) {
                Map<String, Object> r = it.recheck().get();
                if (!"up".equals(r.get("status"))) {
                    allUp = false;
                    skipped = "skipped".equals(r.get("status"));
                    break;
                }
            }
            if (!allUp) {
                if (skipped) {
                    // Doğrulama YÜRÜTÜLEMEDİ (ör. k6 havuzu dolu) — DOWN kanıtı değil: zincir biter, ardışık sağlıklı
                    // tur sayacı korunur; sonraki sağlıklı sweep kurtarmayı sürdürür.
                    log.info("Recovery doğrulaması yürütülemedi: {} [{}] — {}. denemede, zincir sonlandı (sayaç korunur)",
                            domain, alertType, n);
                } else {
                    log.info("Recovery kesildi (yeniden DOWN): {} [{}] — {}. denemede", domain, alertType, n);
                    resetRecoveryCount(key);   // ardışıklık bozuldu
                }
                if (isCurrentGeneration(key, gen)) endRecovery(key);
                return;   // alarm açık kalır; sonraki sweep kesinti-sürüyor yolunu işletir
            }
            int done = n + 1;   // ilk başarılı sweep (tetikleyici) = 1, sonrası aktif re-check'ler
            if (done >= required) {
                // İKİNCİ kuşak doğrulaması: yukarıdaki recheck döngüsü saniyeler sürdü, o pencerede
                // sweep DOWN görüp zinciri iptal etmiş olabilir. Alarmı kapatmadan HEMEN ÖNCE bak.
                if (!isCurrentGeneration(key, gen)) {
                    log.info("Recovery iptal edilmiş, alarm kapatılmıyor: {} [{}]", domain, alertType);
                    return;
                }
                log.info("Recovery tamamlandı (aktif): {} [{}] — {}/{} ardışık başarılı, alarm kapatılıyor",
                        domain, alertType, done, required);
                endRecovery(key);
                resetRecoveryCount(key);
                closeAlarm(alertType, domain, configErrorReason(items));
                return;
            }
            recoveryExecutor.schedule(
                    () -> runRecoveryAttempt(key, gen, alertType, domain, items, required, intervalMs, n + 1),
                    intervalMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("Recovery re-check hatası: {} [{}] — {}", domain, alertType, e.getMessage(), e);
            if (isCurrentGeneration(key, gen)) endRecovery(key);
        }
    }

    /**
     * D-11 (2026-09-29): bir türün alarm bildirimleri KAPATILINCA o türün açık alarmları SESSİZCE kapanır (tekil izlemeyi
     * duraklatmanın deseni). Çoğu türde {@code alert-enabled=false} sweep'i tümden durdurur (kontrol yok): açık alarm hiç
     * kapanamaz, ekranda "kendiliğinden kapanır" diye asılı kalırdı. Ayar değişince ve açılışta bir kez uzlaştırılır;
     * hiçbir tür kapalı değilse sorgu atılmaz.
     *
     * <p><b>D-b8 (2026-09-29).</b> Olay ayar kaydının İŞLEMİ içinde yayımlanıyor; toplu kapanış eskiden o işlemin içinde
     * EŞZAMANLI koşuyordu — yüzlerce açık alarmda ayar kaydı yanıtı bekliyor, kapanışlar ayar işlemine bağlanıyordu
     * (geri alınırsa kapanış da gidiyordu). Artık işlem TAMAMLANDIKTAN sonra ({@code AFTER_COMMIT}; işlem yoksa hemen)
     * tek iş parçacıklı arka plan havuzunda koşar.
     */
    @org.springframework.transaction.event.TransactionalEventListener(
            phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSettingsChanged(AppSettingsChangedEvent ev) {
        if (ev == null) return;
        boolean relevant = ev.changedKeys() == null || ev.changedKeys().isEmpty() || ev.changedKeys().stream()
                .anyMatch(k -> k != null && (k.endsWith(".alert-enabled") || "site.monitor.scripted.enabled".equals(k)));
        if (!relevant) return;
        try {
            settingsReconcileExecutor.execute(() -> closeAlarmsOfDisabledTypes("ayar değişikliği"));
        } catch (Exception ex) {
            log.warn("Kapalı türlerin alarm uzlaştırması kuyruğa alınamadı: {}", ex.getMessage());
        }
    }

    /** D-b8: toplu sessiz kapanışın arka plan yürütücüsü (paket-özel: testte eşzamanlı yürütücüyle değiştirilir). */
    java.util.concurrent.Executor settingsReconcileExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "monitoring-settings-reconcile");
        t.setDaemon(true);
        return t;
    });

    /**
     * D-b9: ELLE kapanan değişiklik alarmları (DNS kaydı / alan adı kaydı değişti — olası ele geçirme sinyali) toplu
     * sessiz kapanışa GİRMEZ: kendiliğinden kapanmazlar (kapalı bildirim onları "asılı" bırakmaz), kapanışları operatörün
     * incelemesine bağlıdır. Bildirimleri kapatmak kanıtı silmemeli. Arayüzdeki "elle kapanır" listesiyle aynı.
     */
    static final Set<String> MANUAL_CLOSE_TYPES =
            Set.of(EscalationService.TYPE_DNS_CHANGED, EscalationService.TYPE_DOMAINMON_CHANGED);

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onReadyCloseDisabledTypeAlarms() {
        closeAlarmsOfDisabledTypes("açılış");
    }

    /** @return sessizce kapatılan (alan adı, tür) sayısı */
    int closeAlarmsOfDisabledTypes(String reason) {
        try {
            Set<String> disabled = new HashSet<>();
            for (String t : EscalationService.MONITORING_ALERT_TYPES)
                if (!MANUAL_CLOSE_TYPES.contains(t) && !alertEnabled(t)) disabled.add(t);   // D-b9
            if (disabled.isEmpty()) return 0;
            int n = 0;
            for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
                if (e.getDomain() == null || !disabled.contains(e.getAlertType())) continue;
                escalationService.resolveOpenAlertsSilently(e.getDomain(), Set.of(e.getAlertType()),
                        "Sistem (bu türün alarm bildirimleri kapatıldı)");
                n++;
            }
            if (n > 0) log.info("Bildirimleri kapalı türlerin açık alarmları sessizce kapatıldı ({}): {} alarm, türler {}",
                    reason, n, disabled);
            return n;
        } catch (Exception ex) {
            log.warn("Kapalı türlerin açık alarmları kapatılamadı ({}): {}", reason, ex.getMessage());
            return 0;
        }
    }

    /** Paket-özel: kapı testi (MonitoringOutageServiceTest) her alarm tipi için doğru
     *  ayar anahtarının sorulduğunu doğrudan doğrular. */
    boolean alertEnabled(String alertType) {
        // ELLE SAYMA YOK. Asagidaki switch'te domain dali DORT tip yaziyordu, oysa
        // EscalationService ALTI DOMAINMON_* sabiti tanimliyor: TRANSFER_LOCK ve BLACKLIST
        // switch'ten dusup `default` dalina, yani UPTIME anahtarina bagliyordu. Sonuc iki
        // yonlu yanlisti — yonetici "alan adi alarmlarini sustur" dediginde
        // (`site.monitor.domain.alert-enabled=false`) bu iki tip YINE mail/push gonderiyor,
        // uptime alarmlarini kapattiginda ise domain alarmlari acikken SESSIZCE susuyorlardi.
        // Tipleri sayan TEK kaynak isDomainMon; liste buraya kopyalanmaz.
        if (EscalationService.isDomainMon(alertType)) {
            return appSettings.getBoolean("site.monitor.domain.alert-enabled", true);
        }
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
            case EscalationService.TYPE_PING_DOWN,
                 EscalationService.TYPE_PING_SLOW   ->
                    appSettings.getBoolean("site.monitor.ping.alert-enabled", pingAlertEnabled);
            case EscalationService.TYPE_HTTP_DOWN,
                 EscalationService.TYPE_HTTP_SSL,
                 EscalationService.TYPE_HTTP_SLOW,
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
            default                                 ->
                    appSettings.getBoolean("site.monitor.uptime.alert-enabled", uptimeAlertEnabled);
        };
    }

    /**
     * Bağlamda seviye YOKSA kullanılan yedek (2026-09-19 ürün kararı): süre-bitişi dışındaki her izleme alarmı
     * WARNING ile açılır. İzlemeye bağlı sweep'ler seviyeyi bağlama damgalar (SchedulerService.chanCtx →
     * izlemenin seçili seviyesi); envanter-türevi (izlemesiz) kontroller bu yedeğe düşer. Eski hâl tipe göre
     * HIGH/CRITICAL sabitiydi ve her kesinti eskalasyon kontaklarına gidiyordu.
     */
    static String levelFor(String alertType) {
        return com.sitemonitor.model.MonitorAlertPrefs.LEVEL_WARNING;
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

    /** Paket içi statik: üretici sözleşmesi testle pinlenir (EscalationContactLeakTest bağlamı buradan kurar, D8). */
    static Map<String, Object> changeCtx(DnsChange c) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("record_type", c.recordType());
        ctx.put("old_values", splitValues(c.previousValue()));
        ctx.put("new_values", splitValues(c.newValue()));
        ctx.put("changed_at", c.detectedAt());
        // Standalone monitör: alarmı takıma yönlendir (processConfirmedOutage ctx team_id'yi kullanır). Envanter türevi
        // monitörde teamId null gelir (SchedulerService.alarmTeamOf) → takım + UG envanterden. NOT: günlük re-alert
        // reconstructChangeCtx'ten gelir (team_id taşımaz); takım açık olayın damgasından okunur (O5).
        if (c.teamId() != null) ctx.put("team_id", c.teamId());
        if (Boolean.TRUE.equals(c.standalone())) ctx.put("standalone", true);   // O1: diğer altı Port/DNS bağlamı gibi
        // D-b2: olayı AÇAN izleme — sahiplik (Y-1: silme/duraklatma yalnız kendi olayını kapatır), günlük yeniden
        // uyarının doğru izlemenin kaydını seçmesi ve e-posta derin linki için.
        if (c.monitorId() != null) ctx.put("monitor_id", c.monitorId());
        // K5 damgasi: alarm acilirken AlertEvent'e yazilir (re-alert ctx'i tasimasa da
        // damga olayda kalir -- bkz. yukaridaki team_id notu).
        if (c.notificationGroupId() != null)
            ctx.put("notification_group_id", c.notificationGroupId());
        // Diğer sekiz sweep kalemi chanCtx'ten geçer; DNS_CHANGED geçmiyordu → monitörde "E-posta"/
        // "Webhook" kapalı olsa da değişiklik alarmı her iki kanaldan gidiyordu.
        Map<String, Object> out = SchedulerService.chanCtx(ctx, c.notifyEmail(), c.notifyWebhook());
        // Y-A3-2 (2026-09-29): izlemenin SEÇİLİ alarm seviyesi de damgalanır (diğer türlerdeki chanCtx(ctx, izleme) ile
        // parite). Eskiden DNS_CHANGED hep WARNING açılıyordu: CRITICAL seçilmiş izlemede eskalasyon kişileri, yönetici
        // push kademesi ve 7/24 asgari seviye kapısı sessizce atlanıyordu.
        out.putIfAbsent("alert_level", com.sitemonitor.model.MonitorAlertPrefs.effectiveLevel(c.alertLevel()));
        return out;
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
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.debug("İzleme alarm kilidi başka replikada: {} — atlanıyor", lockName);
            return;
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            // Kilit TABLOSU yok (ilk açılışta ddl-auto henüz koşmadı) — bilinçli tek-pod geri düşüşü.
            log.warn("Distributed lock table unavailable (HA degraded): {}", e.getMessage());
            acquired = false; // degrade: kilitsiz devam — tek instance kurulumlarda güvenli
        } catch (Exception e) {
            if (StormService.isUniqueViolation(e)) {
                log.debug("İzleme alarm kilidi başka replikada: {} — atlanıyor", lockName);
                return;
            }
            // SchedulerService.tryAcquireSchedulerLock ile aynı kural: geçici DB hatasında (deadlock,
            // statement-timeout, bağlantı kopması) kilitsiz devam etmek çok-pod'da aynı alarmı İKİ kez
            // açmak (çift alert_event + çift eskalasyon maili/push) demekti. Güvenli taraf bu turu
            // ATLAMAK — teyit zinciri bir sonraki sweep'te aynı sinyalle tekrar kurulur.
            log.warn("İzleme alarm kilidi alınamadı — {} bu tur atlanıyor (güvenli taraf): {}", lockName, e.getMessage());
            return;
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
