package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Toplu ağ kesintisi oranına giren hata sayısı (son inceleme 2026-10-22, CHECKS-2 sonrası): bağlanmadan önce DNS'te
 * çözülemeyen alan adları (NXDOMAIN — kaldırılmış / yanlış yazılmış host) sayılmaz; küçük bir turda bunlar sahte "ağ kesintisi"
 * açıp turun alarmlarını bastırıyordu. Bağlantı sırasındaki DNS hatası ve ağ hataları eskisi gibi sayılır.
 */
class SchedulerNetworkErrorCountTest {

    private static Map<String, Object> error(String errorClass, boolean unresolvable) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "error");
        r.put("error_class", errorClass);
        if (unresolvable) r.put(CertificateCheckerService.UNRESOLVABLE_HOST, true);
        return r;
    }

    @Test
    @DisplayName("5 çözülmeyen ad + 1 sağlıklı: sayı 0 (eskiden 5/6 → sahte kesinti); ağ / bağlantı anı DNS hatası sayılır")
    void unresolvableHostsAreNotNetworkErrors() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) results.add(error("DNS", true));
        results.add(new LinkedHashMap<>(Map.of("status", "valid")));
        assertThat(SchedulerService.networkErrorCount(results)).isZero();

        results.add(error("NETWORK", false));
        results.add(error("DNS", false));          // bağlantı sırasında UnknownHost — çıkış / çözümleyici sorunu olabilir
        results.add(error("BLOCKED", false));      // politika reddi
        results.add(error("SSL", false));
        assertThat(SchedulerService.networkErrorCount(results)).isEqualTo(2);
        assertThat(SchedulerService.networkErrorCount(null)).isZero();
    }
}
