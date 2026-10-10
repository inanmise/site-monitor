package com.sitemonitor.service.report.executive;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.AppSettingsCatalog;
import com.sitemonitor.service.AppSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Ayarlar — varsayılanlar (KAPALI), doğrulama doğruluk tablosu, GLOBAL_ONLY kapısı, tek kayıtta yazım. */
class ExecutiveSummarySettingsTest {

    private static ExecutiveSummarySettings defaults(AppSettingsService app) {
        when(app.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(app.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(app.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        when(app.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        return new ExecutiveSummarySettings(app);
    }

    @Test
    @DisplayName("varsayılanlar: KAPALI (opt-in), her ayın 1'i 09:00, global yöneticiler dahil, %99,9, 30 gün")
    void defaults() {
        ExecutiveSummarySettings s = defaults(mock(AppSettingsService.class));
        assertThat(s.enabled()).isFalse();
        assertThat(s.cron()).isEqualTo("0 0 9 1 * *");
        assertThat(s.includeGlobalAdmins()).isTrue();
        assertThat(s.availabilityTarget()).isEqualTo(99.9);
        assertThat(s.renewalTargetDays()).isEqualTo(30);
        assertThat(s.explicitRecipients()).isEmpty();
    }

    @Test
    @DisplayName("bütün anahtarlar katalogda VE GLOBAL_ONLY'de (müdür kurum geneli raporun alıcısını/hedefini değiştiremez)")
    void globalOnly() {
        for (String k : ExecutiveSummarySettings.KEYS) {
            assertThat(AppSettingsCatalog.byKey(k)).as(k).isNotNull();
            assertThat(AppSettingsCatalog.GLOBAL_ONLY).as(k).contains(k);
            assertThat(AppSettingsCatalog.byKey(k).group()).isEqualTo("executive-summary");
        }
    }

    @Test
    @DisplayName("doğrulama: cron, adres, hedef 90–100, gün 0–365; boş = varsayılana dön")
    void validation() {
        assertThatCode(() -> ExecutiveSummarySettings.validate(ExecutiveSummarySettings.CRON_KEY, "0 0 9 1 * *")).doesNotThrowAnyException();
        assertThatCode(() -> ExecutiveSummarySettings.validate(ExecutiveSummarySettings.CRON_KEY, "")).doesNotThrowAnyException();
        assertThatThrownBy(() -> ExecutiveSummarySettings.validate(ExecutiveSummarySettings.CRON_KEY, "her ay"))
                .isInstanceOf(FieldValidationException.class);
        assertThatCode(() -> ExecutiveSummarySettings.validate(ExecutiveSummarySettings.RECIPIENTS_KEY, "a@x.com; b@y.org")).doesNotThrowAnyException();
        assertThatThrownBy(() -> ExecutiveSummarySettings.validate(ExecutiveSummarySettings.RECIPIENTS_KEY, "a@x.com, kotu"))
                .isInstanceOf(FieldValidationException.class).hasMessageContaining("kotu");
        for (String ok : new String[]{ "90", "99.9", "99,95", "100" }) {
            assertThatCode(() -> ExecutiveSummarySettings.validate(ExecutiveSummarySettings.TARGET_KEY, ok)).as(ok).doesNotThrowAnyException();
        }
        for (String bad : new String[]{ "89.99", "100.01", "yüksek" }) {
            assertThatThrownBy(() -> ExecutiveSummarySettings.validate(ExecutiveSummarySettings.TARGET_KEY, bad)).as(bad)
                    .isInstanceOf(FieldValidationException.class);
        }
        for (String ok : new String[]{ "0", "30", "365" }) {
            assertThatCode(() -> ExecutiveSummarySettings.validate(ExecutiveSummarySettings.RENEWAL_TARGET_KEY, ok)).doesNotThrowAnyException();
        }
        for (String bad : new String[]{ "-1", "366", "otuz" }) {
            assertThatThrownBy(() -> ExecutiveSummarySettings.validate(ExecutiveSummarySettings.RENEWAL_TARGET_KEY, bad))
                    .isInstanceOf(FieldValidationException.class);
        }
    }

    @Test
    @DisplayName("save: yalnız gövdedeki alanlar, TEK kayıt; adresler normalize; hatalı alan yazmadan 400")
    @SuppressWarnings("unchecked")
    void save() {
        AppSettingsService app = mock(AppSettingsService.class);
        ExecutiveSummarySettings s = defaults(app);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", true);
        body.put("recipients", "a@x.com;A@x.com  b@y.org");
        body.put("availability_target", "99,5");
        s.save(body, "admin");
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(app, times(1)).save(cap.capture(), eq("admin"));
        Map<String, Object> values = (Map<String, Object>) cap.getValue().get("values");
        assertThat(values).containsOnlyKeys(ExecutiveSummarySettings.ENABLED_KEY, ExecutiveSummarySettings.RECIPIENTS_KEY,
                ExecutiveSummarySettings.TARGET_KEY);
        assertThat(values.get(ExecutiveSummarySettings.RECIPIENTS_KEY)).isEqualTo("a@x.com, b@y.org");
        assertThat(values.get(ExecutiveSummarySettings.TARGET_KEY)).isEqualTo("99.5");

        assertThatThrownBy(() -> s.save(Map.of("renewal_target_days", "999"), "admin"))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("renewal_target_days"));
        verify(app, times(1)).save(any(), anyString());
    }
}
