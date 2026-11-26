package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.oncallroutine.OnCallRoutineSettings.Settings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Nöbet rutini takım ayarları (2026-11-26): varsayılanlar, kısmi uygulama, alan adlı doğrulama, esnek okuma. */
class OnCallRoutineSettingsTest {

    static String fieldOf(Throwable e) {
        return ((FieldValidationException) e).getFields().keySet().iterator().next();
    }

    @Test
    @DisplayName("varsayılanlar: Pzt–Cum, tatil atlanır, 09:00, 16:30 + 17:30, bildirimler açık")
    void defaults() {
        Settings d = OnCallRoutineSettings.defaults();
        assertThat(d.enabled()).isTrue();
        assertThat(d.workdays()).containsExactly(1, 2, 3, 4, 5);
        assertThat(d.skipHolidays()).isTrue();
        assertThat(d.dayStart()).isEqualTo("09:00");
        assertThat(d.reminders().times()).containsExactly("16:30", "17:30");
        assertThat(d.completionNotice().mail()).isTrue();
        assertThat(d.handoverNotice().push()).isTrue();
        assertThat(OnCallRoutineSettings.read(OnCallRoutineSettings.toJson(d))).isEqualTo(d);
    }

    @Test
    @DisplayName("kısmi uygulama: yalnız gönderilen anahtarlar değişir; iç içe kısmi nesne")
    void partialApply() {
        Settings d = OnCallRoutineSettings.defaults();
        Map<String, Object> patch = new HashMap<>();
        patch.put("day_start", "08:00");
        patch.put("reminders", Map.of("times", List.of("17:00")));
        Settings s = OnCallRoutineSettings.apply(d, patch);
        assertThat(s.dayStart()).isEqualTo("08:00");
        assertThat(s.reminders().times()).containsExactly("17:00");
        assertThat(s.reminders().enabled()).isTrue();
        assertThat(s.workdays()).isEqualTo(d.workdays());
        assertThat(s.completionNotice()).isEqualTo(d.completionNotice());
        // boş yama = saklanan
        assertThat(OnCallRoutineSettings.apply(d, Map.of())).isSameAs(d);
        // saatler sıralanır ve tekrar elenir mi? tekrarlı liste katı kayıtta reddedilir
        assertThatThrownBy(() -> OnCallRoutineSettings.apply(d, Map.of("reminders", Map.of("times", List.of("17:00", "17:00")))))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("settings.reminders.times"));
    }

    @Test
    @DisplayName("doğrulama alanları")
    void validation() {
        Settings d = OnCallRoutineSettings.defaults();
        assertThatThrownBy(() -> OnCallRoutineSettings.apply(d, Map.of("workdays", List.of())))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("settings.workdays"));
        assertThatThrownBy(() -> OnCallRoutineSettings.apply(d, Map.of("workdays", List.of(1, 1))))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("settings.workdays"));
        assertThatThrownBy(() -> OnCallRoutineSettings.apply(d, Map.of("workdays", List.of(0, 1))))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("settings.workdays"));
        assertThatThrownBy(() -> OnCallRoutineSettings.apply(d, Map.of("day_start", "9:00")))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("settings.day_start"));
        assertThatThrownBy(() -> OnCallRoutineSettings.apply(d, Map.of("enabled", "true")))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("settings.enabled"));
        assertThatThrownBy(() -> OnCallRoutineSettings.apply(d, Map.of("reminders",
                Map.of("times", List.of("10:00", "11:00", "12:00", "13:00", "14:00")))))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("settings.reminders.times"));
        assertThatThrownBy(() -> OnCallRoutineSettings.apply(d, Map.of("completion_notice",
                Map.of("enabled", true, "mail", false, "push", false))))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("settings.completion_notice.mail"));
        assertThatThrownBy(() -> OnCallRoutineSettings.apply(d, Map.of("handover_notice", "yes")))
                .satisfies(e -> assertThat(fieldOf(e)).isEqualTo("settings.handover_notice"));
        // kapalı bildirim kanalsız olabilir
        Settings off = OnCallRoutineSettings.apply(d, Map.of("completion_notice", Map.of("enabled", false, "mail", false, "push", false)));
        assertThat(off.completionNotice().enabled()).isFalse();
        // saklananla aynı değer yeniden doğrulanmaz (sıra farkı da aynı küme)
        assertThat(OnCallRoutineSettings.apply(d, Map.of("workdays", List.of(1, 2, 3, 4, 5)))).isEqualTo(d);
    }

    @Test
    @DisplayName("esnek okuma: bozuk / geçersiz değer → varsayılan; açık ama kanalsız bildirim → varsayılan kanallar")
    void lenientRead() {
        assertThat(OnCallRoutineSettings.read("{bozuk")).isEqualTo(OnCallRoutineSettings.defaults());
        assertThat(OnCallRoutineSettings.read("[]")).isEqualTo(OnCallRoutineSettings.defaults());
        Settings s = OnCallRoutineSettings.read("{\"workdays\":[9],\"day_start\":\"x\",\"enabled\":false,"
                + "\"completion_notice\":{\"enabled\":true,\"mail\":false,\"push\":false}}");
        assertThat(s.enabled()).isFalse();
        assertThat(s.workdays()).containsExactly(1, 2, 3, 4, 5);
        assertThat(s.dayStart()).isEqualTo("09:00");
        assertThat(s.completionNotice().mail()).isTrue();
        assertThat(s.completionNotice().push()).isTrue();
        assertThat(s.dayStartTime().getHour()).isEqualTo(9);
    }
}
