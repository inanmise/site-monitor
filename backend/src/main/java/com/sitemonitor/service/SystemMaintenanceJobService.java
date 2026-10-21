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

    /**
     * Telafi sahiplenmesi bu kadar eskiyse ve telafi bitmediyse ({@code caught_up_count} yok) sahibi yarıda ölmüştür — yeniden
     * koşulur (2026-10-21, çok pod R1 / MP-18). Telafi idempotenttir (yalnız sonuçlanmamış bastırma satırlarını okur, damgayı
     * yalnız bakım bitişinden önceyse sıfırlar).
     */
    static final Duration STALE_CLAIM = Duration.ofMinutes(10);

    /**
     * Bir işin (pencere × iş türü) pod başına en çok kaç kez yeniden deneneceği — sahiplenme bırakılarak ya da bayat
     * sahiplenme devralınarak. Aşılınca vazgeçilir (ERROR; e-posta işlerinde pencereye FAILED durumu), pencere işlerini
     * bitirebilir; sonsuz yeniden deneme yok.
     */
    static final int MAX_JOB_ATTEMPTS = 10;

    /** Pencere × iş türü → başarısız deneme sayısı (pod belleği; vazgeçme sınırı için). */
    private final Map<String, Integer> failedAttempts = new java.util.concurrent.ConcurrentHashMap<>();

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
        } else if (mailWanted && w.getAnnounceMailAt() != null && mailUnsettled(w.getAnnounceMailStatus())
                && staleClaim(w.getAnnounceMailAt(), now)) {
            // F7: duyuru sahiplenildi ama durumu hiç yazılmadı (gönderen öldü) — bakım başlamadıysa BİR kez yeniden; ikinci kez
            // yarıda kaldıysa ya da bakım başladıysa vazgeçilir (durum yazılır).
            if (w.getAnnounceMailStatus() == null && p != null && p.beforeStart()) {
                log.warn("Sistem bakımı #{} duyuru e-postası yarıda kalmıştı ({} tarihli sahiplenme) — bir kez yeniden gönderiliyor",
                        w.getId(), w.getAnnounceMailAt());
                mails += sendAnnouncement(w, now, rev, w.getAnnounceMailAt());
            } else {
                giveUpMail(w, SystemMaintenanceMail.Kind.ANNOUNCE,
                        repo.giveUpAnnounceMail(w.getId(), w.getAnnounceMailAt(), giveUpStatus(w.getAnnounceMailStatus())));
            }
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
            // 4) Telafi ÖNCE: bitiş denetimi telafi sayısını taşısın. Sahiplenme istisnada BIRAKILIR, ölü pod'un bayat
            // sahiplenmesi devralınır (MP-18); telafi bitmeden pencere iş listesinden çıkmaz.
            boolean catchUpSettled = true;
            if (Boolean.TRUE.equals(w.getMuteNotifications()) && w.getCaughtUpCount() == null) {
                catchUpSettled = false;
                String key = w.getId() + ":catch-up";
                String at = SystemMaintenanceService.iso(now);
                String claimedAt = null;
                if (w.getCatchUpAt() == null) {
                    if (repo.claimCatchUp(w.getId(), at) == 1) claimedAt = at;
                } else if (staleClaim(w.getCatchUpAt(), now)) {
                    if (gaveUp(key)) {
                        catchUpSettled = true;   // vazgeçildi (ERROR yazıldı) — pencere işlerini bitirebilir
                    } else if (repo.reclaimCatchUp(w.getId(), w.getCatchUpAt(), at) == 1) {
                        claimedAt = at;
                        log.warn("Sistem bakımı #{} bildirim telafisi yarıda kalmıştı ({} tarihli sahiplenme) — yeniden koşuluyor",
                                w.getId(), w.getCatchUpAt());
                    }
                }
                if (claimedAt != null) {
                    try {
                        caught = catchUp(w, now);
                        catchUpSettled = true;
                        failedAttempts.remove(key);
                    } catch (RuntimeException e) {
                        if (!countFailure(key, w, "catch-up")) repo.releaseCatchUp(w.getId(), claimedAt);
                        throw e;
                    }
                }
            }
            if (catchUpSettled && w.getEndLoggedAt() == null
                    && repo.claimEndLog(w.getId(), SystemMaintenanceService.iso(now)) == 1) {
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
            // 5) "Bakım tamamlandı" e-postası — bitişte BİR kez (sahiplenme kaybı = başka pod / önceki tur gönderdi). Sahiplenmesi
            // sonuçlanmamış e-posta (durum yazılmadı) pencereyi açık tutar; bayatlayınca BİR kez yeniden gönderilir, o da yarıda
            // kalırsa vazgeçilir (inceleme F7 — eskiden pod ölünce durum sonsuza dek NULL kalıyordu, iş yine "bitti" sayılıyordu).
            boolean endMailSettled = true;
            if (recipientsChosen && SystemMaintenanceService.emailOnEnd(w)) {
                if (w.getEndMailAt() == null) {
                    int r = sendEnded(w, now, null);
                    if (r < 0) endMailSettled = false; else mails += r;
                } else if (mailUnsettled(w.getEndMailStatus())) {
                    endMailSettled = false;
                    if (staleClaim(w.getEndMailAt(), now)) {
                        endMailSettled = true;
                        if (w.getEndMailStatus() == null && endedRecently(w, now)) {
                            log.warn("Sistem bakımı #{} bitiş e-postası yarıda kalmıştı ({} tarihli sahiplenme) — bir kez yeniden gönderiliyor",
                                    w.getId(), w.getEndMailAt());
                            int r = sendEnded(w, now, w.getEndMailAt());
                            if (r < 0) endMailSettled = false; else mails += r;
                        } else {
                            giveUpMail(w, SystemMaintenanceMail.Kind.ENDED,
                                    repo.giveUpEndMail(w.getId(), w.getEndMailAt(), giveUpStatus(w.getEndMailStatus())));
                        }
                    }
                }
            }
            if (catchUpSettled && endMailSettled) repo.markJobsDone(w.getId());
        }
        return new int[]{mails, caught};
    }

    /** Yarıda kalmış bitiş e-postası yalnız bu süre içinde yeniden gönderilir — daha geç bir "bakım tamamlandı" postası yanıltır. */
    static final Duration END_MAIL_RETRY_WINDOW = Duration.ofHours(6);

    static boolean endedRecently(SystemMaintenanceWindow w, Instant now) {
        Instant end = SystemMaintenanceService.parse(w.getEndAt());
        return end != null && now.isBefore(end.plus(END_MAIL_RETRY_WINDOW));
    }

    /** Yeniden gönderilen (devralınmış) e-postanın durum öneki — sonuç yazılınca ezilir. */
    static final String STATUS_RETRYING = "RETRY: önceki gönderen pod yarıda durdu — bir kez yeniden gönderiliyor";
    /** İkinci kez yarıda kalan / artık anlamsız e-postanın durumu (yeniden gönderilmez). */
    static final String STATUS_RESULT_UNKNOWN = "FAILED: sonuç bilinmiyor — gönderen pod yarıda durdu; yeniden gönderilmedi";

    /**
     * İlk dilimden HEMEN önce yazılan "gönderim başladı" durumu (inceleme N2). Devralma bunu görünce YENİDEN GÖNDERMEZ (sonuç
     * bilinmiyor) — yalnız gönderime hiç başlamamış sahiplenme (durum NULL) bir kez yeniden gönderilir. Eskiden gönderim başarılı
     * olup yalnız durum yazımı düşünce 10 dk sonra devralma tüm BCC listesine ikinci postayı atıyordu.
     */
    static final String STATUS_SENDING = "SENDING: gönderim başladı — sonuç henüz yazılmadı";
    /** Gönderimi başlamış ama sonucu hiç yazılamamış e-postanın vazgeçme durumu (yeniden gönderilmez). */
    static final String STATUS_SEND_UNCONFIRMED =
            "FAILED: sonuç bilinmiyor — gönderim başladı ama sonucu yazılamadı; yeniden gönderilmedi";
    /** Sonuç yazımının deneme sayısı ve denemeler arası bekleme (ms; test kısaltır). */
    static final int RECORD_ATTEMPTS = 3;
    long recordBackoffMs = 200;

    /** Sahiplenilmiş e-postanın sonucu henüz yazılmadı mı (NULL, devralınıp yeniden gönderiliyor ya da gönderim sürüyor). */
    static boolean mailUnsettled(String status) {
        return status == null || status.startsWith("RETRY:") || status.startsWith("SENDING:");
    }

    /** Bayat, sonuçlanmamış e-postadan vazgeçme durumu: gönderim başladıysa "başladı ama yazılamadı", değilse "pod durdu". */
    static String giveUpStatus(String status) {
        return status != null && status.startsWith("SENDING:") ? STATUS_SEND_UNCONFIRMED : STATUS_RESULT_UNKNOWN;
    }

    /**
     * Gönderim sonrası durum yazımı — {@value #RECORD_ATTEMPTS} deneme, artan bekleme (inceleme N2). Hepsi düşerse ERROR ve
     * istisna: "gönderim başladı" durumu kalır, devralma yeniden GÖNDERMEZ (bayatlayınca {@link #STATUS_SEND_UNCONFIRMED}).
     */
    private void recordWithRetry(SystemMaintenanceWindow w, SystemMaintenanceMail.Kind kind, Runnable write) {
        RuntimeException last = null;
        for (int i = 0; i < RECORD_ATTEMPTS; i++) {
            try {
                write.run();
                return;
            } catch (RuntimeException e) {
                last = e;
                if (i + 1 < RECORD_ATTEMPTS) {
                    try {
                        Thread.sleep(recordBackoffMs * (1L << i));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.error("Sistem bakımı #{} {} e-postası GÖNDERİLDİ ama durumu {} denemede yazılamadı — yeniden gönderilmeyecek: {}",
                w.getId(), kind, RECORD_ATTEMPTS, last == null ? "kesildi" : last.getMessage());
        throw last != null ? last : new IllegalStateException("durum yazımı kesildi");
    }

    /** Vazgeçme izi: ERROR + {@code SYSTEM_MAINTENANCE_MAIL} denetimi (durum = sonuç bilinmiyor). */
    private void giveUpMail(SystemMaintenanceWindow w, SystemMaintenanceMail.Kind kind, int rows) {
        if (rows != 1) return;
        log.error("Sistem bakımı #{} {} e-postası yarıda kaldı ve yeniden gönderilmedi (sonuç bilinmiyor)", w.getId(), kind);
        try {
            auditService.recordSystemEvent("SYSTEM_MAINTENANCE_MAIL", SystemMaintenanceService.RESOURCE, String.valueOf(w.getId()),
                    AuditDetail.of("kind", kind.name(), "recipients", 0, "status", STATUS_RESULT_UNKNOWN));
        } catch (Exception e) {
            log.warn("Sistem bakımı e-posta vazgeçme denetimi yazılamadı: {}", e.getMessage());
        }
    }

    /** Sahiplenme damgası {@link #STALE_CLAIM}'den eski mi (okunamıyorsa eski sayılır — sahibi belirsiz). */
    static boolean staleClaim(String claimedAt, Instant now) {
        Instant at = SystemMaintenanceService.parse(claimedAt);
        return at == null || !now.isBefore(at.plus(STALE_CLAIM));
    }

    /** Vazgeçildi mi ({@link #MAX_JOB_ATTEMPTS} başarısız deneme bu pod'da doldu)? */
    private boolean gaveUp(String key) {
        return failedAttempts.getOrDefault(key, 0) >= MAX_JOB_ATTEMPTS;
    }

    /**
     * Başarısız denemeyi sayar; sınır DOLDUYSA true (sahiplenme bırakılmaz — vazgeçilir; ERROR) — e-posta işlerinde çağıran
     * durumu pencereye de yazar ({@link #STATUS_GAVE_UP}). Sınır dolmadıysa false (çağıran sahiplenmeyi bırakır, sonraki tur
     * yeniden dener).
     */
    private boolean countFailure(String key, SystemMaintenanceWindow w, String job) {
        int n = failedAttempts.merge(key, 1, Integer::sum);
        if (n < MAX_JOB_ATTEMPTS) return false;
        log.error("Sistem bakımı #{} '{}' işi {} denemede tamamlanamadı — vazgeçildi (elle inceleyin)", w.getId(), job, n);
        return true;
    }

    /** Vazgeçilen e-posta işinin pencereye yazılan durumu (ekran "gönderilemedi" der). */
    static final String STATUS_GAVE_UP = "FAILED: e-posta hazırlanamadı — " + MAX_JOB_ATTEMPTS + " denemede vazgeçildi";

    /** Vazgeçme durumunu yazar; yazılamazsa yalnız WARN (iş zaten ERROR ile günlükte). */
    private void recordGaveUp(Runnable write) {
        try {
            write.run();
        } catch (Exception e) {
            log.warn("Sistem bakımı vazgeçme durumu yazılamadı: {}", e.getMessage());
        }
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
        return sendAnnouncement(w, now, rev, null);
    }

    /** {@code reclaimFrom}: devralınan bayat sahiplenmenin damgası (F7); null = yeni sahiplenme. Sahiplenme kaybı → 0. */
    private int sendAnnouncement(SystemMaintenanceWindow w, Instant now, int rev, String reclaimFrom) {
        String at = SystemMaintenanceService.iso(now);
        boolean claimed = reclaimFrom == null ? repo.claimAnnounceMail(w.getId(), at, rev) == 1
                : repo.reclaimAnnounceMail(w.getId(), reclaimFrom, at, STATUS_RETRYING) == 1;
        if (!claimed) return 0;
        List<String> to;
        String status;
        String key = w.getId() + ":announce";
        try {
            to = recipients(w);
            // İlk dilimden önce "gönderim başladı" (N2) — yazılamazsa gönderilmez (sahiplenme bırakılır, sonraki tur dener).
            if (repo.markAnnounceMailSending(w.getId(), at, STATUS_SENDING) != 1) return 0;   // sahiplenme el değiştirdi
            status = send(w, SystemMaintenanceMail.Kind.ANNOUNCE, to);   // dilim hataları içeride yakalanır — istisna = hiç gitmedi
        } catch (RuntimeException e) {
            if (reclaimFrom == null && !countFailure(key, w, "announce")) repo.releaseAnnounceMail(w.getId(), at);   // MP-18
            else recordGaveUp(() -> repo.recordAnnounceMail(w.getId(), STATUS_GAVE_UP, 0));
            throw e;
        }
        failedAttempts.remove(key);
        final String announceStatus = clip(status);
        final int announceCount = to.size();
        recordWithRetry(w, SystemMaintenanceMail.Kind.ANNOUNCE,
                () -> repo.recordAnnounceMail(w.getId(), announceStatus, announceCount));
        auditService.recordSystemEvent("SYSTEM_MAINTENANCE_MAIL", SystemMaintenanceService.RESOURCE, String.valueOf(w.getId()),
                AuditDetail.of("kind", "ANNOUNCE", "recipients", to.size(), "status", status));
        return to.isEmpty() ? 0 : 1;
    }

    private int sendCorrection(SystemMaintenanceWindow w, SystemMaintenanceMail.Kind kind, Instant now, int rev) {
        String at = SystemMaintenanceService.iso(now);
        int prevRev = w.getMailedRevision() == null ? 0 : w.getMailedRevision();
        if (repo.claimCorrectionMail(w.getId(), at, rev) != 1) return 0;
        List<String> to;
        String status;
        String key = w.getId() + ":correction:" + rev;
        try {
            to = recipients(w);
            status = send(w, kind, to);
        } catch (RuntimeException e) {
            if (!countFailure(key, w, "correction")) repo.releaseCorrectionMail(w.getId(), at, rev, prevRev);   // MP-18
            throw e;
        }
        failedAttempts.remove(key);
        repo.incrementCorrectionMails(w.getId());
        auditService.recordSystemEvent("SYSTEM_MAINTENANCE_MAIL", SystemMaintenanceService.RESOURCE, String.valueOf(w.getId()),
                AuditDetail.of("kind", kind.name(), "recipients", to.size(), "status", status, "revision", rev));
        return to.isEmpty() ? 0 : 1;
    }

    /**
     * "Bakım tamamlandı" e-postası — sahiplenme + duyuruyla aynı alıcılar + denetim ({@code SYSTEM_MAINTENANCE_MAIL}, ENDED).
     * {@code reclaimFrom}: devralınan bayat sahiplenmenin damgası (F7); null = yeni sahiplenme. Dönüş: gönderim sayısı (0/1),
     * sahiplenme kaybedildiyse -1 (başka pod / tur sürdürüyor — pencere açık kalır).
     */
    private int sendEnded(SystemMaintenanceWindow w, Instant now, String reclaimFrom) {
        String at = SystemMaintenanceService.iso(now);
        boolean claimed = reclaimFrom == null ? repo.claimEndMail(w.getId(), at) == 1
                : repo.reclaimEndMail(w.getId(), reclaimFrom, at, STATUS_RETRYING) == 1;
        if (!claimed) return -1;
        List<String> to;
        String status;
        String key = w.getId() + ":end-mail";
        try {
            to = recipients(w);
            // İlk dilimden önce "gönderim başladı" (N2) — yazılamazsa gönderilmez (sahiplenme bırakılır, sonraki tur dener).
            if (repo.markEndMailSending(w.getId(), at, STATUS_SENDING) != 1) return -1;   // sahiplenme el değiştirdi
            status = send(w, SystemMaintenanceMail.Kind.ENDED, to);
        } catch (RuntimeException e) {
            if (reclaimFrom == null && !countFailure(key, w, "end-mail")) repo.releaseEndMail(w.getId(), at);   // MP-18
            else recordGaveUp(() -> repo.recordEndMail(w.getId(), STATUS_GAVE_UP, 0));
            throw e;
        }
        failedAttempts.remove(key);
        final String endStatus = clip(status);
        final int endCount = to.size();
        recordWithRetry(w, SystemMaintenanceMail.Kind.ENDED, () -> repo.recordEndMail(w.getId(), endStatus, endCount));
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
