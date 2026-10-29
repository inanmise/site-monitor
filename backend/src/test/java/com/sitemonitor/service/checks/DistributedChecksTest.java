package com.sitemonitor.service.checks;

import com.sitemonitor.service.AppSettingsChangedEvent;
import com.sitemonitor.service.AppSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-3 P0 — ana anahtar ve tür listesi ({@link DistributedChecks}): VARSAYILAN KAPALI, okuma hatası = KAPALI,
 * tür listesi hoşgörülü + kanonik; açılışta okunan pod ayarları sınırlı; tembel başlatıcılar YALNIZ anahtar ilk kez açık
 * görüldüğünde, bir kez, kapanıştan önce çalışır (K-1: kapalıyken hiçbiri).
 */
class DistributedChecksTest {

    /** Değeri testin değiştirdiği ayar servisi (bellek). */
    static final class Settings {
        volatile String enabled;
        volatile String types;
        volatile RuntimeException fail;
        final AppSettingsService svc = mock(AppSettingsService.class);

        Settings() {
            when(svc.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> {
                if (fail != null) throw fail;
                if (DistributedChecks.KEY_ENABLED.equals(i.getArgument(0)) && enabled != null) {
                    return Boolean.parseBoolean(enabled);
                }
                return i.getArgument(1);
            });
            when(svc.getString(anyString(), anyString())).thenAnswer(i -> {
                if (fail != null) throw fail;
                if (DistributedChecks.KEY_TYPES.equals(i.getArgument(0)) && types != null) return types;
                return i.getArgument(1);
            });
        }
    }

    private static AppSettingsChangedEvent changed(String... keys) {
        return new AppSettingsChangedEvent(Set.of(keys), "save");
    }

    @Test
    @DisplayName("varsayılan KAPALI: ayar yok → enabled false, türler sekizi (anahtardan bağımsız), anyDistributed / distributedType false")
    void defaultOff() {
        Settings s = new Settings();
        DistributedChecks dc = new DistributedChecks(s.svc, null);
        assertThat(DistributedChecks.DEFAULT_ENABLED).isFalse();
        assertThat(dc.enabled()).isFalse();
        assertThat(dc.types()).containsExactlyElementsOf(CheckJobTypes.ALL);
        assertThat(dc.anyDistributed()).isFalse();
        for (String t : CheckJobTypes.ALL) assertThat(dc.distributedType(t)).as(t).isFalse();
        assertThat(dc.tuning()).isEqualTo(DistributedChecks.Tuning.DEFAULTS);
    }

    @Test
    @DisplayName("açık + tür listesi: yalnız seçili türler; bilinmeyen tür / liderde kalan tür hiç dağıtılmaz; boş liste → anyDistributed false")
    void enabledAndTypes() {
        Settings s = new Settings();
        s.enabled = "true";
        s.types = " ping, PORT ,domain";
        DistributedChecks dc = new DistributedChecks(s.svc, null);
        assertThat(dc.enabled()).isTrue();
        assertThat(dc.types()).containsExactly("port", "ping");
        assertThat(dc.anyDistributed()).isTrue();
        assertThat(dc.distributedType("port")).isTrue();
        assertThat(dc.distributedType("ping")).isTrue();
        assertThat(dc.distributedType("http")).isFalse();
        assertThat(dc.distributedType("domain")).isFalse();
        assertThat(dc.distributedType(null)).isFalse();

        s.types = "dns";                     // canlı: metin değişince yeniden çözülür
        assertThat(dc.types()).containsExactly("dns");
        s.types = "uptime";
        assertThat(dc.types()).isEmpty();
        assertThat(dc.anyDistributed()).as("anahtar açık ama tür yok").isFalse();
        s.enabled = "false";
        s.types = "port";
        assertThat(dc.anyDistributed()).isFalse();
        assertThat(dc.distributedType("port")).isFalse();
    }

    @Test
    @DisplayName("okuma hatası = KAPALI: servis null / istisna / sağlayıcı istisnası → enabled false, türler boş (asla fırlatmaz)")
    @SuppressWarnings("unchecked")
    void readFailureIsOff() {
        DistributedChecks none = new DistributedChecks((AppSettingsService) null, null);
        assertThat(none.enabled()).isFalse();
        assertThat(none.types()).isEmpty();
        assertThat(none.anyDistributed()).isFalse();
        assertThat(DistributedChecks.enabled(null)).isFalse();

        Settings s = new Settings();
        s.enabled = "true";
        DistributedChecks dc = new DistributedChecks(s.svc, null);
        assertThat(dc.anyDistributed()).isTrue();
        s.fail = new IllegalStateException("ayar okunamadı");
        assertThat(dc.enabled()).isFalse();
        assertThat(dc.types()).isEmpty();
        assertThat(dc.anyDistributed()).isFalse();

        ObjectProvider<AppSettingsService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenThrow(new org.springframework.beans.factory.BeanCreationException("yok"))
                .thenReturn(s.svc);
        s.fail = null;
        DistributedChecks lazy = new DistributedChecks(p, new MockEnvironment());
        assertThat(lazy.enabled()).as("sağlayıcı istisnası").isFalse();
        assertThat(lazy.enabled()).as("sonraki çağrı yeniden çözer").isTrue();
    }

