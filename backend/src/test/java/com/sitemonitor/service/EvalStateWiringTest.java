package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.service.evalstate.EvalStateRecorder;
import com.sitemonitor.service.evalstate.EvalStateSql;
import com.sitemonitor.service.evalstate.EvalStateVerification;
import com.sitemonitor.service.evalstate.EvalStateVerifier;
import com.sitemonitor.service.evalstate.EvalStateSchemaPatch;
import com.sitemonitor.service.schedule.ClusterScheduleService;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.AbstractApplicationEventMulticaster;
import org.springframework.context.event.ApplicationListenerMethodAdapter;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Çok pod Sürüm 3-1 A2 — Spring bağlaması: MOS (kurucusu değişmedi), kaydedici (A1) ve doğrulayıcı (A2) aynı bağlamda
 * DÖNGÜSÜZ kurulur; bağlam yenilenince MOS kaydediciyi kaynak olarak bağlar (sağlayıcı üzerinden, enjeksiyonda değil) ve
 * kaydedicinin tembel doğrulayıcı sağlayıcısı tek {@link EvalStateVerification} bean'ini bulur.
 */
class EvalStateWiringTest {

    @Test
    @DisplayName("MOS + EvalStateRecorder + EvalStateVerifier bağlamı kurulur; yenilenince MOS kaydediciye bağlı ve kaynak MOS")
    void contextWiresWithoutCycle() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create();
             AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            new EvalStateSchemaPatch(mp.jdbc()).apply();
            ctx.registerBean(JdbcTemplate.class, mp::jdbc);
            ctx.registerBean(AlertEventRepository.class, () -> mock(AlertEventRepository.class));
            ctx.registerBean(EscalationService.class, () -> mock(EscalationService.class));
            ctx.registerBean(DnsRecordRepository.class, () -> mock(DnsRecordRepository.class));
            ctx.registerBean(DnsMonitorRepository.class, () -> mock(DnsMonitorRepository.class));
            ctx.registerBean(AppSettingsService.class, () -> mock(AppSettingsService.class));
            ctx.registerBean(NetworkOutageEventRepository.class, () -> mock(NetworkOutageEventRepository.class));
            ctx.register(MonitoringOutageService.class, EvalStateRecorder.class, EvalStateVerifier.class);
            ctx.refresh();

            MonitoringOutageService mos = ctx.getBean(MonitoringOutageService.class);
            EvalStateRecorder rec = ctx.getBean(EvalStateRecorder.class);
            assertThat(mos.evalStateSink()).as("bağlam yenilenince bağlandı").isSameAs(rec);
            assertThat(ctx.getBeanNamesForType(EvalStateVerification.class)).hasSize(1);
            assertThat(ctx.getBean(EvalStateVerification.class)).isInstanceOf(EvalStateVerifier.class);
            assertThat(mos.evalStateHookFailures()).isZero();

            // Kaydedici kaynağı MOS'tan okur: bağlam yeniden yenilense de ikinci bağlama yok (idempotent).
            mos.bindEvalStateSink();
            assertThat(mos.evalStateSink()).isSameAs(rec);

            // R3-1 FX-STATE #6: kaydedici ve doğrulayıcı ifadeleri kendi sorgu zaman aşımlı şablonlarında.
            assertThat(rec.sql().jdbc().getQueryTimeout()).isEqualTo(EvalStateSql.QUERY_TIMEOUT_SECONDS);
            assertThat(((JdbcTemplate) org.springframework.test.util.ReflectionTestUtils.getField(
                    ctx.getBean(EvalStateVerifier.class), "jdbc")).getQueryTimeout()).isEqualTo(EvalStateSql.QUERY_TIMEOUT_SECONDS);
            assertThat(mp.jdbc().getQueryTimeout()).as("paylaşılan şablon değişmedi").isNotEqualTo(EvalStateSql.QUERY_TIMEOUT_SECONDS);
        }
    }

    @Test
    @DisplayName("FX #4 / F6: Spring liderlik olayını ÖNCE MOS'a (sıfırlama), SONRA kaydediciye dağıtır")
    void leadershipListenersDispatchMosFirst() throws Exception {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create();
             AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            new EvalStateSchemaPatch(mp.jdbc()).apply();
            ctx.registerBean(JdbcTemplate.class, mp::jdbc);
            ctx.registerBean(AlertEventRepository.class, () -> mock(AlertEventRepository.class));
            ctx.registerBean(EscalationService.class, () -> mock(EscalationService.class));
            ctx.registerBean(DnsRecordRepository.class, () -> mock(DnsRecordRepository.class));
            ctx.registerBean(DnsMonitorRepository.class, () -> mock(DnsMonitorRepository.class));
            ctx.registerBean(AppSettingsService.class, () -> mock(AppSettingsService.class));
            ctx.registerBean(NetworkOutageEventRepository.class, () -> mock(NetworkOutageEventRepository.class));
            // Kayıt sırası bilerek TERS: önce kaydedici — sıra @Order'dan gelmeli, kayıttan değil.
            ctx.register(EvalStateRecorder.class, EvalStateVerifier.class, MonitoringOutageService.class,
                    com.sitemonitor.service.metrics.SweepLeadershipCounter.class);
            ctx.refresh();

            Object multicaster = ctx.getBean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME);
            Method m = AbstractApplicationEventMulticaster.class.getDeclaredMethod("getApplicationListeners",
                    ApplicationEvent.class, ResolvableType.class);
            m.setAccessible(true);
            PayloadApplicationEvent<SweepLeadershipChangedEvent> ev = new PayloadApplicationEvent<>(ctx,
                    new SweepLeadershipChangedEvent(true, "x", 1L, SweepLeadershipChangedEvent.GAINED));
            @SuppressWarnings("unchecked")
            Collection<ApplicationListener<?>> listeners = (Collection<ApplicationListener<?>>) m.invoke(multicaster, ev,
                    ResolvableType.forClassWithGenerics(PayloadApplicationEvent.class, SweepLeadershipChangedEvent.class));
            List<String> order = new ArrayList<>();
            for (ApplicationListener<?> l : listeners) {
                if (l instanceof ApplicationListenerMethodAdapter a) order.add(String.valueOf(a));
            }
            int mosAt = indexOf(order, MonitoringOutageService.class.getName());
            int recAt = indexOf(order, EvalStateRecorder.class.getName());
            assertThat(mosAt).as(String.valueOf(order)).isNotNegative();
            assertThat(recAt).as(String.valueOf(order)).isNotNegative();
            assertThat(mosAt).as("MOS önce sıfırlar: " + order).isLessThan(recAt);
        }
    }

    private static int indexOf(List<String> listeners, String className) {
        for (int i = 0; i < listeners.size(); i++) {
            if (listeners.get(i).contains(className + ".")) return i;
        }
        return -1;
    }

    @Test
    @DisplayName("FX #1: kaydedicinin kira anahtarı zamanlayıcının tarama lideri kirasıyla aynı")
    void leaseKeyMatchesScheduler() {
        assertThat(EvalStateSql.LEASE_KEY).isEqualTo(ClusterScheduleService.LEASE_PREFIX + SchedulerService.SWEEP_LEADER);
    }
}
