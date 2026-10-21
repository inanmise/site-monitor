package com.sitemonitor.service.outage;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.NotificationLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Sertifika taramasının toplu ağ kesintisi durumu İKİ POD'da (2026-10-21, çok pod — MP-01): durum veritabanı satırıdır;
 * açılış postasını yalnız satırı EKLEYEN pod, çözüm postasını yalnız satırı KAPATAN pod atar; başarısız posta herhangi bir
 * pod'un sonraki turunda yeniden denenir; küçük tur kesintiyi kapatamaz; pano / Sistem Sağlığı her pod'da aynı.
 * İki pod = aynı H2 veritabanını ({@link MultiPodTestSupport}) paylaşan iki {@link CertNetworkOutageService} örneği (her
 * birinin kendi ezberi), ortak saat.
 */
class CertNetworkOutageMultiPodTest {

    private static final String ADMIN = "admin@example.com";

    private MultiPodTestSupport mp;
    private JdbcTemplate jdbc;
    private EmailNotificationService email;
    private AppSettingsService settings;
    private CertNetworkOutageService a;
    private CertNetworkOutageService b;
    /** Sıradaki gönderim sonuçları (boşsa SENT). */
    private final ConcurrentLinkedQueue<String> alertResults = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> resolvedResults = new ConcurrentLinkedQueue<>();
    private final AtomicInteger alertMails = new AtomicInteger();
    private final AtomicInteger resolvedMails = new AtomicInteger();
    /** notification_logs izi (inceleme S-4 / F8); {@code logFails} = günlük yazımı düşer. */
    private final List<NotificationLog> logs = new CopyOnWriteArrayList<>();
    private final AtomicBoolean logFails = new AtomicBoolean();
    private NotificationLogRepository logRepo;

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        jdbc = mp.jdbc();
        NetworkOutageTestSchema.create(jdbc);
        email = mock(EmailNotificationService.class);
        settings = NetworkOutageTestSchema.defaultSettings();
        when(email.sendSystemAdminNetworkAlert(anyString(), any(), anyInt(), anyInt(), anyDouble(), anyDouble())).thenAnswer(i -> {
            alertMails.incrementAndGet();
            String r = alertResults.poll();
            return r == null ? "SENT" : r;
        });
        when(email.sendSystemAdminNetworkResolved(anyString(), any(), any(), anyLong(), anyInt(), anyInt(), anyDouble())).thenAnswer(i -> {
            resolvedMails.incrementAndGet();
            String r = resolvedResults.poll();
            return r == null ? "SENT" : r;
        });
        logRepo = mock(NotificationLogRepository.class);
        when(logRepo.save(any())).thenAnswer(i -> {
            if (logFails.get()) throw new org.springframework.dao.DataAccessResourceFailureException("günlük yazılamadı");
            logs.add(i.getArgument(0));
            return i.getArgument(0);
        });
        a = pod();
        b = pod();
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    private CertNetworkOutageService pod() {
        CertNetworkOutageService s = new CertNetworkOutageService(jdbc, email, settings).withSystemAdminEmailDefault(ADMIN)
                .withNotificationLogs(logRepo);
        s.clock = mp.clockSupplier();
        return s;
    }

    /** Toplu kesinti şüpheli tur: 8/10 ağ hatası, min-errors 3. */
    private boolean suspectedRun(CertNetworkOutageService pod) {
        return pod.onRun(8, 10, 0.8, true, 3, 0.5);
    }

    /** Sağlıklı büyük tur. */
    private boolean healthyRun(CertNetworkOutageService pod) {
        return pod.onRun(0, 40, 0.0, false, 3, 0.5);
    }

