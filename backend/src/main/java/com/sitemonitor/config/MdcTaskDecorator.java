package com.sitemonitor.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Günlük bağlamını (MDC: {@code requestId}, izleme açıksa {@code traceId}/{@code spanId}) işi gönderen iş parçacığından
 * havuz iş parçacığına taşır (2026-10-11). "Şimdi kontrol et" gibi bir isteğin havuza düşen işleri de aynı
 * {@code rid=} ile günlüğe yazar. İş bitince havuz iş parçacığının ÖNCEKİ bağlamı geri konur — sızıntı yok. Gönderen
 * tarafta bağlam yoksa (zamanlayıcı süpürmesi) havuz iş parçacığı boş bağlamla çalışır.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (captured == null || captured.isEmpty()) MDC.clear();
            else MDC.setContextMap(captured);
            try {
                runnable.run();
            } finally {
                if (previous == null || previous.isEmpty()) MDC.clear();
                else MDC.setContextMap(previous);
            }
        };
    }
}
