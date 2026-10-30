package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.K6CompatScan;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.K6EngineSettings;
import com.sitemonitor.service.K6Profile;
import com.sitemonitor.service.ScriptedCheckerService;
import com.sitemonitor.service.k6engine.K6CompatScanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ayarlar → k6 motoru uçları (2026-10-29): yalnız genel yönetici; yalnız değişen alan; geçiş / geri dönüş. */
class K6EngineControllerTest {

    private ScriptedCheckerService checker;
    private K6CompatScanService scans;
    private AppSettingsService settings;
    private AuditService audit;
    private K6EngineController ctl;
    private final Map<String, String> stored = new HashMap<>();
    /** Desteklenen eski bir 2.x (geri dönüş senaryoları için); 0.49 2026-10-30'dan beri "desteklenmeyen sürüm". */
    private final K6Profile v220 = K6Profile.fromVersionOutput("k6", "k6 v2.2.0", 1L);
    private final K6Profile v049 = K6Profile.fromVersionOutput("/usr/local/bin/k6-0.49", "k6 v0.49.0", 1L);
    private final K6Profile v230 = K6Profile.fromVersionOutput("/opt/k6-next/k6", "k6 v2.3.0", 1L);

    @BeforeEach
    void setUp() {
        checker = mock(ScriptedCheckerService.class);
        scans = mock(K6CompatScanService.class);
        settings = mock(AppSettingsService.class);
        audit = mock(AuditService.class);
        ScriptedMonitorRepository monitors = mock(ScriptedMonitorRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        when(settings.getString(anyString(), anyString())).thenAnswer(i -> stored.getOrDefault(i.getArgument(0), i.getArgument(1)));
        when(settings.getBoolean(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        when(monitors.findAllByOrderByNameAsc()).thenReturn(List.of());
        when(teams.findAll()).thenReturn(List.of());
        when(scans.recent()).thenReturn(List.of());
        when(checker.profiles()).thenReturn(new ScriptedCheckerService.Profiles(v220, null, v230));
        ctl = new K6EngineController(checker, scans, settings, audit, monitors, teams);
    }

    static MockHttpSession session(boolean global) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "root");
        s.setAttribute("systemRole", "ADMIN");
        if (!global) s.setAttribute("viewTeamIds", new ArrayList<>(List.of(7L)));
        return s;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(org.springframework.http.ResponseEntity<Map<String, Object>> r) {
        return (Map<String, Object>) r.getBody().get("data");
    }

    @Test
    @DisplayName("Kapsamlı müdür (AD ADMIN, takım kapsamlı) hiçbir uca erişemez — 403 (SecurityException)")
    void scopedAdminForbidden() {
        MockHttpSession s = session(false);
        assertThatThrownBy(() -> ctl.status(s)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> ctl.save(Map.of("godebug", "tlsrsakex=1"), s, new MockHttpServletRequest()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> ctl.switchToCandidate(Map.of("confirm", true), s, new MockHttpServletRequest()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> ctl.startScan(Map.of(), s, new MockHttpServletRequest())).isInstanceOf(SecurityException.class);
        verify(settings, never()).save(any(), anyString());
    }

    @Test
    @DisplayName("Durum: motorlar, desteklenen en eski sürüm, izinli GODEBUG anahtarları, geçiş düğmesi")
    void statusView() {
        Map<String, Object> d = data(ctl.status(session(true)));
        assertThat(d).containsKeys("pod", "engines", "settings", "godebug_keys", "monitors", "scan");
        @SuppressWarnings("unchecked") Map<String, Object> eng = (Map<String, Object>) d.get("engines");
        assertThat(eng.get("rollback")).isNull();
        assertThat(((Map<?, ?>) eng.get("candidate")).get("version")).isEqualTo("v2.3.0");
        assertThat(((Map<?, ?>) eng.get("candidate")).get("unsupported")).isEqualTo(false);
        assertThat(((Map<?, ?>) eng.get("candidate")).containsKey("api_address")).isFalse();
        assertThat(((Map<?, ?>) eng.get("candidate")).containsKey("modern_syntax")).isFalse();
        assertThat(d).doesNotContainKey("syntax_version");
        assertThat(d.get("min_version")).isEqualTo("v2.0.0");
        assertThat(d.get("can_switch")).isEqualTo(true);
        assertThat(d.get("can_rollback")).isEqualTo(false);
        assertThat((List<?>) d.get("godebug_keys")).hasSize(K6EngineSettings.godebugKeys().size());
    }

    @Test
    @DisplayName("Ayar kaydı: yalnız gönderilen + değişen alan yazılır; GODEBUG normalize; değişiklik yoksa yazma yok")
    @SuppressWarnings("unchecked")
    void saveOnlyChanged() {
        stored.put(K6EngineSettings.KEY_USER_AGENT, "SM");
        ctl.save(Map.of("user_agent", "SM", "godebug", " TLSRSAKEX=1 "), session(true), new MockHttpServletRequest());
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(settings).save(cap.capture(), eq("root"));
        Map<String, Object> values = (Map<String, Object>) cap.getValue().get("values");
        assertThat(values).containsOnlyKeys(K6EngineSettings.KEY_GODEBUG);
        assertThat(values.get(K6EngineSettings.KEY_GODEBUG)).isEqualTo("tlsrsakex=1");
        verify(audit).recordAction(eq("K6_ENGINE_SETTINGS_SAVE"), any(HttpSession.class), any(HttpServletRequest.class), anyString(), anyString(), anyString(), any());

        org.mockito.Mockito.clearInvocations(settings);
        ctl.save(Map.of("user_agent", "SM"), session(true), new MockHttpServletRequest());
        verify(settings, never()).save(any(), anyString());
    }

    @Test
    @DisplayName("Boşaltılan aday yolu 'none' yazılır (ortam varsayılanı geri gelmez); ekranda boş görünür (inceleme L1)")
    @SuppressWarnings("unchecked")
    void clearedFieldIsStoredAsOff() {
        stored.put(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY, "/opt/k6-next/k6");
        ctl.save(Map.of("candidate_bin", ""), session(true), new MockHttpServletRequest());
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(settings).save(cap.capture(), eq("root"));
        Map<String, Object> values = (Map<String, Object>) cap.getValue().get("values");
        assertThat(values).containsEntry(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY, K6EngineSettings.OFF);

        stored.put(ScriptedCheckerService.K6_BIN_CANDIDATE_KEY, K6EngineSettings.OFF);
        Map<String, Object> s = (Map<String, Object>) data(ctl.status(session(true))).get("settings");
        assertThat(s.get("candidate_bin")).isEqualTo("");
        org.mockito.Mockito.clearInvocations(settings);
        ctl.save(Map.of("candidate_bin", ""), session(true), new MockHttpServletRequest());
        verify(settings, never()).save(any(), anyString());   // zaten kapalı: değişiklik yok
    }

    @Test
    @DisplayName("Başka sürüm koşan canlı pod varken geçiş reddedilir; pod sürümleri okunamazsa da (inceleme M2)")
    void switchRefusedWhileVersionsMixed() {
        com.sitemonitor.service.DeploymentHistoryService history = mock(com.sitemonitor.service.DeploymentHistoryService.class);
        ctl.setHistory(history);
        when(history.liveOtherVersions()).thenReturn(List.of(Map.of("pod", "site-monitor-old-1", "version", "20.171.0")));
        assertThat((List<?>) data(ctl.status(session(true))).get("other_version_pods")).hasSize(1);
        assertThatThrownBy(() -> ctl.switchToCandidate(Map.of("confirm", true), session(true), new MockHttpServletRequest()))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("K6_MIXED_VERSIONS");
                    assertThat(e.getMessage()).contains("site-monitor-old-1").contains("20.171.0");
                });
        when(history.liveOtherVersions()).thenReturn(null);
        assertThatThrownBy(() -> ctl.switchToCandidate(Map.of("confirm", true), session(true), new MockHttpServletRequest()))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> assertThat(e.getCode()).isEqualTo("K6_POD_VERSIONS_UNKNOWN"));
        verify(settings, never()).save(any(), anyString());
        when(history.liveOtherVersions()).thenReturn(List.of());
        ctl.switchToCandidate(Map.of("confirm", true), session(true), new MockHttpServletRequest());
        verify(settings).save(any(), eq("root"));
    }

    @Test
    @DisplayName("Ayar kaydı: geçersiz GODEBUG alan hatası (godebug) — hiçbir şey yazılmaz")
    void saveRejectsBadGodebug() {
        assertThatThrownBy(() -> ctl.save(Map.of("godebug", "gctrace=1"), session(true), new MockHttpServletRequest()))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("godebug"));
        verify(settings, never()).save(any(), anyString());
    }