    private int ongoing() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM network_outage_events WHERE status = 'ONGOING'", Integer.class);
    }

    private Map<String, Object> onlyRow() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM network_outage_events ORDER BY id");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    @DisplayName("A kesintiyi tespit eder → 1 satır + 1 açılış postası; B'nin şüpheli turu satır da posta da üretmez, yalnız sayıları tazeler")
    void detectOnA_continueOnB_singleRowSingleMail() {
        assertThat(suspectedRun(a)).as("şüpheli tur alarmları bastırır").isFalse();
        assertThat(ongoing()).isEqualTo(1);
        assertThat(alertMails.get()).isEqualTo(1);
        assertThat(onlyRow()).containsEntry("alert_mail_status", "SENT").containsEntry("source", "CERT");

        mp.advance(3_600_000);   // saatlik tur B'de
        assertThat(b.onRun(9, 12, 0.75, true, 3, 0.5)).isFalse();

        assertThat(ongoing()).as("ikinci ONGOING satırı yok").isEqualTo(1);
        assertThat(alertMails.get()).as("ikinci 'ağ kesintisi' postası yok").isEqualTo(1);
        assertThat(onlyRow()).containsEntry("network_errors", 9).containsEntry("total_checks", 12);
    }

    @Test
    @DisplayName("Kesintiyi görmemiş B'nin sağlıklı turu kapatır → TEK çözüm postası, 0 açık satır; A'nın sonraki sağlıklı turu posta atmaz")
    void resolveOnOtherPod_singleResolvedMail() {
        suspectedRun(a);
        mp.advance(3_600_000);

        assertThat(healthyRun(b)).as("kesinti kapandı → alarmlar işlenir").isTrue();
        assertThat(ongoing()).isZero();
        assertThat(resolvedMails.get()).isEqualTo(1);
        assertThat(onlyRow()).containsEntry("status", "RESOLVED").containsEntry("resolved_mail_status", "SENT")
                .containsEntry("duration_ms", 3_600_000L);

        mp.advance(3_600_000);
        assertThat(healthyRun(a)).isTrue();
        assertThat(resolvedMails.get()).as("ikinci 'çözüldü' postası yok").isEqualTo(1);
    }

    @Test
    @DisplayName("İki pod aynı anda kapatır → koşullu UPDATE tek kazanır, TEK çözüm postası")
    void concurrentResolve_singleResolvedMail() {
        suspectedRun(a);
        mp.advance(60_000);

        List<Boolean> processed = mp.race(() -> healthyRun(a), () -> healthyRun(b));

        assertThat(processed).containsExactly(true, true);
        assertThat(ongoing()).isZero();
        assertThat(resolvedMails.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Pano / Sistem Sağlığı her pod'da aynı: yazan pod hemen, diğeri ezber (≤ 10 sn) dolunca")
    void bannerIsEqualOnBothPods() {
        assertThat(a.isActive()).isFalse();
        assertThat(b.isActive()).isFalse();   // B ezberledi: kesinti yok

        suspectedRun(a);
        assertThat(a.isActive()).as("yazan pod ezberini düşürür").isTrue();
        mp.advance(CertNetworkOutageService.MEMO_MS + 1);
        assertThat(b.isActive()).as("diğer pod ezber dolunca veritabanından okur").isTrue();
        assertThat(b.snapshot().detectedAt()).isEqualTo(a.snapshot().detectedAt());
        assertThat(b.snapshot().lastNetworkErrors()).isEqualTo(8);

        healthyRun(b);
        mp.advance(CertNetworkOutageService.MEMO_MS + 1);
        assertThat(a.isActive()).isFalse();
        assertThat(b.isActive()).isFalse();
        assertThat(a.snapshot().resolvedAt()).isNotNull().isEqualTo(b.snapshot().resolvedAt());
    }

    @Test
    @DisplayName("SMTP kesintiden etkilendi: A'nın açılış postası düşer → B'nin sonraki turu TEK kez yeniden dener ve gönderir")
    void failedAlertMail_retriedOnceByOtherPod() {
        alertResults.add("FAILED: Connection refused");
        suspectedRun(a);
        assertThat(alertMails.get()).isEqualTo(1);
        assertThat(onlyRow()).containsEntry("alert_mail_status", "RETRY").containsEntry("alert_mail_attempts", 1);
        mp.advance(CertNetworkOutageService.MEMO_MS + 1);
        assertThat(b.snapshot().pendingAlertEmail()).as("Sistem Sağlığı 'bekleyen posta' der").isTrue();

        mp.advance(300_000);
        suspectedRun(b);   // bayat tur B'de

        assertThat(alertMails.get()).as("B bir kez yeniden denedi").isEqualTo(2);
        assertThat(onlyRow()).containsEntry("alert_mail_status", "SENT");
        suspectedRun(a);
        assertThat(alertMails.get()).as("gitmiş posta yeniden gitmez").isEqualTo(2);
    }

    @Test
    @DisplayName("Açılış postası gitmeden kesinti kapanır → açılış SUPERSEDED (yeniden denenmez), çözüm postası zaman çizelgesini taşır")
    void pendingAlertSupersededByResolution() {
        alertResults.add("FAILED: smtp");
        suspectedRun(a);
        mp.advance(600_000);
        healthyRun(b);

        assertThat(onlyRow()).containsEntry("alert_mail_status", CertNetworkOutageService.MAIL_SUPERSEDED)
                .containsEntry("resolved_mail_status", "SENT");
        assertThat(alertMails.get()).isEqualTo(1);
        assertThat(resolvedMails.get()).isEqualTo(1);
        healthyRun(a);
        assertThat(alertMails.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Küçük tur (min-errors'tan az kontrol) kesintiyi KAPATAMAZ ve açık kesintide alarm işlemez; büyük tur kapatır")
    void smallRunCannotResolve() {
        suspectedRun(a);

        assertThat(b.onRun(0, 2, 0.0, false, 3, 0.5)).as("açık kesinti sürüyor → alarmlar bastırılır").isFalse();
        assertThat(ongoing()).isEqualTo(1);
        assertThat(resolvedMails.get()).isZero();

        assertThat(b.onRun(0, 3, 0.0, false, 3, 0.5)).isTrue();
        assertThat(ongoing()).isZero();
        assertThat(resolvedMails.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Kesinti yokken küçük sağlıklı tur alarmları her zamanki gibi işler")
    void smallHealthyRunWithoutOutage_processesAlarms() {
        assertThat(a.onRun(0, 1, 0.0, false, 3, 0.5)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM network_outage_events", Integer.class)).isZero();
    }

    @Test
    @DisplayName("Eski sürümün açtığı satır (posta sütunları NULL) gönderilmiş sayılır: yeniden açılış postası yok; kapanınca çözüm postası gider")
    void legacyOngoingRow_notRemailed_butResolvedMailed() {
        NetworkOutageTestSchema.insertLegacyOngoing(jdbc, null, "2026-10-21T05:00:00");

        assertThat(suspectedRun(a)).isFalse();
        assertThat(ongoing()).isEqualTo(1);
        assertThat(alertMails.get()).isZero();

        healthyRun(b);
        assertThat(resolvedMails.get()).isEqualTo(1);
        assertThat(onlyRow()).containsEntry("status", "RESOLVED");
    }

    @Test
    @DisplayName("Sahiplenen pod gönderirken öldü (SENDING kaldı, inceleme F8): 10 dk dolmadan dokunulmaz; dolunca UNKNOWN + iz — YENİDEN GÖNDERİLMEZ (çift posta yok)")
    void staleSendingBecomesUnknown_notResent() {
        suspectedRun(a);
        long id = (Long) onlyRow().get("id");
        jdbc.update("UPDATE network_outage_events SET alert_mail_status = 'SENDING', alert_mail_claimed_ms = ? WHERE id = ?",
                mp.now(), id);
        int before = alertMails.get();

        mp.advance(CertNetworkOutageService.CLAIM_STALE_MS - 1_000);
        suspectedRun(b);
        assertThat(alertMails.get()).as("taze SENDING başka pod'da sürüyor sayılır").isEqualTo(before);
        assertThat(onlyRow()).containsEntry("alert_mail_status", "SENDING");

        mp.advance(2_000);
        suspectedRun(b);
        suspectedRun(a);
        assertThat(alertMails.get()).as("belki gitmiş posta yeniden gönderilmez").isEqualTo(before);
        assertThat(onlyRow()).containsEntry("alert_mail_status", CertNetworkOutageService.MAIL_UNKNOWN);
        assertThat(traces(CertNetworkOutageService.ALERT_SUBJECT, "sonuç bilinmiyor")).as("tek iz").isEqualTo(1);
    }

    @Test
    @DisplayName("Kesinti kapanırken açılış postası bayat SENDING'de (inceleme S-3): UNKNOWN olur (ara hâlde kalmaz) + iz; taze SENDING'e dokunulmaz")
    void resolveMapsStaleSendingToUnknown() {
        suspectedRun(a);
        long id = (Long) onlyRow().get("id");
        jdbc.update("UPDATE network_outage_events SET alert_mail_status = 'SENDING', alert_mail_claimed_ms = ? WHERE id = ?",
                mp.now(), id);
        mp.advance(CertNetworkOutageService.CLAIM_STALE_MS + 1_000);

        healthyRun(b);

        assertThat(onlyRow()).containsEntry("status", "RESOLVED")
                .containsEntry("alert_mail_status", CertNetworkOutageService.MAIL_UNKNOWN)
                .containsEntry("resolved_mail_status", "SENT");
        assertThat(traces(CertNetworkOutageService.ALERT_SUBJECT, "sonuç bilinmiyor")).isEqualTo(1);

        // taze SENDING (gönderen pod hâlâ çalışıyor) — kapanış ona dokunmaz, gönderen pod sonucunu kendisi yazar
        jdbc.update("DELETE FROM network_outage_events");
        suspectedRun(a);
        long id2 = (Long) onlyRow().get("id");
        jdbc.update("UPDATE network_outage_events SET alert_mail_status = 'SENDING', alert_mail_claimed_ms = ? WHERE id = ?",
                mp.now(), id2);
        healthyRun(b);
        assertThat(onlyRow()).containsEntry("alert_mail_status", "SENDING");
    }

    @Test
    @DisplayName("Deneme tavanı: posta her turda düşerse 24. denemede FAILED olur ve bir daha denenmez")
    void attemptsCapped() {
        for (int i = 0; i < 40; i++) alertResults.add("FAILED: smtp kapalı");
        suspectedRun(a);
        for (int i = 0; i < 30; i++) {
            mp.advance(300_000);
            suspectedRun(i % 2 == 0 ? b : a);
        }
        assertThat(alertMails.get()).isEqualTo(CertNetworkOutageService.MAX_ATTEMPTS);
        assertThat(onlyRow()).containsEntry("alert_mail_status", CertNetworkOutageService.MAIL_FAILED)
                .containsEntry("alert_mail_attempts", CertNetworkOutageService.MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("421 kuyruğu (QUEUED_RETRY…) günlük satırıyla birlikte kesin sonuçtur — e-posta servisi o satıra geri yazar, sonraki tur ikinci posta atmaz")
    void queuedRetryIsFinal() {
        alertResults.add("QUEUED_RETRY: 421 too many connections");
        suspectedRun(a);
        mp.advance(300_000);
        suspectedRun(b);
        assertThat(alertMails.get()).isEqualTo(1);
        assertThat(onlyRow().get("alert_mail_status").toString()).startsWith("QUEUED_RETRY");
        assertThat(logs).filteredOn(l -> CertNetworkOutageService.ALERT_SUBJECT.equals(l.getSubject()))
                .singleElement()
                .satisfies(l -> {
                    assertThat(l.getEmailStatus()).startsWith("QUEUED_RETRY");
                    assertThat(l.getRecipientEmail()).as("421 geri yazımı konu + alıcıyla eşleşir").isEqualTo(ADMIN);
                    assertThat(l.getTrigger()).isEqualTo(CertNetworkOutageService.TRIGGER);
                    assertThat(l.getAlertEventId()).isZero();
                });
    }

    @Test
    @DisplayName("421 kuyruğu ama günlük satırı YAZILAMADI (inceleme F8): kuyruğun sonucu hiçbir yerde görünmezdi → kesin sayılmaz, RETRY; sonraki tur yeniden dener")
    void queuedRetryWithoutLogRow_isRetried() {
        alertResults.add("QUEUED_RETRY: 421 too many connections");
        logFails.set(true);
        suspectedRun(a);
        assertThat(onlyRow()).containsEntry("alert_mail_status", "RETRY").containsEntry("alert_mail_attempts", 1);

        logFails.set(false);
        mp.advance(300_000);
        suspectedRun(b);
        assertThat(alertMails.get()).isEqualTo(2);
        assertThat(onlyRow()).containsEntry("alert_mail_status", "SENT");
    }

    @Test
    @DisplayName("Her yönetici postası kararı bir notification_logs satırı (inceleme S-4): açılış SENT, çözüm SENT, gitmeden kapanan açılış SKIPPED — tetik NETWORK_OUTAGE, alertEventId 0")
    void everyAdminMailDecisionIsLogged() {
        suspectedRun(a);
        mp.advance(600_000);
        healthyRun(b);
        assertThat(traces(CertNetworkOutageService.ALERT_SUBJECT, "SENT")).isEqualTo(1);
        assertThat(traces(CertNetworkOutageService.RESOLVED_SUBJECT, "SENT")).isEqualTo(1);

        alertResults.add("FAILED: smtp");
        mp.advance(600_000);
        suspectedRun(a);
        mp.advance(600_000);
        healthyRun(b);
        assertThat(traces(CertNetworkOutageService.ALERT_SUBJECT, "FAILED: smtp")).isEqualTo(1);
        assertThat(traces(CertNetworkOutageService.ALERT_SUBJECT, "SKIPPED: açılış postası gitmeden")).isEqualTo(1);
        assertThat(logs).allSatisfy(l -> {
            assertThat(l.getTrigger()).isEqualTo(CertNetworkOutageService.TRIGGER);
            assertThat(l.getAlertEventId()).isZero();
            assertThat(l.getRecipientRole()).isEqualTo("SYSTEM_ADMIN");
        });
    }

    @Test
    @DisplayName("Adres yok / posta kanalı kapalı da iz bırakır (SKIPPED ≠ FAILED)")
    void skippedDecisionsAreLogged() {
        CertNetworkOutageService noAddr = new CertNetworkOutageService(jdbc, email, settings).withNotificationLogs(logRepo);
        noAddr.clock = mp.clockSupplier();
        noAddr.onRun(8, 10, 0.8, true, 3, 0.5);
        assertThat(traces(CertNetworkOutageService.ALERT_SUBJECT, CertNetworkOutageService.MAIL_NO_ADDRESS)).isEqualTo(1);
        assertThat(logs).singleElement().satisfies(l -> assertThat(l.getRecipientEmail()).isEqualTo("-"));
    }

    @Test
    @DisplayName("Küçük envanter (min-errors'tan küçük, inceleme S-2): TÜM aktif ağ envanterini denetleyen şüphesiz tur kesintiyi kapatır (+ iz); oran eşikteyse kapatmaz")
    void fullSweepOfSmallInventoryResolves() {
        jdbc.execute("CREATE TABLE certificate_inventory (id BIGINT PRIMARY KEY, active BOOLEAN, cert_source VARCHAR(16))");
        jdbc.update("INSERT INTO certificate_inventory VALUES (1, true, NULL), (2, true, 'NETWORK'), (3, true, 'MANUAL'), (4, false, NULL)");
        suspectedRun(a);

        assertThat(b.onRun(2, 2, 1.0, false, 3, 0.5)).as("iki alan adının ikisi de düşüyor: kesinti sürer").isFalse();
        assertThat(ongoing()).isEqualTo(1);

        assertThat(b.onRun(0, 2, 0.0, false, 3, 0.5)).as("tüm ağ envanteri (2) denetlendi, hata yok").isTrue();
        assertThat(ongoing()).isZero();
        assertThat(resolvedMails.get()).isEqualTo(1);
        assertThat(traces(CertNetworkOutageService.RESOLVED_SUBJECT, "küçük turla kapatıldı")).isEqualTo(1);

        suspectedRun(a);
        assertThat(b.onRun(0, 1, 0.0, false, 3, 0.5)).as("envanterin yarısı — kapatamaz").isFalse();
        assertThat(ongoing()).isEqualTo(1);
    }

    @Test
    @DisplayName("Güvenlik süresi (inceleme S-2): 24 saatten eski açık kesintiyi küçük ama şüphesiz tur kapatır — WARN + iz, çözüm postası gider")
    void maxAgeResolvesStuckOutage() {
        suspectedRun(a);
        mp.advance(CertNetworkOutageService.MAX_OUTAGE_AGE_MS - 60_000);
        assertThat(b.onRun(0, 1, 0.0, false, 3, 0.5)).isFalse();
        assertThat(ongoing()).isEqualTo(1);

        mp.advance(120_000);
        assertThat(b.onRun(1, 2, 1.0 / 2 - 0.01, false, 3, 0.5)).isTrue();
        assertThat(ongoing()).isZero();
        assertThat(resolvedMails.get()).isEqualTo(1);
        assertThat(traces(CertNetworkOutageService.RESOLVED_SUBJECT, "güvenlik süresiyle")).isEqualTo(1);
    }

    @Test
    @DisplayName("Yönetici adresi boş: posta yok, kesin sonuç (her turda yeniden denenmez)")
    void blankAdminAddress_noMailNoRetry() {
        CertNetworkOutageService noAddr = new CertNetworkOutageService(jdbc, email, settings);
        noAddr.clock = mp.clockSupplier();
        noAddr.onRun(8, 10, 0.8, true, 3, 0.5);
        noAddr.onRun(8, 10, 0.8, true, 3, 0.5);
        assertThat(alertMails.get()).isZero();
        assertThat(onlyRow()).containsEntry("alert_mail_status", CertNetworkOutageService.MAIL_NO_ADDRESS);
    }

    @Test
    @DisplayName("Çok pod öncesinden kalan iki açık CERT satırı: ikisi de kapanır, TEK çözüm postası (en yeni satır için)")
    void duplicateLegacyRows_singleResolvedMail() {
        NetworkOutageTestSchema.insertLegacyOngoing(jdbc, null, "2026-10-21T04:00:00");
        NetworkOutageTestSchema.insertLegacyOngoing(jdbc, "CERT", "2026-10-21T05:00:00");
        NetworkOutageTestSchema.insertLegacyOngoing(jdbc, "ACCESSIBILITY", "2026-10-21T05:00:00");

        healthyRun(a);

        assertThat(resolvedMails.get()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT source FROM network_outage_events WHERE status = 'ONGOING'", String.class))
                .as("izleme sweep'inin olayına dokunulmaz").containsExactly("ACCESSIBILITY");
        assertThat(jdbc.queryForList("SELECT resolved_mail_status FROM network_outage_events WHERE source IS NULL", String.class))
                .containsExactly(CertNetworkOutageService.MAIL_SKIPPED_DUPLICATE);
    }

    @Test
    @DisplayName("Veritabanı hatası: tur kararı yalnız turun sonucundan (şüpheli → bastır, sağlıklı → işle), posta yok, istisna sızmaz")
    void databaseFailure_safeSide() {
        JdbcTemplate broken = mock(JdbcTemplate.class);
        when(broken.update(any(org.springframework.jdbc.core.PreparedStatementCreator.class), any(org.springframework.jdbc.support.KeyHolder.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("bağlantı yok"));
        when(broken.queryForList(anyString())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("bağlantı yok"));
        CertNetworkOutageService s = new CertNetworkOutageService(broken, email, settings).withSystemAdminEmailDefault(ADMIN);

        assertThat(s.onRun(8, 10, 0.8, true, 3, 0.5)).isFalse();
        assertThat(s.onRun(0, 40, 0.0, false, 3, 0.5)).isTrue();
        assertThat(s.snapshot()).isEqualTo(CertNetworkOutageService.NONE);
        assertThat(alertMails.get() + resolvedMails.get()).isZero();
    }

    private long traces(String subject, String statusPart) {
        return logs.stream().filter(l -> subject.equals(l.getSubject()))
                .filter(l -> l.getEmailStatus() != null && l.getEmailStatus().contains(statusPart)).count();
    }
}
