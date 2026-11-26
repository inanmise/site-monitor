package com.sitemonitor.service;

import com.sitemonitor.model.PageCheck;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.model.PingCheck;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortCheck;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.UptimeCheck;
import com.sitemonitor.repository.PageCheckRepository;
import com.sitemonitor.repository.PingCheckRepository;
import com.sitemonitor.repository.PortCheckRepository;
import com.sitemonitor.repository.UptimeCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Erişilebilirlik sayım bayrakları (H paketi 2026-11-26, rapor B2): teyit / kurtarma zincirinin yeniden kontrolü
 * ({@link CheckRunContext#asRecheck}) satıra {@code recheck = TRUE} yazar; planlı tarama NULL yazar; Sayfa Bütünlüğü'nün
 * elle kontrolü {@code manual = TRUE}. Bayrak yalnız TRUE yazılır (NULL = planlı), alarm kararı ve diğer alanlar değişmez.
 */
class AvailabilityFlagWritersTest {

    private SchedulerService scheduler;
    private PingCheckerService pingChecker;
    private PortCheckerService portChecker;
    private UptimeHttpCheckerService uptimeChecker;
    private PageCheckerService pageChecker;

    @SuppressWarnings("unchecked")
    private static <T> T build(Class<T> type, Map<Class<?>, Object> provided) throws Exception {
        Constructor<?> c = java.util.Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = provided.containsKey(pt[i]) ? provided.get(pt[i]) : mock(pt[i]);
        return (T) c.newInstance(args);
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) {
        return (T) ReflectionTestUtils.getField(scheduler, name);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @BeforeEach
    void setUp() throws Exception {
        AppSettingsService appSettings = mock(AppSettingsService.class);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        pingChecker = mock(PingCheckerService.class);
        portChecker = mock(PortCheckerService.class);
        uptimeChecker = mock(UptimeHttpCheckerService.class);
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(AppSettingsService.class, appSettings);
        provided.put(PingCheckerService.class, pingChecker);
        provided.put(PortCheckerService.class, portChecker);
        provided.put(UptimeHttpCheckerService.class, uptimeChecker);
        scheduler = build(SchedulerService.class, provided);
        pageChecker = mock(PageCheckerService.class);
        ReflectionTestUtils.setField(scheduler, "pageCheckerService", pageChecker);
        ReflectionTestUtils.setField(scheduler, "pageCheckRepo", mock(PageCheckRepository.class));
        ReflectionTestUtils.setField(scheduler, "pageResourceIssueRepo",
                mock(com.sitemonitor.repository.PageResourceIssueRepository.class));
    }

    @Test
    @DisplayName("CheckRunContext: asRecheck içinde TRUE, dışında null; iç içe çağrı ve istisna sonrası önceki değer geri gelir")
    void contextRules() {
        assertThat(CheckRunContext.recheck()).isFalse();
        assertThat(CheckRunContext.recheckFlag()).isNull();
        Boolean inner = CheckRunContext.asRecheck(() -> CheckRunContext.asRecheck(CheckRunContext::recheckFlag));
        assertThat(inner).isTrue();
        Boolean afterNested = CheckRunContext.asRecheck(() -> {
            CheckRunContext.asRecheck(() -> 1);
            return CheckRunContext.recheckFlag();
        });
        assertThat(afterNested).as("iç çağrı dış bağlamı silmez").isTrue();
        assertThat(CheckRunContext.recheck()).isFalse();
        assertThatThrownBy(() -> CheckRunContext.asRecheck(() -> { throw new IllegalStateException("x"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(CheckRunContext.recheck()).as("istisnadan sonra bağlam temizlenir").isFalse();
    }

    @Test
    @DisplayName("Ping: teyit adımında recheck=TRUE, planlı taramada null; up / rtt aynen")
    void pingRecheckFlag() {
        PingMonitor m = new PingMonitor();
        m.setId(3L); m.setName("P"); m.setHost("host.example.test");
        when(pingChecker.check(eq("host.example.test"), any(), anyInt(), anyInt()))
                .thenReturn(map("up", true, "rtt_ms", 5L, "packet_loss", 0));
        PingCheckRepository repo = field("pingCheckRepo");
        ArgumentCaptor<PingCheck> c = ArgumentCaptor.forClass(PingCheck.class);

        ReflectionTestUtils.invokeMethod(scheduler, "recheckPing", m);
        verify(repo).save(c.capture());
        assertThat(c.getValue().getRecheck()).isNull();
        assertThat(c.getValue().getManual()).isNull();

        reset(repo);
        CheckRunContext.asRecheck(() -> ReflectionTestUtils.invokeMethod(scheduler, "recheckPing", m));
        verify(repo).save(c.capture());
        assertThat(c.getValue().getRecheck()).isTrue();
        assertThat(c.getValue().getUp()).isTrue();
        assertThat(CheckRunContext.recheck()).isFalse();
    }

    @Test
    @DisplayName("Port: teyit adımında recheck=TRUE, planlı taramada null")
    void portRecheckFlag() {
        PortMonitor m = new PortMonitor();
        m.setId(4L); m.setName("Port"); m.setHost("db.example.test"); m.setPort(5432);
        when(portChecker.check(m)).thenReturn(map("open", true, "response_ms", 7L));
        PortCheckRepository repo = field("portCheckRepo");
        ArgumentCaptor<PortCheck> c = ArgumentCaptor.forClass(PortCheck.class);

        ReflectionTestUtils.invokeMethod(scheduler, "recheckPort", m);
        verify(repo).save(c.capture());
        assertThat(c.getValue().getRecheck()).isNull();

        reset(repo);
        CheckRunContext.asRecheck(() -> ReflectionTestUtils.invokeMethod(scheduler, "recheckPort", m));
        verify(repo).save(c.capture());
        assertThat(c.getValue().getRecheck()).isTrue();
        assertThat(c.getValue().getOpen()).isTrue();
    }

    @Test
    @DisplayName("Durum (uptime): kurtarma adımında recheck=TRUE, planlı taramada null")
    void uptimeRecheckFlag() {
        when(uptimeChecker.check("app.example.test", 443, 10000, false))
                .thenReturn(map("via", "direct", "status", "up", "response_ms", 12L));
        UptimeCheckRepository repo = field("uptimeCheckRepo");
        ArgumentCaptor<UptimeCheck> c = ArgumentCaptor.forClass(UptimeCheck.class);

        ReflectionTestUtils.invokeMethod(scheduler, "recheckUptime", "app.example.test", 443, false);
        verify(repo).save(c.capture());
        assertThat(c.getValue().getRecheck()).isNull();

        reset(repo);
        CheckRunContext.asRecheck(() -> ReflectionTestUtils.invokeMethod(scheduler, "recheckUptime", "app.example.test", 443, false));
        verify(repo).save(c.capture());
        assertThat(c.getValue().getRecheck()).isTrue();
        assertThat(c.getValue().getStatus()).isEqualTo("up");
    }

    @Test
    @DisplayName("Sayfa Bütünlüğü: elle kontrol manual=TRUE; teyit recheck=TRUE; planlı ikisi de null")
    void pageFlags() {
        PageMonitor m = new PageMonitor();
        m.setId(6L); m.setName("Sayfa"); m.setUrl("https://site.example.test/"); m.setTeamId(1L);
        when(pageChecker.check(anyString(), anyString(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new PageCheckerService.PageCheckResult("OK", true, 200, 200L, 12, 0, 0, 0, 1, null, null, null, List.of()));
        PageCheckRepository repo = field("pageCheckRepo");
        ArgumentCaptor<PageCheck> c = ArgumentCaptor.forClass(PageCheck.class);

        ReflectionTestUtils.invokeMethod(scheduler, "recheckPage", m, false, "SINGLE_PAGE");
        verify(repo).save(c.capture());
        assertThat(c.getValue().getManual()).isNull();
        assertThat(c.getValue().getRecheck()).isNull();

        reset(repo);
        ReflectionTestUtils.invokeMethod(scheduler, "recheckPage", m, true, "SINGLE_PAGE");
        verify(repo).save(c.capture());
        assertThat(c.getValue().getManual()).isTrue();
        assertThat(c.getValue().getRecheck()).isNull();

        reset(repo);
        CheckRunContext.asRecheck(() -> ReflectionTestUtils.invokeMethod(scheduler, "recheckPage", m, false, "SINGLE_PAGE"));
        verify(repo).save(c.capture());
        assertThat(c.getValue().getManual()).isNull();
        assertThat(c.getValue().getRecheck()).isTrue();
    }
}
