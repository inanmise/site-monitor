package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
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
import java.time.LocalDateTime;
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
    private final WeeklyAvailabilityReportService weeklyAvailability;   // recovery uptime özeti (döngü yok)
    private final WebhookService webhookService;
    private final ObjectMapper objectMapper;
    private final NotificationLogRepository notificationLogRepo;
    private final LatestCheckRepository latestCheckRepo;
    private final TeamRepository teamRepo;
    private final SmtpSettingsService smtpSettings;
    private final MaintenanceService maintenanceService;
    private final StormService stormService;
    // Domain monitör alarmlarının resend/çözüm mailine EN GÜNCEL kayıt bağlamını (bitiş/registrar/EPP) kurmak için.
    private final com.sitemonitor.repository.DomainMonitorRepository domainMonitorRepo;
    private final com.sitemonitor.repository.DomainCheckRepository domainCheckRepo;
    // DNS_CHANGED manuel re-notify'ında eski/yeni değer ctx'ini son changed kayıttan kurmak için (günlük re-alert paritesi).
    private final com.sitemonitor.repository.DnsRecordRepository dnsRecordRepo;
    // Sayfa çözüm mailinde "güncel durum" satırı için son PageCheck (2026-08-04 — çözüm maili detayları).
    private final com.sitemonitor.repository.PageCheckRepository pageCheckRepo;

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
    /** Sabit-genişlik ISO string'i LocalDateTime'a geri ayrıştırmak için (re-alert kadans matematiği). */
    private static final DateTimeFormatter LDT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final Map<String, Integer> LEVEL_ORDER = Map.of(
            "WARNING", 1, "HIGH", 2, "CRITICAL", 3);

    /** HTTP erişilebilirlik kesintisi alarmı — uptime sweep'i tarafından yönetilir. */
    public static final String TYPE_ACCESSIBILITY = "ACCESSIBILITY";

    /** Port kesintisi alarmı — port sweep'i tarafından yönetilir. */
    public static final String TYPE_PORT_DOWN = "PORT_DOWN";

    /** Port yavaş yanıt alarmı — port sweep'i tarafından yönetilir (PORT_DOWN'dan AYRI; aynı envanter-yönlendirmesi). */
    public static final String TYPE_PORT_SLOW = "PORT_SLOW";

    /** DNS çözümleme hatası alarmı — DNS sweep'i tarafından yönetilir. */
    public static final String TYPE_DNS_FAILURE = "DNS_FAILURE";

    /** DNS kayıt değişikliği alarmı (YÜKSEK) — teyitsiz, otomatik kapanmaz. */
    public static final String TYPE_DNS_CHANGED = "DNS_CHANGED";

    /** DNS yavaş/timeout'lu çözümleme alarmı (YÜKSEK) — çözüm başarılı ama response süresi eşiği aşıyor
     *  (primary DNS timeout → fallback). DNS sweep teyit zinciriyle doğrular. */
    public static final String TYPE_DNS_SLOW = "DNS_SLOW";

    /** DNS beklenen-değer kilidi alarmı (YÜKSEK) — canlı sonuçta, monitöre sabitlenen "beklenen değer"de
     *  OLMAYAN bir değer çözümlenir (esnek/hijack-odaklı). State alarmı: değer beklenene dönünce oto-kapanır. */
    public static final String TYPE_DNS_UNEXPECTED = "DNS_UNEXPECTED";

    /** DNS çoklu-resolver tutarsızlığı alarmı (YÜKSEK) — domain public resolver'lar (8.8.8.8/1.1.1.1/...)
     *  arasında farklı cevap döndürür (propagation gecikmesi / split-DNS / poisoning). State; oto-kapanır. */
    public static final String TYPE_DNS_INCONSISTENT = "DNS_INCONSISTENT";

    /** Keyword monitor alarmı — keyword sweep'i tarafından yönetilir. */
    public static final String TYPE_KEYWORD = "KEYWORD";

    /** Keyword monitör yan alarmları (yavaş yanıt / URL host'unun SSL / domain bitişi) — HTTP tiplerinden AYRI. */
    public static final String TYPE_KEYWORD_SLOW = "KEYWORD_SLOW";
    public static final String TYPE_KEYWORD_SSL = "KEYWORD_SSL";
    public static final String TYPE_KEYWORD_DOMAIN_EXPIRY = "KEYWORD_DOMAIN_EXPIRY";
    public static boolean isKeywordAux(String t) {
        return TYPE_KEYWORD_SLOW.equals(t) || TYPE_KEYWORD_SSL.equals(t) || TYPE_KEYWORD_DOMAIN_EXPIRY.equals(t);
    }

    /** Ping (ICMP) kesintisi alarmı — ping sweep'i tarafından yönetilir. */
    public static final String TYPE_PING_DOWN = "PING_DOWN";

    /** HTTP/Website erişilebilirlik kesintisi alarmı — HTTP sweep'i tarafından yönetilir. */
    public static final String TYPE_HTTP_DOWN = "HTTP_DOWN";

    /** HTTP monitörü TLS sertifika hatası/bitişi alarmı — yavaş SSL döngüsü tarafından yönetilir. */
    public static final String TYPE_HTTP_SSL = "HTTP_SSL";

    /** Domain (registrar/WHOIS) kayıt bitişi alarmı — yavaş domain döngüsü tarafından yönetilir. */
    public static final String TYPE_DOMAIN_EXPIRY = "DOMAIN_EXPIRY";

    /** Bağımsız Domain izleme tipi alarmları (DOMAINMON_* — HTTP'nin DOMAIN_EXPIRY'sinden AYRI, domain-anahtarlı). */
    public static final String TYPE_DOMAINMON_EXPIRY  = "DOMAINMON_EXPIRY";
    public static final String TYPE_DOMAINMON_UNKNOWN = "DOMAINMON_UNKNOWN";
    public static final String TYPE_DOMAINMON_STATUS  = "DOMAINMON_STATUS";
    public static final String TYPE_DOMAINMON_CHANGED = "DOMAINMON_CHANGED";
    public static boolean isDomainMon(String t) {
        return TYPE_DOMAINMON_EXPIRY.equals(t) || TYPE_DOMAINMON_UNKNOWN.equals(t)
            || TYPE_DOMAINMON_STATUS.equals(t) || TYPE_DOMAINMON_CHANGED.equals(t);
    }

    /** Sayfa-bütünlüğü (9. tür) alarmları: DOWN = ana sayfa alınamıyor (CRITICAL); INTEGRITY = kırık kaynak /
     *  mixed content (DEGRADED, HIGH). İkisi de Page sweep'i tarafından, ayrı confirmation/recovery yaşam
     *  döngüleriyle yönetilir (KEYWORD'ün çok-alarm-tipli deseniyle aynı). */
    public static final String TYPE_PAGE_DOWN = "PAGE_DOWN";
    public static final String TYPE_PAGE_INTEGRITY = "PAGE_INTEGRITY";
    public static boolean isPage(String t) {
        return TYPE_PAGE_DOWN.equals(t) || TYPE_PAGE_INTEGRITY.equals(t);
    }

    /** Senaryo İzleme (k6): FAIL = PASS değil (kesinti); SLOW = koşum süresi opt-in eşiği aştı (kesinti DEĞİL). */
    public static final String TYPE_SCRIPTED_FAIL = "SCRIPTED_FAIL";
    public static final String TYPE_SCRIPTED_SLOW = "SCRIPTED_SLOW";
    public static boolean isScripted(String t) {
        return TYPE_SCRIPTED_FAIL.equals(t) || TYPE_SCRIPTED_SLOW.equals(t);
    }

    /** İzleme kaynaklı alarm tipleri — kadanslarının sahibi ilgili sweep'lerdir;
     *  cert sweep'inin auto-resolve'u ve startup catch-up bunlara dokunmaz. */
    public static final Set<String> MONITORING_ALERT_TYPES =
            Set.of(TYPE_ACCESSIBILITY, TYPE_PORT_DOWN, TYPE_DNS_FAILURE, TYPE_DNS_CHANGED,
                   TYPE_DNS_SLOW, TYPE_DNS_UNEXPECTED, TYPE_DNS_INCONSISTENT,
                   TYPE_KEYWORD, TYPE_PING_DOWN, TYPE_HTTP_DOWN, TYPE_HTTP_SSL, TYPE_DOMAIN_EXPIRY,
                   TYPE_DOMAINMON_EXPIRY, TYPE_DOMAINMON_UNKNOWN, TYPE_DOMAINMON_STATUS, TYPE_DOMAINMON_CHANGED,
                   TYPE_KEYWORD_SLOW, TYPE_KEYWORD_SSL, TYPE_KEYWORD_DOMAIN_EXPIRY, TYPE_PORT_SLOW,
                   TYPE_PAGE_DOWN, TYPE_PAGE_INTEGRITY, TYPE_SCRIPTED_FAIL, TYPE_SCRIPTED_SLOW);

    /** Sertifika kaynaklı alarm tipleri — cert sweep'inin auto-resolve kapsamı.
     *  İzleme tipleri bilinçli olarak DIŞINDA: sertifika kontrolünün düzelmesi
     *  site erişiminin/portun/DNS'in düzeldiği anlamına gelmez (ve tersi). */
    public static final Set<String> CERT_ALERT_TYPES =
            Set.of("EXPIRY", "CHAIN_BROKEN", "REVOKED", "MISMATCH");

    /** "Erişilemez/çöktü" (DOWN) alarm tipleri — alarm fırtınası (storm) toplaması YALNIZ bunları sayar.
     *  Slow/SSL/expiry/changed/domainmon/cert bilinçli DIŞINDA (bunlar kesinti değildir). */
    public static final Set<String> DOWN_ALERT_TYPES =
            Set.of(TYPE_ACCESSIBILITY, TYPE_HTTP_DOWN, TYPE_PORT_DOWN, TYPE_PING_DOWN, TYPE_DNS_FAILURE, TYPE_KEYWORD,
                   TYPE_PAGE_DOWN, TYPE_SCRIPTED_FAIL);

    public void processResults(List<Map<String, Object>> results) {
        AlertThreshold threshold = thresholdRepo.findFirstByActiveTrue()
                .orElseGet(this::defaultThreshold);
        int reAlertIv = threshold.getReAlertIntervalHours() != null ? threshold.getReAlertIntervalHours() : 24;

        // ── BATCH ÖN YÜKLEME (N+1 önleme) ──────────────────────────────────
        // Sweep'te 1000 result × 2 query = 2000 round-trip yerine: 2 query toplam.
        List<String> allDomains = results.stream()
                .map(r -> (String) r.get("domain"))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, com.sitemonitor.model.CertificateInventory> invByDomain = allDomains.isEmpty()
                ? Map.of()
                : inventoryRepo.findByDomainIn(allDomains).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            com.sitemonitor.model.CertificateInventory::getDomain,
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
            Long domainTeamId = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getTeamId).orElse(null);
            Long ugTeamId     = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getUgTeamId).orElse(null);

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
                    if (reAlertDue(lastAlertTime, now(), reAlertIv)) {
                        List<EscalationContact> contacts = getContactsForLevel(alertLevel, domainTeamId);
                        sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                                "[RE-ALERT] " + message, "[RE-ALERT] ",
                                event.getId(), "DAILY_REALERT", daysRemaining, result);

                        event.setLastReAlertAt(now());
                        event.setRealertCount((event.getRealertCount() == null ? 0 : event.getRealertCount()) + 1);
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
        return reNotify(alertId, Set.of());   // toplu (bulk) yol ve eski çağrılar — hariç tutma yok
    }

    /** Manuel re-notify hedefleri: takımlar + (varsa) eskalasyon kontakları. */
    private record ReNotifyTargets(Long domainTeamId, Long ugTeamId, List<EscalationContact> contacts) {}

    /** Onay pop-up'ında gösterilen tek alıcı satırı. kind: TEAM | CONTACT. */
    public record ReNotifyRecipient(String email, String name, String role, String kind) {}

    /**
     * Alıcı çözümü çözüm/ilk-alarm bildirimiyle AYNI olmalı: standalone izleme (domain/keyword/ping/http) takımı
     * cert envanterinden DEĞİL AlertEvent.teamId'den bulunur (aksi halde envanterde olmayan domain monitörü global
     * müdüre düşer). KRİTİK domain alarmında müdür (eskalasyon kontağı) da eklenir; diğer standalone → yalnız takım.
     * reNotify ve previewReNotify BUNU paylaşır — önizleme ile gerçek gönderim asla sapamaz.
     */
    private ReNotifyTargets resolveReNotifyTargets(AlertEvent event) {
        boolean standalone = isStandaloneMon(event.getAlertType());
        if (standalone) {
            List<EscalationContact> contacts = includeManagerContacts(event.getAlertType(), event.getAlertLevel())
                    ? getContactsForLevel(event.getAlertLevel(), event.getTeamId())
                    : List.of();
            return new ReNotifyTargets(event.getTeamId(), null, contacts);
        }
        var inventoryOpt = inventoryRepo.findByDomain(event.getDomain());
        Long domainTeamId = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getTeamId).orElse(null);
        Long ugTeamId     = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getUgTeamId).orElse(null);
        return new ReNotifyTargets(domainTeamId, ugTeamId, getContactsForLevel(event.getAlertLevel(), domainTeamId));
    }

    /**
     * "Tekrar Bildir" onay pop-up'ı için alıcı önizlemesi — sendCombinedAlert sırasıyla (önce takım
     * e-postaları, sonra kontaklar) dedupe'lu liste döner. HİÇBİR yazma yapmaz (notifiedContacts /
     * lastReAlertAt / log / async gönderim yok); hata semantiği reNotify ile birebir aynı.
     */
    public List<ReNotifyRecipient> previewReNotify(Long alertId) {
        AlertEvent event = alertEventRepo.findById(alertId)
                .orElseThrow(() -> new NoSuchElementException("Alert not found: " + alertId));
        if (Boolean.TRUE.equals(event.getResolved())) {
            throw new IllegalStateException("Alert is already resolved");
        }
        ReNotifyTargets targets = resolveReNotifyTargets(event);
        List<ReNotifyRecipient> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] team : collectTeamRecipients(targets.domainTeamId(), targets.ugTeamId())) {
            if (seen.add(team[0].toLowerCase())) out.add(new ReNotifyRecipient(team[0], team[1], null, "TEAM"));
        }
        for (EscalationContact c : targets.contacts()) {
            if (c.getEmail() != null && !c.getEmail().isBlank()
                    && seen.add(c.getEmail().trim().toLowerCase()))
                out.add(new ReNotifyRecipient(c.getEmail().trim(), c.getName(), c.getRole(), "CONTACT"));
        }
        return out;
    }

    /**
     * Manuel re-notify — {@code excludeEmails}: kullanıcının onay pop-up'ında listeden çıkardığı
     * adresler (case-insensitive). Hariç tutulan KONTAĞIN e-postası da webhook'u da gönderilmez
     * (tek filtrelenmiş contacts listesi ikisini de besler). Send, alıcıları CANLI yeniden çözer;
     * önizleme ile gönderim arasında EKLENEN kontak maili alır (kabul edilen yarış durumu).
     */
    public Map<String, Object> reNotify(Long alertId, Set<String> excludeEmails) {
        AlertEvent event = alertEventRepo.findById(alertId)
                .orElseThrow(() -> new NoSuchElementException("Alert not found: " + alertId));
        if (Boolean.TRUE.equals(event.getResolved())) {
            throw new IllegalStateException("Alert is already resolved");
        }
        Set<String> excluded = excludeEmails == null ? Set.of()
                : excludeEmails.stream().filter(Objects::nonNull)
                    .map(e -> e.trim().toLowerCase()).collect(java.util.stream.Collectors.toSet());

        ReNotifyTargets targets = resolveReNotifyTargets(event);
        Long domainTeamId = targets.domainTeamId(), ugTeamId = targets.ugTeamId();
        // Kontak filtresi serializeContacts + webhook'tan ÖNCE — hariç tutulan kontak hiçbir kanaldan bildirilmez.
        List<EscalationContact> contacts = targets.contacts().stream()
                .filter(c -> c.getEmail() == null || c.getEmail().isBlank()
                        || !excluded.contains(c.getEmail().trim().toLowerCase()))
                .toList();

        // Count actual recipients (team emails + contacts, deduped, exclusions applied) — same logic as sendCombinedAlert
        List<String> teamEmails = collectTeamEmails(domainTeamId, ugTeamId);
        Set<String> seen = new HashSet<>();
        int recipientCount = 0;
        for (String e : teamEmails) {
            if (e != null && !e.isBlank() && !excluded.contains(e.trim().toLowerCase())
                    && seen.add(e.toLowerCase())) recipientCount++;
        }
        for (EscalationContact c : contacts) {
            if (c.getEmail() != null && !c.getEmail().isBlank()
                    && seen.add(c.getEmail().trim().toLowerCase())) recipientCount++;
        }
        if (recipientCount == 0 && !excluded.isEmpty()) {
            // Kullanıcı pop-up'ta TÜM alıcıları çıkardıysa DB'ye yazmadan reddet (→ 400).
            // Doğal sıfır-alıcı durumu (kontak/takım maili hiç yok) ESKİ davranışında kalır:
            // queued döner, sendCombinedAlert boş listeyle gönderimi zaten atlar.
            throw new IllegalArgumentException("Tüm alıcılar hariç tutuldu — en az bir alıcı seçin");
        }

        // Quick DB write — commits before async dispatch
        event.setNotifiedContacts(serializeContacts(contacts));
        event.setLastReAlertAt(now());
        alertEventRepo.save(event);

        // Fire-and-forget async (self-proxy needed for @Async to engage)
        self.reNotifyAsync(event.getId(), domainTeamId, ugTeamId, contacts,
                           event.getDomain(), event.getAlertLevel(), event.getAlertType(),
                           event.getDaysRemaining(), excluded);

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
                              String alertLevel, String alertType, Integer daysRemainingFallback,
                              Set<String> excludeEmails) {
        try {
            // İçerik bağlamı (mail detay tablosu): domain monitörü → EN GÜNCEL domain_checks (bitiş/registrar/EPP);
            // DNS_CHANGED → son changed dns_records kaydından eski/yeni değerler (günlük re-alert ile aynı kaynak;
            // yoksa mail ESKİ/YENİ kutuları boş gidiyordu — 2026-08-03 bug'ı); diğer serbest-form izleme → yok
            // (tipe özel şablon; ctx reconstruction follow-up); sertifika → latest_check.
            // NOT: monitörün dns_change_alert_enabled=false olması MANUEL Tekrar Bildir'i ENGELLEMEZ —
            // bastırma yalnız otomatik günlük re-alert içindir, manuel gönderim operatör iradesidir.
            Map<String, Object> certContext;
            if (isDomainMon(alertType)) {
                certContext = reconstructDomainContext(domain);
            } else if (TYPE_DNS_CHANGED.equals(alertType)) {
                certContext = DnsCheckerService.changeCtxOf(
                        DnsCheckerService.lastChangedRecord(dnsRecordRepo, domain));
                if (certContext.isEmpty()) certContext = null;
            } else if (MONITORING_ALERT_TYPES.contains(alertType)) {
                certContext = null;
            } else {
                certContext = latestCheckRepo.findById(domain).map(this::latestToCertContext).orElse(null);
            }
            Integer freshDays     = certContext != null ? toInt(certContext.get("days_remaining")) : null;
            Integer effectiveDays = freshDays != null ? freshDays : daysRemainingFallback;
            String  freshMessage  = TYPE_DNS_CHANGED.equals(alertType) && certContext != null
                    ? monitoringMessage(domain, alertType, alertLevel, certContext)   // eski→yeni değerli zengin mesaj
                    : buildMessage(domain, alertType, alertLevel, effectiveDays);
            sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                    freshMessage, "[RE-ALERT] ", alertEventId, "MANUAL",
                    effectiveDays, certContext, excludeEmails == null ? Set.of() : excludeEmails);
        } catch (Exception e) {
            log.error("Async reNotify failed for alertEventId={}: {}", alertEventId, e.getMessage(), e);
        }
    }

    public void catchUpMissedDailyAlerts() {
        int reAlertIv = reAlertIntervalHours();
        List<AlertEvent> openAlerts = alertEventRepo
                .findByResolvedFalseAndAcknowledgedFalseOrderByCreatedAtDesc();
        // N+1 önleme: re-alert adayı domain'lerin inventory + latest_check'ini TEK sorguda topla
        // (önceden döngü içinde her alarm için ayrı findByDomain + findById çalışıyordu).
        Set<String> candidateDomains = openAlerts.stream()
                .filter(e -> !MONITORING_ALERT_TYPES.contains(e.getAlertType()))
                .map(AlertEvent::getDomain).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, com.sitemonitor.model.CertificateInventory> invByDomain = candidateDomains.isEmpty()
                ? Map.of()
                : inventoryRepo.findByDomainIn(candidateDomains).stream().collect(
                    java.util.stream.Collectors.toMap(com.sitemonitor.model.CertificateInventory::getDomain, i -> i, (a, b) -> a));
        Map<String, com.sitemonitor.model.LatestCheck> latestByDomain = candidateDomains.isEmpty()
                ? Map.of()
                : latestCheckRepo.findByDomainIn(candidateDomains).stream().collect(
                    java.util.stream.Collectors.toMap(com.sitemonitor.model.LatestCheck::getDomain, l -> l, (a, b) -> a));
        int sent = 0;
        for (AlertEvent event : openAlerts) {
            // İzleme tiplerinin kadansının sahibi ilgili sweep'lerdir; restart
            // sonrası ilk sweep doğrulamadan bayat re-alert atılmasın.
            if (MONITORING_ALERT_TYPES.contains(event.getAlertType())) continue;
            String lastAlertTime = event.getLastReAlertAt() != null
                    ? event.getLastReAlertAt() : event.getCreatedAt();
            if (!reAlertDue(lastAlertTime, now(), reAlertIv)) {
                log.debug("Catch-up: {} re-alert interval not elapsed, skipping", event.getDomain());
                continue;
            }
            var inventoryOpt  = Optional.ofNullable(invByDomain.get(event.getDomain()));
            Long domainTeamId = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getTeamId).orElse(null);
            Long ugTeamId     = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getUgTeamId).orElse(null);
            List<EscalationContact> contacts = getContactsForLevel(event.getAlertLevel(), domainTeamId);
            Map<String, Object> certContext = Optional.ofNullable(latestByDomain.get(event.getDomain()))
                    .map(this::latestToCertContext).orElse(null);
            Integer freshDays     = certContext != null ? toInt(certContext.get("days_remaining")) : null;
            Integer effectiveDays = freshDays != null ? freshDays : event.getDaysRemaining();
            String  freshMessage  = buildMessage(event.getDomain(), event.getAlertType(),
                                                 event.getAlertLevel(), effectiveDays);
            sendCombinedAlert(domainTeamId, ugTeamId, contacts, event.getDomain(), event.getAlertLevel(), event.getAlertType(),
                    "[RE-ALERT] " + freshMessage, "[RE-ALERT] ",
                    event.getId(), "DAILY_REALERT", effectiveDays, certContext);
            event.setLastReAlertAt(now());
            event.setRealertCount((event.getRealertCount() == null ? 0 : event.getRealertCount()) + 1);
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
        // İdempotent: zaten çözülmüş bir alarmı yeniden çözme — çift "çözüldü" e-postası gönderme ve
        // resolvedAt/resolvedBy'ı ezme (çift tık / manuel-çözüm ile oto-recovery yarışı). reNotify'ın aynası.
        if (Boolean.TRUE.equals(event.getResolved())) {
            log.debug("Alarm zaten çözülmüş, tekrar çözülmüyor (idempotent): {} [{}]", event.getDomain(), event.getAlertType());
            return event;
        }
        String by = resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "admin";
        event.setResolved(true);
        event.setResolvedAt(now());
        event.setResolvedBy(by);
        AlertEvent saved = alertEventRepo.save(event);
        self.sendResolutionNotificationAsync(saved, by, "MANUAL_RESOLVE");
        return saved;
    }

    private void resolveOpenAlertsForDomain(String domain, Collection<String> types) {
        // Bakım penceresinde recovery: alarm kapanır ama çözüm e-postası GÖNDERİLMEZ (tam sessizlik).
        if (maintenanceService.isUnderMaintenance(domain)) {
            resolveOpenAlertsSilently(domain, types, "Sistem (bakım penceresi — sessiz kapanış)");
            return;
        }
        List<AlertEvent> openAlerts = alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(domain, types);
        for (AlertEvent event : openAlerts) {
            event.setResolved(true);
            event.setResolvedAt(now());
            event.setResolvedBy("system");
            AlertEvent saved = alertEventRepo.save(event);
            // Storm üyesi + storm hâlâ aktif → bireysel çözüm e-postası GÖNDERME
            // (TEK toplu recovery, fırtına dağıldığında storm sweep'inden gider). Incident yine kapandı.
            if (saved.getStormId() != null && stormService.isActive(saved.getStormId())) {
                log.info("✅ Alarm çözüldü (storm üyesi — bireysel çözüm maili yok): {} [{}]", domain, event.getAlertType());
                continue;
            }
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
     * İzleme SİLİNDİĞİNDE açık alarmları SESSİZCE kapatır — resolve/çözüldü maili
     * GÖNDERİLMEZ (kapanma silme kaynaklı; kullanıcı bildirim istemiyor). Alarm
     * geçmişinde resolved (kapalı) görünür, resolvedBy = silme nedenidir.
     */
    public void resolveOpenAlertsSilently(String domain, Collection<String> types, String resolvedBy) {
        if (domain == null || types == null || types.isEmpty()) return;
        String by = resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "Sistem (izleme silindi)";
        List<AlertEvent> openAlerts = alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(domain, types);
        for (AlertEvent event : openAlerts) {
            event.setResolved(true);
            event.setResolvedAt(now());
            event.setResolvedBy(by);
            alertEventRepo.save(event);   // mail YOK — sendResolutionNotification çağrılmaz
            log.info("✅ Alarm sessizce kapatıldı (izleme silindi, mail yok): {} [{}]", domain, event.getAlertType());
        }
    }

    /**
     * Öksüz ping alarmı temizliği — aktif/pasif HİÇBİR ping monitör host'una karşılık gelmeyen açık
     * PING_DOWN alarmlarını SESSİZCE kapatır. Host rename (updatePing eski host'u değiştirir) veya eski
     * kayıt sonrası recovery bu alarmı asla resolve edemez: kapatma domain=host ile aranır, ama o host
     * artık hiçbir monitörde yok → alarm süresiz açık kalır. Mevcut host kümesinde olmayan domain'ler öksüz.
     * @param existingHosts aktif + pasif tüm ping monitörlerinin host'ları (silinen/yeniden adlandırılan hariç)
     * @return kapatılan öksüz domain sayısı
     */
    public int resolveOrphanedPingAlerts(Set<String> existingHosts) {
        if (existingHosts == null) return 0;
        Set<String> orphanDomains = new HashSet<>();
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (!TYPE_PING_DOWN.equals(e.getAlertType())) continue;
            if (e.getDomain() == null || existingHosts.contains(e.getDomain())) continue;  // eşleşen monitör var → dokunma
            orphanDomains.add(e.getDomain());
        }
        for (String d : orphanDomains) {
            resolveOpenAlertsSilently(d, Set.of(TYPE_PING_DOWN), "Sistem (öksüz alarm — eşleşen ping izlemesi yok)");
        }
        if (!orphanDomains.isEmpty()) log.info("🧹 Öksüz ping alarmı temizlendi: {} domain {}", orphanDomains.size(), orphanDomains);
        return orphanDomains.size();
    }

    /** Öksüz keyword alarmı temizliği — hiçbir keyword monitör URL'ine karşılık gelmeyen açık KEYWORD
     *  alarmlarını sessizce kapatır (url rename/silme sonrası recovery'nin asla kapatamadığı askıda alarm).
     *  {@code resolveOrphanedPingAlerts} ile birebir; kimlik = url. */
    public int resolveOrphanedKeywordAlerts(Set<String> existingUrls) {
        if (existingUrls == null) return 0;
        Set<String> orphanDomains = new HashSet<>();
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (!TYPE_KEYWORD.equals(e.getAlertType()) && !isKeywordAux(e.getAlertType())) continue;
            if (e.getDomain() == null || existingUrls.contains(e.getDomain())) continue;  // eşleşen monitör var → dokunma
            orphanDomains.add(e.getDomain());
        }
        for (String d : orphanDomains) {
            resolveOpenAlertsSilently(d, Set.of(TYPE_KEYWORD, TYPE_KEYWORD_SLOW, TYPE_KEYWORD_SSL, TYPE_KEYWORD_DOMAIN_EXPIRY),
                    "Sistem (öksüz alarm — eşleşen keyword izlemesi yok)");
        }
        if (!orphanDomains.isEmpty()) log.info("🧹 Öksüz keyword alarmı temizlendi: {} domain {}", orphanDomains.size(), orphanDomains);
        return orphanDomains.size();
    }

    /** Öksüz HTTP alarmı temizliği — hiçbir HTTP monitör URL'sine karşılık gelmeyen açık
     *  HTTP_DOWN/HTTP_SSL/DOMAIN_EXPIRY alarmlarını sessizce kapatır (url rename/silme sonrası). Kimlik = url. */
    public int resolveOrphanedHttpAlerts(Set<String> existingUrls) {
        if (existingUrls == null) return 0;
        Set<String> orphanDomains = new HashSet<>();
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (!TYPE_HTTP_DOWN.equals(e.getAlertType()) && !TYPE_HTTP_SSL.equals(e.getAlertType())
                    && !TYPE_DOMAIN_EXPIRY.equals(e.getAlertType())) continue;
            if (e.getDomain() == null || existingUrls.contains(e.getDomain())) continue;  // eşleşen monitör var → dokunma
            orphanDomains.add(e.getDomain());
        }
        for (String d : orphanDomains) {
            resolveOpenAlertsSilently(d, Set.of(TYPE_HTTP_DOWN, TYPE_HTTP_SSL, TYPE_DOMAIN_EXPIRY),
                    "Sistem (öksüz alarm — eşleşen HTTP izlemesi yok)");
        }
        if (!orphanDomains.isEmpty()) log.info("🧹 Öksüz HTTP alarmı temizlendi: {} domain {}", orphanDomains.size(), orphanDomains);
        return orphanDomains.size();
    }

    /** Öksüz sayfa-bütünlüğü alarmı temizliği — hiçbir page monitör URL'sine karşılık gelmeyen açık
     *  PAGE_DOWN/PAGE_INTEGRITY alarmlarını sessizce kapatır (url rename/silme sonrası). Kimlik = url. */
    public int resolveOrphanedPageAlerts(Set<String> existingUrls) {
        if (existingUrls == null) return 0;
        Set<String> orphanDomains = new HashSet<>();
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (!isPage(e.getAlertType())) continue;
            if (e.getDomain() == null || existingUrls.contains(e.getDomain())) continue;  // eşleşen monitör var → dokunma
            orphanDomains.add(e.getDomain());
        }
        for (String d : orphanDomains) {
            resolveOpenAlertsSilently(d, Set.of(TYPE_PAGE_DOWN, TYPE_PAGE_INTEGRITY),
                    "Sistem (öksüz alarm — eşleşen sayfa izlemesi yok)");
        }
        if (!orphanDomains.isEmpty()) log.info("🧹 Öksüz sayfa alarmı temizlendi: {} domain {}", orphanDomains.size(), orphanDomains);
        return orphanDomains.size();
    }

    /** Öksüz senaryo alarmı temizliği — hiçbir senaryo monitörüne karşılık gelmeyen açık SCRIPTED_FAIL
     *  alarmlarını sessizce kapatır (ad rename/silme sonrası). Kimlik = senaryo adı. */
    public int resolveOrphanedScriptedAlerts(Set<String> existingNames) {
        if (existingNames == null) return 0;
        Set<String> orphans = new HashSet<>();
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (!isScripted(e.getAlertType())) continue;
            if (e.getDomain() == null || existingNames.contains(e.getDomain())) continue;
            orphans.add(e.getDomain());
        }
        for (String d : orphans) {
            resolveOpenAlertsSilently(d, Set.of(TYPE_SCRIPTED_FAIL, TYPE_SCRIPTED_SLOW),
                    "Sistem (öksüz alarm — eşleşen sentetik izleme yok)");
        }
        if (!orphans.isEmpty()) log.info("🧹 Öksüz senaryo alarmı temizlendi: {} senaryo {}", orphans.size(), orphans);
        return orphans.size();
    }

    /** Öksüz Domain-izleme alarmı temizliği — hiçbir domain monitörüne karşılık gelmeyen açık
     *  DOMAINMON_* alarmlarını sessizce kapatır (domain rename/silme sonrası). Kimlik = kayıtlı domain. */
    public int resolveOrphanedDomainMonAlerts(Set<String> existingDomains) {
        if (existingDomains == null) return 0;
        Set<String> orphanDomains = new HashSet<>();
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (!isDomainMon(e.getAlertType())) continue;
            if (e.getDomain() == null || existingDomains.contains(e.getDomain())) continue;
            orphanDomains.add(e.getDomain());
        }
        for (String d : orphanDomains) {
            resolveOpenAlertsSilently(d, Set.of(TYPE_DOMAINMON_EXPIRY, TYPE_DOMAINMON_UNKNOWN, TYPE_DOMAINMON_STATUS, TYPE_DOMAINMON_CHANGED),
                    "Sistem (öksüz alarm — eşleşen domain izlemesi yok)");
        }
        if (!orphanDomains.isEmpty()) log.info("🧹 Öksüz Domain alarmı temizlendi: {} domain {}", orphanDomains.size(), orphanDomains);
        return orphanDomains.size();
    }

    /** Öksüz port alarmı temizliği — hiçbir port monitör host'una karşılık gelmeyen açık PORT_DOWN
     *  alarmlarını sessizce kapatır. Host birden çok port monitörünce paylaşılıyorsa host kümesinde
     *  kalır → alarm kapatılmaz (yalnız o host'un HİÇ monitörü kalmayınca öksüz). Kimlik = host. */
    public int resolveOrphanedPortAlerts(Set<String> existingHosts) {
        if (existingHosts == null) return 0;
        Set<String> orphanDomains = new HashSet<>();
        for (AlertEvent e : alertEventRepo.findAllOpenOrderBySeverity()) {
            if (!TYPE_PORT_DOWN.equals(e.getAlertType()) && !TYPE_PORT_SLOW.equals(e.getAlertType())) continue;
            if (e.getDomain() == null || existingHosts.contains(e.getDomain())) continue;  // eşleşen monitör var → dokunma
            orphanDomains.add(e.getDomain());
        }
        for (String d : orphanDomains) {
            resolveOpenAlertsSilently(d, Set.of(TYPE_PORT_DOWN, TYPE_PORT_SLOW), "Sistem (öksüz alarm — eşleşen port izlemesi yok)");
        }
        if (!orphanDomains.isEmpty()) log.info("🧹 Öksüz port alarmı temizlendi: {} domain {}", orphanDomains.size(), orphanDomains);
        return orphanDomains.size();
    }

    /**
     * MonitoringOutageService teyit zinciri tamamlandığında (ya da kesinti
     * sürerken her sweep'te / DNS_CHANGED'de anında) çağırır. processResults'un
     * INITIAL / DAILY_REALERT dallarının izleme aynası — seviye sabit
     * (CRITICAL ya da DNS_CHANGED için HIGH) olduğundan escalation dalı yoktur.
     */
    public void processConfirmedOutage(String domain, String alertType, String alertLevel,
                                       Map<String, Object> outageContext) {
        // Bakım penceresi: atanan monitör bakımdaysa alarm AÇILMAZ + hiçbir kanaldan bildirim gitmez
        // (açılış + günlük re-alert + DNS_CHANGED hepsi bu tek noktadan geçer; save + sendCombinedAlert'ten ÖNCE).
        if (maintenanceService.isUnderMaintenance(domain)) {
            log.debug("🔧 Bakım penceresi aktif — alarm/bildirim bastırıldı: {} [{}]", domain, alertType);
            return;
        }
        // Sweep ctx'i açık bir alert_level taşıyorsa onu kullan (domain izlemesi değişken şiddet — WARNING/CRITICAL).
        if (outageContext != null && outageContext.get("alert_level") instanceof String lvl && !lvl.isBlank()) alertLevel = lvl;
        // Serbest-form izleme (keyword/ping) takımı outageContext.team_id'den gelir
        // (envantere bağlı değil); uptime/port/dns ise domain→envanter eşlemesinden.
        Long domainTeamId, ugTeamId;
        Object ctxTeam = outageContext != null ? outageContext.get("team_id") : null;
        if (ctxTeam instanceof Number teamNum) {
            domainTeamId = teamNum.longValue();
            ugTeamId = null;
        } else {
            var inventoryOpt = inventoryRepo.findByDomain(domain);
            domainTeamId = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getTeamId).orElse(null);
            ugTeamId     = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getUgTeamId).orElse(null);
        }
        // Standalone izleme (keyword/ping/http/domain) takım-özeldir; AMA KRİTİK domain alarmında müdür de eklenir.
        boolean teamOnly = isStandaloneMon(alertType) && !includeManagerContacts(alertType, alertLevel);

        String message = monitoringMessage(domain, alertType, alertLevel, outageContext);
        Optional<AlertEvent> existing = alertEventRepo.findOpenAlert(domain, alertType);

        if (existing.isEmpty()) {
            AlertEvent event = newEvent(domain, alertLevel, alertType, message, null);
            event.setTeamId(domainTeamId);   // çözüm bildiriminde takımı buradan bul (özellikle keyword/ping)
            event.setContextJson(snapshotContext(outageContext));   // çözüldü mailinde keyword/koşul detayı için
            event = alertEventRepo.save(event);

            // Alarm fırtınası hunisi (bakım + ≥%50 geçitlerinin ALTINDA): eşik+pencere aşıldıysa bireysel
            // bildirim bastırılır → TEK toplu alarm storm üzerinden gider. Storm KAPALI / eşik altı ise
            // SEND_INDIVIDUAL (sıfır gecikme, bugünkü davranış). stormId/groupName evaluate'te event'e
            // damgalanır; aşağıdaki save onu kalıcılaştırır.
            StormService.StormAction stormAction = stormService.evaluate(event, outageContext);
            if (stormAction == StormService.StormAction.SUPPRESSED) {
                event.setLastReAlertAt(now());
                alertEventRepo.save(event);
                log.info("🌩 İzleme alarmı storm'a eklendi (bireysel bildirim yok): {} [{}] → storm #{}",
                        domain, alertType, event.getStormId());
            } else {
                List<EscalationContact> contacts = teamOnly ? List.of() : getContactsForLevel(alertLevel, domainTeamId);
                sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                        message, "", event.getId(), "INITIAL", null, outageContext);

                event.setNotifiedContacts(serializeContacts(contacts));
                event.setLastReAlertAt(now());
                alertEventRepo.save(event);
                log.warn("🔴 İzleme alarmı oluşturuldu: {} [{}] {} — takım bilgilendirildi",
                        domain, alertType,
                        outageContext != null ? outageContext.getOrDefault("detail", "") : "");
            }

        } else if (!existing.get().getAcknowledged()) {
            AlertEvent event = existing.get();
            // Storm üyesi + storm hâlâ aktif → bireysel günlük re-alert YOK (toplu re-alert storm sweep'inden gider).
            if (event.getStormId() != null && stormService.isActive(event.getStormId())) {
                log.debug("İzleme alarmı storm üyesi — bireysel re-alert atlandı: {} [{}]", domain, alertType);
                return;
            }
            String lastAlertTime = event.getLastReAlertAt() != null
                    ? event.getLastReAlertAt() : event.getCreatedAt();
            if (reAlertDue(lastAlertTime, now(), reAlertIntervalHours())) {
                List<EscalationContact> contacts = teamOnly ? List.of() : getContactsForLevel(alertLevel, domainTeamId);
                sendCombinedAlert(domainTeamId, ugTeamId, contacts, domain, alertLevel, alertType,
                        "[RE-ALERT] " + message, "[RE-ALERT] ",
                        event.getId(), "DAILY_REALERT", null, outageContext);

                event.setLastReAlertAt(now());
                event.setRealertCount((event.getRealertCount() == null ? 0 : event.getRealertCount()) + 1);
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
            case TYPE_PORT_SLOW -> {
                Object port = ctx.get("port");
                Object proto = ctx.getOrDefault("protocol", "TCP");
                Object ms = ctx.get("response_ms");
                Object th = ctx.get("threshold_ms");
                return "YÜKSEK: " + domain + " üzerindeki " + port + "/" + proto +
                        " portu yanıt süresi eşiğini aştı" +
                        (ms != null ? " — " + ms + " ms" : "") + (th != null ? " (eşik " + th + " ms)" : "") + ". " +
                        "Yanıt süresi eşiğin altına indiğinde alarm otomatik kapanır.";
            }
            case TYPE_DNS_FAILURE -> {
                Object rt = ctx.get("record_type");
                if (rt != null) {
                    return "KRİTİK: " + domain + " için " + rt +
                            " DNS sorgusu çözümlenemiyor. Ardışık doğrulama denemeleri başarısız oldu. " +
                            "Çözümleme düzeldiğinde alarm otomatik kapanacaktır.";
                }
            }
            case TYPE_DNS_SLOW -> {
                Object rt = ctx.get("record_type");
                Object ms = ctx.get("response_ms");
                Object thr = ctx.get("slow_threshold_ms");
                if (rt != null) {
                    return "YÜKSEK: " + domain + " için " + rt + " DNS sorgusu çözümleniyor ANCAK YAVAŞ — "
                            + (ms != null ? ms + " ms" : "yanıt süresi yüksek")
                            + (thr != null ? " (eşik " + thr + " ms)" : "")
                            + ". DNS sunucusunda timeout/gecikme olabilir. Yanıt hızlandığında alarm otomatik kapanır.";
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
            case TYPE_DNS_UNEXPECTED -> {
                Object rt = ctx.get("record_type");
                String unexp = joinValues(ctx.get("unexpected_values"));
                String exp = joinValues(ctx.get("expected_values"));
                if (rt != null && !unexp.isEmpty()) {
                    return "YÜKSEK: " + domain + " için " + rt + " kaydında BEKLENMEYEN değer çözümleniyor: "
                            + unexp + ". Beklenen (sabitlenen): " + (exp.isEmpty() ? "—" : exp) + ". "
                            + "Olası hijack/yanlış yönlendirme — doğrulayın. Değer beklenene dönünce alarm otomatik kapanır.";
                }
            }
            case TYPE_DNS_INCONSISTENT -> {
                Object rt = ctx.get("record_type");
                Object detail = ctx.get("resolver_detail");
                if (rt != null) {
                    return "YÜKSEK: " + domain + " için " + rt + " kaydı public RESOLVER'LAR ARASINDA TUTARSIZ"
                            + (detail != null ? " — " + detail : "") + ". "
                            + "DNS propagation gecikmesi / split-DNS / olası cache poisoning. "
                            + "Resolver'lar aynı cevabı dönünce alarm otomatik kapanır.";
                }
            }
            case TYPE_KEYWORD -> {
                Object url = ctx.get("url");
                Object kw = ctx.get("keyword");
                if (url != null && kw != null) {
                    String op = ctx.get("operator") != null ? ctx.get("operator").toString() : "GTE";
                    int n = ctx.get("match_count") instanceof Number mn ? mn.intValue() : 1;
                    Object occ = ctx.get("occurrences");
                    String occPart = occ != null ? " Şu an " + occ + " kez bulundu." : "";
                    return "KRİTİK: " + url + " sayfasında \"" + kw + "\" " +
                            KeywordCheckerService.opPhrase(op, n) + " bulunmalı; koşul sağlanmıyor." + occPart +
                            " Ardışık doğrulama denemeleri başarısız oldu. " +
                            "Koşul yeniden sağlandığında alarm otomatik kapanacaktır.";
                }
            }
            case TYPE_KEYWORD_SLOW -> {
                Object url = ctx.getOrDefault("url", domain);
                Object ms = ctx.get("response_ms");
                Object th = ctx.get("threshold_ms");
                return "YÜKSEK: " + url + " içerik izlemesinde yanıt süresi eşiği aşıldı" +
                        (ms != null ? " — " + ms + " ms" : "") + (th != null ? " (eşik " + th + " ms)" : "") + ". " +
                        "Yanıt süresi eşiğin altına indiğinde alarm otomatik kapanır.";
            }
            case TYPE_KEYWORD_SSL -> {
                Object url = ctx.getOrDefault("url", domain);
                Object days = ctx.get("ssl_days_remaining");
                Object detail = ctx.get("detail");
                return "YÜKSEK: " + url + " içerik izlemesi host'unun TLS sertifikasında sorun" +
                        (days != null ? " — bitişe " + days + " gün" : (detail != null ? " — " + detail : "")) + ". " +
                        "Sertifika yenilendiğinde/düzeldiğinde alarm otomatik kapanır.";
            }
            case TYPE_KEYWORD_DOMAIN_EXPIRY -> {
                Object dom = ctx.getOrDefault("domain", domain);
                Object days = ctx.get("domain_days_remaining");
                return "YÜKSEK: " + dom + " (içerik izlemesi) domain kaydının süresi" +
                        (days != null ? " " + days + " gün içinde doluyor" : " dolmak üzere") + ". " +
                        "Kayıt yenilendiğinde alarm otomatik kapanır.";
            }
            case TYPE_PING_DOWN -> {
                Object host = ctx.get("host");
                if (host != null) {
                    return "KRİTİK: " + host + " ICMP ping'e yanıt vermiyor. " +
                            "Ardışık doğrulama denemeleri başarısız oldu. " +
                            "Host yeniden yanıt verdiğinde alarm otomatik kapanacaktır.";
                }
            }
            case TYPE_HTTP_DOWN -> {
                Object url = ctx.getOrDefault("url", domain);
                Object status = ctx.get("http_status");
                return "KRİTİK: " + url + " adresine HTTP isteği başarısız" +
                        (status != null ? " (durum " + status + ")" : "") + ". " +
                        "Ardışık doğrulama denemeleri başarısız oldu. " +
                        "Erişim geri geldiğinde alarm otomatik kapanacaktır.";
            }
            case TYPE_HTTP_SSL -> {
                Object url = ctx.getOrDefault("url", domain);
                Object days = ctx.get("ssl_days_remaining");
                Object detail = ctx.get("detail");
                return "YÜKSEK: " + url + " için TLS sertifikası sorunu" +
                        (days != null ? " — bitişe " + days + " gün" : (detail != null ? " — " + detail : "")) + ". " +
                        "Sertifika yenilendiğinde/düzeldiğinde alarm otomatik kapanır.";
            }
            case TYPE_PAGE_DOWN -> {
                Object url = ctx.getOrDefault("url", domain);
                Object status = ctx.get("http_status");
                return "KRİTİK: " + url + " sayfası yüklenemiyor" +
                        (status != null ? " (durum " + status + ")" : "") + ". " +
                        "Ardışık doğrulama denemeleri başarısız oldu. " +
                        "Sayfa yeniden yüklendiğinde alarm otomatik kapanacaktır.";
            }
            case TYPE_PAGE_INTEGRITY -> {
                Object url = ctx.getOrDefault("url", domain);
                Object detail = ctx.get("detail");
                return "YÜKSEK: " + url + " sayfasında bütünlük sorunu tespit edildi" +
                        (detail != null ? " — " + detail : " (kırık kaynak / mixed content)") + ". " +
                        "Sorunlu kaynaklar giderildiğinde alarm otomatik kapanır.";
            }
            case TYPE_SCRIPTED_SLOW -> {
                Object ms = ctx.get("duration_ms");
                Object th = ctx.get("threshold_ms");
                return "YÜKSEK: " + domain + " senaryosu çalışıyor ANCAK YAVAŞ" +
                        (ms != null ? " — " + ms + " ms" : "") + (th != null ? " (eşik " + th + " ms)" : "") + ". " +
                        "Koşum süresi eşiğin altına indiğinde alarm otomatik kapanır.";
            }
            case TYPE_SCRIPTED_FAIL -> {
                Object detail = ctx.get("detail");
                return "KRİTİK: " + domain + " sentetik testi başarısız" +
                        (detail != null ? " — " + detail : "") + ". " +
                        "Ardışık doğrulama denemeleri başarısız oldu. Test yeniden geçtiğinde alarm otomatik kapanır.";
            }
            case TYPE_DOMAIN_EXPIRY -> {
                Object dom = ctx.getOrDefault("domain", domain);
                Object days = ctx.get("domain_days_remaining");
                return "YÜKSEK: " + dom + " domain kaydının (registrar) süresi" +
                        (days != null ? " " + days + " gün içinde doluyor" : " dolmak üzere") + ". " +
                        "Kayıt yenilendiğinde alarm otomatik kapanır.";
            }
            case TYPE_DOMAINMON_EXPIRY -> {
                Object dom = ctx.getOrDefault("domain", domain);
                Object days = ctx.get("days");
                Object exp = ctx.get("expiry_date");
                Object reg = ctx.get("registrar");
                return ("CRITICAL".equals(alertLevel) ? "KRİTİK" : "YÜKSEK") + ": " + dom + " alan adının kaydı" +
                        (days != null ? " " + days + " gün içinde doluyor" : " dolmak üzere") +
                        (exp != null ? " (bitiş: " + exp + ")" : "") + (reg != null ? ", registrar: " + reg : "") +
                        ". Önerilen aksiyon: registrar üzerinden yenileyin. Yenilenince alarm otomatik kapanır.";
            }
            case TYPE_DOMAINMON_UNKNOWN -> {
                Object dom = ctx.getOrDefault("domain", domain);
                Object err = ctx.get("last_error");
                return "UYARI: " + dom + " alan adının kayıt bilgisi ALINAMADI (RDAP/WHOIS yanıt vermedi ya da tarih ayrıştırılamadı)" +
                        (err != null ? " — " + err : "") + ". \"Veri yok\" bir sorun DEĞİL ama körlük yaratır: " +
                        "erişim/proxy/TLD desteğini doğrulayın. Veri gelince alarm otomatik kapanır.";
            }
            case TYPE_DOMAINMON_STATUS -> {
                Object dom = ctx.getOrDefault("domain", domain);
                Object codes = ctx.get("status_codes");
                return ("CRITICAL".equals(alertLevel) ? "KRİTİK" : "YÜKSEK") + ": " + dom + " alan adında dikkat gerektiren EPP durum kodları: " +
                        (codes != null && !codes.toString().isBlank() ? codes : "—") + ". redemptionPeriod/pendingDelete/hold → derhal müdahale; " +
                        "transfer kilidi (clientTransferProhibited) yoksa etkinleştirin.";
            }
            case TYPE_DOMAINMON_CHANGED -> {
                Object dom = ctx.getOrDefault("domain", domain);
                Object det = ctx.get("change_detail");
                return "YÜKSEK: " + dom + " alan adının kayıt bilgisinde DEĞİŞİKLİK tespit edildi" + (det != null ? " — " + det : "") +
                        ". Olası istenmeyen transfer/hijack — doğrulayın. Bu alarm otomatik kapanmaz; inceleyip onaylayın.";
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
            // Çözüm bildirimi alarmla AYNI alıcılara gitmeli: standalone izleme takımı AlertEvent.teamId'den (envanter
            // değil); KRİTİK domain alarmında müdür de dahildi → çözümü de alır. Diğer standalone → yalnız takım.
            boolean standalone = isStandaloneMon(event.getAlertType());
            Long domainTeamId, ugTeamId;
            List<EscalationContact> contacts;
            if (standalone) {
                domainTeamId = event.getTeamId();
                ugTeamId = null;
                contacts = includeManagerContacts(event.getAlertType(), event.getAlertLevel())
                        ? getContactsForLevel(event.getAlertLevel(), domainTeamId)
                        : List.of();
            } else {
                var inventoryOpt = inventoryRepo.findByDomain(event.getDomain());
                domainTeamId = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getTeamId).orElse(null);
                ugTeamId     = inventoryOpt.map(com.sitemonitor.model.CertificateInventory::getUgTeamId).orElse(null);
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
                case TYPE_PORT_SLOW     -> "Port Yavaş Yanıt";
                case TYPE_DNS_FAILURE   -> "DNS Çözümleme Hatası";
                case TYPE_DNS_SLOW      -> "DNS Yavaş/Timeout";
                case TYPE_DNS_UNEXPECTED -> "DNS Beklenmeyen Değer";
                case TYPE_DNS_INCONSISTENT -> "DNS Tutarsızlığı";
                case TYPE_DNS_CHANGED   -> "DNS Değişikliği";
                case TYPE_KEYWORD       -> "İçerik Doğrulama";
                case TYPE_KEYWORD_SLOW  -> "İçerik Yavaş Yanıt";
                case TYPE_KEYWORD_SSL   -> "İçerik SSL Sorunu";
                case TYPE_KEYWORD_DOMAIN_EXPIRY -> "İçerik Domain Bitişi";
                case TYPE_PING_DOWN     -> "Erişilebilirlik (Ping)";
                case TYPE_HTTP_DOWN     -> "HTTP/Website Erişilemez";
                case TYPE_HTTP_SSL      -> "SSL Sertifika Sorunu";
                case TYPE_PAGE_DOWN     -> "Sayfa Yüklenemiyor";
                case TYPE_PAGE_INTEGRITY -> "Sayfa Bütünlüğü";
                case TYPE_SCRIPTED_FAIL -> "Sentetik İzleme";
                case TYPE_SCRIPTED_SLOW -> "Sentetik Yavaş Koşum";
                case TYPE_DOMAIN_EXPIRY -> "Domain Süre Bitişi";
                case TYPE_DOMAINMON_EXPIRY  -> "Alan Adı Süre Bitişi";
                case TYPE_DOMAINMON_UNKNOWN -> "Alan Adı Veri Yok";
                case TYPE_DOMAINMON_STATUS  -> "Alan Adı Durum Kodu";
                case TYPE_DOMAINMON_CHANGED -> "Alan Adı Değişikliği";
                default                 -> "Sertifika Süre Bitişi";
            };
            String subject = "[Site Monitor ✅ ÇÖZÜLDÜ] " + event.getDomain()
                    + " — " + typeTr + " sorunu giderildi";
            // İzleme çözüm mailleri süreyi createdAt→resolvedAt'ten hesaplar;
            // sertifika context'i alakasız olduğundan geçilmez.
            Map<String, Object> certContext;
            if (isDomainMon(event.getAlertType())) {   // domain → en güncel kayıt (yenilenmiş bitiş/registrar)
                certContext = reconstructDomainContext(event.getDomain());
            } else if (standalone) {        // keyword/ping/sayfa — alarm anı snapshot'ından detay
                certContext = deserializeContext(event.getContextJson());
                // Sayfa çözümünde CANLI güncel durum: son PageCheck → mail "ne çözüldü / şu an sağlıklı" gösterir
                // (snapshot alarm anını, resolved_* anahtarları çözüm anını taşır; hata olursa zenginleştirme atlanır).
                if (isPage(event.getAlertType())) {
                    try {
                        Long monId = certContext != null && certContext.get("monitor_id") instanceof Number n
                                ? n.longValue() : null;
                        if (monId != null) {
                            var latest = pageCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(monId).orElse(null);
                            if (latest != null) {
                                Map<String, Object> enriched = new LinkedHashMap<>(certContext);
                                enriched.put("resolved_page_status",     latest.getStatus());
                                enriched.put("resolved_total_resources", latest.getTotalResources());
                                enriched.put("resolved_broken",          latest.getBrokenResources());
                                enriched.put("resolved_timeout",         latest.getTimeoutCount());
                                enriched.put("resolved_mixed",           latest.getMixedContentCount());
                                enriched.put("resolved_checked_at",      latest.getCheckedAt());
                                certContext = enriched;
                            }
                        }
                    } catch (Exception ignore) { /* zenginleştirilemezse mail yine sade hâliyle gönderilir */ }
                }
            } else if (MONITORING_ALERT_TYPES.contains(event.getAlertType())) {
                certContext = null;
            } else {
                certContext = latestCheckRepo.findById(event.getDomain()).map(this::latestToCertContext).orElse(null);
            }
            // Çözüm postasında da olay kimliği taşınır (aksiyon butonları için); ctx null olabildiği
            // için kopya map'e sarılır — MONITORING tiplerinde yukarıda bilerek null'a çekiliyor.
            certContext = withAlertEventId(certContext, event.getId());
            String teamNames = collectTeamNames(domainTeamId, ugTeamId);
            // Recovery erişilebilirlik özeti — yalnız HTTP uptime örneği olan tipte (ACCESSIBILITY); veri yoksa null.
            EmailNotificationService.UptimeSummary uptime = null;
            if (TYPE_ACCESSIBILITY.equals(event.getAlertType())) {
                try {
                    EmailNotificationService.AvailabilityRow r24 = weeklyAvailability.availabilityLastHours(event.getDomain(), 24);
                    if (r24.availabilityPct() != null) {
                        EmailNotificationService.AvailabilityRow r7 = weeklyAvailability.availabilityLastHours(event.getDomain(), 24L * 7);
                        uptime = new EmailNotificationService.UptimeSummary(
                                r24.availabilityPct(), r24.outageCount(), r7.availabilityPct(), r7.outageCount());
                    }
                } catch (Exception ignore) { /* özet üretilemezse mail yine gönderilir */ }
            }
            String htmlBody = emailService.buildResolutionEmailHtml(
                    event.getDomain(), event.getAlertType(), event.getAlertLevel(),
                    event.getDaysRemaining(), resolvedBy, event.getResolvedAt(),
                    event.getCreatedAt(), certContext, teamNames, uptime);
            String status = emailService.sendResolutionAlert(
                    allEmails.toArray(new String[0]), subject,
                    event.getDomain(), event.getAlertType(), event.getAlertLevel(),
                    event.getDaysRemaining(), resolvedBy, event.getResolvedAt(),
                    event.getCreatedAt(), certContext, teamNames, uptime);
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
        // INITIAL/ESCALATION/DAILY_REALERT yolları hariç tutmasız — mevcut imza korunur.
        return sendCombinedAlert(syTeamId, ugTeamId, contacts, domain, level, alertType, message,
                subjectPrefix, alertEventId, trigger, daysRemaining, certContext, Set.of());
    }

    /** Subject'te görünen hedef adı: monitör adı > şema-soyulmuş adres. Çıplak http(s):// subject'e girmez. */
    static String subjectDisplayName(Map<String, Object> ctx, String domain) {
        if (ctx != null) {
            Object n = ctx.get("monitor_name");
            if (n != null && !String.valueOf(n).isBlank() && !"null".equals(String.valueOf(n))) {
                return String.valueOf(n).trim();
            }
        }
        if (domain == null) return "";
        return domain.replaceFirst("(?i)^https?://", "").trim();
    }

    private List<Map<String, String>> sendCombinedAlert(
                                                          Long syTeamId, Long ugTeamId,
                                                          List<EscalationContact> contacts,
                                                          String domain, String level,
                                                          String alertType, String message,
                                                          String subjectPrefix,
                                                          Long alertEventId, String trigger,
                                                          Integer daysRemaining,
                                                          Map<String, Object> certContext,
                                                          Set<String> excludeEmails) {
        // 1. TO listesi: takım email'leri + kontaklar (dedup). excludeEmails (lowercase) — manuel
        // re-notify onay pop-up'ında kullanıcının çıkardığı adresler; takım e-postaları burada
        // çözüldüğünden filtre de burada uygulanır (kontaklar reNotify'da zaten filtrelenmiş gelir).
        List<String> teamEmails = collectTeamEmails(syTeamId, ugTeamId);
        Set<String> seen = new HashSet<>();
        List<String> allEmails = new ArrayList<>();
        for (String e : teamEmails) {
            if (excludeEmails.contains(e.trim().toLowerCase())) continue;
            if (seen.add(e.toLowerCase())) allEmails.add(e);
        }
        for (EscalationContact c : contacts) {
            if (c.getEmail() != null && !c.getEmail().isBlank()
                    && !excludeEmails.contains(c.getEmail().trim().toLowerCase())
                    && seen.add(c.getEmail().trim().toLowerCase()))
                allEmails.add(c.getEmail().trim());
        }
        if (allEmails.isEmpty()) {
            log.warn("No recipients for {} [{}] — skipping", domain, level);
            return List.of();
        }

        // 1.5. ctx zenginleştirme — şablonun timeline/takım satırları için (mevcut anahtarlar EZİLMEZ).
        // realert_count: sayaç call-site'ta gönderim SONRASI artırıldığından, bu mailin numarası
        // DAILY_REALERT tetiklemesinde saklanan değerin +1'idir.
        Map<String, Object> enrichedCtx = new LinkedHashMap<>();
        if (certContext != null) enrichedCtx.putAll(certContext);
        String teamNames = collectTeamNames(syTeamId, ugTeamId);
        if (teamNames != null && !teamNames.isBlank()) enrichedCtx.putIfAbsent("team_name", teamNames);
        if (alertEventId != null) {
            alertEventRepo.findById(alertEventId).ifPresent(ev -> {
                if (ev.getCreatedAt() != null) enrichedCtx.putIfAbsent("first_alert_at", ev.getCreatedAt());
                int shown = (ev.getRealertCount() == null ? 0 : ev.getRealertCount())
                        + ("DAILY_REALERT".equals(trigger) ? 1 : 0);
                if (shown > 0) enrichedCtx.putIfAbsent("realert_count", shown);
            });
        }
        // Olay kimliği: e-postadaki "Olay detayını görüntüle / Olaya yorum yap" derin linklerini besler.
        // put (putIfAbsent DEĞİL): saklanmış eski bir anlık görüntüdeki bayat kimlik canlı olayı ezmesin.
        if (alertEventId != null) enrichedCtx.put("alert_event_id", alertEventId);
        // Envanter bağlamı — YALNIZ sertifika alarmlarında. Sertifikayı kimin nasıl yenileyeceğini
        // belirleyen operasyonel bayraklar (Netscaler/WAF/sunucuda değiştirilecek...) ve takımın kendi
        // yazdığı değişiklik süreci maile taşınır; alarmı alan kişi envanteri açmadan ne yapacağını görür.
        // Ek sorgu kabul edildi: alarm maili düşük hacimlidir ve findByDomain indeksli tekil okumadır.
        if (EmailTemplateBuilder.isCert(alertType) && domain != null) {
            inventoryRepo.findByDomain(domain).ifPresent(inv -> {
                List<String> ops = CertificateInventoryOps.enabledLabels(inv);
                if (!ops.isEmpty()) enrichedCtx.putIfAbsent("inv_ops", ops);
                String desc = inv.getChangeDescription();
                if (desc != null && !desc.isBlank()) enrichedCtx.putIfAbsent("inv_change_desc", desc);
            });
        }
        certContext = enrichedCtx;

        // 2. Subject — "[Site Monitor] SEVERITY · domain · özet" (executive format; EmailTemplateBuilder ile aynı severity etiketi)
        String levelTr = switch (level != null ? level : "") {
            case "CRITICAL"   -> "KRİTİK";
            case "HIGH"       -> "YÜKSEK";
            case "INFO", "LOW" -> "BİLGİ";
            default           -> "ORTA";
        };
        String typeTr = switch (alertType != null ? alertType : "") {
            case "REVOKED"          -> "İptal Edildi";
            case "MISMATCH"         -> "Dağıtım Eksik";
            case "CHAIN_BROKEN"     -> "Zincir Sorunu";
            case TYPE_ACCESSIBILITY -> "Erişim Kesintisi";
            case TYPE_PORT_DOWN     -> "Port Kesintisi";
            case TYPE_PORT_SLOW     -> "Port Yavaş Yanıt";
            case TYPE_DNS_FAILURE   -> "DNS Çözümleme Hatası";
            case TYPE_DNS_SLOW      -> "DNS Yavaş/Timeout";
            case TYPE_DNS_UNEXPECTED -> "DNS Beklenmeyen Değer";
            case TYPE_DNS_INCONSISTENT -> "DNS Tutarsızlığı";
            case TYPE_DNS_CHANGED   -> "DNS Değişikliği";
            case TYPE_KEYWORD       -> "İçerik Doğrulama";
            case TYPE_KEYWORD_SLOW  -> "İçerik Yavaş Yanıt";
            case TYPE_KEYWORD_SSL   -> "İçerik SSL Sorunu";
            case TYPE_KEYWORD_DOMAIN_EXPIRY -> "İçerik Domain Bitişi";
            case TYPE_PING_DOWN     -> "Erişilebilirlik (Ping)";
            case TYPE_HTTP_DOWN     -> "HTTP/Website Erişilemez";
            case TYPE_HTTP_SSL      -> "SSL Sertifika Sorunu";
            case TYPE_PAGE_DOWN     -> "Sayfa Yüklenemiyor";
            case TYPE_PAGE_INTEGRITY -> "Sayfa Bütünlüğü Sorunu";
            case TYPE_SCRIPTED_FAIL -> "Sentetik Test Başarısız";
            case TYPE_SCRIPTED_SLOW -> "Sentetik Yavaş Koşum";
            case TYPE_DOMAIN_EXPIRY -> "Domain Süre Bitişi";
            case TYPE_DOMAINMON_EXPIRY  -> "Alan Adı Süre Bitişi";
            case TYPE_DOMAINMON_UNKNOWN -> "Alan Adı Veri Yok";
            case TYPE_DOMAINMON_STATUS  -> "Alan Adı Durum Kodu";
            case TYPE_DOMAINMON_CHANGED -> "Alan Adı Değişikliği";
            default                 -> daysRemaining != null ? "Sertifika Süre Bitişi (" + daysRemaining + " gün kaldı)" : "Sertifika Süre Bitişi";
        };
        // Konu için doğal-dil özet (typeTr'e göre daha okunur); expiry tiplerinde gün ifadesi.
        String summaryTr = switch (alertType != null ? alertType : "") {
            case TYPE_DOMAINMON_EXPIRY, TYPE_DOMAIN_EXPIRY -> daysRemaining != null ? "Alan adı " + daysRemaining + " gün içinde doluyor" : "Alan adı süre bitişi";
            case TYPE_PAGE_DOWN     -> "Sayfa yüklenemiyor";
            case TYPE_PAGE_INTEGRITY -> "Sayfada kırık kaynak / mixed content";
            case TYPE_SCRIPTED_FAIL -> "Sentetik test (k6) başarısız";
            case TYPE_SCRIPTED_SLOW -> "Sentetik test (k6) yavaş";
            case TYPE_DOMAINMON_UNKNOWN -> "Alan adı kayıt verisi alınamadı";
            case TYPE_DOMAINMON_STATUS  -> "Alan adı durum kodu uyarısı";
            case TYPE_DOMAINMON_CHANGED -> "Alan adı kaydı değişti";
            case TYPE_ACCESSIBILITY -> "Erişim kesintisi";
            case TYPE_PORT_DOWN     -> "Port kesintisi";
            case TYPE_HTTP_DOWN     -> "HTTP/Website erişilemez";
            default -> (alertType == null || alertType.isBlank() || "REVOKED".equals(alertType)
                        || "MISMATCH".equals(alertType) || "CHAIN_BROKEN".equals(alertType))
                    ? (daysRemaining != null ? "Sertifika " + daysRemaining + " gün içinde doluyor" : "Sertifika süre bitişi")
                    : typeTr;
        };
        // Süre-bitişi ailesinde severity yerine kalan gün öne çıkar: "15 GÜN KALDI" / "ACİL 2 GÜN KALDI".
        String daysSeg = daysRemaining == null ? levelTr
                : (daysRemaining <= 3 ? "ACİL " + daysRemaining + " GÜN KALDI" : daysRemaining + " GÜN KALDI");
        // Subject standardı: [Site Monitor] SEVERITY · monitör ADI · kısa sorun — çıplak URL subject'e
        // girmez (ctx.monitor_name; yoksa domain'in şema-soyulmuş hali). AlertEvent.domain (yönlendirme/
        // dedupe anahtarı) DEĞİŞMEZ; bu yalnız görünen metin.
        String subject = subjectPrefix + "[Site Monitor] " + daysSeg + " · "
                + subjectDisplayName(certContext, domain) + " · " + summaryTr;

        // 3. Tek email — tüm alıcılara
        String[] toArr     = allEmails.toArray(new String[0]);
        String htmlBody    = emailService.buildAlertEmailHtml(
                subject, message, domain, level, alertType, daysRemaining, certContext);
        String emailStatus = emailService.sendAlert(
                toArr, subject, message, domain, level, alertType, daysRemaining, certContext);

        // 4. Email log — tek kayıt (teamNames yukarıda ctx zenginleştirmesinde hesaplandı)
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

    /** ctx'e olay kimliğini ekler (ctx null olabilir → yeni map). E-postadaki olay aksiyon
     *  butonlarının derin linkleri bu kimlikten üretilir. */
    private static Map<String, Object> withAlertEventId(Map<String, Object> ctx, Long id) {
        if (id == null) return ctx;
        Map<String, Object> m = (ctx == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(ctx);
        m.put("alert_event_id", id);
        return m;
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

    /** Önizleme için takım alıcıları: [email, takım adı] çiftleri (collectTeamEmails ile aynı sıra/dedupe). */
    private List<String[]> collectTeamRecipients(Long syTeamId, Long ugTeamId) {
        List<String[]> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Long teamId : List.of(
                syTeamId != null ? syTeamId : -1L,
                ugTeamId != null ? ugTeamId : -1L)) {
            if (teamId < 0) continue;
            teamRepo.findById(teamId).ifPresent(team -> {
                String email = team.getEmail() != null ? team.getEmail().trim() : "";
                if (!email.isBlank() && seen.add(email.toLowerCase()))
                    result.add(new String[]{ email, team.getName() != null ? team.getName().trim() : "" });
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
            case TYPE_PORT_SLOW -> "YÜKSEK: " + domain +
                    " üzerinde izlenen port yanıt süresi eşiğini aştı (yavaş). Yanıt hızlandığında alarm otomatik kapanır.";
            case TYPE_DNS_FAILURE -> "KRİTİK: " + domain +
                    " için DNS sorgusu çözümlenemiyor. Ardışık doğrulama denemeleri başarısız oldu. " +
                    "Çözümleme düzeldiğinde alarm otomatik kapanacaktır.";
            case TYPE_DNS_SLOW -> "YÜKSEK: " + domain +
                    " için DNS sorgusu çözümleniyor ANCAK YAVAŞ (yanıt süresi eşiği aşıyor). " +
                    "DNS sunucusunda timeout/gecikme olabilir. Yanıt hızlandığında alarm otomatik kapanır.";
            case TYPE_DNS_CHANGED -> "YÜKSEK: " + domain +
                    " için izlenen DNS kaydı değişti. Bu alarm otomatik kapanmaz; " +
                    "değişiklik planlı ise alarmı onaylayıp manuel kapatınız.";
            case TYPE_DNS_UNEXPECTED -> "YÜKSEK: " + domain +
                    " için izlenen DNS kaydında beklenmeyen (sabitlenen değerde olmayan) bir değer çözümleniyor — " +
                    "olası hijack/yanlış yönlendirme. Doğrulayın; değer beklenene dönünce alarm otomatik kapanır.";
            case TYPE_DNS_INCONSISTENT -> "YÜKSEK: " + domain +
                    " için DNS kaydı public resolver'lar arasında tutarsız — propagation gecikmesi / split-DNS / " +
                    "olası poisoning. Resolver'lar aynılaşınca alarm otomatik kapanır.";
            case "REVOKED" -> "KRİTİK: " + domain +
                    " adresindeki sertifika İPTAL EDİLMİŞTİR. Trafik derhal yönlendirilmelidir.";
            case "MISMATCH" -> "DAĞITIM EKSİK: " + domain +
                    " için yenilenmiş bir sertifika mevcut ancak uç nokta eski sertifikayı sunmaya devam ediyor.";
            case "CHAIN_BROKEN" -> "ZİNCİR SORUNU: " + domain +
                    " sertifika zincirindeki bir ara veya kök CA sertifikası süresi dolmuş ya da geçersiz.";
            case TYPE_HTTP_DOWN -> "KRİTİK: " + domain +
                    " adresine HTTP isteği başarısız — site erişilemez durumda. " +
                    "Erişim geri geldiğinde alarm otomatik kapanacaktır.";
            case TYPE_PAGE_DOWN -> "KRİTİK: " + domain +
                    " sayfası yüklenemiyor — ana içerik alınamadı. " +
                    "Sayfa yeniden yüklendiğinde alarm otomatik kapanacaktır.";
            case TYPE_PAGE_INTEGRITY -> "YÜKSEK: " + domain +
                    " sayfasında bütünlük sorunu (kırık kaynak / mixed content) tespit edildi. " +
                    "Sorunlu kaynaklar giderildiğinde alarm otomatik kapanır.";
            case TYPE_SCRIPTED_FAIL -> "KRİTİK: " + domain +
                    " sentetik testi (k6) başarısız — ardışık doğrulama denemeleri geçmedi. " +
                    "Test yeniden geçtiğinde alarm otomatik kapanacaktır.";
            case TYPE_SCRIPTED_SLOW -> "YÜKSEK: " + domain +
                    " senaryosu çalışıyor ancak koşum süresi eşiği aştı. " +
                    "Süre eşiğin altına indiğinde alarm otomatik kapanacaktır.";
            case TYPE_HTTP_SSL -> "YÜKSEK: " + domain +
                    " için TLS sertifikası hata veriyor ya da süresi dolmak üzere. " +
                    "Sertifika düzeldiğinde alarm otomatik kapanır.";
            case TYPE_DOMAIN_EXPIRY -> "YÜKSEK: " + domain +
                    " domain kaydının (registrar) süresi dolmak üzere. Kayıt yenilendiğinde alarm otomatik kapanır.";
            case TYPE_KEYWORD_SLOW -> "YÜKSEK: " + domain +
                    " içerik izlemesinde yanıt süresi eşiği aşıldı (yavaş). Yanıt hızlandığında alarm otomatik kapanır.";
            case TYPE_KEYWORD_SSL -> "YÜKSEK: " + domain +
                    " içerik izlemesi host'unun TLS sertifikası hata veriyor ya da süresi dolmak üzere. " +
                    "Sertifika düzeldiğinde alarm otomatik kapanır.";
            case TYPE_KEYWORD_DOMAIN_EXPIRY -> "YÜKSEK: " + domain +
                    " (içerik izlemesi) domain kaydının süresi dolmak üzere. Kayıt yenilendiğinde alarm otomatik kapanır.";
            case TYPE_DOMAINMON_EXPIRY -> "YÜKSEK: " + domain + " alan adının kaydı dolmak üzere. Registrar üzerinden yenileyin.";
            case TYPE_DOMAINMON_UNKNOWN -> "UYARI: " + domain + " alan adının kayıt bilgisi alınamadı (veri yok). Erişim/TLD desteğini doğrulayın.";
            case TYPE_DOMAINMON_STATUS -> "YÜKSEK: " + domain + " alan adında dikkat gerektiren EPP durum kodları var.";
            case TYPE_DOMAINMON_CHANGED -> "YÜKSEK: " + domain + " alan adının kayıt bilgisi değişti (olası hijack). İnceleyip onaylayın.";
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

    /** Çözüldü e-postasında detay için alarm anı context'inin küçük JSON snapshot'ı.
     *  Sayfa anahtarları 2026-08-04'te eklendi ("sorun neydi" detayı için) — daha ESKİ açık alarmların
     *  snapshot'ında yoklar; çözüm maili o durumda zarifçe sade düzene düşer. */
    private String snapshotContext(Map<String, Object> ctx) {
        if (ctx == null) return null;
        Map<String, Object> snap = new LinkedHashMap<>();
        for (String k : List.of("keyword", "operator", "match_count", "occurrences",
                                 "url", "host", "ip_version", "monitor_id", "condition",
                                 "http_status", "last_error", "response_ms", "threshold_ms", "port", "protocol",
                                 // Sayfa Bütünlüğü (PAGE_DOWN/PAGE_INTEGRITY) — çözüm maili "sorun neydi" bloğu
                                 "page_status", "page_mode", "broken_resources", "timeout_count",
                                 "mixed_content_count", "total_resources",
                                 "problem_resources", "problem_rows", "problem_total", "detail")) {
            if (ctx.get(k) != null) snap.put(k, ctx.get(k));
        }
        if (snap.isEmpty()) return null;
        try { return objectMapper.writeValueAsString(snap); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeContext(String json) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readValue(json, Map.class); } catch (Exception e) { return null; }
    }

    /** Takımı cert envanterinden DEĞİL AlertEvent.teamId'den (alarm anında damgalanan) bulunan standalone izleme tipi mi?
     *  Serbest-form izleme (keyword/ping/http) + domain monitör alarmları böyledir. */
    private static boolean isStandaloneMon(String alertType) {
        return TYPE_KEYWORD.equals(alertType) || TYPE_PING_DOWN.equals(alertType)
                || TYPE_HTTP_DOWN.equals(alertType) || TYPE_HTTP_SSL.equals(alertType) || TYPE_DOMAIN_EXPIRY.equals(alertType)
                || isDomainMon(alertType) || isKeywordAux(alertType) || isPage(alertType) || isScripted(alertType);
    }

    /** Domain süre-bitişi alarmında müdür (eskalasyon kontağı) da eklensin mi? Kullanıcı politikası:
     *  YALNIZ KRİTİK domain alarmında müdür bilgilendirilir; ORTA/WARNING'de yalnız takım. Diğer standalone
     *  izleme (keyword/ping/http) her zaman yalnız takım. */
    private static boolean includeManagerContacts(String alertType, String level) {
        return (isDomainMon(alertType) || TYPE_DOMAIN_EXPIRY.equals(alertType)) && "CRITICAL".equals(level);
    }

    /** Domain monitör alarmı (DOMAINMON_*) için e-posta detay bağlamını EN GÜNCEL DomainCheck'ten kurar.
     *  Manuel resend + çözüm bildirimi zengin içerik (bitiş tarihi/registrar/kaynak/EPP kodları) göstersin diye:
     *  latestCheckRepo SERTİFİKA verisidir, domain monitörü orada yoktur → doğru kaynak domain_checks tablosudur. */
    private Map<String, Object> reconstructDomainContext(String domain) {
        if (domain == null) return null;
        var monitorOpt = domainMonitorRepo.findFirstByDomainOrderByIdAsc(domain);
        if (monitorOpt.isEmpty()) return null;
        var monitor = monitorOpt.get();
        var checkOpt = domainCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(monitor.getId());
        if (checkOpt.isEmpty()) return null;
        var c = checkOpt.get();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("domain", domain);
        if (monitor.getTeamId() != null) ctx.put("team_id", monitor.getTeamId());
        if (c.getDaysRemaining() != null) { ctx.put("days", c.getDaysRemaining()); ctx.put("days_remaining", c.getDaysRemaining()); }
        if (c.getExpiryDate() != null)  ctx.put("expiry_date", c.getExpiryDate());
        if (c.getRegistrar() != null)   ctx.put("registrar", c.getRegistrar());
        if (c.getSource() != null)      ctx.put("source", c.getSource());
        // DB'de "," ile bitişik saklanır — şablon/plain-text için ", " normalize edilir.
        if (c.getStatusCodes() != null && !c.getStatusCodes().isBlank())
            ctx.put("status_codes", normalizeCsv(c.getStatusCodes()));
        if (c.getNameservers() != null && !c.getNameservers().isBlank())
            ctx.put("nameservers", normalizeCsv(c.getNameservers()));
        if (c.getCheckedAt() != null)   ctx.put("checked_at", c.getCheckedAt());
        return ctx;
    }

    /** "a,b" / "a, b" karışık CSV'yi ", " ayraçlı normalize eder. */
    private static String normalizeCsv(String csv) {
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.joining(", "));
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

    /**
     * Re-alert kadans kararı (saf/test edilebilir): son alarmdan bu yana >= intervalHours saat geçti mi?
     * Eski davranış "farklı UTC takvim günü" idi → 23:59'da açılan bir alarm 00:00'da hemen tekrar alarmlıyordu,
     * ve admin'in {@code reAlertIntervalHours} ayarı hiç okunmuyordu. Artık rolling-saat penceresi (M10).
     */
    static boolean reAlertDue(String lastAlertIso, String nowIso, int intervalHours) {
        int iv = Math.max(1, intervalHours);
        try {
            LocalDateTime last = LocalDateTime.parse(lastAlertIso, LDT);
            LocalDateTime now  = LocalDateTime.parse(nowIso, LDT);
            return !now.isBefore(last.plusHours(iv));
        } catch (Exception e) {
            return true;   // ayrıştırılamazsa re-alert'e izin ver (bayat alarmın süresiz susmasını önle)
        }
    }

    /** Aktif eşikteki re-alert aralığı (saat). Ayar okunmadığı için "ölü"ydü; M10 canlandırır. Yoksa 24. */
    private int reAlertIntervalHours() {
        return thresholdRepo.findFirstByActiveTrue()
                .map(t -> t.getReAlertIntervalHours() != null ? t.getReAlertIntervalHours() : 24)
                .orElse(24);
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
