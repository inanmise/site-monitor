package com.sitemonitor.service.diagnose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.diagnose.NetDiagFindings.FAIL;
import static com.sitemonitor.service.diagnose.NetDiagFindings.INFO;
import static com.sitemonitor.service.diagnose.NetDiagFindings.WARN;
import static com.sitemonitor.service.diagnose.NetDiagFindings.finding;
import static com.sitemonitor.service.diagnose.NetDiagFindings.params;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bulgu kataloğu + hüküm kuralı (2026-10-05) ve YAN ETKİSİZLİK kapısı: tanılama servisleri hiçbir depoya, zamanlayıcıya,
 * eskalasyona, kesinti/alarm servisine ya da CA pinleyicisine BAĞLI DEĞİLDİR — kontrol kaydı yazamaz, alarm
 * değerlendiremez (bağımlılık yoksa çağrı da yok). Bağımlılık eklemek bu testi kırar; bilinçli karar gerekir.
 */
class NetDiagFindingsTest {

    @Test
    @DisplayName("katalog tekil; tür OK kodları + adım anahtarları tanımlı")
    void catalogue() {
        assertThat(NetDiagFindings.CODES).doesNotHaveDuplicates().contains("PING_OK", "PORT_OK", "DNS_OK", "PATH_DIFFERS");
        assertThat(NetDiagFindings.STEP_KEYS).doesNotHaveDuplicates().hasSize(16);
    }

    @Test
    @DisplayName("sıralama: fail → warn → info (kararlı); aynı kod+yol tekrar etmez")
    void ordered() {
        List<Map<String, Object>> in = new ArrayList<>();
        in.add(finding("PING_OK", INFO, params()));
        in.add(finding("HIGH_RTT", WARN, params()));
        in.add(finding("HOST_UNREACHABLE", FAIL, params()));
        in.add(finding("PARTIAL_LOSS", WARN, params()));
        in.add(finding("HIGH_RTT", WARN, params()));
        List<Object> codes = NetDiagFindings.ordered(in).stream().map(f -> f.get("code")).toList();
        assertThat(codes).containsExactly("HOST_UNREACHABLE", "HIGH_RTT", "PARTIAL_LOSS", "PING_OK");
    }

    @Test
    @DisplayName("hüküm: PATH_DIFFERS > ilk fail > ilk warn > OK kodu > INCONCLUSIVE")
    void verdict() {
        List<Map<String, Object>> f = NetDiagFindings.ordered(List.of(
                finding("PROXY_PORT_NOT_ALLOWED", FAIL, "monitor", params("port", 5432)),
                finding("PATH_DIFFERS", FAIL, params("failing_route", "proxy"))));
        assertThat(NetDiagFindings.verdict(f, "PORT_OK")).containsEntry("code", "PATH_DIFFERS").containsEntry("status", "fail");
        assertThat(NetDiagFindings.verdict(NetDiagFindings.ordered(List.of(finding("PORT_OK", INFO, params()),
                finding("SLOW_RESPONSE", WARN, params()))), "PORT_OK")).containsEntry("code", "SLOW_RESPONSE").containsEntry("status", "warn");
        assertThat(NetDiagFindings.verdict(List.of(finding("PROXY_NOT_APPLICABLE", INFO, params()), finding("DNS_OK", INFO, params())), "DNS_OK"))
                .containsEntry("code", "DNS_OK").containsEntry("status", "ok");
        assertThat(NetDiagFindings.verdict(List.of(finding("PROXY_NOT_APPLICABLE", INFO, params())), "PING_OK"))
                .containsEntry("code", "INCONCLUSIVE").containsEntry("status", "warn");
    }

    @Test
    @DisplayName("YAN ETKİSİZLİK: tanılama servislerinin hiçbir bağımlılığı depo / zamanlayıcı / alarm / eskalasyon / CA pin değil")
    void diagnoseServicesHaveNoSideEffectDependencies() {
        List<Class<?>> services = List.of(PingDiagnosticsService.class, PortDiagnosticsService.class, DnsDiagnosticsService.class,
                DefaultNetDiagNetwork.class, NetDiagWorkers.class);
        List<String> forbidden = List.of("Repository", "SchedulerService", "EscalationService", "MonitoringOutageService",
                "CaAutoPinService", "AlertEvent", "StormService", "UserPushService", "EmailNotificationService", "WebhookService");
        List<String> offending = new ArrayList<>();
        for (Class<?> c : services) {
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                for (Class<?> p : k.getParameterTypes()) {
                    if (forbidden.stream().anyMatch(f -> p.getSimpleName().contains(f))) offending.add(c.getSimpleName() + "(" + p.getSimpleName() + ")");
                }
            }
            for (Field fl : c.getDeclaredFields()) {
                if (forbidden.stream().anyMatch(f -> fl.getType().getSimpleName().contains(f))) offending.add(c.getSimpleName() + "." + fl.getName());
            }
        }
        assertThat(offending).isEmpty();
    }
}
