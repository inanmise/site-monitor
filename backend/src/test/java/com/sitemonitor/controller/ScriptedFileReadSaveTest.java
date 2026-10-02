package com.sitemonitor.controller;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.ScriptedCheckerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Sentetik izleme KAYDI ve dosya okuma politikası (2026-10-01, onaylı öneri 1):
 * REPORT (varsayılan) kaydı engellemez, yanıta uyarı ekler; BLOCK dosya okuyan betiğin kaydını reddeder. Dosya
 * okumayan betikte iki modda da hiçbir şey değişmez. Sabit-kodlu sır taraması aynen sürer.
 */
class ScriptedFileReadSaveTest {

    private MonitoringController controller(String policy) {
        MonitoringController c = mock(MonitoringController.class, CALLS_REAL_METHODS);
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        ScriptedCheckerService checker = mock(ScriptedCheckerService.class);
        when(checker.fileReadBlocking()).thenReturn("BLOCK".equals(policy));
        ReflectionTestUtils.setField(c, "appSettings", settings);
        ReflectionTestUtils.setField(c, "scriptedChecker", checker);
        return c;
    }

    private static final String READS = "const users = JSON.parse(open('./users.json'));";
    private static final String CLEAN = "import http from 'k6/http'; export default () => http.get('https://x');";

    @Test
    @DisplayName("BLOCK: dosya okuyan betiğin kaydı reddedilir; temiz betik geçer")
    void block_rejectsSave() {
        var c = controller("BLOCK");
        String err = ReflectionTestUtils.invokeMethod(c, "scanScriptOrError", READS);
        assertThat(err).contains("dosya okuyor").contains("open()");
        assertThat((String) ReflectionTestUtils.invokeMethod(c, "scanScriptOrError", CLEAN)).isNull();
    }

    @Test
    @DisplayName("REPORT: kayıt engellenmez; yanıttaki uyarılara dosya okuma notu eklenir, mevcut uyarılar korunur")
    void report_warnsButSaves() {
        var c = controller("REPORT");
        assertThat((String) ReflectionTestUtils.invokeMethod(c, "scanScriptOrError", READS)).isNull();
        List<String> w = ReflectionTestUtils.invokeMethod(c, "withFileReadWarning", READS, List.of("önceki uyarı"));
        assertThat(w).hasSize(2).first().isEqualTo("önceki uyarı");
        assertThat(w.get(1)).contains("dosya okuyor").contains("__ENV");
        List<String> none = ReflectionTestUtils.invokeMethod(c, "withFileReadWarning", CLEAN, List.of());
        assertThat(none).isEmpty();
        assertThat((List<String>) ReflectionTestUtils.invokeMethod(c, "withFileReadWarning", null, List.of())).isEmpty();
    }
}
