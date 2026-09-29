package com.sitemonitor.service;

import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.ScriptedMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/**
 * ELLE KONTROL BAĞLANTI KAPISI (2026-09-29): her tetik ucunun girdiği {@code SchedulerService.evaluate*Now}
 * değerlendirmeyi YALNIZ "elle" işaretiyle ({@code handleSweepResults(tür, kalemler, true)}) yapar.
 *
 * <p>İşaretsiz (iki argümanlı / {@code manual=false}) çağrı, zamanlanmış sweep gibi davranır: teyit zinciri
 * başlatır, alarm açar, fırtınaya girer. Prod olayında alan adı izlemesinin elle kontrolü
 * ({@code evaluateDomainAlarmsNow}) tam bu şekilde işaretsiz çağırıyordu — alarm açma kuralı ({@link
 * ManualCheckNoAlarmTest}) doğru olsa bile bu tür onu hiç görmezdi.
 *
 * <p>Servis kurucusu değişse de derlensin diye parametre TİPİNE göre sahte nesnelerle kurulur.
 */
class ManualCheckWiringTest {

    private MonitoringOutageService outage;
    private SchedulerService scheduler;

    @BeforeEach
    void setUp() {
        outage = mock(MonitoringOutageService.class);
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(MonitoringOutageService.class, outage);
        provided.put(AppSettingsService.class, appSettings);
        scheduler = ManualCheckNoAlarmTest.build(SchedulerService.class, provided);
    }

    private static Map<String, Object> raw(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** Tür adı → o türün elle değerlendirme girişi (tetik ucunun çağırdığı yol), BAŞARISIZ ham sonuçla. */
    private Map<String, Consumer<SchedulerService>> entries() {
        Map<String, Consumer<SchedulerService>> e = new LinkedHashMap<>();
        HttpMonitor http = new HttpMonitor(); http.setId(1L); http.setName("HTTP A"); http.setUrl("https://a.example.com"); http.setTeamId(7L);
        e.put("http", s -> s.evaluateHttpNow(http, raw("ok", false, "error", "connect timed out")));
        PortMonitor port = new PortMonitor(); port.setId(2L); port.setName("Port A"); port.setHost("db.example.com"); port.setPort(5432); port.setTeamId(7L); port.setStandalone(true);
        e.put("port", s -> s.evaluatePortNow(port, raw("open", false, "error", "connection refused")));
        PingMonitor ping = new PingMonitor(); ping.setId(3L); ping.setName("Ping A"); ping.setHost("gw.example.com"); ping.setTeamId(7L);
        e.put("ping", s -> s.evaluatePingNow(ping, raw("up", false, "error", "timeout")));
        KeywordMonitor kw = new KeywordMonitor(); kw.setId(4L); kw.setName("Kelime A"); kw.setUrl("https://k.example.com"); kw.setKeyword("Tamam"); kw.setTeamId(7L);
        e.put("keyword", s -> s.evaluateKeywordNow(kw, raw("found", false, "count", 0)));
        DnsMonitor dns = new DnsMonitor(); dns.setId(5L); dns.setName("DNS A"); dns.setDomain("d.example.com"); dns.setRecordType("A"); dns.setTeamId(7L); dns.setStandalone(true);
        e.put("dns", s -> s.evaluateDnsNow(dns, raw("success", false, "error", "NXDOMAIN")));
        PageMonitor page = new PageMonitor(); page.setId(6L); page.setName("Sayfa A"); page.setUrl("https://p.example.com"); page.setTeamId(7L);
        e.put("page", s -> s.evaluatePageNow(page, raw("main_up", false, "integrity_up", false, "error", "HTTP 503")));
        PageSpeedMonitor speed = new PageSpeedMonitor(); speed.setId(7L); speed.setName("Hız A"); speed.setUrl("https://s.example.com"); speed.setTeamId(7L);
        e.put("pagespeed", s -> s.evaluatePageSpeedNow(speed, raw("reachable", false, "error", "timeout")));
        ScriptedMonitor sc = new ScriptedMonitor(); sc.setId(8L); sc.setName("Senaryo A"); sc.setTeamId(7L);
        e.put("scripted", s -> s.evaluateScriptedNow(sc, raw("up", false, "detail", "FAIL — 0/2")));
        DomainMonitor dm = new DomainMonitor(); dm.setId(9L); dm.setName("Alan A"); dm.setDomain("example.com"); dm.setTeamId(7L);
        e.put("domain", s -> s.evaluateDomainAlarmsNow(dm, raw("status", "UNKNOWN", "error", "RDAP timeout")));
        return e;
    }

    @Test
    @DisplayName("KAPI: her evaluate*Now değerlendirmeyi YALNIZ elle işaretiyle yapar (işaretsiz = sweep gibi alarm açar)")
    void everyManualEntry_passesManualFlag() {
        List<String> unflagged = new ArrayList<>();
        List<String> silent = new ArrayList<>();
        for (Map.Entry<String, Consumer<SchedulerService>> en : entries().entrySet()) {
            clearInvocations(outage);
            en.getValue().accept(scheduler);
            List<Invocation> calls = mockingDetails(outage).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("handleSweepResults")).toList();
            if (calls.isEmpty()) silent.add(en.getKey());
            for (Invocation i : calls) {
                Object[] a = i.getArguments();
                boolean manual = a.length >= 3 && Boolean.TRUE.equals(a[2]);
                if (!manual) unflagged.add(en.getKey() + " → " + a[0]);
            }
        }
        assertThat(silent).as("değerlendirmeye hiç girmeyen giriş (kapı boşa döner)").isEmpty();
        assertThat(unflagged).as("elle kontrolü İŞARETSİZ (zamanlanmış gibi) değerlendiren giriş").isEmpty();
    }
}
