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
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_MESSAGE_CHARS = 200;
    private static final int MAX_RAW_RESPONSE = 500;

    /** Katman/karar satırlarında kullanılan sistem-sicili: kapsam reddi kişiye değil olaya aittir. */
    static final String SYSTEM_USER = "-";

    private final AppSettingsService appSettings;
    private final UserPushDeliveryRepository deliveryRepo;
    private final UserPushScopeRepository scopeRepo;
    private final UserPushRecipientResolver resolver;
    private final AlertEventRepository alertEventRepo;
    private final SecretCipher secretCipher;

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
                           AlertEventRepository alertEventRepo, SecretCipher secretCipher) {
        this.appSettings = appSettings;
        this.deliveryRepo = deliveryRepo;
        this.scopeRepo = scopeRepo;
        this.resolver = resolver;
        this.alertEventRepo = alertEventRepo;
        this.secretCipher = secretCipher;
    }

    @PreDestroy
    void shutdown() { worker.shutdownNow(); }

    // ── Ayarlar (CANLI okunur — şablon/timeout değişikliği anında etkir) ───────────────────

    public boolean enabled() { return appSettings.getBoolean("site.monitor.userpush.enabled", false); }
    private String url() { return appSettings.getString("site.monitor.userpush.url", ""); }
    private int connectTimeout() { return appSettings.getInt("site.monitor.userpush.timeout-connect-seconds", 3); }
    private int totalTimeout() { return appSettings.getInt("site.monitor.userpush.timeout-total-seconds", 5); }
    private int retryMax() { return appSettings.getInt("site.monitor.userpush.retry-max", 2); }
    private int circuitThreshold() { return appSettings.getInt("site.monitor.userpush.circuit-threshold", 5); }
    private int circuitCooldownSec() { return appSettings.getInt("site.monitor.userpush.circuit-cooldown-seconds", 300); }
    private int hourlyCap() { return appSettings.getInt("site.monitor.userpush.hourly-cap", 30); }

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
            enqueueInternal(event, trigger, fallbackTeamId, ctx);
        } catch (Exception e) {
            log.warn("user-push enqueue atlandı (alarm yolu etkilenmedi): {}", e.toString());
        }
    }

    /** Çözüm bildirimi tetiği. */
    public void enqueueResolve(AlertEvent event, Map<String, Object> ctx) {
        try {
            if (!enabled() || event == null || event.getId() == null) return;
            // Simetri kuralı: açılışı kimseye push'lanmamış bir olayın çözümü de push'lanmaz —
            // yoksa kullanıcı hiç haber almadığı bir kesinti için "DÜZELDİ" mesajı alırdı.
            // (İzleme bayrağı/sessiz saat/katman reddi çözümde ayrıca değerlendirilmez; karar
            // açılışta verilmiş ve satıra yazılmıştır.)
            if (!deliveryRepo.existsByAlertEventIdAndStatus(event.getId(), "SENT")) {
                skipRow(event, "RESOLVE", "SKIPPED_NO_PRIOR");
                return;
            }
            enqueueInternal(event, "RESOLVE", event.getTeamId(), ctx);
        } catch (Exception e) {
            log.warn("user-push çözüm enqueue atlandı: {}", e.toString());
        }
    }

    private void enqueueInternal(AlertEvent event, String trigger, Long fallbackTeamId,
                                 Map<String, Object> ctx) {
        Long teamId = event.getTeamId() != null ? event.getTeamId() : fallbackTeamId;
        String family = MonitorTypeCatalog.typeOfAlert(event.getAlertType());

        // Katman matrisi — reddin HER dalı günlükte görünür (görünmez sessizlik yok).
        if (!scopeEnabled("TYPE", family)) { skipRow(event, trigger, "SKIPPED_TYPE_OFF"); return; }
        if (teamId != null && !scopeEnabled("TEAM", String.valueOf(teamId))) {
            skipRow(event, trigger, "SKIPPED_TEAM_OFF"); return;
        }
        if (ctx != null && Boolean.TRUE.equals(ctx.get("push_disabled"))) {
            skipRow(event, trigger, "SKIPPED_MONITOR_OFF"); return;
        }
        // İzleme bayrağı kararı KALICIDIR: OPEN'da yazılan SKIPPED_MONITOR_OFF satırı sonraki
        // fazları da bağlar. Gerekli çünkü bayrak ctx ile taşınır ve her yol taşımaz — örn.
        // manuel "yeniden gönder" izleme tiplerinde certContext'i null kurar; satır olmasaydı
        // kapalı izlemeye RESEND push'u sızardı. (OPEN/RESOLVE hariç: OPEN kararı zaten kendisi
        // verir, RESOLVE simetri kuralıyla — önce SENT yoksa — zaten gitmez.)
        if (!"OPEN".equals(trigger) && !"RESOLVE".equals(trigger)
                && deliveryRepo.existsByAlertEventIdAndStatus(event.getId(), "SKIPPED_MONITOR_OFF")) {
            skipRow(event, trigger, "SKIPPED_MONITOR_OFF");
            return;
        }
        if (quietHoursBlock(event.getAlertLevel())) { skipRow(event, trigger, "SKIPPED_QUIET_HOURS"); return; }

        List<UserPushRecipientResolver.Recipient> recipients = resolver.resolve(teamId, event.getAlertLevel());
        if (recipients.isEmpty()) { skipRow(event, trigger, "SKIPPED_NO_RECIPIENTS"); return; }

        String dedupeKey = dedupeKeyFor(trigger, event);
        String message = buildMessage(event, trigger, ctx);
        String batchId = UUID.randomUUID().toString().substring(0, 8);
        String now = ISO.format(Instant.now().atZone(ZONE).toLocalDateTime());
        String since = ISO.format(Instant.now().minus(Duration.ofHours(1)).atZone(ZONE).toLocalDateTime());

        int queued = 0;
        for (var r : recipients) {
            String status;
            if (r.skipReason() != null) status = r.skipReason();
            else if (deliveryRepo.countRecentForUser(r.username(), since) >= hourlyCap()) status = "RATE_LIMITED";
            else if (circuitOpen()) status = "CIRCUIT_OPEN";
            else status = "PENDING";

            if (deliveryRepo.existsByAlertEventIdAndDedupeKeyAndUsername(event.getId(), dedupeKey, r.username()))
                continue;   // faz zaten kayıtlı — sessiz erken çıkış (satır orada duruyor)

            UserPushDelivery d = row(event, trigger, dedupeKey, teamId, family, r.username(), r.displayName(),
                    message, status, now);
            d.setBatchId(batchId);
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

    /** Test gönderimi — gerçek istek, TEST satırı; dakikada 3 tavanı çağıran uç denetler. */
    public Map<String, Object> sendTest(List<String> usernames, String templateKey, String note) {
        String batchId = UUID.randomUUID().toString().substring(0, 8);
        String now = ISO.format(Instant.now().atZone(ZONE).toLocalDateTime());
        Map<String, String> sample = new LinkedHashMap<>();
        sample.put("seviye", "TEST"); sample.put("ad", "Örnek İzleme"); sample.put("hedef", "example.com");
        sample.put("neden", "deneme"); sample.put("metrik", "yanıt süresi"); sample.put("deger", "1200ms");
        sample.put("esik", "1000ms"); sample.put("ne", "sertifika"); sample.put("gun", "30");
        sample.put("tarih", "2026-12-31"); sample.put("degisen", "kayıt"); sample.put("sure", "5 dk");
        sample.put("saat", HHMM.format(Instant.now().atZone(ZONE)));
        String message = fillTemplate(template(templateKey == null ? "test" : templateKey), sample);
        List<UserPushDelivery> rows = new ArrayList<>();
        for (String u : usernames) {
            if (u == null || u.isBlank()) continue;
            UserPushDelivery d = new UserPushDelivery();
            d.setTrigger("TEST");
            d.setDedupeKey("TEST:" + batchId);
            d.setUsername(u.trim());
            d.setDisplayName(u.trim());
            d.setTitle(titleSetting());
            d.setMessage(message);
            d.setStatus(circuitOpen() ? "CIRCUIT_OPEN" : "PENDING");
            d.setCreatedAt(now);
            d.setBatchId(batchId);
            d.setMonitorName(note);
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

    // ── Outbox worker ──────────────────────────────────────────────────────────────────────

    /** PENDING satırları batch bazında gönderir. Tek worker — eşzamanlılık yok. */
    void drainOutbox() {
        try {
            List<UserPushDelivery> pending = deliveryRepo.findTop50ByStatusOrderByIdAsc("PENDING");
            if (pending.isEmpty()) return;
            if (circuitOpen()) {   // kuyruktakiler BEKLER (kaybolmaz); cooldown sonunda tekrar bak
                worker.schedule(this::drainOutbox, Math.max(1,
                        (circuitOpenUntil - System.currentTimeMillis()) / 1000), TimeUnit.SECONDS);
                return;
            }
            Map<String, List<UserPushDelivery>> byBatch = new LinkedHashMap<>();
            for (UserPushDelivery d : pending)
                byBatch.computeIfAbsent(d.getBatchId() == null ? "solo-" + d.getId() : d.getBatchId(),
                        k -> new ArrayList<>()).add(d);
            for (var e : byBatch.entrySet()) sendBatch(e.getValue());
            // Gönderim sürerken yeni satır birikmiş olabilir — bir tur daha bak.
            if (!deliveryRepo.findTop50ByStatusOrderByIdAsc("PENDING").isEmpty())
                worker.execute(this::drainOutbox);
        } catch (Exception e) {
            log.warn("user-push outbox taraması düştü (bir sonraki enqueue yeniden dener): {}", e.toString());
        }
    }

    /** TEK toplu istek (K9): batch'in tüm alıcıları tek userIds dizisinde. */
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

        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(totalTimeout()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            for (String[] h : headerPairs()) req.header(h[0], h[1]);
            HttpResponse<String> resp = client().send(req.build(), HttpResponse.BodyHandlers.ofString());
            String raw = resp.body() == null ? "" : resp.body();
            if (raw.length() > MAX_RAW_RESPONSE) raw = raw.substring(0, MAX_RAW_RESPONSE);

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                consecutiveFailures.set(0);
                // notificationId — kanıt zinciri: API bu push'a bu numarayı verdi. Ayrıştırılamazsa
                // gönderim YİNE başarılıdır (SENT + null) — kimlik alınamadı diye FAILED yazılmaz.
                String notificationId = null;
                try {
                    JsonNode n = MAPPER.readTree(resp.body());
                    if (n.hasNonNull("notificationId")) notificationId = n.get("notificationId").asText();
                } catch (Exception ignored) { }
                String sentAt = ISO.format(Instant.now().atZone(ZONE).toLocalDateTime());
                for (UserPushDelivery d : rows) {
                    d.setStatus("SENT");
                    d.setHttpStatus(resp.statusCode());
                    d.setSentAt(sentAt);
                    d.setAttempts(d.getAttempts() == null ? 1 : d.getAttempts() + 1);
                    d.setNotificationId(notificationId);   // tek istek → tek numara, TÜM alt satırlara
                    d.setRawResponse(raw);
                }
                deliveryRepo.saveAll(rows);
                log.info("user-push gönderildi: {} alıcı (HTTP {})", userIds.size(), resp.statusCode());
            } else {
                fail(rows, resp.statusCode(), "HTTP " + resp.statusCode() + " — " + raw, true);
            }
        } catch (Exception e) {
            fail(rows, null, e.toString(), true);
        }
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
        for (UserPushDelivery d : rows) {
            int attempts = (d.getAttempts() == null ? 0 : d.getAttempts()) + 1;
            d.setAttempts(attempts);
            d.setHttpStatus(httpStatus);
            d.setError(error != null && error.length() > 500 ? error.substring(0, 500) : error);
            if (retryable && attempts <= retryMax()) {
                d.setStatus("PENDING");   // kuyrukta kalır; backoff sonrası yeniden denenir
            } else {
                d.setStatus("FAILED");
            }
        }
        deliveryRepo.saveAll(rows);
        boolean willRetry = rows.stream().anyMatch(d -> "PENDING".equals(d.getStatus()));
        if (willRetry) {
            int attempt = rows.get(0).getAttempts();
            int delay = backoff.get(Math.min(Math.max(0, attempt - 1), backoff.size() - 1));
            worker.schedule(this::drainOutbox, delay, TimeUnit.SECONDS);
        }
        log.warn("user-push gönderilemedi ({} alıcı): {}", rows.size(), error);
    }

    // ── Yardımcılar ────────────────────────────────────────────────────────────────────────

    private HttpClient client() {
        // K3: doğrudan çıkış — kurumsal proxy'ye GİRMEZ (API iç ağda; 6 checker'ın deseniyle aynı).
        return HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(Duration.ofSeconds(connectTimeout()))
                .build();
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
        String start = appSettings.getString("site.monitor.userpush.quiet-start", "");
        String end = appSettings.getString("site.monitor.userpush.quiet-end", "");
        if (start.isBlank() || end.isBlank()) return false;
        try {
            LocalTime s = LocalTime.parse(start), e = LocalTime.parse(end);
            LocalTime now = LocalTime.now(ZONE);
            boolean inWindow = s.isBefore(e) ? (!now.isBefore(s) && now.isBefore(e))
                    : (!now.isBefore(s) || now.isBefore(e));   // gece devrilen pencere (22:00-07:00)
            if (!inWindow) return false;
            String min = appSettings.getString("site.monitor.userpush.quiet-min-level", "CRITICAL");
            return UserPushRecipientResolver.levelValue(level) < UserPushRecipientResolver.levelValue(min);
        } catch (Exception ex) {
            return false;   // bozuk pencere ayarı bildirimi ENGELLEMESİN
        }
    }

    private boolean circuitOpen() { return System.currentTimeMillis() < circuitOpenUntil; }

    /** E4 sağlık kartı için anlık durum. */
    public Map<String, Object> healthSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled());
        out.put("circuit_open", circuitOpen());
        out.put("consecutive_failures", consecutiveFailures.get());
        return out;
    }

    private String dedupeKeyFor(String trigger, AlertEvent event) {
        return switch (trigger) {
            case "RE_ALERT" -> "RE_ALERT:" + Instant.now().atZone(ZONE).toLocalDate();
            case "ESCALATION" -> "ESC:" + event.getAlertLevel();   // seviye başına 1x (mail aynası)
            case "RESEND" -> "RESEND:" + UUID.randomUUID().toString().substring(0, 8);
            default -> trigger;   // OPEN / RESOLVE: olay başına 1x (DB garantisi)
        };
    }

    private void skipRow(AlertEvent event, String trigger, String reason) {
        try {
            String dedupeKey = dedupeKeyFor(trigger, event);
            if (deliveryRepo.existsByAlertEventIdAndDedupeKeyAndUsername(event.getId(), dedupeKey, SYSTEM_USER))
                return;
            UserPushDelivery d = row(event, trigger, dedupeKey, event.getTeamId(),
                    MonitorTypeCatalog.typeOfAlert(event.getAlertType()), SYSTEM_USER,
                    "(katman kararı)", null, reason,
                    ISO.format(Instant.now().atZone(ZONE).toLocalDateTime()));
            deliveryRepo.save(d);
        } catch (Exception ignored) { /* karar satırı yazılamadıysa gönderim mantığı etkilenmez */ }
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

    // ── Şablonlar (K6) ─────────────────────────────────────────────────────────────────────

    public static final Map<String, String> DEFAULT_TEMPLATES = Map.of(
            "down", "{seviye} ▸ {ad}: {hedef} yanıt vermiyor — {neden}. {saat}",
            "slow", "{seviye} ▸ {ad}: {metrik} eşiği aşıldı ({deger}, eşik {esik}). {saat}",
            "expiry", "{seviye} ▸ {ad}: {ne} {gun} gün içinde doluyor ({tarih}).",
            "changed", "{seviye} ▸ {ad}: {degisen} değişti — kontrol edin. {saat}",
            "resolved", "DÜZELDİ ▸ {ad}: {sure} sonra normale döndü. {saat}",
            "test", "Deneme ▸ SiteMonitor webhook testi — {saat}");

    /** Kaydetmede bilinen yer tutucular — bilinmeyeni reddet (sessiz bozulma olmasın). */
    public static final List<String> KNOWN_PLACEHOLDERS = List.of(
            "seviye", "ad", "hedef", "neden", "metrik", "deger", "esik",
            "ne", "gun", "tarih", "degisen", "sure", "saat");

    String template(String key) {
        return appSettings.getString("site.monitor.userpush.template." + key,
                DEFAULT_TEMPLATES.getOrDefault(key, DEFAULT_TEMPLATES.get("down")));
    }

    /** Olay → şablon ailesi. */
    static String templateKeyFor(String alertType, String trigger) {
        if ("RESOLVE".equals(trigger)) return "resolved";
        String t = alertType == null ? "" : alertType;
        if (t.contains("EXPIRY")) return "expiry";
        if (t.contains("CHANGED") || t.contains("TRANSFER_LOCK") || t.contains("BLACKLIST")) return "changed";
        if (t.contains("SLOW") || t.contains("THRESHOLD")) return "slow";
        return "down";
    }

    String buildMessage(AlertEvent event, String trigger, Map<String, Object> ctx) {
        String levelTr = switch (event.getAlertLevel() == null ? "" : event.getAlertLevel()) {
            case "CRITICAL" -> "KRİTİK";
            case "HIGH" -> "YÜKSEK";
            default -> "UYARI";
        };
        Map<String, String> vals = new LinkedHashMap<>();
        vals.put("seviye", levelTr);
        vals.put("ad", nz(event.getDomain(), "-"));
        vals.put("hedef", nz(event.getDomain(), "-"));
        vals.put("neden", firstLine(nz(event.getMessage(), "")));
        vals.put("metrik", ctxStr(ctx, "metric", "yanıt"));
        vals.put("deger", ctxStr(ctx, "value", "-"));
        vals.put("esik", ctxStr(ctx, "threshold", "-"));
        vals.put("ne", "süre");
        vals.put("gun", event.getDaysRemaining() == null ? "-" : String.valueOf(event.getDaysRemaining()));
        vals.put("tarih", ctxStr(ctx, "expiry_date", "-"));
        vals.put("degisen", firstLine(nz(event.getMessage(), "kayıt")));
        vals.put("sure", durationSince(event.getCreatedAt()));
        vals.put("saat", HHMM.format(Instant.now().atZone(ZONE)));
        String msg = fillTemplate(template(templateKeyFor(event.getAlertType(), trigger)), vals);
        if (msg.length() > MAX_MESSAGE_CHARS) msg = msg.substring(0, MAX_MESSAGE_CHARS - 1) + "…";
        return msg;
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

    private static String nz(String s, String fallback) { return s == null || s.isBlank() ? fallback : s; }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        String line = i < 0 ? s : s.substring(0, i);
        return line.length() > 120 ? line.substring(0, 120) : line;
    }

    private String durationSince(String createdAt) {
        try {
            var start = java.time.LocalDateTime.parse(createdAt, ISO);
            var mins = Duration.between(start, java.time.LocalDateTime.now(ZONE)).toMinutes();
            if (mins < 60) return mins + " dk";
            if (mins < 24 * 60) return (mins / 60) + " sa " + (mins % 60) + " dk";
            return (mins / (24 * 60)) + " gün";
        } catch (Exception e) {
            return "-";
        }
    }
}
