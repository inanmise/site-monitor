package com.sitemonitor.service;

import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.service.checks.CheckJobTypes;
import com.sitemonitor.service.checks.CheckResultRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Çok pod Sürüm 3-3 paket B — K-3 GİDİŞ-DÖNÜŞ KAPISI (tasarım R3-3 §9.2 B). Sekiz dağıtılan türün her biri için aynı sabit
 * denetleyici sonuçlarıyla:
 * <ol>
 *   <li>KLASİK yol: {@code run<Tür>ChecksLocked()} — {@code MonitoringOutageService}'e giden kalemler / DNS değişiklikleri;</li>
 *   <li>DAĞITIK yol: her izleme için işçinin {@code runCheckJob} gövdesi → {@code CheckResultCodec.encode} (JSON) →
 *       {@code CheckResultRow} → değerlendiricinin {@code applyCheckResults} (tek dalga).</li>
 * </ol>
 * İki yolun MOS çağrıları (alarm türü sırası, kalemler — alan, ayrıntı, up, hata, bağlam, yeniden kontrol var mı — ve DNS'in
 * dört kalem listesi + {@code DnsChange}) JSON'a göre normalleştirilmiş (anahtar sırası, Long → Integer) olarak EŞİT olmalı;
 * kontrol geçmişi + etkinlik yazımları da (işçi gövdesi klasik tedarikçinin aynısı). SLOW eşleri, sayfa bütünlüğü kapsamları,
 * k6 SKIPPED (kalem yok), DNS'in dört türü + değişiklik dahil.
 */
class ScheduledResultRoundTripTest {

    R33SweepHarness h;

    @BeforeEach
    void setUp() throws Exception {
        h = new R33SweepHarness();
    }

    // ── yazım dökümü (geçmiş satırı + etkinlik) ──────────────────────────────────────────────────────────────────────

    private static final Set<String> REPO_WRITES = Set.of("save", "saveAll", "replaceLatest");

    private Object[] writeMocks() {
        return new Object[]{h.activity, h.portCheckRepo, h.keywordResultRepo, h.httpCheckRepo, h.pingCheckRepo,
                h.dnsRecordRepo, h.pageCheckRepo, h.pageIssueRepo, h.pageSpeedCheckRepo, h.pageSpeedResourceRepo,
                h.scriptedCheckRepo};
    }

    private List<String> writes() {
        List<Invocation> all = new ArrayList<>();
        for (Object m : writeMocks()) {
            for (Invocation inv : Mockito.mockingDetails(m).getInvocations()) {
                String name = inv.getMethod().getName();
                boolean rec = m == h.activity ? "recordCheck".equals(name) : REPO_WRITES.contains(name);
                if (rec) all.add(inv);
            }
        }
        all.sort(Comparator.comparingInt(Invocation::getSequenceNumber));
        List<String> out = new ArrayList<>();
        for (Invocation inv : all) {
            List<Object> args = new ArrayList<>();
            for (Object a : inv.getArguments()) args.add(R33SweepHarness.canonical(ClassicSweepGoldenTest.norm(a, 0)));
            out.add(inv.getMethod().getDeclaringClass().getSimpleName() + "." + inv.getMethod().getName() + " "
                    + ClassicSweepGoldenTest.Json.write(args));
        }
        return out;
    }

    private void clearAll() {
        h.clearMos();
        Mockito.clearInvocations(writeMocks());
    }

    /**
     * Klasik gövdeyi koşturur, sonra aynı izlemeler için işçi → JSON → değerlendirici yolunu; iki MOS dökümünü ve iki yazım
     * dökümünü karşılaştırır.
     */
    private <M> List<String> assertRoundTrip(String type, String lockedBody, List<M> monitors, Function<M, Long> idOf,
                                             String... mustContain) {
        clearAll();
        ReflectionTestUtils.invokeMethod(h.scheduler, lockedBody);
        List<String> classic = h.mosCalls();
        List<String> classicWrites = writes();
        clearAll();
        List<CheckResultRow> wave = new ArrayList<>();
        for (M m : monitors) wave.add(h.workerRow(type, idOf.apply(m)));
        List<String> workerWrites = writes();
        h.scheduler.applyCheckResults(wave);
        List<String> distributed = h.mosCalls();
        assertThat(distributed).as(type + ": dağıtık değerlendirme klasik taramanın AYNI kalemlerini üretmeli (K-3)")
                .isEqualTo(classic);
        assertThat(workerWrites).as(type + ": işçi gövdesi klasik tedarikçinin aynı geçmiş / etkinlik yazımlarını yapmalı")
                .isEqualTo(classicWrites);
        assertThat(String.join("\n", classic)).contains(mustContain).contains("\"recheck\": true");
        return distributed;
    }

