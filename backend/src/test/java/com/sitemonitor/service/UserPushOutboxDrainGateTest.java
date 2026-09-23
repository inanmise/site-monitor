package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI: push outbox'ın kuyruğunu geri alan bir tetik HER ZAMAN vardır.
 *
 * <p><b>Neden kapı.</b> Sınıf javadoc'u outbox'ı "pod yeniden başlasa da PENDING satırlar durur"
 * diye anlatıyor; satırlar gerçekten duruyordu ama 2026-09-23'e kadar onları GERİ ALAN hiçbir
 * tetik yoktu. {@code drainOutbox} yalnız {@code enqueue*} yollarından ve {@code fail()}'in
 * BELLEKTEKİ {@code worker.schedule} timer'ından çağrılıyordu. Prod tek pod ve her sürüm bir
 * restart demek: backoff beklerken ya da yeni yazılmışken yeniden başlayan pod, o satırları bir
 * sonraki alarma kadar askıda bırakıyordu. Satır {@code FAILED} bile olmuyor — gönderim logunda
 * "bekliyor" görünüyor, nöbetçinin telefonunda hiçbir şey yok.
 *
 * <p><b>Neden davranış testi değil.</b> Tetikler işi tek-thread'lik {@code worker}'a atıyor;
 * "çağrıldı mı" iddiasını asenkron worker üzerinden kurmak, bu projede daha önce yanlış
 * kırmızı üretmiş bir yarış desenidir (worker başladıktan sonra yeniden stub'lama). Sözleşme
 * zaten YAPISAL: açılışta bir kez + periyodik olarak süpürülür. Kapı onu pinler; gönderim
 * davranışı {@code UserPushServiceTest}'te gerçek HTTP sunucusuyla sınanır.
 */
class UserPushOutboxDrainGateTest {

    private static Method method(String name) {
        try {
            Method m = UserPushService.class.getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new AssertionError("UserPushService." + name + "() KALDIRILMIŞ — outbox kuyruğu "
                    + "restart'tan sonra kendi kendine boşalmaz; bkz. bu testin javadoc'u", e);
        }
    }

    @Test
    @DisplayName("KAPI: açılışta outbox bir kez süpürülür (ApplicationReadyEvent)")
    void startupDrainExists() {
        EventListener ann = method("drainOnStartup").getAnnotation(EventListener.class);
        assertThat(ann)
                .as("drainOnStartup @EventListener taşımıyor — restart öncesi PENDING kalan push "
                  + "satırları bir sonraki alarma kadar askıda kalır")
                .isNotNull();
        assertThat(ann.value())
                .as("açılış drain'i ApplicationReadyEvent'e bağlı olmalı")
                .contains(ApplicationReadyEvent.class);
    }

    @Test
    @DisplayName("KAPI: outbox periyodik de süpürülür (bellekteki backoff timer'ı restart'ta kayboluyor)")
    void periodicSweepExists() {
        Scheduled ann = method("sweepOutbox").getAnnotation(Scheduled.class);
        assertThat(ann)
                .as("sweepOutbox @Scheduled taşımıyor — bir tur istisnayla düşerse ya da backoff "
                  + "timer'ı restart'la kaybolursa kuyruk kendiliğinden geri alınmaz")
                .isNotNull();
        assertThat(ann.fixedDelayString())
                .as("süpürme aralığı ayarla değiştirilebilir olmalı (sabit kodlanmış değer değil)")
                .contains("site.monitor.userpush.outbox-sweep-ms");
    }
}
