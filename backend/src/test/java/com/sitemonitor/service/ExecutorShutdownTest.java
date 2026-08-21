package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executor yaşam döngüsü kapısı (2026-08-20 bellek denetimi).
 *
 * <p>Kod tabanındaki executor'ların çoğu {@code @PreDestroy} ile kapanıyordu, ancak
 * {@code SchedulerService.manualRunPool} ve {@code IncidentNotificationService.exec} bu desenin
 * dışında kalmıştı. Üretimde etkisi küçüktür (JVM zaten ölüyor, thread'ler daemon); asıl bedel
 * TEST ve context-refresh senaryolarındadır: kapatılmayan her havuz, süit boyunca açılıp kapanan
 * onlarca Spring context'inde arkasında iş parçacığı ve o thread'lerin tuttuğu nesneleri bırakır.
 * Depodaki 15 hs_err kaydının 13'ü native bellek tükenmesi olduğuna göre, test JVM'inin ayak izini
 * büyüten her kalıntı doğrudan ilgilidir.
 */
class ExecutorShutdownTest {

    @Test
    @DisplayName("SchedulerService.manualRunPool kapanışta gerçekten shutdown edilir")
    void schedulerManualRunPool_isShutDown() throws Exception {
        SchedulerService svc = newWithNullDeps(SchedulerService.class);
        ExecutorService pool = (ExecutorService) ReflectionTestUtils.getField(svc, "manualRunPool");
        assertThat(pool).isNotNull();
        assertThat(pool.isShutdown()).isFalse();

        invoke(svc, "shutdownManualRunPool");

        assertThat(pool.isShutdown()).isTrue();
    }

    @Test
    @DisplayName("IncidentNotificationService.exec kapanışta gerçekten shutdown edilir")
    void incidentNotificationExecutor_isShutDown() throws Exception {
        IncidentNotificationService svc = newWithNullDeps(IncidentNotificationService.class);
        ExecutorService pool = (ExecutorService) ReflectionTestUtils.getField(svc, "exec");
        assertThat(pool).isNotNull();
        assertThat(pool.isShutdown()).isFalse();

        invoke(svc, "shutdownExecutor");

        assertThat(pool.isShutdown()).isTrue();
    }

    /** @PreDestroy metodu paket-özel olabilir → adıyla çağır. */
    private static void invoke(Object target, String method) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(target);
    }

    /**
     * Yapıcıyı TÜM bağımlılıklar null olacak şekilde çağırır. Lombok'un ürettiği yapıcı yalnız
     * atama yapar, hiçbir argümanı dereference etmez; alan başlatıcıları (executor'lar) normal
     * şekilde koşar. Onlarca bağımlılığı mock'lamak bu testi konusuyla ilgisiz biçimde
     * kırılganlaştırırdı — burada test edilen tek şey kapatma davranışı.
     */
    @SuppressWarnings("unchecked")
    private static <T> T newWithNullDeps(Class<T> type) throws Exception {
        Constructor<?> c = type.getDeclaredConstructors()[0];
        c.setAccessible(true);
        return (T) c.newInstance(new Object[c.getParameterCount()]);
    }
}
