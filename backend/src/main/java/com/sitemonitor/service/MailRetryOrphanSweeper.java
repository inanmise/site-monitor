package com.sitemonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Sonucu hiç gelmeyecek SMTP 421 yeniden denemelerini KAPATIR (2026-10-21, çok pod R1 / MP-06).
 *
 * <p>421 yeniden denemeleri gönderen pod'un BELLEĞİNDE yaşar ({@code EmailNotificationService}, 90 / 180 / 360 sn). Pod
 * kapanırken iptal edilenleri o pod kendisi FAILED yazar; ama pod ÖLDÜYSE (OOM, SIGKILL, düğüm kaybı) satır sonsuza dek
 * {@code QUEUED_RETRY…} kalıyordu: alarm penceresi "kuyrukta" gösteriyor, 7/24 açılışı "gitti" sayıp yeniden denemiyor, kanal
 * teknik hata raporu kuyrukta takılıyordu. Bu iş her {@code site.monitor.mail.retry-orphan-sweep-ms} (5 dk) bir, kümede TEK
 * pod'da ({@code scheduler_lock} {@value #LOCK}), yeniden deneme zincirinden ({@link EmailNotificationService#retryChainDuration}
 * + 5 dk, en az {@link #MIN_ORPHAN_AGE}) eski {@code QUEUED_RETRY} satırlarını {@link EmailNotificationService#STATUS_RETRY_ORPHANED}
 * yapar — KOŞULLU tek UPDATE (yalnız hâlâ {@code QUEUED_RETRY} olan satır; iki pod aynı satırı iki kez yazmaz, kilit yalnız
 * boşa iş yapılmasın diye). Tarama son {@link #LOOKBACK} ile sınırlı ({@code idx_nl_sent_at} aralık taraması; tüm tablo okunmaz).
 * Geç gelen gerçek sonuç satırı yine düzeltir. Aynı turda 7/24 teslim satırlarının ({@code noc_deliveries}) kuyruk sonucu da
 * yazılır ({@code NocQueuedRetryReconciler}), ardından kanal teknik hata raporlarının kuyrukları sonuçlandırılır (R2a, MP-41 —
 * {@code TechErrorReportService.reconcileAllQueued}).
 */
@Slf4j
@Component
public class MailRetryOrphanSweeper {

    /** Kilit adı — KALICI (yayından sonra değiştirilmez). */
    public static final String LOCK = "mail-retry-orphans";
    /** Taban — {@link EmailNotificationService#MIN_ORPHAN_AGE} (formül tek yerde). */
    static final Duration MIN_ORPHAN_AGE = EmailNotificationService.MIN_ORPHAN_AGE;
    /** Geriye bakış — daha eski takılı satırlar (bu sürümden önce) dokunulmadan kalır. */
    static final Duration LOOKBACK = Duration.ofDays(2);

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /**
     * Canlı pod'un tazelediği satır ({@code retry_alive_at} {@link #aliveTtl} içinde) yetim sayılmaz (inceleme N4) — en çok
     * {@link #MAX_ALIVE} boyunca; daha eski satır damgadan bağımsız kapanır (takılı kalmış iş parçacığı sonsuz bekletmez).
     */
    static final String SQL = "UPDATE notification_logs SET email_status = ? "
            + "WHERE sent_at >= ? AND sent_at < ? AND email_status LIKE 'QUEUED_RETRY%' "
            + "AND (retry_alive_at IS NULL OR retry_alive_at < ? OR sent_at < ?)";
    /** Kolon yoksa (yama uygulanamadı) eski ifade. */
    static final String SQL_LEGACY = "UPDATE notification_logs SET email_status = ? "
            + "WHERE sent_at >= ? AND sent_at < ? AND email_status LIKE 'QUEUED_RETRY%'";
    /** Canlılık damgasının en az geçerlilik süresi (tur aralığının 3 katı daha uzunsa o). */
    static final Duration MIN_ALIVE_TTL = Duration.ofMinutes(15);
    /** Canlı pod'un satırı bile bu yaştan sonra yetim sayılır. */
    static final Duration MAX_ALIVE = Duration.ofHours(2);

    /** Tur aralığı (canlılık süresi bundan türetilir). */
    @org.springframework.beans.factory.annotation.Value("${site.monitor.mail.retry-orphan-sweep-ms:300000}")
    long sweepIntervalMs = 300_000L;

    private final JdbcTemplate jdbc;
    private final EmailNotificationService emailService;

    @Autowired(required = false)
    private SchedulerService scheduler;

    /**
     * 7/24 teslim satırlarının kuyruk sonucu (2026-10-21, MP-06) — posta günlüğü yetimleri yazıldıktan SONRA, aynı turda ve
     * aynı eşikle. Yoksa (dilim testleri) atlanır.
     */
    @Autowired(required = false)
    private com.sitemonitor.service.noc.NocQueuedRetryReconciler nocReconciler;

    /**
     * Kanal teknik hata raporlarının kuyruk sonucu (çok pod R2a, MP-41, 2026-10-22) — posta günlüğü yetimleri yazıldıktan
     * SONRA aynı turda: posta servisinin geri yazdığı SENT / FAILED (ya da bu turda yetim sayılan satır) raporu kimse ekranı
     * açmasa da sonuçlandırır. Yoksa (dilim testleri) atlanır.
     */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.sitemonitor.service.techerror.TechErrorReportService techErrorReports;

    public MailRetryOrphanSweeper(JdbcTemplate jdbc, EmailNotificationService emailService) {
        this.jdbc = jdbc;
        this.emailService = emailService;
    }

    /** Test dikişi. */
    void setScheduler(SchedulerService scheduler) {
        this.scheduler = scheduler;
    }

    /** Test dikişi. */
    public void setNocReconciler(com.sitemonitor.service.noc.NocQueuedRetryReconciler nocReconciler) {
        this.nocReconciler = nocReconciler;
    }

    /** Test dikişi. */
    public void setTechErrorReports(com.sitemonitor.service.techerror.TechErrorReportService techErrorReports) {
        this.techErrorReports = techErrorReports;
    }

    @Scheduled(fixedDelayString = "${site.monitor.mail.retry-orphan-sweep-ms:300000}",
               initialDelayString = "${site.monitor.mail.retry-orphan-sweep-initial-ms:180000}")
    public void scheduledSweep() {
        // Her pod KENDİ bekleyen yeniden denemelerini tazeler (kilitten bağımsız — yalnız bir pod süpürür ama her canlı pod
        // kendi satırını korumalı).
        try {
            if (emailService != null) emailService.heartbeatPendingRetries(ISO.format(Instant.now()));
        } catch (Exception e) {
            log.debug("421 yeniden deneme canlılık damgası atlandı: {}", e.getMessage());
        }
        try {
            if (scheduler == null) {
                sweep(Instant.now());
            } else {
                scheduler.callWithSchedulerLock(LOCK, () -> sweep(Instant.now()));
            }
        } catch (Exception e) {
            log.warn("SMTP 421 yetim satır süpürmesi atlandı (sonraki turda yeniden denenir): {}", e.getMessage());
        }
    }

    /** Bir tur — yetim sayılan satır sayısını döner. */
    int sweep(Instant now) {
        Duration age = orphanAge();
        String before = ISO.format(now.minus(age));
        String from = ISO.format(now.minus(LOOKBACK));
        String aliveBefore = ISO.format(now.minus(aliveTtl()));
        String hardBefore = ISO.format(now.minus(MAX_ALIVE));
        int n;
        try {
            n = jdbc.update(SQL, EmailNotificationService.STATUS_RETRY_ORPHANED, from, before, aliveBefore, hardBefore);
        } catch (org.springframework.jdbc.BadSqlGrammarException noColumn) {
            n = jdbc.update(SQL_LEGACY, EmailNotificationService.STATUS_RETRY_ORPHANED, from, before);
        }
        if (n > 0) {
            log.warn("SMTP 421 yeniden denemesinin sonucu gelmeyen {} bildirim satırı FAILED yapıldı (gönderen pod kapanmış; eşik {} dk)",
                    n, age.toMinutes());
        }
        if (nocReconciler != null) {
            try {
                int noc = nocReconciler.reconcile(now, age);
                if (noc > 0) log.info("7/24 teslimlerinin {} tanesinin 421 kuyruk sonucu yazıldı", noc);
            } catch (Exception e) {
                log.warn("7/24 teslimlerinin kuyruk sonucu yazılamadı (sonraki turda yeniden denenir): {}", e.getMessage());
            }
        }
        if (techErrorReports != null) {
            try {
                int te = techErrorReports.reconcileAllQueued();
                if (te > 0) log.info("Kanal teknik hata raporlarının {} kuyruk gönderiminin sonucu yazıldı", te);
            } catch (Exception e) {
                log.warn("Kanal teknik hata raporlarının kuyruk sonucu yazılamadı (sonraki turda yeniden denenir): {}", e.getMessage());
            }
        }
        return n;
    }

    /** Canlılık damgasının geçerlilik süresi: max({@link #MIN_ALIVE_TTL}, 3 × tur aralığı). */
    Duration aliveTtl() {
        Duration three = Duration.ofMillis(Math.max(0, sweepIntervalMs) * 3);
        return three.compareTo(MIN_ALIVE_TTL) > 0 ? three : MIN_ALIVE_TTL;
    }

    /** Yetim eşiği: yeniden deneme zinciri + pay, en az {@link #MIN_ORPHAN_AGE}. */
    Duration orphanAge() {
        Duration chain = emailService == null ? Duration.ZERO : emailService.currentRetryChainDuration();
        // EmailNotificationService.orphanAge ile AYNI formül — canlı pod'un yeniden denemesi bu eşikten önce başlamak zorunda
        // (retryStartDeadline), yani yetim sayılan satır canlı bir gönderimin satırı olamaz (inceleme D-2).
        Duration a = chain.plus(EmailNotificationService.ORPHAN_MARGIN);
        return a.compareTo(MIN_ORPHAN_AGE) < 0 ? MIN_ORPHAN_AGE : a;
    }
}
