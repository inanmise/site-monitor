package com.sitemonitor.service.schema;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.function.ToIntFunction;

/**
 * Açılış şema yamalarının sonucu Prometheus'ta (2026-10-01, onaylı öneri 4): {@code sitemonitor.schema.patch.failed}
 * sıfırdan büyükse son açılışta en az bir yama gerçekten başarısız olmuştur (ayrıntı pod günlüğünde WARN olarak).
 * {@code applied} gerçek değişiklik sayısı, {@code noop} zaten uygulanmış yamalar, {@code locked} 1 ise yamalar
 * advisory lock altında koştu. Veri {@link SchemaPatchRunner#last()}'ten okunur; açılış bitmeden 0'dır.
 */
@Component
public class SchemaPatchMetrics {

    public SchemaPatchMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) return;
        gauge(registry, "sitemonitor.schema.patch.failed", "Son açılışta başarısız olan şema yaması sayısı",
                SchemaPatchRunner.Summary::failed);
        gauge(registry, "sitemonitor.schema.patch.applied", "Son açılışta gerçek değişiklik yapan şema yaması sayısı",
                SchemaPatchRunner.Summary::applied);
        gauge(registry, "sitemonitor.schema.patch.noop", "Son açılışta zaten uygulanmış (değişiklik yapmayan) yama sayısı",
                SchemaPatchRunner.Summary::noop);
        gauge(registry, "sitemonitor.schema.patch.locked", "Yamalar advisory lock altında koştuysa 1",
                s -> s.locked() ? 1 : 0);
    }

    private static void gauge(MeterRegistry registry, String name, String description,
                              ToIntFunction<SchemaPatchRunner.Summary> fn) {
        Gauge.builder(name, () -> {
                    SchemaPatchRunner.Summary s = SchemaPatchRunner.last();
                    return s == null ? 0 : fn.applyAsInt(s);
                })
                .description(description)
                .register(registry);
    }
}
