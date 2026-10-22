package com.sitemonitor.service.report;

import com.sitemonitor.service.SchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Aylık envanter raporunun DİNAMİK tetikleyicisi — testi yoktu ve sessiz ölüme çok yatkın:
 * cron ifadesi Ayarlar'dan (kullanıcı eliyle) geliyor. Trigger {@code null} dönerse Spring o görevi
 * BİR DAHA ASLA zamanlamaz — uygulama sağlıklı görünür, log temizdir, rapor aylarca gitmez ve bunu
 * kimse fark etmez. Bu yüzden burada asıl kilitlenen sözleşme: <b>geçersiz ifade rapora mal olmaz</b>.
 *
 * <p>Ayrıca tetiklenen işin kilit ALTINDA koştuğu doğrulanıyor: kilit adı kayarsa çok-pod'da her pod
 * ayrı mail atar (idempotens ikinci savunma hattı, birincisi bu).
 */
@ExtendWith(MockitoExtension.class)
class CertInventoryReportSchedulingTest {

    @Mock CertificateInventoryReportService reportService;
    @Mock SchedulerService schedulerService;

    CertInventoryReportScheduling scheduling;
    ScheduledTaskRegistrar registrar;

    @BeforeEach
    void setUp() {
        scheduling = new CertInventoryReportScheduling(reportService, schedulerService);
        // @Value alanı manuel kurulumda null kalır → gerçek varsayılanı koy (her ayın son Cuma'sı 10:00).
        ReflectionTestUtils.setField(scheduling, "fallbackCron", "0 0 10 * * FRIL");
        registrar = new ScheduledTaskRegistrar();
    }

    /** configureTasks'ın kaydettiği tek trigger görevini döner. */
    private TriggerTask task() {
        scheduling.configureTasks(registrar);
        assertThat(registrar.getTriggerTaskList()).hasSize(1);
        return registrar.getTriggerTaskList().get(0);
    }

    private Instant nextRun(Trigger trigger) {
        return trigger.nextExecution(new SimpleTriggerContext());
    }

    @Test
    @DisplayName("Geçerli cron: sıradaki çalışma zamanı CANLI ayardan hesaplanır")
    void validCron_schedulesNextRun() {
        when(reportService.cron()).thenReturn("0 30 9 1 * *");   // her ayın 1'i 09:30

        Instant next = nextRun(task().getTrigger());

        assertThat(next).isNotNull().isAfter(Instant.now());
    }

    @Test
    @DisplayName("Cron her seferinde YENİDEN okunur — ayar değişince yeniden başlatma gerekmez")
    void cronIsReReadOnEveryTrigger() {
        when(reportService.cron()).thenReturn("0 30 9 1 * *");
        Trigger trigger = task().getTrigger();

        nextRun(trigger);
        nextRun(trigger);

        verify(reportService, org.mockito.Mockito.times(2)).cron();
    }

    @Test
    @DisplayName("GEÇERSİZ cron: null DÖNMEZ — varsayılana düşer (rapor sessizce ölmez)")
    void invalidCron_fallsBackInsteadOfDying() {
        when(reportService.cron()).thenReturn("her ayin son cumasi");   // ifade değil, serbest metin

        Instant next = nextRun(task().getTrigger());

        assertThat(next)
                .as("null = 'bir daha asla zamanlama'; rapor aylarca sessizce gitmezdi")
                .isNotNull()
                .isAfter(Instant.now());
    }

    @Test
    @DisplayName("Varsayılan da geçersizse null döner (tanımlı, loglanan son çare)")
    void invalidCronAndInvalidFallback_returnsNull() {
        when(reportService.cron()).thenReturn("bozuk");
        ReflectionTestUtils.setField(scheduling, "fallbackCron", "bu da bozuk");

        assertThat(nextRun(task().getTrigger())).isNull();
    }

