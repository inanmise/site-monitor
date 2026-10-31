package com.sitemonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.model.UserPushScope;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.repository.UserPushScopeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kişi-bazlı webhook (push) gönderim servisi — MAİL HATTINDAN TAMAMEN BAĞIMSIZ ikinci kanal.
 *
 * <p><b>Bağımsızlık sözleşmesi:</b> bu sınıftaki hiçbir arıza çağırana yayılmaz; tetik noktaları
 * zaten try/catch içinde ama {@link #enqueueAlert} kendi içinde de istisna yutar. Mail
 * gönderilemedi diye push, push gönderilemedi diye mail ASLA aksamaz.
 *
 * <p><b>DB-outbox (K7):</b> satır önce PENDING yazılır, tek-worker gönderir. Bellekte kuyruk yok:
 * pod yeniden başlasa da PENDING satırlar durur; sınırsız in-memory retry'ın OOM riski doğmaz.
 *
 * <p><b>Katman matrisi (K5):</b> gönder = global AÇIK + unvan-grubu AÇIK + tip AÇIK + takım AÇIK
 * + izleme {@code notifyWebhook} AÇIK + bastırılmamış + dedupe temiz + tavan aşılmamış.
 * Fırtına/bakım/toplu-kesinti bastırmaları için AYRI kod yok — tetik, mail hunisinin hemen
 * yanında durduğundan (K8: mail neyi gönderiyorsa webhook da) bastırmalar kendiliğinden miras
 * kalır: mail bastırıldıysa o satıra zaten gelinmez.
 *
 * <p><b>Gizlilik:</b> sunucu logları yalnız ADET yazar; sicil listesi yalnız DB/UI'da yaşar.
 */
@Slf4j
@Service
public class UserPushService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    /**
     * Teslimat damgalari — <b>UTC</b>.
     *
     * <p>Eskiden Istanbul yereliyle yaziliyordu ({@code Instant.now().atZone(ZONE)}), oysa
     * {@code AlertEvent.createdAt} ve {@code MonitoringOutageService} UTC yaziyor ve arayuzdeki
     * {@code toUtc} zone tasimayan HER damgaya 'Z' ekliyor. Sonuc: 14:03'te giden bir push
     * teslimat gunlugunde 17:03 gorunuyordu — yanindaki alarm saati dogru oldugu icin ikisi
     * yan yana 3 saat kayik duruyordu. Ayni asimetri CSV export'ta da vardi.
     *
     * <p><b>Gecis notu:</b> yayindan once yazilmis satirlar Istanbul yerelidir ve 3 saat ileri
     * gorunur; yeni satirlar dogrudur. Saatlik gonderim tavani (countRecentForUser) esik
     * penceresini ayni saate gore hesapladigi icin gecis suresince yalnizca daha TEMKINLI davranir.
     */
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    /**
     * Mesaj tavanının VARSAYILANI. Ürün sözleşmesi (webhook-bildirim K6: "şablon metinleri
     * ≤200 karakter, tek satır") — keyfi bir sayı değil. Yönetici
     * {@code site.monitor.userpush.max-message-chars} ile değiştirebilir; arayüz üst sınır
     * uygular, çünkü tavanı kaldırmak kanal sözleşmesini sessizce delerdi.
     */
    private static final int MAX_MESSAGE_CHARS = 200;
    private static final int MAX_RAW_RESPONSE = 500;
    /** Yanit govdesinden okunacak BAYT tavani (bkz. sendBatch) — notificationId birkac bayt,
     *  gunluge yazilan onizleme MAX_RAW_RESPONSE karakter; fazlasi bellekte tutulmaz. */
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    /** {@code notification_id} kolon uzunluğu — API daha uzun kimlik dönerse satır kaydı düşmesin diye kırpılır. */
    private static final int MAX_NOTIFICATION_ID = 60;
    /** Outbox turu başına en çok bu kadar satır (yaş sırasıyla). */
    private static final org.springframework.data.domain.PageRequest OUTBOX_PAGE =
            org.springframework.data.domain.PageRequest.of(0, 50);

    /** Katman/karar satırlarında kullanılan sistem-sicili: kapsam reddi kişiye değil olaya aittir. */
    static final String SYSTEM_USER = "-";

    private final AppSettingsService appSettings;
    private final UserPushDeliveryRepository deliveryRepo;
    private final UserPushScopeRepository scopeRepo;
    private final UserPushRecipientResolver resolver;
    private final AlertEventRepository alertEventRepo;
    private final SecretCipher secretCipher;
    private final TrustEvaluator trustEvaluator;
    private final CaAutoPinService caAutoPinService;

    /**
     * Tek worker: outbox'ı sırayla boşaltır — API'ye eşzamanlı yığılma olmaz. Kapanışta (2026-10-21) bekleyen GECİKMELİ
     * görevler (backoff / devre kesici yeniden bakışı) koşmaz — satırlar veritabanında durur, başka pod ya da yeni pod alır;
     * yalnız süren gönderim bitirilir ({@link #shutdown}).
     */
    private final ScheduledExecutorService worker = newWorker();

    private static ScheduledExecutorService newWorker() {
        java.util.concurrent.ScheduledThreadPoolExecutor ex = new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "user-push-worker");
            t.setDaemon(true);
            return t;
        });
        ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        ex.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        ex.setRemoveOnCancelPolicy(true);
        return ex;
    }

    /** Kapanış başladı: yeni tur başlamaz, süren tur kalan batch'leri göndermeden kiralarını bırakır. */
    private volatile boolean stopping;
    /** Kapanışta süren gönderimin bitmesi için en çok bu kadar beklenir (sonra kesilir). */
    static final long SHUTDOWN_WAIT_SECONDS = 10;

    // ── Devre kesici (K7) — bellek içi; pod yeniden başlarsa temiz başlar (bilinçli) ──
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    // ── Kira tazeleme hataları (2026-10-23, çok pod R2b) — {@code sitemonitor_push_lease_renew_failed} POD ölçümü ──
    /** Tazeleme SORGUSU düştü (batch gönderilmedi; kira dolunca yeniden alınır). */
    private final java.util.concurrent.atomic.AtomicLong leaseRenewErrors = new java.util.concurrent.atomic.AtomicLong();
    /** Satırların (bir kısmının) kirası başka pod'a geçmişti — bu pod onları göndermedi. */
    private final java.util.concurrent.atomic.AtomicLong leaseRenewLost = new java.util.concurrent.atomic.AtomicLong();
    private volatile long circuitOpenUntil = 0L;

    @Autowired
    public UserPushService(AppSettingsService appSettings, UserPushDeliveryRepository deliveryRepo,
                           UserPushScopeRepository scopeRepo, UserPushRecipientResolver resolver,
                           AlertEventRepository alertEventRepo, SecretCipher secretCipher,
                           TrustEvaluator trustEvaluator, CaAutoPinService caAutoPinService) {
        this.appSettings = appSettings;
        this.deliveryRepo = deliveryRepo;
        this.scopeRepo = scopeRepo;
        this.resolver = resolver;
        this.alertEventRepo = alertEventRepo;
        this.secretCipher = secretCipher;
        this.trustEvaluator = trustEvaluator;
        this.caAutoPinService = caAutoPinService;
    }

    /**
     * Giden istemci — gönderim başına DEĞİL, bir kez kurulur.
     *
     * <p>Önceden her {@code sendBatch} çağrısı yeni bir {@link HttpClient} kuruyor ve hiç
     * kapatmıyordu: her JDK istemcisi kendi selector-thread'ini, bağlantı havuzunu ve FD'lerini
     * tutar. Alarm başına bir istemci demek, yoğun bir günde yüzlerce ölü istemci demekti — üstelik
     * bağlantı yeniden kullanımı da her seferinde sıfırlanıyordu.
     *
     * <p>Yalnız {@code connectTimeout} kurulum-zamanı bir ayardır; değişirse istemci yeniden kurulur.
     * Güven bağlamı (kurumsal CA paketi / pin) HER handshake'te canlı okunur, o yüzden CA değişikliği
     * yeniden kurma gerektirmez.
     */
    private volatile HttpClient httpClient;
    private volatile int httpClientTimeout = -1;
    private final Object clientLock = new Object();

    /**
     * Kapanış (2026-10-21, çok pod — MP-05 / N-16): eskiden {@code shutdownNow} süren gönderimi KESİYORDU — ağ geçidi isteği
     * kabul etmiş ama SENT yazılmamış satır kira dolunca başka pod'ca yeniden gönderiliyordu (aynı push iki kez). Artık süren
     * istek + sonucunun yazımı kapanışın başında ({@link #beginShutdown}, {@code NotifyExecutorDrain}) beklenir; burada en çok
     * {@value #SHUTDOWN_WAIT_SECONDS} sn ve ortak yok etme süresi kadar daha beklenir. Turun kalan batch'leri gönderilmez,
     * kiraları bırakılır (başka pod hemen alır). Süre dolarsa kesilir (eski davranış).
     */
    /**
     * Kapanışın EN BAŞI ({@code ContextClosedEvent}, {@code NotifyExecutorDrain} çağırır — inceleme N3): yeni tur / batch
     * başlamaz; süren istek bitirilir, boşaltma onu bekler. Eskiden {@code stopping} yalnız {@code @PreDestroy}'da kuruluyordu:
     * worker web ve yürütücü aşamalarında göndermeye devam ediyor, ortak yok etme süresi ({@code ShutdownDeadline}) önceki
     * beklemelerce tüketilince süren gönderim SENT yazılmadan kesiliyor ve başka pod aynı push'u yeniden gönderiyordu.
     */
    public void beginShutdown() {
        stopping = true;
    }

    /** Şu an süren gönderim turu sayısı (tek worker: 0 / 1) — kapanış boşaltması bunu bekler. */
    public int inFlightBatches() {
        return worker instanceof java.util.concurrent.ThreadPoolExecutor tpe ? tpe.getActiveCount() : 0;
    }

    @PreDestroy
    void shutdown() {
        stopping = true;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(com.sitemonitor.config.ShutdownDeadline.waitMs(SHUTDOWN_WAIT_SECONDS * 1000L), TimeUnit.MILLISECONDS)) {
                log.warn("user-push: süren gönderim {} sn'de bitmedi — kesiliyor", SHUTDOWN_WAIT_SECONDS);
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        HttpClient c = httpClient;
        if (c != null) try { c.close(); } catch (Exception ignore) { /* best-effort */ }
    }

    // ── Ayarlar (CANLI okunur — şablon/timeout değişikliği anında etkir) ───────────────────

    public boolean enabled() { return appSettings.getBoolean("site.monitor.userpush.enabled", false); }
    /** Haftalık rapor onayı → takım üyelerine push (rol grubu / seviye kuralıyla). Vars. AÇIK (2026-09-13). */
    public boolean weeklyTeamEnabled() { return appSettings.getBoolean("site.monitor.userpush.weekly.team-enabled", true); }
    /** Haftalık rapor onayı → takım müdürüne DOĞRUDAN push (e-postanın alıcısı). Vars. AÇIK (2026-09-13). */
    public boolean weeklyManagerEnabled() { return appSettings.getBoolean("site.monitor.userpush.weekly.manager-enabled", true); }
    private String url() { return appSettings.getString("site.monitor.userpush.url", ""); }
    private int connectTimeout() { return appSettings.getInt("site.monitor.userpush.timeout-connect-seconds", 3); }
    private int totalTimeout() { return appSettings.getInt("site.monitor.userpush.timeout-total-seconds", 5); }
    private int retryMax() { return appSettings.getInt("site.monitor.userpush.retry-max", 2); }
    private int circuitThreshold() { return appSettings.getInt("site.monitor.userpush.circuit-threshold", 5); }
    private int circuitCooldownSec() { return appSettings.getInt("site.monitor.userpush.circuit-cooldown-seconds", 300); }
    private int hourlyCap() { return appSettings.getInt("site.monitor.userpush.hourly-cap", 30); }
    /**
     * Saat tavanı özeti (2026-10-04, onaylı öneri 2): tavana takılan push'lar kullanıcı başına TEK özet push'unda toplanır.
     * Vars. AÇIK; kapalıyken {@code RATE_LIMITED} satırları bugünkü gibi yalnız günlükte kalır.
     */
    public boolean overflowSummaryEnabled() {
        return appSettings.getBoolean("site.monitor.userpush.overflow-summary-enabled", true);
    }
    /** Özet aralığı (dk) — ilk özetlenmemiş taşmadan bu kadar sonra, kullanıcı başına bu aralıkta en çok BİR özet. 5–120. */
    public int overflowSummaryMinutes() {
        int v = appSettings.getInt("site.monitor.userpush.overflow-summary-minutes", 15);
        return Math.min(120, Math.max(5, v));
    }
    /** KRİTİK alarm push'ları saat tavanına hiç takılmasın mı (vars. KAPALI = bugünkü davranış). */
    public boolean criticalBypassCap() {
        return appSettings.getBoolean("site.monitor.userpush.critical-bypass-cap", false);
    }
    /** Zamana bağlı eskalasyon adımı kişiye push da göndersin mi (vars. AÇIK; adım tanımlı değilse etkisiz). */
    public boolean stepPushEnabled() {
        return appSettings.getBoolean("site.monitor.escalation.step-push-enabled", true);
    }
    /** Alarm push'u bu seviyede saat tavanından muaf mı (yalnız ayar AÇIK + KRİTİK). */
    boolean capBypass(String level) {
        return "CRITICAL".equalsIgnoreCase(level == null ? "" : level.trim()) && criticalBypassCap();
    }
    /**
     * Mesaj tavanı — yönetici ayarı (vars. {@link #MAX_MESSAGE_CHARS}), <b>sunucuda kırpılır</b>.
     *
     * <p>Arayüz 80-320 aralığını dayatıyor ama {@code AppSettingsService.validate} yalnız TİP
     * doğruluyor, ARALIK doğrulamıyor: API'den 0 gönderilirse her push mesajı {@code "..."}
     * olurdu. Aynı sınıf bulgu DNS/Domain teyit-kurtarma alanlarında da çıkmıştı ve orada da
     * sunucu tarafı kırpmayla çözülmüştü — aynı karar burada da uygulanıyor.
     */
    int maxMessageChars() {
        int raw = appSettings.getInt("site.monitor.userpush.max-message-chars", MAX_MESSAGE_CHARS);
        return Math.min(320, Math.max(80, raw));
    }

    /**
     * Mesaj tavanı (kırpılmış, {@link #maxMessageChars}) — kodla giriş push şablonunun uzunluk doğrulaması için
     * (2026-10-03): {@link #sendDirect} bu tavanla kırpar; şablon en kötü dolumla bu sınırı aşarsa kod kesilebilirdi.
     */
    public int messageCharLimit() {
        return maxMessageChars();
    }

    /**
     * Sebep ({@code neden}/{@code degisen}) tavanı — yönetici ayarı, sunucuda kırpılır.
     *
     * <p>{@code <= 0} bilinçli "tavan yok" demektir: dıştaki mesaj tavanı zaten üç noktayla
     * taşmayı hallediyor. Pozitif değerler 40-280 aralığına çekilir.
     */
    private int reasonMaxChars() {
        int raw = appSettings.getInt("site.monitor.userpush.reason-max-chars", PushText.DEFAULT_REASON_CHARS);
        if (raw <= 0) return 0;
        return Math.min(280, Math.max(40, raw));
    }

    private List<Integer> backoffSeconds() {
        List<String> raw = appSettings.getCsv("site.monitor.userpush.retry-backoff-seconds", "30,120");
        List<Integer> out = new ArrayList<>();
        for (String s : raw) { try { out.add(Integer.parseInt(s.trim())); } catch (Exception ignored) { } }
        return out.isEmpty() ? List.of(30, 120) : out;
    }

    // ── Tetikler ───────────────────────────────────────────────────────────────────────────

    /**
     * Alarm hunisinden (INITIAL/ESCALATION/DAILY_REALERT/MANUAL) çağrılır — mail SONUCUNDAN
     * bağımsız, mail çağrısının hemen yanından. Hiçbir istisna yayılmaz.
     */
    public void enqueueAlert(Long alertEventId, String mailTrigger, Long fallbackTeamId,
                             Map<String, Object> ctx) {
        enqueueAlert(alertEventId, mailTrigger, fallbackTeamId, ctx, Set.of());
    }

    /**
     * @param excludeUsernames "Tekrar Bildir" onay pop-up'inda kullanicinin webhook listesinden
     *                         CIKARDIGI sicil(ler). Mail tarafindaki {@code excludeEmails}'in
     *                         karsiligi: iki kanal ayri ayri secilebilir.
     */
    public void enqueueAlert(Long alertEventId, String mailTrigger, Long fallbackTeamId,
                             Map<String, Object> ctx, Set<String> excludeUsernames) {
        try {
            if (!enabled() || alertEventId == null) return;   // global KAPALI = bugünün davranışı; satır bile yazılmaz
            AlertEvent event = alertEventRepo.findById(alertEventId).orElse(null);
            if (event == null) return;
            String trigger = switch (mailTrigger == null ? "" : mailTrigger) {
                case "INITIAL" -> "OPEN";
                case "ESCALATION" -> "ESCALATION";
                case "DAILY_REALERT" -> "RE_ALERT";
                case "MANUAL" -> "RESEND";
                default -> "OPEN";
            };
            if ("RE_ALERT".equals(trigger)
                    && !appSettings.getBoolean("site.monitor.userpush.realert-enabled", true)) {
                skipRow(event, trigger, "SKIPPED_REALERT_OFF");
                return;
            }
            // Sistem bakımı (2026-10-02): elle gönderim (RESEND) operatör iradesidir — susmaz; diğer fazlar karar satırıyla atlanır.
            if (!"RESEND".equals(trigger) && systemMaintenanceMuted()) {
                skipRow(event, trigger, SystemMaintenanceService.PUSH_SKIPPED);
                return;
            }
            enqueueInternal(event, trigger, fallbackTeamId, ctx, excludeUsernames);
        } catch (Exception e) {
            log.warn("user-push enqueue atlandı (alarm yolu etkilenmedi): {}", e.toString());
        }
    }

    /** Çözüm bildirimi tetiği (takım yedeği yok — eski çağıranlar). */
    public void enqueueResolve(AlertEvent event, Map<String, Object> ctx) {
        enqueueResolve(event, ctx, null);
    }

    /**
     * Çözüm bildirimi tetiği. {@code fallbackTeamId}: olayda takım damgası yoksa satırın
     * team_id'si için yedek (sertifika alarmlarında envanterin SY takımı). Eskiden yedek olarak
     * yine {@code event.getTeamId()} geçiliyordu → damgasız sertifika olayında alıcı çözümü boş
     * kalıp SKIPPED_NO_RECIPIENTS ("katman kararı") yazılıyor, e-posta giderken push gitmiyordu.
     */
    public void enqueueResolve(AlertEvent event, Map<String, Object> ctx, Long fallbackTeamId) {
        try {
            if (!enabled() || event == null || event.getId() == null) return;
            // Hâlâ gönderilmemiş (PENDING) açılış/eskalasyon/tekrar push'u iptal edilir (2026-10-09): alarm düzeldikten
            // sonra "DÜŞTÜ" gitmesin. Karar aşağıda AYNEN kalır — SENT satır yoksa çözüm de gitmez (SKIPPED_NO_PRIOR),
            // telefon zaten hiç "düştü" görmedi; SENT varsa "DÜZELDİ" her zamanki gibi gider.
            cancelPendingBeforeResolve(event);
            // Sistem bakımı (2026-10-02): bildirimler susturulmuşken çözüm push'u da gitmez — karar satırıyla.
            if (systemMaintenanceMuted()) {
                skipRow(event, "RESOLVE", SystemMaintenanceService.PUSH_SKIPPED);
                return;
            }
            // Simetri kuralı: açılışı kimseye push'lanmamış bir olayın çözümü de push'lanmaz —
            // yoksa kullanıcı hiç haber almadığı bir kesinti için "DÜZELDİ" mesajı alırdı.
            // (İzleme bayrağı/sessiz saat/katman reddi çözümde ayrıca değerlendirilmez; karar
            // açılışta verilmiş ve satıra yazılmıştır.)
            if (!deliveryRepo.existsByAlertEventIdAndStatus(event.getId(), "SENT")) {
                skipRow(event, "RESOLVE", "SKIPPED_NO_PRIOR");
                return;
            }
            enqueueInternal(event, "RESOLVE", fallbackTeamId, ctx, Set.of());
        } catch (Exception e) {
            log.warn("user-push çözüm enqueue atlandı: {}", e.toString());
        }
    }

    /** Alarm gönderilemeden düzeldi: bekleyen (PENDING) eski faz satırının durumu — push günlüğünde nedeniyle görünür. */
    public static final String STATUS_SKIPPED_RESOLVED_BEFORE_SEND = "SKIPPED_RESOLVED_BEFORE_SEND";

    /** Çözümde bekleyen eski faz satırlarını iptal eder — arıza çözüm kararını ASLA düşürmez. */
    private void cancelPendingBeforeResolve(AlertEvent event) {
        try {
            int n = deliveryRepo.cancelPendingForResolved(event.getId(), STATUS_SKIPPED_RESOLVED_BEFORE_SEND);
            if (n > 0) log.info("user-push: alarm #{} gönderilmeden düzeldi — {} bekleyen push iptal edildi", event.getId(), n);
        } catch (Exception e) {
            log.warn("user-push bekleyen satır iptali atlandı (olay #{}): {}", event.getId(), e.toString());
        }
    }

    private void enqueueInternal(AlertEvent event, String trigger, Long fallbackTeamId,
                                 Map<String, Object> ctx, Set<String> excludeUsernames) {
        Long teamId = event.getTeamId() != null ? event.getTeamId() : fallbackTeamId;
        String family = MonitorTypeCatalog.typeOfAlert(event.getAlertType());

        String block = channelBlockReason(event, trigger, teamId, family, ctx);
        if (block != null) { skipRow(event, trigger, block); return; }

        // Onay pop-up'inda cikarilan sicillere SATIR YAZILMAZ (mail tarafindaki filtreyle simetrik).
        Set<String> excluded = excludeUsernames == null ? Set.of() : excludeUsernames;
        // RESOLVE: alıcı kümesi açılışta gerçekten push ALANLARDIR (seviye/grup/takım çözümlemesi
        // yeniden yapılmaz) — bkz. resolver.resolvePrior. Diğer fazlar takım+seviye ile çözülür.
        // Kişisel aile süzgeci (2026-10-04) yalnız açılış/eskalasyon/tekrar/elle gönderimde; ÇÖZÜM muaf (resolvePrior).
        List<UserPushRecipientResolver.Recipient> resolved =
                "RESOLVE".equals(trigger) ? resolver.resolvePrior(priorSentUsernames(event))
                                          : UserPushRecipientResolver.withFamilies(
                                                  resolver.resolve(teamId, event.getAlertLevel()), familyList(family));
        String dedupeKey = dedupeKeyFor(trigger, event);
        String since = ISO.format(Instant.now().minus(Duration.ofHours(1)));
        // RESOLVE saat tavanından MUAF (prod kapısı 2026-09-25, O-3): 2026-09-10 kararı "çözüm push'u açılışta SENT
        // olanlara gider" — sessiz saatten muaf olmasının nedeni aynı ("telefondaki alarm kapanmalı"). Tavana
        // takılan "DÜZELDİ" push'u ayakta olan izlemeyi telefonda "düştü" gösteriyordu. Alıcılar zaten açılışta
        // SENT olanlarla sınırlı (resolvePrior), yani çözüm push'u açılış sayısını aşamaz.
        // KRİTİK alarm, "kritikler tavana takılmasın" ayarı açıksa muaf (2026-10-04, öneri 2; vars. KAPALI).
        boolean capExempt = "RESOLVE".equals(trigger) || capBypass(event.getAlertLevel());
        BaseStatus base = new BaseStatus(overCap(capExempt, since));   // devre kesici açıksa drainOutbox bekletir (N2)
        for (var r : resolved) base.skip(r.username(), r.skipReason());
        // Aynı kişi (2026-10-21, PushSamePersonGuard): aynı e-posta + aynı ad taşıyan aktif hesaplardan YALNIZ biri push alır —
        // bu bildirimi zaten almış hesap; yoksa GÖNDERİLEBİLİR hesaplar (önce tavan altı, sonra bu olayda push alan, sonra son
        // giriş, sonra kimlik). Hiçbiri gönderilebilir değilse birleştirme yok. Diğerleri SKIPPED_SAME_PERSON (tavana sayılmaz).
        // Karar ÇIKARMADAN ÖNCE, önizlemeyle aynı listede verilir: operatör push'u alacak hesabı çıkardıysa kişiyi çıkarmıştır —
        // öbür hesabı da yazılmaz (onay penceresinde "gitmeyecek" görünüyordu).
        PushSamePersonGuard.Plan same = samePlan(resolved.stream().map(UserPushRecipientResolver.Recipient::username).toList(),
                base, alarmPrior(event.getId(), d -> dedupeKey.equals(d.dedupeKey())));
        List<UserPushRecipientResolver.Recipient> recipients = resolved.stream()
                .filter(r -> !containsName(excluded, r.username()) && !containsName(excluded, same.chosenFor(r.username())))
                .toList();
        if (recipients.isEmpty()) { skipRow(event, trigger, "SKIPPED_NO_RECIPIENTS"); return; }

        // Mesaj kişinin DİLİNDE (2026-10-04, öneri 5) — dil başına bir kez kurulur; Türkçe bugünküyle bayt bayt aynı.
        Map<String, String> messages = new java.util.HashMap<>();
        String batchId = UUID.randomUUID().toString().substring(0, 8);
        String now = ISO.format(Instant.now());
        int queued = 0;
        for (var r : recipients) {
            String chosen = same.chosenFor(r.username());
            String status = chosen != null ? STATUS_SKIPPED_SAME_PERSON : base.of(r.username());

            if (deliveryRepo.existsByAlertEventIdAndDedupeKeyAndUsername(event.getId(), dedupeKey, r.username()))
                continue;   // faz zaten kayıtlı — sessiz erken çıkış (satır orada duruyor)

            String lang = r.lang();
            String message = messages.computeIfAbsent(lang, l -> buildMessage(event, trigger, ctx, l));
            UserPushDelivery d = row(event, trigger, dedupeKey, teamId, family, r.username(), r.displayName(),
                    message, status, now, lang);
            d.setSamePersonUsername(chosen);
            d.setBatchId(batchIdFor(batchId, lang));
            try {
                deliveryRepo.save(d);
                if ("PENDING".equals(status)) queued++;
            } catch (Exception dup) {
                // UNIQUE kısıt yarışta da son sözü söyler: aynı olayın aynı fazı aynı kişiye
                // kod hatasında bile iki kez yazılamaz. İhlal = zaten var → sessizce geç; başka hata WARN (ALERTS-12).
                logRowWriteFailure("olay #" + event.getId(), d, dup);
            }
        }
        if (queued > 0) {
            log.info("user-push kuyruğa alındı: {} alıcı (olay #{}, {})", queued, event.getId(), trigger);
            worker.execute(this::drainOutbox);
        }
    }

    /**
     * KANAL düzeyi katman kararı — {@code null} = geçti, aksi halde {@code SKIPPED_*} sebebi.
     *
     * <p>Ayrı metot olması ZORUNLU: gerçek gönderim ({@link #enqueueInternal}) ile onay
     * pop-up'ının önizlemesi ({@link #preview}) BUNU paylaşır. Mail tarafında aynı disiplin
     * zaten var ({@code EscalationService.resolveReNotifyTargets}) — önizleme, gerçekten
     * gönderilecek olandan sapamaz; saparsa kullanıcı onayladığı şeyden başkasını göndermiş olur.
     */
    private String channelBlockReason(AlertEvent event, String trigger, Long teamId,
                                      String family, Map<String, Object> ctx) {
        String scope = scopeBlockReason(teamId, family);
        if (scope != null) return scope;
        if (ctx != null && Boolean.TRUE.equals(ctx.get("push_disabled"))) return "SKIPPED_MONITOR_OFF";
        // İzleme bayrağı kararı KALICIDIR: OPEN'da yazılan SKIPPED_MONITOR_OFF satırı sonraki
        // fazları da bağlar. Gerekli çünkü bayrak ctx ile taşınır ve her yol taşımaz — örn.
        // manuel "yeniden gönder" izleme tiplerinde certContext'i null kurar; satır olmasaydı
        // kapalı izlemeye RESEND push'u sızardı. (OPEN/RESOLVE hariç: OPEN kararı zaten kendisi
        // verir, RESOLVE simetri kuralıyla — önce SENT yoksa — zaten gitmez.)
        if (!"OPEN".equals(trigger) && !"RESOLVE".equals(trigger)
                && deliveryRepo.existsByAlertEventIdAndStatus(event.getId(), "SKIPPED_MONITOR_OFF"))
            return "SKIPPED_MONITOR_OFF";
        // Sessiz saat ÇÖZÜMÜ tutmaz: açılış push'u gitmişse (simetri kuralı) telefondaki alarm gece de
        // kapanmalı; aksi halde kullanıcı sabaha kadar "düştü" ekranına bakıyordu (karar 2026-09-10).
        if (!"RESOLVE".equals(trigger) && quietHoursBlock(event.getAlertLevel())) return "SKIPPED_QUIET_HOURS";
        // Takım sessiz saati (2026-10-01, onaylı öneri 15): e-posta hunisi bu bildirimi takımın özetine erteledi — push da
        // ŞİMDİ gitmez, karar satırı kalır. İşaret yalnız ertelenen gönderimin ctx kopyasında bulunur (EscalationService);
        // çözümde, elle gönderimde ve önizlemede hiç yoktur → bu dal pencere tanımsızken hiç çalışmaz.
        if (ctx != null && Boolean.TRUE.equals(ctx.get(EscalationService.CTX_QUIET_DEFERRED)))
            return EscalationService.PUSH_SKIPPED_TEAM_QUIET;
        return null;
    }

    /**
     * Katman matrisi kararı (tür + takım) — gerçek gönderim ({@link #channelBlockReason}) ile "Kim bilgilendirilir?"
     * senaryosu ({@link #scenarioChannel}) AYNI kodu kullanır. {@code family}/{@code teamId} null ise o eksen sorulmaz.
     */
    private String scopeBlockReason(Long teamId, String family) {
        if (!scopeEnabled("TYPE", family)) return "SKIPPED_TYPE_OFF";
        if (teamId != null && !scopeEnabled("TEAM", String.valueOf(teamId))) return "SKIPPED_TEAM_OFF";
        return null;
    }

    /**
     * "Kim bilgilendirilir?" senaryosu için push KANAL durumu (2026-09-28) — HİÇBİR yazma yapmaz, alarm olayı yoktur.
     *
     * <p>Kişi kararları {@link UserPushRecipientResolver#explain} ile verilir; burada yalnız herkesi aynı anda etkileyen
     * kanal düzeyi kapılar döner. Kararlar gerçek gönderimin kodundan gelir: global anahtar ({@link #enabled()}),
     * adres ({@code url} boşsa gönderim "URL ayarlanmamış" ile FAILED düşer), katman matrisi
     * ({@link #scopeBlockReason}) ve sessiz saat ({@link #quietWindow}; ŞU ANKİ saate göre — gönderim de öyle karar
     * verir). İzleme başına "push kapalı" bayrağı senaryoda bilinmez (izleme seçilmiyor) — çağıran bunu metinle söyler.
     *
     * <p>Adres/başlık/şablon gibi ayar DEĞERLERİ dönmez; yalnız var/yok ve açık/kapalı bilgisi.
     *
     * @param standaloneMonitor izleme alarmı (sertifika dışındaki dokuz tür) mı; değilse yalnız {@code cert} türü sorulur
     */
    public Map<String, Object> scenarioChannel(Long teamId, String level, boolean standaloneMonitor) {
        boolean on = enabled();
        boolean configured = !url().isBlank();
        List<String> families = standaloneMonitor
                ? MonitorTypeCatalog.ORDER.stream().filter(f -> !"cert".equals(f)).toList()
                : List.of("cert");
        List<String> typeOff = families.stream().filter(f -> scopeBlockReason(null, f) != null).toList();
        boolean teamOn = teamId == null || scopeBlockReason(teamId, null) == null;
        QuietWindow quiet = quietWindow();
        boolean quietActive = quiet != null && quiet.contains(LocalTime.now(ZONE));
        boolean quietBlocksLevel = quiet != null && quiet.blocks(level);

        String block;
        if (!on) block = "CHANNEL_DISABLED";
        else if (!configured) block = "NOT_CONFIGURED";
        else if (!families.isEmpty() && typeOff.size() == families.size()) block = "SKIPPED_TYPE_OFF";   // gönderimle aynı sıra: önce tür
        else if (!teamOn) block = "SKIPPED_TEAM_OFF";
        else if (quietActive && quietBlocksLevel) block = "SKIPPED_QUIET_HOURS";
        else block = null;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", on);
        out.put("configured", configured);
        out.put("team_enabled", teamOn);
        out.put("types", families);
        out.put("disabled_types", typeOff);
        out.put("quiet_start", quiet == null ? null : quiet.start().toString());
        out.put("quiet_end", quiet == null ? null : quiet.end().toString());
        out.put("quiet_min_level", quiet == null ? null : quiet.minLevel());
        out.put("quiet_active", quietActive);
        out.put("quiet_blocks_level", quietBlocksLevel);
        out.put("block_reason", block);
        return out;
    }

    /** Bu olay için daha önce SENT olmuş tekil kullanıcı adları (sistem satırı '-' hariç), ilk gönderim sırasıyla. */
    private List<String> priorSentUsernames(AlertEvent event) {
        List<String> out = new ArrayList<>();
        for (UserPushDelivery d : deliveryRepo.findByAlertEventIdOrderByIdAsc(event.getId())) {
            if (!"SENT".equals(d.getStatus()) || d.getUsername() == null) continue;
            if (SYSTEM_USER.equals(d.getUsername()) || out.contains(d.getUsername())) continue;
            out.add(d.getUsername());
        }
        return out;
    }

    /**
     * Onay pop-up'ındaki tek webhook alıcı satırı. {@code status}: PENDING = gidecek, aksi halde sebep.
     * {@code samePersonOf}: durum {@code SKIPPED_SAME_PERSON} ise push'un gideceği (aynı kişinin) hesap (2026-10-21).
     */
    public record PushPreviewRow(String username, String displayName, String status, String samePersonOf) {
        public PushPreviewRow(String username, String displayName, String status) {
            this(username, displayName, status, null);
        }
    }

    /**
     * Webhook kanalının "kime gider" önizlemesi — HİÇBİR yazma yapmaz.
     *
     * @param blockReason kanal tamamen kapalıysa sebebi (satır listesi boş olur), aksi halde null
     */
    public record PushPreview(boolean channelEnabled, String blockReason, List<PushPreviewRow> recipients) {}

    /**
     * "Tekrar Bildir" onayı için webhook alıcılarını çözer. Kanal kararı ve alıcı çözümü
     * gerçek gönderimle AYNI kodu kullanır ({@link #channelBlockReason} + {@code resolver.resolve}).
     *
     * <p>ctx verilmez: manuel yol zaten {@code certContext}'i null kuruyor; izleme bayrağı kararı
     * kalıcı {@code SKIPPED_MONITOR_OFF} satırından okunur (yukarıdaki dal).
     */
    public PushPreview preview(Long alertEventId, Long fallbackTeamId) {
        return preview(alertEventId, fallbackTeamId, List.of());
    }

    /**
     * Önizleme + nöbetçi push'u (2026-10-12): gönderimde {@link #enqueueOnCall} ile eklenecek nöbetçiler de listelenir, böylece
     * operatör onları görüp çıkarabilir. Takım listesinde zaten olan kişi tekrar yazılmaz (gönderimde de ikinci push gitmez).
     */
    public PushPreview preview(Long alertEventId, Long fallbackTeamId,
                               List<com.sitemonitor.service.oncall.OnCallService.OnCallPerson> oncall) {
        PushPreview base = previewTeam(alertEventId, fallbackTeamId);
        // Olay düzeyindeki engel (izleme push'u kapalı, sessiz saat, kapsam…) nöbetçi push'unu da durdurur; yalnız "takımda alıcı yok"
        // durumunda nöbetçi satırları yine gösterilir.
        boolean teamOpen = base.blockReason() == null || "SKIPPED_NO_RECIPIENTS".equals(base.blockReason());
        if (!base.channelEnabled() || oncall == null || oncall.isEmpty() || !teamOpen) return base;
        AlertEvent event = alertEventRepo.findById(alertEventId).orElse(null);
        if (event == null) return base;
        String family = MonitorTypeCatalog.typeOfAlert(event.getAlertType());
        Set<String> listed = new HashSet<>();
        base.recipients().forEach(r -> listed.add(r.username()));
        // Bu gönderimde push'u GERÇEKTEN alacak hesaplar (PENDING — atlama, tavan, aynı kişi satırı değil): nöbetçinin kardeş
        // hesabı bunlardan biriyse gönderimdeki gibi (enqueueOnCall: yalnız push'u alan satırlar sayılır) nöbetçi satırı
        // SKIPPED_SAME_PERSON olur (2026-10-21).
        Map<String, String> holders = new LinkedHashMap<>();
        for (PushPreviewRow b : base.recipients())
            if ("PENDING".equals(b.status()) && b.samePersonOf() == null) holders.put(PushSamePersonGuard.key(b.username()), b.username());
        List<PushPreviewRow> extra = new ArrayList<>();
        String since = ISO.format(Instant.now().minus(Duration.ofHours(1)));
        Set<Long> done = new HashSet<>();
        for (var p : oncall) {
            if (p == null || p.userId() == null || !done.add(p.userId())) continue;
            if (scopeBlockReason(p.teamId(), family) != null) continue;
            com.sitemonitor.model.EscalationContact probe = new com.sitemonitor.model.EscalationContact();
            probe.setUserId(p.userId());
            probe.setTeamId(p.teamId());
            UserPushRecipientResolver.ContactMatch match = resolver.resolveContact(probe, event.getAlertLevel(), familyList(family));
            if (match == null || match.recipient() == null) continue;
            UserPushRecipientResolver.Recipient r = match.recipient();
            if (!listed.add(r.username())) continue;
            String chosen = samePlan(List.of(r.username()), null, names -> {
                Map<String, PushSamePersonGuard.Evidence> m = new HashMap<>();
                for (String n : names) {
                    if (holders.containsKey(PushSamePersonGuard.key(n)))
                        m.put(PushSamePersonGuard.key(n), new PushSamePersonGuard.Evidence(true, false, true));
                }
                return m;
            }).chosenFor(r.username());
            String status = chosen != null ? STATUS_SKIPPED_SAME_PERSON
                    : new BaseStatus(overCap(capBypass(event.getAlertLevel()), since)).skip(r.username(), r.skipReason()).of(r.username());
            if ("PENDING".equals(status)) holders.put(PushSamePersonGuard.key(r.username()), r.username());
            extra.add(new PushPreviewRow(r.username(), r.displayName(), status, chosen));
        }
        if (extra.isEmpty()) return base;
        List<PushPreviewRow> rows = new ArrayList<>(base.recipients());
        rows.addAll(extra);
        return new PushPreview(true, null, rows);
    }

    private PushPreview previewTeam(Long alertEventId, Long fallbackTeamId) {
        if (!enabled()) return new PushPreview(false, "CHANNEL_DISABLED", List.of());
        AlertEvent event = alertEventRepo.findById(alertEventId).orElse(null);
        if (event == null) return new PushPreview(true, "ALERT_NOT_FOUND", List.of());
        Long teamId = event.getTeamId() != null ? event.getTeamId() : fallbackTeamId;
        String family = MonitorTypeCatalog.typeOfAlert(event.getAlertType());

        String block = channelBlockReason(event, "RESEND", teamId, family, null);
        if (block != null) return new PushPreview(true, block, List.of());

        List<PushPreviewRow> rows = new ArrayList<>();
        String since = ISO.format(Instant.now().minus(Duration.ofHours(1)));
        boolean capExempt = capBypass(event.getAlertLevel());
        List<UserPushRecipientResolver.Recipient> recipients =
                UserPushRecipientResolver.withFamilies(resolver.resolve(teamId, event.getAlertLevel()), familyList(family));
        // Aynı kişi (2026-10-21): gönderimle AYNI karar (enqueueInternal) — elle gönderimin anahtarı her seferinde yenidir,
        // "bu bildirimi zaten aldı" kanıtı olmaz; seçim gönderilebilir hesaplar arasından (tavan altı, bu olayda push alan, son
        // giriş, kimlik); hiçbiri gönderilebilir değilse birleştirme yok.
        BaseStatus baseStatus = new BaseStatus(overCap(capExempt, since));   // devre kesici açıksa drainOutbox bekletir (N2)
        for (var r : recipients) baseStatus.skip(r.username(), r.skipReason());
        PushSamePersonGuard.Plan same = samePlan(recipients.stream().map(UserPushRecipientResolver.Recipient::username).toList(),
                baseStatus, alarmPrior(event.getId(), d -> false));
        for (var r : recipients) {
            String chosen = same.chosenFor(r.username());
            String status = chosen != null ? STATUS_SKIPPED_SAME_PERSON : baseStatus.of(r.username());
            rows.add(new PushPreviewRow(r.username(), r.displayName(), status, chosen));
        }
        if (rows.isEmpty()) return new PushPreview(true, "SKIPPED_NO_RECIPIENTS", List.of());
        return new PushPreview(true, null, rows);
    }

    /** Test gönderimi — gerçek istek, TEST satırı; dakikada 3 tavanı çağıran uç denetler. */
    public Map<String, Object> sendTest(List<String> usernames, String templateKey, String note) {
        return sendTest(usernames, templateKey, note, PushI18n.TR);
    }

    /** Test gönderimi seçilen DİLİN şablonu ve başlığıyla (2026-10-04: şablon düzenleyicisinin TR / EN sekmeleri). */
    public Map<String, Object> sendTest(List<String> usernames, String templateKey, String note, String lang) {
        String lng = PushI18n.norm(lang);
        String batchId = UUID.randomUUID().toString().substring(0, 8);
        String now = ISO.format(Instant.now());
        Map<String, String> sample = new LinkedHashMap<>();
        sample.put("seviye", "TEST"); sample.put("ad", "Örnek İzleme"); sample.put("hedef", "example.com");
        sample.put("neden", "deneme"); sample.put("metrik", "yanıt süresi"); sample.put("deger", "1200ms");
        sample.put("esik", "1000ms"); sample.put("ne", "sertifika"); sample.put("gun", "30");
        sample.put("tarih", "2026-12-31"); sample.put("degisen", "kayıt"); sample.put("sure", "5 dk");
        // fillTemplate YALNIZ haritada bulunan anahtari degistirir: burada eksik birakilan bir yer
        // tutucu, kullaniciya giden test mesajinda ciplak "{baslangic}" olarak KALIRDI. Kapi testi
        // her KNOWN_PLACEHOLDER'in hem burada hem buildMessage'da doldurulmasini zorunlu kilar.
        String sampleClock = PushText.istClockNow(Instant.now());
        sample.put("saat", sampleClock);
        sample.put("baslangic", sampleClock);
        sample.put("bitis", sampleClock);
        sample.put("ip", "192.0.2.10"); sample.put("cn", "ornek.example.com");
        if (PushI18n.isEn(lng)) {   // İngilizce örnek değerler (Türkçe örnek bugünküyle aynı kalır)
            sample.put("ad", "Example monitor"); sample.put("neden", "test"); sample.put("metrik", "response time");
            sample.put("ne", "certificate"); sample.put("degisen", "record"); sample.put("sure", "5 min");
            sample.put("cn", "example.example.com");
        }
        // Gercek gonderimle AYNI islem sirasi (pushSafe -> truncate): admin testte tam goren
        // ama gercek alarmda sessizce kesilen bir sablonu dogrulamis olmasin.
        String message = PushText.truncate(
                PushText.pushSafe(fillTemplate(template(templateKey == null ? "test" : templateKey, lng), sample)),
                maxMessageChars());
        List<UserPushDelivery> rows = new ArrayList<>();
        for (String u : usernames) {
            if (u == null || u.isBlank()) continue;
            UserPushDelivery d = new UserPushDelivery();
            d.setTrigger("TEST");
            d.setDedupeKey("TEST:" + batchId);
            d.setUsername(u.trim());
            d.setDisplayName(u.trim());
            d.setTitle(titleSetting(lng));
            d.setMessage(message);
            d.setStatus("PENDING");   // devre kesici açıksa drainOutbox bekletir (N2)
            d.setCreatedAt(now);
            d.setBatchId(batchId);
            d.setMonitorName(note);
            d.setPushLang(lng);
            rows.add(deliveryRepo.save(d));
        }
        boolean queued = rows.stream().anyMatch(r -> "PENDING".equals(r.getStatus()));
        if (queued) worker.execute(this::drainOutbox);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batch_id", batchId);
        out.put("queued", rows.size());
        out.put("message", message);
        return out;
    }

    /**
     * Takıma DOĞRUDAN bildirim (2026-09-12, Zayıf Algoritma Raporu "takıma bildir"): alarm olayı yok,
     * alıcılar takım + seviye ile çözülür (rol grubu / asgari seviye / sessiz saat kuralları aynen).
     * Kanal paritesi: e-posta ile aynı anda push. Dedupe anahtarı çağıranın verdiği {@code dedupeKey}
     * (aynı alan için gün içinde ikinci tıklama tekrar yazmaz). Dönen harita: queued / skipped.
     */
    public Map<String, Object> enqueueTeamNotice(Long teamId, String trigger, String alertLevel,
                                                 String monitorName, String message, String dedupeKey) {
        return enqueueTeamNotice(teamId, trigger, alertLevel, "CERTIFICATE", monitorName, message, dedupeKey);
    }

    /** {@link #enqueueTeamNotice(Long, String, String, String, String, String)} + teslimat günlüğünde görünen
     *  {@code monitorType} (2026-09-13: haftalık rapor onayı "WEEKLY_REPORT" olarak süzülebilsin). */
    public Map<String, Object> enqueueTeamNotice(Long teamId, String trigger, String alertLevel, String monitorType,
                                                 String monitorName, String message, String dedupeKey) {
        return enqueueTeamNotice(teamId, trigger, alertLevel, monitorType, monitorName, message, dedupeKey, java.util.Set.of());
    }

    /** + {@code excludeUsernames}: aynı olay için başka kanaldan (ör. müdür push'u) zaten bildirilen kişiler
     *  takım bildirimini İKİNCİ kez almaz (QA ISSUE-001, 2026-09-13). Karşılaştırma büyük/küçük harf duyarsız. */
    public Map<String, Object> enqueueTeamNotice(Long teamId, String trigger, String alertLevel, String monitorType,
                                                 String monitorName, String message, String dedupeKey,
                                                 java.util.Set<String> excludeUsernames) {
        return enqueueTeamNoticeLocalized(teamId, trigger, alertLevel, monitorType, monitorName, LocalizedText.of(message),
                dedupeKey, excludeUsernames);
    }

    /**
     * İki dilli metin (2026-10-04, öneri 5): push kişinin dilinde gider. {@code en} boşsa İngilizce alıcı da Türkçe metni
     * alır (eski çağıranlar). Türkçe metin her zaman zorunlu ve bugünküyle aynıdır.
     */
    public record LocalizedText(String tr, String en) {
        public static LocalizedText of(String tr) { return new LocalizedText(tr, null); }
        public String forLang(String lang) {
            return PushI18n.isEn(lang) && en != null && !en.isBlank() ? en : tr;
        }
    }

    /** Takım bildirimi türü → izleme ailesi (kişisel aile süzgeci için); bilinmeyen tür = süzgeç yok. */
    static List<String> noticeFamilies(String monitorType) {
        if (monitorType == null) return null;
        return switch (monitorType) {
            case "DOMAIN" -> List.of("domain");
            case "SCRIPTED" -> List.of("scripted");
            // TLS notu düşüşü "CERT" türüyle gelir (TlsGradeDropNotifier) — sertifika ailesidir (L-P1)
            case "CERTIFICATE", "CERT" -> List.of("cert");
            default -> null;
        };
    }

    /**
     * Takım bildirimi — iki dilli metinle (kişinin push diline göre, 2026-10-04). Ayrı ad bilinçli: aynı adlı aşırı yükleme
     * {@code any()} eşleyicili sahte nesnelerde belirsizlik doğururdu.
     */
    public Map<String, Object> enqueueTeamNoticeLocalized(Long teamId, String trigger, String alertLevel, String monitorType,
                                                          String monitorName, LocalizedText message, String dedupeKey,
                                                          java.util.Set<String> excludeUsernames) {
        // Anahtar kolona SIĞDIRILIR (ALERTS-12): yazım ve "zaten var" araması aynı değeri kullanır.
        dedupeKey = UserPushDelivery.fitDedupeKey(dedupeKey);
        java.util.Set<String> excluded = new java.util.HashSet<>();
        List<String> excludedNames = new ArrayList<>();
        for (String u : excludeUsernames == null ? java.util.Set.<String>of() : excludeUsernames) {
            if (u == null || u.isBlank()) continue;
            excluded.add(u.trim().toUpperCase(java.util.Locale.ROOT));
            excludedNames.add(u.trim());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queued", 0); out.put("skipped", 0); out.put("recipients", List.of());
        if (!enabled()) { out.put("reason", "SKIPPED_DISABLED"); return out; }
        if (teamId == null) { out.put("reason", "SKIPPED_NO_TEAM"); return out; }
        // Kişisel sessiz saat (2026-10-01) global sessiz saatin AYNASIDIR: global pencere takım bildirimlerini (haftalık rapor
        // onayı, zayıf algoritma raporu) susturmaz, kişisel pencere de susturmaz — kişi kararı burada "alıcı"ya döner
        // (opt-out'tan SONRA verildiği için başka bir red nedenini ezmez).
        // Kişisel tercihler (2026-10-04): seviye / susturma çözümde, aile burada (bildirimin türü biliniyorsa) — sessiz saat
        // eşlemesinden ÖNCE (aile süzgeci sessiz saat nedenini ezer).
        List<UserPushRecipientResolver.Recipient> recipients = UserPushRecipientResolver.withFamilies(
                        resolver.resolve(teamId, alertLevel), noticeFamilies(monitorType)).stream()
                .map(r -> UserPushRecipientResolver.SKIPPED_USER_QUIET_HOURS.equals(r.skipReason()) ? r.withSkip(null) : r)
                .toList();
        if (recipients.isEmpty()) { out.put("reason", "SKIPPED_NO_RECIPIENTS"); return out; }
        // Düşülen kişinin (ör. müdür push'unu alan, raporu gönderen) AYNI KİŞİYE ait öbür hesabı da bu bildirimi almaz
        // (2026-10-21): satırı SKIPPED_SAME_PERSON_EXCLUDED ("kişi bu bildirimden çıkarıldı"), satırdaki hesap = düşülen hesap.
        Map<String, String> excludedSiblings = excludedNames.isEmpty() ? Map.of() : samePersonSiblings(excludedNames);
        return writeTeamRows(out, teamId, trigger, alertLevel, monitorType, monitorName, message, dedupeKey,
                recipients, excluded, false, keyPrior(dedupeKey), excludedSiblings);
    }

    /**
     * Olaysız takım satırlarını yazar — takım bildirimi ve fırtına push'u ortak (dedupe, opt-out, saat tavanı, aynı kişi).
     *
     * @param prior            aynı kişi kuralının önceki satır kanıtı ({@link PushSamePersonGuard})
     * @param excludedSiblings düşülen hesapların kardeş hesapları ({@link PushSamePersonGuard#key}) → düşülen hesap
     */
    private Map<String, Object> writeTeamRows(Map<String, Object> out, Long teamId, String trigger, String alertLevel,
                                              String monitorType, String monitorName, LocalizedText message, String dedupeKey,
                                              List<UserPushRecipientResolver.Recipient> recipients,
                                              java.util.Set<String> excluded, boolean capExempt,
                                              PushSamePersonGuard.Prior prior, Map<String, String> excludedSiblings) {
        String batchId = UUID.randomUUID().toString().substring(0, 8);
        String now = ISO.format(Instant.now());
        String since = ISO.format(Instant.now().minus(Duration.ofHours(1)));
        Map<String, String> texts = new java.util.HashMap<>();
        int queued = 0, skipped = 0;
        List<String> names = new ArrayList<>();
        Map<String, String> excludedBy = excludedSiblings == null ? Map.of() : excludedSiblings;
        BaseStatus base = new BaseStatus(overCap(capExempt, since));   // devre kesici açıksa drainOutbox bekletir (N2)
        List<String> candidates = new ArrayList<>();
        for (var r : recipients) {
            base.skip(r.username(), r.skipReason());
            if (r.username() != null && !excluded.contains(r.username().trim().toUpperCase(java.util.Locale.ROOT))
                    && !excludedBy.containsKey(PushSamePersonGuard.key(r.username()))) candidates.add(r.username());
        }
        // Aynı kişi (2026-10-21): aynı e-posta + aynı ad taşıyan aktif hesaplardan YALNIZ biri — bu bildirimi zaten almış hesap;
        // yoksa gönderilebilir hesaplar (tavan altı, olayda push alan, son giriş, kimlik); hiçbiri gönderilebilir değilse
        // birleştirme yok. Diğerleri SKIPPED_SAME_PERSON (tavana sayılmaz).
        PushSamePersonGuard.Plan same = samePlan(candidates, base, prior);
        for (var r : recipients) {
            if (r.username() != null && excluded.contains(r.username().trim().toUpperCase(java.util.Locale.ROOT))) { skipped++; continue; }
            String chosen = excludedBy.get(PushSamePersonGuard.key(r.username()));
            String status;
            if (chosen != null) status = STATUS_SKIPPED_SAME_PERSON_EXCLUDED;
            else {
                chosen = same.chosenFor(r.username());
                status = chosen != null ? STATUS_SKIPPED_SAME_PERSON : base.of(r.username());
            }
            if (dedupeKey != null && deliveryRepo.existsByDedupeKeyAndUsername(dedupeKey, r.username())) { skipped++; continue; }
            UserPushDelivery d = new UserPushDelivery();
            d.setTrigger(trigger);
            d.setDedupeKey(dedupeKey);
            d.setMonitorType(monitorType == null ? "CERTIFICATE" : monitorType);
            d.setMonitorName(monitorName);
            d.setTeamId(teamId);
            d.setAlertLevel(alertLevel);
            d.setUsername(r.username());
            d.setDisplayName(r.displayName());
            String lang = r.lang();
            d.setTitle(titleSetting(lang));
            d.setMessage(texts.computeIfAbsent(lang, l -> PushText.truncate(PushText.pushSafe(message.forLang(l)), maxMessageChars())));
            d.setStatus(status);
            d.setSamePersonUsername(chosen);
            d.setCreatedAt(now);
            d.setBatchId(batchIdFor(batchId, lang));
            d.setPushLang(lang);
            if (!insertEventless(d)) { skipped++; continue; }
            if ("PENDING".equals(status)) { queued++; names.add(r.displayName() == null ? r.username() : r.displayName()); }
            else skipped++;
        }
        if (queued > 0) worker.execute(this::drainOutbox);
        out.put("queued", queued); out.put("skipped", skipped); out.put("recipients", names); out.put("batch_id", batchId);
        return out;
    }

    @Autowired(required = false)
    private org.springframework.transaction.PlatformTransactionManager txManager;

    /**
     * OLAYSIZ satırın eklenmesi (takım bildirimi, doğrudan bildirim, fırtına push'u ve karar satırı) — 2026-10-21, çok pod
     * (MP-17). Ön kontrol ({@code existsByDedupeKeyAndUsername…}) gürültüsüz erken çıkıştır; yarışta son söz PostgreSQL'deki
     * kısmi tekil indeksindir ({@code DeferredIndexBuilder.CATALOG}: anahtar + kişi + takım, olaysız, TEST / taşma özeti
     * hariç): aynı anda iki pod aynı anahtarı yazarsa ikincisi "zaten var" sayılır ve push İKİNCİ KEZ kuyruğa girmez.
     * Çağıran bir işlemin içindeyse ekleme AYRI işlemde yapılır — PostgreSQL'de işlem içindeki tekil ihlal çağıranın tüm
     * işlemini geçersiz kılardı (25P02). {@code true} = eklendi.
     */
    boolean insertEventless(UserPushDelivery d) {
        try {
            org.springframework.transaction.PlatformTransactionManager tm = txManager;
            if (tm != null && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.support.TransactionTemplate tt = new org.springframework.transaction.support.TransactionTemplate(tm);
                tt.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tt.executeWithoutResult(st -> deliveryRepo.save(d));
            } else {
                deliveryRepo.save(d);
            }
            return true;
        } catch (Exception e) {
            logRowWriteFailure("olaysız", d, e);
            return false;
        }
    }

    /**
     * Push satırı yazılamadı (2026-10-22, ALERTS-12): YALNIZ tekil ihlal "aynı satır zaten var"dır (DEBUG, sessiz erken çıkış).
     * Başka her bütünlük hatası (PostgreSQL 22001 "value too long", NOT NULL …) eskiden aynı DEBUG satırıyla "zaten var"
     * sayılıyordu — push kayboluyor, iz kalmıyordu. Artık WARN: anahtar, tetik ve kolonuna sığmayan alan(lar) yazılır; sicil
     * YAZILMAZ (sunucu günlüğü yalnız adet / anahtar taşır).
     */
    void logRowWriteFailure(String where, UserPushDelivery d, Exception e) {
        if (isUniqueViolation(e)) {
            log.debug("user-push ({}): aynı bildirim satırı zaten var (anahtar {}) — ikinci kez yazılmadı", where,
                    d == null ? null : d.getDedupeKey());
            return;
        }
        java.util.List<String> over = d == null ? List.of() : d.overlongColumns();
        log.warn("user-push satırı YAZILAMADI ({}; anahtar {}, tetik {}){} — push kuyruğa girmedi: {}", where,
                d == null ? null : d.getDedupeKey(), d == null ? null : d.getTrigger(),
                over.isEmpty() ? "" : " — kolona sığmayan: " + String.join(", ", over), e.toString());
    }

    /** Tekil (unique) ihlal mi — SQLSTATE 23505 (PostgreSQL ve H2) ya da Spring {@code DuplicateKeyException}. */
    static boolean isUniqueViolation(Throwable t) {
        Throwable c = t;
        for (int causeDepth = 0; c != null && causeDepth < com.sitemonitor.util.CauseChain.MAX_DEPTH; causeDepth++, c = c.getCause()) {
            if (c instanceof org.springframework.dao.DuplicateKeyException) return true;
            if (c instanceof java.sql.SQLException se && "23505".equals(se.getSQLState())) return true;
            if (c instanceof org.hibernate.exception.ConstraintViolationException cv && "23505".equals(cv.getSQLState())) return true;
        }
        return false;
    }

    // ── Fırtına push'u (2026-09-28) ────────────────────────────────────────────────────────

    /** Fırtına satırlarının teslimat günlüğündeki türü ve adı (e-postanın "Alarm fırtınası" başlığıyla aynı). */
    static final String STORM_MONITOR_TYPE = "STORM";
    static final String STORM_MONITOR_NAME = "Alarm fırtınası";

    /**
     * Fırtına push'u — bireysel alarm push'unun KANAL kapılarıyla (2026-09-28).
     *
     * <p>Eskiden {@link #enqueueTeamNotice} kullanılıyordu ve o yol hiçbir kanal kapısına bakmıyordu: yönetici
     * Takım A'nın push'unu kapatsa da Takım A HTTP izlemelerinin fırtınası takımın tüm üyelerine gidiyordu. Karar
     * artık {@link #stormBlockReason}'da, bireysel yolun sırasıyla; ret {@code SKIPPED_*} karar satırı olarak
     * yazılır (teslimat günlüğü nedenini söyler).
     *
     * <p><b>Çözüm</b> bireysel RESOLVE'un aynası: alıcılar bu fırtınanın BU takıma giden açılış/tekrar push'unu
     * gerçekten ALANLAR ({@code resolvePrior}); sessiz saat ve saat tavanından muaf; önce SENT yoksa
     * {@code SKIPPED_NO_PRIOR}. Eskiden çözüm "INFO" seviyesiyle yeniden çözümleniyordu: asgari seviyesi INFO'nun
     * üstünde olan gruplar (ör. yöneticiler) "N monitör düştü"yü alıp "düzeldi"yi hiç almıyordu.
     *
     * <p><b>Alarm bağı</b> (2026-10-04): karar verildikten sonra (gönderim satırları ya da karar satırı yazıldı) bildirimin
     * KAPSADIĞI üye alarmlar {@code storm_push_coverage}'a tek batch ile yazılır ({@link StormPushCoverageService#record});
     * alarm detayı / push geçmişim / teslimat günlüğü "bu fırtına push'u hangi alarmı kapsadı" sorusunu buradan cevaplar.
     *
     * @param stormTrigger {@code INITIAL} / {@code DAILY_REALERT} / {@code RESOLVE} (fırtına e-postasının tetiği)
     * @param members      bu takımın SY takımı olduğu fırtına üyeleri — tür ve izleme bayrağı kararı bunlardan
     */
    public Map<String, Object> enqueueStormNotice(Long stormId, Long teamId, String stormTrigger, String alertLevel,
                                                  List<AlertEvent> members, String message) {
        return enqueueStormNotice(stormId, null, teamId, stormTrigger, alertLevel, members, message);
    }

    /**
     * @param legacyStormId üyeleri eski (kuruluş geneli) fırtınadan SESSİZCE taşınmış takım fırtınasında o eski fırtına
     *                      (2026-09-29, O-3 / D-b6): takım açılış push'unu ESKİ kimlikle aldı — çözümün "önceden alanlar"
     *                      listesi onu da sayar, yoksa "düştü"yü alan "düzeldi"yi alamazdı ({@code SKIPPED_NO_PRIOR})
     */
    public Map<String, Object> enqueueStormNotice(Long stormId, Long legacyStormId, Long teamId, String stormTrigger,
                                                  String alertLevel, List<AlertEvent> members, String message) {
        return enqueueStormNoticeLocalized(stormId, legacyStormId, teamId, stormTrigger, alertLevel, members, LocalizedText.of(message));
    }

    /** {@link #enqueueStormNotice} — iki dilli metinle (2026-10-04, öneri 5); karar mantığı aynı. */
    public Map<String, Object> enqueueStormNoticeLocalized(Long stormId, Long legacyStormId, Long teamId, String stormTrigger,
                                                           String alertLevel, List<AlertEvent> members, LocalizedText message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queued", 0); out.put("skipped", 0); out.put("recipients", List.of());
        try {
            if (!enabled()) { out.put("reason", "SKIPPED_DISABLED"); return out; }
            if (stormId == null || teamId == null) { out.put("reason", "SKIPPED_NO_TEAM"); return out; }
            boolean resolve = "RESOLVE".equals(stormTrigger);
            String trigger = resolve ? "STORM_RESOLVED" : "STORM";
            String dedupeKey = UserPushDelivery.fitDedupeKey(stormDedupeKey(stormId, stormTrigger));
            List<String> prior = resolve ? priorStormRecipients(stormId, teamId) : List.of();
            if (resolve && legacyStormId != null) {
                List<String> merged = new ArrayList<>(prior);
                for (String u : priorStormRecipients(legacyStormId, teamId)) if (!merged.contains(u)) merged.add(u);
                prior = merged;
            }
            String block = resolve && prior.isEmpty() ? "SKIPPED_NO_PRIOR"
                    : stormBlockReason(stormTrigger, teamId, alertLevel, members);
            if (block == null) {
                // Kişisel tercihler (2026-10-04): açılış/tekrarda seviye + susturma (çözüm) ve üyelerin aileleri (hiçbiri
                // kişinin listesinde yoksa SKIPPED_USER_TYPE). Çözüm muaf (resolvePrior). Tercih yoksa liste bugünküyle aynı.
                List<UserPushRecipientResolver.Recipient> recipients =
                        resolve ? resolver.resolvePrior(prior)
                                : UserPushRecipientResolver.withFamilies(resolver.resolve(teamId, alertLevel), memberFamilies(members));
                if (!recipients.isEmpty()) {
                    Map<String, Object> res = writeTeamRows(out, teamId, trigger, alertLevel, STORM_MONITOR_TYPE, STORM_MONITOR_NAME,
                            message, dedupeKey, recipients, java.util.Set.of(),
                            resolve || capBypass(alertLevel),   // çözüm tavandan muaf; KRİTİK, ayar açıksa muaf
                            stormPrior(stormId, legacyStormId, dedupeKey), Map.of());
                    recordCoverage(stormId, teamId, dedupeKey, stormTrigger, members);
                    return res;
                }
                block = "SKIPPED_NO_RECIPIENTS";
            }
            stormSkipRow(teamId, trigger, alertLevel, dedupeKey, block);
            recordCoverage(stormId, teamId, dedupeKey, stormTrigger, members);
            out.put("reason", block);
        } catch (Exception e) {
            log.warn("user-push fırtına bildirimi atlandı (fırtına e-postası etkilenmedi): {}", e.toString());
        }
        return out;
    }

    /**
     * Fırtına push'unun KANAL kararı — {@link #channelBlockReason}'ın üye listesi üzerinden eşleniği, AYNI sıra:
     * günlük tekrar ayarı → tür → takım → izleme bayrağı → sessiz saat (aynı seviye muafiyeti; çözümde yok).
     * Tür ve izleme bayrağı üye başınadır: fırtına ancak HİÇBİR üyesi push'a açık değilse susar — açık kalan tek
     * üye bile bireysel yolda push üretirdi.
     */
    private String stormBlockReason(String stormTrigger, Long teamId, String level, List<AlertEvent> members) {
        if ("DAILY_REALERT".equals(stormTrigger)
                && !appSettings.getBoolean("site.monitor.userpush.realert-enabled", true)) return "SKIPPED_REALERT_OFF";
        List<AlertEvent> list = members == null ? List.of() : members;
        List<AlertEvent> typeOn = list.stream()
                .filter(m -> scopeBlockReason(null, MonitorTypeCatalog.typeOfAlert(m.getAlertType())) == null).toList();
        if (!list.isEmpty() && typeOn.isEmpty()) return "SKIPPED_TYPE_OFF";
        String team = scopeBlockReason(teamId, null);
        if (team != null) return team;
        if (!list.isEmpty() && typeOn.stream().allMatch(UserPushService::pushDisabled)) return "SKIPPED_MONITOR_OFF";
        if (!"RESOLVE".equals(stormTrigger) && quietHoursBlock(level)) return "SKIPPED_QUIET_HOURS";
        return null;
    }

    /** Fırtına üyelerinin izleme aileleri (tekil, sıralı); bilinmeyen tür atlanır. Boş = aile bilinmiyor. */
    static List<String> memberFamilies(List<AlertEvent> members) {
        if (members == null) return null;
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (AlertEvent m : members) {
            String f = m == null ? null : MonitorTypeCatalog.typeOfAlert(m.getAlertType());
            if (f != null) out.add(f);
        }
        return out.isEmpty() ? null : new ArrayList<>(out);
    }

    /** İzlemenin push bayrağı KAPALI mı — olayın alarm-anı bağlamındaki {@code push_disabled} damgası. */
    private static boolean pushDisabled(AlertEvent m) {
        String json = m == null ? null : m.getContextJson();
        if (json == null || json.isBlank()) return false;
        try {
            return MAPPER.readTree(json).path("push_disabled").booleanValue();
        } catch (Exception e) {
            return false;   // bozuk bağlam bastırma SAYILMAZ (StormService.mailDisabled ile aynı karar)
        }
    }

    /**
     * Fırtına push dedupe anahtarı. Günlük tekrar GÜNÜ taşır: eskiden {@code storm:<id>:DAILY_REALERT} her gün
     * aynıydı ve 1. günden sonraki her tekrar "zaten kayıtlı" diye sessizce atlanıyordu — bireysel
     * {@code RE_ALERT:<gün>} ile aynı kural (kurum saatiyle gün).
     */
    static String stormDedupeKey(Long stormId, String stormTrigger) {
        if ("RESOLVE".equals(stormTrigger)) return "storm-resolved:" + stormId;
        if ("DAILY_REALERT".equals(stormTrigger))
            return "storm:" + stormId + ":DAILY_REALERT:" + Instant.now().atZone(ZONE).toLocalDate();
        return "storm:" + stormId + ":" + stormTrigger;
    }

    /**
     * Bu fırtına (ya da üyelerinin taşındığı eski fırtına) için bu takıma TOPLU açılış/tekrar push'u gerçekten GİTTİ Mİ
     * (2026-10-03). Bireysel push kipinde toplu çözüm push'u yalnız bu durumda gönderilir: ayar fırtına sürerken açıldıysa
     * "N monitör düştü"yü toplu alan "düzeldi"yi de toplu alır; baştan bireysel kipte açılan fırtınada toplu push hiç
     * yoktur (üyelerin çözüm push'u kendi kapanışında gider). Salt okuma; hata = false.
     */
    public boolean stormNoticeSent(Long stormId, Long legacyStormId, Long teamId) {
        if (stormId == null || teamId == null) return false;
        try {
            if (!priorStormRecipients(stormId, teamId).isEmpty()) return true;
            return legacyStormId != null && !priorStormRecipients(legacyStormId, teamId).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Bu fırtınanın bu takıma giden açılış/tekrar push'unu gerçekten ALMIŞ tekil kullanıcılar (ilk gönderim sırası). */
    private List<String> priorStormRecipients(Long stormId, Long teamId) {
        List<String> out = new ArrayList<>();
        for (UserPushDelivery d : deliveryRepo.findByDedupeKeyStartingWithAndTeamIdAndStatusOrderByIdAsc(
                "storm:" + stormId + ":", teamId, "SENT")) {
            if (d.getUsername() == null || SYSTEM_USER.equals(d.getUsername()) || out.contains(d.getUsername())) continue;
            out.add(d.getUsername());
        }
        return out;
    }

    /**
     * Fırtına push'u ↔ üye alarm bağı (2026-10-04) — isteğe bağlı: yokken (elle kurulan testler) davranış birebir aynı.
     * Alan enjeksiyonu: yapıcı imzası değişmez.
     */
    @Autowired(required = false)
    private StormPushCoverageService stormPushCoverage;

    /** Test kancası. */
    void setStormPushCoverage(StormPushCoverageService s) { this.stormPushCoverage = s; }

    /**
     * Bildirim kararı verildikten SONRA (satırlar yazıldı, outbox tetiklendi) kapsanan üye alarmların bağı — tek batch,
     * hiçbir hata yayılmaz: gözlem kaydı push kararını değiştirmez ve geciktirmez.
     */
    private void recordCoverage(Long stormId, Long teamId, String dedupeKey, String stormTrigger, List<AlertEvent> members) {
        StormPushCoverageService c = stormPushCoverage;
        if (c == null) return;
        try {
            c.record(stormId, teamId, dedupeKey, stormTrigger, members);
        } catch (Exception e) {
            log.warn("Fırtına push kapsamı yazılamadı (fırtına #{}) — bildirim kararı etkilenmedi: {}", stormId, e.toString());
        }
    }

    /** Fırtına karar satırı (sistem sicili) — bireysel {@link #skipRow}'un olaysız eşleniği, takım başına bir kez. */
    private void stormSkipRow(Long teamId, String trigger, String level, String dedupeKey, String reason) {
        try {
            if (deliveryRepo.existsByDedupeKeyAndUsernameAndTeamId(dedupeKey, SYSTEM_USER, teamId)) return;
            UserPushDelivery d = new UserPushDelivery();
            d.setTrigger(trigger);
            d.setDedupeKey(dedupeKey);
            d.setMonitorType(STORM_MONITOR_TYPE);
            d.setMonitorName(STORM_MONITOR_NAME);
            d.setTeamId(teamId);
            d.setAlertLevel(level);
            d.setUsername(SYSTEM_USER);
            d.setDisplayName("(katman kararı)");
            d.setTitle(titleSetting());
            d.setStatus(reason);
            d.setCreatedAt(ISO.format(Instant.now()));
            insertEventless(d);
        } catch (Exception ignored) { /* karar satırı yazılamadıysa gönderim mantığı etkilenmez */ }
    }

    /**
     * Doğrudan alıcı (rol grubu çözümü YOK): kullanıcı adı + görünen ad + opt-out. {@code skipReason} (2026-10-18, teknik hata
     * raporu push'ları): çağıranın kişisel tercih kararı ({@link UserPushRecipientResolver#preferenceSkip} — seviye / susturma);
     * doluysa satır o nedenle yazılır (sessiz kayıp yok). Eski biçimler null verir: davranış bugünküyle aynı.
     */
    public record DirectRecipient(String username, String displayName, boolean optOut, String lang, String skipReason) {
        /** Eski üç alanlı biçim — push Türkçe (bugünkü davranış). */
        public DirectRecipient(String username, String displayName, boolean optOut) {
            this(username, displayName, optOut, PushI18n.TR, null);
        }
        /** Dört alanlı biçim (haftalık rapor müdür push'u) — kişisel tercih süzgeci yok (bugünkü davranış). */
        public DirectRecipient(String username, String displayName, boolean optOut, String lang) {
            this(username, displayName, optOut, lang, null);
        }
        public DirectRecipient {
            lang = PushI18n.norm(lang);
            skipReason = skipReason == null || skipReason.isBlank() ? null : skipReason;
        }
    }

    /**
     * Belirli kullanıcılara DOĞRUDAN bildirim (2026-09-13, haftalık rapor → müdür): alıcılar çağıran
     * tarafından çözülür (rol grubu / asgari seviye uygulanmaz — kişi zaten e-postanın alıcısıdır);
     * opt-out, saatlik tavan, devre kesici ve dedupe aynen. Sessiz saat uygulanmaz (bilgilendirme, alarm değil).
     */
    public Map<String, Object> enqueueDirect(List<DirectRecipient> recipients, Long teamId, String trigger, String alertLevel,
                                             String monitorType, String monitorName, String message, String dedupeKey) {
        return enqueueDirectLocalized(recipients, teamId, trigger, alertLevel, monitorType, monitorName, LocalizedText.of(message), dedupeKey);
    }

    /** {@link #enqueueDirect} — iki dilli metinle (kişinin push diline göre, 2026-10-04). */
    public Map<String, Object> enqueueDirectLocalized(List<DirectRecipient> recipients, Long teamId, String trigger, String alertLevel,
                                                      String monitorType, String monitorName, LocalizedText message, String dedupeKey) {
        dedupeKey = UserPushDelivery.fitDedupeKey(dedupeKey);   // ALERTS-12: yazım ve arama aynı (sığdırılmış) anahtarla
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queued", 0); out.put("skipped", 0); out.put("recipients", List.of()); out.put("queued_usernames", List.of());
        out.put("same_person", Map.of());
        if (!enabled()) { out.put("reason", "SKIPPED_DISABLED"); return out; }
        if (recipients == null || recipients.isEmpty()) { out.put("reason", "SKIPPED_NO_RECIPIENTS"); return out; }
        String batchId = UUID.randomUUID().toString().substring(0, 8);
        String now = ISO.format(Instant.now());
        String since = ISO.format(Instant.now().minus(Duration.ofHours(1)));
        Map<String, String> texts = new java.util.HashMap<>();
        int queued = 0, skipped = 0;
        List<String> names = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String> usernames = new ArrayList<>();
        List<String> queuedUsernames = new ArrayList<>();
        // Aynı kişi (2026-10-21): aynı e-posta + aynı ad taşıyan aktif hesaplardan YALNIZ biri — bu bildirimi (anahtar) zaten
        // almış hesap; yoksa gönderilebilir hesaplar (tavan altı, son giriş, kimlik); hiçbiri gönderilebilir değilse birleştirme
        // yok. Diğerleri SKIPPED_SAME_PERSON; çağıran "kime ulaştı" kararını {@code same_person} haritasıyla verir (kişi başına).
        BaseStatus base = new BaseStatus(overCap(false, since));   // devre kesici açıksa drainOutbox bekletir (N2)
        List<String> candidates = new ArrayList<>();
        for (DirectRecipient r : recipients) {
            if (r == null || r.username() == null || r.username().isBlank()) continue;
            // kişisel tercih (opt-out / seviye / susturma) — satır nedeniyle
            base.skip(r.username().trim(), r.optOut() ? "SKIPPED_USER_OPT_OUT" : r.skipReason());
            candidates.add(r.username().trim());
        }
        PushSamePersonGuard.Plan same = samePlan(candidates, base, keyPrior(dedupeKey));
        Map<String, String> samePersonOut = new LinkedHashMap<>();
        for (DirectRecipient r : recipients) {
            String u = r.username() == null ? "" : r.username().trim();
            if (u.isEmpty() || !seen.add(u)) { skipped++; continue; }
            String chosen = same.chosenFor(u);
            String status = chosen != null ? STATUS_SKIPPED_SAME_PERSON : base.of(u);
            if (dedupeKey != null && deliveryRepo.existsByDedupeKeyAndUsername(dedupeKey, u)) { skipped++; continue; }
            UserPushDelivery d = new UserPushDelivery();
            d.setTrigger(trigger);
            d.setDedupeKey(dedupeKey);
            d.setMonitorType(monitorType == null ? "CERTIFICATE" : monitorType);
            d.setMonitorName(monitorName);
            d.setTeamId(teamId);
            d.setAlertLevel(alertLevel);
            d.setUsername(u);
            d.setDisplayName(r.displayName() == null ? u : r.displayName());
            String lang = r.lang();
            d.setTitle(titleSetting(lang));
            d.setMessage(texts.computeIfAbsent(lang, l -> PushText.truncate(PushText.pushSafe(message.forLang(l)), maxMessageChars())));
            d.setStatus(status);
            d.setSamePersonUsername(chosen);
            d.setCreatedAt(now);
            d.setBatchId(batchIdFor(batchId, lang));
            d.setPushLang(lang);
            if (!insertEventless(d)) { skipped++; continue; }
            if ("PENDING".equals(status)) { queued++; names.add(d.getDisplayName()); queuedUsernames.add(u); }
            else skipped++;
            if (chosen != null) samePersonOut.put(u, chosen);
            usernames.add(u);
        }
        if (queued > 0) worker.execute(this::drainOutbox);
        out.put("queued", queued); out.put("skipped", skipped); out.put("recipients", names); out.put("batch_id", batchId);
        out.put("usernames", usernames);   // çağıran, aynı olayın takım bildiriminden bu kişileri düşer
        // Aynı kişinin öbür hesabı yüzünden atlanan hesap → push'un gittiği hesap (2026-10-21): kişi başına "ulaştı" kararı için
        out.put("same_person", samePersonOut);
        // Kuyruğa GİREN kişiler (2026-10-18): çağıran kişi başına "push ulaştı" kararı verir (teknik hata raporu onay
        // hatırlatması — talep PO başına; satırı atlanan / tekrar sayılan kişi ulaşmış sayılmaz)
        out.put("queued_usernames", queuedUsernames);
        return out;
    }

    // ── Aynı kişi kuralı (2026-10-21) ─────────────────────────────────────────────────────────

    /**
     * Aynı kişinin başka hesabı için karar verildi; bu hesaba ayrıca gönderilmedi: kişinin (aynı e-posta + aynı ad) başka bir
     * hesabı bu push'u aldı ya da alacak — bu hesaba satır yazılır ama push gitmez; satırın {@code samePersonUsername}'i karar
     * verilen hesaptır. Bkz. {@link PushSamePersonGuard}.
     */
    public static final String STATUS_SKIPPED_SAME_PERSON = PushSamePersonGuard.SKIPPED_SAME_PERSON;
    /** Kişinin başka hesabı bu bildirimden bilerek çıkarıldı (müdür push'u, gönderen) — kişi dışarıda tutuldu. */
    public static final String STATUS_SKIPPED_SAME_PERSON_EXCLUDED = PushSamePersonGuard.SKIPPED_SAME_PERSON_EXCLUDED;

    /**
     * Aynı kişi kuralının uygulayıcısı — isteğe bağlı (alan enjeksiyonu: yapıcı imzası değişmez; elle kurulan testlerde yokken
     * davranış birebir bugünkü). Kodla giriş push'u ({@link #sendDirect}) bu kurala tabi değildir.
     */
    @Autowired(required = false)
    private PushSamePersonGuard samePerson;

    /** Test kancası. */
    void setSamePerson(PushSamePersonGuard g) { this.samePerson = g; }

    /**
     * Bir teslimatın hesap başına durumu — kişisel atlama nedeni / {@code RATE_LIMITED} / {@code PENDING}. Hesap başına bir
     * kez hesaplanır: aynı kişi kararı (hangi hesap gönderilebilir, hangisi tavanda) ve satır yazımı aynı değeri okur — saat
     * tavanı sayımı alıcı başına yine tek sorgudur.
     */
    static final class BaseStatus {
        private final Map<String, String> skipByKey = new HashMap<>();
        private final java.util.function.Predicate<String> overCap;
        private final Map<String, String> memo = new HashMap<>();

        BaseStatus(java.util.function.Predicate<String> overCap) {
            this.overCap = overCap;
        }

        /** Hesabın kişisel atlama nedeni (null = yok). İlk kayıt geçerlidir. */
        BaseStatus skip(String username, String reason) {
            if (username != null) skipByKey.putIfAbsent(PushSamePersonGuard.key(username), reason == null ? "" : reason);
            return this;
        }

        String of(String username) {
            return memo.computeIfAbsent(PushSamePersonGuard.key(username), k -> {
                String s = skipByKey.get(k);
                if (s != null && !s.isEmpty()) return s;
                return overCap != null && overCap.test(username) ? "RATE_LIMITED" : "PENDING";
            });
        }

        PushSamePersonGuard.Readiness readiness(String username) {
            String s = of(username);
            if ("PENDING".equals(s)) return PushSamePersonGuard.Readiness.DELIVERABLE;
            if ("RATE_LIMITED".equals(s)) return PushSamePersonGuard.Readiness.OVER_CAP;
            return PushSamePersonGuard.Readiness.BLOCKED;
        }
    }

    /** Saat tavanı sorusu — muafsa hiç sorulmaz. */
    private java.util.function.Predicate<String> overCap(boolean capExempt, String since) {
        return u -> !capExempt && deliveryRepo.countRecentForUser(u, since) >= hourlyCap();
    }

    /** Teslimatın aynı kişi kararı; koruyucu yoksa ya da düşerse boş karar (bugünkü davranış — push kaybolmaz). */
    private PushSamePersonGuard.Plan samePlan(java.util.Collection<String> usernames, BaseStatus base,
                                              PushSamePersonGuard.Prior prior) {
        PushSamePersonGuard g = samePerson;
        if (g == null || usernames == null || usernames.isEmpty()) return PushSamePersonGuard.Plan.NONE;
        try {
            return g.plan(usernames, base == null ? u -> PushSamePersonGuard.Readiness.DELIVERABLE : base::readiness, prior);
        } catch (RuntimeException e) {
            log.warn("user-push aynı kişi kararı verilemedi (birleştirme yok): {}", e.toString());
            return PushSamePersonGuard.Plan.NONE;
        }
    }

    /** Düşülen hesapların kardeş hesapları (aynı kişi) → düşülen hesap; koruyucu yoksa boş. */
    private Map<String, String> samePersonSiblings(java.util.Collection<String> usernames) {
        PushSamePersonGuard g = samePerson;
        if (g == null) return Map.of();
        try {
            return g.siblingsOf(usernames);
        } catch (RuntimeException e) {
            log.warn("user-push aynı kişi kardeş hesapları okunamadı: {}", e.toString());
            return Map.of();
        }
    }

    /** Kanıt satırı — dar izdüşüm {@code [username, dedupeKey, status, createdAt]} (mesaj metni okunmaz). */
    record EvRow(String username, String dedupeKey, String status, String createdAt) {
        static List<EvRow> of(List<Object[]> rows) {
            List<EvRow> out = new ArrayList<>();
            for (Object[] r : rows == null ? List.<Object[]>of() : rows) {
                if (r == null || r.length < 3 || r[0] == null) continue;
                out.add(new EvRow(r[0].toString(), r[1] == null ? null : r[1].toString(), r[2] == null ? null : r[2].toString(),
                        r.length > 3 && r[3] != null ? r[3].toString() : null));
            }
            return out;
        }
    }

    /** Olayın kişi satırları (dar izdüşüm, tek sorgu). Okunamazsa boş — kanıt yok sayılır (bugünkü davranış). */
    private List<EvRow> eventRows(Long eventId) {
        if (eventId == null) return List.of();
        try {
            return EvRow.of(deliveryRepo.findPersonEvidenceByEvent(eventId));
        } catch (RuntimeException e) {
            log.warn("user-push olay satırları okunamadı (olay #{}): {}", eventId, e.toString());
            return List.of();
        }
    }

    /**
     * Alarm teslimatının kanıtı: olayın satırları (tek dar sorgu, yalnız birden çok hesabı olan kişi varsa). Olayın push'u ALAN
     * her satırı "aynı mantıksal olay" sayılır (açılış / eskalasyon / tekrar / çözüm / nöbetçi / adım); {@code exact} bu
     * bildirimin aynısı.
     */
    private PushSamePersonGuard.Prior alarmPrior(Long eventId, java.util.function.Predicate<EvRow> exact) {
        if (eventId == null) return PushSamePersonGuard.Prior.NONE;
        return new PushSamePersonGuard.Prior() {
            private List<EvRow> rows;   // teslimat başına en çok bir okuma (birden çok kişi grubu olsa da)

            @Override
            public Map<String, PushSamePersonGuard.Evidence> of(java.util.Collection<String> names) {
                if (rows == null) rows = eventRows(eventId);
                return evidenceOf(rows, names, exact);
            }
        };
    }

    /** Olaysız takım / doğrudan bildirimin kanıtı: aynı yinelenmezlik anahtarındaki satırlar (= bu bildirimin aynısı). */
    private PushSamePersonGuard.Prior keyPrior(String dedupeKey) {
        if (dedupeKey == null) return PushSamePersonGuard.Prior.NONE;
        return names -> evidenceOf(EvRow.of(deliveryRepo.findPersonEvidenceByKey(dedupeKey, upperAll(names))), names, d -> true);
    }

    /**
     * Fırtına push'unun kanıtı: aynı fırtınanın (ve üyelerin taşındığı eski fırtınanın) açılış / tekrar satırları — günlük tekrar
     * ve çözüm, açılışı alan hesaba gider; {@code exact} bu bildirimin anahtarı (bireysel takım yolunun anahtar × kişi tekilliği).
     */
    private PushSamePersonGuard.Prior stormPrior(Long stormId, Long legacyStormId, String dedupeKey) {
        return names -> {
            List<String> up = upperAll(names);
            List<EvRow> rows = new ArrayList<>(EvRow.of(deliveryRepo.findPersonEvidenceByKeyPrefix(likePrefix("storm:" + stormId + ":"), up)));
            if (legacyStormId != null)
                rows.addAll(EvRow.of(deliveryRepo.findPersonEvidenceByKeyPrefix(likePrefix("storm:" + legacyStormId + ":"), up)));
            if (dedupeKey != null && !dedupeKey.startsWith("storm:" + stormId + ":"))
                rows.addAll(EvRow.of(deliveryRepo.findPersonEvidenceByKey(dedupeKey, up)));   // çözüm anahtarı (storm-resolved:<id>)
            return evidenceOf(rows, names, d -> dedupeKey != null && dedupeKey.equals(d.dedupeKey()));
        };
    }

    /**
     * Satırlar → hesap başına kanıt. YALNIZ push'u alan satırlar sayılır ({@link PushSamePersonGuard#delivering}: SENT /
     * PENDING) — atlama satırları (opt-out, seviye, aynı kişi …), saat tavanı ve hata kanıt değildir; sistem satırı da değildir.
     */
    static Map<String, PushSamePersonGuard.Evidence> evidenceOf(List<EvRow> rows, java.util.Collection<String> names,
                                                               java.util.function.Predicate<EvRow> exact) {
        Set<String> keys = new HashSet<>();
        for (String n : names == null ? List.<String>of() : names) keys.add(PushSamePersonGuard.key(n));
        Map<String, PushSamePersonGuard.Evidence> out = new java.util.HashMap<>();
        for (EvRow d : rows == null ? List.<EvRow>of() : rows) {
            if (d == null || d.username() == null || SYSTEM_USER.equals(d.username())) continue;
            if (!PushSamePersonGuard.delivering(d.status())) continue;
            String k = PushSamePersonGuard.key(d.username());
            if (!keys.contains(k)) continue;
            out.merge(k, new PushSamePersonGuard.Evidence(exact != null && exact.test(d), "SENT".equals(d.status()), true),
                    PushSamePersonGuard.Evidence::or);
        }
        return out;
    }

    /**
     * Koruyucu yokken / karar veremediğinde (sorgu düştü) çözümün AYNI kişi dediği hesaplar arasından seçim (eskalasyon adımı):
     * sıradaki ilk gönderilebilir hesap, yoksa ilk tavandaki; hiçbiri gönderilebilir değilse null (birleştirme yok — her hesap
     * kendi nedenini yazar).
     */
    static UserPushRecipientResolver.Recipient fallbackChoice(List<UserPushRecipientResolver.Recipient> people, BaseStatus base) {
        UserPushRecipientResolver.Recipient overCap = null;
        for (UserPushRecipientResolver.Recipient r : people) {
            PushSamePersonGuard.Readiness rd = base.readiness(r.username());
            if (rd == PushSamePersonGuard.Readiness.DELIVERABLE) return r;
            if (rd == PushSamePersonGuard.Readiness.OVER_CAP && overCap == null) overCap = r;
        }
        return overCap;
    }

    /** Çıkarılan siciller kümesi bu adı içeriyor mu (null güvenli — {@code Set.of().contains(null)} istisna atar). */
    private static boolean containsName(java.util.Collection<String> names, String name) {
        return name != null && names != null && !names.isEmpty() && names.contains(name);
    }

    private static List<String> upperAll(java.util.Collection<String> names) {
        List<String> out = new ArrayList<>();
        for (String n : names == null ? List.<String>of() : names) if (n != null && !n.isBlank()) out.add(PushSamePersonGuard.key(n));
        return out;
    }

    /** LIKE önek deseni ({@code \} kaçış karakteri — sorgudaki ESCAPE ile aynı). */
    static String likePrefix(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    // ── Outbox worker ──────────────────────────────────────────────────────────────────────

    /**
     * Webhook Push Gönderim Logu "Yeniden kuyruğa al" (2026-09-19): FAILED/CIRCUIT_OPEN/RATE_LIMITED satırı PENDING'e
     * çekilir, TEK BAŞINA batch olur (eski batch arkadaşları yeniden gitmesin), deneme sayacı sıfırlanır ve worker
     * hemen tetiklenir. Devre kesici açıksa drainOutbox zaten bekletir — kayıp olmaz.
     */
    public void requeue(UserPushDelivery d) {
        d.setStatus("PENDING");
        d.setAttempts(0);
        d.setError(null);
        d.setHttpStatus(null);
        d.setNextAttemptAt(null);   // elle yeniden kuyruk = hemen (eski backoff damgası beklenmez)
        d.setBatchId("requeue-" + d.getId() + "-" + System.currentTimeMillis());
        deliveryRepo.save(d);
        worker.execute(this::drainOutbox);
    }

    /**
     * Açılış süpürmesi — outbox sözleşmesini gerçekten tutan halka.
     *
     * <p>Sınıf javadoc'u "pod yeniden başlasa da PENDING satırlar durur" diyor; doğru, ama
     * 2026-09-23'e kadar o satırları GERİ ALAN hiçbir tetik yoktu: {@code drainOutbox} yalnız
     * {@code enqueue*} yollarından ve {@code fail()}'in BELLEKTEKİ {@code worker.schedule}
     * timer'ından çağrılıyordu. Prod tek pod ve her sürüm bir restart demek — backoff beklerken
     * ya da yeni yazılmışken yeniden başlayan bir pod, o satırları bir sonraki alarma kadar
     * (sessiz bir gecede saatlerce) askıda bırakıyordu. Satır FAILED bile olmadığı için
     * gönderim logunda "bekliyor" görünüyor, nöbetçinin telefonunda hiçbir şey yok.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(400)
    public void drainOnStartup() {
        worker.execute(this::drainOutbox);
    }

    /**
     * Periyodik ağ süpürmesi: bellekteki backoff timer'ı kaybolduysa (restart) ya da bir tur
     * istisnayla düştüyse kuyruk burada geri alınır. {@code findDuePending} idempotent ve backoff'u
     * bekleyen satırı ALMAZ (damga satırda); kuyruk boşsa tur bedava. Devre kesici açıkken atlanır — cooldown'ı zaten
     * {@code drainOutbox}'ın kendi {@code schedule}'ı bekliyor, üstüne görev yığmayalım.
     */
    @Scheduled(fixedDelayString = "${site.monitor.userpush.outbox-sweep-ms:60000}",
               initialDelayString = "${site.monitor.userpush.outbox-sweep-initial-ms:30000}")
    void sweepOutbox() {
        flushSentUnrecorded();   // ağ geçidinden bağımsız: devre kesici açıkken de sonucu yazılamamış gönderimler kalıcılaşsın
        if (circuitOpen()) return;
        worker.execute(this::drainOutbox);
    }

    /** PENDING satırları batch bazında gönderir. Tek worker — eşzamanlılık yok. */
    void drainOutbox() {
        if (stopping) return;   // kapanış: yeni tur yok — satırlar veritabanında, başka pod alır
        flushSentUnrecorded();
        try {
            // Yalnız ZAMANI GELMİŞ satırlar: fail()'in backoff'u satıra yazılır; süpürme/enqueue/açılış turları
            // bekleyen retry'ı erken göndermesin (2026-09-28). Yeni satırlar (damgasız) hemen gelir.
            List<UserPushDelivery> due = deliveryRepo.findDuePending(ISO.format(Instant.now()), OUTBOX_PAGE);
            if (due.isEmpty()) return;
            if (circuitOpen()) {   // kuyruktakiler BEKLER (kaybolmaz); cooldown sonunda tekrar bak
                worker.schedule(this::drainOutbox, Math.max(1,
                        (circuitOpenUntil - System.currentTimeMillis()) / 1000), TimeUnit.SECONDS);
                return;
            }
            // Çok pod güvenliği (2026-10-01, öneri 3): yalnız BU turun kiraladığı satırlar gönderilir.
            List<UserPushDelivery> claimed = claim(due);
            if (claimed.isEmpty()) return;   // başka bir pod aldı (ya da veritabanı yazmayı reddetti — claim)
            // Gönderilmiş ama sonucu yazılamamış satır YENİDEN GÖNDERİLMEZ (2026-10-09) — yalnız SENT sonucu yazılır.
            List<UserPushDelivery> pending = withoutSentUnrecorded(claimed);
            Map<List<String>, List<UserPushDelivery>> byBatch = new LinkedHashMap<>();
            // Tek istek TEK mesaj taşır (gövde ilk satırın başlık + mesajı): aynı batch'te farklı metin (iki dil) olursa
            // ayrı isteğe bölünür (2026-10-04, öneri 5). Dil başına ayrı batch kimliği zaten verilir; bu savunma katmanıdır.
            for (UserPushDelivery d : pending)
                byBatch.computeIfAbsent(List.of(d.getBatchId() == null ? "solo-" + d.getId() : d.getBatchId(),
                                String.valueOf(d.getTitle()), String.valueOf(d.getMessage())),
                        k -> new ArrayList<>()).add(d);
            // Bu turda işlenen satırları HARİÇ tut: fail() retry edilebilir hatada satırı PENDING
            // bırakıp 30/120 sn backoff PLANLIYOR, ama kuyruk-sonu taraması onları yeniden
            // görüp worker.execute ile GECİKMESİZ tur kuyruklıyordu. Tek-thread worker'da
            // gecikmesiz görev planlanmış görevden önce koşar → aynı batch retryMax tükenene dek
            // milisaniyeler içinde tekrar gönderiliyor, backoff hiçbir zaman uygulanmıyordu
            // (retry-max yükseltilirse tek arıza penceresinde push API'sine ardışık burst).
            // Gönderilmeyen (sonucu bekleyen) satırlar da İŞLENDİ sayılır — yoksa aşağıdaki tarama onları "yeni iş" görüp
            // gecikmesiz tur kuyruklar ve tek iş parçacıklı worker kendini durmadan yeniden çağırırdı.
            Set<Long> handled = new java.util.HashSet<>();
            for (UserPushDelivery d : claimed) if (d.getId() != null) handled.add(d.getId());
            List<List<UserPushDelivery>> batches = new ArrayList<>(byBatch.values());
            for (int i = 0; i < batches.size(); i++) {
                if (stopping) {   // kapanış: kalan batch'ler gönderilmez, kiraları bırakılır (başka pod beklemeden alır)
                    for (int j = i; j < batches.size(); j++) releaseLease(batches.get(j));
                    break;
                }
                // Çok pod (2026-10-21, MP-05): tur kirası 50 satırın SIRALI gönderimini kapsar; dolarsa başka pod satırları
                // yeniden kiralayıp gönderirdi. Her batch'ten hemen önce kira tazelenir, yalnız tazelenen satırlar gider.
                List<UserPushDelivery> mine = renewForSend(batches.get(i));
                if (!mine.isEmpty()) sendBatch(mine);
            }
            // Gönderim sürerken YENİ satır birikmiş olabilir — yalnız onlar için bir tur daha bak.
            boolean freshWork = deliveryRepo.findDuePending(ISO.format(Instant.now()), OUTBOX_PAGE).stream()
                    .anyMatch(d -> d.getId() == null || !handled.contains(d.getId()));
            if (freshWork && !stopping) worker.execute(this::drainOutbox);
        } catch (Exception e) {
            log.warn("user-push outbox taraması düştü (bir sonraki enqueue yeniden dener): {}", e.toString());
        }
    }

    /** TEK toplu istek (K9): batch'in tüm alıcıları tek userIds dizisinde. */
    /** Kira süresi: gönderim süresinin (bağlantı + gövde) iki katı + pay, en az 2 dk. */
    private long claimLeaseSec() {
        if (leaseSecondsOverride > 0) return leaseSecondsOverride;
        return Math.max(120L, totalTimeout() * 2L + 30L);
    }

    /** Test: kira süresi (sn) — 0 = hesaplanan. Çok pod kira testi kısa kira verir. */
    long leaseSecondsOverride = 0;

    /** Tur / batch başına tekil kira damgası (saniye + rastgele kesir; "şimdi" damgasıyla metin olarak sıralı). */
    private String newLease() {
        return leaseStamp(claimLeaseSec());
    }

    /**
     * Tekil kira damgası: şimdi + {@code seconds} (saniye + rastgele kesir). Tur sahiplenmesi, batch tazelemesi ve "gönderildi,
     * sonucu yazılamadı" tutuşu AYNI damga biçimini ve aynı {@code renewLease} CAS'ını kullanır (ikinci inceleme D3).
     */
    static String leaseStamp(long seconds) {
        return ISO.format(Instant.now().plusSeconds(seconds))
                + "." + String.format("%09d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    /**
     * Gönderilmek ÜZERE olan batch'in kirası (ikinci inceleme D3): en az {@link #SENT_UNRECORDED_HOLD_SEC}. POST başarılı olup
     * SENT yazılamazsa satır, bu pod'un ilk tutuş yazımı da düşse (havuz tükenmesi) en az bu süre başka pod'ca yeniden
     * kiralanamaz; tutuş her süpürmede ileri alınır. Bedeli: pod gönderim ORTASINDA çökerse o batch en çok bu kadar gecikir
     * (düzgün kapanışta süren istek sonucuyla biter). Test kısa kira verirse ({@link #leaseSecondsOverride}) o kullanılır.
     */
    long inFlightLeaseSec() {
        if (leaseSecondsOverride > 0) return leaseSecondsOverride;
        return Math.max(claimLeaseSec(), SENT_UNRECORDED_HOLD_SEC);
    }

    /**
     * Batch'in kirasını gönderimden HEMEN ÖNCE tazeler (2026-10-21, çok pod — MP-05) ve yalnız tazelenen satırları döner.
     * Kimliksiz satır (yalnız birim testlerinin kayıtsız taklitleri) kiralanmamıştır, olduğu gibi döner. Tazeleme sorgusu
     * düşerse batch GÖNDERİLMEZ (kira dolunca herhangi bir pod alır — gecikme, çift gönderim değil).
     */
    List<UserPushDelivery> renewForSend(List<UserPushDelivery> batch) {
        Map<String, List<UserPushDelivery>> byLease = new LinkedHashMap<>();
        List<UserPushDelivery> out = new ArrayList<>(batch.size());
        for (UserPushDelivery d : batch) {
            if (d.getId() == null || d.getNextAttemptAt() == null) out.add(d);   // kiralanmamış (kimliksiz) satır
            else byLease.computeIfAbsent(d.getNextAttemptAt(), k -> new ArrayList<>()).add(d);
        }
        for (Map.Entry<String, List<UserPushDelivery>> e : byLease.entrySet()) {
            List<UserPushDelivery> rows = e.getValue();
            List<Long> ids = rows.stream().map(UserPushDelivery::getId).toList();
            String lease = leaseStamp(inFlightLeaseSec());
            int renewed;
            try {
                renewed = deliveryRepo.renewLease(ids, e.getKey(), lease);
            } catch (Exception ex) {
                leaseRenewErrors.incrementAndGet();
                log.warn("user-push kira tazelenemedi ({} satır) — bu batch GÖNDERİLMİYOR (kira dolunca yeniden alınır): {}",
                        ids.size(), ex.toString());
                continue;
            }
            if (renewed <= 0) {
                leaseRenewLost.incrementAndGet();
                log.info("user-push: {} satırın kirası başka pod'a geçmiş — bu pod göndermiyor", ids.size());
                continue;
            }
            List<UserPushDelivery> kept = rows;
            if (renewed < ids.size()) {
                leaseRenewLost.incrementAndGet();
                Set<Long> stillMine = new java.util.HashSet<>();
                try {
                    for (UserPushDelivery x : deliveryRepo.findByIdInAndNextAttemptAtOrderByIdAsc(ids, lease)) stillMine.add(x.getId());
                } catch (Exception ex) {
                    log.warn("user-push tazelenen satırlar okunamadı — bu batch GÖNDERİLMİYOR: {}", ex.toString());
                    continue;
                }
                kept = rows.stream().filter(d -> stillMine.contains(d.getId())).toList();
                log.info("user-push: {} / {} satırın kirası başka pod'a geçmiş — yalnız kalanlar gönderiliyor",
                        ids.size() - kept.size(), ids.size());
            }
            for (UserPushDelivery d : kept) {
                d.setNextAttemptAt(lease);
                out.add(d);
            }
        }
        return out;
    }

    /** Gönderilmeyecek batch'in kirasını bırakır (kapanış) — hata yutulur: kira kendiliğinden dolar. */
    private void releaseLease(List<UserPushDelivery> batch) {
        Map<String, List<Long>> byLease = new LinkedHashMap<>();
        for (UserPushDelivery d : batch) {
            if (d.getId() != null && d.getNextAttemptAt() != null)
                byLease.computeIfAbsent(d.getNextAttemptAt(), k -> new ArrayList<>()).add(d.getId());
        }
        for (Map.Entry<String, List<Long>> e : byLease.entrySet()) {
            try {
                deliveryRepo.releaseLease(e.getValue(), e.getKey());
            } catch (Exception ex) {
                log.debug("user-push kira bırakılamadı ({} satır, kira dolunca alınır): {}", e.getValue().size(), ex.toString());
            }
        }
    }

    /**
     * Zamanı gelmiş satırları bu tur için kiralar ({@link UserPushDeliveryRepository#claimDue}) ve YALNIZ kiralananları
     * döner — iki pod aynı satırı göndermez. Kira damgası tur başına tekildir (saniye + rastgele kesir; metin
     * karşılaştırması {@code findDuePending}'in "şimdi" damgasıyla sıralı kalır). Sahiplenme sorgusu düşerse bu tur HİÇBİR
     * şey göndermez (2026-10-21, çok pod — MP-05; eskiden okunan satırlar gönderiliyordu ve başka pod aynı satırları
     * kiralayıp gönderebiliyordu): satır PENDING kalır, sonraki tur alır — push gecikir, iki kez gitmez. Kira yalnız turun
     * ilk batch'ine kadar geçerli sayılmaz: her batch gönderimden hemen önce {@link #renewForSend} ile tazelenir.
     *
     * <p>Yazma reddi (2026-10-09, {@link #writeRejected} — ör. PostgreSQL yük devri sonrası salt-okunur replika, SQLSTATE
     * 25006) ayrıca uyarılır: okunan satırlar gönderilseydi push API 2xx döner, sonucu yazmak da reddedilir, satır PENDING
     * kalıp her süpürmede aynı push yeniden giderdi. Gönderilmeyen satır kaybolmaz — veritabanı yazılabilir olunca ilk tur
     * gönderir.
     */
    List<UserPushDelivery> claim(List<UserPushDelivery> due) {
        List<Long> ids = due.stream().map(UserPushDelivery::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return due;
        Instant now = Instant.now();
        String lease = newLease();
        try {
            if (deliveryRepo.claimDue(ids, ISO.format(now), lease) == 0) return List.of();
            return deliveryRepo.findByIdInAndNextAttemptAtOrderByIdAsc(ids, lease);
        } catch (Exception e) {
            if (writeRejected(e)) {
                log.warn("user-push: veritabanı yazmayı reddediyor (salt-okunur?) — {} satır bu tur GÖNDERİLMİYOR, "
                        + "sonuç yazılamayacağı için yeniden gönderim döngüsü olurdu: {}", ids.size(), e.toString());
                return List.of();
            }
            // Çok pod (2026-10-21, MP-05): sahiplenme düşerse HİÇBİR şey gönderilmez — eskiden okunan (kiralanmamış) satırlar
            // gönderiliyordu; aynı anda başka pod onları kiralayıp gönderebiliyordu. Satır PENDING kalır, sonraki tur alır
            // (push en çok bir süpürme aralığı ≈ 60 sn gecikir, iki kez gitmez).
            log.warn("user-push sahiplenme yapılamadı ({} satır) — bu tur gönderilmiyor (sonraki tur yeniden dener): {}",
                    ids.size(), e.toString());
            return List.of();
        }
    }

    /**
     * Hata zincirinde bir YAZMA REDDİ var mı (2026-10-09): SQLSTATE sınıfı 25 ("geçersiz işlem durumu"; 25006 =
     * read_only_sql_transaction — PostgreSQL salt-okunur replika / {@code default_transaction_read_only}), H2'nin
     * salt-okunur veritabanı kodu 90097 ya da sürücünün salt-okunur iletisi. Bağlantı/sorgu hataları DEĞİL — onlarda
     * {@link #claim} eski yolu korur.
     */
    static boolean writeRejected(Throwable e) {
        int causeDepth = 0;   // neden zinciri tavanı: A→B→A döngüsü sonsuza dek dönmesin
        for (Throwable t = e; t != null && causeDepth++ < com.sitemonitor.util.CauseChain.MAX_DEPTH; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("25") || "90097".equals(state))) return true;
            }
            String m = t.getMessage();
            if (m != null) {
                String lm = m.toLowerCase(java.util.Locale.ROOT);
                if (lm.contains("read-only transaction") || lm.contains("read only transaction")
                        || lm.contains("database is read only")) return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    /**
     * Gönderildi ama sonucu yazılamadı: SENT sonucunun yeniden yazımı için gereken alanlar + kayıt anı + satırın şu anki kira
     * damgası ({@code lease}; bu pod'un tuttuğu, null = bilinmiyor).
     */
    record SentUnrecorded(Integer attempts, Integer httpStatus, String sentAt, long atMs, String lease) {
        SentUnrecorded(Integer attempts, Integer httpStatus, String sentAt, long atMs) {
            this(attempts, httpStatus, sentAt, atMs, null);
        }

        SentUnrecorded withLease(String l) {
            return new SentUnrecorded(attempts, httpStatus, sentAt, atMs, l);
        }
    }

    /**
     * Sonucu yazılamamış gönderimin satır kirası bu kadar ileri alınır ve her süpürmede (≈ 60 sn) yeniden ileri alınır
     * (2026-10-21, çok pod — inceleme F4): kira dolup başka pod satırı yeniden kiralayıp AYNI push'u ikinci kez göndermesin.
     * Pod ölürse kira en geç bu sürede düşer ve satır yeniden gönderilir (pod ölümünde en az bir kez — bilinçli).
     */
    static final long SENT_UNRECORDED_HOLD_SEC = 10L * 60;

    /**
     * Push API'nin KABUL ETTİĞİ ama sonucu (tam kayıt + dar güncelleme) yazılamamış satırlar (2026-10-09). Satır
     * veritabanında PENDING kaldığından sonraki turlar onu yeniden gönderiyordu (kişiye aynı push dakikada bir). Bu pod
     * böyle bir satırı YENİDEN GÖNDERMEZ; her turda yalnız SENT sonucunu yazmayı dener, yazınca kaydı siler. Bellekte ve
     * tavanlı ({@link #SENT_UNRECORDED_MAX}); {@link #SENT_UNRECORDED_TTL_MS} sonra düşer (sonuç hâlâ yazılamıyorsa satır
     * en çok o aralıkta bir yeniden gönderilir). Başka pod'un kiraladığı satırı bilmez — çok pod'da sahiplenme korur.
     */
    final Map<Long, SentUnrecorded> sentUnrecorded = new java.util.concurrent.ConcurrentHashMap<>();
    static final int SENT_UNRECORDED_MAX = 10_000;
    static final long SENT_UNRECORDED_TTL_MS = 24L * 60 * 60 * 1000;

    /** Sonucu yazılamamış gönderilmiş satırları ayıklar; onlar için SENT sonucunu yeniden yazmayı dener. */
    List<UserPushDelivery> withoutSentUnrecorded(List<UserPushDelivery> rows) {
        if (sentUnrecorded.isEmpty()) return rows;
        long nowMs = System.currentTimeMillis();
        pruneSentUnrecorded(nowMs);
        List<UserPushDelivery> out = new ArrayList<>(rows.size());
        for (UserPushDelivery d : rows) {
            SentUnrecorded s = d.getId() == null ? null : sentUnrecorded.get(d.getId());
            if (s == null) { out.add(d); continue; }
            try {
                deliveryRepo.updateOutcome(d.getId(), "SENT", s.attempts(), s.httpStatus(), null, s.sentAt(), null);
                sentUnrecorded.remove(d.getId());
                log.info("user-push satır #{} daha önce gönderilmişti — yeniden GÖNDERİLMEDİ, SENT sonucu şimdi yazıldı", d.getId());
            } catch (Exception ex) {
                log.warn("user-push satır #{} gönderilmiş ama sonucu hâlâ yazılamıyor — yeniden gönderilmiyor: {}",
                        d.getId(), ex.toString());
            }
        }
        return out;
    }

    /** Gönderilmiş satırın sonucu yazılamadı — sonraki turların yeniden göndermemesi için hatırla (tavanlı). */
    private void rememberSentUnrecorded(UserPushDelivery d, String lease) {
        if (d.getId() == null) return;
        if (sentUnrecorded.size() >= SENT_UNRECORDED_MAX) {
            log.warn("user-push: sonucu yazılamamış gönderim kaydı tavanda ({}) — satır #{} hatırlanamadı", SENT_UNRECORDED_MAX, d.getId());
            return;
        }
        SentUnrecorded s = new SentUnrecorded(d.getAttempts(), d.getHttpStatus(), d.getSentAt(), System.currentTimeMillis(), lease);
        sentUnrecorded.put(d.getId(), s);
        holdLease(d.getId(), s);   // hemen: kira dolmadan başka pod satırı yeniden almasın
    }

    /**
     * Sonucu yazılamamış gönderimleri kalıcılaştırır (2026-10-21, çok pod — inceleme F4): her süpürme / turda önce SENT
     * yazılmaya çalışılır; yazılamazsa satırın kirası (hâlâ bu pod'unsa) ileri alınır — satır BAŞKA pod'ca yeniden
     * kiralanıp ikinci kez gönderilmesin. Eskiden SENT yalnız bu pod satırı kira dolunca yeniden kiraladığında yazılıyordu;
     * kira dolunca iki pod'un süpürmesi yarışıyor, öbür pod kazanırsa aynı push yeniden gidiyordu.
     */
    void flushSentUnrecorded() {
        if (sentUnrecorded.isEmpty()) return;
        long nowMs = System.currentTimeMillis();
        pruneSentUnrecorded(nowMs);
        for (Map.Entry<Long, SentUnrecorded> e : new ArrayList<>(sentUnrecorded.entrySet())) {
            Long id = e.getKey();
            SentUnrecorded s = e.getValue();
            try {
                deliveryRepo.updateOutcome(id, "SENT", s.attempts(), s.httpStatus(), null, s.sentAt(), null);
                sentUnrecorded.remove(id, s);
                log.info("user-push satır #{}: gönderilmiş push'un SENT sonucu şimdi yazıldı (yeniden gönderilmedi)", id);
            } catch (Exception ex) {
                holdLease(id, s);
            }
        }
    }

    /**
     * {@link #SENT_UNRECORDED_TTL_MS}'den eski kayıtları düşürür — SESSİZ DEĞİL (ikinci inceleme D3): düşen her kayıt ERROR
     * satırı bırakır (kimlik + gönderim anı); satırın SENT sonucu 24 saattir yazılamıyor, tutuş bırakıldığı için kira dolunca
     * satır YENİDEN GÖNDERİLEBİLİR. @return düşen kayıt sayısı
     */
    int pruneSentUnrecorded(long nowMs) {
        int dropped = 0;
        for (Map.Entry<Long, SentUnrecorded> e : new ArrayList<>(sentUnrecorded.entrySet())) {
            SentUnrecorded x = e.getValue();
            if (nowMs - x.atMs() <= SENT_UNRECORDED_TTL_MS) continue;
            if (!sentUnrecorded.remove(e.getKey(), x)) continue;
            dropped++;
            if (dropped <= 20) {
                log.error("user-push satır #{}: gönderilmiş push'un SENT sonucu {} saattir yazılamadı (gönderim {}) — izden "
                        + "düşürüldü; kira dolunca satır YENİDEN GÖNDERİLEBİLİR", e.getKey(), SENT_UNRECORDED_TTL_MS / 3_600_000L,
                        x.sentAt());
            }
        }
        if (dropped > 20) log.error("user-push: toplam {} sonucu yazılamamış gönderim kaydı düşürüldü (ilk 20'si yukarıda)", dropped);
        return dropped;
    }

    /** Satırın kirasını (hâlâ bu pod'un damgasındaysa) ileri alır; hata yutulur (sonraki süpürme yeniden dener). */
    private void holdLease(Long id, SentUnrecorded s) {
        if (id == null || s == null || s.lease() == null) return;
        String next = leaseStamp(SENT_UNRECORDED_HOLD_SEC);
        try {
            int n = deliveryRepo.renewLease(List.of(id), s.lease(), next);
            if (n == 1) {
                sentUnrecorded.replace(id, s, s.withLease(next));
            } else {
                leaseRenewLost.incrementAndGet();
                // Kira artık bu pod'un değil (başka pod aldı ya da satır sonuçlandı) — ileri alınacak bir şey yok.
                sentUnrecorded.replace(id, s, s.withLease(null));
                log.warn("user-push satır #{}: sonucu yazılamamış gönderimin kirası artık bu pod'da değil", id);
            }
        } catch (Exception ex) {
            leaseRenewErrors.incrementAndGet();
            log.debug("user-push satır #{}: kira ileri alınamadı (sonraki süpürme yeniden dener): {}", id, ex.toString());
        }
    }

    private void sendBatch(List<UserPushDelivery> rows) {
        List<String> userIds = rows.stream().map(UserPushDelivery::getUsername).distinct().toList();
        UserPushDelivery first = rows.get(0);
        String body;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();   // alan SIRASI API sözleşmesine sadık
            // Kanal ISO-8859-9 (PushText): alarm hattının başlığı da süzülür (L-P3) — yönetici başlığındaki "—" telefonda "?" oluyordu
            payload.put("title", PushText.pushSafe(first.getTitle() == null ? titleSetting() : first.getTitle()));
            payload.put("message", first.getMessage());
            payload.put("pipeline", appSettings.getString("site.monitor.userpush.pipeline", ""));
            payload.put("userIds", userIds);
            body = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            fail(rows, null, "gövde kurulamadı: " + e, false);
            return;
        }
        String targetUrl = url();
        if (targetUrl.isBlank()) { fail(rows, null, "URL ayarlanmamış", false); return; }

        // GÖNDERİM hatası ile SONUCU YAZMA hatası AYRI (2026-09-28): eskiden 2xx sonrası saveAll da aynı try'daydı;
        // kayıt düşünce (ör. 60 karakteri aşan notificationId) catch fail(retryable) çağırıyor, fail()'in kaydı da
        // aynı alan yüzünden düşüyor, satır PENDING + eski sayaçla kalıp HER süpürmede (dakikada bir) kullanıcıya
        // yeniden gidiyordu. Yalnız isteğin kendisi başarısızsa yeniden denenir.
        HttpResponse<java.io.InputStream> resp;
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(totalTimeout()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            for (String[] h : headerPairs()) req.header(h[0], h[1]);
            // Yanıt TAVANLI okunur: ofString() hedefin gönderdiği HER ŞEYİ belleğe alıyordu. Bize
            // yalnız notificationId (birkaç bayt) ve günlüğe yazılacak ilk MAX_RAW_RESPONSE karakter
            // lazım; tek pod'da sınırsız okuma gereksiz bir OOM yüzeyi. Tavanda kesilen gövde
            // ayrıştırılamazsa notificationId null kalır — gönderim YİNE başarılıdır.
            resp = client().send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            fail(rows, null, explain(e), true);
            return;
        }
        // Gövde SÜRE sınırıyla (prod kapısı 2026-09-25, N1): tek iş parçacıklı push worker'ı, başlığı gönderip
        // gövdeyi bitirmeyen bir API yanıtında SÜRESİZ bekliyor ve tüm push kanalı duruyordu. Süre dolarsa
        // okunan kısım kullanılır — başarı durum koduyla belli; gövde yalnız notificationId + günlük içindir
        // (okunamaması da sonucu değiştirmez: 2xx geldiyse push gitmiştir, yeniden gönderilmez).
        String bodyText;
        try {
            bodyText = new String(com.sitemonitor.util.HttpBodies.readPreview(resp.body(), MAX_RESPONSE_BYTES,
                    totalTimeout() * 1000L, "Push"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            bodyText = "";
        }
        String raw = bodyText.length() > MAX_RAW_RESPONSE ? bodyText.substring(0, MAX_RAW_RESPONSE) : bodyText;

        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            consecutiveFailures.set(0);
            // notificationId — kanıt zinciri: API bu push'a bu numarayı verdi. Ayrıştırılamazsa
            // gönderim YİNE başarılıdır (SENT + null) — kimlik alınamadı diye FAILED yazılmaz.
            String notificationId = null;
            try {
                JsonNode n = MAPPER.readTree(bodyText);
                if (n.hasNonNull("notificationId")) notificationId = n.get("notificationId").asText();
            } catch (Exception ignored) { }
            // Kolon sınırına kırpılır (rawResponse gibi): uzun kimlik satır kaydını düşürüyordu.
            if (notificationId != null && notificationId.length() > MAX_NOTIFICATION_ID)
                notificationId = notificationId.substring(0, MAX_NOTIFICATION_ID);
            String sentAt = ISO.format(Instant.now());
            Map<Long, String> leases = new HashMap<>();   // sonuç yazılamazsa kirayı tutabilmek için (inceleme F4)
            for (UserPushDelivery d : rows) {
                if (d.getId() != null && d.getNextAttemptAt() != null) leases.put(d.getId(), d.getNextAttemptAt());
                d.setStatus("SENT");
                d.setHttpStatus(resp.statusCode());
                d.setSentAt(sentAt);
                d.setAttempts(d.getAttempts() == null ? 1 : d.getAttempts() + 1);
                d.setNextAttemptAt(null);
                d.setNotificationId(notificationId);   // tek istek → tek numara, TÜM alt satırlara
                d.setRawResponse(raw);
            }
            saveOutcome(rows, leases);
            log.info("user-push gönderildi: {} alıcı (HTTP {})", userIds.size(), resp.statusCode());
        } else {
            fail(rows, resp.statusCode(), "HTTP " + resp.statusCode() + " — " + raw, true);
        }
    }

    /**
     * Gönderim sonucunu yazar; tam kayıt DÜŞERSE durum/sayaç/zamanlar dar güncellemeyle yine yazılır
     * ({@code updateOutcome} — kimlik/ham yanıt gibi sorunlu olabilecek alanlara dokunmaz). SENT satırı
     * PENDING kalıp yeniden gönderilmesin, başarısız satırın deneme sayacı da ilerlesin diye (2026-09-28).
     */
    private void saveOutcome(List<UserPushDelivery> rows) {
        saveOutcome(rows, Map.of());
    }

    /** {@link #saveOutcome(List)} — {@code leases}: gönderilen satırların kira damgası (sonuç yazılamazsa kira tutulur). */
    private void saveOutcome(List<UserPushDelivery> rows, Map<Long, String> leases) {
        try {
            deliveryRepo.saveAll(rows);
        } catch (Exception e) {
            log.warn("user-push sonucu tam kaydedilemedi ({} satır) — durum dar güncellemeyle yazılıyor: {}",
                    rows.size(), e.toString());
            for (UserPushDelivery d : rows) {
                if (d.getId() == null) continue;
                try {
                    deliveryRepo.updateOutcome(d.getId(), d.getStatus(), d.getAttempts(), d.getHttpStatus(),
                            d.getError(), d.getSentAt(), d.getNextAttemptAt());
                } catch (Exception ex) {
                    log.warn("user-push satır #{} durumu yazılamadı: {}", d.getId(), ex.toString());
                    // Gönderildi ama PENDING kaldı (2026-10-09): sonraki turlar yeniden göndermesin — withoutSentUnrecorded.
                    if ("SENT".equals(d.getStatus())) rememberSentUnrecorded(d, leases.get(d.getId()));
                }
            }
        }
    }

    /**
     * Teslimat günlüğüne yazılan hata metni. Ham istisna operatöre ne yapacağını söylemiyor —
     * PKIX/hostname/timeout gibi TANIDIK arızalarda tek cümlelik yön verilir. Metin satırda
     * saklandığı için ekrandan okunur; ham istisna da korunur (teşhis kaybolmasın).
     */
    static String explain(Exception e) {
        String raw = e.toString();
        String hint = null;
        if (raw.contains("PKIX path building failed") || raw.contains("SSLHandshakeException")) {
            hint = "TLS güven zinciri kurulamadı — Ayarlar → Genel'deki kurumsal CA paketine "
                    + "(site.monitor.trust.ca-bundle-pem) API'nin kök/ara CA'sını ekleyin.";
        } else if (raw.contains("CertificateException") && raw.contains("No subject alternative")) {
            hint = "Sertifika host adıyla uyuşmuyor — URL'deki host sertifikadaki SAN ile aynı olmalı.";
        } else if (raw.contains("HttpConnectTimeoutException") || raw.contains("ConnectException")) {
            hint = "Bağlantı kurulamadı — adres/port doğru mu, pod'dan bu hedefe çıkış açık mı?";
        } else if (raw.contains("HttpTimeoutException")) {
            hint = "Yanıt zaman aşımına uğradı — timeout ayarını yükseltmeyi ya da API tarafını "
                    + "kontrol etmeyi deneyin.";
        }
        return hint == null ? raw : hint + " (" + raw + ")";
    }

    /** Hata işleme: tavanlı retry (PENDING kalır + backoff'la yeniden dene) ya da kalıcı FAILED. */
    private void fail(List<UserPushDelivery> rows, Integer httpStatus, String error, boolean retryable) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= circuitThreshold()) {
            circuitOpenUntil = System.currentTimeMillis() + circuitCooldownSec() * 1000L;
            log.warn("user-push devre kesici AÇILDI ({} ardışık hata) — {} sn susuluyor",
                    failures, circuitCooldownSec());
        }
        List<Integer> backoff = backoffSeconds();
        Instant now = Instant.now();
        long minDelay = Long.MAX_VALUE;
        for (UserPushDelivery d : rows) {
            int attempts = (d.getAttempts() == null ? 0 : d.getAttempts()) + 1;
            d.setAttempts(attempts);
            d.setHttpStatus(httpStatus);
            d.setError(error != null && error.length() > 500 ? error.substring(0, 500) : error);
            if (retryable && attempts <= retryMax()) {
                d.setStatus("PENDING");   // kuyrukta kalır; backoff sonrası yeniden denenir
                int delay = backoff.get(Math.min(Math.max(0, attempts - 1), backoff.size() - 1));
                // Backoff SATIRA yazılır: tarama (findDuePending) bu damgadan önce satırı almaz. Eskiden yalnız
                // bellekteki timer vardı; 60 sn süpürmesi / her yeni kuyruk satırı retry'ı hemen yeniden gönderiyor,
                // ~2 dk'lık bir API kesintisi o penceredeki HER push'u kalıcı FAILED yapıyordu (2026-09-28).
                d.setNextAttemptAt(ISO.format(now.plusSeconds(delay)));
                minDelay = Math.min(minDelay, delay);
            } else {
                d.setStatus("FAILED");
                d.setNextAttemptAt(null);
            }
        }
        saveOutcome(rows);
        // Kapanışta yeniden deneme planlanmaz (worker yeni iş almaz) — satır backoff damgasıyla durur, başka pod alır
        if (minDelay != Long.MAX_VALUE && !stopping) worker.schedule(this::drainOutbox, minDelay, TimeUnit.SECONDS);
        log.warn("user-push gönderilemedi ({} alıcı): {}", rows.size(), error);
    }

    // ── Yardımcılar ────────────────────────────────────────────────────────────────────────

    /**
     * Giden istemci — kurumsal TLS güveniyle.
     *
     * <p><b>2026-08-28 prod hatası:</b> düz {@code HttpClient.newBuilder()} yalnız JVM cacerts'e
     * bakıyordu; bildirim API'si kurumsal bir CA ile imzalı olduğundan her gönderim
     * {@code PKIX path building failed} ile düşüyordu (üç deneme de aynı). Kanal bağımsızlığı
     * sayesinde mail etkilenmedi ama push HİÇ gitmedi.
     *
     * <p>Çözüm YENİ kod değil, projedeki hazır zincir: cacerts → admin'in yapıştırdığı kurumsal
     * CA paketi ({@code site.monitor.trust.ca-bundle-pem}, canlı reload) → host'un otomatik
     * pinlenmiş CA'sı (TOFU). RDAP/.tr-whois/HTTP monitör istemcileri de bunu kullanıyor —
     * kurumsal CA bundle'a elle girilmemiş olsa bile auto-pin devreye girer.
     *
     * <p>Çıkış YOLU değişmedi (K3): proxy'ye girilmez, API iç ağdadır.
     */
    private HttpClient client() {
        int ct = connectTimeout();
        HttpClient cached = httpClient;
        if (cached != null && ct == httpClientTimeout) return cached;
        HttpClient stale;
        HttpClient fresh;
        synchronized (clientLock) {
            if (httpClient != null && ct == httpClientTimeout) return httpClient;
            stale = httpClient;
            HttpClient.Builder b = HttpClient.newBuilder()
                    .proxy(HttpClient.Builder.NO_PROXY)
                    .connectTimeout(Duration.ofSeconds(ct));
            SSLContext ssl = trustEvaluator.pinAwareOutboundSslContext(
                    caAutoPinService::trustManagerForHost, (h, prt) -> caAutoPinService.recordTrustFailure("user-push", h, prt));
            if (ssl != null) b.sslContext(ssl);   // null = kurulamadı → varsayılan güvene düş
            fresh = b.build();
            httpClient = fresh;
            httpClientTimeout = ct;
        }
        // close() uçuşan istekleri BEKLER → kilit DIŞINDA kapatılır (HttpCheckerService'teki D3 dersi).
        if (stale != null) try { stale.close(); } catch (Exception ignore) { /* best-effort */ }
        return fresh;
    }

    /** Ayarlardaki başlık listesi: [{name, value(şifreli), secret}] → çözülmüş ad-değer çiftleri. */
    private List<String[]> headerPairs() {
        String json = appSettings.getString("site.monitor.userpush.headers", "");
        List<String[]> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (arr.isArray()) for (JsonNode n : arr) {
                String name = n.path("name").asText("");
                if (name.isBlank() || "content-type".equalsIgnoreCase(name)) continue;
                String value = n.path("value").asText("");
                if (n.path("secret").asBoolean(false) && !value.isBlank()) value = secretCipher.decrypt(value);
                out.add(new String[]{name, value});
            }
        } catch (Exception e) {
            log.warn("user-push başlıkları ayrıştırılamadı: {}", e.toString());
        }
        return out;
    }

    private boolean scopeEnabled(String type, String key) {
        if (key == null) return true;
        return scopeRepo.findByScopeTypeAndScopeKey(type, key)
                .map(UserPushScope::getEnabled).orElse(true);   // kayıt yok = AÇIK (vars.)
    }

    /** E2 sessiz saatler: pencere içinde yalnız min seviye ve üstü geçer. */
    boolean quietHoursBlock(String level) {
        QuietWindow w = quietWindow();
        return w != null && w.contains(LocalTime.now(ZONE)) && w.blocks(level);
    }

    /**
     * Sessiz saat penceresi — gönderim ({@link #quietHoursBlock}) ve senaryo ({@link #scenarioChannel}) ortak okur.
     * Boş, bozuk ya da sıfır uzunluklu ayar = pencere YOK ({@code null}): bozuk ayar bildirimi ENGELLEMESİN.
     */
    record QuietWindow(LocalTime start, LocalTime end, String minLevel) {
        boolean contains(LocalTime now) {
            return start.isBefore(end) ? (!now.isBefore(start) && now.isBefore(end))
                    : (!now.isBefore(start) || now.isBefore(end));   // gece devrilen pencere (22:00-07:00)
        }
        /** Pencere içindeyken bu seviye susturulur mu (asgari seviyenin altında mı). */
        boolean blocks(String level) {
            return UserPushRecipientResolver.levelValue(level) < UserPushRecipientResolver.levelValue(minLevel);
        }
    }

    QuietWindow quietWindow() {
        String start = appSettings.getString("site.monitor.userpush.quiet-start", "");
        String end = appSettings.getString("site.monitor.userpush.quiet-end", "");
        if (start == null || end == null || start.isBlank() || end.isBlank()) return null;
        try {
            LocalTime s = LocalTime.parse(start), e = LocalTime.parse(end);
            // Y20: start == end (ör. "22:00"-"22:00") gece-devrilen dalda (!before(s) || before(e))
            // totolojiye dönüp 24 saat susturuyordu; sıfır uzunluklu pencere = pencere YOK.
            if (s.equals(e)) return null;
            // Ham değer (boş dize → levelValue 1 → hiçbir seviye susturulmaz) — gönderimin eski davranışı birebir.
            return new QuietWindow(s, e, appSettings.getString("site.monitor.userpush.quiet-min-level", "CRITICAL"));
        } catch (Exception ex) {
            return null;   // bozuk pencere ayarı bildirimi ENGELLEMESİN
        }
    }

    /**
     * Devre kesici açık mı. Kuyruğa YAZMA kararını ETKİLEMEZ (prod kapısı 2026-09-25, N2): kesici açıkken
     * doğan satırlar eskiden {@code CIRCUIT_OPEN} yazılıyordu ve drain yalnız PENDING okuduğu için kalıcı
     * düşüyordu — bildirim API'si bir dakika düşünce o 5 dakikada açılan HER alarmın push'u (çoğu zaman
     * kesintinin kendisininki de) API ayağa kalksa bile gitmiyordu. Artık satır PENDING yazılır ve
     * {@link #drainOutbox} cooldown bitene dek bekletir (açılmadan önce kuyruğa girenlerle aynı yol);
     * hata olursa {@code fail()}'in tavanlı retry + backoff'u devralır.
     */
    private boolean circuitOpen() { return System.currentTimeMillis() < circuitOpenUntil; }

    /**
     * Bu pod'un açılıştan beri kira tazeleme hataları (2026-10-23, çok pod R2b): {@code error} = tazeleme sorgusu düştü,
     * {@code lost} = satırların kirası başka pod'a geçmişti (bu pod göndermedi). Sağlıklı kümede ikisi de 0 kalır.
     */
    public Map<String, Long> leaseRenewFailures() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("error", leaseRenewErrors.get());
        out.put("lost", leaseRenewLost.get());
        return out;
    }

    /** E4 sağlık kartı için anlık durum. */
    public Map<String, Object> healthSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled());
        out.put("circuit_open", circuitOpen());
        out.put("consecutive_failures", consecutiveFailures.get());
        return out;
    }

    // ── İşlemsel tekil push (kodla giriş, 2026-10-02) ─────────────────────────────────────────

    /**
     * Kurumsal push ağ geçidi yapılandırılmış mı (adres dolu). Kodla giriş push yöntemi buna bağlıdır: adres yoksa yöntem
     * giriş ekranında görünmez. Alarm kanalının genel anahtarı ({@link #enabled()}) bundan BAĞIMSIZDIR — o, alarm
     * bildirimlerinin açık olup olmadığını söyler; kullanıcının kendi istediği giriş kodu onun kapsamında değildir.
     */
    public boolean gatewayConfigured() {
        String u = url();
        return u != null && !u.isBlank();
    }

    /** Tekil gönderimin sonucu — {@code error} yalnız HTTP durumu ya da istisna SINIFI (mesaj/gövde ASLA). */
    public record DirectResult(boolean ok, Integer httpStatus, String error) { }

    /**
     * İŞLEMSEL tekil push (2026-10-02, kodla giriş) — alarm hattından AYRI: outbox'a YAZILMAZ, teslimat satırı DOĞMAZ,
     * saatlik tavana / sessiz saate / kişisel opt-out'a / devre kesiciye TAKILMAZ (kullanıcı kodu kendisi istedi; alarm
     * kanalının arızası giriş kodunu bekletmemeli, giriş trafiği de alarm kanalının devre kesicisini açmamalı). İstek
     * biçimi ve istemci alarm push'uyla AYNI: {@code {title, message, pipeline, userIds:[kimlik]}}, aynı adres, aynı
     * (şifresi çözülmüş) başlıklar, aynı kurumsal TLS güveni. Kimlik = kullanıcı adı (sicil) — alarm alıcı çözümüyle aynı alan.
     *
     * <p><b>Gizlilik:</b> mesaj (giriş kodu içerir) burada hiçbir log'a yazılmaz; hata metni yalnız HTTP durumu ya da
     * istisna sınıf adıdır (yanıt gövdesi isteği yankılayabileceği için OKUNMAZ, akış hemen kapatılır).
     */
    public DirectResult sendDirect(String userId, String title, String message) {
        String targetUrl = url();
        if (targetUrl == null || targetUrl.isBlank()) return new DirectResult(false, null, "NOT_CONFIGURED");
        if (userId == null || userId.isBlank()) return new DirectResult(false, null, "NO_TARGET");
        try {
            Map<String, Object> payload = new LinkedHashMap<>();   // alan SIRASI API sözleşmesine sadık (sendBatch ile aynı)
            // Kanal ISO-8859-9 (PushText): alarm hattı gibi BURADA da süzülür — süzülmeyen "—" telefonda "?" oluyordu
            // (2026-10-03, kullanıcı bildirimi: giriş kodundan sonra "?" geliyor). Merkezde: her sendDirect çağıranı korunur.
            payload.put("title", PushText.pushSafe(title == null || title.isBlank() ? titleSetting() : title));
            payload.put("message", PushText.truncate(PushText.pushSafe(message), maxMessageChars()));
            payload.put("pipeline", appSettings.getString("site.monitor.userpush.pipeline", ""));
            payload.put("userIds", List.of(userId.trim()));
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(totalTimeout()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)));
            for (String[] h : headerPairs()) req.header(h[0], h[1]);
            HttpResponse<java.io.InputStream> resp = client().send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream ignored = resp.body()) { /* gövde okunmaz (bkz. Gizlilik) */ } catch (Exception ignore) { /* kapatma best-effort */ }
            int sc = resp.statusCode();
            return sc >= 200 && sc < 300 ? new DirectResult(true, sc, null) : new DirectResult(false, sc, "HTTP " + sc);
        } catch (Exception e) {
            return new DirectResult(false, null, e.getClass().getSimpleName());
        }
    }

    /**
     * "Kendime test push'u gönder" (2026-10-04, öneri 4) — YALNIZ çağıranın kendi kimliğine, kendi push dilinde, işlemsel
     * tekil gönderimle ({@link #sendDirect}): kişisel tercihler / sessiz saat / susturma / saat tavanı UYGULANMAZ (açık bir
     * test isteğidir), teslimat satırı DOĞMAZ. Hız sınırı ve denetim çağıran uçta.
     */
    public DirectResult sendSelfTest(String username, String lang) {
        String l = PushI18n.norm(lang);
        return sendDirect(username, titleSetting(l), PushI18n.selfTest(Instant.now(), l));
    }

    /** Outbox'ı hemen boşalt (özet işi yeni satır yazınca) — tek worker'a iş atar, beklemez. */
    void kickDrain() {
        worker.execute(this::drainOutbox);
    }

    // ── Zamana bağlı eskalasyon adımı push'u (2026-10-04, onaylı öneri 6) ──────────────────────────────

    /** Eskalasyon adımı push satırlarının tetiği. */
    public static final String TRIGGER_ESCALATION_STEP = "ESCALATION_STEP";

    /** Adım dedupe anahtarı — {@code alert_escalation_steps} sahiplenmesiyle aynı eksen: (alarm, kişi, seviye). */
    static String stepDedupeKey(Long contactId, String level) {
        String lvl = level == null || level.isBlank() ? "WARNING" : level.trim().toUpperCase(java.util.Locale.ROOT);
        return "ESC_STEP:" + contactId + ":" + lvl;
    }

    /**
     * Eskalasyon adımı gönderildiğinde kişiye PUSH (e-posta + webhook'un yanında). Kişi aktif bir kullanıcıya çözülürse
     * ({@link UserPushRecipientResolver#resolveContact}; aynı e-posta + aynı adı taşıyan birden çok aktif hesap aynı kişidir —
     * 2026-10-21, yalnız biri alır, diğerleri {@code SKIPPED_SAME_PERSON}) satır yazılır; çözülemezse (yok / pasif) push gitmez ve
     * neden sistem satırına yazılır. Kanal kuralları (sistem bakımı, tür/takım kapsamı, izleme bayrağı, global sessiz saat),
     * kişisel tercihler (opt-out, aile, seviye, susturma — kritik istisnası, kişisel sessiz saat) ve saat tavanı (KRİTİK
     * muafiyeti ayarıyla) aynen uygulanır. Takım yönlendirmesini adım servisi zaten verdi; burada yeniden sorulmaz.
     * Hiçbir istisna yaymaz — adımın e-postası/webhook'u bundan etkilenmez.
     *
     * @return yazılan satırın durumu (PENDING / SKIPPED_* / RATE_LIMITED), kayıt yoksa null
     */
    public String enqueueEscalationStep(AlertEvent event, com.sitemonitor.model.EscalationContact contact, int delayMinutes) {
        try {
            if (!enabled() || !stepPushEnabled() || event == null || event.getId() == null || contact == null) return null;
            String level = event.getAlertLevel();
            String dedupeKey = stepDedupeKey(contact.getId(), level);
            String family = MonitorTypeCatalog.typeOfAlert(event.getAlertType());
            Long teamId = contact.getTeamId() != null ? contact.getTeamId() : event.getTeamId();
            String block = null;
            if (systemMaintenanceMuted()) block = SystemMaintenanceService.PUSH_SKIPPED;
            if (block == null) block = scopeBlockReason(teamId, family);
            if (block == null && pushDisabled(event)) block = "SKIPPED_MONITOR_OFF";
            if (block == null && quietHoursBlock(level)) block = "SKIPPED_QUIET_HOURS";
            UserPushRecipientResolver.ContactMatch match = block == null
                    ? resolver.resolveContact(contact, level, familyList(family)) : null;
            if (block == null && match != null && match.skipReason() != null) block = match.skipReason();
            if (block != null || match == null || match.recipient() == null) {
                String reason = block == null ? UserPushRecipientResolver.SKIPPED_NO_USER_MATCH : block;
                stepSkipRow(event, contact, teamId, family, dedupeKey, reason);
                return reason;
            }
            // Aynı kişi (2026-10-21): kişinin e-postası aynı adı taşıyan birden çok AKTİF hesaba eşleşiyorsa hepsi aynı kişidir
            // (eskiden "belirsiz", push yok) — YALNIZ biri alır: bu adımı zaten almış hesap; yoksa gönderilebilir hesaplar (tavan
            // altı, bu alarmda push alan, son giriş, kimlik). Hiçbiri gönderilebilir değilse birleştirme yok (her hesap kendi
            // nedenini yazar). Diğerleri SKIPPED_SAME_PERSON satırı alır.
            List<UserPushRecipientResolver.Recipient> people = new ArrayList<>();
            people.add(match.recipient());
            for (UserPushRecipientResolver.Recipient o : match.sameEmail())
                if (o != null && people.stream().noneMatch(p -> PushSamePersonGuard.key(p.username()).equals(PushSamePersonGuard.key(o.username()))))
                    people.add(o);
            String since = ISO.format(Instant.now().minus(Duration.ofHours(1)));
            BaseStatus base = new BaseStatus(overCap(capBypass(level), since));
            for (UserPushRecipientResolver.Recipient r : people) base.skip(r.username(), r.skipReason());
            PushSamePersonGuard.Plan same = samePlan(people.stream().map(UserPushRecipientResolver.Recipient::username).toList(),
                    base, alarmPrior(event.getId(), d -> dedupeKey.equals(d.dedupeKey())));
            // Hesapları çözüm AYNI kişi diye eşledi: koruyucu yoksa (elle kurulan test) ya da karar veremediyse (sorgu düştü) yine
            // tek push — sıradaki ilk gönderilebilir hesap (çözümün sırası: son giriş, kimlik); hiçbiri değilse birleştirme yok.
            PushSamePersonGuard.Plan decided = same;
            if (people.size() > 1 && people.stream().noneMatch(p -> decided.chosenFor(p.username()) != null)) {
                UserPushRecipientResolver.Recipient pick = fallbackChoice(people, base);
                if (pick != null) {
                    Map<String, String> m = new LinkedHashMap<>();
                    for (UserPushRecipientResolver.Recipient p : people)
                        if (p != pick) m.put(PushSamePersonGuard.key(p.username()), pick.username());
                    same = new PushSamePersonGuard.Plan(m);
                }
            }
            String result = null;
            boolean anyPending = false, anyWritten = false;
            for (UserPushRecipientResolver.Recipient r : people) {
                if (deliveryRepo.existsByAlertEventIdAndDedupeKeyAndUsername(event.getId(), dedupeKey, r.username())) continue;
                String chosen = same.chosenFor(r.username());
                String status = chosen != null ? STATUS_SKIPPED_SAME_PERSON : base.of(r.username());
                String lang = r.lang();
                String message = PushText.truncate(PushText.pushSafe(PushI18n.escalationStepPrefix(delayMinutes, lang)
                        + rawMessage(event, "ESCALATION", contextOf(event), lang)), maxMessageChars());
                UserPushDelivery d = row(event, TRIGGER_ESCALATION_STEP, dedupeKey, teamId, family, r.username(), r.displayName(),
                        message, status, ISO.format(Instant.now()), lang);
                d.setSamePersonUsername(chosen);
                d.setBatchId("step-" + UUID.randomUUID().toString().substring(0, 8));
                try {
                    deliveryRepo.save(d);
                } catch (Exception dup) {
                    logRowWriteFailure("eskalasyon adımı, olay #" + event.getId(), d, dup);
                    continue;
                }
                anyWritten = true;
                if ("PENDING".equals(status)) anyPending = true;
                if (chosen == null && result == null) result = status;
            }
            if (!anyWritten) return null;
            if (result == null) result = STATUS_SKIPPED_SAME_PERSON;   // kişi bu adımı başka hesabıyla zaten almıştı
            if (anyPending) worker.execute(this::drainOutbox);
            log.info("user-push eskalasyon adımı: olay #{} kişi #{} → {}", event.getId(), contact.getId(), result);
            return result;
        } catch (Exception e) {
            log.warn("user-push eskalasyon adımı atlandı (adımın e-postası etkilenmedi): {}", e.toString());
            return null;
        }
    }

    /**
     * Nöbetçi push'u (2026-10-11, kullanıcı kararı: "takım seçerse nöbetçiye de"). Takımın nöbet çizelgesinde "nöbetçiye
     * de bildir" açıksa YÜKSEK / KRİTİK alarm bildirimi o anki nöbetçiye de gider. Kurallar eskalasyon adımıyla aynı:
     * sistem bakımı (elle gönderim hariç), tür/takım kapsamı, izleme bayrağı, global sessiz saat, kişisel tercihler
     * (opt-out, aile, seviye, susturma, kişisel sessiz saat) ve saat tavanı. Kişi aynı tetikte TAKIM push'unu zaten
     * aldıysa ikinci satır yazılmaz. Satırın tetiği takım push'uyla aynı (OPEN / ESCALATION / RE_ALERT / RESEND);
     * ayırt eden yinelenmezlik anahtarı {@code ONCALL:…}. Hiçbir istisna yaymaz.
     *
     * @return yazılan satırın durumu, yazılmadıysa null
     */
    public String enqueueOnCall(Long alertEventId, String mailTrigger, Long teamId, Long userId, Set<String> excludeUsernames) {
        try {
            if (!enabled() || alertEventId == null || userId == null) return null;
            AlertEvent event = alertEventRepo.findById(alertEventId).orElse(null);
            if (event == null) return null;
            String trigger = switch (mailTrigger == null ? "" : mailTrigger) {
                case "ESCALATION" -> "ESCALATION";
                case "DAILY_REALERT" -> "RE_ALERT";
                case "MANUAL" -> "RESEND";
                default -> "OPEN";
            };
            String level = event.getAlertLevel();
            String family = MonitorTypeCatalog.typeOfAlert(event.getAlertType());
            String block = null;
            if (!"RESEND".equals(trigger) && systemMaintenanceMuted()) block = SystemMaintenanceService.PUSH_SKIPPED;
            if (block == null && "RE_ALERT".equals(trigger) && !appSettings.getBoolean("site.monitor.userpush.realert-enabled", true)) {
                block = "SKIPPED_REALERT_OFF";
            }
            if (block == null) block = scopeBlockReason(teamId, family);
            if (block == null && pushDisabled(event)) block = "SKIPPED_MONITOR_OFF";
            if (block == null && quietHoursBlock(level)) block = "SKIPPED_QUIET_HOURS";
            if (block != null) return block;   // kanal kararı takım push'unun karar satırında zaten izli
            com.sitemonitor.model.EscalationContact probe = new com.sitemonitor.model.EscalationContact();
            probe.setUserId(userId);
            probe.setTeamId(teamId);
            UserPushRecipientResolver.ContactMatch match = resolver.resolveContact(probe, level, familyList(family));
            if (match == null || match.recipient() == null) return match == null ? null : match.skipReason();
            UserPushRecipientResolver.Recipient r = match.recipient();
            if (excludeUsernames != null && excludeUsernames.contains(r.username())) return null;
            // Operatör onay penceresinde kişinin ÖBÜR hesabını çıkardıysa kişiyi çıkarmıştır (2026-10-21, aynı kişi kuralı)
            if (excludeUsernames != null && !excludeUsernames.isEmpty()
                    && samePersonSiblings(excludeUsernames).containsKey(PushSamePersonGuard.key(r.username()))) return null;
            // Aynı tetikte takım push'unu zaten ALDIYSA (üyelik + seviye) ikinci kez gitmez — RESEND anahtarı rastgele olduğundan
            // elle gönderimde "aynı bildirim" = son 2 dakikada yazılmış RESEND / nöbetçi RESEND satırı (takım push'u aynı çağrıda,
            // nöbetçi push'undan hemen ÖNCE yazılır). YALNIZ push'u alan satırlar sayılır (SENT / PENDING / QUEUED — 2026-10-21):
            // takım satırı atlama satırıysa (opt-out, seviye, tavan, aynı kişi …) nöbetçi push'u kendi kararını verir.
            String teamKey = dedupeKeyFor(trigger, event);
            String resendSince = ISO.format(Instant.now().minus(Duration.ofMinutes(2)));
            java.util.function.Predicate<EvRow> sameNotice = "RESEND".equals(trigger)
                    ? d -> d.dedupeKey() != null && d.createdAt() != null && d.createdAt().compareTo(resendSince) >= 0
                            && (d.dedupeKey().startsWith("RESEND:") || d.dedupeKey().startsWith("ONCALL:RESEND:"))
                    : d -> teamKey.equals(d.dedupeKey()) || ("ONCALL:" + teamKey).equals(d.dedupeKey());
            List<EvRow> rows = eventRows(event.getId());   // tek dar sorgu (kişi başına değil)
            String own = PushSamePersonGuard.key(r.username());
            for (EvRow d : rows)
                if (own.equals(PushSamePersonGuard.key(d.username())) && PushSamePersonGuard.delivering(d.status()) && sameNotice.test(d))
                    return null;
            String dedupeKey = "ONCALL:" + ("RESEND".equals(trigger) ? "RESEND:" + UUID.randomUUID().toString().substring(0, 8) : teamKey);
            if (deliveryRepo.existsByAlertEventIdAndDedupeKeyAndUsername(event.getId(), dedupeKey, r.username())) return null;
            // Aynı kişi (2026-10-21): kişinin BAŞKA hesabı bu tetikte takım push'unu (ya da başka bir nöbet push'unu) zaten ALDIYSA
            // bu hesaba ikinci push gitmez — satır SKIPPED_SAME_PERSON (iz kalır, tavana sayılmaz). Kanıt yine yalnız push'u alan
            // satırlardır (evidenceOf).
            String chosen = samePlan(List.of(r.username()), null,
                    names -> evidenceOf(rows, names, sameNotice)).chosenFor(r.username());
            String since = ISO.format(Instant.now().minus(Duration.ofHours(1)));
            String status = chosen != null ? STATUS_SKIPPED_SAME_PERSON
                    : new BaseStatus(overCap(capBypass(level), since)).skip(r.username(), r.skipReason()).of(r.username());
            String lang = r.lang();
            String prefix = "en".equals(lang) ? "On call: " : "Nöbetçisiniz: ";
            String message = PushText.truncate(PushText.pushSafe(prefix + rawMessage(event, trigger, contextOf(event), lang)), maxMessageChars());
            UserPushDelivery d = row(event, trigger, dedupeKey, teamId, family, r.username(), r.displayName(),
                    message, status, ISO.format(Instant.now()), lang);
            d.setSamePersonUsername(chosen);
            d.setBatchId("oncall-" + UUID.randomUUID().toString().substring(0, 8));
            try {
                deliveryRepo.save(d);
            } catch (Exception dup) {
                logRowWriteFailure("nöbetçi, olay #" + event.getId(), d, dup);
                return null;
            }
            if ("PENDING".equals(status)) worker.execute(this::drainOutbox);
            log.info("user-push nöbetçi: olay #{} takım #{} → {}", event.getId(), teamId, status);
            return status;
        } catch (Exception e) {
            log.warn("user-push nöbetçi atlandı (alarm e-postası etkilenmedi): {}", e.toString());
            return null;
        }
    }

    /** Adım push'unun karar satırı (sistem sicili) — kişi başına (alarm, kişi, seviye) bir kez. Ad: kişi kimliği (isim değil). */
    private void stepSkipRow(AlertEvent event, com.sitemonitor.model.EscalationContact contact, Long teamId, String family,
                             String dedupeKey, String reason) {
        try {
            if (deliveryRepo.existsByAlertEventIdAndDedupeKeyAndUsername(event.getId(), dedupeKey, SYSTEM_USER)) return;
            UserPushDelivery d = row(event, TRIGGER_ESCALATION_STEP, dedupeKey, teamId, family, SYSTEM_USER,
                    "(eskalasyon kişisi #" + contact.getId() + ")", null, reason, ISO.format(Instant.now()));
            deliveryRepo.save(d);
        } catch (Exception ignored) { /* karar satırı yazılamadıysa adımın kendisi etkilenmez */ }
    }

    /** Olayın alarm-anı bağlamı (JSON) → harita; yoksa/bozuksa null (şablon yedek değerlerle dolar). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> contextOf(AlertEvent event) {
        String json = event == null ? null : event.getContextJson();
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String dedupeKeyFor(String trigger, AlertEvent event) {
        return switch (trigger) {
            case "RE_ALERT" -> "RE_ALERT:" + Instant.now().atZone(ZONE).toLocalDate();
            case "ESCALATION" -> "ESC:" + event.getAlertLevel();   // seviye başına 1x (mail aynası)
            case "RESEND" -> "RESEND:" + UUID.randomUUID().toString().substring(0, 8);
            default -> trigger;   // OPEN / RESOLVE: olay başına 1x (DB garantisi)
        };
    }

    /**
     * Açılışta hiçbir kanal ÇALIŞMADAN verilen kararın izi (2026-09-30): fırtına devri ({@code SKIPPED_STORM}; yalnız
     * {@code site.monitor.storm.push-individual} KAPALIYKEN — 2026-10-03'ten beri varsayılan kipte fırtına üyesinin push'u
     * bireysel gider) ve sahipsiz kayıt ({@code SKIPPED_NO_TEAM}). Bireysel gönderim hattı bu olayı hiç görmediğinden karar satırı
     * çağıran tarafından yazdırılır; çözüm simetrisi ({@code SKIPPED_NO_PRIOR}) değişmez. Kanal global KAPALIYSA
     * satır yazılmaz (hattın kalanıyla aynı kural). Hiçbir istisna yayılmaz.
     */
    public void recordSuppressed(AlertEvent event, String reason) {
        try {
            if (!enabled() || event == null || event.getId() == null || reason == null) return;
            skipRow(event, "OPEN", reason);
        } catch (Exception e) {
            log.warn("user-push karar satırı yazılamadı: {}", e.toString());
        }
    }

    /**
     * {@link #recordSuppressed} — e-posta tetiğinin push karşılığıyla (2026-10-02, sistem bakımı): susturulan bildirim
     * yeniden uyarı / eskalasyon / çözüm ise karar satırı kendi fazında (RE_ALERT / ESCALATION / RESOLVE) yazılır.
     */
    public void recordSuppressedFor(AlertEvent event, String mailTrigger, String reason) {
        try {
            if (!enabled() || event == null || event.getId() == null || reason == null) return;
            skipRow(event, pushTriggerOf(mailTrigger), reason);
        } catch (Exception e) {
            log.warn("user-push karar satırı yazılamadı: {}", e.toString());
        }
    }

    /** E-posta tetiği → push fazı (enqueueAlert eşlemesiyle aynı; RESOLUTION → RESOLVE). */
    static String pushTriggerOf(String mailTrigger) {
        return switch (mailTrigger == null ? "" : mailTrigger) {
            case "ESCALATION" -> "ESCALATION";
            case "DAILY_REALERT" -> "RE_ALERT";
            case "MANUAL" -> "RESEND";
            case "RESOLUTION", "MANUAL_RESOLVE" -> "RESOLVE";
            default -> "OPEN";
        };
    }

    /**
     * Sistem Bakım Modu (2026-10-02) — "Bildirimler bakım boyunca sussun" açık bakım AKTİFKEN push gönderilmez (karar
     * satırı {@code SKIPPED_SYSTEM_MAINTENANCE}). Ana kapı alarm hunisinde ({@code EscalationService}); bu MERKEZÎ ağ, hunin
     * dışından gelen çözüm push'larını (fırtına üyesi / sessiz kapanış) da yakalar. Alan enjeksiyonu + isteğe bağlı: yokken
     * davranış birebir bugünkü.
     */
    @Autowired(required = false)
    private SystemMaintenanceService systemMaintenance;

    /** Test kancası. */
    void setSystemMaintenance(SystemMaintenanceService s) { this.systemMaintenance = s; }

    boolean systemMaintenanceMuted() {
        try {
            return systemMaintenance != null && systemMaintenance.notificationsMuted();
        } catch (Exception e) {
            return false;
        }
    }

    private void skipRow(AlertEvent event, String trigger, String reason) {
        try {
            String dedupeKey = dedupeKeyFor(trigger, event);
            if (deliveryRepo.existsByAlertEventIdAndDedupeKeyAndUsername(event.getId(), dedupeKey, SYSTEM_USER))
                return;
            UserPushDelivery d = row(event, trigger, dedupeKey, event.getTeamId(),
                    MonitorTypeCatalog.typeOfAlert(event.getAlertType()), SYSTEM_USER,
                    "(katman kararı)", null, reason,
                    ISO.format(Instant.now()));
            deliveryRepo.save(d);
        } catch (Exception ignored) { /* karar satırı yazılamadıysa gönderim mantığı etkilenmez */ }
    }

    /** Olayın tek ailesi → aile süzgeci girdisi (bilinmiyorsa null = süzgeç yok). */
    static List<String> familyList(String family) {
        return family == null || family.isBlank() ? null : List.of(family);
    }

    /** Toplu istek kimliği DİL başına ayrı: tek istek tek mesaj taşır, iki dil aynı isteğe girmez (2026-10-04). */
    static String batchIdFor(String base, String lang) {
        return PushI18n.isEn(lang) ? base + "-en" : base;
    }

    private UserPushDelivery row(AlertEvent event, String trigger, String dedupeKey, Long teamId,
                                 String family, String username, String displayName,
                                 String message, String status, String now, String lang) {
        UserPushDelivery d = row(event, trigger, dedupeKey, teamId, family, username, displayName, message, status, now);
        d.setPushLang(PushI18n.norm(lang));
        d.setTitle(titleSetting(lang));
        return d;
    }

    private UserPushDelivery row(AlertEvent event, String trigger, String dedupeKey, Long teamId,
                                 String family, String username, String displayName,
                                 String message, String status, String now) {
        UserPushDelivery d = new UserPushDelivery();
        d.setAlertEventId(event.getId());
        d.setTrigger(trigger);
        d.setDedupeKey(dedupeKey);
        d.setMonitorType(family);
        d.setMonitorName(event.getDomain());
        d.setTeamId(teamId);
        d.setAlertLevel(event.getAlertLevel());
        d.setUsername(username);
        d.setDisplayName(displayName);
        d.setTitle(titleSetting());
        d.setMessage(message);
        d.setStatus(status);
        d.setCreatedAt(now);
        return d;
    }

    private String titleSetting() {
        return appSettings.getString("site.monitor.userpush.title", "Site Monitor");
    }

    /**
     * Dile göre başlık (2026-10-04): İngilizce için {@code site.monitor.userpush.title.en}; boşsa Türkçe başlık ayarı (çoğu
     * kurumda başlık bir ürün adıdır, çevrilmez). Türkçe = {@link #titleSetting()} (bugünkü).
     */
    String titleSetting(String lang) {
        if (!PushI18n.isEn(lang)) return titleSetting();
        String en = appSettings.getString("site.monitor.userpush.title.en", "");
        return en == null || en.isBlank() ? titleSetting() : en;
    }

    // ── Şablonlar (K6) ─────────────────────────────────────────────────────────────────────

    public static final Map<String, String> DEFAULT_TEMPLATES = Map.of(
            "down",     "{seviye}: {ad} yanıt vermiyor. Başlangıç {baslangic}. {neden}",
            "slow",     "{seviye}: {ad} yavaş - {metrik} {deger} (eşik {esik}). Başlangıç {baslangic}.",
            "expiry",   "{seviye}: {ad} - {ne} {gun} gün içinde doluyor ({tarih}).",
            "changed",  "{seviye}: {ad} - {degisen} değişti. Başlangıç {baslangic}.",
            // Sertifika kusuru "yanıt vermiyor" DEĞİLDİR: host gayet iyi cevap veriyor olabilir.
            // Kanıt (IP/CN) sebepten ÖNCE gelir — 200 karakter tavanında ilk düşen kuyruk olur.
            "cert",     "{seviye}: {ad} sertifikası kabul edilemez (IP {ip}, CN {cn}). {neden}",
            "resolved", "DÜZELDİ: {ad} normale döndü. Süre {sure} (başlangıç {baslangic}, bitiş {bitis}).",
            // Hedef AYAKTA ama bozuk: sayfa bütünlüğü, çözülemeyen alan adı durumu. "yanıt
            // vermiyor" demek nöbetçiyi yanlış teşhise (ağ/erişim) yönlendiriyordu.
            "degraded", "{seviye}: {ad} - sorun var (erişim değil). {neden} Başlangıç {baslangic}.",
            "test",     "Deneme: SiteMonitor webhook testi - {saat}");

    /** Kaydetmede bilinen yer tutucular — bilinmeyeni reddet (sessiz bozulma olmasın). */
    public static final List<String> KNOWN_PLACEHOLDERS = List.of(
            "seviye", "ad", "hedef", "neden", "metrik", "deger", "esik",
            "ne", "gun", "tarih", "degisen", "sure", "saat",
            // Sertifika güvenlik alarmının KANITI: çözümlenen IP ve sunulan sertifikanın CN'i.
            // Ayrı yer tutucu olmaları şart — alarm metninin sonuna eklenselerdi {neden}'in
            // 120 karakter kırpması onları HER ZAMAN düşürürdü (özelliğin ana kanıtı kaybolurdu).
            "ip", "cn",
            // Sorunun BAŞLADIĞI ve (çözümde) BİTTİĞİ saat — kullanıcı bildirime bakıp olayın
            // penceresini görebilmeli. İkisi de İstanbul saatiyle {@code HH:mm}.
            "baslangic", "bitis");

    String template(String key) {
        return appSettings.getString("site.monitor.userpush.template." + key,
                DEFAULT_TEMPLATES.getOrDefault(key, DEFAULT_TEMPLATES.get("down")));
    }

    /**
     * Dile göre şablon (2026-10-04, öneri 5): İngilizce için {@code site.monitor.userpush.template.<key>.en} (boşsa gömülü
     * İngilizce varsayılan — {@link PushI18n#DEFAULT_TEMPLATES_EN}). Türkçe = {@link #template(String)} (bugünkü).
     */
    String template(String key, String lang) {
        if (!PushI18n.isEn(lang)) return template(key);
        String v = appSettings.getString("site.monitor.userpush.template." + key + ".en", "");
        if (v != null && !v.isBlank()) return v;
        return PushI18n.DEFAULT_TEMPLATES_EN.getOrDefault(key, PushI18n.DEFAULT_TEMPLATES_EN.get("down"));
    }

    /**
     * Süre-bitişi alarmında NEYİN dolduğu — {@code {ne}} yer tutucusu. Alan adı kaydı tipleri (whois/RDAP:
     * DOMAIN_EXPIRY, DOMAINMON_EXPIRY, KEYWORD_DOMAIN_EXPIRY, …_DOMAIN_EXPIRY) "alan adı kaydı"; geri kalan
     * her EXPIRY (sertifika sweep'i EXPIRY, HTTP/KEYWORD SSL) "SSL sertifikası".
     */
    static String expiringWhat(String alertType) {
        String t = alertType == null ? "" : alertType;
        if (t.contains("DOMAIN")) return "alan adı kaydı";
        return "SSL sertifikası";
    }

    /** Olay → şablon ailesi. */
    static String templateKeyFor(String alertType, String trigger) {
        if ("RESOLVE".equals(trigger)) return "resolved";
        String t = alertType == null ? "" : alertType;
        if (t.contains("EXPIRY")) return "expiry";
        if (t.contains("CHANGED") || t.contains("TRANSFER_LOCK") || t.contains("BLACKLIST")) return "changed";
        // Veritabanı sorgu eşiği (2026-10-31) BİLİNÇLİ ayrı dal: ihlal bir yavaşlık DEĞİL ("eşik" sözcüğü aşağıdaki "slow"
        // dalına düşürürdü — telefona sorgu SÜRESİ yazılırdı) ve "yanıt vermiyor" da değil (sorgu çalıştı) → "degraded"
        // ("sorun var (erişim değil)" + neden = kural özeti). DBQUERY_FAIL → "down", DBQUERY_SLOW → "slow".
        if ("DBQUERY_THRESHOLD".equals(t)) return "degraded";
        if (t.contains("SLOW") || t.contains("THRESHOLD")) return "slow";
        // Sertifika kusurları: "yanıt vermiyor" metni yanlış teşhise yönlendiriyordu.
        if ("REVOKED".equals(t) || "MISMATCH".equals(t) || t.contains("CHAIN")
                || t.contains("HOSTNAME_MISMATCH") || t.contains("UNTRUSTED")) return "cert";
        // İzleme tarafının SSL tipleri (HTTP_SSL / KEYWORD_SSL) yukarıdaki cert-sweep adlarının
        // hiçbirine uymuyor ve "down" şablonuna düşüyordu: site ayaktayken telefona "yanıt
        // vermiyor" gidiyor, e-posta ise "SSL Sertifika Sorunu" diyordu. Mesaj kendi içinde de
        // çelişiyordu ("yanıt vermiyor … TLS sertifikası sorunu — bitişe 12 gün").
        if (t.endsWith("_SSL")) return "cert";
        // Aşağıdakiler hiçbir dala uymuyor ve "down" şablonuna düşüyordu: telefona "{seviye}: {ad}
        // yanıt vermiyor" gidiyor, e-posta AYNI olay için "DNS Beklenmeyen Değer" / "Sayfa
        // Bütünlüğü Sorunu" diyordu. Sayfa/DNS ayaktayken "erişilemiyor" demek nöbetçiyi yanlış
        // teşhise yönlendiriyor — HTTP_SSL/KEYWORD_SSL için yukarıda kapatılan hatanın kalanı.
        if ("DNS_UNEXPECTED".equals(t) || "DNS_INCONSISTENT".equals(t) || "DOMAINMON_STATUS".equals(t))
            return "changed";
        if ("PAGE_INTEGRITY".equals(t) || "DOMAINMON_UNKNOWN".equals(t)) return "degraded";
        return "down";
    }

    String buildMessage(AlertEvent event, String trigger, Map<String, Object> ctx) {
        return buildMessage(event, trigger, ctx, PushI18n.TR);
    }

    /**
     * Alarm push metni — kişinin dilinde (2026-10-04). {@code tr} dalı bugünkü yardımcıları çağırır (bayt bayt aynı çıktı,
     * {@code PushLanguageTest}); {@code en} şablonu, seviye sözcüğü, süre/tarih biçimi ve ölçü adlarını çevirir.
     */
    String buildMessage(AlertEvent event, String trigger, Map<String, Object> ctx, String lang) {
        // Kanal ISO-8859-9 tasiyor: tipografik isaretler burada karsiligina cevrilir, aksi halde
        // kullanicinin telefonunda soru isaretine donuyorlar (kirpma isareti "..." dahil).
        return PushText.truncate(PushText.pushSafe(rawMessage(event, trigger, ctx, lang)), maxMessageChars());
    }

    /** Doldurulmuş şablon — süzme/kırpma ÖNCESİ (eskalasyon adımı önek ekleyip kendisi kırpar). */
    String rawMessage(AlertEvent event, String trigger, Map<String, Object> ctx, String lang) {
        // D-b14 (2026-09-29): TEK seviye sözlüğü (e-posta konusu/rozeti, ileti gövdesi, 7/24 postası ile aynı) —
        // eskiden INFO/LOW burada "UYARI", e-postada "BİLGİ" yazıyordu.
        String levelTr = PushI18n.levelWord(event.getAlertLevel(), lang);
        Map<String, String> vals = new LinkedHashMap<>();
        vals.put("seviye", levelTr);
        vals.put("ad", nz(event.getDomain(), "-"));
        vals.put("hedef", nz(event.getDomain(), "-"));
        // Alarm metni E-POSTA icin yazilmis tam bir cumle ve zaten "KRITIK: <adres> ..." ile
        // basliyor; sablon ayrica {seviye} ve {ad} koydugu icin seviye IKI, adres UC kez cikiyordu.
        vals.put("neden", PushText.capitalize(
                PushText.reasonOf(event.getMessage(), event.getDomain(), reasonMaxChars())));
        // Ölçü alanları: her izleme türü ölçüyü kendi adıyla koyuyor (rtt_ms / response_ms /
        // duration_ms), şablon ise metric/value/threshold arıyor. Eşleme sınırda yapılır; açık
        // anahtar yazan bir üretici olursa YİNE o kazanır (ctxStr önce ctx'e bakar).
        Map<String, String> slow = PushText.slowFields(ctx);
        vals.put("metrik", ctxStr(ctx, "metric",    slow.containsKey("metric")
                ? PushI18n.metric(slow.get("metric"), lang) : PushI18n.defaultMetric(lang)));
        vals.put("deger",  ctxStr(ctx, "value",     slow.getOrDefault("value", "-")));
        vals.put("esik",   ctxStr(ctx, "threshold", slow.getOrDefault("threshold", "-")));
        // {ne}: NEYİN dolduğu (2026-09-18, kullanıcı bildirimi): eskiden sabit "süre" yazıyor, telefonda
        // "x.com - süre 18 gün içinde doluyor" sertifika mı alan adı kaydı mı belli olmuyordu.
        vals.put("ne", PushI18n.expiringWhat(event.getAlertType(), lang));
        // ÖNCE ctx, sonra event — dosyanın geri kalanındaki kural (bkz. metrik/deger/esik).
        // Eskiden YALNIZ event.getDaysRemaining() okunuyordu: o değer alarm açılırken/tırmanırken
        // yazılır, e-posta ise gönderim anında latest_check'ten TAZE değeri kullanır. İki kanal
        // aynı olay için farklı gün söylüyordu — üretimde aynı saniyede push "15 gün", e-posta
        // "14 gün" dedi (bitiş 22.09 23:59 UTC; floorDiv ile 14 doğru olan). Bir alarm ürününde
        // iki kanalın farklı sayı söylemesi, hangisine inanılacağını belirsizleştirir.
        //
        // ALIAS ZİNCİRİ: üreticiler aynı büyüklüğü ÜÇ farklı adla yazıyor —
        // sertifika/DOMAIN_EXPIRY yolları "days_remaining", DOMAINMON_EXPIRY "days"
        // (SchedulerService:4636), DOMAIN_EXPIRY/KEYWORD_DOMAIN_EXPIRY ise
        // "domain_days_remaining" (SchedulerService:4146, 4262, 4343, 4425). Yalnız ilki
        // okunduğu için alan adı süre-bitişi push'larında "… - gün içinde doluyor" yazıyordu.
        // Mail tarafı (EmailTemplateBuilder:377) days_remaining→days alias'ını zaten tanıyor;
        // burada aynı zincir + mail'in de tanımadığı domain_days_remaining tamamlanıyor.
        vals.put("gun", firstCtx(ctx,
                event.getDaysRemaining() == null ? "-" : String.valueOf(event.getDaysRemaining()),
                "days_remaining", "days", "domain_days_remaining", "ssl_days_remaining"));
        // expiry_date YALNIZ domain/whois bağlamında var; SERTİFİKA bağlamı not_after taşıyor
        // (latestToCertContext). Şablon yalnız expiry_date aradığı için sertifika süre-bitişi
        // push'larında tarih HER ZAMAN "-" çıkıyordu — kullanıcının gördüğü "(-)" buydu.
        vals.put("tarih", ctxStr(ctx, "expiry_date",
                PushI18n.date(ctxStr(ctx, "not_after", null), lang)));
        // {neden} ile AYNI çekirdek: seviye öneki ve adres tekrarı burada da kırpılır. İkizin
        // atlanması telefona "KRİTİK: x - UYARI: x DNS kaydı değişti değişti." düşürüyordu.
        vals.put("degisen", PushText.capitalize(
                PushText.reasonOf(event.getMessage(), event.getDomain(), reasonMaxChars())));
        vals.put("ip", ctxStr(ctx, "resolved_ip", "-"));
        vals.put("cn", ctxStr(ctx, "subject", "-"));
        vals.put("sure", durationSince(event.getCreatedAt(), lang));
        vals.put("baslangic", PushText.istClock(event.getCreatedAt()));
        String nowClock = PushText.istClockNow(Instant.now());
        vals.put("saat", nowClock);
        vals.put("bitis", nowClock);   // çözüm tetiginde "şimdi" = normale dönüş anı
        return fillTemplate(template(templateKeyFor(event.getAlertType(), trigger), lang), vals);
    }

    static String fillTemplate(String template, Map<String, String> vals) {
        String out = template == null ? "" : template;
        for (var e : vals.entrySet()) out = out.replace("{" + e.getKey() + "}", nz(e.getValue(), "-"));
        return out.replaceAll("\\s{2,}", " ").trim();
    }

    private static String ctxStr(Map<String, Object> ctx, String key, String fallback) {
        Object v = ctx == null ? null : ctx.get(key);
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
    }

    /**
     * ctx'te SIRAYLA denenen anahtarlardan ilk dolu olanı; hiçbiri yoksa {@code fallback}.
     *
     * <p>Aynı büyüklüğü farklı adla yazan üreticiler için. Sıra ANLAMLIDIR: en özel ad önce gelir,
     * böylece açık anahtar yazan bir üretici genel alias'ı her zaman yener.
     */
    private static String firstCtx(Map<String, Object> ctx, String fallback, String... keys) {
        for (String k : keys) {
            String v = ctxStr(ctx, k, null);
            if (v != null) return v;
        }
        return fallback;
    }

    private static String nz(String s, String fallback) { return s == null || s.isBlank() ? fallback : s; }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        String line = i < 0 ? s : s.substring(0, i);
        return line.length() > 120 ? line.substring(0, 120) : line;
    }

    /**
     * Alarm açılışından bu yana geçen süre.
     *
     * <p><b>İki kusur birden düzeltildi.</b> (1) {@code AlertEvent.createdAt} UTC yazılıyor
     * ({@code EscalationService.now()}), burası ise onu çıplak yerel-zaman sanıp İstanbul
     * saatinden çıkarıyordu — her süreye sabit <b>+3 saat</b> ekleniyordu (5 dakikalık kesinti
     * telefonda "3 sa 5 dk" görünüyordu). (2) Birimler ürünün kendi standardından
     * ({@code incidentMeta.js}) sapıyordu ve gün sınırında saat bilgisi tamamen düşüyordu.
     */
    private String durationSince(String createdAt, String lang) {
        Instant start = PushText.parseStoredUtc(createdAt);
        if (start == null) return "-";
        return PushI18n.compactDuration(Duration.between(start, Instant.now()), lang);
    }
}
