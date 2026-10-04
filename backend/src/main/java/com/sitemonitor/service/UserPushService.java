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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
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

    /** Tek worker: outbox'ı sırayla boşaltır — API'ye eşzamanlı yığılma olmaz. */
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "user-push-worker");
                t.setDaemon(true);
                return t;
            });

    // ── Devre kesici (K7) — bellek içi; pod yeniden başlarsa temiz başlar (bilinçli) ──
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
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

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
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
        List<UserPushRecipientResolver.Recipient> recipients =
                ("RESOLVE".equals(trigger) ? resolver.resolvePrior(priorSentUsernames(event))
                                            : UserPushRecipientResolver.withFamilies(
                                                    resolver.resolve(teamId, event.getAlertLevel()), familyList(family)))
                .stream().filter(r -> !excluded.contains(r.username())).toList();
        if (recipients.isEmpty()) { skipRow(event, trigger, "SKIPPED_NO_RECIPIENTS"); return; }

        String dedupeKey = dedupeKeyFor(trigger, event);
        // Mesaj kişinin DİLİNDE (2026-10-04, öneri 5) — dil başına bir kez kurulur; Türkçe bugünküyle bayt bayt aynı.
        Map<String, String> messages = new java.util.HashMap<>();
        String batchId = UUID.randomUUID().toString().substring(0, 8);
        String now = ISO.format(Instant.now());
        String since = ISO.format(Instant.now().minus(Duration.ofHours(1)));

        // RESOLVE saat tavanından MUAF (prod kapısı 2026-09-25, O-3): 2026-09-10 kararı "çözüm push'u açılışta SENT
        // olanlara gider" — sessiz saatten muaf olmasının nedeni aynı ("telefondaki alarm kapanmalı"). Tavana
        // takılan "DÜZELDİ" push'u ayakta olan izlemeyi telefonda "düştü" gösteriyordu. Alıcılar zaten açılışta
        // SENT olanlarla sınırlı (resolvePrior), yani çözüm push'u açılış sayısını aşamaz.
        // KRİTİK alarm, "kritikler tavana takılmasın" ayarı açıksa muaf (2026-10-04, öneri 2; vars. KAPALI).
        boolean capExempt = "RESOLVE".equals(trigger) || capBypass(event.getAlertLevel());
        int queued = 0;
        for (var r : recipients) {
            String status;
            if (r.skipReason() != null) status = r.skipReason();
            else if (!capExempt && deliveryRepo.countRecentForUser(r.username(), since) >= hourlyCap()) status = "RATE_LIMITED";
            else status = "PENDING";   // devre kesici açıksa drainOutbox bekletir (N2) — satır kaybolmaz

            if (deliveryRepo.existsByAlertEventIdAndDedupeKeyAndUsername(event.getId(), dedupeKey, r.username()))
                continue;   // faz zaten kayıtlı — sessiz erken çıkış (satır orada duruyor)

            String lang = r.lang();
            String message = messages.computeIfAbsent(lang, l -> buildMessage(event, trigger, ctx, l));
            UserPushDelivery d = row(event, trigger, dedupeKey, teamId, family, r.username(), r.displayName(),
                    message, status, now, lang);
            d.setBatchId(batchIdFor(batchId, lang));
            try {
                deliveryRepo.save(d);
                if ("PENDING".equals(status)) queued++;
            } catch (Exception dup) {
                // UNIQUE kısıt yarışta da son sözü söyler: aynı olayın aynı fazı aynı kişiye
                // kod hatasında bile iki kez yazılamaz. İhlal = zaten var → sessizce geç.
                log.debug("user-push dedupe (unique): event={} key={}", event.getId(), dedupeKey);
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

    /** Onay pop-up'ındaki tek webhook alıcı satırı. {@code status}: PENDING = gidecek, aksi halde sebep. */
    public record PushPreviewRow(String username, String displayName, String status) {}

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
        for (var r : UserPushRecipientResolver.withFamilies(resolver.resolve(teamId, event.getAlertLevel()), familyList(family))) {
            String status;
            if (r.skipReason() != null) status = r.skipReason();
            else if (!capExempt && deliveryRepo.countRecentForUser(r.username(), since) >= hourlyCap()) status = "RATE_LIMITED";
            else status = "PENDING";   // devre kesici açıksa drainOutbox bekletir (N2) — satır kaybolmaz
            rows.add(new PushPreviewRow(r.username(), r.displayName(), status));
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
            case "CERTIFICATE" -> List.of("cert");
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
        java.util.Set<String> excluded = new java.util.HashSet<>();
        for (String u : excludeUsernames == null ? java.util.Set.<String>of() : excludeUsernames)
            if (u != null) excluded.add(u.trim().toUpperCase(java.util.Locale.ROOT));
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
        return writeTeamRows(out, teamId, trigger, alertLevel, monitorType, monitorName, message, dedupeKey,
                recipients, excluded, false);
    }

    /** Olaysız takım satırlarını yazar — takım bildirimi ve fırtına push'u ortak (dedupe, opt-out, saat tavanı). */
    private Map<String, Object> writeTeamRows(Map<String, Object> out, Long teamId, String trigger, String alertLevel,
                                              String monitorType, String monitorName, LocalizedText message, String dedupeKey,
                                              List<UserPushRecipientResolver.Recipient> recipients,
                                              java.util.Set<String> excluded, boolean capExempt) {
        String batchId = UUID.randomUUID().toString().substring(0, 8);
        String now = ISO.format(Instant.now());
        String since = ISO.format(Instant.now().minus(Duration.ofHours(1)));
        Map<String, String> texts = new java.util.HashMap<>();
        int queued = 0, skipped = 0;
        List<String> names = new ArrayList<>();
        for (var r : recipients) {
            if (r.username() != null && excluded.contains(r.username().trim().toUpperCase(java.util.Locale.ROOT))) { skipped++; continue; }
            String status;
            if (r.skipReason() != null) status = r.skipReason();
            else if (!capExempt && deliveryRepo.countRecentForUser(r.username(), since) >= hourlyCap()) status = "RATE_LIMITED";
            else status = "PENDING";   // devre kesici açıksa drainOutbox bekletir (N2) — satır kaybolmaz
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
            d.setCreatedAt(now);
            d.setBatchId(batchIdFor(batchId, lang));
            d.setPushLang(lang);
            try { deliveryRepo.save(d); } catch (Exception dup) { skipped++; continue; }
            if ("PENDING".equals(status)) { queued++; names.add(r.displayName() == null ? r.username() : r.displayName()); }
            else skipped++;
        }
        if (queued > 0) worker.execute(this::drainOutbox);
        out.put("queued", queued); out.put("skipped", skipped); out.put("recipients", names); out.put("batch_id", batchId);
        return out;
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
            String dedupeKey = stormDedupeKey(stormId, stormTrigger);
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
                            resolve || capBypass(alertLevel));   // çözüm tavandan muaf; KRİTİK, ayar açıksa muaf
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
            deliveryRepo.save(d);
        } catch (Exception ignored) { /* karar satırı yazılamadıysa gönderim mantığı etkilenmez */ }
    }

    /** Doğrudan alıcı (rol grubu çözümü YOK): kullanıcı adı + görünen ad + opt-out. */
    public record DirectRecipient(String username, String displayName, boolean optOut, String lang) {
        /** Eski üç alanlı biçim — push Türkçe (bugünkü davranış). */
        public DirectRecipient(String username, String displayName, boolean optOut) {
            this(username, displayName, optOut, PushI18n.TR);
        }
        public DirectRecipient {
            lang = PushI18n.norm(lang);
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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queued", 0); out.put("skipped", 0); out.put("recipients", List.of());
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
        for (DirectRecipient r : recipients) {
            String u = r.username() == null ? "" : r.username().trim();
            if (u.isEmpty() || !seen.add(u)) { skipped++; continue; }
            String status;
            if (r.optOut()) status = "SKIPPED_USER_OPT_OUT";
            else if (deliveryRepo.countRecentForUser(u, since) >= hourlyCap()) status = "RATE_LIMITED";
            else status = "PENDING";   // devre kesici açıksa drainOutbox bekletir (N2) — satır kaybolmaz
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
            d.setCreatedAt(now);
            d.setBatchId(batchIdFor(batchId, lang));
            d.setPushLang(lang);
            try { deliveryRepo.save(d); } catch (Exception dup) { skipped++; continue; }
            if ("PENDING".equals(status)) { queued++; names.add(d.getDisplayName()); }
            else skipped++;
            usernames.add(u);
        }
        if (queued > 0) worker.execute(this::drainOutbox);
        out.put("queued", queued); out.put("skipped", skipped); out.put("recipients", names); out.put("batch_id", batchId);
        out.put("usernames", usernames);   // çağıran, aynı olayın takım bildiriminden bu kişileri düşer
        return out;
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
        if (circuitOpen()) return;
        worker.execute(this::drainOutbox);
    }

    /** PENDING satırları batch bazında gönderir. Tek worker — eşzamanlılık yok. */
    void drainOutbox() {
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
            List<UserPushDelivery> pending = claim(due);
            if (pending.isEmpty()) return;   // başka bir pod aldı
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
            Set<Long> handled = new java.util.HashSet<>();
            for (UserPushDelivery d : pending) if (d.getId() != null) handled.add(d.getId());
            for (var e : byBatch.entrySet()) sendBatch(e.getValue());
            // Gönderim sürerken YENİ satır birikmiş olabilir — yalnız onlar için bir tur daha bak.
            boolean freshWork = deliveryRepo.findDuePending(ISO.format(Instant.now()), OUTBOX_PAGE).stream()
                    .anyMatch(d -> d.getId() == null || !handled.contains(d.getId()));
            if (freshWork) worker.execute(this::drainOutbox);
        } catch (Exception e) {
            log.warn("user-push outbox taraması düştü (bir sonraki enqueue yeniden dener): {}", e.toString());
        }
    }

    /** TEK toplu istek (K9): batch'in tüm alıcıları tek userIds dizisinde. */
    /** Kira süresi: gönderim süresinin (bağlantı + gövde) iki katı + pay, en az 2 dk. */
    private long claimLeaseSec() { return Math.max(120L, totalTimeout() * 2L + 30L); }

    /**
     * Zamanı gelmiş satırları bu tur için kiralar ({@link UserPushDeliveryRepository#claimDue}) ve YALNIZ kiralananları
     * döner — iki pod aynı satırı göndermez. Kira damgası tur başına tekildir (saniye + rastgele kesir; metin
     * karşılaştırması {@code findDuePending}'in "şimdi" damgasıyla sıralı kalır). Sahiplenme sorgusu düşerse eski yol
     * (okunan satırlar) — tek pod'da davranış birebir aynıdır.
     */
    List<UserPushDelivery> claim(List<UserPushDelivery> due) {
        List<Long> ids = due.stream().map(UserPushDelivery::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return due;
        Instant now = Instant.now();
        String lease = ISO.format(now.plusSeconds(claimLeaseSec()))
                + "." + String.format("%09d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000_000));
        try {
            if (deliveryRepo.claimDue(ids, ISO.format(now), lease) == 0) return List.of();
            return deliveryRepo.findByIdInAndNextAttemptAtOrderByIdAsc(ids, lease);
        } catch (Exception e) {
            log.debug("user-push sahiplenme yapılamadı ({} satır) — okunan satırlarla devam: {}", ids.size(), e.toString());
            return due;
        }
    }

    private void sendBatch(List<UserPushDelivery> rows) {
        List<String> userIds = rows.stream().map(UserPushDelivery::getUsername).distinct().toList();
        UserPushDelivery first = rows.get(0);
        String body;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();   // alan SIRASI API sözleşmesine sadık
            payload.put("title", first.getTitle() == null ? titleSetting() : first.getTitle());
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
            for (UserPushDelivery d : rows) {
                d.setStatus("SENT");
                d.setHttpStatus(resp.statusCode());
                d.setSentAt(sentAt);
                d.setAttempts(d.getAttempts() == null ? 1 : d.getAttempts() + 1);
                d.setNextAttemptAt(null);
                d.setNotificationId(notificationId);   // tek istek → tek numara, TÜM alt satırlara
                d.setRawResponse(raw);
            }
            saveOutcome(rows);
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
        if (minDelay != Long.MAX_VALUE) worker.schedule(this::drainOutbox, minDelay, TimeUnit.SECONDS);
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
     * Eskalasyon adımı gönderildiğinde kişiye PUSH (e-posta + webhook'un yanında). Kişi TEK aktif kullanıcıya çözülürse
     * ({@link UserPushRecipientResolver#resolveContact}) satır yazılır; çözülemezse (yok / belirsiz / pasif) push gitmez ve
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
            UserPushRecipientResolver.Recipient r = match.recipient();
            if (deliveryRepo.existsByAlertEventIdAndDedupeKeyAndUsername(event.getId(), dedupeKey, r.username())) return null;
            String since = ISO.format(Instant.now().minus(Duration.ofHours(1)));
            String status;
            if (r.skipReason() != null) status = r.skipReason();
            else if (!capBypass(level) && deliveryRepo.countRecentForUser(r.username(), since) >= hourlyCap()) status = "RATE_LIMITED";
            else status = "PENDING";
            String lang = r.lang();
            String message = PushText.truncate(PushText.pushSafe(PushI18n.escalationStepPrefix(delayMinutes, lang)
                    + rawMessage(event, "ESCALATION", contextOf(event), lang)), maxMessageChars());
            UserPushDelivery d = row(event, TRIGGER_ESCALATION_STEP, dedupeKey, teamId, family, r.username(), r.displayName(),
                    message, status, ISO.format(Instant.now()), lang);
            d.setBatchId("step-" + UUID.randomUUID().toString().substring(0, 8));
            try {
                deliveryRepo.save(d);
            } catch (Exception dup) {
                log.debug("user-push eskalasyon adımı dedupe (unique): olay={} kişi={}", event.getId(), contact.getId());
                return null;
            }
            if ("PENDING".equals(status)) worker.execute(this::drainOutbox);
            log.info("user-push eskalasyon adımı: olay #{} kişi #{} → {}", event.getId(), contact.getId(), status);
            return status;
        } catch (Exception e) {
            log.warn("user-push eskalasyon adımı atlandı (adımın e-postası etkilenmedi): {}", e.toString());
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