    @Test
    @DisplayName("Tarama dışı izlemeler dizi olarak gelir, CSV'ye çevrilir")
    @SuppressWarnings("unchecked")
    void excludedIdsArray() {
        ctl.save(Map.of("excluded_ids", List.of(5, "9", 5)), session(true), new MockHttpServletRequest());
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(settings).save(cap.capture(), eq("root"));
        assertThat(((Map<String, Object>) cap.getValue().get("values")).get(K6EngineSettings.KEY_SCAN_EXCLUDED)).isEqualTo("5,9");
    }

    @Test
    @DisplayName("Adayı etkin yap: onay ister; k6-bin = aday, yedek = eski etkin; denetim kaydı")
    @SuppressWarnings("unchecked")
    void switchWritesBothKeys() {
        assertThatThrownBy(() -> ctl.switchToCandidate(Map.of(), session(true), new MockHttpServletRequest()))
                .isInstanceOf(FieldValidationException.class);
        ctl.switchToCandidate(Map.of("confirm", true), session(true), new MockHttpServletRequest());
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(settings).save(cap.capture(), eq("root"));
        Map<String, Object> values = (Map<String, Object>) cap.getValue().get("values");
        assertThat(values).containsEntry(ScriptedCheckerService.K6_BIN_KEY, "/opt/k6-next/k6")
                .containsEntry(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY, "k6");
        verify(audit).recordAction(eq("K6_ENGINE_SWITCH"), any(HttpSession.class), any(HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Adayı etkin yap: aday çalışmıyor / zaten etkin / tarama sürüyor → 409, yazma yok")
    void switchConflicts() {
        when(checker.profiles()).thenReturn(new ScriptedCheckerService.Profiles(v220, null,
                K6Profile.unavailable("/opt/k6-next/k6", 1L)));
        assertThatThrownBy(() -> ctl.switchToCandidate(Map.of("confirm", true), session(true), new MockHttpServletRequest()))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> assertThat(e.getCode()).isEqualTo("K6_CANDIDATE_UNAVAILABLE"));
        when(checker.profiles()).thenReturn(new ScriptedCheckerService.Profiles(v230, v220, v230));
        assertThatThrownBy(() -> ctl.switchToCandidate(Map.of("confirm", true), session(true), new MockHttpServletRequest()))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> assertThat(e.getCode()).isEqualTo("K6_ALREADY_ACTIVE"));
        K6CompatScan open = new K6CompatScan();
        open.setState("RUNNING");
        when(scans.recent()).thenReturn(List.of(open));
        assertThatThrownBy(() -> ctl.switchToCandidate(Map.of("confirm", true), session(true), new MockHttpServletRequest()))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> assertThat(e.getCode()).isEqualTo("K6_SCAN_RUNNING"));
        verify(settings, never()).save(any(), anyString());
    }