    @Test
    @DisplayName("açılış ayarları: application.properties varsayılanları; aralık dışı değer sınıra, çevrilemeyen değer varsayılana; lider payı 16 × 50 % = 8")
    void tuning() throws IOException {
        DistributedChecks.Tuning d = DistributedChecks.Tuning.from(new MockEnvironment());
        assertThat(d).isEqualTo(DistributedChecks.Tuning.DEFAULTS);
        assertThat(d.workerEnabled()).isTrue();
        assertThat(d.slots()).isEqualTo(16);
        assertThat(d.leaderSlots()).isEqualTo(8);
        assertThat(DistributedChecks.Tuning.from(null)).isEqualTo(DistributedChecks.Tuning.DEFAULTS);

        MockEnvironment env = new MockEnvironment()
                .withProperty(DistributedChecks.KEY_WORKER_ENABLED, "FALSE")
                .withProperty(DistributedChecks.KEY_WORKER_SLOTS, "500")
                .withProperty(DistributedChecks.KEY_WORKER_POLL_MS, "abc")
                .withProperty(DistributedChecks.KEY_DISPATCH_MS, "10")
                .withProperty(DistributedChecks.KEY_DRAIN_MS, "2500")
                .withProperty(DistributedChecks.KEY_WORKER_LEADER_SHARE_PCT, "-5")
                .withProperty(DistributedChecks.KEY_WORKER_STEAL_AFTER_MS, "7000")
                .withProperty(DistributedChecks.KEY_RESULT_STALE_MS, "1");
        DistributedChecks.Tuning t = DistributedChecks.Tuning.from(env);
        assertThat(t.workerEnabled()).isFalse();
        assertThat(t.slots()).isEqualTo(DistributedChecks.Tuning.MAX_SLOTS);
        assertThat(t.pollMs()).as("çevrilemedi → varsayılan").isEqualTo(1_000L);
        assertThat(t.dispatchMs()).isEqualTo(1_000L);
        assertThat(t.drainMs()).isEqualTo(2_500L);
        assertThat(t.leaderSharePct()).isZero();
        assertThat(t.leaderSlots()).isZero();
        assertThat(t.stealAfterMs()).isEqualTo(7_000L);
        assertThat(t.resultStaleMs()).isEqualTo(60_000L);
        assertThat(new DistributedChecks.Tuning(true, 0, 0, 0, 0, 0, 0, 0).slots()).isEqualTo(1);
        assertThat(new DistributedChecks.Tuning(true, 3, 1_000, 5_000, 2_000, 50, 5_000, 600_000).leaderSlots()).isEqualTo(1);

        // ana application.properties: ASCII, ana anahtar KAPALI, sekiz tür, açılış anahtarları varsayılanlarla
        String props = Files.readString(Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8);
        assertThat(props).contains("\nsite.monitor.checks.distributed.enabled=${DISTRIBUTED_CHECKS_ENABLED:false}\n")
                .contains("\nsite.monitor.checks.distributed.types=${DISTRIBUTED_CHECKS_TYPES:"
                        + CheckJobTypes.DEFAULT_CSV + "}\n")
                .contains("\nsite.monitor.checks.worker.enabled=${CHECK_WORKER_ENABLED:true}\n")
                .contains("\nsite.monitor.checks.worker.slots=${CHECK_WORKER_SLOTS:16}\n")
                .contains("\nsite.monitor.checks.worker.poll-ms=${CHECK_WORKER_POLL_MS:1000}\n")
                .contains("\nsite.monitor.checks.worker.leader-share-pct=${CHECK_WORKER_LEADER_SHARE_PCT:50}\n")
                .contains("\nsite.monitor.checks.worker.steal-after-ms=${CHECK_WORKER_STEAL_AFTER_MS:5000}\n")
                .contains("\nsite.monitor.checks.dispatch-ms=${CHECK_DISPATCH_MS:5000}\n")
                .contains("\nsite.monitor.checks.drain-ms=${CHECK_DRAIN_MS:2000}\n")
                .contains("\nsite.monitor.checks.result-stale-ms=${CHECK_RESULT_STALE_MS:600000}\n");
        int from = props.indexOf("# Cok pod Surum 3-3");
        int to = props.indexOf("site.monitor.checks.result-stale-ms");
        assertThat(from).isPositive();
        assertThat(props.substring(from, to).chars().allMatch(c -> c < 128)).as("3-3 satırları ASCII").isTrue();
    }

