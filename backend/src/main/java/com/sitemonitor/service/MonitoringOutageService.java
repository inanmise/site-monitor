package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DnsRecord;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.service.evalstate.EvalLoad;
import com.sitemonitor.service.evalstate.EvalLoadGate;
import com.sitemonitor.service.evalstate.EvalLoadReport;
import com.sitemonitor.service.evalstate.EvalSnapshot;
import com.sitemonitor.service.evalstate.EvalStateReadSwitch;
import com.sitemonitor.service.evalstate.EvalStateSink;
import com.sitemonitor.service.evalstate.EvalStateSource;
import com.sitemonitor.service.monitoring.ChangeStates;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
 * Liderlik devri = yeniden başlatma (2026-10-21, çok pod R1-B): tarama liderliği bu pod'da her değiştiğinde
 * ({@code SweepLeadershipChangedEvent} — alındı / kaybedildi / bırakıldı) TÜM bellek içi alarm durumu sıfırlanır
 * ({@link #resetVolatileState}): kurtarma sayaçları, kardeş DOWN gözlemleri, tur / bastırma bayrakları; çalışan teyit ve
 * kurtarma zincirleri bir sonraki adımlarında kuşak doğrulamasıyla düşer (iş parçacığı öldürülmez). Zamanlanmış turun
 * başlattığı zincir son adımından (alarm açma / kapatma) önce kapıyı yeniden sorar — kirayı kaybeden pod karar yazmaz;
 * elle kontrolün başlattığı kurtarma zinciri kapıdan muaftır (bugünkü elle kontrol kuralı). Kapı hata verirse KAPALI
 * sayılır. Kenar tetikli değişiklik alarmlarının kararı {@code change_state} kolonunda kalıcıdır ({@code ChangeStates}).
 *
 * Kapanış = UZLAŞTIRMA (2026-09-29): her turda "DB'de açık alarm VAR + güncel sonuç sağlıklı" sorulur; geçiş
 * (DOWN→UP) aranmaz. recoveryChecks kadar ardışık sağlıklı tur (ya da aktif doğrulama zinciri) alarmı kapatır —
 * toplu-kesinti bastırması ve tür bildirimlerinin kapalı olması kapanışı DURDURMAZ, yalnız yeni alarmı durdurur.
 * Aynı anahtarı paylaşan kardeş izlemenin son gözlemi DOWN ise kapanış bekler (bkz. recoverDomain).
 *
 * Kalıcı değerlendirme durumu — İKİLİ YAZIM (2026-10-23, çok pod Sürüm 3-1, plan 3a): yukarıdaki bellek durumunun her
 * mutasyonundan SONRA bir kanca ({@code note*}) değeri {@link EvalStateSink}'e (kaydedici, pod başına bir) KUYRUĞA alır;
 * tek bir daemon yazıcı tablolara boşaltır. Kararlar 3-1'de HER ZAMAN bellekten verilir — kaydedici yok, NOOP, kapalı
 * ya da her çağrıda fırlatıyor olsa da her alarm / kurtarma / bastırma / teyit kararı aynıdır (değişmez I-1); kanca
 * veritabanına gitmez, beklemez, istisnasını yutar. Doğrulama için bellek görüntüsü {@link #verifySnapshot()}; güncel
 * değerin dar okuma yüzü {@link EvalStateSource}. Yeni bir bellek durumu alanı = yeni kanca + doğrulama türü.
 *
 * Devirde OKUMA (2026-10-28, çok pod Sürüm 3-2 B, tasarım R3-2; ayar {@code site.monitor.eval-state.read-enabled},
 * VARSAYILAN KAPALI): ayar açıkken GAINED'de değerlendirme kapısı ({@link EvalLoadGate}) kurulur — yalnız bellek; her giriş
 * ({@link #handleSweepResults}, {@link #handleDnsSweep}, {@link #requeueDnsChanges}) yükleme bitene ya da 20 sn son tarihe
 * dek bekler. Kaydedicinin yazıcı iş parçacığı önceki sahibin kuşağını okuyup {@link #applyEvalLoad} çağırır: yük yalnız
 * uçuşta değerlendirme yokken ve BOŞ belleğe uygulanır (asla birleştirilmez); temiz devirde (FULL) sayaçlar, kardeşler,
 * bastırma, tur ve pencere; çökmede (OBSERVATIONS) yalnız kardeşler ve pencere. Teyit / kurtarma zincirleri her durumda
 * yeniden başlar. Ayar kapalıyken kapı hiç kurulmaz: her karar, günlük ve kanca v20.168.1 ile birebir aynıdır (J-1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringOutageService implements EvalStateSource {

    private final AlertEventRepository alertEventRepo;
    private final EscalationService escalationService;
    private final JdbcTemplate jdbcTemplate;
    private final DnsRecordRepository dnsRecordRepo;
    private final DnsMonitorRepository dnsMonitorRepo;
    private final AppSettingsService appSettings;
    private final com.sitemonitor.repository.NetworkOutageEventRepository networkOutageRepo;

    /** Değişiklik alarmı kilidi alınamayınca bırakılan iz (2026-10-21, çok pod R1-B) — isteğe bağlı: bean yoksa yalnız günlük. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.NotificationLogRepository notificationLogRepo;

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

    /**
     * Hedef hâlâ izleniyor mu (kayıtlı yüklem; yoksa / hata verirse true). Çok pod Sürüm 3-1'den beri {@code public}:
     * kalıcı değerlendirme doğrulayıcısı ({@code EvalStateVerifier}, kaydedicinin yazıcı iş parçacığında) bellekte
     * budanmış kardeş gözleminin ve sürdürülemeyecek teyit satırının kararını AYNI kuralla verir. Karar yolunu değiştirmez.
     */
    public boolean stillMonitored(SweepItem item) {
        java.util.function.Predicate<SweepItem> p = stillMonitored;
        if (p == null) return true;
        try { return p.test(item); }
        catch (Exception e) { return true; }   // kimlik çözümlenemezse eski davranış (fail-open)
    }

    /**
     * Üç durumlu "hâlâ izleniyor mu" — YALNIZ doğrulayıcı için (R3-1 FX-STATE F5): yüklem hata verirse {@code null}
     * (bilinmiyor — doğrulayıcı anahtarı atlar; karar yolunun {@link #stillMonitored} fail-open kuralı uyuşmazlık uydurmasın).
     * Yüklem yoksa true. Karar yolu bunu KULLANMAZ.
     */
    public Boolean stillMonitoredOrNull(SweepItem item) {
        java.util.function.Predicate<SweepItem> p = stillMonitored;
        if (p == null) return Boolean.TRUE;
        try { return p.test(item); }
        catch (Exception e) { return null; }
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

    /**
     * SOAK-1 (2026-10-22): bastırma oranının pencere TABANI (ms). Oran artık TEK tur çağrısının kalemleri üzerinden değil,
     * türün son {@code max(3 × tur aralığı, bu taban)} içindeki alan adı başına EN SON zamanlanmış sonucu üzerinden
     * hesaplanır. İzleme başına due zamanlı kümede bir tur yalnız o anda vakti gelen izlemeleri taşır: 9 izlemeli bir
     * turda 7 ağ hatası (0,78) gerçek kesintileri ~5 dk bastırdı, öbür tur her 30 sn'de şüpheyi kaldırıp
     * {@code network_outage_events}'e her dakika açılış / kapanış satırı yazdırdı.
     */
    @Value("${site.monitor.network.suppression-window-ms:300000}")
    private long suppressionWindowFloorMs = 300_000L;

    /** Pencerenin üst sınırı — çok seyrek turlu türde (günlük alan adı turu) pencere sınırsız büyümesin. */
    static final long SUPPRESSION_WINDOW_CAP_MS = 60L * 60_000L;

    /** Test saati (yalnız testler değiştirir). */
    java.util.function.LongSupplier suppressionClock = System::currentTimeMillis;

    /** SOAK-1: tür → (alan adı → son zamanlanmış sonucun ağ-sınıfı DOWN bayrağı + zamanı). Tarama liderinin belleği. */
    private final Map<String, Map<String, OutcomeMark>> suppressionWindow = new ConcurrentHashMap<>();
    /** SOAK-1: tür → önceki zamanlanmış çağrının zamanı ve aralığı (pencere = max(3 × aralık, taban)). */
    private final Map<String, long[]> suppressionTick = new ConcurrentHashMap<>();

    private record OutcomeMark(boolean networkDown, long atMs) {}

    /** Penceredeki örnek: ağ-sınıfı DOWN alan adı sayısı / gözlenen alan adı sayısı. */
    record WindowSample(int networkDown, int total) {
        double rate() { return total == 0 ? 0 : (double) networkDown / total; }
    }

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

    /**
     * Teyit zinciri kimliği (2026-10-21, çok pod R1-B): "tip:domain:detail" → o anahtarı tutan zincirin tekil numarası.
     * {@link #inFlight} kaydı yalnız bu numarayı taşıyan zincir tarafından bırakılır — liderlik değişiminde harita
     * temizlenip aynı anahtarda yeni zincir başlarsa eski zincirin geç biten adımı yenisinin kaydını silemez.
     */
    private final ConcurrentHashMap<String, Long> chainOwner = new ConcurrentHashMap<>();

    /** Zincir numarası kaynağı — global, asla geri sarılmaz. */
    private final java.util.concurrent.atomic.AtomicLong chainSeq = new java.util.concurrent.atomic.AtomicLong();

    /** Teyit kuşağı: liderlik her değiştiğinde artar; eski kuşağın zincirleri bir sonraki adımlarında düşer. */
    private final java.util.concurrent.atomic.AtomicLong confirmEpoch = new java.util.concurrent.atomic.AtomicLong();

    /** Zincir kimliği bilinmeyen eski çağrı ({@link #runConfirmAttempt(String, SweepItem, String, List, int)}). */
    private static final long LEGACY_CHAIN = -1L;

    /** Değişiklik türü (DNS_CHANGED / DOMAINMON_CHANGED) alarm kilidi çakışınca son adımın yeniden deneme aralığı (ms). */
    static final long CHANGE_LOCK_RETRY_MS = 5_000L;
    /** …ve en çok deneme sayısı; sonra {@code notification_logs} izi bırakılır, satır PENDING kalır (sonraki tur yeniden). */
    static final int CHANGE_LOCK_RETRIES = 6;
    /** Test: bekleme süresi kısaltılabilir. */
    long changeLockRetryMs = CHANGE_LOCK_RETRY_MS;

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
        noteRecovery(key);   // 3-1 ikili yazım: zincir alanları (mutasyondan SONRA)
        return gen;
    }

    /**
     * İptal (kurtarma penceresinde DOWN) ya da bitiş: kayıt SİLİNİR. Kuyrukta bekleyen eski görev artık kendi
     * kuşağını doğrulayamaz ve düşer; bir sonraki düzelme turu yeni (farklı numaralı) zincir başlatabilir.
     */
    private void endRecovery(String key) {
        boolean changed = recoveryGeneration.remove(key) != null;
        changed |= recoveryStartedAt.remove(key) != null;
        if (changed) noteRecovery(key);   // 3-1 ikili yazım: yalnız gerçekten silindiyse (değişmeyen anahtar = işlem yok)
    }

    /** Bu görev hâlâ GEÇERLİ kuşağa mı ait? Hem girişte hem alarm kapatmadan önce sorulur. */
    private boolean isCurrentGeneration(String key, long gen) {
        Long cur = recoveryGeneration.get(key);
        return cur != null && cur == gen;
    }

    // ── Liderlik devri = yeniden başlatma (2026-10-21, çok pod R1-B, MP-12) ───────────────────────────────────────────

    /**
     * Liderlik olayı dinleyicisinin sırası: kalıcı değerlendirme durumu kaydedicisinden ({@code EvalStateRecorder},
     * {@code LOWEST_PRECEDENCE}) ÖNCE — bellek önce sıfırlanır, arada kuyruğa giren işlem sıfırlanmış değeri okur (R3-1
     * FX-STATE bulgu #4 / F6). Öteki dinleyici yalnız ölçü sayacıdır; karar sırası etkilenmez.
     */
    public static final int LEADERSHIP_LISTENER_ORDER = org.springframework.core.Ordered.LOWEST_PRECEDENCE - 1000;

    /**
     * Tarama liderliği bu pod'da değişti (alındı / kaybedildi / bırakıldı) → bellek içi alarm durumu SIFIRLANIR.
     *
     * <p><b>Neden.</b> Takipçi pod zamanlanmış sonuç işlemez (kapı), belleği donar. Liderlik geri dönünce eski sayaçlar
     * kullanılıyordu: A pod'u 2/3 sağlıklı saymışken lider B oldu, B saatlerce DOWN gördü, kira A'ya döndü → A'nın İLK
     * sağlıklı sonucu 3/3 yapıp alarmı kapatıyordu (dalgalanma + çift bildirim); bayat kardeş gözlemi kapanışı bekletiyor,
     * bayat "tur görüldü" işareti elle kapanış korumasını (D-6) devre dışı bırakıyordu. Yeni dönem tek pod'un yeniden
     * başlatılmasıyla aynı başlar. Hızlıdır (yalnız harita temizliği) — olay liderlik kilidi tutulurken eşzamanlı gelir.
     */
    @org.springframework.context.event.EventListener
    @org.springframework.core.annotation.Order(LEADERSHIP_LISTENER_ORDER)
    public void onSweepLeadershipChanged(com.sitemonitor.service.schedule.SweepLeadershipChangedEvent ev) {
        if (ev == null) return;
        // 3-2 (inceleme 2026-10-28, L-1): olay sırası ÖNCE artar, kapı sıfırlamadan ÖNCE sökülür — sökme süren bir uygulama
        // gövdesini (kapı izleyicisi) bekler; sıfırlama sonra gövdenin yazdığını siler ve uygulama leadership_changed
        // sayılır. Ayar kapalıyken kapı hiç kurulmadığından sökme işlem yapmaz, günlük sırası aynı (J-1).
        evalLeadershipSeq.incrementAndGet();
        disarmEvalLoad();
        resetVolatileState(ev.reason());
        onEvalLoadLeadership(ev);   // 3-2: GAINED → kapıyı kur (yalnız bellek; ayar kapalıyken kurulmaz)
    }

    /**
     * Restart semantiği: çalışan teyit zincirleri ({@link #confirmEpoch} artar, {@link #inFlight} temizlenir) ve kurtarma
     * zincirleri ({@link #recoveryGeneration} temizlenir — kuşak numaraları tekrarsız olduğundan eski görev doğrulayamaz)
     * bir sonraki adımlarında düşer; sayaçlar, kardeş gözlemleri, tur ve bastırma bayrakları silinir. Bastırma kaydı
     * DB'dedir: sonraki temiz turda bir kez uzlaştırılır ({@link #outageReconciled} da silindiği için).
     */
    void resetVolatileState(String reason) {
        confirmEpoch.incrementAndGet();
        int chains = inFlight.size();
        int recoveries = recoveryGeneration.size();
        inFlight.clear();
        chainOwner.clear();
        recoveryGeneration.clear();
        recoveryStartedAt.clear();
        recoveryUpCount.clear();
        recoveryLastCountedAt.clear();
        downObserved.clear();
        scheduledRoundSeen.clear();
        suppressionActive.clear();
        suppressionWindow.clear();
        suppressionTick.clear();
        outageReconciled.clear();
        log.info("Tarama liderliği değişti ({}) — bellek içi alarm durumu sıfırlandı ({} teyit, {} kurtarma zinciri durduruldu)",
                reason, chains, recoveries);
    }

    // ── Devirde kalıcı durumu okuma (2026-10-28, çok pod Sürüm 3-2 B; tasarım R3-2 §1, §3, §5.1) ─────────────────────────
    //
    // Yalnız bellek (J-2): kurulum / sökme liderlik dinleyicisinde, uygulama kaydedicinin yazıcı iş parçacığında (MOS
    // yalnız harita yazar). Ayar KAPALI: kapı hiç kurulmaz, giriş = bir sayaç artırma + bir volatile okuma, günlük yok (J-1).

    /** Liderlik dönemi (yükleme belirteci) kaynağı — her GAINED'de artar, asla geri sarılmaz. */
    private final java.util.concurrent.atomic.AtomicLong evalEpoch = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Liderlik olaylarının sırası — her olayın (GAINED / LOST / RELEASED) dinleyicisinin İLK adımında artar. Kapı kurulumdan
     * sonra değiştiyse yükü {@code leadership_changed} sayar (inceleme L-1: uygulama gövdesiyle yarışan LOST).
     */
    private final java.util.concurrent.atomic.AtomicLong evalLeadershipSeq = new java.util.concurrent.atomic.AtomicLong();

    /** Değerlendirme kapısı — saat = {@link #nowMs} (testler ilerletir); zaman aşımı raporu {@link #reportEnded}. */
    private final EvalLoadGate evalGate = new EvalLoadGate(() -> nowMs.getAsLong(), this::reportEnded,
            () -> evalLeadershipSeq.get());

    /** Okuma ayarı ({@link EvalStateReadSwitch}); null = KAPALI (elle kurulan servis / ayar servisi yok). */
    private volatile java.util.function.BooleanSupplier evalReadEnabled;

    /** Raporlardaki pod kimliği; null = {@link SchedulerService#instanceId()} (kaydedicinin yazar kimliği). */
    private volatile String evalLoadPodId;

    /** Son yükleme raporu (bu pod; null = açılıştan beri liderlik alınmadı) — {@link #lastEvalLoad()}. */
    private volatile EvalLoadReport lastEvalLoad;
    private final Object evalReportLock = new Object();
    /** Raporu yazılmış en büyük belirteç (eski belirtecin geç raporu yenisini ezmesin) — {@link #evalReportLock} altında. */
    private long evalReportedToken;

    /** Spring bağlaması: okuma ayarı her kararda canlı okunur (bellek; okuma hatası = kapalı). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvalReadSettingsProvider(org.springframework.beans.factory.ObjectProvider<AppSettingsService> provider) {
        this.evalReadEnabled = provider == null ? null : EvalStateReadSwitch.supplier(provider);
    }

    /**
     * Test / çok pod düzeneği kancası: okuma ayarı yerine verilen tedarikçi (null = KAPALI). Tedarikçi fırlatırsa kapalı
     * sayılır.
     */
    public void setEvalReadEnabled(java.util.function.BooleanSupplier enabled) {
        this.evalReadEnabled = enabled;
    }

    /** Test / çok pod düzeneği kancası: raporlardaki pod kimliği (null = {@link SchedulerService#instanceId()}). */
    public void setEvalLoadPodId(String podId) {
        this.evalLoadPodId = podId;
    }

    /** Değerlendirme kapısı (tanı / test). */
    EvalLoadGate evalLoadGate() {
        return evalGate;
    }

    /** Okuma ayarı şu an açık mı — tedarikçi yok / hata → false. */
    boolean evalReadEnabledNow() {
        java.util.function.BooleanSupplier s = evalReadEnabled;
        if (s == null) return false;
        try {
            return s.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String evalPod() {
        String id = evalLoadPodId;
        return id != null ? id : SchedulerService.instanceId();
    }

    /**
     * Her liderlik olayının başında, sıfırlamadan ({@link #resetVolatileState}) ÖNCE: kapı sökülür (LOADING'deki belirteç
     * {@code leadership_changed}; HANDOVER biter). Süren bir uygulama gövdesini bekler (kapı izleyicisi) — ardından gelen
     * sıfırlama gövdenin yazdığını siler (inceleme L-1). Yalnız bellek, asla fırlatmaz.
     */
    private void disarmEvalLoad() {
        try {
            reportEnded(evalGate.disarm());
        } catch (RuntimeException e) {
            log.debug("Devir kapısı sökülemedi: {}", e.toString());
        }
    }

    /**
     * GAINED: yeni belirteç; kurulum engeli yoksa kapı LOADING (20 sn son tarih), varsa kapı açık kalır ve
     * {@code none/<engel>} raporu yazılır. LOST / RELEASED: hiçbir şey (kapı {@link #disarmEvalLoad} ile zaten söküldü).
     * Sıfırlamadan ({@link #resetVolatileState}) SONRA çağrılır — bellek boş. Asla fırlatmaz.
     */
    private void onEvalLoadLeadership(com.sitemonitor.service.schedule.SweepLeadershipChangedEvent ev) {
        if (!com.sitemonitor.service.schedule.SweepLeadershipChangedEvent.GAINED.equals(ev.reason())) return;
        try {
            long token = evalEpoch.incrementAndGet();
            long now = nowMs.getAsLong();
            String blocker = evalLoadArmBlocker();
            if (blocker == null) {
                reportEnded(evalGate.arm(token, now + EvalLoadGate.LOAD_TIMEOUT_MS));
            } else {
                reportEnded(evalGate.disarm());
                reportEvalLoad(token, EvalLoadReport.none(now, evalPod(), blocker));
            }
        } catch (RuntimeException e) {
            // Beklenmez (yalnız bellek); kapı açık kalsın — değerlendirme asla takılmaz.
            evalGate.disarm();
            log.debug("Devir kapısı kurulamadı — yükleme yok (bugünkü davranış): {}", e.toString());
        }
    }

    /** Kurulum engeli: ayar kapalı → {@code setting_off}; kaydedici yok → {@code no_recorder}; sonra kaydedicinin engeli. */
    private String evalLoadArmBlocker() {
        if (!evalReadEnabledNow()) return EvalLoadReport.REASON_SETTING_OFF;
        EvalStateSink s = evalState;
        if (s == null) return EvalLoadReport.REASON_NO_RECORDER;
        try {
            return s.loadBlocker();
        } catch (RuntimeException e) {
            evalStateHookFailed("loadBlocker", e);
            return EvalLoadReport.REASON_NO_RECORDER;
        }
    }

    /** Kapının bitirdiği yükleme (liderlik değişti / zaman aşımı) → {@code none/<neden>} raporu. */
    private void reportEnded(EvalLoadGate.Ended e) {
        if (e == null) return;
        String why = e.reason() != null ? e.reason() : EvalLoadReport.REASON_LEADERSHIP_CHANGED;
        reportEvalLoad(e.token(), EvalLoadReport.none(nowMs.getAsLong(), evalPod(), why).withWaits(e.maxWaitMs(),
                e.waiters()));
    }

    /** Raporu yazar (eski belirteç yenisini ezmez) ve günlüğe bir satır bırakır ({@code setting_off} hariç). */
    private void reportEvalLoad(long token, EvalLoadReport r) {
        if (r == null) return;
        synchronized (evalReportLock) {
            if (token < evalReportedToken) return;
            evalReportedToken = token;
            lastEvalLoad = r;
        }
        logEvalLoad(r);
    }

    private static void logEvalLoad(EvalLoadReport r) {
        if (r.loaded()) {
            log.info("Kalıcı değerlendirme durumu devirde yüklendi ({}) — kuşak {} ({}), {} sn önce yazıldı: {} kurtarma "
                            + "sayacı, {} DOWN kardeş, {} bastırma, {} tür turu, {} pencere alan adı; okuma {} ms, {} "
                            + "değerlendirme en çok {} ms bekledi",
                    EvalLoadReport.OUTCOME_FULL.equals(r.outcome()) ? "tam" : "gözlemler", r.fromGeneration(), r.fromPod(),
                    r.durableAgeMs() == null ? "?" : r.durableAgeMs() / 1000, r.recoveryCounters(), r.siblings(),
                    r.suppressions(), r.roundSeen(), r.windowDomains(), r.readMs(), r.waiters(), r.maxWaitMs());
            return;
        }
        if (EvalLoadReport.REASON_SETTING_OFF.equals(r.reason())) return;   // J-1: ayar kapalıyken günlük yok
        String msg = "Kalıcı değerlendirme durumu devirde yüklenmedi ({}) — yeniden başlatma semantiği (bugünkü davranış)";
        if (EvalLoadReport.operational(r.reason())) log.warn(msg, r.reason());
        else log.info(msg, r.reason());
    }

    @Override
    public long evalLoadToken() {
        return evalGate.loadingToken();
    }

    /**
     * Yükü belleğe uygular (yazıcı iş parçacığı; tasarım §3.3): kapı hâlâ bu belirteçle LOADING değilse eski (rapor zaten
     * yazıldı); son tarih geçtiyse {@code timeout}; ayar kapandıysa {@code setting_turned_off}; uçuştaki değerlendirme
     * bitmezse {@code evaluation_busy}; bellek boş değilse {@code evaluated_before_load} — hiçbirinde birleştirme yok (J-3).
     */
    @Override
    public boolean applyEvalLoad(EvalLoad load) {
        if (load == null) return false;
        int[] put = new int[PUT_COUNTS];
        EvalLoadGate.ApplyOutcome out = evalGate.apply(load.token(), this::evalReadEnabledNow, () -> putEvalLoad(load, put));
        if (out.stale()) {
            log.debug("Kalıcı değerlendirme yükü geç geldi ({}) — belirteç {} artık güncel değil, uygulanmadı", out.reason(),
                    load.token());
            return false;
        }
        long now = nowMs.getAsLong();
        EvalLoadReport r = out.applied()
                ? withPutCounts(EvalLoadReport.applied(now, evalPod(), load), put)
                : EvalLoadReport.discarded(now, evalPod(), out.reason(), load);
        reportEvalLoad(load.token(), r.withWaits(out.maxWaitMs(), out.waiters()));
        return out.applied();
    }

    /** {@link #putEvalLoad} sayaçları: kurtarma, kardeş, bastırma, tur, pencere türü, pencere alan adı. */
    private static final int PUT_COUNTS = 6;

    /**
     * Uygulanan raporun sayıları belleğe GERÇEKTEN konanlar (inceleme 2026-10-28, C-1: MOS saatinde TTL'i aşmış kardeşler,
     * 0 sayaçlar konmaz; rapor ve günlük satırı fazla saymaz).
     */
    private static EvalLoadReport withPutCounts(EvalLoadReport r, int[] put) {
        return new EvalLoadReport(r.atMs(), r.pod(), r.outcome(), r.reason(), r.attemptedTier(), r.fromGeneration(),
                r.fromPod(), r.handoverAgeMs(), r.durableAgeMs(), put[0], put[1], put[2], put[3], put[4], put[5], r.readMs(),
                r.maxWaitMs(), r.waiters());
    }

    /**
     * Kapı izleyicisi altında, uçuşta değerlendirme yokken: bellek boş değilse atma nedeni; boşsa önce TÜM değerleri yerel
     * yapılara kurar, sonra yalnız harita yazar (yarım yük kalmaz; kanca / veritabanı yok). Katman kuralı (J-4): sayaçlar,
     * bastırma ve tur (D-6) YALNIZ FULL'da; kardeşler, pencere ve tur aralığı her iki katmanda. Konan sayılar {@code put}'a
     * yazılır ({@link #PUT_COUNTS}).
     */
    private String putEvalLoad(EvalLoad load, int[] put) {
        if (!evalMemoryEmpty()) return EvalLoadReport.REASON_EVALUATED_BEFORE_LOAD;
        boolean full = load.full();
        long now = nowMs.getAsLong();
        Map<String, Integer> up = new HashMap<>();
        Map<String, Long> lastCounted = new HashMap<>();
        if (full) {
            for (Map.Entry<String, EvalLoad.Recovery> e : load.recovery().entrySet()) {
                EvalLoad.Recovery r = e.getValue();
                if (r.upCount() <= 0) continue;
                up.put(e.getKey(), r.upCount());
                // L-4 (inceleme 2026-10-28): son sayım önceki sahibin saatinde — bizim "şimdi"mizden ileride olamaz (elle
                // kurtarma aralığı {@code now − last < aralık} saat farkıyla gevşemesin).
                if (r.lastCountedMs() != null) lastCounted.put(e.getKey(), Math.min(r.lastCountedMs(), now));
            }
        }
        Map<String, DownObservation> down = new HashMap<>();
        for (EvalLoad.Sibling s : load.siblings().values()) {
            if (s.alertType() == null || s.domain() == null || now - s.observedMs() > SIBLING_TTL_MS) continue;
            down.put(s.key(), new DownObservation(s.domain(), s.slimItem(), s.observedMs(), s.errorClass()));
        }
        Set<String> supp = full ? load.suppressionActive() : Set.of();
        Set<String> seen = full ? load.roundSeen() : Set.of();
        Map<String, long[]> ticks = new HashMap<>();
        load.intervals().forEach((t, iv) -> {
            if (iv > 0) ticks.put(t, new long[]{0L, iv});   // tick[0] = 0 → ilk tur aralığı korur (windowedOutageSample)
        });
        Map<String, Map<String, OutcomeMark>> window = new HashMap<>();
        load.window().forEach((t, marks) -> {
            Map<String, OutcomeMark> m = new ConcurrentHashMap<>();
            marks.forEach((d, mk) -> m.put(d, new OutcomeMark(mk.networkDown(), mk.atMs())));
            if (!m.isEmpty()) window.put(t, m);
        });
        recoveryUpCount.putAll(up);
        recoveryLastCountedAt.putAll(lastCounted);
        downObserved.putAll(down);
        for (String t : supp) suppressionActive.put(t, Boolean.TRUE);   // FALSE satırlar konmaz (yokluk ≡ false)
        scheduledRoundSeen.addAll(seen);
        suppressionTick.putAll(ticks);
        suppressionWindow.putAll(window);
        put[0] = up.size();
        put[1] = down.size();
        put[2] = supp.size();
        put[3] = seen.size();
        put[4] = window.size();
        int domains = 0;
        for (Map<String, OutcomeMark> m : window.values()) domains += m.size();
        put[5] = domains;
        return null;
    }

    /** Uygulama öncesi bellek boş mu (tasarım §3.3 adım 5) — değilse araya bir değerlendirme girmiştir. */
    private boolean evalMemoryEmpty() {
        return recoveryUpCount.isEmpty() && recoveryLastCountedAt.isEmpty() && recoveryGeneration.isEmpty()
                && recoveryStartedAt.isEmpty() && downObserved.isEmpty() && suppressionActive.isEmpty()
                && scheduledRoundSeen.isEmpty() && suppressionWindow.isEmpty() && suppressionTick.isEmpty()
                && outageReconciled.isEmpty() && inFlight.isEmpty() && chainOwner.isEmpty();
    }

    /** Kaydedici / yükleyici hiçbir şey yüklemeyecek — belirteç güncelse kapı açılır ve rapor yazılır; eskiyse yok sayılır. */
    @Override
    public void evalLoadSkipped(long token, EvalLoadReport report) {
        EvalLoadGate.Ended e = evalGate.skip(token);
        if (e == null) return;
        EvalLoadReport r = report != null ? report
                : EvalLoadReport.none(nowMs.getAsLong(), evalPod(), EvalLoadReport.REASON_READ_FAILED);
        reportEvalLoad(token, r.withWaits(e.maxWaitMs(), e.waiters()));
    }

    @Override
    public EvalLoadReport lastEvalLoad() {
        evalGate.expireIfDue();   // süresi dolmuş yükleme raporu anında görünsün
        return lastEvalLoad;
    }

    @Override
    public int replay() {
        return replayEvalState();
    }

    /** Temiz devir başlıyor (zarif kapanış): yeni girişler en çok {@link EvalLoadGate#HANDOVER_WAIT_MS} bekler. */
    @Override
    public void beginEvalHandover() {
        evalGate.beginHandover();
    }

    @Override
    public boolean awaitEvalQuiescent(long maxMs) {
        return evalGate.awaitQuiescent(maxMs);
    }

    @Override
    public void endEvalHandover() {
        evalGate.endHandover();
    }

    // ── R3-3 P0 (çok pod Sürüm 3-3, ortak sözleşme) ─────────────────────────────────────────────────────────────────

    /**
     * Toplu değerlendirme bileti (2026-10-29, çok pod Sürüm 3-3 P0, tasarım R3-3 §4.3) = değerlendirme kapısının GİRİŞİ
     * ({@link EvalLoadGate#enter()}): dağıtık kontrol sonuçlarının boşaltıcısı (paket C) bir alma + uygulama turunu bu
     * biletle sarar — {@code try (var t = mos.evalBatchTicket()) { take; apply; }}.
     *
     * <ul>
     *   <li>Kapı NONE: sayar, hemen döner (bir sayaç artırma + bir volatile okuma).</li>
     *   <li>Kapı LOADING (3-2 devir yüklemesi): yükleme bitene / son tarihe dek BEKLER — yükleme yarım belleğe karar
     *       verdirilmez (J-3). HANDOVER: en çok {@link EvalLoadGate#HANDOVER_WAIT_MS} bekler.</li>
     *   <li>Sayılır: devirde {@code awaitEvalQuiescent} bu biletin kapanmasını bekler (bilet açıkken durulma false); yükleme
     *       uygulaması bilet açıkken boş olmayan belleğe uygulamaz.</li>
     *   <li>YENİDEN GİRİLEBİLİR: bilet açıkken aynı iş parçacığındaki {@code handleSweepResults} / {@code handleDnsSweep}
     *       yeniden beklemez, yeniden sayılmaz.</li>
     * </ul>
     * Bileti mutlaka kapatın (try-with-resources); {@code close()} asla fırlatmaz. Bilet karar VERMEZ — liderlik
     * ({@code isSweepLeaderNow()}) ve zamanlanmış sonuç çiti ayrıca geçerlidir.
     */
    public EvalLoadGate.Ticket evalBatchTicket() {
        return evalGate.enter();
    }
    // ── /R3-3 P0 ─────────────────────────────────────────────────────────────────────────────────────────────────────

    // ── R3-3 B-MOS (çok pod Sürüm 3-3, paket B; tasarım R3-3 §1.3 / risk R-8) — klasik tur kadansı ipucu ─────────────────
    // Dağıtık kipte zamanlanmış sonuçlar boşaltıcı dalgalarıyla (~2 sn arayla) gelir; ölçülen tur aralığı klasik
    // {@code @Scheduled} tıkının çok altına düşer, pencere tabana iner (DNS: 900 sn → 300 sn) ve kaydedilen tur aralığı oynar.
    // Değerlendirici her zamanlanmış çağrıdan önce türün KLASİK tıkını bildirir; ölçülen aralık max(ölçülen, ipucu) olur —
    // pencere ve kaydedilen aralık klasiktekiyle aynı kalır. İpucu YOKSA (anahtar kapalı — kimse bildirmez) davranış birebir
    // 20.169.0. İpucu ölçülmüş bir boşluğu ASLA kısaltmaz; ilk çağrıda (ölçüm yok) uygulanmaz (klasikte de pencere tabandır).
    // Liderlik değişiminde (resetVolatileState) SİLİNMEZ: gözlem değil yapılandırmadır (bu pod'un tık ayarı); değerlendirici
    // zaten her çağrıdan önce yeniden bildirir, klasik kipe dönen pod'da da zararsızdır (klasik boşluk ≥ tık).

    /** Alarm türü → klasik zamanlanmış taramanın tık aralığı (ms). Anahtar kapalıyken BOŞ. */
    private final Map<String, Long> roundCadenceHints = new ConcurrentHashMap<>();

    /**
     * Türün klasik tarama tıkını bildirir (paket B değerlendiricisi, dağıtık kipte her zamanlanmış çağrıdan önce). Geçersiz
     * değer (tür yok, tık ≤ 0) yok sayılır. Yalnız bellek.
     */
    public void noteRoundCadence(String alertType, long classicTickMs) {
        if (alertType == null || classicTickMs <= 0) return;
        roundCadenceHints.put(alertType, classicTickMs);
    }

    /** Ölçülen tur boşluğu, varsa klasik tık ipucuyla alttan sınırlanmış: {@code max(boşluk, ipucu)}; ipucu yoksa boşluğun kendisi. */
    private long cadenceFloored(String alertType, long measuredGapMs) {
        Long hint = alertType == null ? null : roundCadenceHints.get(alertType);
        return hint == null ? measuredGapMs : Math.max(measuredGapMs, hint);
    }
    // ── /R3-3 B-MOS ──────────────────────────────────────────────────────────────────────────────────────────────────

    // ── Kalıcı değerlendirme durumu: ikili yazım kancaları (2026-10-23, çok pod Sürüm 3-1 A2, tasarım R3-1 §2.5) ───────
    //
    // Sıfırlama (resetVolatileState) kanca ÇAĞIRMAZ: kaydedici aynı liderlik olayını dinler (LOST / RELEASED → bekleyenleri
    // atar, kuşağı unutur; GAINED → yeni kuşak). Kancalar bellek mutasyonundan SONRA çağrılır: doğrulayıcı "anahtar s0'dan
    // sonra kuyruğa alındı mı" sorusuyla yarışan anahtarları atlar — kanca mutasyondan önce olsaydı görüntü eski değeri,
    // tablo yeniyi taşıyıp sahte uyuşmazlık doğabilirdi.

    /**
     * Kalıcı değerlendirme durumu kaydedicisi — yalnız AYNA (hiçbir karar okumaz, değişmez I-1). null = kaydedici yok
     * (bugünkü davranış; elle kurulan testler). Kancalar yalnız kuyruğa alır (I-2) ve istisnayı yutar.
     */
    private volatile EvalStateSink evalState;

    /** Spring bağlaması için tembel sağlayıcı — bkz. {@link #bindEvalStateSink()}. */
    private volatile org.springframework.beans.factory.ObjectProvider<EvalStateSink> evalStateSinkProvider;

    /** Kanca istisnası sayacı (tanı / test) — karar etkilenmez. */
    private final java.util.concurrent.atomic.AtomicLong evalStateHookFailures = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong evalStateHookLogAt = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Kaydediciyi bağlar ve bu servisi güncel değerlerin kaynağı yapar ({@link EvalStateSink#bindSource}). null = kaydedici
     * yok. {@code bindSource} hata verse de bağlama kalır (kancalar zaten her çağrıyı yalıtır) — açılış asla düşmez.
     */
    public void setEvalStateSink(EvalStateSink s) {
        evalState = s;
        if (s == null) return;
        try {
            s.bindSource(this);
        } catch (Exception e) {
            evalStateHookFailed("bindSource", e);
        }
    }

    /** Bağlı kaydedici (tanı / test); null = yok. */
    EvalStateSink evalStateSink() {
        return evalState;
    }

    /** Kanca istisnası sayısı (birikimli; tanı / test). */
    long evalStateHookFailures() {
        return evalStateHookFailures.get();
    }

    /**
     * Spring bağlaması DÖNGÜSÜZ: kaydedici {@code SchedulerService}'e (lider ipucu), {@code SchedulerService} kurucusu bu
     * servise bağlıdır — ayarlayıcıyla doğrudan kaydedici istemek "oluşturulmakta olan bean" döngüsü kurardı (Spring Boot
     * döngüsel referansa izin vermez). Sağlayıcı burada yalnız saklanır; kaydedici bağlam yenilendiğinde (tüm tekil
     * bean'ler hazır) çözülür. Zamanlanmış taramalar zaten o andan sonra başlar.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvalStateSinkProvider(org.springframework.beans.factory.ObjectProvider<EvalStateSink> provider) {
        this.evalStateSinkProvider = provider;
    }

    /** Bağlam yenilendi → kaydedici (varsa, tekse) bağlanır. İdempotent; hata açılışı düşürmez (kaydedicisiz = bugün). */
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextRefreshedEvent.class)
    public void bindEvalStateSink() {
        if (evalState != null) return;
        org.springframework.beans.factory.ObjectProvider<EvalStateSink> p = evalStateSinkProvider;
        if (p == null) return;
        try {
            EvalStateSink s = p.getIfUnique();
            if (s != null) setEvalStateSink(s);
        } catch (Exception e) {
            log.warn("Kalıcı değerlendirme durumu kaydedicisi bağlanamadı — ikili yazım kapalı, kararlar etkilenmez: {}",
                    e.toString());
        }
    }

    private void evalStateHookFailed(String hook, Exception e) {
        evalStateHookFailures.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = evalStateHookLogAt.get();
        if (now - last >= 60_000L && evalStateHookLogAt.compareAndSet(last, now)) {
            log.debug("Kalıcı değerlendirme durumu kancası hata verdi ({}) — karar etkilenmez (bellek kaynak): {}", hook,
                    e.toString());
        }
    }

    /** Kurtarma anahtarı ({@code "tür:alan adı"}) değişti — kaydedici güncel değeri {@link #recoveryValues} ile okur. */
    private void noteRecovery(String rkey) {
        EvalStateSink s = evalState;
        if (s == null || rkey == null) return;
        try {
            int i = rkey.indexOf(':');   // alarm türü ':' içermez; alan adı içerebilir (IPv6 / host:port)
            if (i <= 0) return;
            s.recovery(rkey, rkey.substring(0, i), rkey.substring(i + 1));
        } catch (Exception e) {
            evalStateHookFailed("recovery", e);
        }
    }

    /**
     * Kalemin son gözlemi ({@code monitor_last_status}). Kaydediciye İNCE kopya gider (recheck lambdası / izleme varlığı
     * tutulmaz — D-5 ile aynı gerekçe; bekleyen işlem DB kesintisinde birikebilir).
     */
    private void noteObservation(String alertType, SweepItem item, boolean manual, long atMs) {
        EvalStateSink s = evalState;
        if (s == null || alertType == null || item == null || item.domain() == null) return;
        try {
            s.observation(alertType, observationCopy(item), manual, atMs);
        } catch (Exception e) {
            evalStateHookFailed("observation", e);
        }
    }

    /**
     * Bellek bir kalemin DOWN gözlemini YENİ sonuç olmadan budadı (D-5: TTL / artık izlenmiyor) — tablodaki satır da silinsin
     * (R3-1 FX-STATE F2): duraklatılıp yeniden açılan izleme, yeniden gözlenene dek bellekte olmayan DOWN kardeş satırı
     * bırakmasın.
     */
    private void noteObservationPruned(String key, DownObservation o) {
        EvalStateSink s = evalState;
        if (s == null || key == null || o == null || o.item() == null) return;
        try {
            int bar = key.lastIndexOf('|');
            s.observationPruned(bar > 0 ? key.substring(0, bar) : o.item().alertType(), o.item());
        } catch (Exception e) {
            evalStateHookFailed("observationPruned", e);
        }
    }

    /** Türün bayrakları değişti — kaydedici güncel değeri {@link #typeValues} ile okur. */
    private void noteTypeState(String alertType) {
        EvalStateSink s = evalState;
        if (s == null || alertType == null) return;
        try {
            s.typeState(alertType);
        } catch (Exception e) {
            evalStateHookFailed("typeState", e);
        }
    }

    private void noteChainStart(String key, long chainId, SweepItem item, ConfirmState state, long delayMs) {
        EvalStateSink s = evalState;
        if (s == null) return;
        try {
            s.chainStart(key, chainId, item, state, state.startedAt(), delayMs);
        } catch (Exception e) {
            evalStateHookFailed("chainStart", e);
        }
    }

    private void noteChainProgress(String key, long chainId, ConfirmState state, List<Map<String, Object>> attempts) {
        EvalStateSink s = evalState;
        if (s == null || state == null) return;
        try {
            s.chainProgress(key, chainId, state, attempts == null ? List.of() : List.copyOf(attempts));
        } catch (Exception e) {
            evalStateHookFailed("chainProgress", e);
        }
    }

    private void noteChainFinishing(String key, long chainId, int lockTry) {
        EvalStateSink s = evalState;
        if (s == null) return;
        try {
            s.chainFinishing(key, chainId, lockTry);
        } catch (Exception e) {
            evalStateHookFailed("chainFinishing", e);
        }
    }

    private void noteChainEnd(String key, long chainId) {
        EvalStateSink s = evalState;
        if (s == null) return;
        try {
            s.chainEnd(key, chainId);
        } catch (Exception e) {
            evalStateHookFailed("chainEnd", e);
        }
    }

    private void noteSweepEnd(String alertType) {
        EvalStateSink s = evalState;
        if (s == null || alertType == null) return;
        try {
            s.sweepEnd(alertType);
        } catch (Exception e) {
            evalStateHookFailed("sweepEnd", e);
        }
    }

    /** Gözlem kancasının ince kopyası: tür, alan adı, detay, durum, hata + sahiplik bağlamı (recheck yok). */
    private static SweepItem observationCopy(SweepItem it) {
        Map<String, Object> ctx = new HashMap<>();
        for (String k : List.of("monitor_id", "team_id", "standalone")) {
            Object v = it.ctxExtra() == null ? null : it.ctxExtra().get(k);
            if (v != null) ctx.put(k, v);
        }
        return new SweepItem(it.alertType(), it.domain(), it.detail(), it.up(), it.error(), ctx, null);
    }

    // ── EvalStateSource: güncel bellek değerlerinin dar okuma yüzü (kaydedici seq ayırdıktan SONRA okur) ──────────────

    @Override
    public RecoveryValues recoveryValues(String rkey) {
        if (rkey == null) return RecoveryValues.NONE;
        Integer up = recoveryUpCount.get(rkey);
        Long last = recoveryLastCountedAt.get(rkey);
        Long gen = recoveryGeneration.get(rkey);
        Long started = recoveryStartedAt.get(rkey);
        if (up == null && last == null && gen == null && started == null) return RecoveryValues.NONE;
        return new RecoveryValues(up == null ? 0 : up, last, gen, started);
    }

    @Override
    public TypeValues typeValues(String alertType) {
        if (alertType == null) return TypeValues.NONE;
        Boolean active = suppressionActive.get(alertType);
        boolean seen = scheduledRoundSeen.contains(alertType);
        Long interval = null;
        long[] tick = suppressionTick.get(alertType);
        if (tick != null) {
            synchronized (tick) {
                if (tick[1] > 0) interval = tick[1];
            }
        }
        if (active == null && !seen && interval == null) return TypeValues.NONE;
        return new TypeValues(active, seen, interval);
    }

    @Override
    public String errorClass(String error, String domain) {
        return classifyError(error, domain);
    }

    @Override
    public DownValues downObservation(String alertType, long monitorId) {
        if (alertType == null) return DownValues.ABSENT;
        DownObservation o = downObserved.get(alertType + "|" + monitorId);
        if (o == null || o.item() == null) return DownValues.ABSENT;
        Map<String, Object> ctx = o.item().ctxExtra();
        return new DownValues(true, o.domain(), o.item().detail(),
                ctx != null && ctx.get("team_id") instanceof Number n ? n.longValue() : null,
                ctx != null && ctx.get("standalone") instanceof Boolean b ? b : null,
                classOf(o), o.atMs());
    }

    @Override
    public long hookFailures() {
        return evalStateHookFailures.get();
    }

    // ── Doğrulama (VERIFY) için bellek görüntüsü — karar yolu okumaz ─────────────────────────────────────────────────

    /**
     * Bellek durumunun DEĞİŞMEZ kopyası (kaydedicinin yazıcı iş parçacığında, {@code EvalStateVerifier} çağırır): kurtarma
     * haritaları, kardeş DOWN gözlemleri (ince — ham hata metni yerine sınıfı), bastırma ve tur bayrakları, teyit zincirleri
     * ve tür başına pencere örneği ({@link #peekWindowSample} — pencere işaretlerini ve tur saatini YAZMAZ). Kilitsiz
     * kopya (eşzamanlı haritalar); doğrulayıcı görüntü sırasında değişen anahtarları sıra numarasıyla atlar.
     */
    public EvalSnapshot verifySnapshot() {
        long at = nowMs.getAsLong();
        Map<String, EvalSnapshot.Down> down = new LinkedHashMap<>();
        for (Map.Entry<String, DownObservation> e : downObserved.entrySet()) {
            DownObservation o = e.getValue();
            if (o == null || o.item() == null) continue;
            String k = e.getKey();
            int bar = k.lastIndexOf('|');
            String type = bar > 0 ? k.substring(0, bar) : o.item().alertType();
            Map<String, Object> ctx = o.item().ctxExtra();
            down.put(k, new EvalSnapshot.Down(type, o.domain(), o.item().detail(), monitorIdOf(o.item()),
                    ctx != null && ctx.get("team_id") instanceof Number n ? n.longValue() : null,
                    ctx != null && ctx.get("standalone") instanceof Boolean b ? b : null,
                    classOf(o), o.atMs()));
        }
        Map<String, EvalSnapshot.Window> windows = new LinkedHashMap<>();
        Set<String> types = new TreeSet<>(suppressionWindow.keySet());
        types.addAll(suppressionTick.keySet());
        for (String t : types) {
            WindowSample ws = peekWindowSample(t);
            windows.put(t, new EvalSnapshot.Window(ws.networkDown(), ws.total(), windowLengthMs(t)));
        }
        return new EvalSnapshot(at, recoveryUpCount, recoveryLastCountedAt, recoveryGeneration, recoveryStartedAt, down,
                suppressionActive, scheduledRoundSeen, inFlight, chainOwner, windows);
    }

    /**
     * {@link #windowedOutageSample} ile AYNI aritmetik ama YAZMADAN: türün mevcut işaretlerinden, son bilinen tur
     * aralığıyla hesaplanan pencere içindekileri sayar (işaret eklemez / budamaz, tur saatini ilerletmez).
     */
    WindowSample peekWindowSample(String alertType) {
        Map<String, OutcomeMark> marks = alertType == null ? null : suppressionWindow.get(alertType);
        if (marks == null || marks.isEmpty()) return new WindowSample(0, 0);
        long cutoff = suppressionClock.getAsLong() - windowLengthMs(alertType);
        int down = 0, total = 0;
        for (OutcomeMark m : marks.values()) {
            if (m.atMs() < cutoff) continue;
            total++;
            if (m.networkDown()) down++;
        }
        return new WindowSample(down, total);
    }

    /** Türün güncel pencere uzunluğu: {@code max(3 × son tur aralığı, taban)}, en çok {@link #SUPPRESSION_WINDOW_CAP_MS}. */
    long windowLengthMs(String alertType) {
        long interval = 0L;
        long[] tick = alertType == null ? null : suppressionTick.get(alertType);
        if (tick != null) {
            synchronized (tick) {
                interval = tick[1];
            }
        }
        return windowLengthFor(interval);
    }

    /**
     * Pencere uzunluğu formülü (tek kaynak): {@code min(SUPPRESSION_WINDOW_CAP_MS, max(taban, 3 × aralık))}; aralık ≤ 0
     * (bilinmiyor) → taban.
     */
    private long windowLengthFor(long intervalMs) {
        long interval = Math.min(Math.max(0L, intervalMs), SUPPRESSION_WINDOW_CAP_MS);   // 3 × aralık taşmasın
        return Math.min(SUPPRESSION_WINDOW_CAP_MS, Math.max(Math.max(0L, suppressionWindowFloorMs), 3L * interval));
    }

    /**
     * Yükleyicinin pencere tohumu için türün pencere uzunluğu, kaydedilmiş tur aralığından (inceleme 2026-10-28, kaydedici
     * M-2: tohum VERIFY gibi tür başına pencereyle) — MOS'un kendi formülü ve tabanı ({@code site.monitor.network
     * .suppression-window-ms}); aynı aralıkla {@link #windowLengthMs} ile birebir aynı. Yalnız bellek.
     */
    @Override
    public long windowLengthMsFor(String alertType, long roundIntervalMs) {
        return windowLengthFor(roundIntervalMs);
    }

    /**
     * Verilen pencere örneği için bastırma kararı — {@link #handleSweepResults} ile AYNI kural ({@link #suppressDecision}),
     * türün GÜNCEL histerezis durumuyla. Yalnız okur (doğrulayıcının pencere tohumu ölçümü; karar yolu kullanmaz).
     */
    public boolean wouldSuppress(String alertType, int networkDown, int total) {
        boolean active = alertType != null && Boolean.TRUE.equals(suppressionActive.get(alertType));
        return suppressDecision(active, networkDown, total == 0 ? 0 : (double) networkDown / total);
    }

    /**
     * Bellekteki tüm durumu kaydediciye YENİDEN kuyruğa alır (doğrulayıcının "eşitleme" turu: yeni kuşak ya da uzun boşluk —
     * kip kapalıyken kaydedici toplu düşüş yapar ve o ana kadarki anahtarları doğrulamada atlar; yeniden kuyruğa alınan
     * anahtar yine karşılaştırılabilir olur). Kurtarma anahtarları, tür bayrakları ve kardeş DOWN gözlemleri (kendi gözlem
     * anlarıyla). Bellekte OLMAYAN anahtar oynatılmaz: kaydedicinin "son yazılan" belleği düşüşte silindiği için "yok"
     * değeri no-op sayılıp yazılmaz, ama anahtar karşılaştırılabilir olurdu — bayat satır sahte uyuşmazlık verirdi; oynatılmayan
     * anahtar düşüş kuralıyla atlanır. Teyit zincirleri de OYNATILMAZ (kalem bağlamı zincirin kapanışındadır; eksik
     * betimleyici satırı bozardı) — kısa ömürlüdürler. Yalnız bellek; asla fırlatmaz.
     *
     * @return kuyruğa alınan işlem sayısı (kaydedici yoksa 0)
     */
    public int replayEvalState() {
        if (evalState == null) return 0;
        int n = 0;
        Set<String> rkeys = new LinkedHashSet<>();
        rkeys.addAll(recoveryUpCount.keySet());
        rkeys.addAll(recoveryLastCountedAt.keySet());
        rkeys.addAll(recoveryGeneration.keySet());
        rkeys.addAll(recoveryStartedAt.keySet());
        for (String k : rkeys) {
            noteRecovery(k);
            n++;
        }
        Set<String> types = new LinkedHashSet<>(suppressionActive.keySet());
        types.addAll(scheduledRoundSeen);
        types.addAll(suppressionTick.keySet());
        for (String t : types) {
            noteTypeState(t);
            n++;
        }
        for (Map.Entry<String, DownObservation> e : downObserved.entrySet()) {
            DownObservation o = e.getValue();
            if (o == null || o.item() == null) continue;
            String k = e.getKey();
            int bar = k.lastIndexOf('|');
            noteObservation(bar > 0 ? k.substring(0, bar) : o.item().alertType(), o.item(), false, o.atMs());
            n++;
        }
        return n;
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
        String c = classifyError(error, domain);
        return ERROR_NETWORK.equals(c) || ERROR_DNS.equals(c);
    }

    /**
     * Hata metninin sınıfı — {@link #isOutageClass}'ın TEK kaynağı (2026-10-23, çok pod Sürüm 3-1: kalıcı durumun
     * {@code error_class} / {@code network_down} sütunları ve 3-2'nin pencere tohumu aynı sınıflandırmayı kullansın).
     * Sıra eskisiyle aynı: boş → null; yapılandırma / politika işareti EZER → CONFIG; hata metni = çıplak hedef → DNS
     * (UnknownHost); DNS işaretleri → DNS; taşıma katmanı → NETWORK; tanınmayan → OTHER.
     */
    static String classifyError(String error, String domain) {
        if (error == null || error.isBlank()) return null;
        // Veritabanı sorgu izlemesi (2026-10-31): öğe hatası "[DB_KOD] metin" — sınıf sürücü metninden değil KODDAN gelir
        // (bağlantı / zaman aşımı → ağ, DNS → DNS; kimlik / SQL / güvenlik reddi → yapılandırma, toplu kesinti sayılmaz).
        String dbClass = com.sitemonitor.service.dbquery.DbQueryAlarmText.outageClassOf(
                com.sitemonitor.service.dbquery.DbQueryAlarmText.codeOf(error));
        if (dbClass != null) return dbClass;
        String e = error.toLowerCase(Locale.ROOT);
        for (String m : CONFIG_ERROR_MARKS) if (e.contains(m)) return ERROR_CONFIG;   // yapılandırma EZER
        if (domain != null && !domain.isBlank() && e.trim().equals(domain.trim().toLowerCase(Locale.ROOT))) {
            return ERROR_DNS;                                                          // çıplak host = UnknownHost
        }
        for (String m : DNS_ERROR_MARKS)     if (e.contains(m)) return ERROR_DNS;
        for (String m : NETWORK_ERROR_MARKS) if (e.contains(m)) return ERROR_NETWORK;
        return ERROR_OTHER;
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
        if (!wasActive) noteTypeState(alertType);   // 3-1 ikili yazım: yalnız geçişte
        if (wasActive) {
            log.debug("{} sweep: bastırma sürüyor ({}/{} ağ-sınıfı DOWN)", alertType, networkDown, totalDomains);
            return;
        }
        log.warn("⚠ {} sweep: {}/{} domain AĞ-SINIFI hatayla DOWN (oran={}) — ağ kesintisi şüphesi, "
                + "alarmlar bastırılıyor", alertType, networkDown, totalDomains, String.format("%.2f", rate));
        try {
            // Çok pod (2026-10-21, R1-B, MP-16): kaynak başına TEK açık (ONGOING) kayıt. Bellekteki "geçiş" bayrağı pod
            // başınadır — liderlik devrinde yeni lider aynı kesintiye yeniden "girer"; önceki liderin açtığı kayıt sürüyorsa
            // ikincisi YAZILMAZ (eskiden iki satır doğuyor, biri sonsuza dek "sürüyor" kalıyordu). PostgreSQL'de aynı anda
            // iki ekleme yarışını kısmi tekil dizin keser (ux_noe_one_ongoing) → ihlal = "zaten açık".
            if (networkOutageRepo.findFirstBySourceAndStatusOrderByIdDesc(alertType, "ONGOING").isPresent()) {
                log.info("{} bastırma kaydı zaten açık (önceki liderden) — ikinci kayıt yazılmadı", alertType);
                return;
            }
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
            if (StormService.isUniqueViolation(e)
                    || e instanceof org.springframework.dao.DataIntegrityViolationException) {
                log.info("{} bastırma kaydı başka pod'da eşzamanlı açıldı — ikinci kayıt yazılmadı", alertType);
                return;
            }
            log.warn("{} bastırma olayı kaydedilemedi: {}", alertType, e.getMessage());
        }
    }

    /** Bir turda kapatılan en çok açık bastırma kaydı (bozuk veri / sahte döngüye tavan). */
    static final int MAX_ONGOING_RESOLVE = 20;

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
        if (wasActive) noteTypeState(alertType);   // 3-1 ikili yazım: yalnız geçişte (null → false değişiklik sayılmaz)
        if (!wasActive && !outageReconciled.add(alertType)) return;   // zaten kapalıydı ve DB bir kez uzlaştırıldı
        if (wasActive) {
            log.info("{} sweep: ağ kesintisi şüphesi kalktı — alarm işleme normale döndü", alertType);
        }
        // Çok pod (2026-10-21, R1-B, MP-16): kaynağın AÇIK kayıtlarının HEPSİ kapatılır — liderlik devrinde (ya da bu
        // düzeltmeden önce) iki pod iki kayıt açmış olabilir; eskiden yalnız en yenisi kapanıyor, diğeri sonsuza dek
        // "sürüyor" görünüyordu. Kayıt kimliği tekrar görülürse ya da tavan aşılırsa döngü durur (sonsuz döngü yok).
        try {
            java.util.Set<Long> seen = new java.util.HashSet<>();
            for (int i = 0; i < MAX_ONGOING_RESOLVE; i++) {
                com.sitemonitor.model.NetworkOutageEvent ev =
                        networkOutageRepo.findFirstBySourceAndStatusOrderByIdDesc(alertType, "ONGOING").orElse(null);
                if (ev == null || !"ONGOING".equals(ev.getStatus())) break;
                if (ev.getId() != null && !seen.add(ev.getId())) break;
                String now = ISO.format(Instant.now());
                ev.setResolvedAt(now);
                ev.setStatus("RESOLVED");
                try {
                    ev.setDurationMs(Instant.from(ISO.parse(now)).toEpochMilli()
                                   - Instant.from(ISO.parse(ev.getDetectedAt())).toEpochMilli());
                } catch (Exception ignored) { /* süre null kalır */ }
                networkOutageRepo.save(ev);
                if (!wasActive) {
                    log.info("{} sweep: yeniden başlatma / liderlik devri öncesinden açık kalan ağ kesintisi kaydı kapatıldı "
                            + "(id={}, tespit={})", alertType, ev.getId(), ev.getDetectedAt());
                }
                if (ev.getId() == null) break;   // kimliksiz (kaydedilmemiş) nesne — tekrar görülse ayırt edilemez
            }
        } catch (Exception e) {
            log.warn("{} bastırma olayı kapatılamadı: {}", alertType, e.getMessage());
        }
    }

    /**
     * SOAK-1: bu turun alan adı sonuçlarını türün penceresine yazar, pencere dışını budar ve örneği döner. Pencere =
     * {@code max(3 × son tur aralığı, taban)} ({@link #SUPPRESSION_WINDOW_CAP_MS} ile sınırlı). Aynı alan adının YENİ sonucu
     * eskisinin yerini alır; tur aralığı ilk çağrıda bilinmez (taban). Harita türün alan adı sayısıyla sınırlıdır.
     */
    WindowSample windowedOutageSample(String alertType, Map<String, List<SweepItem>> byDomain) {
        long now = suppressionClock.getAsLong();
        long[] tick = suppressionTick.computeIfAbsent(alertType, k -> new long[]{0L, 0L});
        long window;
        long prevInterval;
        long newInterval;
        synchronized (tick) {
            prevInterval = tick[1];
            if (tick[0] > 0 && now > tick[0]) tick[1] = cadenceFloored(alertType, now - tick[0]);   // R3-3 B-MOS: ipucu yoksa = boşluk
            tick[0] = now;
            newInterval = tick[1];
            window = windowLengthFor(tick[1]);
        }
        // 3-1 ikili yazım: tur aralığı (pencere uzunluğu girdisi) yalnız %10'dan fazla değişince kuyruğa alınır.
        if (intervalChanged(prevInterval, newInterval)) noteTypeState(alertType);
        Map<String, OutcomeMark> marks = suppressionWindow.computeIfAbsent(alertType, k -> new ConcurrentHashMap<>());
        for (Map.Entry<String, List<SweepItem>> e : byDomain.entrySet()) {
            if (e.getKey() == null) continue;
            boolean netDown = e.getValue().stream().anyMatch(it -> !it.up() && isOutageClass(it.error(), e.getKey()));
            marks.put(e.getKey(), new OutcomeMark(netDown, now));
        }
        long cutoff = now - window;
        int down = 0, total = 0;
        for (Iterator<Map.Entry<String, OutcomeMark>> it = marks.entrySet().iterator(); it.hasNext(); ) {
            OutcomeMark m = it.next().getValue();
            if (m.atMs() < cutoff) { it.remove(); continue; }
            total++;
            if (m.networkDown()) down++;
        }
        return new WindowSample(down, total);
    }

    /**
     * Bastırma kararı (SOAK-1 histerezisi) — TEK kaynak: tarama turu ({@link #handleSweepResults}) ve doğrulayıcının pencere
     * tohumu ölçümü ({@link #wouldSuppress}). Girme: ≥ {@code bulkMinErrors} ağ-sınıfı DOWN ve oran ≥ eşik; sürme (açıkken):
     * ≥ 1 ağ-sınıfı DOWN ve oran ≥ eşik.
     */
    private boolean suppressDecision(boolean active, int networkDown, double rate) {
        return active
                ? networkDown > 0 && rate >= bulkRateThreshold
                : networkDown >= bulkMinErrors && rate >= bulkRateThreshold;
    }

    /** Tur aralığı kayda değer değişti mi (ilk ölçüm ya da %10'dan fazla) — ikili yazımın tür satırı için. */
    static boolean intervalChanged(long prev, long now) {
        if (now <= 0) return false;
        if (prev <= 0) return true;
        return Math.abs(now - prev) * 10 > prev;
    }

    /** Ağ kesintisi şüphesi bu tür için şu an AÇIK mı (pencere kararı — teyit zincirinin son adımı da sorar). */
    boolean outageSuppressed(String alertType) {
        return alertType != null && Boolean.TRUE.equals(suppressionActive.get(alertType));
    }

    /** Uptime/Port/DNS-failure sweep'leri her tur sonunda bir kez çağırır. */
    public void handleSweepResults(String alertType, List<SweepItem> items) {
        handleSweepResults(alertType, items, false);
    }

    /**
     * "Zamanlanmış sonuç yalnız tarama liderinden" kapısı (2026-10-09). {@code SchedulerService} açılışta takar; çok pod'da
     * kirayı (tarama liderliği) kaybeden pod'un yarıda kalmış taramasının sonucu alarm durumuna YAZILMAZ — yeni lider
     * aynı izlemeleri kendi turunda yoklar. Elle kontrol ({@code manual=true}) kapıdan geçmez (bugünkü davranış). Tek pod'da
     * ya da kapı takılmamışsa her şey eskisi gibi. Kapı HATA verirse KAPALI (2026-10-21, çok pod R1-B, MP-11): liderliği
     * doğrulanamayan pod alarm durumuna yazmaz — açık kapı, bağlantı havuzu tükenmiş bir takipçiyi ikinci lider yapıyordu.
     */
    private volatile java.util.function.BooleanSupplier scheduledResultsFence;
    private final java.util.concurrent.atomic.AtomicLong fenceLogAt = new java.util.concurrent.atomic.AtomicLong();

    public void setScheduledResultsFence(java.util.function.BooleanSupplier fence) {
        this.scheduledResultsFence = fence;
    }

    /** Zamanlanmış sonuç işlenmeli mi (kapı yoksa evet; kapı hata verirse HAYIR). */
    private boolean scheduledResultsAllowed(String alertType, int count) {
        java.util.function.BooleanSupplier f = scheduledResultsFence;
        if (f == null) return true;
        boolean ok;
        try {
            ok = f.getAsBoolean();
        } catch (RuntimeException e) {
            long last = fenceLogAt.get();
            if (System.currentTimeMillis() - last >= 60_000L && fenceLogAt.compareAndSet(last, System.currentTimeMillis())) {
                log.warn("Zamanlanmış tarama sonucu işlenmedi: tarama liderliği doğrulanamadı (kapı hata verdi — güvenli taraf, "
                        + "tür={}, {} sonuç): {}", alertType, count, e.toString());
            }
            return false;
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
        // 3-2: devirde yükleme bitene dek bekler (kapı kuruluysa; boş toplu iş belleğe dokunmaz → beklemez).
        try (EvalLoadGate.Ticket t = items == null || items.isEmpty() ? EvalLoadGate.NO_TICKET : evalGate.enter()) {
            evaluateSweepResults(alertType, items, manual);
        } finally {
            noteSweepEnd(alertType);   // 3-1 ikili yazım: yazıcıya boşaltma ipucu (beklemez, karar sonrası)
        }
    }

    /** {@link #handleSweepResults}'ın gövdesi (2026-10-23'ten beri ayrı: çağıran turun sonunda boşaltma ipucunu verir). */
    private void evaluateSweepResults(String alertType, List<SweepItem> items, boolean manual) {
        if (items == null || items.isEmpty()) return;
        if (!manual && !scheduledResultsAllowed(alertType, items.size())) return;

        // Domain bazlı agregasyon: any-down = domain down; all-up = recovered
        Map<String, List<SweepItem>> byDomain = new LinkedHashMap<>();
        for (SweepItem it : items) {
            byDomain.computeIfAbsent(it.domain(), d -> new ArrayList<>()).add(it);
        }
        // Aynı anahtarı paylaşan izlemelerin son gözlemi (D2) — her kontrol bir gözlemdir; bastırma/kapalı bildirim
        // bunu değiştirmez.
        recordObservations(alertType, items, manual);
        if (!manual) {
            scheduledRoundSeen.add(alertType);   // D-6: bu türün kardeş gözlemleri en az bir kez dolduruldu
            noteTypeState(alertType);            // 3-1 ikili yazım: tur damgası (kaydedici tür başına 60 sn'de bire indirir)
        }

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
            decideChanges(items, ChangeStates.SUPPRESSED);   // R1-B: değişiklik bilerek alarmlanmadı (bekleyen satır kapanır)
            return;
        }

        // Bastırma YALNIZ ağ-sınıfı hatalara bakar — sertifika sweep'indeki kuralın aynısı
        // (SchedulerService: error_class ∈ {DNS, NETWORK}). Gerekçe için bkz. isOutageClass:
        // yapılandırma hatası olan bir monitör SONSUZA DEK düşük kalır ve oranı kalıcı şişirir.
        // SOAK-1 (2026-10-22): karar TEK turun kalemlerinden değil türün kayan penceresinden (alan adı başına son sonuç).
        // Girme: ≥ bulkMinErrors ağ-sınıfı DOWN ve oran ≥ eşik; çıkma (histerezis): oran eşiğin altına inince — tek turun
        // küçük örneği şüpheyi ne açar ne her turda kapatır.
        WindowSample sample = windowedOutageSample(alertType, byDomain);
        boolean active = Boolean.TRUE.equals(suppressionActive.get(alertType));
        boolean suppress = suppressDecision(active, sample.networkDown(), sample.rate());
        if (suppress) {
            noteSuppression(alertType, sample.networkDown(), sample.total());
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
            // ALERTS-11 (2026-10-22): aynı anahtarı paylaşan sahibin kalemlerinden EN YÜKSEK seviyeli olan (443 WARNING +
            // 8443 CRITICAL) — eskiden ilk kalem gidiyordu ve KRİTİK kardeş olayı hiç terfi ettirmiyordu.
            SweepItem firstDown = highestLevelItem(ownerDown);
            if (isChangeType(alertType)) {
                // R1-B (MP-13): açık olaya eklenen değişiklik (DOMAINMON_CHANGED) — karar kilit İÇİNDE, satır hâlâ
                // bekliyorsa; kilit alınamazsa satır PENDING kalır, sonraki tur yeniden kuyruğa alır.
                List<SweepItem> pendingDown = ownerDown;
                withLock(alertType, domain, () -> {
                    List<ChangePending> states = pendingDown.stream().map(this::changePending).toList();
                    if (!states.contains(ChangePending.YES)) {
                        // Hepsi başka karara bağlı ya da şu an okunamıyor (UNKNOWN → satır PENDING kalır, sonraki tik dener).
                        log.info("{} [{}]: değişiklik şu an işlenmedi ({}) — karar verilmiş ya da durum okunamadı", domain,
                                alertType, states);
                        return;
                    }
                    escalationService.processConfirmedOutage(domain, alertType, levelFor(alertType), sweepContext(firstDown));
                    for (SweepItem it : pendingDown) markChange(it, ChangeStates.ALARMED);
                });
                return;
            }
            withLock(alertType, domain, () ->
                    escalationService.processConfirmedOutage(domain, alertType,
                            levelFor(alertType), sweepContext(firstDown)));
            return;
        }
        if (foreignDown > 0) {
            // R1-B: başka sahibin açık olayı varken bu izlemenin değişikliği bugünkü kuralla (Y-1) alarmlanmaz — bekleyen
            // satır karara bağlanır (her turda yeniden kuyruğa girip aynı sonuca varmasın).
            if (isChangeType(alertType)) {
                for (SweepItem it : domainItems) if (!it.up() && !isOwnerItem(it, openEvent)) decideChange(it, ChangeStates.SUPPRESSED);
            }
            log.debug("{} [{}]: {} kalem başka sahibin izlemesi ve DOWN — açık olayı (#{}) etkilemez; o izlemenin alarmı "
                    + "bu olay kapandıktan sonra kendi takımıyla açılır", domain, alertType, foreignDown, openEvent.getId());
        }
        if (!up.isEmpty()) recoverDomain(alertType, domain, up, openEvent, false);
    }

    /** Kalemlerden bağlamdaki {@code alert_level}'i en yüksek olan; eşitlikte ilk (eski seçim). Liste boş olamaz. */
    static SweepItem highestLevelItem(List<SweepItem> items) {
        SweepItem best = items.get(0);
        int bestRank = levelRank(best);
        for (SweepItem it : items) {
            int r = levelRank(it);
            if (r > bestRank) { best = it; bestRank = r; }
        }
        return best;
    }

    private static int levelRank(SweepItem it) {
        Object lvl = it == null || it.ctxExtra() == null ? null : it.ctxExtra().get("alert_level");
        return switch (lvl == null ? "" : String.valueOf(lvl).trim().toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 3;
            case "HIGH" -> 2;
            case "WARNING" -> 1;
            default -> 0;
        };
    }

    /** Teyitli arızanın son hatası (yoksa tespit hatası) ağ / DNS sınıfı mı — bastırma oranına giren sınıf. */
    private static boolean networkClassFailure(SweepItem item, Map<String, Object> ctx) {
        Object last = ctx == null ? null : ctx.get("last_error");
        String err = last != null && !String.valueOf(last).isBlank() && !"null".equals(String.valueOf(last))
                ? String.valueOf(last) : item.error();
        return isOutageClass(err, item.domain()) || isOutageClass(item.error(), item.domain());
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
            noteRecovery(rkey);   // 3-1 ikili yazım (mutasyondan SONRA)
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
            startRecovery(alertType, domain, upItems, required, recIntervalMs, manual);
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
        boolean changed = recoveryUpCount.remove(rkey) != null;
        changed |= recoveryLastCountedAt.remove(rkey) != null;
        if (changed) noteRecovery(rkey);   // 3-1 ikili yazım: her sağlıklı alarmsız alan adı her turda buraya gelir — sıfır kuyruk
    }

    /** "tip:domain" → son SAYILMIŞ sağlıklı sonucun anı (ms) — elle tıklama aralığı (O-1). */
    private final ConcurrentHashMap<String, Long> recoveryLastCountedAt = new ConcurrentHashMap<>();

    /** Yeniden başlatmadan beri en az bir ZAMANLANMIŞ turu işlenmiş türler (D-6). */
    private final Set<String> scheduledRoundSeen = ConcurrentHashMap.newKeySet();

    // ── D2: aynı anahtarı paylaşan izlemelerin son gözlemi ───────────────────────────────────

    /** Kardeş gözleminin geçerlilik süresi — günlük kadanslı izlemeleri (alan adı) de kapsar; daha eskisi yok sayılır. */
    static final long SIBLING_TTL_MS = 26L * 60 * 60 * 1000;

    /** {@link #SIBLING_TTL_MS} — kalıcı durum doğrulayıcısı aynı TTL ile "budanmış = tutarlı" der (3-1). */
    public static long siblingTtlMs() {
        return SIBLING_TTL_MS;
    }

    /** Gözlem haritası budama aralığı (D-5): TTL'i aşan ya da artık izlenmeyen kayıtlar bu sıklıkla silinir. */
    static final long OBSERVATION_PRUNE_INTERVAL_MS = 10L * 60 * 1000;

    /**
     * Son gözlemi DOWN olan izleme (yalnız DOWN'lar tutulur; UP gözlem kaydı siler). {@code item} İNCE bir kopyadır —
     * yalnız tür, alan adı, detay ve sahiplik/canlılık için gereken bağlam anahtarları ({@code monitor_id},
     * {@code team_id}, {@code standalone}); izleme varlığını yakalayan recheck lambdası TUTULMAZ (D-5).
     *
     * <p>{@code loadedClass} (çok pod Sürüm 3-2, tasarım R3-2 §1.5): devirde YÜKLENEN kardeşin hata sınıfı — tablo ham hata
     * metnini tutmaz, yalnız sınıfı; canlı gözlemde null ({@link #classOf} hata metninden sınıflandırır). Karar yolu kardeşin
     * hatasını okumaz; yalnız yeniden oynatma ve doğrulama ({@link #downObservation}, {@link #verifySnapshot}) sınıfı kullanır.
     */
    private record DownObservation(String domain, SweepItem item, long atMs, String loadedClass) {}

    /** Gözlemin hata sınıfı: yüklenen sınıf, yoksa canlı hata metninin sınıfı. */
    private static String classOf(DownObservation o) {
        return o.loadedClass() != null ? o.loadedClass() : classifyError(o.item().error(), o.domain());
    }

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

    private void recordObservations(String alertType, List<SweepItem> items, boolean manual) {
        long now = nowMs.getAsLong();
        for (SweepItem it : items) {
            Long id = monitorIdOf(it);
            if (id != null && it.domain() != null) {
                String k = alertType + "|" + id;
                if (it.up()) downObserved.remove(k);
                else downObserved.put(k, new DownObservation(it.domain(), slim(it), now, null));
            }
            // 3-1 ikili yazım: HER kalemin son durumu (kimliksiz kalemler dâhil — tablo belleğin üst kümesi), mutasyondan sonra.
            noteObservation(alertType, it, manual, now);
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
                if (downObserved.remove(e.getKey(), o)) {
                    removed++;
                    noteObservationPruned(e.getKey(), o);   // 3-1 ikili yazım: satır da silinsin (F2), mutasyondan SONRA
                }
            }
        }
        return removed;
    }

    /** Teyit zincirinde yeniden kontrol SAĞLIKLI döndü (geçici dalgalanma) → o izlemenin DOWN gözlemi düşer. */
    private void clearDownObservation(SweepItem item) {
        Long id = monitorIdOf(item);
        if (id != null && downObserved.remove(item.alertType() + "|" + id) != null && item.domain() != null) {
            // 3-1 ikili yazım: teyit yeniden kontrolü SAĞLIKLI döndü — bu da kalemin bir gözlemidir; tablo "DOWN" kalırsa
            // bellek (kayıt yok) ile ayrışırdı (tasarım §2.5'te "kanca yok" denmişti; doğrulama bunu yanlış uyuşmazlık sayardı).
            noteObservation(item.alertType(), new SweepItem(item.alertType(), item.domain(), item.detail(), true, null,
                    item.ctxExtra(), null), false, nowMs.getAsLong());
        }
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
                if (downObserved.remove(e.getKey(), o)) noteObservationPruned(e.getKey(), o);   // 3-1 ikili yazım (F2)
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
        try (EvalLoadGate.Ticket t = evalGate.enter()) {   // 3-2: iç handleSweepResults çağrıları iç içe (yeniden beklemez)
            evaluateDnsSweep(failureItems, slowItems, changes, unexpectedItems, inconsistentItems);
        } finally {
            noteSweepEnd(EscalationService.TYPE_DNS_CHANGED);   // 3-1 ikili yazım: boşaltma ipucu
        }
    }

    private void evaluateDnsSweep(List<SweepItem> failureItems, List<SweepItem> slowItems,
                                  List<DnsChange> changes,
                                  List<SweepItem> unexpectedItems, List<SweepItem> inconsistentItems) {
        // Zamanlanmış DNS turu — değişiklik teyidi de alarm durumuna yazar: kirayı kaybeden pod'da hiçbiri işlenmez (2026-10-09).
        if (!scheduledResultsAllowed(EscalationService.TYPE_DNS_FAILURE, failureItems == null ? 0 : failureItems.size())) return;
        handleSweepResults(EscalationService.TYPE_DNS_FAILURE, failureItems);
        handleSweepResults(EscalationService.TYPE_DNS_SLOW, slowItems);   // yavaş/timeout'lu çözümleme — kendi teyit zinciri (3×60sn ctxExtra'dan)
        handleSweepResults(EscalationService.TYPE_DNS_UNEXPECTED, unexpectedItems);   // beklenen-değer kilidi (state; değer beklenene dönünce oto-kapanır)
        handleSweepResults(EscalationService.TYPE_DNS_INCONSISTENT, inconsistentItems);   // çoklu-resolver tutarsızlık (state; teyitli; resolver'lar aynılaşınca oto-kapanır)
        if (!appSettings.getBoolean("site.monitor.dns.alert-enabled", dnsAlertEnabled)) {
            if (changes != null) for (DnsChange c : changes) decideChange(changeItem(c), ChangeStates.SUPPRESSED);   // R1-B
            return;
        }

        Set<String> changedThisSweep = new HashSet<>();
        if (changes != null) {
            for (DnsChange c : changes) {
                changedThisSweep.add(c.domain());
                // DNS_CHANGED artık 3× teyitli (diğer DNS alarmları gibi): startConfirmation zinciri;
                // recheck baseline'a dönerse "geçici dalgalanma" → iptal. Teyit sonrası processConfirmedOutage
                // (HIGH) açar; handleSweepResults'a girmez → OTOMATİK KAPANMAZ (manuel). changeCtx old/new_values taşır.
                // R1-B (MP-13): karar dns_records.change_state'e yazılır; zincir yarıda kalırsa satır PENDING kalır ve
                // tarama lideri sonraki turda yeniden kuyruğa alır (requeueDnsChanges).
                startConfirmation(changeItem(c));
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

    /** Değişiklik → teyit kalemi (sweep ve yeniden kuyruk aynı kalemi kurar). */
    private static SweepItem changeItem(DnsChange c) {
        return new SweepItem(EscalationService.TYPE_DNS_CHANGED, c.domain(), c.recordType(), false, "changed",
                changeCtx(c), c.recheck());
    }

    /**
     * Karar verilmemiş DNS değişikliklerini yeniden teyide alır (2026-10-21, çok pod R1-B, MP-13) — tarama lideri her DNS
     * turunun BAŞINDA çağırır ({@code SchedulerService.requeuePendingDnsChanges}; değişiklikler {@code dns_records}
     * satırlarındaki PENDING durumdan, taban = satırın önceki değeri). Zamanlanmış sonuç kapısı ve tür ayarı aynen
     * uygulanır; teyit zinciri bu pod'da zaten sürüyorsa atlanır (o zincir kendi satırına karar yazar).
     *
     * @return başlatılan teyit sayısı
     */
    public int requeueDnsChanges(List<DnsChange> changes) {
        try (EvalLoadGate.Ticket t = changes == null || changes.isEmpty() ? EvalLoadGate.NO_TICKET : evalGate.enter()) {
            return requeueDnsChangesInner(changes);
        } finally {
            noteSweepEnd(EscalationService.TYPE_DNS_CHANGED);   // 3-1 ikili yazım: boşaltma ipucu
        }
    }

    private int requeueDnsChangesInner(List<DnsChange> changes) {
        if (changes == null || changes.isEmpty()) return 0;
        if (!scheduledResultsAllowed(EscalationService.TYPE_DNS_CHANGED, changes.size())) return 0;
        if (!appSettings.getBoolean("site.monitor.dns.alert-enabled", dnsAlertEnabled)) {
            for (DnsChange c : changes) decideChange(changeItem(c), ChangeStates.SUPPRESSED);
            return 0;
        }
        int started = 0;
        for (DnsChange c : changes) {
            SweepItem it = changeItem(c);
            if (inFlight.containsKey(confirmKey(it))) continue;
            log.info("Bekleyen DNS değişikliği yeniden teyide alındı (liderlik devri / yarıda kalan teyit): {} {} izleme={} "
                    + "tespit={}", c.recordType(), c.domain(), c.monitorId(), c.detectedAt());
            startConfirmation(it);
            started++;
        }
        return started;
    }

    // ── Değişiklik kararının kalıcı durumu (2026-10-21, çok pod R1-B, MP-13) ─────────────────────────────────────────

    static boolean isChangeType(String alertType) {
        return MANUAL_CLOSE_TYPES.contains(alertType);
    }

    /**
     * Kalemin karşılık geldiği değişiklik satırı: (tablo, izleme, tur anı). DNS bağlamı {@code changed_at}, alan adı
     * bağlamı {@code checked_at} taşır — ikisi de satırın {@code checked_at}'ıdır. Değişiklik türü değilse ya da kimlik
     * yoksa null (eski davranış: durum yazılmaz).
     */
    private static String[] changeRow(SweepItem it) {
        if (it == null || !isChangeType(it.alertType()) || it.ctxExtra() == null) return null;
        Long id = monitorIdOf(it);
        boolean dns = EscalationService.TYPE_DNS_CHANGED.equals(it.alertType());
        Object at = it.ctxExtra().get(dns ? "changed_at" : "checked_at");
        if (id == null || !(at instanceof String s) || s.isBlank()) return null;
        return new String[]{dns ? "dns_records" : "domain_checks", String.valueOf(id), s};
    }

    /** {@link #changePending} sonucu: işle / başka karar yazılmış (işleme) / şu an okunamadı (sonra yeniden dene). */
    enum ChangePending { YES, DECIDED, UNKNOWN }

    /**
     * Değişiklik satırı hâlâ karar bekliyor mu (inceleme 2026-10-22)?
     * <ul>
     *   <li>{@link ChangePending#YES} — satır PENDING; ya da eski davranış: kalem satıra bağlanamıyor, değişmiş satır
     *       bulunamadı, durumu NULL (bu özellikten önceki / eski pod'un yazdığı değişmiş satır — bunlar yeniden kuyruğa hiç
     *       alınmadığı için yükseltmede alarm seli olmaz) ya da kolon / tablo henüz yok (ilk açılış saniyeleri).</li>
     *   <li>{@link ChangePending#DECIDED} — başka bir karar yazılmış: yeniden alarmlanmaz.</li>
     *   <li>{@link ChangePending#UNKNOWN} — geçici okuma hatası: ŞİMDİ karar verilmez (iki kez alarmlama riski yok), satır
     *       PENDING kalır; zincir son adımı yeniden dener, olmazsa sonraki tur yeniden kuyruğa alır.</li>
     * </ul>
     * Yalnız değişmiş ({@code changed = TRUE}) satırlara bakılır: aynı saniyedeki elle satırın NULL durumu kararı
     * "bekliyor"a çevirmesin.
     */
    ChangePending changePending(SweepItem it) {
        String[] row = changeRow(it);
        if (row == null) return ChangePending.YES;
        try {
            List<String> states = jdbcTemplate.queryForList("SELECT " + ChangeStates.COLUMN + " FROM " + row[0]
                    + " WHERE monitor_id = ? AND checked_at = ? AND changed = TRUE", String.class, Long.valueOf(row[1]), row[2]);
            if (states == null || states.isEmpty()) return ChangePending.YES;
            for (String s : states) if (s == null || ChangeStates.PENDING.equals(s)) return ChangePending.YES;
            return ChangePending.DECIDED;
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            log.debug("Değişiklik durumu kolonu henüz yok ({} izleme {}) — eski davranış: {}", row[0], row[1], e.toString());
            return ChangePending.YES;
        } catch (Exception e) {
            log.info("Değişiklik durumu okunamadı ({} izleme {}) — karar ertelendi, yeniden denenecek: {}", row[0], row[1],
                    e.toString());
            return ChangePending.UNKNOWN;
        }
    }

    /** Bekleyen satıra kararı KOŞULLU yazar (yalnız PENDING iken — iki pod iki karar yazamaz). Hata yutulur. */
    void markChange(SweepItem it, String state) {
        String[] row = changeRow(it);
        if (row == null) return;
        try {
            int n = jdbcTemplate.update("UPDATE " + row[0] + " SET " + ChangeStates.COLUMN + " = ? WHERE monitor_id = ? "
                    + "AND checked_at = ? AND " + ChangeStates.COLUMN + " = ?", state, Long.valueOf(row[1]), row[2],
                    ChangeStates.PENDING);
            if (n > 0) log.debug("Değişiklik kararı yazıldı: {} izleme {} tur {} → {}", row[0], row[1], row[2], state);
        } catch (Exception e) {
            log.debug("Değişiklik kararı yazılamadı ({} izleme {}): {}", row[0], row[1], e.toString());
        }
    }

    /** Zamanlanmış kararın (geçici / bastırıldı) yazımı — kirayı kaybeden pod karar YAZMAZ (yeni lider yeniden teyit eder). */
    private void decideChange(SweepItem it, String state) {
        if (changeRow(it) == null) return;
        if (!scheduledResultsAllowed(it.alertType(), 1)) return;
        markChange(it, state);
    }

    private void decideChanges(List<SweepItem> items, String state) {
        if (items == null) return;
        for (SweepItem it : items) if (it != null && !it.up()) decideChange(it, state);
    }

    /**
     * Değişiklik alarmının son adımı kilit alınamadığı için {@value #CHANGE_LOCK_RETRIES} denemede de yapılamadı — iz
     * {@code notification_logs}'a (bildirim gitmedi, nedeni yazılı; satır PENDING kalır ve sonraki tur yeniden dener).
     */
    private void traceChangeLockGiveUp(SweepItem it, boolean stateUnreadable) {
        String why = stateUnreadable ? "değişiklik durumu okunamadı" : "alarm kilidi alınamadı";
        log.warn("{} [{}] değişiklik alarmı: {} ({} deneme) — karar bekliyor (sonraki tur yeniden dener), izleme={}",
                it.domain(), it.alertType(), why, CHANGE_LOCK_RETRIES, monitorIdOf(it));
        if (notificationLogRepo == null) return;
        try {
            com.sitemonitor.model.NotificationLog n = new com.sitemonitor.model.NotificationLog();
            n.setAlertEventId(0L);   // olay henüz yok (kolon NOT NULL)
            n.setSentAt(now());
            n.setTrigger("INITIAL");
            n.setRecipientRole("SYSTEM");
            n.setSubject(it.alertType() + " · " + it.domain());
            n.setMessage("Değişiklik alarmı (" + it.alertType() + ", " + it.domain() + ", izleme " + monitorIdOf(it)
                    + ") " + CHANGE_LOCK_RETRIES + " denemede işlenemedi (" + why + "); değişiklik kaydı karar bekliyor "
                    + "ve sonraki taramada yeniden teyit edilecek.");
            n.setEmailStatus("SKIPPED: " + why + " (" + CHANGE_LOCK_RETRIES + " deneme) — değişiklik bekliyor");
            n.setWebhookStatus("SKIPPED");
            notificationLogRepo.save(n);
        } catch (Exception e) {
            log.warn("Değişiklik alarmı kilit izi yazılamadı: {}", e.getMessage());
        }
    }

    /**
     * Karara bağlanamadan süresi dolan değişikliğin izi (inceleme 2026-10-22, F6) — olası ele geçirme sinyali yalnız WARN
     * satırında kaybolmasın: {@code notification_logs} (tetik = alarm türü, {@code SKIPPED: değişiklik süresi doldu …},
     * alertEventId 0). Satır başına TEK kez: çağıran önce koşullu EXPIRED yazar, yalnız 1 satır güncellediyse bunu çağırır.
     */
    public void traceChangeExpired(String alertType, String domain, Long monitorId, String detectedAt, long maxAgeMs) {
        long hours = Math.max(1L, maxAgeMs / 3_600_000L);
        log.warn("{} değişikliği karara bağlanamadan süresi doldu ({} saat) — alarmlanmadı: {} izleme={} tespit={}",
                alertType, hours, domain, monitorId, detectedAt);
        if (notificationLogRepo == null) return;
        try {
            com.sitemonitor.model.NotificationLog n = new com.sitemonitor.model.NotificationLog();
            n.setAlertEventId(0L);
            n.setSentAt(now());
            n.setTrigger(alertType);
            n.setRecipientRole("SYSTEM");
            n.setSubject(alertType + " · " + (domain == null ? "?" : domain));
            n.setMessage("Değişiklik (" + alertType + ", " + domain + ", izleme " + monitorId + ", tespit " + detectedAt
                    + ") " + hours + " saat içinde karara bağlanamadı (tarama lideri bu sürede teyit edemedi); alarm "
                    + "açılmadı. Kaydı izlemenin geçmişinden inceleyin.");
            n.setEmailStatus("SKIPPED: değişiklik süresi doldu (" + hours + " saat, karar verilemedi) — alarmlanmadı");
            n.setWebhookStatus("SKIPPED");
            notificationLogRepo.save(n);
        } catch (Exception e) {
            log.warn("Süresi dolan değişiklik izi yazılamadı: {}", e.getMessage());
        }
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
        // DBQUERY_* (2026-10-31) da aynı sınıfta: eşik öğesinin detayı kural özeti (ÖLÇÜLEN değeri taşır — "gelen 1.250"),
        // yavaş sorgunun detayı süre. Ad takımda tekildir; detail anahtara girmez, gösterimde korunur.
        if (EscalationService.isScripted(item.alertType())
                || EscalationService.TYPE_PAGESPEED_SLOW.equals(item.alertType())
                || EscalationService.isDbQuery(item.alertType()))
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
        long epoch = confirmEpoch.get();
        long chainId = chainSeq.incrementAndGet();
        if (inFlight.putIfAbsent(key, initial) != null) {
            log.debug("Teyit zaten devam ediyor, atlanıyor: {}", key);
            return;
        }
        chainOwner.put(key, chainId);
        noteChainStart(key, chainId, item, initial, effDelayMs(item));   // 3-1 ikili yazım (mutasyondan SONRA)
        if (confirmEpoch.get() != epoch) {   // liderlik değişimi tam bu arada sıfırladı → bu zincir eski döneme ait
            releaseChain(key, chainId);
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
            // finishConfirmed kaydı kendisi bırakır (değişiklik türünde kilit yeniden denemesi sürerken TUTAR); istisnada
            // burada bırakılır — releaseChain idempotent, başka zincirin kaydına dokunmaz.
            try {
                Map<String, Object> ctx = buildOutageContext(item, firstFailureAt, attempts);
                finishConfirmed(key, chainId, epoch, item, ctx, 0);
            } catch (Exception e) {
                log.error("Immediate teyit başarısız oldu: {} — {}", key, e.getMessage(), e);
                releaseChain(key, chainId);
            }
            return;
        }
        log.info("{} DOWN tespit edildi: {} [{}] — {} sn arayla {} doğrulama denemesi başlatıldı",
                item.alertType(), item.domain(), item.detail(), effDelayMs(item) / 1000, effAttempts(item));
        confirmExecutor.schedule(
                () -> runConfirmAttempt(key, chainId, epoch, item, firstFailureAt, attempts, 1),
                effDelayMs(item), TimeUnit.MILLISECONDS);
    }

    /** Zincir hâlâ geçerli mi: aynı liderlik dönemi ve anahtar hâlâ bu zincirin (eski çağrı = her zaman geçerli). */
    private boolean chainCurrent(String key, long chainId, long epoch) {
        if (chainId == LEGACY_CHAIN) return true;
        return confirmEpoch.get() == epoch && Long.valueOf(chainId).equals(chainOwner.get(key));
    }

    /** Anahtarı YALNIZ bu zincir tutuyorsa bırakır (sıfırlama sonrası başlayan yeni zincirin kaydı silinmez). */
    private void releaseChain(String key, long chainId) {
        if (chainId == LEGACY_CHAIN) {
            Long prev = chainOwner.remove(key);
            ConfirmState st = inFlight.remove(key);
            if (prev != null || st != null) noteChainEnd(key, prev != null ? prev : LEGACY_CHAIN);   // 3-1 ikili yazım
            return;
        }
        if (chainOwner.remove(key, chainId)) {
            inFlight.remove(key);
            noteChainEnd(key, chainId);   // 3-1 ikili yazım: yalnız kaydı GERÇEKTEN bu zincir bıraktıysa
        }
    }

    /**
     * Teyitli kesintinin SON adımı — alarm hattına verir. Zamanlanmış turun zinciri olduğu için önce kapı sorulur: kirayı
     * kaybeden pod karar YAZMAZ (değişiklik satırı PENDING kalır, yeni lider yeniden teyit eder; diğer türlerde yeni lider
     * kendi turunda yeniden tespit eder). Değişiklik türünde karar kilit İÇİNDE, satır hâlâ bekliyorsa verilir (iki pod aynı
     * değişikliği iki kez alarmlamaz) ve kilit çakışmasında {@link #CHANGE_LOCK_RETRY_MS} sonra yeniden denenir (en çok
     * {@link #CHANGE_LOCK_RETRIES}; eskiden ikinci zincir SESSİZCE atlanıyor, ikinci izlemenin değişikliği kayboluyordu).
     * Kaydı kendisi bırakır; yeniden deneme planlandıysa tutar. İstisna çağırana yayılır (kayıt bırakılmış olarak).
     */
    private void finishConfirmed(String key, long chainId, long epoch, SweepItem item, Map<String, Object> ctx, int lockTry) {
        if (!chainCurrent(key, chainId, epoch)) {
            log.debug("Teyit zinciri liderlik değişiminde düştü, son adım atlandı: {}", key);
            return;
        }
        if (!scheduledResultsAllowed(item.alertType(), 1)) {
            log.info("Teyit son adımı atlandı — bu pod artık tarama lideri değil, karar yeni liderde: {}", key);
            releaseChain(key, chainId);
            return;
        }
        boolean change = isChangeType(item.alertType());
        // İnceleme J-2 (2026-10-23, çok pod R2a): zincir sürerken türün alarm bildirimleri KAPATILDIYSA yeni alarm açılmaz
        // (kapalı türün açık alarmı uzlaştırmada zaten sessizce kapanır; açılsaydı INITIAL / eskalasyon adımı gidiyordu).
        // Değişiklik türünün bekleyen satırı SUPPRESSED olur (bilerek alarmlanmadı). Kurtarma ve elle kontrol kuralları aynı.
        if (!alertEnabled(item.alertType())) {
            log.info("Teyit tamamlandı ama {} türünün alarm bildirimleri kapalı — alarm açılmadı: {}", item.alertType(), key);
            if (change) markChange(item, ChangeStates.SUPPRESSED);
            releaseChain(key, chainId);
            return;
        }
        // SOAK-1 (2026-10-22): zincir şüphe AÇILMADAN önce başladıysa (pencere bir turun sağlıklı sonuçlarını hâlâ taşıyordu)
        // ve teyit bittiğinde tür için ağ kesintisi şüphesi açıksa ağ-sınıfı hata yeni alarm AÇMAZ — bastırma yalnız yeni
        // alarmı durdurur (kurtarma sürer). Şüphe kalkınca sonraki DOWN turu yeniden teyit eder. Değişiklik türü hariç.
        if (!change && outageSuppressed(item.alertType()) && networkClassFailure(item, ctx)) {
            log.info("Teyit tamamlandı ama {} için ağ kesintisi şüphesi açık — alarm açılmadı (şüphe kalkınca yeniden teyit): {}",
                    item.alertType(), key);
            releaseChain(key, chainId);
            return;
        }
        java.util.concurrent.atomic.AtomicBoolean unreadable = new java.util.concurrent.atomic.AtomicBoolean();
        LockOutcome out;
        try {
            out = lockAndRun(item.alertType(), item.domain(), () -> {
                if (change) {
                    ChangePending p = changePending(item);
                    if (p == ChangePending.DECIDED) {
                        log.info("Değişiklik başka pod'da zaten karara bağlandı — yeniden alarmlanmadı: {}", key);
                        return;
                    }
                    if (p == ChangePending.UNKNOWN) {   // geçici okuma hatası: şimdi karar yok, yeniden dene
                        unreadable.set(true);
                        return;
                    }
                }
                escalationService.processConfirmedOutage(item.domain(), item.alertType(), levelFor(item.alertType()), ctx);
                if (change) markChange(item, ChangeStates.ALARMED);
            });
        } catch (RuntimeException e) {
            releaseChain(key, chainId);
            throw e;
        }
        if ((out == LockOutcome.RAN && !unreadable.get()) || !change) {
            releaseChain(key, chainId);
            return;
        }
        if (lockTry < CHANGE_LOCK_RETRIES) {
            log.info("Değişiklik alarmı son adımı ertelendi ({}), {} ms sonra yeniden denenecek ({}/{}): {}",
                    unreadable.get() ? "durum okunamadı" : out, changeLockRetryMs, lockTry + 1, CHANGE_LOCK_RETRIES, key);
            noteChainFinishing(key, chainId, lockTry + 1);   // 3-1 ikili yazım — planlamadan ÖNCE (eşzamanlı yürütücüde sıra)
            try {
                confirmExecutor.schedule(() -> {
                    EvalLoadGate.Ticket step = evalGate.enterChainStep();   // 3-2: zincir adımı (M-1 yol 2)
                    if (step == null) {
                        log.debug("Değişiklik alarmı son adımı devir sırasında başladı — atlandı (satır PENDING kalır): {}",
                                key);
                        return;
                    }
                    try (step) {
                        try {
                            finishConfirmed(key, chainId, epoch, item, ctx, lockTry + 1);
                        } catch (Exception e) {
                            log.error("Değişiklik alarmı son adımı başarısız oldu: {} — {}", key, e.getMessage(), e);
                            releaseChain(key, chainId);
                        }
                    }
                }, changeLockRetryMs, TimeUnit.MILLISECONDS);
                return;
            } catch (RuntimeException e) {   // havuz kapandı (kapanış) — satır PENDING kalır
                log.warn("Değişiklik alarmı yeniden denemesi planlanamadı: {} — {}", key, e.toString());
                releaseChain(key, chainId);
                return;
            }
        }
        traceChangeLockGiveUp(item, unreadable.get());
        releaseChain(key, chainId);
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

    /** Eski giriş (zincir kimliği bilinmiyor — testler / doğrudan çağıran): anahtarı şu an tutan zincir sayılır. */
    void runConfirmAttempt(String key, SweepItem item, String firstFailureAt,
                           List<Map<String, Object>> attempts, int n) {
        Long owner = chainOwner.get(key);
        runConfirmAttempt(key, owner == null ? LEGACY_CHAIN : owner, confirmEpoch.get(), item, firstFailureAt, attempts, n);
    }

    void runConfirmAttempt(String key, long chainId, long epoch, SweepItem item, String firstFailureAt,
                           List<Map<String, Object>> attempts, int n) {
        // 3-2 (inceleme 2026-10-28, M-1 yol 2): adım sayılır — devirde durulma beklemesi yeniden kontrolün ve ardından gelen
        // bellek değişikliğinin bitmesini bekler; devir (HANDOVER) sürerken başlayan adım belleğe dokunmadan çıkar.
        EvalLoadGate.Ticket step = evalGate.enterChainStep();
        if (step == null) {
            log.debug("Teyit adımı devir sırasında başladı — atlandı, yeni lider zinciri yeniden başlatır ({}. deneme): {}",
                    n, key);
            return;
        }
        try (step) {
            runConfirmAttemptStep(key, chainId, epoch, item, firstFailureAt, attempts, n);
        }
    }

    private void runConfirmAttemptStep(String key, long chainId, long epoch, SweepItem item, String firstFailureAt,
                                       List<Map<String, Object>> attempts, int n) {
        try {
            // Liderlik değişimi (R1-B): sıfırlama kaydı temizledi; bu zincirin geç gelen adımı hiçbir şeye dokunmaz.
            if (!chainCurrent(key, chainId, epoch)) {
                log.debug("Teyit zinciri liderlik değişiminde düştü ({}. deneme): {}", n, key);
                return;
            }
            if (!stillMonitored(item)) {
                log.info("Teyit zinciri iptal — hedef artık izlenmiyor (silindi/duraklatıldı): {} ({}. deneme)", key, n);
                decideChange(item, ChangeStates.SUPPRESSED);
                releaseChain(key, chainId);
                return;
            }
            // Canlı durum: bu deneme koşuyor; sonraki (varsa) effDelayMs sonra — UI 30sn poll'unda "X/N" görünür.
            ConfirmState progressed = inFlight.computeIfPresent(key, (k, s) -> new ConfirmState(s.alertType(), s.domain(),
                    s.detail(), n, s.totalAttempts(), s.startedAt(), System.currentTimeMillis() + effDelayMs(item)));
            if (progressed != null) noteChainProgress(key, chainId, progressed, attempts);   // 3-1 ikili yazım
            Map<String, Object> r = item.recheck().get();
            if (!chainCurrent(key, chainId, epoch)) {   // yeniden kontrol saniyeler sürdü; o arada liderlik değişti
                log.debug("Teyit zinciri liderlik değişiminde düştü ({}. deneme sonrası): {}", n, key);
                return;
            }
            Map<String, Object> attempt = new LinkedHashMap<>();
            attempt.put("attempt", n);
            attempt.put("checked_at", now());
            attempt.put("status", r.get("status"));
            attempt.put("error", r.get("error"));
            attempts.add(attempt);
            ConfirmState current = inFlight.get(key);
            if (current != null) noteChainProgress(key, chainId, current, attempts);   // 3-1 ikili yazım: deneme listesi

            if ("up".equals(r.get("status"))) {
                log.info("Geçici dalgalanma: {} — {}. doğrulama denemesinde düzeldi, alarm üretilmedi", key, n);
                clearDownObservation(item);   // D2: bu izleme artık kardeşinin kapanışını bekletmez
                decideChange(item, ChangeStates.TRANSIENT);   // R1-B: değer tabana döndü — karar kalıcı
                releaseChain(key, chainId);
                return;
            }
            // "skipped" = doğrulama YÜRÜTÜLEMEDİ (ör. k6 havuzu dolu). Kanıt yok ⇒ zinciri iptal et.
            // Kesinti gerçekse sonraki sweep zinciri yeniden açar; tersini yapmak (kanıtsız DOWN
            // saymak) altyapı darlığını KRİTİK alarma çevirirdi. (Değişiklik satırı PENDING kalır → yeniden kuyruk.)
            if ("skipped".equals(r.get("status"))) {
                log.warn("Teyit denemesi yürütülemedi ({}. deneme): {} — {} · zincir iptal edildi, "
                        + "sonraki sweep yeniden değerlendirecek", n, key, r.get("error"));
                releaseChain(key, chainId);
                return;
            }
            if (n < effAttempts(item)) {
                confirmExecutor.schedule(
                        () -> runConfirmAttempt(key, chainId, epoch, item, firstFailureAt, attempts, n + 1),
                        effDelayMs(item), TimeUnit.MILLISECONDS);
                return;
            }

            log.warn("{} kesintisi TEYİT EDİLDİ: {} [{}] — {}/{} doğrulama denemesi başarısız",
                    item.alertType(), item.domain(), item.detail(), effAttempts(item), effAttempts(item));
            Map<String, Object> ctx = buildOutageContext(item, firstFailureAt, attempts);
            finishConfirmed(key, chainId, epoch, item, ctx, 0);
        } catch (Exception e) {
            log.error("Teyit başarısız oldu: {} — {}", key, e.getMessage(), e);
            releaseChain(key, chainId);
        }
    }

    /** Aktif recovery döngüsü (keyword/ping): alarm açıkken tüm monitörler up görülünce başlar; required kadar
     *  ardışık başarılı re-check (intervalMs arayla) sağlanınca alarmı kapatır; arada DOWN görülürse iptal olur. */
    void startRecovery(String alertType, String domain, List<SweepItem> items, int required, long intervalMs) {
        startRecovery(alertType, domain, items, required, intervalMs, false);
    }

    /**
     * @param manual zinciri elle kontrol başlattı — son adımı (kapanış) liderlik kapısından MUAFTIR (bugünkü elle kontrol
     *               kuralı); zamanlanmış turun zinciri kapanıştan önce kapıyı sorar (R1-B, SWEEPS-12)
     */
    void startRecovery(String alertType, String domain, List<SweepItem> items, int required, long intervalMs, boolean manual) {
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
                () -> runRecoveryAttempt(key, gen, alertType, domain, items, required, intervalMs, 1, manual),
                intervalMs, TimeUnit.MILLISECONDS);
    }

    void runRecoveryAttempt(String key, long gen, String alertType, String domain, List<SweepItem> items,
                            int required, long intervalMs, int n) {
        runRecoveryAttempt(key, gen, alertType, domain, items, required, intervalMs, n, false);
    }

    void runRecoveryAttempt(String key, long gen, String alertType, String domain, List<SweepItem> items,
                            int required, long intervalMs, int n, boolean manual) {
        if (!isCurrentGeneration(key, gen)) return;   // arada DOWN / yeniden başlatma / liderlik değişimi → bu zincir iptal
        // 3-2 (inceleme 2026-10-28, M-1 yol 2): adım sayılır — devirde durulma beklemesi saniyeler süren yeniden kontrolün ve
        // ardından gelen sayaç değişikliğinin (yeniden DOWN → sıfırlama) bitmesini bekler; bitmezse işaret yazılmaz (ardılı
        // sayaç taşımaz). Devir (HANDOVER) sürerken başlayan adım belleğe dokunmadan çıkar.
        EvalLoadGate.Ticket step = evalGate.enterChainStep();
        if (step == null) {
            log.debug("Kurtarma adımı devir sırasında başladı — atlandı, yeni lider yeniden değerlendirir: {} [{}] ({}. deneme)",
                    domain, alertType, n);
            return;
        }
        try (step) {
            runRecoveryAttemptStep(key, gen, alertType, domain, items, required, intervalMs, n, manual);
        }
    }

    private void runRecoveryAttemptStep(String key, long gen, String alertType, String domain, List<SweepItem> items,
                                        int required, long intervalMs, int n, boolean manual) {
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
                // R1-B (SWEEPS-12): zamanlanmış turun zinciri, kirayı kaybetmiş pod'da alarmı KAPATMAZ — yeni lider aynı
                // hedefi DOWN görüyor olabilir. Elle kontrolün zinciri bugünkü kuralla kapıdan muaftır.
                if (!manual && !scheduledResultsAllowed(alertType, items.size())) {
                    log.info("Recovery son adımı atlandı — bu pod artık tarama lideri değil, kapanış yeni liderde: {} [{}]",
                            domain, alertType);
                    endRecovery(key);
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
                    () -> runRecoveryAttempt(key, gen, alertType, domain, items, required, intervalMs, n + 1, manual),
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
        // MP-44 (2026-10-23, çok pod R2a): kaydı yapan pod kilit meşgulken yeniden dener; başka pod'un kaydını DB tazelemesinde
        // ({@code source="refresh"}, ~10 sn) gören pod da TEK deneme yapar (kilit altında, idempotent — push yalnız kapatanda):
        // kaydı yapan pod'un turundan SONRA biten teyit zincirlerinin (lider) açtığı alarmlar da kapanır (inceleme J-2).
        boolean refresh = "refresh".equals(ev.source());
        try {
            settingsReconcileExecutor.execute(refresh
                    ? () -> closeAlarmsOfDisabledTypes("ayar tazelemesi")
                    : () -> closeAlarmsOfDisabledTypesWithRetry("ayar değişikliği"));
        } catch (Exception ex) {
            log.warn("Kapalı türlerin alarm uzlaştırması kuyruğa alınamadı: {}", ex.getMessage());
        }
    }

    /**
     * MP-44: kapalı türlerin uzlaştırması küme genelinde TEK pod'da koşar ({@code scheduler_lock} satırı
     * {@value #DISABLED_TYPES_LOCK}, yenilenen kilit). Kilit servisi yoksa (elle kurulan testler, eski düzen) bugünkü gibi
     * kilitsiz koşar. İsim kalıcıdır.
     */
    static final String DISABLED_TYPES_LOCK = "disabled-types-reconcile";
    /** Ayar kaydı yolunda kilit meşgulse (başka pod'un açılış uzlaştırması) yeniden deneme sayısı ve aralığı — sınırlı. */
    static final int DISABLED_TYPES_ATTEMPTS = 6;
    long disabledTypesRetryDelayMs = 10_000L;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.schedule.ClusterLockService clusterLockService;
    /** MP-44: koşullu sessiz kapanış (yalnız açık satır, bütün varlık kaydı yok); yoksa bugünkü {@code EscalationService} yolu. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SilentAlarmCloser silentAlarmCloser;

    void setClusterLockService(com.sitemonitor.service.schedule.ClusterLockService s) { this.clusterLockService = s; }
    void setSilentAlarmCloser(SilentAlarmCloser c) { this.silentAlarmCloser = c; }

    /**
     * Ayar kaydı yolu: kilit başka pod'daysa (onun açılış uzlaştırması kaydı görmeden başlamış olabilir) aralıkla yeniden
     * dener, en çok {@link #DISABLED_TYPES_ATTEMPTS} kez; pod kapanıyorsa bırakır. Hepsi düşerse WARN — sonraki açılış telafi
     * eder.
     */
    void closeAlarmsOfDisabledTypesWithRetry(String reason) {
        for (int attempt = 1; attempt <= DISABLED_TYPES_ATTEMPTS; attempt++) {
            if (closeAlarmsOfDisabledTypes(reason) >= 0) return;
            if (clusterLockService == null || clusterLockService.acquiringStopped()) return;
            if (attempt == DISABLED_TYPES_ATTEMPTS) break;
            try {
                Thread.sleep(Math.max(0L, disabledTypesRetryDelayMs));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Kapalı türlerin alarm uzlaştırması {} denemede kilit alamadı ({}) — sonraki açılış ya da ayar kaydı telafi eder",
                DISABLED_TYPES_ATTEMPTS, reason);
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

    /**
     * @return sessizce kapatılan alarm sayısı; {@code -1} = kilit başka pod'da / pod kapanıyor / kilit alınamadı (hiçbir şey
     *         yapılmadı)
     */
    int closeAlarmsOfDisabledTypes(String reason) {
        try {
            Set<String> disabled = new HashSet<>();
            for (String t : EscalationService.MONITORING_ALERT_TYPES)
                if (!MANUAL_CLOSE_TYPES.contains(t) && !alertEnabled(t)) disabled.add(t);   // D-b9
            if (disabled.isEmpty()) return 0;   // kapalı tür yok: kilit de sorgu da yok (her ayar kaydında maliyet yok)
            com.sitemonitor.service.schedule.ClusterLockService locks = clusterLockService;
            if (locks == null) return closeOpenAlarmsOf(disabled, reason);
            var hold = locks.tryAcquire(DISABLED_TYPES_LOCK, java.time.Duration.ofMinutes(2),
                    com.sitemonitor.service.schedule.ClusterLockService.hardCapFor(DISABLED_TYPES_LOCK));
            if (hold.isEmpty()) {
                log.debug("Kapalı türlerin alarm uzlaştırması ({}) — kilit başka pod'da ya da pod kapanıyor, atlandı", reason);
                return -1;
            }
            try {
                return closeOpenAlarmsOf(disabled, reason);
            } finally {
                locks.release(hold.get());
            }
        } catch (Exception ex) {
            log.warn("Kapalı türlerin açık alarmları kapatılamadı ({}): {}", reason, ex.getMessage());
            return 0;
        }
    }

    private int closeOpenAlarmsOf(Set<String> disabled, String reason) {
        try {
            int n = 0;
            for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
                if (e.getDomain() == null || !disabled.contains(e.getAlertType())) continue;
                SilentAlarmCloser closer = silentAlarmCloser;
                if (closer != null) {
                    // MP-44: koşullu kapanış — başka yolda kapanmış alarmı yeniden yazmaz, okumadan sonra yazılmış onay /
                    // damga ezilmez, çözüm push'u yalnız kapatan çağıranda.
                    if (closer.closeIfOpen(e, SilentAlarmCloser.BY_TYPE_DISABLED)) n++;
                } else {
                    escalationService.resolveOpenAlertsSilently(e.getDomain(), Set.of(e.getAlertType()),
                            SilentAlarmCloser.BY_TYPE_DISABLED);
                    n++;
                }
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
            // Veritabanı sorgu izlemesi (2026-10-31): kendi anahtarı (diğer türlerle parite).
            case EscalationService.TYPE_DBQUERY_FAIL,
                 EscalationService.TYPE_DBQUERY_THRESHOLD,
                 EscalationService.TYPE_DBQUERY_SLOW ->
                    appSettings.getBoolean("site.monitor.dbquery.alert-enabled", true);
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
        lockAndRun(alertType, domain, action);
    }

    /** {@link #lockAndRun} sonucu: eylem koştu / kilit başka yazarda / geçici DB hatası (eylem koşmadı). */
    enum LockOutcome { RAN, BUSY, FAILED }

    /**
     * {@link #withLock}'un sonucu bildiren hâli (2026-10-21, çok pod R1-B): değişiklik türünün son adımı kilit çakışmasında
     * yeniden denenir ({@link #finishConfirmed}); diğer yollar sonucu yok sayar (bugünkü "sessiz atla"). Eylemin istisnası
     * yayılır (kilit finally'de bırakılır).
     */
    LockOutcome lockAndRun(String alertType, String domain, Runnable action) {
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
            return LockOutcome.BUSY;
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            // Kilit TABLOSU yok (ilk açılışta ddl-auto henüz koşmadı) — bilinçli tek-pod geri düşüşü.
            log.warn("Distributed lock table unavailable (HA degraded): {}", e.getMessage());
            acquired = false; // degrade: kilitsiz devam — tek instance kurulumlarda güvenli
        } catch (Exception e) {
            if (StormService.isUniqueViolation(e)) {
                log.debug("İzleme alarm kilidi başka replikada: {} — atlanıyor", lockName);
                return LockOutcome.BUSY;
            }
            // SchedulerService.tryAcquireSchedulerLock ile aynı kural: geçici DB hatasında (deadlock,
            // statement-timeout, bağlantı kopması) kilitsiz devam etmek çok-pod'da aynı alarmı İKİ kez
            // açmak (çift alert_event + çift eskalasyon maili/push) demekti. Güvenli taraf bu turu
            // ATLAMAK — teyit zinciri bir sonraki sweep'te aynı sinyalle tekrar kurulur.
            log.warn("İzleme alarm kilidi alınamadı — {} bu tur atlanıyor (güvenli taraf): {}", lockName, e.getMessage());
            return LockOutcome.FAILED;
        }
        try {
            action.run();
            return LockOutcome.RAN;
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

    /**
     * Kilit sahibi kimliğinin host kısmı — {@link SchedulerService}'in kilit kimliğiyle AYNI değer (SOAK-3, 2026-10-22): makine
     * adı → {@code HOSTNAME} → {@code POD_NAME} → rastgele. Eskiden çözüm düşünce sabit "unknown" dönüyordu; birden çok pod'da
     * düşerse hepsi "unknown-…" olur, bu pod'un önceki örneğinin izleme kilitleri açılış temizliğince tanınmazdı.
     */
    private static String resolveHostname() {
        return SchedulerService.hostname();   // aynı süreçte aynı değer: açılış temizliği bu pod'un izleme kilitlerini de tanır
    }
}