    @Test
    @DisplayName("Yedeğe dön: k6-bin = yedek, yedek ayarı temizlenir; yedek yoksa 409")
    @SuppressWarnings("unchecked")
    void rollback() {
        assertThatThrownBy(() -> ctl.rollback(Map.of("confirm", true), session(true), new MockHttpServletRequest()))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> assertThat(e.getCode()).isEqualTo("K6_ROLLBACK_UNAVAILABLE"));
        when(checker.profiles()).thenReturn(new ScriptedCheckerService.Profiles(v230, v220, v230));
        ctl.rollback(Map.of("confirm", true), session(true), new MockHttpServletRequest());
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(settings).save(cap.capture(), eq("root"));
        Map<String, Object> values = (Map<String, Object>) cap.getValue().get("values");
        assertThat(values).containsEntry(ScriptedCheckerService.K6_BIN_KEY, "k6")
                .containsEntry(ScriptedCheckerService.K6_BIN_ROLLBACK_KEY, K6EngineSettings.OFF);
        verify(audit).recordAction(eq("K6_ENGINE_ROLLBACK"), any(HttpSession.class), any(HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("2.0'dan eski ikili (ör. 0.49): durumda 'desteklenmiyor'; geçiş ve geri dönüş sürümü adlandırarak reddedilir")
    void unsupportedEnginesRefused() {
        when(checker.profiles()).thenReturn(new ScriptedCheckerService.Profiles(v230, v049, v049));
        Map<String, Object> d = data(ctl.status(session(true)));
        @SuppressWarnings("unchecked") Map<String, Object> eng = (Map<String, Object>) d.get("engines");
        assertThat(((Map<?, ?>) eng.get("rollback")).get("unsupported")).isEqualTo(true);
        assertThat(((Map<?, ?>) eng.get("rollback")).get("available")).isEqualTo(false);
        assertThat(((Map<?, ?>) eng.get("rollback")).get("version")).isEqualTo("v0.49.0");
        assertThat(d.get("can_switch")).isEqualTo(false);
        assertThat(d.get("can_rollback")).isEqualTo(false);
        assertThatThrownBy(() -> ctl.switchToCandidate(Map.of("confirm", true), session(true), new MockHttpServletRequest()))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("K6_CANDIDATE_UNAVAILABLE");
                    assertThat(e.getMessage()).contains("v0.49.0").contains("v2.0.0");
                });
        assertThatThrownBy(() -> ctl.rollback(Map.of("confirm", true), session(true), new MockHttpServletRequest()))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("K6_ROLLBACK_UNAVAILABLE");
                    assertThat(e.getMessage()).contains("v0.49.0").contains("v2.0.0");
                });
        verify(settings, never()).save(any(), anyString());
    }

    @Test
    @DisplayName("Tarama başlat: seçili kimlikler servise geçer; harfli kimlik alan hatası")
    void startScan() {
        K6CompatScan s = new K6CompatScan();
        s.setId(11L);
        s.setScope("SELECTED");
        s.setTotal(1);
        when(scans.start(eq(List.of(4L)), eq("root"), anyString())).thenReturn(s);
        Map<String, Object> d = data(ctl.startScan(Map.of("monitor_ids", List.of(4)), session(true), new MockHttpServletRequest()));
        assertThat(d.get("id")).isEqualTo(11L);
        verify(audit).recordAction(eq("K6_COMPAT_SCAN_START"), any(HttpSession.class), any(HttpServletRequest.class), anyString(), eq("k6-compat-scan:11"), anyString());
        assertThatThrownBy(() -> ctl.startScan(Map.of("monitor_ids", List.of("x")), session(true), new MockHttpServletRequest()))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    @DisplayName("Örnek kimliğinden pod adı")
    void podOf() {
        assertThat(K6EngineController.podOf("site-monitor-7d9f-ab12cd34")).isEqualTo("site-monitor-7d9f");
        assertThat(K6EngineController.podOf(null)).isNull();
    }
}
