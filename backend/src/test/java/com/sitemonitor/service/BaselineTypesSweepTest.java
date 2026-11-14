package com.sitemonitor.service;

import com.sitemonitor.service.baseline.BaselineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Öğrenen bant (dinamik taban çizgisi) SLOW kalemi — HTTP dışındaki 6 türün ({@code keyword/port/ping/pagespeed/scripted/dns})
 * SchedulerService builder'larına bağlanması (2026-11-12). Her türün {@code <tür>BaselineSlowItem} sarmalayıcısı, enjekte
 * edilmiş {@code baseline_*} anahtarlarını doğru SweepItem'a (yavaş = up:false / bant içi = up:true / kuruluyor = null)
 * çevirir; detay KARARLI ({@code SLOW_DETAIL}) ve alarm tipi doğru türün SLOW'u olur. HTTP {@link BaselineHttpSweepTest}'te,
 * "kapalıyken birebir aynı" {@code ClassicSweepGoldenTest} / {@code ScheduledResultRoundTripTest}'te, matematik + örnek
 * yazımı {@code BaselineServiceTest}'te.
 */
class BaselineTypesSweepTest {

    R33SweepHarness h;

    @BeforeEach
    void setup() throws Exception {
        h = new R33SweepHarness();
    }

    /** CTX_* enjekte edilmiş bir zamanlanmış sonuç (builder'ın öğrenen-bant dalı bunu okur). */
    private static Map<String, Object> ctx(long respMs, Boolean evaluated, String level) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "up");
        r.put("response_ms", respMs);
        r.put(BaselineService.CTX_ON, true);
        r.put(BaselineService.CTX_EVALUATED, evaluated);
        if (level != null) r.put(BaselineService.CTX_LEVEL, level);
        r.put(BaselineService.CTX_VALUE, respMs);
        r.put(BaselineService.CTX_MEAN, 100.0);
        r.put(BaselineService.CTX_HIGH, 105.0);
        r.put(BaselineService.CTX_LOW, 95.0);
        r.put(BaselineService.CTX_DEVIATION, 40.0);
        return r;
    }

    private MonitoringOutageService.SweepItem item(String wrapper, Object monitor, Map<String, Object> r) {
        return ReflectionTestUtils.invokeMethod(h.scheduler, wrapper, monitor, r);
    }

    private void assertSlowTriplet(String wrapper, Object monitor, String expectedAlertType, String expectedKey) {
        // 1) Bant dışı (kritik) → yavaş kalem: up:false, KARARLI detail, doğru alarm tipi/anahtar.
        var slow = item(wrapper, monitor, ctx(500, true, "critical"));
        assertThat(slow).as("%s kritik → kalem var", wrapper).isNotNull();
        assertThat(slow.alertType()).isEqualTo(expectedAlertType);
        assertThat(slow.domain()).isEqualTo(expectedKey);
        assertThat(slow.detail()).isEqualTo(BaselineService.SLOW_DETAIL);   // zincir anahtarı bant kaydıkça değişmesin
        assertThat(slow.up()).isFalse();                                     // yavaş = sağlıklı değil

        // 2) Bant içi (none) → sağlıklı kalem: up:true (açık alarm kapanır).
        var ok = item(wrapper, monitor, ctx(101, true, "none"));
        assertThat(ok).as("%s bant içi → kalem var", wrapper).isNotNull();
        assertThat(ok.up()).isTrue();
        assertThat(ok.detail()).isEqualTo(BaselineService.SLOW_DETAIL);

        // 3) Taban kuruluyor (evaluated=false) → kalem YOK (D-7: açık alarm sahte kapanmaz).
        assertThat(item(wrapper, monitor, ctx(500, false, null)))
                .as("%s kuruluyor → null", wrapper).isNull();
    }

    @Test
    void keyword() {
        assertSlowTriplet("keywordBaselineSlowItem", R33SweepHarness.keyword(1, "https://ornek.test/", "ok"),
                EscalationService.TYPE_KEYWORD_SLOW, "https://ornek.test/");
    }

    @Test
    void port() {
        assertSlowTriplet("portBaselineSlowItem", R33SweepHarness.port(1, "ornek.test", 443, false),
                EscalationService.TYPE_PORT_SLOW, "ornek.test");
    }

    @Test
    void ping() {
        assertSlowTriplet("pingBaselineSlowItem", R33SweepHarness.ping(1, "ornek.test"),
                EscalationService.TYPE_PING_SLOW, "ornek.test");
    }

    @Test
    void pageSpeed() {
        assertSlowTriplet("pageSpeedBaselineSlowItem", R33SweepHarness.pageSpeed(1, "https://ornek.test/"),
                EscalationService.TYPE_PAGESPEED_SLOW, "https://ornek.test/");
    }

    @Test
    @DisplayName("PSR1 madde 3: bant YALNIZ yükleme eşiğinin yerini alır — TTFB / boyut ihlali tek SLOW kalemde alarm üretir")
    void pageSpeedOtherThresholdsKeepAlerting() {
        var m = R33SweepHarness.pageSpeed(1, "https://ornek.test/");
        // 1) Bant İÇİ ama TTFB eşiği aşıldı → kalem down, bant ölçüleri bağlamda YOK (posta "dinamik taban" demesin)
        Map<String, Object> r = ctx(101, true, "none");
        r.put("breached", List.of("TTFB"));
        r.put("ttfb_ms", 900);
        var it = item("pageSpeedBaselineSlowItem", m, r);
        assertThat(it).isNotNull();
        assertThat(it.up()).isFalse();
        assertThat(it.alertType()).isEqualTo(EscalationService.TYPE_PAGESPEED_SLOW);
        assertThat(it.detail()).contains("TTFB").doesNotContain(BaselineService.SLOW_DETAIL);
        assertThat(it.ctxExtra()).doesNotContainKey("baseline");
        assertThat(it.ctxExtra().get("breached_metrics")).isEqualTo(List.of("TTFB"));

        // 2) Bant DIŞI + boyut ihlali → ikisi birden listelenir, bant ölçüleri bağlamda
        Map<String, Object> both = ctx(500, true, "warning");
        both.put("breached", List.of("LOAD", "SIZE"));
        var b = item("pageSpeedBaselineSlowItem", m, both);
        assertThat(b.up()).isFalse();
        assertThat(b.ctxExtra().get("breached_metrics")).isEqualTo(List.of("LOAD", "SIZE"));
        assertThat(b.ctxExtra()).containsEntry("baseline", true);

        // 3) Sabit yükleme eşiği aşıldı ama bant İÇİ → yükleme sabit eşiği YOK sayılır: sağlıklı kalem
        Map<String, Object> loadOnly = ctx(101, true, "none");
        loadOnly.put("breached", List.of("LOAD"));
        assertThat(item("pageSpeedBaselineSlowItem", m, loadOnly).up()).isTrue();

        // 4) Bant kuruluyor + istek eşiği aşıldı → sabit eşik kanıttır: kalem down (D-7 yalnız yükleme için)
        Map<String, Object> building = ctx(500, false, null);
        building.put("breached", List.of("REQUESTS"));
        var bld = item("pageSpeedBaselineSlowItem", m, building);
        assertThat(bld).isNotNull();
        assertThat(bld.up()).isFalse();
        assertThat(bld.ctxExtra()).doesNotContainKey("baseline");
    }

    @Test
    void scripted() {
        assertSlowTriplet("scriptedBaselineSlowItem", R33SweepHarness.scripted(1, "senaryo-1"),
                EscalationService.TYPE_SCRIPTED_SLOW, "senaryo-1");
    }

    @Test
    void dns() {
        assertSlowTriplet("dnsBaselineSlowItem", R33SweepHarness.dns(1, "ornek.test", "A", false),
                EscalationService.TYPE_DNS_SLOW, "ornek.test");
    }
}
