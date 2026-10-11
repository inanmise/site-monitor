package com.sitemonitor.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Günlük bağlamı aktarımı (2026-10-11): gönderenin MDC'si işte görünür; iş bitince çalışan iş parçacığının bağlamı geri gelir. */
class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    @DisplayName("gönderim anındaki istek kimliği işte görünür; iş sonrası çalışanın kendi bağlamı geri konur")
    void propagatesAndRestores() {
        MDC.put("requestId", "rid-1");
        String[] seen = new String[1];
        Runnable task = decorator.decorate(() -> seen[0] = MDC.get("requestId"));
        MDC.put("requestId", "calisanin-kendisi");   // çalışan iş parçacığının o anki bağlamı
        task.run();
        assertThat(seen[0]).isEqualTo("rid-1");
        assertThat(MDC.get("requestId")).isEqualTo("calisanin-kendisi");
    }

    @Test
    @DisplayName("bağlamsız gönderim (zamanlayıcı) çalışanda kalmış eski kimliği GÖRMEZ; sonra eski bağlam geri gelir")
    void emptyContextDoesNotInheritStale() {
        String[] seen = {"x"};
        Runnable task = decorator.decorate(() -> seen[0] = MDC.get("requestId"));
        MDC.put("requestId", "eski");
        task.run();
        assertThat(seen[0]).isNull();
        assertThat(MDC.get("requestId")).isEqualTo("eski");
    }

    @Test
    @DisplayName("gerçek havuz iş parçacığında: kimlik geçer, iş sonrası havuz iş parçacığı temiz kalır")
    void onPoolThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            MDC.put("requestId", "rid-havuz");
            String[] seen = new String[2];
            pool.submit(decorator.decorate(() -> seen[0] = MDC.get("requestId"))).get(5, TimeUnit.SECONDS);
            pool.submit(() -> { seen[1] = MDC.get("requestId"); }).get(5, TimeUnit.SECONDS);
            assertThat(seen[0]).isEqualTo("rid-havuz");
            assertThat(seen[1]).as("süslenmemiş sonraki iş önceki kimliği görmez").isNull();
        } finally {
            pool.shutdownNow();
        }
    }
}