    @Test
    @DisplayName("K-1 tembel başlatma: anahtar kapalıyken başlatıcı HİÇ çalışmaz (hazır + ayar olayları dahil)")
    void startersNeverRunWhileOff() {
        Settings s = new Settings();
        DistributedChecks dc = new DistributedChecks(s.svc, null);
        List<String> ran = new ArrayList<>();
        dc.whenActivated("worker", () -> ran.add("worker"));
        dc.onReady(null);
        dc.onSettingsChanged(changed(DistributedChecks.KEY_ENABLED));
        dc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(), "refresh"));
        s.enabled = "true";
        s.types = "uptime";                  // açık ama geçerli tür yok
        dc.onSettingsChanged(changed(DistributedChecks.KEY_TYPES));
        assertThat(ran).isEmpty();
        assertThat(dc.activated()).isFalse();
    }

    @Test
    @DisplayName("etkinleşme: hazırdan ÖNCE açık görülse de bekler; hazırda bir kez; ilgisiz anahtar tetiklemez; sonradan kapanınca geri alınmaz; geç kayıt hemen çalışır")
    void activatesOnceAfterReady() {
        Settings s = new Settings();
        DistributedChecks dc = new DistributedChecks(s.svc, null);
        List<String> ran = new ArrayList<>();
        dc.whenActivated("worker", () -> ran.add("worker"));
        dc.whenActivated("dispatch", () -> ran.add("dispatch"));
        s.enabled = "true";
        dc.onSettingsChanged(changed(DistributedChecks.KEY_ENABLED));
        assertThat(ran).as("hazır değil").isEmpty();
        dc.onReady(null);
        assertThat(ran).containsExactly("worker", "dispatch");
        assertThat(dc.activated()).isTrue();

        dc.onReady(null);
        dc.onSettingsChanged(changed(DistributedChecks.KEY_ENABLED));
        assertThat(ran).as("bir kez").containsExactly("worker", "dispatch");

        s.enabled = "false";
        dc.onSettingsChanged(changed(DistributedChecks.KEY_ENABLED));
        assertThat(dc.activated()).as("etkinleşme geri alınmaz — döngüler belleği okuyup döner").isTrue();

        AtomicReference<String> thread = new AtomicReference<>();
        dc.whenActivated("drainer", () -> thread.set(Thread.currentThread().getName()));
        assertThat(thread.get()).as("etkinleştikten sonra kayıt → hemen, çağıranın iş parçacığında")
                .isEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("etkinleşme ayar olayıyla: başka pod'un kaydı (refresh, boş anahtar kümesi) da tetikler; ilgisiz anahtar tetiklemez")
    void activatesOnSettingsEvent() {
        Settings s = new Settings();
        DistributedChecks dc = new DistributedChecks(s.svc, null);
        List<String> ran = new ArrayList<>();
        dc.whenActivated("worker", () -> ran.add("worker"));
        dc.onReady(null);
        s.enabled = "true";
        dc.onSettingsChanged(changed("site.monitor.port.alert-enabled"));
        assertThat(ran).as("ilgisiz anahtar").isEmpty();
        dc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(), "refresh"));
        assertThat(ran).containsExactly("worker");
    }

    @Test
    @DisplayName("başlatıcı istisnası yutulur, diğerleri çalışır; null başlatıcı yok sayılır; kapanıştan sonra ne etkinleşme ne kayıt")
    void failuresAndShutdown() {
        Settings s = new Settings();
        s.enabled = "true";
        DistributedChecks dc = new DistributedChecks(s.svc, null);
        List<String> ran = new ArrayList<>();
        dc.whenActivated("bad", () -> { throw new IllegalStateException("kurulamadı"); });
        dc.whenActivated("good", () -> ran.add("good"));
        dc.whenActivated("null", null);
        dc.onReady(null);
        assertThat(ran).containsExactly("good");

        DistributedChecks closing = new DistributedChecks(s.svc, null);
        List<String> late = new ArrayList<>();
        closing.whenActivated("worker", () -> late.add("worker"));
        closing.onContextClosed(null);
        closing.onReady(null);
        closing.onSettingsChanged(changed(DistributedChecks.KEY_ENABLED));
        closing.whenActivated("after-close", () -> late.add("after-close"));
        assertThat(late).as("kapanıştan sonra yeni iş yok").isEmpty();
        assertThat(closing.activated()).isFalse();
    }
}
