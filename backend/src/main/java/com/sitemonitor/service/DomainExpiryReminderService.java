package com.sitemonitor.service;

import com.sitemonitor.model.DomainExpiryReminder;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.repository.DomainExpiryReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Alan adı süre-bitişi hatırlatmaları (2026-09-22, denetim madde E / bulgu F1).
 *
 * <p>Sözleşme: izlemenin {@code thresholdsCsv} eşiklerinden (vars. 60,30,14,7,3,1) kalan günün altına indiği her eşik
 * için <b>bir kez</b> e-posta + push. Aynı turda birden çok eşik birden aşılmışsa (izleme 5 gün kala kurulduysa 60/30/
 * 14/7 aynı anda "aşılmış" olur) yalnız <b>en sıkı</b> eşik gönderilir, diğerleri COVERED olarak işaretlenir — dört mail
 * peş peşe gürültü olurdu. Bitiş tarihi ileri gidince (yenileme) seri sıfırdan başlar. Açık DOMAINMON_EXPIRY alarmından
 * BAĞIMSIZ: alarm "eşiğin altındasın" der ve bir kez açılır, hatırlatma ise takvimdir.
 *
 * <p>Kanallar izlemenin kendi tercihini izler: {@code notifyEmail} kapalıysa mail yok, {@code notifyWebhook} kapalıysa
 * push yok; ikisi de kapalıysa kayıt SKIPPED_CHANNELS_OFF ile yine yazılır (bir daha denenmez — "kapalı" kapalıdır).
 * Alıcılar sertifika/izleme alarmlarıyla AYNI zincir: bildirim grubu → takım varsayılan grubu → takım e-postası.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainExpiryReminderService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final String DEFAULT_THRESHOLDS = "60,30,14,7,3,1";
    public static final String PUSH_TRIGGER = "DOMAIN_EXPIRY_REMINDER";

    private final DomainExpiryReminderRepository repo;
    private final EscalationService escalation;
    private final EmailNotificationService email;
    private final UserPushService push;
    private final ActivityLogService activityLog;

    /** CSV eşikleri → azalan sıralı, tekil, pozitif tam sayılar. Bozuk/boş girdi → varsayılan liste. */
    public static List<Integer> parseThresholds(String csv) {
        TreeSet<Integer> set = new TreeSet<>(java.util.Comparator.reverseOrder());
        String src = csv == null || csv.isBlank() ? DEFAULT_THRESHOLDS : csv;
        for (String p : src.split("[,;\\s]+")) {
            try { int v = Integer.parseInt(p.trim()); if (v >= 0) set.add(v); } catch (NumberFormatException ignored) { /* bozuk parça atlanır */ }
        }
        if (set.isEmpty()) return parseThresholds(DEFAULT_THRESHOLDS);
        return new ArrayList<>(set);
    }

    /**
     * Kontrol sonucunu değerlendirir; gerekiyorsa hatırlatma gönderir. Hata sweep'i asla kırmaz.
     *
     * @return gönderilen hatırlatmanın kaydı (yoksa null)
     */
    public DomainExpiryReminder evaluate(DomainMonitor m, Map<String, Object> result) {
        try {
            if (m == null || m.getId() == null || result == null) return null;
            if (!Boolean.TRUE.equals(m.getActive())) return null;
            Integer days = result.get("days_remaining") instanceof Number n ? n.intValue() : null;
            String expiry = result.get("expiry_date") instanceof String s ? s : null;
            if (days == null || expiry == null || expiry.isBlank()) return null;   // veri yok → hatırlatma yok (UNKNOWN alarmı ayrı)

            List<Integer> crossed = new ArrayList<>();
            for (Integer t : parseThresholds(m.getThresholdsCsv())) if (days <= t) crossed.add(t);
            if (crossed.isEmpty()) return null;
            List<Integer> unsent = new ArrayList<>();
            for (Integer t : crossed) if (!repo.existsByMonitorIdAndExpiryDateAndThresholdDays(m.getId(), expiry, t)) unsent.add(t);
            if (unsent.isEmpty()) return null;

            int tightest = unsent.get(unsent.size() - 1);   // azalan sıralı → sonuncusu en küçük
            String now = ISO.format(Instant.now());
            DomainExpiryReminder sent = send(m, result, days, expiry, tightest, now);
            for (Integer t : unsent) {
                if (t == tightest) continue;
                DomainExpiryReminder cov = new DomainExpiryReminder();
                cov.setMonitorId(m.getId()); cov.setExpiryDate(expiry); cov.setThresholdDays(t);
                cov.setDaysRemaining(days); cov.setStatus("COVERED"); cov.setSentAt(now);
                repo.save(cov);
            }
            return sent;
        } catch (Exception e) {
            log.warn("Alan adı hatırlatması değerlendirilemedi: {} — {}", m != null ? m.getDomain() : "?", e.getMessage());
            return null;
        }
    }

    private DomainExpiryReminder send(DomainMonitor m, Map<String, Object> result, int days, String expiry, int threshold, String now) {
        boolean mailOn = !Boolean.FALSE.equals(m.getNotifyEmail());
        boolean pushOn = !Boolean.FALSE.equals(m.getNotifyWebhook());
        DomainExpiryReminder r = new DomainExpiryReminder();
        r.setMonitorId(m.getId()); r.setExpiryDate(expiry); r.setThresholdDays(threshold);
        r.setDaysRemaining(days); r.setSentAt(now);

        List<String> recipients = mailOn ? escalation.teamEmailsForMonitor(m.getTeamId(), m.getNotificationGroupId()) : List.of();
        if (!mailOn && !pushOn) {
            r.setStatus("SKIPPED_CHANNELS_OFF");
        } else if (mailOn && recipients.isEmpty() && !pushOn) {
            r.setStatus("SKIPPED_NO_RECIPIENT");
        } else {
            String level = days < 0 || days <= (m.getCriticalDays() != null ? m.getCriticalDays() : 7) ? "CRITICAL"
                    : days <= (m.getWarningDays() != null ? m.getWarningDays() : 30) ? "WARNING" : "INFO";
            String registrar = result.get("registrar") instanceof String s ? s : null;
            String name = m.getName() != null && !m.getName().isBlank() ? m.getName() : m.getDomain();
            if (mailOn && !recipients.isEmpty()) {
                String subject = "[Site Monitor] " + name + " — alan adı bitişine " + Math.max(days, 0) + " gün (hatırlatma · " + threshold + " gün eşiği)";
                String html = email.buildDomainExpiryReminderHtml(name, m.getDomain(), days, expiry, threshold, registrar, level, m.getId());
                String status = email.sendHtml(recipients.toArray(new String[0]), null, subject, html, null);
                log.info("Alan adı hatırlatması: {} eşik={} kalan={} alıcı={} durum={}", m.getDomain(), threshold, days, recipients, status);
                r.setRecipients(String.join(",", recipients));
            }
            if (pushOn) {
                String msg = name + " alan adının kaydı " + (days < 0 ? Math.abs(days) + " gün önce doldu" : days + " gün sonra doluyor")
                        + " (" + threshold + " gün eşiği hatırlatması)";
                Map<String, Object> pr = push.enqueueTeamNotice(m.getTeamId(), PUSH_TRIGGER, level, "DOMAIN", name, msg,
                        "domain-reminder:" + m.getId() + ":" + expiry + ":" + threshold);
                r.setPushQueued(pr.get("queued") instanceof Number n ? n.intValue() : 0);
            }
            r.setStatus("SENT");
        }
        repo.save(r);
        activityLog.recordLifecycle(ActivityLogService.DOMAIN, m.getId(), m.getName(), m.getDomain(), m.getTeamId(),
                "EXPIRY_REMINDER", "scheduler", "eşik " + threshold + " gün · kalan " + days + " gün · " + r.getStatus());
        return r;
    }

    /** Detay penceresi: gönderilen hatırlatmalar (yeni önce). */
    public List<Map<String, Object>> history(Long monitorId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DomainExpiryReminder r : repo.findTop50ByMonitorIdOrderBySentAtDesc(monitorId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId()); row.put("threshold_days", r.getThresholdDays()); row.put("days_remaining", r.getDaysRemaining());
            row.put("expiry_date", r.getExpiryDate()); row.put("status", r.getStatus()); row.put("recipients", r.getRecipients());
            row.put("push_queued", r.getPushQueued()); row.put("sent_at", r.getSentAt());
            out.add(row);
        }
        return out;
    }
}
