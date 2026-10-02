package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.SystemMaintenanceSuppression;
import com.sitemonitor.model.SystemMaintenanceWindow;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.SystemMaintenanceSuppressionRepository;
import com.sitemonitor.repository.SystemMaintenanceWindowRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.SystemMaintenanceService.Phase;
import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.SystemMaintenanceMail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sistem Bakım Modu YAN İŞLERİ (2026-10-02, kullanıcı kararı) — {@code scheduler_lock} ({@value #LOCK_NAME}) altında tek
 * pod'da, kısa aralıkla ({@code site.monitor.system-maintenance.job-interval-ms}, varsayılan 30 sn). Bakımın KENDİSİ
 * (giriş kapısı, oturum kesimi, bildirim susturma) bu işe bağlı DEĞİLDİR — durum zamandan türetilir
 * ({@link SystemMaintenanceService#phaseOf}); burası yalnız "bir kez yapılacak" işleri yürütür:
 *
 * <ol>
 *   <li><b>Duyuru e-postası</b> — planlamada BİR kez (alıcılar: tüm aktif kullanıcılar ve/veya seçili takım adresleri,
 *       gizli/BCC; pasif kullanıcılar sorguda ve merkezî e-posta süzgecinde düşer).</li>
 *   <li><b>Düzeltme e-postası</b> — duyuru gittikten sonra saat değişirse (düzenle/uzat) ya da iptal edilirse, anahtar
 *       açıksa ({@code revision} &gt; {@code mailed_revision}).</li>
 *   <li><b>Başlangıç / bitiş denetimi</b> — {@code SYSTEM_MAINTENANCE_STARTED} / {@code _ENDED} (aktör SYSTEM), tam bir
 *       kez (koşullu UPDATE sahiplenmesi).</li>
 *   <li><b>Bildirim telafisi</b> — "Bildirimler bakım boyunca sussun" açık bakım bitince: açılış bildirimi susturulmuş,
 *       hâlâ açık ve onaylanmamış alarmların "ilk bildirim gitti" damgası sıfırlanır → bir sonraki tur INITIAL'ı normal
 *       kurallarla (takım yönlendirmesi, sessiz saat, bakım penceresi…) BİR kez gönderir. Bakım içinde kapanan / onaylanan
 *       alarm için hiçbir şey gitmez (kararı satırına yazılır).</li>
 *   <li><b>"Bakım tamamlandı" e-postası</b> (2026-10-02, kullanıcı isteği: "planlı bakım sonlandığında kullanıcılara bir
 *       uyarı daha gönderilsin") — bakım BİTİNCE (iptal değil), "Bakım bitince de e-posta gönder" açıksa (varsayılan) ve
 *       e-posta alıcısı seçilmişse, duyuruyla AYNI alıcılara BİR kez (atomik sahiplenme; "Hemen bitir" sonraki turda,
 *       ≤ iş aralığı). "Hemen bakıma al"da duyuru/düzeltme e-postası YOKTUR (duyuru planlama özelliği) — orada seçilen
 *       alıcılar yalnız bu bitiş e-postasını alır.</li>
 * </ol>
 *
 * <p>Her iş kendi sahiplenmesiyle "tam bir kez"dir: iki pod kilit süresi aşımında aynı anda koşsa bile e-posta / denetim /
 * telafi ikinci kez yapılmaz. Bitmiş (ya da iptal edilmiş) ve işleri tamamlanmış satır {@code jobs_done} ile tur dışına
 * çıkar; işi olmayan turda tek boolean sorgu, kilit yok.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemMaintenanceJobService {

    public static final String LOCK_NAME = "system-maintenance";
    /** Tek e-postadaki gizli alıcı tavanı — kurumsal ağ geçitleri büyük alıcı listelerini reddedebiliyor. */
    static final int BCC_CHUNK = 100;

    private final SystemMaintenanceWindowRepository repo;
    private final SystemMaintenanceSuppressionRepository suppressionRepo;
    private final AlertEventRepository alertEventRepo;
    private final AppUserRepository userRepo;
    private final TeamRepository teamRepo;
    private final EmailNotificationService emailService;
    private final AuditService auditService;
    private final SchedulerService schedulerService;
    private final SystemMaintenanceService maintenance;
    private final AppSettingsService appSettings;

    @Value("${site.monitor.app.base-url:http://localhost:5173}")
    private String fallbackBaseUrl = "";

    /** Tur özeti (log + test). */
    public record RunResult(int windows, int mails, int caughtUp) {
        static final RunResult EMPTY = new RunResult(0, 0, 0);
    }

    @Scheduled(fixedDelayString = "${site.monitor.system-maintenance.job-interval-ms:30000}",
               initialDelayString = "${site.monitor.system-maintenance.job-initial-delay-ms:40000}")
    public void scheduledRun() {
        if (!anyPending()) return;
        schedulerService.runWithSchedulerLock(LOCK_NAME, () -> {
            RunResult r = run(maintenance.now());
            if (r.mails() > 0 || r.caughtUp() > 0) {
                log.info("Sistem bakımı yan işleri: {} pencere, {} e-posta gönderimi, {} alarm telafiye alındı",
                        r.windows(), r.mails(), r.caughtUp());
            }
        });
    }

    boolean anyPending() {
        try {
            return repo.existsPendingJobs();
        } catch (Exception e) {
            log.debug("Sistem bakımı iş ön kapısı okunamadı (tablo henüz yok?): {}", e.getMessage());
            return false;
        }
    }

    /** Tek tur — çağıran kilidi tutar. Paket-özel: test doğrudan çağırır. */
    RunResult run(Instant now) {
        List<SystemMaintenanceWindow> pending = repo.findPendingJobs();
        if (pending == null || pending.isEmpty()) return RunResult.EMPTY;
        int mails = 0, caught = 0;
        for (SystemMaintenanceWindow w : pending) {
            try {
                int[] r = process(w, now);
                mails += r[0];
                caught += r[1];
            } catch (Exception e) {
                log.warn("Sistem bakımı #{} yan işi atlandı (sonraki turda yeniden denenir): {}", w.getId(), e.toString());
            }
        }
        return new RunResult(pending.size(), mails, caught);
    }

    /** @return {e-posta gönderim sayısı, telafiye alınan alarm sayısı} */
    int[] process(SystemMaintenanceWindow w, Instant now) {
        Phase p = SystemMaintenanceService.phaseOf(w, now);
        int mails = 0, caught = 0;
        boolean recipientsChosen = wantsMail(w);
        // Duyuru / düzeltme e-postası planlama özelliğidir: "Hemen bakıma al"da alıcılar yalnız bitiş e-postası içindir
        // (2026-10-02 — geri sayım 10 sn iken iş turuna denk gelip gelmemesine göre değişen duyuru belirsizliği yok).
        boolean mailWanted = recipientsChosen && !Boolean.TRUE.equals(w.getImmediate());
        boolean corrections = Boolean.TRUE.equals(w.getEmailCorrections());
        int rev = w.getRevision() == null ? 1 : w.getRevision();
        int mailedRev = w.getMailedRevision() == null ? 0 : w.getMailedRevision();

        if (p == Phase.CANCELLED) {
            if (mailWanted && corrections && w.getAnnounceMailAt() != null && mailedRev < rev) {
                mails += sendCorrection(w, SystemMaintenanceMail.Kind.CANCELLED, now, rev);
            }
            repo.markJobsDone(w.getId());
            return new int[]{mails, 0};
        }

        // 1) Duyuru — planlamada BİR kez (bakım başlamadan önce). 2) Saat değiştiyse düzeltme (bakım bitmeden).
        if (mailWanted && w.getAnnounceMailAt() == null && p != null && p.beforeStart()) {
            mails += sendAnnouncement(w, now, rev);
        } else if (mailWanted && corrections && w.getAnnounceMailAt() != null && mailedRev < rev && p != Phase.ENDED) {
            mails += sendCorrection(w, SystemMaintenanceMail.Kind.UPDATED, now, rev);
        }

        // 3) Başlangıç denetimi (saat gelince sistem başlatır — "hemen" bakımda da asıl başlangıç anı budur).
        if ((p == Phase.ACTIVE || p == Phase.ENDED) && w.getStartLoggedAt() == null
                && repo.claimStartLog(w.getId(), SystemMaintenanceService.iso(now)) == 1) {
            auditService.recordSystemEvent("SYSTEM_MAINTENANCE_STARTED", SystemMaintenanceService.RESOURCE, String.valueOf(w.getId()),
                    AuditDetail.of("start", SystemMaintenanceService.wire(w.getStartAt()), "end", SystemMaintenanceService.wire(w.getEndAt()),
                            "immediate", Boolean.TRUE.equals(w.getImmediate()), "started_by", w.getStartedBy(),
                            "mute_notifications", Boolean.TRUE.equals(w.getMuteNotifications())));
            log.warn("Sistem bakımı BAŞLADI: #{} {} → {} — yalnız global yöneticiler giriş yapabilir", w.getId(),
                    w.getStartAt(), w.getEndAt());
        }

        if (p == Phase.ENDED) {
            // 4) Telafi ÖNCE: bitiş denetimi telafi sayısını taşısın.
            if (Boolean.TRUE.equals(w.getMuteNotifications()) && w.getCatchUpAt() == null
                    && repo.claimCatchUp(w.getId(), SystemMaintenanceService.iso(now)) == 1) {
                caught = catchUp(w, now);
            }
            if (w.getEndLoggedAt() == null && repo.claimEndLog(w.getId(), SystemMaintenanceService.iso(now)) == 1) {
                SystemMaintenanceWindow fresh = repo.findById(w.getId()).orElse(w);
                auditService.recordSystemEvent("SYSTEM_MAINTENANCE_ENDED", SystemMaintenanceService.RESOURCE, String.valueOf(w.getId()),
                        AuditDetail.of("planned_start", SystemMaintenanceService.wire(fresh.getPlannedStartAt()),
                                "planned_end", SystemMaintenanceService.wire(fresh.getPlannedEndAt()),
                                "actual_start", SystemMaintenanceService.wire(fresh.getStartAt()),
                                "actual_end", SystemMaintenanceService.wire(fresh.getEndAt()),
                                "ended_by", fresh.getEndedBy() == null ? "SYSTEM" : fresh.getEndedBy(),
                                "sessions_ended", nz(fresh.getSessionsEnded()), "logins_blocked", nz(fresh.getLoginsBlocked()),
                                "notifications_suppressed", nz(fresh.getNotificationsSuppressed()), "caught_up", caught));
                log.warn("Sistem bakımı BİTTİ: #{} — girişler açıldı (kapatılan oturum {}, engellenen giriş {}, susturulan bildirim {}, "
                        + "telafi {})", w.getId(), nz(fresh.getSessionsEnded()), nz(fresh.getLoginsBlocked()),
                        nz(fresh.getNotificationsSuppressed()), caught);
            }
            // 5) "Bakım tamamlandı" e-postası — bitişte BİR kez (sahiplenme kaybı = başka pod / önceki tur gönderdi).
            if (recipientsChosen && SystemMaintenanceService.emailOnEnd(w) && w.getEndMailAt() == null) {
                mails += sendEnded(w, now);
            }
            repo.markJobsDone(w.getId());
        }
        return new int[]{mails, caught};
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }

    private static boolean wantsMail(SystemMaintenanceWindow w) {
        return Boolean.TRUE.equals(w.getEmailAllUsers()) || !SystemMaintenanceService.parseIds(w.getEmailTeamIds()).isEmpty();
    }

    // ── Bildirim telafisi ───────────────────────────────────────────────────────────────────────────

    /**
     * Bakımda AÇILIŞ bildirimi susturulmuş alarmların kararı: hâlâ açık + onaysız → "ilk bildirim" damgası sıfırlanır
     * (sonraki tur INITIAL'ı BİR kez gönderir); kapanmış → hiçbir şey; onaylanmış → hiçbir şey (biri ilgileniyor).
     * Yalnız re-alert'i susturulmuş (açılışı bakımdan önce gitmiş) alarm telafi edilmez — günlük kadansı normal sürer.
     */
    int catchUp(SystemMaintenanceWindow w, Instant now) {
        String at = SystemMaintenanceService.iso(now);
        List<SystemMaintenanceSuppression> rows = suppressionRepo.findByWindowIdAndCaughtUpAtIsNull(w.getId());
        if (rows == null || rows.isEmpty()) {
            repo.recordCaughtUp(w.getId(), 0);
            return 0;
        }
        List<Long> openingIds = new ArrayList<>();
        List<Long> notOpening = new ArrayList<>();
        for (SystemMaintenanceSuppression s : rows) {
            if (s.getAlertEventId() == null) continue;
            if (Boolean.TRUE.equals(s.getOpening())) openingIds.add(s.getAlertEventId());
            else notOpening.add(s.getAlertEventId());
        }
        Map<Long, AlertEvent> events = new HashMap<>();
        if (!openingIds.isEmpty()) {
            for (AlertEvent e : alertEventRepo.findAllById(openingIds)) if (e != null && e.getId() != null) events.put(e.getId(), e);
        }
        List<Long> resolved = new ArrayList<>(), acked = new ArrayList<>(), candidates = new ArrayList<>();
        for (Long id : openingIds) {
            AlertEvent e = events.get(id);
            if (e == null || Boolean.TRUE.equals(e.getResolved())) resolved.add(id);
            else if (Boolean.TRUE.equals(e.getAcknowledged())) acked.add(id);
            else candidates.add(id);
        }
        int n = candidates.isEmpty() ? 0 : alertEventRepo.clearInitialStampForCatchUp(candidates, w.getEndAt());
        if (!candidates.isEmpty()) suppressionRepo.markOutcome(w.getId(), candidates, SystemMaintenanceSuppression.OUTCOME_CAUGHT_UP, at);
        if (!resolved.isEmpty()) suppressionRepo.markOutcome(w.getId(), resolved, SystemMaintenanceSuppression.OUTCOME_RESOLVED, at);
        if (!acked.isEmpty()) suppressionRepo.markOutcome(w.getId(), acked, SystemMaintenanceSuppression.OUTCOME_ACKNOWLEDGED, at);
        if (!notOpening.isEmpty()) suppressionRepo.markOutcome(w.getId(), notOpening, SystemMaintenanceSuppression.OUTCOME_NOT_OPENING, at);
        repo.recordCaughtUp(w.getId(), n);
        log.info("Sistem bakımı #{} bildirim telafisi: {} alarm sonraki turda INITIAL alacak, {} bakımda kapandı, {} onaylı, "
                + "{} yalnız tekrar hatırlatması susturulmuştu", w.getId(), n, resolved.size(), acked.size(), notOpening.size());
        return n;
    }

    // ── E-posta duyurusu ────────────────────────────────────────────────────────────────────────────

    private int sendAnnouncement(SystemMaintenanceWindow w, Instant now, int rev) {
        if (repo.claimAnnounceMail(w.getId(), SystemMaintenanceService.iso(now), rev) != 1) return 0;
        List<String> to = recipients(w);
        String status = send(w, SystemMaintenanceMail.Kind.ANNOUNCE, to);
        repo.recordAnnounceMail(w.getId(), clip(status), to.size());
        auditService.recordSystemEvent("SYSTEM_MAINTENANCE_MAIL", SystemMaintenanceService.RESOURCE, String.valueOf(w.getId()),
                AuditDetail.of("kind", "ANNOUNCE", "recipients", to.size(), "status", status));
        return to.isEmpty() ? 0 : 1;
    }

    private int sendCorrection(SystemMaintenanceWindow w, SystemMaintenanceMail.Kind kind, Instant now, int rev) {
        if (repo.claimCorrectionMail(w.getId(), SystemMaintenanceService.iso(now), rev) != 1) return 0;
        List<String> to = recipients(w);
        String status = send(w, kind, to);
        repo.incrementCorrectionMails(w.getId());
        auditService.recordSystemEvent("SYSTEM_MAINTENANCE_MAIL", SystemMaintenanceService.RESOURCE, String.valueOf(w.getId()),
                AuditDetail.of("kind", kind.name(), "recipients", to.size(), "status", status, "revision", rev));
        return to.isEmpty() ? 0 : 1;
    }

    /** "Bakım tamamlandı" e-postası — sahiplenme + duyuruyla aynı alıcılar + denetim ({@code SYSTEM_MAINTENANCE_MAIL}, ENDED). */
    private int sendEnded(SystemMaintenanceWindow w, Instant now) {
        if (repo.claimEndMail(w.getId(), SystemMaintenanceService.iso(now)) != 1) return 0;
        List<String> to = recipients(w);
        String status = send(w, SystemMaintenanceMail.Kind.ENDED, to);
        repo.recordEndMail(w.getId(), clip(status), to.size());
        auditService.recordSystemEvent("SYSTEM_MAINTENANCE_MAIL", SystemMaintenanceService.RESOURCE, String.valueOf(w.getId()),
                AuditDetail.of("kind", SystemMaintenanceMail.Kind.ENDED.name(), "recipients", to.size(), "status", status,
                        "planned_end", SystemMaintenanceService.wire(w.getPlannedEndAt()),
                        "actual_end", SystemMaintenanceService.wire(w.getEndAt())));
        return to.isEmpty() ? 0 : 1;
    }

    /** Gerçek bitişin plana göre yeri — 1 dk'dan küçük fark "zamanında" sayılır. */
    static SystemMaintenanceMail.EndShift endShift(SystemMaintenanceWindow w) {
        Instant planned = SystemMaintenanceService.parse(w.getPlannedEndAt());
        Instant actual = SystemMaintenanceService.parse(w.getEndAt());
        if (planned == null || actual == null) return SystemMaintenanceMail.EndShift.ON_TIME;
        long diff = Duration.between(planned, actual).getSeconds();
        if (diff <= -60) return SystemMaintenanceMail.EndShift.EARLY;
        if (diff >= 60) return SystemMaintenanceMail.EndShift.EXTENDED;
        return SystemMaintenanceMail.EndShift.ON_TIME;
    }

    /**
     * Alıcılar: "tüm aktif kullanıcılar" (pasifler sorguda düşer) ve/veya seçili takımların adresleri — küçük harf
     * tekilleştirilir. Merkezî süzgeç ({@code InactiveRecipientGuard}) pasif kişiye ait adresleri ayrıca düşürür.
     */
    List<String> recipients(SystemMaintenanceWindow w) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        if (Boolean.TRUE.equals(w.getEmailAllUsers())) {
            for (AppUser u : userRepo.findByActiveTrueOrderByUsernameAsc()) {
                if (!Boolean.TRUE.equals(u.getActive())) continue;
                addEmail(u.getEmail(), seen, out);
            }
        }
        List<Long> teamIds = SystemMaintenanceService.parseIds(w.getEmailTeamIds());
        if (!teamIds.isEmpty()) {
            teamRepo.findAllById(teamIds).forEach(t -> {
                if (t != null && !Boolean.FALSE.equals(t.getActive()) && t.getEmail() != null) {
                    for (String part : t.getEmail().split("[,;]")) addEmail(part, seen, out);
                }
            });
        }
        return out;
    }

    private static void addEmail(String raw, Set<String> seen, List<String> out) {
        if (raw == null) return;
        String e = raw.trim();
        if (e.isEmpty() || !e.contains("@")) return;
        if (seen.add(e.toLowerCase(Locale.ROOT))) out.add(e);
    }

    /** Gizli alıcılarla, {@link #BCC_CHUNK}'lık dilimlerle gönderir; dilim sonuçlarının özeti döner. */
    private String send(SystemMaintenanceWindow w, SystemMaintenanceMail.Kind kind, List<String> to) {
        if (to.isEmpty()) return "SKIPPED: alıcı yok";
        boolean ended = kind == SystemMaintenanceMail.Kind.ENDED;
        SystemMaintenanceMail.Info info = new SystemMaintenanceMail.Info(kind,
                SystemMaintenanceService.windowText(w.getStartAt(), w.getEndAt()),
                durationText(w.getStartAt(), w.getEndAt()), w.getMessageTr(), w.getMessageEn(), w.getContact(), baseUrl(),
                ended ? SystemMaintenanceService.windowText(w.getPlannedStartAt(), w.getPlannedEndAt()) : null,
                ended ? endShift(w) : null);
        MailDoc.Mail mail = SystemMaintenanceMail.build(info);
        String subject = SystemMaintenanceMail.subject(info);
        Map<String, Integer> statuses = new java.util.LinkedHashMap<>();
        for (int i = 0; i < to.size(); i += BCC_CHUNK) {
            List<String> chunk = to.subList(i, Math.min(to.size(), i + BCC_CHUNK));
            String st;
            try {
                st = emailService.sendHtmlBcc(chunk.toArray(new String[0]), subject, mail.html(), mail.text());
            } catch (Exception e) {
                st = "FAILED: " + e.getMessage();
            }
            String key = st == null ? "FAILED" : st.startsWith("FAILED") ? "FAILED" : st.startsWith("QUEUED_RETRY") ? "QUEUED_RETRY" : st;
            statuses.merge(key, chunk.size(), Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        statuses.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ", ").append(k).append(" ×").append(v));
        log.info("Sistem bakımı #{} {} e-postası: {} alıcı → {}", w.getId(), kind, to.size(), sb);
        return sb.toString();
    }

    private String baseUrl() {
        String url = appSettings.getString("site.monitor.app.base-url", fallbackBaseUrl);
        return url == null || url.isBlank() ? null : url.trim().replaceAll("/+$", "");
    }

    /** "1 sa 30 dk" / "45 dk" / "2 sa". */
    static String durationText(String startIso, String endIso) {
        Instant s = SystemMaintenanceService.parse(startIso), e = SystemMaintenanceService.parse(endIso);
        if (s == null || e == null || !e.isAfter(s)) return null;
        long min = Duration.between(s, e).toMinutes();
        long h = min / 60, m = min % 60;
        if (h == 0) return m + " dk";
        return m == 0 ? h + " sa" : h + " sa " + m + " dk";
    }

    private static String clip(String s) {
        return s == null ? null : s.length() > 200 ? s.substring(0, 200) : s;
    }
}
