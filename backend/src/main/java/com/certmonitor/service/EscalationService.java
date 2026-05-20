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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final Map<String, Integer> LEVEL_ORDER = Map.of(
            "WARNING", 1, "HIGH", 2, "CRITICAL", 3);

    public void processResults(List<Map<String, Object>> results) {
        AlertThreshold threshold = thresholdRepo.findFirstByActiveTrue()
                .orElseGet(this::defaultThreshold);

        for (Map<String, Object> result : results) {
            String domain = (String) result.get("domain");
            String alertType = determineAlertType(result);
            if (alertType == null) {
                resolveOpenAlerts(domain, "EXPIRY");
                continue;
            }

            String alertLevel = determineAlertLevel(result, alertType, threshold);
            if (alertLevel == null) continue;

            // Route alert to the team that owns this cert
            Long domainTeamId = inventoryRepo.findByDomain(domain)
                    .map(com.certmonitor.model.CertificateInventory::getTeamId)
                    .orElse(null);

            Integer daysRemaining = toInt(result.get("days_remaining"));
            String message = buildMessage(domain, alertType, alertLevel, daysRemaining);

            Optional<AlertEvent> existing = alertEventRepo.findOpenAlert(domain, alertType);

            if (existing.isEmpty()) {
                AlertEvent event = newEvent(domain, alertLevel, alertType, message, daysRemaining);
                event = alertEventRepo.save(event);

                List<EscalationContact> contacts = getContactsForLevel(alertLevel, domainTeamId);
                sendAllWithDetails(contacts, domain, alertLevel, alertType, message,
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
                    sendAllWithDetails(contacts, domain, alertLevel, alertType, message,
                            "", event.getId(), "ESCALATION", daysRemaining, result);

                    event.setNotifiedContacts(serializeContacts(contacts));
                    event.setLastReAlertAt(now());
                    alertEventRepo.save(event);

                } else if (!event.getAcknowledged()) {
                    String lastAlertTime = event.getLastReAlertAt() != null
                            ? event.getLastReAlertAt() : event.getCreatedAt();
                    if (!isSameUtcDay(lastAlertTime, now())) {
                        List<EscalationContact> contacts = getContactsForLevel(alertLevel, domainTeamId);
                        sendAllWithDetails(contacts, domain, alertLevel, alertType,
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
     * Immediately re-sends notifications for an open alert to all currently
     * eligible contacts, regardless of the re-alert interval.
     */
    // No @Transactional: DB auto-commits per save(), I/O runs without holding the connection
    public Map<String, Object> reNotify(Long alertId) {
        AlertEvent event = alertEventRepo.findById(alertId)
                .orElseThrow(() -> new NoSuchElementException("Alert not found: " + alertId));
        if (Boolean.TRUE.equals(event.getResolved())) {
            throw new IllegalStateException("Alert is already resolved");
        }

        Long domainTeamId = inventoryRepo.findByDomain(event.getDomain())
                .map(com.certmonitor.model.CertificateInventory::getTeamId).orElse(null);
        List<EscalationContact> contacts = getContactsForLevel(event.getAlertLevel(), domainTeamId);
        Map<String, Object> certContext = latestCheckRepo.findById(event.getDomain())
                .map(this::latestToCertContext)
                .orElse(null);

        // DB write commits before I/O starts
        event.setNotifiedContacts(serializeContacts(contacts));
        event.setLastReAlertAt(now());
        alertEventRepo.save(event);

        // I/O after DB connection is released
        // Use fresh days from LatestCheck so subject and alarm detail always match
        Integer freshDays     = certContext != null ? toInt(certContext.get("days_remaining")) : null;
        Integer effectiveDays = freshDays != null ? freshDays : event.getDaysRemaining();
        String  freshMessage  = buildMessage(event.getDomain(), event.getAlertType(),
                                             event.getAlertLevel(), effectiveDays);
        List<Map<String, String>> notificationDetails = sendAllWithDetails(
                contacts, event.getDomain(), event.getAlertLevel(), event.getAlertType(),
                freshMessage, "[RE-ALERT] ", event.getId(), "MANUAL",
                effectiveDays, certContext);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alert", event);
        result.put("contacts_attempted", contacts.size());
        result.put("notifications", notificationDetails);
        return result;
    }

    // No @Transactional: DB save auto-commits, then resolution notification runs without holding connection
    public AlertEvent resolve(Long eventId, String resolvedBy) {
        AlertEvent event = alertEventRepo.findById(eventId)
                .orElseThrow(() -> new NoSuchElementException("Alert not found: " + eventId));
        String by = resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "admin";
        event.setResolved(true);
        event.setResolvedAt(now());
        event.setResolvedBy(by);
        AlertEvent saved = alertEventRepo.save(event);  // auto-commits, releases connection
        sendResolutionNotification(saved, by, "MANUAL_RESOLVE");  // I/O without holding DB
        return saved;
    }

    private void resolveOpenAlerts(String domain, String alertType) {
        alertEventRepo.findOpenAlert(domain, alertType).ifPresent(event -> {
            event.setResolved(true);
            event.setResolvedAt(now());
            event.setResolvedBy("system");
            AlertEvent saved = alertEventRepo.save(event);
            sendResolutionNotification(saved, "Sistem (otomatik)", "RESOLUTION");
            log.info("✅ Alarm çözüldü ve bildirim gönderildi: {} [{}]", domain, alertType);
        });
    }

    private void sendResolutionNotification(AlertEvent event, String resolvedBy, String trigger) {
        try {
            Long domainTeamId = inventoryRepo.findByDomain(event.getDomain())
                    .map(com.certmonitor.model.CertificateInventory::getTeamId).orElse(null);
            List<EscalationContact> contacts = getContactsForLevel(event.getAlertLevel(), domainTeamId);
            String typeTr = switch (event.getAlertType() != null ? event.getAlertType() : "") {
                case "REVOKED"      -> "İptal";
                case "MISMATCH"     -> "Dağıtım Eksik";
                case "CHAIN_BROKEN" -> "Zincir Sorunu";
                default             -> "Son Kullanma";
            };
            String subject = "[CertMonitor ✅ ÇÖZÜLDÜ] " + event.getDomain()
                    + " — " + typeTr + " sorunu giderildi";
            Map<String, Object> certContext = latestCheckRepo.findById(event.getDomain())
                    .map(this::latestToCertContext)
                    .orElse(null);
            for (EscalationContact c : contacts) {
                String htmlBody = emailService.buildResolutionEmailHtml(
                        event.getDomain(), event.getAlertType(), event.getAlertLevel(),
                        event.getDaysRemaining(), resolvedBy, event.getResolvedAt(),
                        event.getCreatedAt(), certContext);
                String status = emailService.sendResolutionAlert(
                        c.getEmail(), subject,
                        event.getDomain(), event.getAlertType(), event.getAlertLevel(),
                        event.getDaysRemaining(), resolvedBy, event.getResolvedAt(),
                        event.getCreatedAt(), certContext);
                saveLog(event.getId(), c, subject, htmlBody, status, "SKIPPED", trigger);
                log.info("Çözüm bildirimi → {} <{}> status={}", c.getName(), c.getEmail(), status);
            }
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

    private String determineAlertLevel(Map<String, Object> result, String alertType,
                                        AlertThreshold threshold) {
        if ("REVOKED".equals(alertType) || "MISMATCH".equals(alertType)
                || "CHAIN_BROKEN".equals(alertType) || "error".equals(result.get("status"))) {
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

    private List<Map<String, String>> sendAllWithDetails(List<EscalationContact> contacts,
                                                          String domain, String level,
                                                          String alertType, String message,
                                                          String subjectPrefix,
                                                          Long alertEventId, String trigger,
                                                          Integer daysRemaining,
                                                          Map<String, Object> certContext) {
        String levelTr = switch (level != null ? level : "") {
            case "CRITICAL" -> "KRİTİK";
            case "HIGH"     -> "YÜKSEK";
            default         -> "UYARI";
        };
        String typeTr = switch (alertType != null ? alertType : "") {
            case "REVOKED"      -> "İptal Edildi";
            case "MISMATCH"     -> "Dağıtım Eksik";
            case "CHAIN_BROKEN" -> "Zincir Sorunu";
            default             -> daysRemaining != null ? daysRemaining + " gün kaldı" : "Son Kullanma";
        };
        String subject = subjectPrefix + "[CertMonitor " + levelTr + "] " + domain + " — " + typeTr;
        List<Map<String, String>> details = new ArrayList<>();

        for (EscalationContact c : contacts) {
            String htmlBody = emailService.buildAlertEmailHtml(
                    subject, message, domain, level, alertType, daysRemaining, certContext);
            String emailStatus = emailService.sendAlert(
                    c.getEmail(), subject, message, domain, level, alertType, daysRemaining, certContext);
            String webhookStatus = "SKIPPED";
            if (c.getWebhookUrl() != null && !c.getWebhookUrl().isBlank()) {
                try {
                    webhookService.send(c.getWebhookType(), c.getWebhookUrl(), subject, message, level);
                    webhookStatus = "SENT";
                } catch (Exception e) {
                    webhookStatus = "FAILED: " + e.getMessage();
                }
            }
            saveLog(alertEventId, c, subject, htmlBody, emailStatus, webhookStatus, trigger);

            Map<String, String> d = new LinkedHashMap<>();
            d.put("name", c.getName());
            d.put("email", c.getEmail());
            d.put("role", c.getRole());
            d.put("subject", subject);
            d.put("email_status", emailStatus);
            d.put("webhook_status", webhookStatus);
            details.add(d);
        }
        log.info("Notifications for {} [{}] → {} contacts | trigger={}", domain, level, contacts.size(), trigger);
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
            notificationLogRepo.save(entry);
        } catch (Exception e) {
            log.warn("Bildirim logu kaydedilemedi: {}", e.getMessage());
        }
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
                yield lvl + ": " + domain + " adresindeki sertifikanın süresi " +
                        (days != null ? days + " gün içinde doluyor." : "bilinmeyen bir hata nedeniyle kontrol edilemedi.");
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
