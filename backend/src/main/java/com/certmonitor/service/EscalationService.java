package com.certmonitor.service;

import com.certmonitor.model.AlertEvent;
import com.certmonitor.model.AlertThreshold;
import com.certmonitor.model.EscalationContact;
import com.certmonitor.model.LatestCheck;
import com.certmonitor.model.NotificationLog;
import com.certmonitor.repository.AlertEventRepository;
import com.certmonitor.repository.AlertThresholdRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.EscalationContactRepository;
import com.certmonitor.repository.LatestCheckRepository;
import com.certmonitor.repository.NotificationLogRepository;
import com.certmonitor.repository.TeamRepository;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EscalationService {

    private final AlertEventRepository alertEventRepo;
    private final AlertThresholdRepository thresholdRepo;
    private final EscalationContactRepository contactRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final EmailNotificationService emailService;
    private final WebhookService webhookService;
    private final ObjectMapper objectMapper;
    private final NotificationLogRepository notificationLogRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final TeamRepository teamRepo;
    private final SmtpSettingsService smtpSettings;

    // Self-injection (@Lazy avoids circular dep) — needed to invoke @Async methods via proxy
    @Autowired @Lazy
    private EscalationService self;

    // Inter-domain catch-up pacing now comes from the DB-backed SMTP settings
    // (admin Settings → SMTP → Gelişmiş), falling back to the env default.
    private long interDomainDelayMs() {
        Integer v = smtpSettings.getOrDefaults().getInterDomainDelayMs();
        return v != null ? v.longValue() : 3000L;
    }

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final Map<String, Integer> LEVEL_ORDER = Map.of(
            "WARNING", 1, "HIGH", 2, "CRITICAL", 3);

    /** HTTP erişilebilirlik kesintisi alarmı — uptime sweep'i tarafından yönetilir. */
    public static final String TYPE_ACCESSIBILITY = "ACCESSIBILITY";

    /** Port kesintisi alarmı — port sweep'i tarafından yönetilir. */
    public static final String TYPE_PORT_DOWN = "PORT_DOWN";

    /** DNS çözümleme hatası alarmı — DNS sweep'i tarafından yönetilir. */
    public static final String TYPE_DNS_FAILURE = "DNS_FAILURE";

    /** DNS kayıt değişikliği alarmı (YÜKSEK) — teyitsiz, otomatik kapanmaz. */
    public static final String TYPE_DNS_CHANGED = "DNS_CHANGED";

    /** Keyword monitor alarmı — keyword sweep'i tarafından yönetilir. */
    public static final String TYPE_KEYWORD = "KEYWORD";

    /** Ping (ICMP) kesintisi alarmı — ping sweep'i tarafından yönetilir. */
    public static final String TYPE_PING_DOWN = "PING_DOWN";

    /** İzleme kaynaklı alarm tipleri — kadanslarının sahibi ilgili sweep'lerdir;
     *  cert sweep'inin auto-resolve'u ve startup catch-up bunlara dokunmaz. */
    public static final Set<String> MONITORING_ALERT_TYPES =
            Set.of(TYPE_ACCESSIBILITY, TYPE_PORT_DOWN, TYPE_DNS_FAILURE, TYPE_DNS_CHANGED,
                   TYPE_KEYWORD, TYPE_PING_DOWN);

    /** Sertifika kaynaklı alarm tipleri — cert sweep'inin auto-resolve kapsamı.
     *  İzleme tipleri bilinçli olarak DIŞINDA: sertifika kontrolünün düzelmesi
     *  site erişiminin/portun/DNS'in düzeldiği anlamına gelmez (ve tersi). */
    public static final Set<String> CERT_ALERT_TYPES =
            Set.of("EXPIRY", "CHAIN_BROKEN", "REVOKED", "MISMATCH");

    public void processResults(List<Map<String, Object>> results) {
        AlertThreshold threshold = thresholdRepo.findFirstByActiveTrue()
                .orElseGet(this::defaultThreshold);

        // ── BATCH ÖN YÜKLEME (N+1 önleme) ──────────────────────────────────
        // Sweep'te 1000 result × 2 query = 2000 round-trip yerine: 2 query toplam.
        List<String> allDomains = results.stream()
                .map(r -> (String) r.get("domain"))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<String, com.certmonitor.model.CertificateInventory> invByDomain = allDomains.isEmpty()
                ? Map.of()
                : inventoryRepo.findByDomainIn(allDomains).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            com.certmonitor.model.CertificateInventory::getDomain,
                            inv -> inv,
                            (a, b) -> a));
        // (domain, alertType) -> AlertEvent (sadece resolved=false olanlar)
        Map<String, AlertEvent> openAlertByKey = allDomains.isEmpty()
                ? Map.of()
                : alertEventRepo.findOpenByDomainIn(allDomains).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            e -> e.getDomain() + "|" + e.getAlertType(),
                            e -> e,
                            (a, b) -> a.getCreatedAt() != null && b.getCreatedAt() != null
                                    && a.getCreatedAt().compareTo(b.getCreatedAt()) >= 0 ? a : b));

        for (Map<String, Object> result : results) {
            String domain = (String) result.get("domain");
            String alertType = determineAlertType(result);
            if (alertType == null) {
                // Sertifika sağlıklı — SADECE cert tiplerini kapat; açık bir
                // ACCESSIBILITY alarmı uptime sweep'inin sorumluluğundadır.
                resolveOpenAlertsForDomain(domain, CERT_ALERT_TYPES);
                continue;
            }

            String alertLevel = determineAlertLevel(result, alertType, threshold);
            if (alertLevel == null) continue;

            // Route alert to the team that owns this cert — batch'ten lookup
            var inventoryOpt  = Optional.ofNullable(invByDomain.get(domain));
            Long domainTeamId = inventoryOpt.map(com.certmonitor.model.CertificateInventory::getTeamId).orElse(null);
            Long ugTeamId     = inventoryOpt.map(com.certmonitor.model.CertificateInventory::getUgTeamId).orElse(null);

            Integer daysRemaining = toInt(result.get("days_remaining"));
            String message = buildMessage(domain, alertType, alertLevel, daysRemaining);

            Optional<AlertEvent> existing = Optional.ofNullable(openAlertByKey.get(domain + "|" + alertType));

            if (existing.isEmpty()) {
                AlertEvent event = newEvent(domain, alertLevel, alertType, message, daysRemaining);
                event = alertEventRepo.save(event);

                List<EscalationContact> contacts = getContactsForLevel(alertLevel, domainTeamId);
                sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType, message,
                        "", event.getId(), "INITIAL", daysRemaining, result);

                event.setNotifiedContacts(serializeContacts(contacts));
                event.setLastReAlertAt(now());
                alertEventRepo.save(event);

            } else {
                AlertEvent event = existing.get();
                boolean escalated = levelValue(alertLevel) > levelValue(event.getAlertLevel());

                if (escalated) {
                    event.setAlertLevel(alertLevel);
                    event.setMessage(message);
                    event.setDaysRemaining(daysRemaining);
                    event.setAcknowledged(false);

                    List<EscalationContact> contacts = getContactsForLevel(alertLevel, domainTeamId);
                    sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType, message,
                            "", event.getId(), "ESCALATION", daysRemaining, result);

                    event.setNotifiedContacts(serializeContacts(contacts));
                    event.setLastReAlertAt(now());
                    alertEventRepo.save(event);

                } else if (!event.getAcknowledged()) {
                    String lastAlertTime = event.getLastReAlertAt() != null
                            ? event.getLastReAlertAt() : event.getCreatedAt();
                    if (!isSameUtcDay(lastAlertTime, now())) {
                        List<EscalationContact> contacts = getContactsForLevel(alertLevel, domainTeamId);
                        sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                                "[RE-ALERT] " + message, "[RE-ALERT] ",
                                event.getId(), "DAILY_REALERT", daysRemaining, result);

                        event.setLastReAlertAt(now());
                        event.setDaysRemaining(daysRemaining);
                        event.setMessage(message);
                        alertEventRepo.save(event);
                        log.info("Re-alert sent: {} [{}] — previous day: {}",
                                domain, alertLevel, lastAlertTime.substring(0, 10));
                    } else {
                        log.debug("Alert already sent today, skipping: {} [{}] — last: {}",
                                domain, alertLevel, lastAlertTime.substring(0, 10));
                    }
                }
            }
        }
    }

    @Transactional
    public AlertEvent acknowledge(Long eventId, String acknowledgedBy) {
        AlertEvent event = alertEventRepo.findById(eventId)
                .orElseThrow(() -> new NoSuchElementException("Alert not found: " + eventId));
        event.setAcknowledged(true);
        event.setAcknowledgedBy(acknowledgedBy);
        event.setAcknowledgedAt(now());
        return alertEventRepo.save(event);
    }

    /**
     * Trigger immediate re-notification.
     * Quick: validates, reads contacts, persists last-realert timestamp, queues async send.
     * Returns immediately so HTTP request does not hold DB connection during SMTP I/O.
     */
    public Map<String, Object> reNotify(Long alertId) {
        AlertEvent event = alertEventRepo.findById(alertId)
                .orElseThrow(() -> new NoSuchElementException("Alert not found: " + alertId));
        if (Boolean.TRUE.equals(event.getResolved())) {
            throw new IllegalStateException("Alert is already resolved");
        }

        var inventoryOpt  = inventoryRepo.findByDomain(event.getDomain());
        Long domainTeamId = inventoryOpt.map(com.certmonitor.model.CertificateInventory::getTeamId).orElse(null);
        Long ugTeamId     = inventoryOpt.map(com.certmonitor.model.CertificateInventory::getUgTeamId).orElse(null);
        List<EscalationContact> contacts = getContactsForLevel(event.getAlertLevel(), domainTeamId);

        // Quick DB write — commits before async dispatch
        event.setNotifiedContacts(serializeContacts(contacts));
        event.setLastReAlertAt(now());
        alertEventRepo.save(event);

        // Count actual recipients (team emails + contacts, deduped) — same logic as sendCombinedAlert
        List<String> teamEmails = collectTeamEmails(domainTeamId, ugTeamId);
        Set<String> seen = new HashSet<>();
        int recipientCount = 0;
        for (String e : teamEmails) {
            if (e != null && !e.isBlank() && seen.add(e.toLowerCase())) recipientCount++;
        }
        for (EscalationContact c : contacts) {
            if (c.getEmail() != null && !c.getEmail().isBlank()
                    && seen.add(c.getEmail().trim().toLowerCase())) recipientCount++;
        }

        // Fire-and-forget async (self-proxy needed for @Async to engage)
        self.reNotifyAsync(event.getId(), domainTeamId, ugTeamId, contacts,
                           event.getDomain(), event.getAlertLevel(), event.getAlertType(),
                           event.getDaysRemaining());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alert_id",          event.getId());
        result.put("recipients_queued", recipientCount);
        result.put("contacts_queued",   contacts.size());
        result.put("status",            "queued");
        return result;
    }

    /**
     * Background SMTP + webhook dispatch. Runs on certCheckExecutor pool.
     * Does not hold DB connection from the originating HTTP request.
     */
    @Async("certCheckExecutor")
    public void reNotifyAsync(Long alertEventId, Long domainTeamId, Long ugTeamId,
                              List<EscalationContact> contacts, String domain,
                              String alertLevel, String alertType, Integer daysRemainingFallback) {
        try {
            // İzleme alarmlarında sertifika context'i alakasızdır — builder'lar
            // null-toleranslı, mail kind'e özgü şablondan üretilir.
            Map<String, Object> certContext = MONITORING_ALERT_TYPES.contains(alertType)
                    ? null
                    : latestCheckRepo.findById(domain)
                        .map(this::latestToCertContext).orElse(null);
            Integer freshDays     = certContext != null ? toInt(certContext.get("days_remaining")) : null;
            Integer effectiveDays = freshDays != null ? freshDays : daysRemainingFallback;
            String  freshMessage  = buildMessage(domain, alertType, alertLevel, effectiveDays);
            sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                    freshMessage, "[RE-ALERT] ", alertEventId, "MANUAL",
                    effectiveDays, certContext);
        } catch (Exception e) {
            log.error("Async reNotify failed for alertEventId={}: {}", alertEventId, e.getMessage(), e);
        }
    }

    public void catchUpMissedDailyAlerts() {
        String todayUtc = now().substring(0, 10);
        List<AlertEvent> openAlerts = alertEventRepo
                .findByResolvedFalseAndAcknowledgedFalseOrderByCreatedAtDesc();
        // N+1 önleme: re-alert adayı domain'lerin inventory + latest_check'ini TEK sorguda topla
        // (önceden döngü içinde her alarm için ayrı findByDomain + findById çalışıyordu).
        java.util.Set<String> candidateDomains = openAlerts.stream()
                .filter(e -> !MONITORING_ALERT_TYPES.contains(e.getAlertType()))
                .map(AlertEvent::getDomain).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, com.certmonitor.model.CertificateInventory> invByDomain = candidateDomains.isEmpty()
                ? Map.of()
                : inventoryRepo.findByDomainIn(candidateDomains).stream().collect(
                    java.util.stream.Collectors.toMap(com.certmonitor.model.CertificateInventory::getDomain, i -> i, (a, b) -> a));
        Map<String, com.certmonitor.model.LatestCheck> latestByDomain = candidateDomains.isEmpty()
                ? Map.of()
                : latestCheckRepo.findByDomainIn(candidateDomains).stream().collect(
                    java.util.stream.Collectors.toMap(com.certmonitor.model.LatestCheck::getDomain, l -> l, (a, b) -> a));
        int sent = 0;
        for (AlertEvent event : openAlerts) {
            // İzleme tiplerinin kadansının sahibi ilgili sweep'lerdir; restart
            // sonrası ilk sweep doğrulamadan bayat re-alert atılmasın.
            if (MONITORING_ALERT_TYPES.contains(event.getAlertType())) continue;
            String lastAlertTime = event.getLastReAlertAt() != null
                    ? event.getLastReAlertAt() : event.getCreatedAt();
            if (isSameUtcDay(lastAlertTime, todayUtc)) {
                log.debug("Catch-up: {} already notified today, skipping", event.getDomain());
                continue;
            }
            var inventoryOpt  = java.util.Optional.ofNullable(invByDomain.get(event.getDomain()));
            Long domainTeamId = inventoryOpt.map(com.certmonitor.model.CertificateInventory::getTeamId).orElse(null);
            Long ugTeamId     = inventoryOpt.map(com.certmonitor.model.CertificateInventory::getUgTeamId).orElse(null);
            List<EscalationContact> contacts = getContactsForLevel(event.getAlertLevel(), domainTeamId);
            Map<String, Object> certContext = java.util.Optional.ofNullable(latestByDomain.get(event.getDomain()))
                    .map(this::latestToCertContext).orElse(null);
            Integer freshDays     = certContext != null ? toInt(certContext.get("days_remaining")) : null;
            Integer effectiveDays = freshDays != null ? freshDays : event.getDaysRemaining();
            String  freshMessage  = buildMessage(event.getDomain(), event.getAlertType(),
                                                 event.getAlertLevel(), effectiveDays);
            sendCombinedAlert(domainTeamId, ugTeamId, contacts, event.getDomain(), event.getAlertLevel(), event.getAlertType(),
                    "[RE-ALERT] " + freshMessage, "[RE-ALERT] ",
                    event.getId(), "DAILY_REALERT", effectiveDays, certContext);
            event.setLastReAlertAt(now());
            event.setDaysRemaining(effectiveDays);
            alertEventRepo.save(event);
            sent++;
            log.info("Startup catch-up: alert sent for {} [{}] — last was: {}",
                    event.getDomain(), event.getAlertLevel(), lastAlertTime.substring(0, 10));
            // Pace between domain batches to avoid flooding the SMTP gateway
            try { Thread.sleep(interDomainDelayMs()); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("Startup catch-up complete — {} missed notification(s) sent", sent);
    }

    // DB save is sync (atomic + fast), mail notification is dispatched async on certCheckExecutor.
    // HTTP response returns in <1s even if SMTP times out.
    public AlertEvent resolve(Long eventId, String resolvedBy) {
        AlertEvent event = alertEventRepo.findById(eventId)
                .orElseThrow(() -> new NoSuchElementException("Alert not found: " + eventId));
        String by = resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "admin";
        event.setResolved(true);
        event.setResolvedAt(now());
        event.setResolvedBy(by);
        AlertEvent saved = alertEventRepo.save(event);
        self.sendResolutionNotificationAsync(saved, by, "MANUAL_RESOLVE");
        return saved;
    }

    private void resolveOpenAlertsForDomain(String domain, Collection<String> types) {
        List<AlertEvent> openAlerts = alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(domain, types);
        for (AlertEvent event : openAlerts) {
            event.setResolved(true);
            event.setResolvedAt(now());
            event.setResolvedBy("system");
            AlertEvent saved = alertEventRepo.save(event);
            self.sendResolutionNotificationAsync(saved, "Sistem (otomatik)", "RESOLUTION");
            log.info("✅ Alarm çözüldü: {} [{}] — sorun giderildi, otomatik kapatıldı",
                    domain, event.getAlertType());
        }
    }

    /** İzleme recovery'si — SADECE verilen tipin alarmlarını kapatır (çözüm maili ile). */
    public void resolveMonitoringAlertsForDomain(String domain, String alertType) {
        resolveOpenAlertsForDomain(domain, Set.of(alertType));
    }

    /**
     * MonitoringOutageService teyit zinciri tamamlandığında (ya da kesinti
     * sürerken her sweep'te / DNS_CHANGED'de anında) çağırır. processResults'un
     * INITIAL / DAILY_REALERT dallarının izleme aynası — seviye sabit
     * (CRITICAL ya da DNS_CHANGED için HIGH) olduğundan escalation dalı yoktur.
     */
    public void processConfirmedOutage(String domain, String alertType, String alertLevel,
                                       Map<String, Object> outageContext) {
        // Serbest-form izleme (keyword/ping) takımı outageContext.team_id'den gelir
        // (envantere bağlı değil); uptime/port/dns ise domain→envanter eşlemesinden.
        Long domainTeamId, ugTeamId;
        Object ctxTeam = outageContext != null ? outageContext.get("team_id") : null;
        if (ctxTeam instanceof Number teamNum) {
            domainTeamId = teamNum.longValue();
            ugTeamId = null;
        } else {
            var inventoryOpt = inventoryRepo.findByDomain(domain);
            domainTeamId = inventoryOpt.map(com.certmonitor.model.CertificateInventory::getTeamId).orElse(null);
            ugTeamId     = inventoryOpt.map(com.certmonitor.model.CertificateInventory::getUgTeamId).orElse(null);
        }
        // Serbest-form izleme (keyword/ping) alarmı YALNIZ takıma gider — müdür/eskalasyon kontağı eklenmez.
        boolean teamOnly = TYPE_KEYWORD.equals(alertType) || TYPE_PING_DOWN.equals(alertType);

        String message = monitoringMessage(domain, alertType, alertLevel, outageContext);
        Optional<AlertEvent> existing = alertEventRepo.findOpenAlert(domain, alertType);

        if (existing.isEmpty()) {
            AlertEvent event = newEvent(domain, alertLevel, alertType, message, null);
            event.setTeamId(domainTeamId);   // çözüm bildiriminde takımı buradan bul (özellikle keyword/ping)
            event = alertEventRepo.save(event);

            List<EscalationContact> contacts = teamOnly ? List.of() : getContactsForLevel(alertLevel, domainTeamId);
            sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                    message, "", event.getId(), "INITIAL", null, outageContext);

            event.setNotifiedContacts(serializeContacts(contacts));
            event.setLastReAlertAt(now());
            alertEventRepo.save(event);
            log.warn("🔴 İzleme alarmı oluşturuldu: {} [{}] {} — takım bilgilendirildi",
                    domain, alertType,
                    outageContext != null ? outageContext.getOrDefault("detail", "") : "");

        } else if (!existing.get().getAcknowledged()) {
            AlertEvent event = existing.get();
            String lastAlertTime = event.getLastReAlertAt() != null
                    ? event.getLastReAlertAt() : event.getCreatedAt();
            if (!isSameUtcDay(lastAlertTime, now())) {
                List<EscalationContact> contacts = teamOnly ? List.of() : getContactsForLevel(alertLevel, domainTeamId);
                sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                        "[RE-ALERT] " + message, "[RE-ALERT] ",
                        event.getId(), "DAILY_REALERT", null, outageContext);

                event.setLastReAlertAt(now());
                event.setMessage(message);
                alertEventRepo.save(event);
                log.info("İzleme re-alert gönderildi: {} [{}] — önceki gün: {}",
                        domain, alertType, lastAlertTime.substring(0, 10));
            } else {
                log.debug("İzleme alarmı bugün zaten gönderildi, atlanıyor: {} [{}]", domain, alertType);
            }
        }
        // acknowledged açık alarm → sessiz (expiry semantiğiyle aynı)
    }

    /** İzleme alarm mesajı — ctx alanları varsa zenginleştirilir, yoksa buildMessage'a düşer. */
    private String monitoringMessage(String domain, String alertType, String alertLevel,
                                     Map<String, Object> ctx) {
        if (ctx == null || ctx.isEmpty()) return buildMessage(domain, alertType, alertLevel, null);
        switch (alertType) {
            case TYPE_PORT_DOWN -> {
                Object port = ctx.get("port");
                Object proto = ctx.getOrDefault("protocol", "TCP");
                if (port != null) {
                    return "KRİTİK: " + domain + " üzerindeki " + port + "/" + proto +
                            " portuna erişilemiyor. Ardışık doğrulama denemeleri başarısız oldu. " +
                            "Port yeniden açıldığında alarm otomatik kapanacaktır.";
                }
            }
            case TYPE_DNS_FAILURE -> {
                Object rt = ctx.get("record_type");
                if (rt != null) {
                    return "KRİTİK: " + domain + " için " + rt +
                            " DNS sorgusu çözümlenemiyor. Ardışık doğrulama denemeleri başarısız oldu. " +
                            "Çözümleme düzeldiğinde alarm otomatik kapanacaktır.";
                }
            }
            case TYPE_DNS_CHANGED -> {
                Object rt = ctx.get("record_type");
                String olds = joinValues(ctx.get("old_values"));
                String news = joinValues(ctx.get("new_values"));
                if (rt != null && !news.isEmpty()) {
                    return "YÜKSEK: " + domain + " için " + rt + " kaydı değişti. " +
                            "Eski değer(ler): " + (olds.isEmpty() ? "—" : olds) +
                            " → Yeni değer(ler): " + news + ". " +
                            "Bu alarm otomatik kapanmaz; değişiklik planlı ise alarmı onaylayıp manuel kapatınız.";
                }
            }
            case TYPE_KEYWORD -> {
                Object url = ctx.get("url");
                Object kw = ctx.get("keyword");
                Object cond = ctx.getOrDefault("condition", "NOT_CONTAINS");
                if (url != null && kw != null) {
                    if ("CONTAINS".equals(cond)) {
                        return "KRİTİK: " + url + " sayfasında istenmeyen \"" + kw +
                                "\" ifadesi bulundu. Ardışık doğrulama denemeleri bunu doğruladı. " +
                                "İfade kaybolduğunda alarm otomatik kapanacaktır.";
                    }
                    return "KRİTİK: " + url + " sayfasında \"" + kw +
                            "\" anahtar kelimesi bulunamıyor. Ardışık doğrulama denemeleri başarısız oldu. " +
                            "Kelime tekrar göründüğünde alarm otomatik kapanacaktır.";
                }
            }
            case TYPE_PING_DOWN -> {
                Object host = ctx.get("host");
                if (host != null) {
                    return "KRİTİK: " + host + " ICMP ping'e yanıt vermiyor. " +
                            "Ardışık doğrulama denemeleri başarısız oldu. " +
                            "Host yeniden yanıt verdiğinde alarm otomatik kapanacaktır.";
                }
            }
            default -> { /* ACCESSIBILITY → buildMessage */ }
        }
        return buildMessage(domain, alertType, alertLevel, null);
    }

    @SuppressWarnings("unchecked")
    private static String joinValues(Object v) {
        if (v instanceof List<?> l) {
            return l.stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
        return v != null ? String.valueOf(v) : "";
    }

    /**
     * Silent close for inventory delete path — does NOT trigger resolution email.
     * Admin is intentionally decommissioning the domain; monitoring stops, so
     * spamming contacts with a "resolved" email would be noise.
     */
    public int closeAlertsOnInventoryDelete(String domain) {
        return closeOpenAlerts(domain, "inventory_delete", "envanter silindi");
    }

    /**
     * Silent close for inventory deactivation (active=false). Pasife alınan domain
     * artık taranmaz; açık alarmları otomatik çözülemeyeceğinden burada sessizce
     * (resolution maili olmadan) kapatılır. Delete'ten farkı: kayıt silinmez,
     * resolvedBy = "inventory_deactivate".
     */
    public int closeAlertsOnDeactivate(String domain) {
        return closeOpenAlerts(domain, "inventory_deactivate", "domain pasife alındı");
    }

    private int closeOpenAlerts(String domain, String resolvedBy, String reason) {
        List<AlertEvent> openAlerts = alertEventRepo.findByDomainAndResolvedFalse(domain);
        for (AlertEvent event : openAlerts) {
            event.setResolved(true);
            event.setResolvedAt(now());
            event.setResolvedBy(resolvedBy);
            alertEventRepo.save(event);
            log.info("Alarm kapatıldı ({}): {} [{}]", reason, domain, event.getAlertType());
        }
        return openAlerts.size();
    }

    /**
     * Startup safety net — closes any alarms that are still open on domains
     * already soft-deleted from inventory. Legacy state from before the live
     * inventory-delete close hook shipped (v18.9.0) is cleaned up automatically
     * on next application start. Silent (no resolution email), idempotent.
     */
    public int catchUpAlertsOnDeletedDomains() {
        List<AlertEvent> stuck = alertEventRepo.findOpenAlertsOnSoftDeletedDomains();
        for (AlertEvent event : stuck) {
            event.setResolved(true);
            event.setResolvedAt(now());
            event.setResolvedBy("inventory_delete");
            alertEventRepo.save(event);
            log.info("Startup catch-up: closed stale alarm {} [{}] for soft-deleted domain {}",
                    event.getId(), event.getAlertType(), event.getDomain());
        }
        if (!stuck.isEmpty()) {
            log.info("Startup catch-up complete — closed {} stale alarm(s) on soft-deleted domains",
                    stuck.size());
        }
        return stuck.size();
    }

    @Async("certCheckExecutor")
    public void sendResolutionNotificationAsync(AlertEvent event, String resolvedBy, String trigger) {
        try {
            sendResolutionNotification(event, resolvedBy, trigger);
        } catch (Exception e) {
            log.error("Async resolution notification failed for alertEventId={}: {}",
                    event != null ? event.getId() : null, e.getMessage(), e);
        }
    }

    private void sendResolutionNotification(AlertEvent event, String resolvedBy, String trigger) {
        try {
            // Keyword/Ping çözüm bildirimi YALNIZ takıma gider; takım AlertEvent.teamId'den (envanter değil).
            boolean teamOnly = TYPE_KEYWORD.equals(event.getAlertType()) || TYPE_PING_DOWN.equals(event.getAlertType());
            Long domainTeamId, ugTeamId;
            List<EscalationContact> contacts;
            if (teamOnly) {
                domainTeamId = event.getTeamId();
                ugTeamId = null;
                contacts = List.of();
            } else {
                var inventoryOpt = inventoryRepo.findByDomain(event.getDomain());
                domainTeamId = inventoryOpt.map(com.certmonitor.model.CertificateInventory::getTeamId).orElse(null);
                ugTeamId     = inventoryOpt.map(com.certmonitor.model.CertificateInventory::getUgTeamId).orElse(null);
                contacts = getContactsForLevel(event.getAlertLevel(), domainTeamId);
            }

            // Build combined TO: team emails + contact emails (deduped)
            List<String> teamEmails = collectTeamEmails(domainTeamId, ugTeamId);
            Set<String> seen = new HashSet<>();
            List<String> allEmails = new ArrayList<>();
            for (String e : teamEmails) {
                if (seen.add(e.toLowerCase())) allEmails.add(e);
            }
            for (EscalationContact c : contacts) {
                if (c.getEmail() != null && !c.getEmail().isBlank()
                        && seen.add(c.getEmail().trim().toLowerCase()))
                    allEmails.add(c.getEmail().trim());
            }
            if (allEmails.isEmpty()) {
                log.warn("Çözüm bildirimi — alıcı bulunamadı: {}", event.getDomain());
                return;
            }

            String typeTr = switch (event.getAlertType() != null ? event.getAlertType() : "") {
                case "REVOKED"          -> "İptal";
                case "MISMATCH"         -> "Dağıtım Eksik";
                case "CHAIN_BROKEN"     -> "Zincir Sorunu";
                case TYPE_ACCESSIBILITY -> "Erişim Kesintisi";
                case TYPE_PORT_DOWN     -> "Port Kesintisi";
                case TYPE_DNS_FAILURE   -> "DNS Çözümleme Hatası";
                case TYPE_DNS_CHANGED   -> "DNS Değişikliği";
                default                 -> "Son Kullanma";
            };
            String subject = "[CertMonitor ✅ ÇÖZÜLDÜ] " + event.getDomain()
                    + " — " + typeTr + " sorunu giderildi";
            // İzleme çözüm mailleri süreyi createdAt→resolvedAt'ten hesaplar;
            // sertifika context'i alakasız olduğundan geçilmez.
            Map<String, Object> certContext = MONITORING_ALERT_TYPES.contains(event.getAlertType())
                    ? null
                    : latestCheckRepo.findById(event.getDomain())
                        .map(this::latestToCertContext)
                        .orElse(null);
            String htmlBody = emailService.buildResolutionEmailHtml(
                    event.getDomain(), event.getAlertType(), event.getAlertLevel(),
                    event.getDaysRemaining(), resolvedBy, event.getResolvedAt(),
                    event.getCreatedAt(), certContext);
            String status = emailService.sendResolutionAlert(
                    allEmails.toArray(new String[0]), subject,
                    event.getDomain(), event.getAlertType(), event.getAlertLevel(),
                    event.getDaysRemaining(), resolvedBy, event.getResolvedAt(),
                    event.getCreatedAt(), certContext);
            String teamNames = collectTeamNames(domainTeamId, ugTeamId);
            saveLog(event.getId(), teamNames, String.join(", ", allEmails), subject, htmlBody, status, "SKIPPED", trigger);
            log.info("Çözüm bildirimi → [{}] status={}", String.join(", ", allEmails), status);
        } catch (Exception e) {
            log.warn("Çözüm bildirimi gönderilemedi: {} — {}", event.getDomain(), e.getMessage());
        }
    }

    private String determineAlertType(Map<String, Object> result) {
        String revocationStatus = (String) result.get("revocation_status");
        String deploymentStatus = (String) result.get("deployment_status");
        String chainStatus = (String) result.get("chain_status");
        Boolean warning = (Boolean) result.get("warning");
        String status = (String) result.get("status");

        if ("REVOKED".equals(revocationStatus)) return "REVOKED";
        if ("INCOMPLETE".equals(deploymentStatus)) return "MISMATCH";
        if ("BROKEN".equals(chainStatus)) return "CHAIN_BROKEN";
        if (Boolean.TRUE.equals(warning) || "error".equals(status)) return "EXPIRY";
        return null;
    }

    /** Sertifikaya erişilemediğini / sertifika bilgilerinin alınamadığını gösteren,
     *  ağ veya firewall kaynaklı ulaşılabilirlik hata sınıfları. Bunlar gerçek bir
     *  sertifika kusuru değildir; gerçek kusurlar REVOKED/MISMATCH/CHAIN_BROKEN ve
     *  eşik tabanlı (gün) son kullanma alarmlarıdır. */
    private static final Set<String> REACHABILITY_ERROR_CLASSES = Set.of("NETWORK", "DNS");

    private String determineAlertLevel(Map<String, Object> result, String alertType,
                                        AlertThreshold threshold) {
        // Doğrulanmış sertifika kusurları → her zaman KRİTİK (müdüre eskalasyon haklı).
        if ("REVOKED".equals(alertType) || "MISMATCH".equals(alertType)
                || "CHAIN_BROKEN".equals(alertType)) {
            return "CRITICAL";
        }
        // Sertifikaya erişilemedi / bilgileri alınamadı (status=error). Ağ veya firewall
        // kaynaklı bir ulaşılabilirlik hatası gerçek bir sertifika sorunu değildir →
        // müdürü KRİTİK ile rahatsız etmemek için UYARI seviyesinde tut; takım bildirimi
        // yeterli (UYARI seviyesinde yalnız WARNING kontağı alır, müdür HIGH/CRITICAL
        // kontağıdır → otomatik hariç). Müdür yalnız eşik tabanlı YÜKSEK/KRİTİK
        // (yaklaşan son kullanma) ve doğrulanmış kusurlarda bilgilendirilir.
        if ("error".equals(result.get("status"))) {
            String errorClass = result.get("error_class") instanceof String s ? s : null;
            if (errorClass != null && REACHABILITY_ERROR_CLASSES.contains(errorClass)) {
                return "WARNING";
            }
            // SSL/UNKNOWN — olası gerçek TLS/sertifika kusuru, mevcut davranış (KRİTİK) korunur.
            return "CRITICAL";
        }
        Integer days = toInt(result.get("days_remaining"));
        if (days == null) return "CRITICAL";
        if (days <= threshold.getCriticalDays()) return "CRITICAL";
        if (days <= threshold.getHighDays()) return "HIGH";
        if (days <= threshold.getWarningDays()) return "WARNING";
        return null;
    }

    private List<EscalationContact> getContactsForLevel(String level, Long teamId) {
        if (teamId != null) {
            List<EscalationContact> teamContacts = switch (level) {
                case "CRITICAL" -> contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(teamId);
                case "HIGH"     -> contactRepo.findByTeamIdAndMinAlertLevelInAndActiveTrue(teamId, List.of("WARNING", "HIGH"));
                default         -> contactRepo.findByTeamIdAndMinAlertLevelAndActiveTrue(teamId, "WARNING");
            };
            if (!teamContacts.isEmpty()) return teamContacts;
            // Fall back to global contacts (no team assigned) if team has none
            log.warn("No contacts for teamId={} at level={} — falling back to global contacts", teamId, level);
        }
        return switch (level) {
            case "CRITICAL" -> contactRepo.findByActiveTrueOrderByRoleAsc();
            case "HIGH"     -> contactRepo.findByMinAlertLevelInAndActiveTrue(List.of("WARNING", "HIGH"));
            default         -> contactRepo.findByMinAlertLevelAndActiveTrue("WARNING");
        };
    }

    private List<Map<String, String>> sendCombinedAlert(
                                                          Long syTeamId, Long ugTeamId,
                                                          List<EscalationContact> contacts,
                                                          String domain, String level,
                                                          String alertType, String message,
                                                          String subjectPrefix,
                                                          Long alertEventId, String trigger,
                                                          Integer daysRemaining,
                                                          Map<String, Object> certContext) {
        // 1. TO listesi: takım email'leri + kontaklar (dedup)
        List<String> teamEmails = collectTeamEmails(syTeamId, ugTeamId);
        Set<String> seen = new HashSet<>();
        List<String> allEmails = new ArrayList<>();
        for (String e : teamEmails) {
            if (seen.add(e.toLowerCase())) allEmails.add(e);
        }
        for (EscalationContact c : contacts) {
            if (c.getEmail() != null && !c.getEmail().isBlank()
                    && seen.add(c.getEmail().trim().toLowerCase()))
                allEmails.add(c.getEmail().trim());
        }
        if (allEmails.isEmpty()) {
            log.warn("No recipients for {} [{}] — skipping", domain, level);
            return List.of();
        }

        // 2. Subject
        String levelTr = switch (level != null ? level : "") {
            case "CRITICAL" -> "KRİTİK";
            case "HIGH"     -> "YÜKSEK";
            default         -> "UYARI";
        };
        String typeTr = switch (alertType != null ? alertType : "") {
            case "REVOKED"          -> "İptal Edildi";
            case "MISMATCH"         -> "Dağıtım Eksik";
            case "CHAIN_BROKEN"     -> "Zincir Sorunu";
            case TYPE_ACCESSIBILITY -> "Erişim Kesintisi";
            case TYPE_PORT_DOWN     -> "Port Kesintisi";
            case TYPE_DNS_FAILURE   -> "DNS Çözümleme Hatası";
            case TYPE_DNS_CHANGED   -> "DNS Değişikliği";
            default                 -> daysRemaining != null ? daysRemaining + " gün kaldı" : "Son Kullanma";
        };
        String subject = subjectPrefix + "[CertMonitor " + levelTr + "] " + domain + " — " + typeTr;

        // 3. Tek email — tüm alıcılara
        String[] toArr     = allEmails.toArray(new String[0]);
        String htmlBody    = emailService.buildAlertEmailHtml(
                subject, message, domain, level, alertType, daysRemaining, certContext);
        String emailStatus = emailService.sendAlert(
                toArr, subject, message, domain, level, alertType, daysRemaining, certContext);

        // 4. Email log — tek kayıt
        String teamNames = collectTeamNames(syTeamId, ugTeamId);
        saveLog(alertEventId, teamNames, String.join(", ", allEmails), subject, htmlBody, emailStatus, "SKIPPED", trigger);

        // 5. Webhook — kontaklara ayrı ayrı
        List<Map<String, String>> details = new ArrayList<>();
        for (EscalationContact c : contacts) {
            String webhookStatus = "SKIPPED";
            if (c.getWebhookUrl() != null && !c.getWebhookUrl().isBlank()) {
                try {
                    webhookService.send(c.getWebhookType(), c.getWebhookUrl(), subject, message, level);
                    webhookStatus = "SENT";
                } catch (Exception e) {
                    webhookStatus = "FAILED: " + e.getMessage();
                }
                saveLog(alertEventId, c, subject, htmlBody, "SKIPPED", webhookStatus, trigger);
            }
            Map<String, String> d = new LinkedHashMap<>();
            d.put("name",           c.getName());
            d.put("email",          c.getEmail());
            d.put("role",           c.getRole());
            d.put("subject",        subject);
            d.put("email_status",   emailStatus);
            d.put("webhook_status", webhookStatus);
            details.add(d);
        }

        log.info("Combined alert: {} [{}] → TO=[{}] | webhooks={} | trigger={}",
                domain, level, String.join(", ", allEmails), contacts.size(), trigger);
        return details;
    }

    private void saveLog(Long alertEventId, EscalationContact c,
                         String subject, String message,
                         String emailStatus, String webhookStatus, String trigger) {
        try {
            NotificationLog entry = new NotificationLog();
            entry.setAlertEventId(alertEventId);
            entry.setSentAt(now());
            entry.setRecipientName(c.getName());
            entry.setRecipientEmail(c.getEmail());
            entry.setRecipientRole(c.getRole());
            entry.setSubject(subject);
            entry.setMessage(message);
            entry.setEmailStatus(emailStatus);
            entry.setWebhookStatus(webhookStatus);
            entry.setTrigger(trigger);
            entry.setEmailFrom(emailService.getEmailFrom());
            notificationLogRepo.save(entry);
        } catch (Exception e) {
            log.warn("Bildirim logu kaydedilemedi: {}", e.getMessage());
        }
    }

    private void saveLog(Long alertEventId, String recipientName, String recipientEmail,
                         String subject, String message,
                         String emailStatus, String webhookStatus, String trigger) {
        try {
            NotificationLog entry = new NotificationLog();
            entry.setAlertEventId(alertEventId);
            entry.setSentAt(now());
            entry.setRecipientName(recipientName);
            entry.setRecipientEmail(recipientEmail);
            entry.setRecipientRole("COMBINED");
            entry.setSubject(subject);
            entry.setMessage(message);
            entry.setEmailStatus(emailStatus);
            entry.setWebhookStatus(webhookStatus);
            entry.setTrigger(trigger);
            entry.setEmailFrom(emailService.getEmailFrom());
            notificationLogRepo.save(entry);
        } catch (Exception e) {
            log.warn("Bildirim logu kaydedilemedi: {}", e.getMessage());
        }
    }

    private String collectTeamNames(Long syTeamId, Long ugTeamId) {
        List<String> names = new ArrayList<>();
        Set<String> seenEmails = new HashSet<>();
        for (Long teamId : List.of(
                syTeamId != null ? syTeamId : -1L,
                ugTeamId != null ? ugTeamId : -1L)) {
            if (teamId < 0) continue;
            teamRepo.findById(teamId).ifPresent(team -> {
                String email = team.getEmail() != null ? team.getEmail().trim() : "";
                if (!email.isBlank() && seenEmails.add(email.toLowerCase())) {
                    String name = team.getName() != null ? team.getName().trim() : "";
                    if (!name.isBlank()) names.add(name);
                }
            });
        }
        return String.join(", ", names);
    }

    private List<String> collectTeamEmails(Long syTeamId, Long ugTeamId) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Long teamId : List.of(
                syTeamId != null ? syTeamId : -1L,
                ugTeamId != null ? ugTeamId : -1L)) {
            if (teamId < 0) continue;
            teamRepo.findById(teamId).ifPresent(team -> {
                String email = team.getEmail() != null ? team.getEmail().trim() : "";
                if (!email.isBlank() && seen.add(email.toLowerCase()))
                    result.add(email);
            });
        }
        return result;
    }

    private Map<String, Object> latestToCertContext(LatestCheck l) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("revocation_status",  l.getRevocationStatus()  != null ? l.getRevocationStatus()  : "UNKNOWN");
        ctx.put("chain_status",       l.getChainStatus()       != null ? l.getChainStatus()       : "UNKNOWN");
        ctx.put("deployment_status",  l.getDeploymentStatus()  != null ? l.getDeploymentStatus()  : "UNKNOWN");
        ctx.put("days_remaining",     l.getDaysRemaining());
        ctx.put("not_after",          l.getNotAfter());
        ctx.put("not_before",         l.getNotBefore());
        ctx.put("issuer",             l.getIssuer());
        ctx.put("issuer_cn",          l.getIssuerCn());
        ctx.put("subject",            l.getSubject());
        ctx.put("fingerprint",        l.getFingerprint());
        ctx.put("checked_at",         l.getCheckedAt());
        return ctx;
    }

    private String buildMessage(String domain, String alertType, String alertLevel, Integer days) {
        return switch (alertType) {
            case TYPE_ACCESSIBILITY -> "KRİTİK: " + domain +
                    " adresine erişilemiyor. Ardışık doğrulama denemeleri başarısız oldu — " +
                    "site erişilemez durumda. Erişim geri geldiğinde alarm otomatik kapanacaktır.";
            case TYPE_PORT_DOWN -> "KRİTİK: " + domain +
                    " üzerinde izlenen porta erişilemiyor. Ardışık doğrulama denemeleri başarısız oldu. " +
                    "Port yeniden açıldığında alarm otomatik kapanacaktır.";
            case TYPE_DNS_FAILURE -> "KRİTİK: " + domain +
                    " için DNS sorgusu çözümlenemiyor. Ardışık doğrulama denemeleri başarısız oldu. " +
                    "Çözümleme düzeldiğinde alarm otomatik kapanacaktır.";
            case TYPE_DNS_CHANGED -> "YÜKSEK: " + domain +
                    " için izlenen DNS kaydı değişti. Bu alarm otomatik kapanmaz; " +
                    "değişiklik planlı ise alarmı onaylayıp manuel kapatınız.";
            case "REVOKED" -> "KRİTİK: " + domain +
                    " adresindeki sertifika İPTAL EDİLMİŞTİR. Trafik derhal yönlendirilmelidir.";
            case "MISMATCH" -> "DAĞITIM EKSİK: " + domain +
                    " için yenilenmiş bir sertifika mevcut ancak uç nokta eski sertifikayı sunmaya devam ediyor.";
            case "CHAIN_BROKEN" -> "ZİNCİR SORUNU: " + domain +
                    " sertifika zincirindeki bir ara veya kök CA sertifikası süresi dolmuş ya da geçersiz.";
            default -> {
                String lvl = switch (alertLevel != null ? alertLevel : "") {
                    case "CRITICAL" -> "KRİTİK";
                    case "HIGH"     -> "YÜKSEK";
                    default         -> "UYARI";
                };
                yield days != null
                    ? lvl + ": " + domain + " adresindeki sertifikanın süresi " + days + " gün içinde doluyor."
                    : lvl + ": " + domain + " adresindeki sertifikaya erişilemediği için sertifika bilgileri alınamadı (ağ/firewall kaynaklı olabilir).";
            }
        };
    }

    private AlertEvent newEvent(String domain, String level, String type, String message, Integer days) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertLevel(level);
        e.setAlertType(type);
        e.setMessage(message);
        e.setDaysRemaining(days);
        e.setAcknowledged(false);
        e.setResolved(false);
        e.setCreatedAt(now());
        return e;
    }

    private String serializeContacts(List<EscalationContact> contacts) {
        try {
            List<Map<String, String>> list = contacts.stream()
                    .map(c -> Map.of("name", c.getName(), "email", c.getEmail(), "role", c.getRole()))
                    .collect(Collectors.toList());
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private AlertThreshold defaultThreshold() {
        AlertThreshold t = new AlertThreshold();
        t.setWarningDays(30);
        t.setHighDays(15);
        t.setCriticalDays(7);
        t.setReAlertIntervalHours(24);
        return t;
    }

    /** ISO string'lerin UTC tarih kısımlarını (yyyy-MM-dd) karşılaştırır. */
    private boolean isSameUtcDay(String iso1, String iso2) {
        try {
            return iso1.substring(0, 10).equals(iso2.substring(0, 10));
        } catch (Exception e) {
            return false;
        }
    }

    private int levelValue(String level) {
        return LEVEL_ORDER.getOrDefault(level, 0);
    }

    private Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    private String now() {
        return ISO.format(Instant.now());
    }
}