    @Test
    @DisplayName("port: PORT_DOWN + PORT_SLOW (ölçüldü / ölçülemedi / özellik kapalı), envanter dışı türev, istisna — klasikle aynı")
    void port() {
        var all = h.portFixture();
        assertRoundTrip(CheckJobTypes.PORT, "runPortChecksLocked", all, PortMonitor::getId,
                "PORT_DOWN", "PORT_SLOW", "\"up\": false", "\"up\": true", "mail_disabled");
    }

    @Test
    @DisplayName("keyword: KEYWORD + KEYWORD_SLOW, adet koşulu, yapılandırma hatası, uzun anahtar sözcük, istisna — klasikle aynı")
    void keyword() {
        var all = h.keywordFixture();
        assertRoundTrip(CheckJobTypes.KEYWORD, "runKeywordChecksLocked", all, com.sitemonitor.model.KeywordMonitor::getId,
                "\"KEYWORD\"", "KEYWORD_SLOW", "config_error");
    }

    @Test
    @DisplayName("http: HTTP_DOWN + HTTP_SLOW (yalnız açık izlemede), JSON doğrulaması, yapılandırma hatası — klasikle aynı")
    void http() {
        var all = h.httpFixture();
        assertRoundTrip(CheckJobTypes.HTTP, "runHttpChecksLocked", all, com.sitemonitor.model.HttpMonitor::getId,
                "HTTP_DOWN", "HTTP_SLOW", "json_assertion_failed", "config_error");
    }

    @Test
    @DisplayName("ping: PING_DOWN + PING_SLOW (taban çizgisi, checked_at üst sınırı), ICMP yok — klasikle aynı")
    void ping() {
        var all = h.pingFixture();
        assertRoundTrip(CheckJobTypes.PING, "runPingChecksLocked", all, com.sitemonitor.model.PingMonitor::getId,
                "PING_DOWN", "PING_SLOW", "baseline_ms", "limit_ms");
        // Taban çizgisinin üst sınırı işçinin yazdığı satırın damgası (checked_at) — iki yolda da bir ISO zaman.
        verify(h.pingCheckRepo, atLeastOnce()).slowBaseline(eq(1L), anyString(),
                argThat(s -> s != null && s.matches("\\d{4}-.*")));
    }

    @Test
    @DisplayName("dns: DNS_FAILURE + DNS_SLOW + DNS_UNEXPECTED + DNS_INCONSISTENT + DnsChange TEK handleDnsSweep'te; change_state")
    void dns() {
        var all = h.dnsFixture();
        List<String> calls = assertRoundTrip(CheckJobTypes.DNS, "runDnsChecksLocked", all,
                com.sitemonitor.model.DnsMonitor::getId,
                "DNS failure=", "DNS_SLOW", "DNS_UNEXPECTED", "DNS_INCONSISTENT", "\"detectedAt\": \"<time>\"",
                "\"previousValue\": \"1.2.3.4\"");
        assertThat(calls).hasSize(1);   // dalga başına TEK handleDnsSweep (MOS günlük yeniden uyarı kuralı)
    }

    @Test
    @DisplayName("page: PAGE_DOWN + PAGE_INTEGRITY (bulgu + kaynak sayfa, ana sayfa düştü, derin tarama izlemesinin ana sayfa turu) — klasikle aynı")
    void page() {
        var all = h.pageFixture();
        assertRoundTrip(CheckJobTypes.PAGE, "runPageChecksLocked", all, com.sitemonitor.model.PageMonitor::getId,
                "PAGE_DOWN", "PAGE_INTEGRITY", "\"integrity_scope\": \"HOME\"", "problem_rows");
    }

