package com.sitemonitor.service.techerror;

import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.TechErrorApprovalTokenRepository;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReminderRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.schedule.ClusterScheduleService;
import com.sitemonitor.service.schedule.SideJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R3-1 3e (2026-10-23): teknik hata hatırlatmasının 09:15 tetiği yan iş yerleşiminden geçer — lider başka canlı pod varken
 * erteler; ertelenen denemenin ön denetimi bugünün tur kaydı ({@code round:tech-error-reminder}): başka pod aldıysa gövde
 * koşmaz. Zamanlayıcı üstlenmezse (tek pod, sahte) gövde bugünkü gibi burada koşar.
 */
class TechErrorReminderSideJobTest {

    private static final Instant T = Instant.parse("2026-10-23T06:15:00Z");   // 09:15 İstanbul

    final TechErrorReportService service = mock(TechErrorReportService.class);
    final SchedulerService scheduler = mock(SchedulerService.class);

    TechErrorReminderService job() {
        TechErrorReminderService j = new TechErrorReminderService(service, mock(TechErrorReminderRepository.class),
                mock(TechErrorReportRepository.class), mock(TechErrorChannelRepository.class),
                mock(TechErrorApprovalService.class), mock(TechErrorApprovalTokenRepository.class), mock(TeamRepository.class),
                mock(EmailNotificationService.class), mock(NotificationLogRepository.class), mock(AppSettingsService.class),
                mock(TechErrorVisibilityService.class));
        ReflectionTestUtils.setField(j, "scheduler", scheduler);
        when(service.now()).thenReturn(T);
        return j;
    }

    @Test
    @DisplayName("Zamanlayıcı üstlenirse (erteleme / koşucu) gövde burada koşmaz; iş adı = kilit, cron'lu, kısa iş, ön denetimli")
    void offloadedWithRoundPrecheck() {
        TechErrorReminderService j = job();
        when(scheduler.offloadSideJob(any())).thenReturn(true);
        j.scheduled();
        ArgumentCaptor<SideJob> c = ArgumentCaptor.forClass(SideJob.class);
        verify(scheduler).offloadSideJob(c.capture());
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any());
        SideJob sj = c.getValue();
        assertThat(sj.name()).isEqualTo(TechErrorReminderService.LOCK);
        assertThat(sj.cronTriggered()).isTrue();
        assertThat(sj.heavy()).isFalse();
        assertThat(sj.alreadyDone()).isNotNull();
    }

    @Test
    @DisplayName("Üstlenmezse (tek pod / sahte zamanlayıcı) gövde bugünkü gibi kilit altında burada koşar")
    void inlineWhenNotOffloaded() {
        TechErrorReminderService j = job();
        j.scheduled();
        verify(scheduler).callWithSchedulerLock(eq(TechErrorReminderService.LOCK), any());
    }

    @Test
    @DisplayName("Ön denetim: bugünün turu alınmadan önce false, başka pod aldıktan sonra true, ertesi gün false; kayıt yoksa false")
    void roundTakenFollowsTheDailyRound() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:tesidejob" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
        jdbc.execute(ClusterScheduleService.DDL);
        TechErrorReminderService leader = job();
        TechErrorReminderService follower = job();
        assertThat(leader.roundTaken(T)).as("küme kaydı yok").isFalse();
        leader.setClusterSchedule(new ClusterScheduleService(jdbc, "pod-a"));
        follower.setClusterSchedule(new ClusterScheduleService(jdbc, "pod-b"));
        assertThat(leader.roundTaken(T)).isFalse();
        assertThat(follower.roundDue(T)).as("takipçi bugünün turunu aldı").isTrue();
        assertThat(leader.roundTaken(T.plusSeconds(20))).as("ertelenen deneme: alınmış").isTrue();
        assertThat(leader.roundTaken(T.plusSeconds(86_400))).as("ertesi gün").isFalse();
        jdbc.execute("SHUTDOWN");
        assertThat(leader.roundTaken(T)).as("okunamadı → gövde koşar").isFalse();
    }
}
