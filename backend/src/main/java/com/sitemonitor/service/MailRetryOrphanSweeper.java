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
 * Geç gelen gerçek sonuç satırı yine düzeltir.
 */
@Slf4j
@Component
public class MailRetryOrphanSweeper {

    /** Kilit adı — KALICI (yayından sonra değiştirilmez). */
    public static final String LOCK = "mail-retry-orphans";
    /** Varsayılan 90 sn gecikmeyle zincir 10.5 dk; teknik hata raporunun sonuç penceresiyle aynı taban. */
    static final Duration MIN_ORPHAN_AGE = Duration.ofMinutes(20);
    /** Zincirin üstüne pay (tek iş parçacıklı yeniden deneme kuyruğunun sırası, SMTP zaman aşımları). */
    static final Duration MARGIN = Duration.ofMinutes(5);
    /** Geriye bakış — daha eski takılı satırlar (bu sürümden önce) dokunulmadan kalır. */
    static final Duration LOOKBACK = Duration.ofDays(2);

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    static final String SQL = "UPDATE notification_logs SET email_status = ? "
            + "WHERE sent_at >= ? AND sent_at < ? AND email_status LIKE 'QUEUED_RETRY%'";

    private final JdbcTemplate jdbc;
    private final EmailNotificationService emailService;

    @Autowired(required = false)
    private SchedulerService scheduler;

    public MailRetryOrphanSweeper(JdbcTemplate jdbc, EmailNotificationService emailService) {
        this.jdbc = jdbc;
        this.emailService = emailService;
    }

    /** Test dikişi. */
    void setScheduler(SchedulerService scheduler) {
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelayString = "${site.monitor.mail.retry-orphan-sweep-ms:300000}",
               initialDelayString = "${site.monitor.mail.retry-orphan-sweep-initial-ms:180000}")
    public void scheduledSweep() {
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
        int n = jdbc.update(SQL, EmailNotificationService.STATUS_RETRY_ORPHANED, from, before);
        if (n > 0) {
            log.warn("SMTP 421 yeniden denemesinin sonucu gelmeyen {} bildirim satırı FAILED yapıldı (gönderen pod kapanmış; eşik {} dk)",
                    n, age.toMinutes());
        }
        return n;
    }

    /** Yetim eşiği: yeniden deneme zinciri + pay, en az {@link #MIN_ORPHAN_AGE}. */
    Duration orphanAge() {
        Duration chain = emailService == null ? Duration.ZERO : emailService.currentRetryChainDuration();
        Duration a = chain.plus(MARGIN);
        return a.compareTo(MIN_ORPHAN_AGE) < 0 ? MIN_ORPHAN_AGE : a;
    }
}
