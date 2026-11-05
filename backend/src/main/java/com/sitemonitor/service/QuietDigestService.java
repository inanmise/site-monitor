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
    /**
     * Yarım sahiplenme aramasının geriye bakışı (sorgu sınırı): dakikalık iş yarım sahiplenmeyi ~16 dk içinde kapatır; iki
     * günden eski bir yarım sahiplenme (iş günlerce durdu) aranmaz — görünür kalır ama sorgu geçmişi taramaz.
     */
    static final long STALE_LOOKBACK_MS = 2L * 24 * 60 * 60_000L;
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

    /**
     * Alarm iletimi (2026-11-05): ertelenen bildirimin iletim alıcıları da özetini alır — sahip takımın iletimleri için alıcı
     * takım başına AYRI özet (bant), yalnız kendisine iletilen alarmlarla. Sağlayıcı üzerinden çözülür (açılış döngüsü yok);
     * yokken ya da takımın iletimi yokken özet bayt bayt bugünkü.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.sitemonitor.service.forward.AlertForwardRouting> forwardRoutingProvider;
    private volatile com.sitemonitor.service.forward.AlertForwardRouting forwardRouting;

    /** Test kancası. */
    void setForwardRouting(com.sitemonitor.service.forward.AlertForwardRouting r) { this.forwardRouting = r; }

    /**
     * Envanter — iletim kopyasında alarmın TÜM sahiplerini bulmak için (envanter gibi yönlenen alarmda SY / UG; 2026-11-06).
     * Yalnız iletim yolunda, takımın iletimi varken okunur; isteğe bağlı (yokken yalnız damga takımı sahip sayılır).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.CertificateInventoryRepository inventoryRepo;

    /** Test kancası. */
    void setInventoryRepo(com.sitemonitor.repository.CertificateInventoryRepository r) { this.inventoryRepo = r; }

    private com.sitemonitor.service.forward.AlertForwardRouting forwardRouting() {
        var r = forwardRouting;
        if (r != null) return r;
        try {
            r = forwardRoutingProvider == null ? null : forwardRoutingProvider.getIfAvailable();
        } catch (Exception e) {
            return null;
        }
        if (r != null) forwardRouting = r;   // çözülen bean önbelleğe — alarm başına bean araması yok
        return r;
    }

    /** Tur özeti (log + test). */
    public record RunResult(int items, int mails, int sent) {
        static final RunResult EMPTY = new RunResult(0, 0, 0);
    }

    @Scheduled(fixedDelayString = "${site.monitor.quiet-digest.interval-ms:60000}",
               initialDelayString = "${site.monitor.quiet-digest.initial-delay-ms:75000}")
    public void scheduledRun() {
        Instant at = Instant.now(clock);
        String now = ISO.format(at);
        // Sessiz saat kurulmamış (bugünkü kurulum) ya da vakti gelen özet yok: iki küçük sorgu, kilit yok. Yarıda kalmış
        // sahiplenme (ölen pod) varsa da kilide girilir — yeni kayıt beklenmeden kapanır (inceleme J-1).
        boolean due = anyDue(now);
        boolean stale = anyStaleClaim(at);
        if (!due && !stale) return;
        if (due && systemMaintenanceHeld()) {
            log.debug("Sessiz saat özeti sistem bakımı nedeniyle bekletildi (bildirimler susturulmuş / telafi bekleniyor)");
            if (!stale) return;
            due = false;   // bakımda posta yok; yalnız yarım sahiplenmeler kapanır (iz, gönderim değil)
        }
        boolean sendRound = due;
        schedulerService.runWithSchedulerLock(LOCK_NAME, () -> {
            if (!sendRound) {
                failStaleClaims(Instant.now(clock));
                return;
            }
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

    boolean anyStaleClaim(Instant at) {
        try {
            return itemRepo.countStaleClaims(ISO.format(at.minusMillis(STALE_LOOKBACK_MS)),
                    ISO.format(at.minusMillis(STALE_CLAIM_MS))) > 0;
        } catch (Exception e) {
            log.debug("Sessiz saat özeti yarım sahiplenme kapısı okunamadı: {}", e.getMessage());
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
                // Alarm iletimi (2026-11-05): bu özetin alarmlarının iletim alıcılarına ayrı özet kopyaları (yoksa hiçbir şey).
                forwardDigest(t.getKey(), name, group, events, to, windowLabel);
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

    /** İletim özeti grubu: (alıcı takım, adres kümesi) → alarmlar; {@code reason} doluysa posta yok, yalnız karar satırı. */
    private static final class ForwardDigestGroup {
        final com.sitemonitor.service.forward.ForwardTarget target;
        final List<String> to;
        final String reason;
        final List<AlertEvent> events = new ArrayList<>();

        ForwardDigestGroup(com.sitemonitor.service.forward.ForwardTarget target, List<String> to, String reason) {
            this.target = target;
            this.to = to;
            this.reason = reason;
        }
    }

    /**
     * Sessiz saat özetinin iletim kopyaları (2026-11-05): {@code teamId} takımının özetindeki her alarm için o takımın
     * iletimleri ({@code targetsFor(owners=[takım])}); alıcı takım × adres kümesi başına AYRI özet — yalnız ona iletilen
     * alarmlar, başta bant, başlıkta sahip takımın adı. Adresler: hedef − sahip özetinin alıcıları − bu alarmı iletimle ZATEN
     * almış adresler (damga). Alarm başına {@code QUIET_DIGEST} satırı (rol FORWARD, alıcı adı = alıcı takım). Teslim edilen ve
     * hâlâ açık alarmlar iletim damgasına yazılır (çözüm postası onlara gider). Hata sahip özetini bozmaz.
     *
     * <p>Sahipler (2026-11-06, inceleme): alarmın damga takımı + envanter gibi yönleniyorsa envanterin SY / UG takımı; alıcı
     * takımı bu sahiplerden biri olan hedef ATLANIR (alarmı kendi postasından / özetinden zaten alıyor — anlık gönderim ve fırtına
     * yoluyla aynı kural). Eskiden yalnız özet takımı sahip sayılıyordu: SY'nin UG'ye iletimi UG'ye ikinci bir kopya
     * götürüyordu.
     */
    private void forwardDigest(Long teamId, String ownerName, List<QuietDigestItem> group, Map<Long, AlertEvent> events,
                               List<String> ownerTo, String windowLabel) {
        var routing = forwardRouting();
        if (routing == null || teamId == null) return;
        try {
            if (!routing.anyFor(List.of(teamId))) return;
            Set<String> ownerKeys = new HashSet<>();
            for (String a : ownerTo) if (a != null) ownerKeys.add(a.trim().toLowerCase(Locale.ROOT));
            Map<String, ForwardDigestGroup> groups = new LinkedHashMap<>();
            Set<Long> seenEvents = new HashSet<>();
            Map<String, Set<Long>> ownersByDomain = new HashMap<>();
            for (QuietDigestItem i : group) {
                AlertEvent e = events.get(i.getAlertEventId());
                if (e == null || e.getId() == null || !seenEvents.add(e.getId())) continue;
                var targets = routing.targetsFor(new com.sitemonitor.service.forward.AlertForwardRouting.AlertRef(
                        List.of(teamId), e.getAlertType(), e.getDomain(), EscalationService.contextMonitorId(e)));
                if (targets == null || targets.isEmpty()) continue;
                Set<Long> owners = alarmOwners(e, teamId, ownersByDomain);
                Set<String> already = new HashSet<>();
                ForwardEmailStamp.of(e).values().forEach(l -> l.forEach(a -> already.add(a.toLowerCase(Locale.ROOT))));
                for (var t : targets) {
                    if (t == null || owners.contains(t.recipientTeamId())) continue;
                    List<String> to = new ArrayList<>();
                    Set<String> keys = new LinkedHashSet<>();
                    for (String a : t.emails()) {
                        String v = a == null ? "" : a.trim();
                        String k = v.toLowerCase(Locale.ROOT);
                        if (v.isEmpty() || ownerKeys.contains(k) || already.contains(k) || !keys.add(k)) continue;
                        to.add(v);
                    }
                    String reason = to.isEmpty()
                            ? (t.emails().isEmpty() ? STATUS_NO_RECIPIENT : EscalationService.STATUS_FORWARD_ALREADY_MAILED)
                            : null;
                    String key = t.recipientTeamId() + "|" + (reason != null ? reason : String.join(",", new TreeSet<>(keys)));
                    groups.computeIfAbsent(key, k -> new ForwardDigestGroup(t, to, reason)).events.add(e);
                }
            }
            for (ForwardDigestGroup g : groups.values()) sendForwardDigest(g, ownerName, windowLabel);
        } catch (Exception e) {
            log.warn("Sessiz saat özetinin iletim kopyaları atlandı (takım {}): {}", teamId, e.toString());
        }
    }

    /**
     * Alarmın sahip takımları: özet takımı + damga takımı + (envanter gibi yönleniyorsa) envanterin SY / UG takımı. Envanter
     * alan adı başına bir kez okunur (tur içi bellek); okunamazsa yalnız özet + damga takımı.
     */
    private Set<Long> alarmOwners(AlertEvent e, Long digestTeam, Map<String, Set<Long>> cache) {
        Set<Long> owners = new LinkedHashSet<>();
        if (digestTeam != null) owners.add(digestTeam);
        if (e.getTeamId() != null) owners.add(e.getTeamId());
        var inv = inventoryRepo;
        if (inv == null || e.getDomain() == null || !AlertOwnership.routesLikeInventory(e)) return owners;
        Set<Long> fromInventory = cache.computeIfAbsent(e.getDomain().trim().toLowerCase(Locale.ROOT), k -> {
            Set<Long> ids = new LinkedHashSet<>();
            try {
                inv.findByDomain(e.getDomain()).ifPresent(c -> {
                    if (c.getTeamId() != null) ids.add(c.getTeamId());
                    if (c.getUgTeamId() != null) ids.add(c.getUgTeamId());
                });
            } catch (Exception ex) {
                log.debug("Sessiz saat iletim kopyası: envanter sahipleri okunamadı ({}): {}", e.getDomain(), ex.toString());
            }
            return ids;
        });
        owners.addAll(fromInventory);
        return owners;
    }

    /** Bir iletim özeti grubunun postası (ya da karar satırı) + alarm başına satır + açık alarmların damgası. */
    private void sendForwardDigest(ForwardDigestGroup g, String ownerName, String windowLabel) {
        String rtName = g.target.recipientTeamName() == null || g.target.recipientTeamName().isBlank()
                ? "-" : g.target.recipientTeamName().trim();
        List<EmailNotificationService.QuietDigestRow> rows = new ArrayList<>();
        for (AlertEvent e : g.events) rows.add(row(e));
        rows.sort(Comparator.comparing(EmailNotificationService.QuietDigestRow::resolved)
                .thenComparing(r -> -QuietHours.levelValue(r.level()))
                .thenComparing(r -> r.openedAt() == null ? "" : r.openedAt()));
        String subject = EmailNotificationService.quietDigestSubject(ownerName, rows);
        String html = "";
        String status;
        try {
            String base = emailService.buildQuietDigestHtml(ownerName, windowLabel, rows);
            var mail = EmailTemplateBuilder.withForwardNote(
                    new com.sitemonitor.service.mail.MailDoc.Mail(base, com.sitemonitor.service.mail.MailKit.plainTextFor(base)),
                    EmailTemplateBuilder.forwardNote(List.of(ownerName), g.target.recipientTeamName(), true));
            html = mail.html() == null ? "" : mail.html();
            status = g.reason != null ? g.reason
                    : emailService.sendHtml(g.to.toArray(new String[0]), null, subject, mail.html(), mail.text(),
                            List.of(), false, null);
        } catch (Exception ex) {
            status = "FAILED: " + ex.getMessage();
        }
        if (status == null || status.isBlank()) status = "FAILED: boş durum";
        List<NotificationLog> logs = new ArrayList<>();
        for (AlertEvent e : g.events) {
            NotificationLog l = logRow(e.getId(), rtName, String.join(", ", g.to), subject, html, status);
            l.setRecipientRole(EscalationService.ROLE_FORWARD);
            logs.add(l);
        }
        try {
            notificationLogRepo.saveAll(logs);
        } catch (Exception ex) {
            log.warn("Sessiz saat özetinin iletim satırları yazılamadı (alıcı takım {}): {}", g.target.recipientTeamId(),
                    ex.getMessage());
        }
        if (g.reason == null && !g.to.isEmpty() && EscalationService.mailDelivered(status)) {
            for (AlertEvent e : g.events) {
                if (Boolean.TRUE.equals(e.getResolved())) continue;
                ForwardEmailStamp.stamp(alertEventRepo, e.getId(), Map.of(g.target.recipientTeamId(), g.to));
            }
        }
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
            List<QuietDigestItem> stale = itemRepo.findStaleClaims(ISO.format(at.minusMillis(STALE_LOOKBACK_MS)),
                    ISO.format(at.minusMillis(STALE_CLAIM_MS)));
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
