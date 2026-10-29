package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.service.checks.CheckResultDrainer;
import com.sitemonitor.service.checks.DistributedChecks;
import com.sitemonitor.service.evalstate.EvalStateRecorder;
import com.sitemonitor.service.evalstate.EvalStateSchemaPatch;
import com.sitemonitor.service.evalstate.EvalStateVerifier;
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
 * Çok pod Sürüm 3-3 inceleme düzeltmesi DH-L4 (FX-EVAL): GERÇEK Spring çok yayıncısı liderlik olayını önce MOS'a (3-2 kapısını
 * kurar), SONRA sonuç boşaltıcısına dağıtır — boşaltıcının "GAINED görülmeden bilet yok" güvencesi bu sıraya dayanır
 * (tasarım R3-3 C sapma 3). Kayıt sırası bilerek ters: sıra {@code @Order}'dan gelmeli.
 */
class CheckResultDrainerWiringTest {

    @Test
    @DisplayName("DH-L4: gerçek bağlamda liderlik dinleyicileri sırası MOS → kaydedici → boşaltıcı")
    void leadershipListenersDispatchMosBeforeTheDrainer() throws Exception {
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
            // Kayıt sırası TERS: önce boşaltıcı, sonra kaydedici, en son MOS.
            ctx.register(DistributedChecks.class, CheckResultDrainer.class, EvalStateRecorder.class, EvalStateVerifier.class,
                    MonitoringOutageService.class);
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
            int drainAt = indexOf(order, CheckResultDrainer.class.getName());
            assertThat(mosAt).as(String.valueOf(order)).isNotNegative();
            assertThat(drainAt).as(String.valueOf(order)).isNotNegative();
            assertThat(mosAt).as("MOS önce (3-2 kapısı kurulur): " + order).isLessThan(drainAt);
            // anahtar kapalı (ayar sahte → false): bağlam kurulunca iş parçacığı yok (K-1)
            assertThat(ctx.getBean(CheckResultDrainer.class).started()).isFalse();
        }
    }

    private static int indexOf(List<String> listeners, String className) {
        for (int i = 0; i < listeners.size(); i++) {
            if (listeners.get(i).contains(className + ".")) return i;
        }
        return -1;
    }
}
