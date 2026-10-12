package com.sitemonitor.service.watchdog;

import com.sitemonitor.service.AppSettingsCatalog;
import com.sitemonitor.service.mail.WatchdogMail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Kendi kendini izleme ayarları + e-posta şablonu (2026-10-11). */
class WatchdogSettingsTest {

    @Test
    @DisplayName("aralıklar, e-posta / kullanıcı adı / webhook biçimi, uzunluk sınırı")
    void validate() {
        assertThatCode(() -> WatchdogSettings.validate(WatchdogSettings.KEY_REMINDER_HOURS, "0")).doesNotThrowAnyException();
        assertThatThrownBy(() -> WatchdogSettings.validate(WatchdogSettings.KEY_REMINDER_HOURS, "200")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WatchdogSettings.validate(WatchdogSettings.KEY_CONFIRM, "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WatchdogSettings.validate(WatchdogSettings.KEY_STALE_RATIO, "abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> WatchdogSettings.validate(WatchdogSettings.KEY_RECIPIENTS, "a@example.com; b@example.com")).doesNotThrowAnyException();
        assertThatThrownBy(() -> WatchdogSettings.validate(WatchdogSettings.KEY_RECIPIENTS, "a@example.com, yanlis")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WatchdogSettings.validate(WatchdogSettings.KEY_PUSH_USERS, "N1 <script>")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WatchdogSettings.validate(WatchdogSettings.KEY_WEBHOOK_URL, "ftp://x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WatchdogSettings.validate(WatchdogSettings.KEY_RECIPIENTS, "a".repeat(20_001))).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> WatchdogSettings.validate(WatchdogSettings.KEY_RECIPIENTS, "")).doesNotThrowAnyException();
        assertThat(WatchdogSettings.split("a@x.com,,a@x.com ; b@x.com")).containsExactly("a@x.com", "b@x.com");
    }

    @Test
    @DisplayName("katalog: tüm anahtarlar 'watchdog' grubunda; alıcı / push / webhook GLOBAL_ONLY")
    void catalog() {
        for (String k : List.of(WatchdogSettings.KEY_ENABLED, WatchdogSettings.KEY_RECIPIENTS, WatchdogSettings.KEY_PUSH_USERS,
                WatchdogSettings.KEY_WEBHOOK_URL, WatchdogSettings.KEY_WEBHOOK_TYPE, WatchdogSettings.KEY_REMINDER_HOURS,
                WatchdogSettings.KEY_CONFIRM, WatchdogSettings.KEY_STALE_RATIO, WatchdogSettings.KEY_CERT_STALE_HOURS)) {
            assertThat(AppSettingsCatalog.byKey(k)).as(k).isNotNull();
            assertThat(AppSettingsCatalog.byKey(k).group()).isEqualTo("watchdog");
        }
        assertThat(AppSettingsCatalog.isGlobalOnly(WatchdogSettings.KEY_RECIPIENTS)).isTrue();
        assertThat(AppSettingsCatalog.isGlobalOnly(WatchdogSettings.KEY_WEBHOOK_URL)).isTrue();
        assertThat(AppSettingsCatalog.isGlobalOnly(WatchdogSettings.KEY_REMINDER_HOURS)).isFalse();
    }

    @Test
    @DisplayName("e-posta: konu tek sorun / düzelme / karma / test; gövde kontrol adı ve ayrıntıyı taşır, Sistem Sağlığı bağlantısı")
    void mail() {
        var alert = new WatchdogMail.Item("ALERT", "Sertifika taraması", "HIGH", "Son sonuç 6 sa önce (sınır 4 sa).");
        var rec = new WatchdogMail.Item("RECOVER", "Veritabanı", "CRITICAL", "Veritabanı durumu: UP.");
        assertThat(WatchdogMail.subject(List.of(alert), false)).isEqualTo("[Site Monitor] Uygulama sorunu: Sertifika taraması");
        assertThat(WatchdogMail.subject(List.of(rec), false)).isEqualTo("[Site Monitor] Düzeldi: Veritabanı");
        assertThat(WatchdogMail.subject(List.of(alert, rec), false)).isEqualTo("[Site Monitor] Kendi kendini izleme · 1 sorun · 1 düzeldi");
        assertThat(WatchdogMail.subject(List.of(), true)).contains("test");
        var mail = WatchdogMail.build(List.of(alert, rec), false, "https://sm.example.com/");
        assertThat(mail.html()).contains("Sertifika taraması").contains("Son sonuç 6 sa önce").contains("Düzeldi")
                .contains("https://sm.example.com/?tab=health");
        assertThat(mail.text()).contains("Veritabanı");
    }
}
