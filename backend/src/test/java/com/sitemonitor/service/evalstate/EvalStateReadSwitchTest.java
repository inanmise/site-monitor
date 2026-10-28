package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.AppSettingsCatalog;
import com.sitemonitor.service.AppSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-2 P0 (2026-10-28, kullanıcı kararı D-1): devirde okuma anahtarı {@link EvalStateReadSwitch} —
 * VARSAYILAN KAPALI, okuma hatası = KAPALI (güvenli taraf), değer CANLI (kaydedilen ayar hemen okunur). Gerçek
 * {@link AppSettingsService} (H2) + sahte servis / sağlayıcı.
 */
@DataJpaTest
@Import(AppSettingsService.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // src/test/resources/application.properties ana dosyayı gölgeler — görev havuzu çapraz kuralı için (AppSettingsServiceTest)
        "site.monitor.executor.core-size=20",
        "site.monitor.executor.max-size=50",
        "site.monitor.executor.queue-capacity=5000"
})
class EvalStateReadSwitchTest {

    @Autowired AppSettingsService settings;

    private static Map<String, Object> values(String key, Object val) {
        Map<String, Object> inner = new HashMap<>();
        inner.put(key, val);
        Map<String, Object> body = new HashMap<>();
        body.put("values", inner);
        return body;
    }

    @Test
    @DisplayName("anahtar kalıcı; varsayılan KAPALI — ayar kaydedilmemişken gerçek servis false; ana application.properties satırı "
            + "ASCII ve varsayılanı false")
    void defaultOff() throws IOException {
        assertThat(EvalStateReadSwitch.KEY).isEqualTo("site.monitor.eval-state.read-enabled");
        assertThat(EvalStateReadSwitch.DEFAULT).isFalse();
        assertThat(EvalStateReadSwitch.enabled(settings)).isFalse();

        String props = Files.readString(Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8);
        assertThat(props).contains("\nsite.monitor.eval-state.read-enabled=${EVAL_STATE_READ_ENABLED:false}\n");
    }

    @Test
    @DisplayName("canlı değer: kaydedilen true hemen açık, false kapalı, boş kayıt varsayılana (kapalı) döner; true/false dışı reddedilir")
    void liveValue() {
        settings.save(values(EvalStateReadSwitch.KEY, "true"), "admin");
        assertThat(EvalStateReadSwitch.enabled(settings)).isTrue();
        settings.save(values(EvalStateReadSwitch.KEY, "false"), "admin");
        assertThat(EvalStateReadSwitch.enabled(settings)).isFalse();
        settings.save(values(EvalStateReadSwitch.KEY, "true"), "admin");
        settings.save(values(EvalStateReadSwitch.KEY, ""), "admin");
        assertThat(EvalStateReadSwitch.enabled(settings)).as("override kaldırıldı → varsayılan").isFalse();
        assertThatThrownBy(() -> settings.save(values(EvalStateReadSwitch.KEY, "evet"), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(EvalStateReadSwitch.enabled(settings)).isFalse();

        AppSettingsCatalog.Setting s = AppSettingsCatalog.byKey(EvalStateReadSwitch.KEY);
        assertThat(s).isNotNull();
        assertThat(s.type()).isEqualTo(AppSettingsCatalog.Type.BOOL);
        assertThat(s.group()).isEqualTo("monitoring");
    }

    @Test
    @DisplayName("okuma hatası = KAPALI: servis null, getBoolean istisnası → false; servis true derse true (varsayılan argüman false)")
    void readFailureIsOff() {
        assertThat(EvalStateReadSwitch.enabled(null)).isFalse();
        AppSettingsService broken = mock(AppSettingsService.class);
        when(broken.getBoolean(anyString(), anyBoolean())).thenThrow(new IllegalStateException("ortam okunamadı"));
        assertThat(EvalStateReadSwitch.enabled(broken)).isFalse();

        AppSettingsService on = mock(AppSettingsService.class);
        when(on.getBoolean(EvalStateReadSwitch.KEY, false)).thenReturn(true);
        assertThat(EvalStateReadSwitch.enabled(on)).isTrue();
        verify(on).getBoolean(EvalStateReadSwitch.KEY, false);
    }

    @Test
    @DisplayName("tembel tedarikçi: sağlayıcı null / boş / istisna → false ve sonraki çağrı yeniden dener; çözülünce her çağrı CANLI okur")
    @SuppressWarnings("unchecked")
    void lazySupplier() {
        assertThat(EvalStateReadSwitch.supplier(null).getAsBoolean()).isFalse();

        ObjectProvider<AppSettingsService> provider = mock(ObjectProvider.class);
        AppSettingsService svc = mock(AppSettingsService.class);
        when(provider.getIfAvailable())
                .thenThrow(new org.springframework.beans.factory.BeanCreationException("henüz yok"))
                .thenReturn(null)
                .thenReturn(svc);
        when(svc.getBoolean(EvalStateReadSwitch.KEY, false)).thenReturn(true, false);
        BooleanSupplier sw = EvalStateReadSwitch.supplier(provider);
        assertThat(sw.getAsBoolean()).as("BeansException").isFalse();
        assertThat(sw.getAsBoolean()).as("henüz bean yok").isFalse();
        assertThat(sw.getAsBoolean()).as("çözüldü, açık").isTrue();
        assertThat(sw.getAsBoolean()).as("canlı: kapandı").isFalse();
        verify(provider, times(3)).getIfAvailable();

        ObjectProvider<AppSettingsService> real = mock(ObjectProvider.class);
        when(real.getIfAvailable()).thenReturn(settings);
        BooleanSupplier live = EvalStateReadSwitch.supplier(real);
        assertThat(live.getAsBoolean()).isFalse();
        settings.save(values(EvalStateReadSwitch.KEY, "true"), "admin");
        assertThat(live.getAsBoolean()).isTrue();
        settings.save(values(EvalStateReadSwitch.KEY, ""), "admin");
        assertThat(live.getAsBoolean()).isFalse();
    }
}
