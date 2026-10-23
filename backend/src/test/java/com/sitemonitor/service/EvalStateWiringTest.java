package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.service.evalstate.EvalStateRecorder;
import com.sitemonitor.service.evalstate.EvalStateVerification;
import com.sitemonitor.service.evalstate.EvalStateVerifier;
import com.sitemonitor.service.evalstate.EvalStateSchemaPatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

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
        }
    }
}
