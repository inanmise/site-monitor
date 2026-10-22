package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.DomainExpiryReminder;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.repository.DomainExpiryReminderRepository;
import org.junit.jupiter.api.AfterEach;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MP-45 (2026-10-23, çok pod R2a · D2) — alan adı bitiş hatırlatması gönderimden ÖNCE sahiplenilir, GERÇEK H2 üstünde:
 * iki tur (16:00 kritik turu + saatlik tur ya da devirde iki lider) aynı eşiği aynı anda değerlendirir → TEK e-posta; tekil
 * indeks henüz yokken aynı pod'daki iki tur yine TEK e-posta (pod içi değerlendirme kilidi); gönderirken ölen pod'un satırı
 * 1 saat sonra yarıda kaldı (yeniden gönderilmez); teslim başlamadan düşen hata sahiplenmeyi geri verir.
 *
 * <p>Üretimde tekillik PostgreSQL'in kısmi indeksidir ({@code ux_der_mon_exp_thr}); H2'de kısmi indeks olmadığından burada aynı
 * anahtar üstünde düz tekil indeks kurulur (sözleşme aynı: ikinci ekleme ihlal verir).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DomainExpiryReminderRaceTest {

    private static final String EXPIRY = "2026-10-30T00:00:00Z";

    @Autowired DataSource dataSource;
    @Autowired DomainExpiryReminderRepository repo;

    JdbcTemplate jdbc;
    EscalationService escalation;
    EmailNotificationService email;
    UserPushService push;
    ActivityLogService activity;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        repo.deleteAll();
        jdbc.execute("DROP INDEX IF EXISTS ux_der_test");
        escalation = mock(EscalationService.class);
        email = mock(EmailNotificationService.class);
        push = mock(UserPushService.class);
        activity = mock(ActivityLogService.class);
        when(escalation.teamEmailsForMonitor(anyLong(), any())).thenReturn(List.of("team@example.com"));
        when(email.buildDomainExpiryReminderHtml(any(), any(), anyInt(), any(), anyInt(), any(), any(), any())).thenReturn("<html/>");
        when(email.sendHtml(any(), any(), any(), any(), any())).thenReturn("SENT");
        when(push.enqueueTeamNoticeLocalized(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Map.of("queued", 1));
    }

    /** NOT_SUPPORTED satırları işler — önbellekli @DataJpaTest bağlamını paylaşan sonraki testler temiz başlasın. */
    @AfterEach
    void cleanUpCommittedRows() {
        repo.deleteAll();
    }

    private void uniqueIndex() {
        jdbc.execute("CREATE UNIQUE INDEX ux_der_test ON domain_expiry_reminders(monitor_id, expiry_date, threshold_days)");
    }

    private DomainExpiryReminderService pod() {
        return new DomainExpiryReminderService(repo, escalation, email, push, activity);
    }

    private static DomainMonitor monitor() {
        DomainMonitor m = new DomainMonitor();
        m.setId(7L); m.setDomain("a.example.com"); m.setName("A"); m.setTeamId(1L); m.setActive(true);
        m.setThresholdsCsv("30,7"); m.setWarningDays(30); m.setCriticalDays(7);
        return m;
    }

    private static Map<String, Object> result(int days) {
        Map<String, Object> r = new HashMap<>();
        r.put("days_remaining", days); r.put("expiry_date", EXPIRY); r.put("registrar", "R");
        return r;
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("SELECT threshold_days, status, recipients FROM domain_expiry_reminders ORDER BY threshold_days");
    }

    @Test
    @DisplayName("İki pod aynı eşiği aynı anda değerlendirir (ikisi de 'gönderilmemiş' görür) → TEK e-posta, TEK push, tek satır")
    void twoPodsRaceSameThreshold_oneMail() {
        uniqueIndex();
        DomainExpiryReminderService a = pod(), b = pod();
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        Runnable await = () -> {
            try { bothChecked.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
        };
        a.beforeClaim = await;
        b.beforeClaim = await;
        DomainMonitor m = monitor();

        try (MultiPodTestSupport race = MultiPodTestSupport.create()) {
            List<DomainExpiryReminder> r = race.race(() -> a.evaluate(m, result(5)), () -> b.evaluate(m, result(5)));
            assertThat(r.stream().filter(java.util.Objects::nonNull).count()).as("yalnız bir tur gönderdi").isEqualTo(1);
        }
        verify(email, times(1)).sendHtml(any(), any(), any(), any(), any());
        verify(push, times(1)).enqueueTeamNoticeLocalized(any(), any(), any(), any(), any(), any(), any(), any());
        List<Map<String, Object>> rows = rows();
        assertThat(rows).hasSize(2);   // 7 → SENT, 30 → COVERED
        assertThat(rows.get(0).get("status")).isEqualTo("SENT");
        assertThat(rows.get(0).get("recipients")).isEqualTo("team@example.com");
        assertThat(rows.get(1).get("status")).isEqualTo("COVERED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM domain_expiry_reminders WHERE claim_token IS NULL", Integer.class))
                .as("yeni kodun her satırı belirteç taşır (tekil indeks yalnız onları kapsar)").isZero();
    }

    @Test
    @DisplayName("Tekil indeks yokken (açılıştan sonraki ertelenmiş kurulum / H2) aynı pod'un iki turu yine TEK e-posta")
    void samePodTwoSweeps_withoutIndex_oneMail() throws Exception {
        DomainExpiryReminderService pod = pod();
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        pod.beforeClaim = () -> {
            if (inside.getCount() == 0) return;
            inside.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        DomainMonitor m = monitor();
        Thread first = new Thread(() -> pod.evaluate(m, result(5)), "critical-sweep");
        first.start();
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(pod.evaluate(m, result(5))).as("saatlik tur aynı izlemeyi bu pod'da değerlendirilirken atlar").isNull();
        release.countDown();
        first.join(10_000);
        verify(email, times(1)).sendHtml(any(), any(), any(), any(), any());
        assertThat(rows()).extracting(r -> r.get("status")).containsExactly("SENT", "COVERED");
    }

    @Test
    @DisplayName("Gönderirken ölen pod: SENDING satırı 1 saat sonra INTERRUPTED olur, YENİDEN gönderilmez, etkinlik satırı tek")
    void staleSending_becomesInterrupted_notResent() {
        uniqueIndex();
        DomainExpiryReminderService pod = pod();
        long now = Instant.parse("2026-10-23T10:00:00Z").toEpochMilli();
        pod.clock = () -> now;
        jdbc.update("INSERT INTO domain_expiry_reminders(monitor_id, expiry_date, threshold_days, days_remaining, status, sent_at) "
                + "VALUES (7, ?, 7, 5, 'SENDING', '2026-10-23T08:30:00')", EXPIRY);
        jdbc.update("INSERT INTO domain_expiry_reminders(monitor_id, expiry_date, threshold_days, days_remaining, status, sent_at) "
                + "VALUES (7, ?, 30, 5, 'COVERED', '2026-10-23T08:30:00')", EXPIRY);

        assertThat(pod.evaluate(monitor(), result(5))).isNull();
        assertThat(pod.evaluate(monitor(), result(5))).isNull();

        verify(email, never()).sendHtml(any(), any(), any(), any(), any());
        assertThat(rows()).extracting(r -> r.get("status")).containsExactly("INTERRUPTED", "COVERED");
        verify(activity, times(1)).recordLifecycle(eq(ActivityLogService.DOMAIN), eq(7L), eq("A"), eq("a.example.com"), eq(1L),
                eq("EXPIRY_REMINDER"), eq("scheduler"), contains("yarıda kaldı"));
    }

    @Test
    @DisplayName("Taze SENDING satırı (başka pod şu an gönderiyor, gevşek eşikleri henüz yazmadı) dokunulmaz; bu tur ne o eşiği ne daha gevşeğini gönderir")
    void freshSending_leftAlone() {
        DomainExpiryReminderService pod = pod();
        pod.clock = () -> Instant.parse("2026-10-23T09:00:00Z").toEpochMilli();
        jdbc.update("INSERT INTO domain_expiry_reminders(monitor_id, expiry_date, threshold_days, days_remaining, status, sent_at) "
                + "VALUES (7, ?, 7, 5, 'SENDING', '2026-10-23T08:30:00')", EXPIRY);
        assertThat(pod.evaluate(monitor(), result(5))).as("daha gevşek 30 gün eşiği de gönderilmez").isNull();
        verify(email, never()).sendHtml(any(), any(), any(), any(), any());
        assertThat(rows()).extracting(r -> r.get("status")).containsExactly("SENDING");
    }

    @Test
    @DisplayName("Teslim başlamadan düşen hata (gövde kurulamadı) sahiplenmeyi geri verir; sonraki tur gönderir")
    void failureBeforeHandover_releasesClaim() {
        uniqueIndex();
        DomainExpiryReminderService pod = pod();
        when(email.buildDomainExpiryReminderHtml(any(), any(), anyInt(), any(), anyInt(), any(), any(), any()))
                .thenThrow(new IllegalStateException("şablon"))
                .thenReturn("<html/>");

        assertThat(pod.evaluate(monitor(), result(5))).isNull();
        verify(email, never()).sendHtml(any(), any(), any(), any(), any());
        assertThat(rows()).extracting(r -> r.get("status")).containsExactly("COVERED");   // sıkı eşik geri verildi

        DomainExpiryReminder r = pod.evaluate(monitor(), result(5));
        assertThat(r).isNotNull();
        assertThat(r.getThresholdDays()).isEqualTo(7);
        verify(email, times(1)).sendHtml(any(), any(), any(), any(), any());
        assertThat(rows()).extracting(x -> x.get("status")).containsExactly("SENT", "COVERED");
    }

    @Test
    @DisplayName("Push teslimden sonra hata verirse sahiplenme geri VERİLMEZ (posta gitti) — satır SENT, ikinci tur göndermez")
    void failureAfterHandover_keepsClaim() {
        uniqueIndex();
        DomainExpiryReminderService pod = pod();
        when(push.enqueueTeamNoticeLocalized(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("push"));
        assertThat(pod.evaluate(monitor(), result(5))).isNotNull();
        assertThat(pod.evaluate(monitor(), result(4))).isNull();
        verify(email, times(1)).sendHtml(any(), any(), any(), any(), any());
        assertThat(rows()).extracting(x -> x.get("status")).containsExactly("SENT", "COVERED");
    }
}