    @Test
    @DisplayName("pagespeed: PAGESPEED_DOWN + PAGESPEED_SLOW, ağır kaynak satırları, kırpılmış boyut — klasikle aynı")
    void pageSpeed() {
        var all = h.pageSpeedFixture();
        assertRoundTrip(CheckJobTypes.PAGESPEED, "runPageSpeedChecksLocked", all,
                com.sitemonitor.model.PageSpeedMonitor::getId, "PAGESPEED_DOWN", "PAGESPEED_SLOW", "heavy_rows",
                "breached_metrics");
    }

    @Test
    @DisplayName("scripted: SCRIPTED_FAIL + SCRIPTED_SLOW; SKIPPED kalem ve kayıt üretmez; düşen koşum kalem üretmez — klasikle aynı")
    void scripted() {
        var all = h.scriptedFixture();
        List<String> calls = assertRoundTrip(CheckJobTypes.SCRIPTED, "runScriptedChecksLocked", all,
                com.sitemonitor.model.ScriptedMonitor::getId, "SCRIPTED_FAIL", "SCRIPTED_SLOW", "failed_checks");
        assertThat(String.join("\n", calls)).doesNotContain("Atlanan akış").doesNotContain("Düşen gelecek");
    }

    @Test
    @DisplayName("yeniden kontrol kapanışı DEĞERLENDİRİCİNİN yüklediği varlık üzerine kurulur (yeniden kontroller liderde)")
    void recheckClosureUsesTheEvaluatorsEntity() {
        h.portFixture();
        PortMonitor loaded = R33SweepHarness.port(1, "a.example.com", 443, true);   // değerlendiricinin AYRI kopyası
        loaded.setSlowResponseEnabled(true);
        loaded.setSlowThresholdMs(500);
        CheckResultRow row = h.workerRow(CheckJobTypes.PORT, 1L);
        Mockito.doReturn(List.of(loaded)).when(h.portRepo).findAllById(anyIterable());
        h.clearMos();
        Mockito.clearInvocations(h.portChecker);
        h.scheduler.applyCheckResults(List.of(row));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MonitoringOutageService.SweepItem>> items = ArgumentCaptor.forClass(List.class);
        verify(h.outage).handleSweepResults(eq(EscalationService.TYPE_PORT_DOWN), items.capture());
        assertThat(items.getValue()).hasSize(1);
        verify(h.portChecker, never()).check(argThat((PortMonitor m) -> m == loaded));
        items.getValue().get(0).recheck().get();   // MOS teyit adımı gibi
        verify(h.portChecker).check(argThat((PortMonitor m) -> m == loaded));
    }

    @Test
    @DisplayName("tek dalgada birden çok tür: tür başına klasik sıra (DOWN önce SLOW), türler CheckJobTypes sırasında")
    void mixedWaveKeepsTypeOrder() {
        h.portFixture();
        h.pingFixture();
        List<CheckResultRow> wave = List.of(h.workerRow(CheckJobTypes.PING, 1L), h.workerRow(CheckJobTypes.PORT, 1L));
        h.clearMos();
        h.scheduler.applyCheckResults(wave);
        List<String> heads = h.mosCalls().stream().map(s -> s.substring(0, s.indexOf(' '))).toList();
        assertThat(heads).containsExactly("PORT_DOWN", "PORT_SLOW", "PING_DOWN", "PING_SLOW");
        verify(h.outage, never()).handleSweepResults(anyString(), anyList(), eq(true));
    }

    @Test
    @DisplayName("JSON gidiş-dönüşü: bağlamdaki sayılar ve listeler klasikle aynı türde okunur (Long → Integer fark etmez)")
    void decodedResultKeepsShape() {
        h.dnsFixture();
        CheckResultRow row = h.workerRow(CheckJobTypes.DNS, 5L);
        Map<String, Object> r = row.result();
        assertThat(r).containsKeys("main", "prev_value", "value", "changed", "rotated", "checked_at", "prop", "target");
        assertThat(r.get("target")).isEqualTo("e.example.com|A");
        @SuppressWarnings("unchecked")
        Map<String, Object> main = (Map<String, Object>) r.get("main");
        assertThat(main.get("values")).isEqualTo(List.of("9.9.9.9", "7.7.7.7"));
    }
}
