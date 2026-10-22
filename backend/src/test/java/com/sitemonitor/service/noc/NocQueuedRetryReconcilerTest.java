package com.sitemonitor.service.noc;

import com.sitemonitor.model.NocDelivery;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.NocDeliveryRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.MailRetryOrphanSweeper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 7/24 teslim satırlarının SMTP 421 kuyruk sonucu (2026-10-21, çok pod R1 / MP-06) — H2'de GERÇEK depo. Eskiden teslim satırı
 * ilk denemenin {@code QUEUED_RETRY} cevabında kalıyor, yeniden denemenin sonucu yalnız posta günlüğüne yazılıyordu: gönderen
 * pod ölünce (ya da deneme sonunda düşünce) açılış sonsuza dek "gitti" sayılıyordu ({@code noc=sent}, konsol, çözüm postası).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NocQueuedRetryReconcilerTest {

    static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final Duration ORPHAN_AGE = Duration.ofMinutes(20);

    @Autowired NocDeliveryRepository deliveries;
    @Autowired NotificationLogRepository logs;
    @Autowired DataSource dataSource;

    JdbcTemplate jdbc;
    Instant now;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        deliveries.deleteAll();
        logs.deleteAll();
        now = Instant.now();
    }

    NocDelivery delivery(String key, String status, Instant at, String subject) {
        NocDelivery d = new NocDelivery();
        d.setDedupeKey(key);
        d.setAlertEventId(1L);
        d.setPhase(NocDelivery.OPEN);
        d.setStatus(status);
        d.setSubject(subject);
        d.setAttempts(1);
        d.setCreatedAt(ISO.format(at));
        d.setUpdatedAt(ISO.format(at));
        return deliveries.save(d);
    }

    void logRow(String subject, String status, Instant at) {
        NotificationLog n = new NotificationLog();
        n.setAlertEventId(1L);
        n.setSentAt(ISO.format(at));
        n.setRecipientName("7/24 İzleme Ekibi");
        n.setRecipientEmail("NOC Ana (2 adres)");
        n.setRecipientRole(NocNotificationService.LOG_ROLE);
        n.setSubject(subject);
        n.setEmailStatus(status);
        n.setTrigger(NocNotificationService.TRIGGER_OPEN);
        logs.save(n);
    }

    String status(NocDelivery d) {
        return deliveries.findById(d.getId()).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("kesin sonuç günlükten aynalanır (SENT / FAILED); günlükte hâlâ kuyruktaki eski deneme yetim FAILED olur; genç deneme beklenir; "
            + "iki pod aynı anda koşsa da her satır BİR kez yazılır")
    void finalisesEachQueuedDeliveryOnceAcrossPods() throws Exception {
        Instant old = now.minus(Duration.ofMinutes(30));
        Instant young = now.minus(Duration.ofMinutes(5));
        NocDelivery sent = delivery("alert:1:OPEN", "QUEUED_RETRY: 421", old, "[7/24] KRİTİK · a.example.com");
        logRow("[7/24] KRİTİK · a.example.com", "SENT", old.plusSeconds(1));
        NocDelivery failed = delivery("alert:2:OPEN", "QUEUED_RETRY: 421", old, "[7/24] KRİTİK · b.example.com");
        logRow("[7/24] KRİTİK · b.example.com", "FAILED: 550 relay denied", old);
        NocDelivery orphan = delivery("alert:3:OPEN", "QUEUED_RETRY: 421", old, "[7/24] KRİTİK · c.example.com");
        logRow("[7/24] KRİTİK · c.example.com", "QUEUED_RETRY: 421", old);
        NocDelivery noLog = delivery("alert:4:OPEN", "QUEUED_RETRY: 421", old, "[7/24] KRİTİK · d.example.com");
        NocDelivery waiting = delivery("alert:5:OPEN", "QUEUED_RETRY: 421", young, "[7/24] KRİTİK · e.example.com");
        logRow("[7/24] KRİTİK · e.example.com", "QUEUED_RETRY: 421", young);
        NocDelivery quickRetry = delivery("alert:6:OPEN", "QUEUED_RETRY: 421", young, "[7/24] KRİTİK · f.example.com");
        logRow("[7/24] KRİTİK · f.example.com", "SENT", young);
        NocDelivery sending = delivery("alert:7:OPEN", "SENDING", old, "[7/24] KRİTİK · g.example.com");
        NocDelivery alreadySent = delivery("alert:8:OPEN", "SENT", old, "[7/24] KRİTİK · h.example.com");

        NocQueuedRetryReconciler a = new NocQueuedRetryReconciler(deliveries, jdbc);
        NocQueuedRetryReconciler b = new NocQueuedRetryReconciler(deliveries, jdbc);
        CyclicBarrier gate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> podA = () -> { gate.await(10, TimeUnit.SECONDS); return a.reconcile(now, ORPHAN_AGE); };
            Callable<Integer> podB = () -> { gate.await(10, TimeUnit.SECONDS); return b.reconcile(now, ORPHAN_AGE); };
            Future<Integer> fa = pool.submit(podA);
            Future<Integer> fb = pool.submit(podB);
            assertThat(fa.get(30, TimeUnit.SECONDS) + fb.get(30, TimeUnit.SECONDS)).as("her satır bir kez").isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }

        assertThat(status(sent)).isEqualTo("SENT");
        assertThat(status(failed)).isEqualTo("FAILED: 550 relay denied");
        assertThat(status(orphan)).isEqualTo(EmailNotificationService.STATUS_RETRY_ORPHANED);
        assertThat(status(noLog)).isEqualTo(EmailNotificationService.STATUS_RETRY_ORPHANED);
        assertThat(status(waiting)).as("sonuç hâlâ gelebilir").startsWith("QUEUED_RETRY");
        assertThat(status(quickRetry)).isEqualTo("SENT");
        assertThat(status(sending)).isEqualTo("SENDING");
        assertThat(status(alreadySent)).isEqualTo("SENT");
        assertThat(deliveries.findById(orphan.getId()).orElseThrow().getAttempts()).as("deneme sayacı değişmez").isEqualTo(1);

        // NOC kuralları yetim / düşen açılışı artık "gitti" saymaz → mevcut yeniden deneme yolları onu bir kez daha dener;
        // SENT olan bir daha gönderilmez (engeller) ve çözüm postası yalnız ona gider.
        assertThat(NocNotificationService.sent(EmailNotificationService.STATUS_RETRY_ORPHANED)).isFalse();
        assertThat(NocNotificationService.blocking(EmailNotificationService.STATUS_RETRY_ORPHANED)).isFalse();
        assertThat(NocAlertFacts.sent(status(failed))).isFalse();
        assertThat(NocNotificationService.blocking(status(sent))).isTrue();

        assertThat(a.reconcile(now, ORPHAN_AGE)).as("ikinci tur yazmaz").isZero();
    }

    @Test
    @DisplayName("arada yeniden sahiplenilmiş (durum / an değişmiş) satıra dokunulmaz — koşul reclaim ile aynı")
    void concurrentReclaimWins() {
        Instant old = now.minus(Duration.ofMinutes(30));
        NocDelivery d = delivery("alert:9:OPEN", "QUEUED_RETRY: 421", old, "[7/24] KRİTİK · i.example.com");
        assertThat(deliveries.reclaim(d.getId(), "QUEUED_RETRY: 421", ISO.format(old), ISO.format(now))).isEqualTo(1);
        assertThat(deliveries.finishQueuedRetry(d.getId(), "QUEUED_RETRY: 421", ISO.format(old),
                EmailNotificationService.STATUS_RETRY_ORPHANED)).isZero();
        assertThat(status(d)).isEqualTo("SENDING");
    }

    @Test
    @DisplayName("inceleme D-2: yetim sayılmış teslim, günlük satırı sonradan SENT olduysa (geç gelen gerçek sonuç) SENT olur — "
            + "7/24 açılışı ikinci kez gönderilmez; son deneme anı (updated_at) korunur")
    void orphanedDeliveryRepairedWhenLogTurnsSent() {
        Instant old = now.minus(Duration.ofMinutes(30));
        NocDelivery d = delivery("alert:11:OPEN", "QUEUED_RETRY: 421", old, "[7/24] KRİTİK · k.example.com");
        logRow("[7/24] KRİTİK · k.example.com", "QUEUED_RETRY: 421", old);
        NocQueuedRetryReconciler r = new NocQueuedRetryReconciler(deliveries, jdbc);
        assertThat(r.reconcile(now, ORPHAN_AGE)).isEqualTo(1);
        assertThat(status(d)).isEqualTo(EmailNotificationService.STATUS_RETRY_ORPHANED);
        assertThat(deliveries.findById(d.getId()).orElseThrow().getUpdatedAt()).as("son deneme anı korunur").isEqualTo(ISO.format(old));

        jdbc.update("UPDATE notification_logs SET email_status = 'SENT'");   // geç gelen gerçek sonuç (tek satır)
        assertThat(r.reconcile(now, ORPHAN_AGE)).isEqualTo(1);
        assertThat(status(d)).isEqualTo("SENT");
        assertThat(NocNotificationService.blocking(status(d))).isTrue();
        assertThat(r.reconcile(now, ORPHAN_AGE)).isZero();
    }

    @Test
    @DisplayName("uçtan uca: posta günlüğü süpürücüsü önce yetim günlük satırını, aynı turda teslim satırını FAILED yapar")
    void sweeperFinalisesLogThenDelivery() {
        Instant old = now.minus(Duration.ofMinutes(30));
        NocDelivery d = delivery("alert:10:OPEN", "QUEUED_RETRY: 421", old, "[7/24] KRİTİK · j.example.com");
        logRow("[7/24] KRİTİK · j.example.com", "QUEUED_RETRY: 421", old);
        EmailNotificationService email = mock(EmailNotificationService.class);
        when(email.currentRetryChainDuration()).thenReturn(EmailNotificationService.retryChainDuration(90_000L));
        MailRetryOrphanSweeper sweeper = new MailRetryOrphanSweeper(jdbc, email);
        sweeper.setNocReconciler(new NocQueuedRetryReconciler(deliveries, jdbc));
        sweeper.scheduledSweep();
        assertThat(logs.findAll()).singleElement().extracting(NotificationLog::getEmailStatus)
                .isEqualTo(EmailNotificationService.STATUS_RETRY_ORPHANED);
        assertThat(status(d)).isEqualTo(EmailNotificationService.STATUS_RETRY_ORPHANED);
    }
}
