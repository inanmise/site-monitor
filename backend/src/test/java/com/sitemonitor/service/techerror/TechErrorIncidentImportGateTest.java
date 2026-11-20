package com.sitemonitor.service.techerror;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Görselli olay içe almasının pod başına yük sınırı (inceleme 2026-11-20): kişi başına aynı anda tek, pod başına
 * {@link TechErrorIncidentImportGate#PER_POD}; reddedilen istek hiçbir şey tutmaz; yer bir kez bırakılır.
 */
class TechErrorIncidentImportGateTest {

    @Test
    @DisplayName("kişi başına tek: aynı kişinin ikinci isteği USER reddi; başka kişi geçer; pod dolunca POD reddi; ret yer tutmaz")
    void perUserAndPerPod() {
        TechErrorIncidentImportGate g = new TechErrorIncidentImportGate();
        var a = g.tryEnter("u:1");
        assertThat(a.slot()).isNotNull();
        var a2 = g.tryEnter("u:1");
        assertThat(a2.slot()).isNull();
        assertThat(a2.refusal()).isEqualTo(TechErrorIncidentImportGate.Refusal.USER);
        var b = g.tryEnter("u:2");
        assertThat(b.slot()).isNotNull();
        assertThat(g.available()).isZero();
        var c = g.tryEnter("u:3");
        assertThat(c.slot()).isNull();
        assertThat(c.refusal()).isEqualTo(TechErrorIncidentImportGate.Refusal.POD);
        // POD reddi kişiyi tutmaz: yer açılınca aynı kişi girer
        a.slot().close();
        var c2 = g.tryEnter("u:3");
        assertThat(c2.slot()).isNotNull();
        b.slot().close();
        c2.slot().close();
        assertThat(g.available()).isEqualTo(TechErrorIncidentImportGate.PER_POD);
    }

    @Test
    @DisplayName("yer bir kez bırakılır: ikinci close etkisiz (izin sayısı PER_POD'u aşmaz)")
    void closeIsIdempotent() {
        TechErrorIncidentImportGate g = new TechErrorIncidentImportGate();
        var a = g.tryEnter("u:1");
        a.slot().close();
        a.slot().close();
        assertThat(g.available()).isEqualTo(TechErrorIncidentImportGate.PER_POD);
        var again = g.tryEnter("u:1");
        assertThat(again.slot()).isNotNull();
        again.slot().close();
        assertThat(g.tryEnter(null).slot()).isNotNull();          // anahtarsız istek de sınırlı ("?")
        assertThat(g.tryEnter(null).refusal()).isEqualTo(TechErrorIncidentImportGate.Refusal.USER);
    }
}
