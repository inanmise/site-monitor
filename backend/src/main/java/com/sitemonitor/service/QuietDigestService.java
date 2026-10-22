package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.QuietDigestItem;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.QuietDigestItemRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Sessiz saat özeti (2026-10-01, onaylı öneri 15) — takımın penceresi bitince, pencerede bildirimi ertelenen alarmlar
 * için TEK e-posta ({@link EmailNotificationService#buildQuietDigestHtml}).
 *
 * <p><b>Ne zaman.</b> Dakikada bir: vakti gelmiş (pencere bitişi geçmiş) bekleyen {@code quiet_digest_items} kaydı yoksa
 * tek boolean sorguyla, kilit almadan biter. Varsa iş {@code scheduler_lock} ({@value #LOCK_NAME}) altında koşar.
 *
 * <p><b>Tam bir kez.</b> Kayıtlar gönderimden ÖNCE belirteçle sahiplenilir (koşullu UPDATE — yalnız hâlâ bekleyenler);
 * kilit süresi aşılıp iki pod aynı anda koşsa bile bir kaydı yalnız biri alır, yeniden başlatma ikinci özet üretmez.
 *
 * <p><b>Alıcılar.</b> Takımın NORMAL e-posta alıcıları — alarm postasıyla aynı zincir (alarmın damgalı bildirim grubu →
 * takımın varsayılan grubu → takım adresi; {@link EscalationService#teamEmailsForMonitor}). Farklı gruplara damgalı
 * alarmlar farklı alıcı kümesine düşerse küme başına bir e-posta gider (grup sınırı aşılmaz; yaygın durumda takım başına
 * TEK e-posta). Eskalasyon kişileri ve webhook/push özete dâhil değildir.
 *
 * <p><b>Sonra.</b> Her alarm için {@code notification_logs}'a {@code QUIET_DIGEST} satırı (gönderim durumu ile); hâlâ açık
 * alarmlar özet anından itibaren "bildirilmiş" sayılır ({@code last_re_alert_at} = özet anı — günlük hatırlatma kadansı
 * normal sürer). Pencerede ertelenmeyen bir bildirim almış kayıtlar ({@code superseded_at}) ve izlemede e-postası kapalı
 * alarmlar özete girmez; kayıtları nedeniyle kapatılır.
 *
 * <p><b>Maliyet.</b> Tur başına sabit sayıda TOPLU sorgu (vakti gelen kayıtlar, sahiplenme, olaylar, takımlar, toplu
 * damga) + takım × farklı grup damgası başına bir alıcı çözümü — alarm başına sorgu yok.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuietDigestService {

    public static final String LOCK_NAME = "quiet-digest";
    static final String STATUS_SUPERSEDED = "SKIPPED: pencerede zaten bildirildi";
    static final String STATUS_MAIL_OFF = "SKIPPED: e-posta kanalı kapalı";
    static final String STATUS_NO_RECIPIENT = "SKIPPED: alıcı yok";
    static final String STATUS_NO_EVENT = "SKIPPED: alarm kaydı yok";
    /**
     * Yarıda kalan sahiplenme (2026-10-23, çok pod R2a · D2, MP-46): tur sahiplendikten sonra hata verdi ya da pod öldü — kayıt
     * {@code SENDING:<belirteç>} durumunda sonsuza dek kalıyor, ertelenen bildirim hiç gitmiyor ve {@code notification_logs}'ta
     * NEDENİ yazmıyordu. Artık son duruma geçer ve alarm başına bir {@code QUIET_DIGEST} satırı yazılır; posta gitmiş olabilir,
     * bu yüzden YENİDEN gönderilmez (en çok bir kez).
     */
    static final String STATUS_INTERRUPTED = "FAILED: özet turu yarıda kaldı — sonuç bilinmiyor, yeniden gönderilmedi";
    static final String STATUS_STALE = "FAILED: özet turu yarıda kaldı (gönderen pod durdu) — sonuç bilinmiyor, yeniden gönderilmedi";
    /** Bu süreden eski {@code SENDING:} sahiplenmesi sahibi ölmüş sayılır (tur kilit altında; sahibi yaşıyorsa kilit bırakılmaz). */
    static final long STALE_CLAIM_MS = 15L * 60_000L;
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final QuietDigestItemRepository itemRepo;
    private final AlertEventRepository alertEventRepo;
    private final TeamRepository teamRepo;
    private final NotificationLogRepository notificationLogRepo;
    private final EmailNotificationService emailService;
    private final EscalationService escalationService;
    private final SchedulerService schedulerService;

    /** Test kancası (paket-özel). */
    Clock clock = Clock.systemUTC();

    /**
     * Sistem Bakım Modu (2026-10-02, kullanıcı kararı) — "Bildirimler bakım boyunca sussun" açık bakım sürerken VE bitişteki
     * telafi koşana dek özet BEKLER (kayıtlar sahiplenilmez, kaybolmaz): bakım bitince normal turda gider. İsteğe bağlı;
     * yokken davranış bayt bayt bugünkü.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SystemMaintenanceService systemMaintenance;

    void setSystemMaintenance(SystemMaintenanceService s) { this.systemMaintenance = s; }

    /** Tur özeti (log + test). */
    public record RunResult(int items, int mails, int sent) {
        static final RunResult EMPTY = new RunResult(0, 0, 0);
    }

    @Scheduled(fixedDelayString = "${site.monitor.quiet-digest.interval-ms:60000}",
               initialDelayString = "${site.monitor.quiet-digest.initial-delay-ms:75000}")
    public void scheduledRun() {
        String now = ISO.format(Instant.now(clock));
        // Sessiz saat kurulmamış (bugünkü kurulum) ya da vakti gelen özet yok: tek boolean sorgu, kilit yok.
        if (!anyDue(now)) return;
        if (systemMaintenanceHeld()) {
            log.debug("Sessiz saat özeti sistem bakımı nedeniyle bekletildi (bildirimler susturulmuş / telafi bekleniyor)");
            return;
        }
        schedulerService.runWithSchedulerLock(LOCK_NAME, () -> {
            RunResult r = run(Instant.now(clock));
            if (r.items() > 0) {
                log.info("Sessiz saat özeti turu: {} kayıt, {} e-posta ({} gönderildi)", r.items(), r.mails(), r.sent());
            }
        });
    }

    private boolean systemMaintenanceHeld() {
        try {
            return systemMaintenance != null && systemMaintenance.notificationsHeld();
        } catch (Exception e) {
            return false;
        }
    }

    boolean anyDue(String now) {
        try {
            return itemRepo.existsByDigestSentAtIsNullAndWindowEndLessThanEqual(now);
        } catch (Exception e) {
            log.debug("Sessiz saat özeti ön kapısı okunamadı (tablo henüz yok?): {}", e.getMessage());
            return false;
        }
    }

    /** Tek tur — çağıran kilidi tutar. Paket-özel: test doğrudan çağırır. */
    RunResult run(Instant at) {
        String now = ISO.format(at);
        failStaleClaims(at);
        List<QuietDigestItem> due = itemRepo.findDue(now);
        if (due == null || due.isEmpty()) return RunResult.EMPTY;

        // 1) Sahiplen — yalnız hâlâ bekleyenler; bu turun sahip olduğu kayıtlar belirteçten okunur.
        String token = "SENDING:" + UUID.randomUUID().toString().substring(0, 12);
        List<Long> ids = due.stream().map(QuietDigestItem::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty() || itemRepo.claim(ids, now, token) == 0) return RunResult.EMPTY;
        List<QuietDigestItem> owned;
        try {
            owned = itemRepo.findByDigestStatus(token);
        } catch (RuntimeException e) {
            failClaim(token, null, e);
            throw e;
        }
        if (owned == null || owned.isEmpty()) return RunResult.EMPTY;
        try {
            return deliver(owned, now);
        } catch (RuntimeException e) {
            // MP-46: sahiplenmeden sonra beklenmeyen hata — hâlâ bu turun belirtecini taşıyan kayıtlar son duruma geçer (iz ile).
            failClaim(token, owned, e);
            throw e;
        }
    }

    /** Adım 2–5: okumalar, gruplama, gönderim, damga. Çağıran ({@link #run}) beklenmeyen hatada sahiplenmeyi kapatır. */
    private RunResult deliver(List<QuietDigestItem> owned, String now) {
        // 2) Toplu okumalar: olaylar + takımlar.
        Map<Long, AlertEvent> events = new HashMap<>();
        for (AlertEvent e : alertEventRepo.findAllById(owned.stream().map(QuietDigestItem::getAlertEventId)
                .filter(Objects::nonNull).distinct().toList())) {
            if (e != null && e.getId() != null) events.put(e.getId(), e);
        }
        Map<Long, Team> teams = new HashMap<>();
        for (Team t : teamRepo.findAllById(owned.stream().map(QuietDigestItem::getTeamId)
                .filter(Objects::nonNull).distinct().toList())) {
            if (t != null && t.getId() != null) teams.put(t.getId(), t);
        }

        // 3) Takım → (alıcı kümesi → kayıtlar). Özete girmeyecek kayıtlar nedeniyle kapanır.
        Map<String, List<Long>> closedWithReason = new LinkedHashMap<>();
        Map<Long, Map<String, List<QuietDigestItem>>> byTeam = new LinkedHashMap<>();
        Map<String, List<String>> recipientCache = new HashMap<>();
        for (QuietDigestItem i : owned) {
            AlertEvent e = events.get(i.getAlertEventId());
            if (e == null) { closedWithReason.computeIfAbsent(STATUS_NO_EVENT, k -> new ArrayList<>()).add(i.getId()); continue; }
            if (i.getSupersededAt() != null) {
                closedWithReason.computeIfAbsent(STATUS_SUPERSEDED, k -> new ArrayList<>()).add(i.getId());
                continue;
            }
            if (mailDisabled(e)) {
                closedWithReason.computeIfAbsent(STATUS_MAIL_OFF, k -> new ArrayList<>()).add(i.getId());
                writeLog(e.getId(), teamName(teams.get(i.getTeamId())), "", "", "Sessiz saat özeti: izlemede e-posta kanalı kapalı.",
                        STATUS_MAIL_OFF);
                continue;
            }
            // Bildirim grubu damgası yalnız alarmın SY (sahip) takımına uygulanır — alarm postasıyla aynı kural.
            Long stamp = i.getTeamId().equals(e.getTeamId()) ? e.getNotificationGroupId() : null;
            List<String> to = recipientCache.computeIfAbsent(i.getTeamId() + "|" + stamp,
                    k -> safeRecipients(i.getTeamId(), stamp));
            String key = String.join(",", to.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).sorted().toList());
            byTeam.computeIfAbsent(i.getTeamId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        closedWithReason.forEach((status, list) -> itemRepo.markStatus(list, status));

        // 4) Gönder: (takım, alıcı kümesi) başına bir e-posta.
        int mails = 0, sentMails = 0;
        Set<Long> notifiedOpen = new LinkedHashSet<>();
        for (Map.Entry<Long, Map<String, List<QuietDigestItem>>> t : byTeam.entrySet()) {
            Team team = teams.get(t.getKey());
            String name = teamName(team);
            for (List<QuietDigestItem> group : t.getValue().values()) {
                QuietDigestItem first = group.get(0);
                AlertEvent firstEvent = events.get(first.getAlertEventId());
                Long stamp = firstEvent != null && first.getTeamId().equals(firstEvent.getTeamId())
                        ? firstEvent.getNotificationGroupId() : null;
                List<String> to = recipientCache.getOrDefault(first.getTeamId() + "|" + stamp, List.of());
                List<EmailNotificationService.QuietDigestRow> rows = new ArrayList<>();
                Set<Long> seenEvents = new HashSet<>();
                for (QuietDigestItem i : group) {
                    AlertEvent e = events.get(i.getAlertEventId());
                    if (e == null || !seenEvents.add(e.getId())) continue;
                    rows.add(row(e));
                }
                rows.sort(Comparator.comparing(EmailNotificationService.QuietDigestRow::resolved)
                        .thenComparing(r -> -QuietHours.levelValue(r.level()))
                        .thenComparing(r -> r.openedAt() == null ? "" : r.openedAt()));
                String windowLabel = windowLabel(group);
                String subject = EmailNotificationService.quietDigestSubject(name, rows);
                String html = emailService.buildQuietDigestHtml(name, windowLabel, rows);
                String status;
                if (to.isEmpty()) {
                    status = STATUS_NO_RECIPIENT;
                } else {
                    mails++;
                    try {
                        status = emailService.sendHtml(to.toArray(new String[0]), null, subject, html, null);
                    } catch (Exception ex) {
                        status = "FAILED: " + ex.getMessage();
                    }
                    if ("SENT".equals(status)) sentMails++;
                }
                if (status == null || status.isBlank()) status = "FAILED: boş durum";
                String clipped = status.length() > 290 ? status.substring(0, 290) : status;
                itemRepo.markStatus(group.stream().map(QuietDigestItem::getId).toList(), clipped);
                List<NotificationLog> logs = new ArrayList<>();
                for (Long eventId : seenEvents) {
                    logs.add(logRow(eventId, name, String.join(", ", to), subject, html, status));
                    AlertEvent e = events.get(eventId);
                    if ("SENT".equals(status) && e != null && !Boolean.TRUE.equals(e.getResolved())) notifiedOpen.add(eventId);
                }
                try {
                    notificationLogRepo.saveAll(logs);
                } catch (Exception ex) {
                    log.warn("Sessiz saat özeti günlüğe yazılamadı (takım {}): {}", t.getKey(), ex.getMessage());
                }
            }
        }

        // 5) Hâlâ açık alarmlar özet anından itibaren "bildirilmiş" — günlük hatırlatma kadansı buradan sürer.
        if (!notifiedOpen.isEmpty()) {
            try {
                alertEventRepo.stampNotifiedByQuietDigest(notifiedOpen, now);
            } catch (Exception ex) {
                log.warn("Sessiz saat özeti sonrası yeniden uyarı damgası yazılamadı: {}", ex.getMessage());
            }
        }
        return new RunResult(owned.size(), mails, sentMails);
    }

    /**
     * MP-46: yarıda kalan bu turun sahiplenmesi — hâlâ {@code token} taşıyan kayıtlar {@link #STATUS_INTERRUPTED} olur, alarm
     * başına bir {@code QUIET_DIGEST} satırı. Yazım da düşerse kayıt {@code SENDING:} kalır ve bir sonraki tur
     * ({@link #failStaleClaims}) kapatır. Hata yutulur (asıl hata çağırana gider).
     */
    private void failClaim(String token, List<QuietDigestItem> owned, RuntimeException cause) {
        String status = STATUS_INTERRUPTED + " (" + cause.getClass().getSimpleName() + ")";
        try {
            List<QuietDigestItem> left = itemRepo.findByDigestStatus(token);
            if (left == null || left.isEmpty()) left = owned == null ? List.of() : owned;
            if (itemRepo.markStatusIfToken(token, status) > 0) traceClosed(left, status);
            log.warn("Sessiz saat özeti turu yarıda kaldı ({}): {} kayıt kapatıldı, yeniden gönderilmeyecek — {}",
                    token, left.size(), cause.toString());
        } catch (Exception e) {
            log.warn("Sessiz saat özeti yarım sahiplenmesi kapatılamadı ({}): {} — sonraki tur kapatır", token, e.getMessage());
        }
    }

    /**
     * MP-46: {@link #STALE_CLAIM_MS}'den eski {@code SENDING:} sahiplenmeleri (sahibi ölmüş tur — tur kilit altında koştuğu için
     * şu an başka bir tur yok) → {@link #STATUS_STALE} + alarm başına bir {@code QUIET_DIGEST} satırı. Belirteç başına koşullu:
     * iki tur aynı sahiplenmeyi görse de iz bir kez yazılır.
     */
    void failStaleClaims(Instant at) {
        try {
            List<QuietDigestItem> stale = itemRepo.findStaleClaims(ISO.format(at.minusMillis(STALE_CLAIM_MS)));
            if (stale == null || stale.isEmpty()) return;
            Map<String, List<QuietDigestItem>> byToken = new LinkedHashMap<>();
            for (QuietDigestItem i : stale) {
                if (i.getDigestStatus() != null) byToken.computeIfAbsent(i.getDigestStatus(), k -> new ArrayList<>()).add(i);
            }
            for (Map.Entry<String, List<QuietDigestItem>> e : byToken.entrySet()) {
                if (itemRepo.markStatusIfToken(e.getKey(), STATUS_STALE) == 0) continue;
                traceClosed(e.getValue(), STATUS_STALE);
                log.warn("Sessiz saat özeti: yarıda kalmış sahiplenme ({}) kapatıldı — {} kayıt, yeniden gönderilmedi",
                        e.getKey(), e.getValue().size());
            }
        } catch (Exception e) {
            log.debug("Sessiz saat özeti yarım sahiplenmeleri okunamadı: {}", e.getMessage());
        }
    }

    /** Kapatılan kayıtların alarm başına {@code notification_logs} izi (takım adı bilinmeyebilir). */
    private void traceClosed(List<QuietDigestItem> items, String status) {
        Map<Long, Long> eventTeam = new LinkedHashMap<>();
        for (QuietDigestItem i : items) {
            if (i.getAlertEventId() != null) eventTeam.putIfAbsent(i.getAlertEventId(), i.getTeamId());
        }
        Map<Long, String> names = new HashMap<>();
        try {
            for (Team t : teamRepo.findAllById(eventTeam.values().stream().filter(Objects::nonNull).distinct().toList())) {
                if (t != null && t.getId() != null) names.put(t.getId(), teamName(t));
            }
        } catch (Exception ignore) { /* ad yalnız bilgi */ }
        List<NotificationLog> logs = new ArrayList<>();
        for (Map.Entry<Long, Long> e : eventTeam.entrySet()) {
            logs.add(logRow(e.getKey(), names.getOrDefault(e.getValue(), "-"), "", "Sessiz saat özeti",
                    "Sessiz saat özeti turu yarıda kaldı; bu alarmın ertelenen bildirimi gönderilmiş olabilir, yeniden gönderilmedi.",
                    status));
        }
        try {
            notificationLogRepo.saveAll(logs);
        } catch (Exception ex) {
            log.warn("Sessiz saat özeti yarım tur izi günlüğe yazılamadı: {}", ex.getMessage());
        }
    }

    private List<String> safeRecipients(Long teamId, Long stamp) {
        try {
            List<String> out = escalationService.teamEmailsForMonitor(teamId, stamp);
            return out == null ? List.of() : out;
        } catch (Exception e) {
            log.warn("Sessiz saat özeti alıcıları çözülemedi (takım {}): {}", teamId, e.getMessage());
            return List.of();
        }
    }

    private static EmailNotificationService.QuietDigestRow row(AlertEvent e) {
        Map<String, Object> ctx = context(e);
        return new EmailNotificationService.QuietDigestRow(e.getId(), e.getAlertLevel(),
                EscalationService.subjectDisplayName(ctx, e.getDomain()), EscalationService.resolvedTypeLabel(e.getAlertType()),
                e.getCreatedAt(), e.getResolvedAt(), Boolean.TRUE.equals(e.getResolved()));
    }

    private static boolean mailDisabled(AlertEvent e) {
        Map<String, Object> ctx = context(e);
        return ctx != null && Boolean.TRUE.equals(ctx.get("mail_disabled"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> context(AlertEvent e) {
        String json = e == null ? null : e.getContextJson();
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, Map.class);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Grubun pencere etiketi: "01.10.2026 22:00–07:00" (birden çok pencere birikmişse ilk–son). */
    static String windowLabel(List<QuietDigestItem> group) {
        TreeSet<String> keys = new TreeSet<>();
        TreeSet<String> ends = new TreeSet<>();
        for (QuietDigestItem i : group) {
            if (i.getWindowKey() != null) keys.add(i.getWindowKey());
            if (i.getWindowEnd() != null) ends.add(i.getWindowEnd());
        }
        if (keys.isEmpty()) return "—";
        try {
            LocalDateTime start = LocalDateTime.parse(keys.first());
            String endLocal = ends.isEmpty() ? "" : LocalDateTime.parse(ends.last()).atZone(ZoneOffset.UTC)
                    .withZoneSameInstant(QuietHours.ZONE).toLocalTime().toString();
            return start.format(DAY) + " " + start.toLocalTime() + (endLocal.isEmpty() ? "" : "–" + endLocal);
        } catch (Exception ex) {
            return keys.first();
        }
    }

    private static String teamName(Team t) {
        return t == null || t.getName() == null || t.getName().isBlank() ? "-" : t.getName().trim();
    }

    private NotificationLog logRow(Long eventId, String name, String to, String subject, String message, String status) {
        NotificationLog l = new NotificationLog();
        l.setAlertEventId(eventId);
        l.setSentAt(ISO.format(Instant.now(clock)));
        l.setRecipientName(name);
        l.setRecipientEmail(to);
        l.setRecipientRole("COMBINED");
        l.setSubject(subject);
        l.setMessage(message);
        l.setEmailStatus(status);
        l.setWebhookStatus("SKIPPED");
        l.setTrigger(EscalationService.TRIGGER_QUIET_DIGEST);
        try {
            l.setEmailFrom(emailService.getEmailFrom());
        } catch (Exception ignore) { /* gönderen yalnız bilgi */ }
        return l;
    }

    private void writeLog(Long eventId, String name, String to, String subject, String message, String status) {
        try {
            notificationLogRepo.save(logRow(eventId, name, to, subject, message, status));
        } catch (Exception ex) {
            log.warn("Sessiz saat özeti günlüğe yazılamadı (olay {}): {}", eventId, ex.getMessage());
        }
    }
}