    @Test
    @DisplayName("Tetiklenen iş DAĞITIK KİLİT altında koşar ve zamanlanmış (dry-run olmayan) gönderim yapar")
    void triggeredTask_runsUnderLock() {
        // Yeniden temel (2026-10-22, R2a MP-42): iş artık canlı cron bu dakikada tetikleniyorsa ve küme turu bu pod'a
        // düştüyse koşar — her saniye tetiklenen ifade + tur kazanıldı
        when(reportService.cron()).thenReturn("* * * * * *");
        when(schedulerService.claimCronRound(eq(CertInventoryReportScheduling.ROUND), eq("* * * * * *"), eq("Europe/Istanbul")))
                .thenReturn(true);
        doAnswer(inv -> { inv.getArgument(1, Runnable.class).run(); return null; })
                .when(schedulerService).runWithSchedulerLock(eq(CertInventoryReportScheduling.LOCK), any());

        task().getRunnable().run();

        verify(schedulerService).runWithSchedulerLock(eq("cert-inventory-report"), any());
        verify(reportService).sendMonthlyReport(false);   // true = dry-run: mail GİTMEZ
    }

    @Test
    @DisplayName("Kilit başka pod'daysa rapor GÖNDERİLMEZ (çok-pod'da mükerrer mail yok)")
    void lockHeldElsewhere_doesNotSend() {
        lenient().when(reportService.cron()).thenReturn("0 30 9 1 * *");
        // runWithSchedulerLock stub'lanmadı → runnable hiç çalıştırılmaz (kilit alınamamış davranışı)
        task().getRunnable().run();

        verify(reportService, never()).sendMonthlyReport(anyBooleanValue());
    }

    /** Mockito matcher'ı: {@code sendMonthlyReport(boolean)} tek imzalı, okunurluk için sarmalandı. */
    private static boolean anyBooleanValue() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }

    // ── Çok pod R2a (MP-42, 2026-10-22) ──────────────────────────────────────────────────────────────────────────

    /** Son Cuma 10:00 İstanbul = 07:00 UTC (30 Ekim 2026). */
    private static final Instant LAST_FRIDAY_10 = Instant.parse("2026-10-30T07:00:00Z");

    @Test
    @DisplayName("R2a MP-42: geç kalan ikinci pod aynı planlı tetikte (cron turu başkasında) GÖNDERMEZ — tek posta")
    void latePodSameFire_noSecondMail() {
        when(reportService.cron()).thenReturn("0 0 10 * * FRIL");
        when(schedulerService.claimCronRound(eq(CertInventoryReportScheduling.ROUND), eq("0 0 10 * * FRIL"), eq("Europe/Istanbul")))
                .thenReturn(true).thenReturn(false);
        scheduling.scheduledTick(LAST_FRIDAY_10);
        scheduling.scheduledTick(LAST_FRIDAY_10.plusSeconds(20));   // dolu havuzda geç kalan tetik, kilit bırakıldıktan sonra
        verify(reportService, org.mockito.Mockito.times(1)).sendMonthlyReport(false);
    }

    @Test
    @DisplayName("R2a MP-42: zamanlama tetik planlandıktan SONRA değiştiyse eski planlı tetik göndermez (canlı cron bu dakikada tetiklenmiyor); geçersiz canlı ifade → varsayılan")
    void oldFireAfterCronChange_notDue() {
        when(reportService.cron()).thenReturn("0 0 10 1 * *");      // yeni: ayın 1'i 10:00
        scheduling.scheduledTick(LAST_FRIDAY_10);                   // eski planlı tetik (son Cuma) geldi
        verify(reportService, never()).sendMonthlyReport(anyBooleanValue());
        verify(schedulerService, never()).claimCronRound(any(), any(), any());
        assertThat(LiveCron.firesNow("bozuk", "0 0 10 * * FRIL", java.time.ZoneId.of("Europe/Istanbul"), LAST_FRIDAY_10)).isTrue();
        assertThat(LiveCron.firesNow("0 0 10 * * FRIL", null, java.time.ZoneId.of("Europe/Istanbul"), LAST_FRIDAY_10.plusSeconds(60))).isTrue();
        assertThat(LiveCron.firesNow("0 0 10 * * FRIL", null, java.time.ZoneId.of("Europe/Istanbul"), LAST_FRIDAY_10.plusSeconds(90))).isFalse();
        assertThat(LiveCron.firesNow("0 0 10 * * FRIL", null, java.time.ZoneId.of("Europe/Istanbul"), LAST_FRIDAY_10.minusSeconds(1))).isTrue();
        assertThat(LiveCron.firesNow("bozuk", "bu da bozuk", java.time.ZoneId.of("Europe/Istanbul"), LAST_FRIDAY_10)).isTrue();
    }
}
