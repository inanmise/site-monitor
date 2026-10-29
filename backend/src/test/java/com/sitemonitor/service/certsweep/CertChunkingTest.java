package com.sitemonitor.service.certsweep;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-4 P0 — anahtar ({@link CertChunking}): VARSAYILAN KAPALI, okuma hatası = KAPALI, pod ayarları sınırlı,
 * bütçe / lider payı / gerçek parça boyutu kuralları; tembel başlatıcılar YALNIZ anahtar ilk kez açık görüldüğünde, bir
 * kez, kapanıştan önce çalışır (L-1: açılıştan beri kapalıyken hiçbiri); pod sayaç kayıtları bellek.
 */
class CertChunkingTest {

    /** Değeri testin değiştirdiği ayar servisi (bellek). */
    static final class Settings {
        volatile String enabled;
        volatile RuntimeException fail;
        final AppSettingsService svc = mock(AppSettingsService.class);

        Settings() {
            when(svc.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> {
                if (fail != null) throw fail;
                if (CertChunking.KEY_ENABLED.equals(i.getArgument(0)) && enabled != null) {
                    return Boolean.parseBoolean(enabled);
                }
                return i.getArgument(1);
            });
        }
    }

    private static AppSettingsChangedEvent changed(String... keys) {
        return new AppSettingsChangedEvent(Set.of(keys), "save");
    }

    @Test
    @DisplayName("varsayılan KAPALI: ayar yok → enabled false; anahtar adı kalıcı; 3-3 anahtarından bağımsız")
    void defaultOff() {
        Settings s = new Settings();
        CertChunking cc = new CertChunking(s.svc, null);
        assertThat(CertChunking.KEY_ENABLED).isEqualTo("site.monitor.checks.cert-chunks.enabled");
        assertThat(CertChunking.DEFAULT_ENABLED).isFalse();
        assertThat(cc.enabled()).isFalse();
        assertThat(cc.tuning()).isEqualTo(CertChunking.Tuning.DEFAULTS);
        assertThat(cc.closing()).isFalse();
        assertThat(CertChunking.KEY_ENABLED).isNotEqualTo(com.sitemonitor.service.checks.DistributedChecks.KEY_ENABLED);

        s.enabled = "true";
        assertThat(cc.enabled()).as("canlı").isTrue();
        s.enabled = "false";
        assertThat(cc.enabled()).isFalse();
    }

    @Test
    @DisplayName("okuma hatası = KAPALI: servis null / istisna / sağlayıcı istisnası → false (asla fırlatmaz)")
    @SuppressWarnings("unchecked")
    void readFailureIsOff() {
        CertChunking none = new CertChunking((AppSettingsService) null, null);
        assertThat(none.enabled()).isFalse();
        assertThat(CertChunking.enabled(null)).isFalse();

        Settings s = new Settings();
        s.enabled = "true";
        CertChunking cc = new CertChunking(s.svc, null);
        assertThat(cc.enabled()).isTrue();
        s.fail = new IllegalStateException("ayar okunamadı");
        assertThat(cc.enabled()).isFalse();

        ObjectProvider<AppSettingsService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenThrow(new org.springframework.beans.factory.BeanCreationException("yok"))
                .thenReturn(s.svc);
        s.fail = null;
        CertChunking lazy = new CertChunking(p, new MockEnvironment());
        assertThat(lazy.enabled()).as("sağlayıcı istisnası").isFalse();
        assertThat(lazy.enabled()).as("sonraki çağrı yeniden çözer").isTrue();
    }

    @Test
    @DisplayName("açılış ayarları: varsayılanlar; aralık dışı değer sınıra, çevrilemeyen değer varsayılana, int taşması doyurulur")
    void tuningClamps() {
        CertChunking.Tuning d = CertChunking.Tuning.from(new MockEnvironment());
        assertThat(d).isEqualTo(CertChunking.Tuning.DEFAULTS);
        assertThat(d.workerEnabled()).isTrue();
        assertThat(d.chunkSize()).isEqualTo(20);
        assertThat(d.minDomains()).isEqualTo(40);
        assertThat(d.maxChunks()).isEqualTo(4);
        assertThat(d.maxInflightDomains()).isZero();
        assertThat(d.leaseMs()).isEqualTo(60_000L);
        assertThat(d.stealAfterMs()).isEqualTo(10_000L);
        assertThat(d.leaderSharePct()).isEqualTo(50);
        assertThat(d.pollMs()).isEqualTo(250L);
        assertThat(d.idlePollMs()).isEqualTo(5_000L);
        assertThat(d.renewEveryMs()).as("kira / 4").isEqualTo(15_000L);
        assertThat(CertChunking.Tuning.from(null)).isEqualTo(CertChunking.Tuning.DEFAULTS);

        MockEnvironment env = new MockEnvironment()
                .withProperty(CertChunking.KEY_WORKER_ENABLED, " FALSE ")
                .withProperty(CertChunking.KEY_CHUNK_SIZE, "1")
                .withProperty(CertChunking.KEY_MIN_DOMAINS, "5000000000")
                .withProperty(CertChunking.KEY_MAX_CHUNKS, "abc")
                .withProperty(CertChunking.KEY_MAX_INFLIGHT_DOMAINS, "-3")
                .withProperty(CertChunking.KEY_LEASE_MS, "999999999")
                .withProperty(CertChunking.KEY_STEAL_AFTER_MS, "-1")
                .withProperty(CertChunking.KEY_LEADER_SHARE_PCT, "250")
                .withProperty(CertChunking.KEY_POLL_MS, "10")
                .withProperty(CertChunking.KEY_IDLE_POLL_MS, "1");
        CertChunking.Tuning t = CertChunking.Tuning.from(env);
        assertThat(t.workerEnabled()).isFalse();
        assertThat(t.chunkSize()).isEqualTo(CertChunking.Tuning.MIN_CHUNK_SIZE);
        assertThat(t.minDomains()).as("int taşması eksiye dönmez, sınıra doyar").isEqualTo(CertChunking.Tuning.MAX_MIN_DOMAINS);
        assertThat(t.maxChunks()).as("çevrilemedi → varsayılan").isEqualTo(4);
        assertThat(t.maxInflightDomains()).isZero();
        assertThat(t.leaseMs()).isEqualTo(CertChunking.Tuning.MAX_LEASE_MS);
        assertThat(t.stealAfterMs()).isZero();
        assertThat(t.leaderSharePct()).isEqualTo(100);
        assertThat(t.pollMs()).isEqualTo(50L);
        assertThat(t.idlePollMs()).as("en az 250 ve en az poll").isEqualTo(250L);
        assertThat(new CertChunking.Tuning(true, 999, 0, 99, 99_999, 0, 0, 0, 9_999, 999_999).chunkSize())
                .isEqualTo(CertChunking.Tuning.MAX_CHUNK_SIZE);
        CertChunking.Tuning hi = new CertChunking.Tuning(true, 20, 40, 4, 0, 60_000, 10_000, 50, 4_000, 1_000);
        assertThat(hi.idlePollMs()).as("boşta yoklama etkin yoklamadan kısa olamaz").isEqualTo(4_000L);
        assertThat(new CertChunking.Tuning(true, 20, 40, 99, 99_999, 15_000, 0, 50, 250, 5_000).maxChunks())
                .isEqualTo(CertChunking.Tuning.MAX_MAX_CHUNKS);
        assertThat(new CertChunking.Tuning(true, 20, 40, 4, 99_999, 15_000, 0, 50, 250, 5_000).maxInflightDomains())
                .isEqualTo(CertChunking.Tuning.MAX_INFLIGHT_DOMAINS);
    }

    @Test
    @DisplayName("bütçe: 0 = 2 × havuz çekirdeği (20 → 40), dolu değer olduğu gibi; lider payı 40 × 50 % = 20, 0 % → 0; çekirdek okunamazsa ≥ 1")
    void budgets() {
        CertChunking.Tuning d = CertChunking.Tuning.DEFAULTS;
        assertThat(d.budget(20)).isEqualTo(40);
        assertThat(d.leaderBudget(20)).isEqualTo(20);
        assertThat(d.budget(60)).as("canlı havuz ayarı").isEqualTo(120);
        assertThat(d.budget(0)).isEqualTo(2);
        assertThat(d.budget(-5)).isEqualTo(2);
        CertChunking.Tuning fixed = new CertChunking.Tuning(true, 20, 40, 4, 30, 60_000, 10_000, 50, 250, 5_000);
        assertThat(fixed.budget(20)).isEqualTo(30);
        assertThat(fixed.leaderBudget(20)).isEqualTo(15);
        CertChunking.Tuning none = new CertChunking.Tuning(true, 20, 40, 4, 0, 60_000, 10_000, 0, 250, 5_000);
        assertThat(none.leaderBudget(20)).isZero();
        CertChunking.Tuning odd = new CertChunking.Tuning(true, 20, 40, 4, 3, 60_000, 10_000, 50, 250, 5_000);
        assertThat(odd.leaderBudget(20)).as("aşağı yuvarlanır").isEqualTo(1);
    }

    @Test
    @DisplayName("FX-WORKER: liderin tam bütçe eşiği 3 × steal-after (taban 10 sn → 30 sn; 0 sn ayarında da 30 sn); kapanış sonrası sahiplenme süresi 2 × kira")
    void leaderReliefAndOffGrace() {
        CertChunking.Tuning d = CertChunking.Tuning.DEFAULTS;
        assertThat(d.leaderFullBudgetAgeMs()).isEqualTo(30_000L);
        assertThat(d.offGraceMs()).isEqualTo(120_000L);
        CertChunking.Tuning immediate = new CertChunking.Tuning(true, 20, 40, 4, 0, 60_000, 0, 50, 250, 5_000);
        assertThat(immediate.leaderFullBudgetAgeMs()).as("steal-after 0 → yarı bütçe hemen düşmesin").isEqualTo(30_000L);
        CertChunking.Tuning slow = new CertChunking.Tuning(true, 20, 40, 4, 0, 15_000, 20_000, 50, 250, 5_000);
        assertThat(slow.leaderFullBudgetAgeMs()).isEqualTo(60_000L);
        assertThat(slow.offGraceMs()).isEqualTo(30_000L);
        assertThat(d.leaderFullBudgetAgeMs()).as("bekletmeden uzun").isGreaterThan(d.stealAfterMs());
    }

    @Test
    @DisplayName("parça boyutu: tur en çok 500 parça — 1 200 alan adı 60 × 20; 20 000 alan adı 500 × 40; 10 001 → 21'lik 477 parça")
    void effectiveChunkSize() {
        CertChunking.Tuning d = CertChunking.Tuning.DEFAULTS;
        assertThat(d.effectiveChunkSize(1_200)).isEqualTo(20);
        assertThat(d.chunkCount(1_200)).isEqualTo(60);
        assertThat(d.chunkCount(41)).isEqualTo(3);
        assertThat(d.effectiveChunkSize(20_000)).isEqualTo(40);
        assertThat(d.chunkCount(20_000)).isEqualTo(500);
        assertThat(d.effectiveChunkSize(10_001)).isEqualTo(21);
        assertThat(d.chunkCount(10_001)).isEqualTo(477);
        assertThat(d.chunkCount(0)).isZero();
        assertThat(d.effectiveChunkSize(0)).isEqualTo(20);
        for (int n : new int[]{1, 40, 499, 500, 9_999, 10_000, 10_001, 123_457, 1_000_000}) {
            assertThat(d.chunkCount(n)).as("n=" + n).isLessThanOrEqualTo(CertChunking.MAX_CHUNKS_PER_RUN);
            assertThat((long) d.chunkCount(n) * d.effectiveChunkSize(n)).as("kapsar n=" + n).isGreaterThanOrEqualTo(n);
        }
    }

    @Test
    @DisplayName("protokol sabitleri: en çok 2 sahiplenme, koordinatör canlılığı nabzın 6 katı, kira yenilemesi canlılık süresinden kısa")
    void protocolConstants() {
        assertThat(CertChunking.MAX_ATTEMPTS).isEqualTo(2);
        assertThat(CertChunking.COORDINATOR_ALIVE_MS).isEqualTo(6 * CertChunking.COORDINATOR_HEARTBEAT_MS);
        assertThat(CertChunking.COORDINATOR_WAIT_POLL_MS).isEqualTo(500L);
        assertThat(CertChunking.MAX_CHUNKS_PER_RUN).isEqualTo(500);
        assertThat(CertChunking.RUN_RETENTION_MS).isEqualTo(7L * 24 * 3600 * 1000);
        assertThat(CertChunking.Tuning.DEFAULTS.renewEveryMs()).isLessThan(CertChunking.Tuning.DEFAULTS.leaseMs());
        assertThat(CertSweepStore.MAX_CHUNK_ROWS).isGreaterThanOrEqualTo(2 * CertChunking.MAX_CHUNKS_PER_RUN);
        assertThat(CertSweepStore.SHUTDOWN_QUERY_TIMEOUT_SECONDS).isLessThan(CertSweepStore.QUERY_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("application.properties: ana anahtar KAPALI + 10 açılış anahtarı varsayılanlarla, satırlar ASCII")
    void properties() throws IOException {
        String props = Files.readString(Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8);
        assertThat(props).contains("\nsite.monitor.checks.cert-chunks.enabled=${CERT_CHUNKS_ENABLED:false}\n")
                .contains("\nsite.monitor.checks.cert-chunks.worker.enabled=${CERT_CHUNK_WORKER_ENABLED:true}\n")
                .contains("\nsite.monitor.checks.cert-chunks.chunk-size=${CERT_CHUNK_SIZE:20}\n")
                .contains("\nsite.monitor.checks.cert-chunks.min-domains=${CERT_CHUNK_MIN_DOMAINS:40}\n")
                .contains("\nsite.monitor.checks.cert-chunks.max-chunks=${CERT_CHUNK_MAX_CHUNKS:4}\n")
                .contains("\nsite.monitor.checks.cert-chunks.max-inflight-domains=${CERT_CHUNK_MAX_INFLIGHT_DOMAINS:0}\n")
                .contains("\nsite.monitor.checks.cert-chunks.lease-ms=${CERT_CHUNK_LEASE_MS:60000}\n")
                .contains("\nsite.monitor.checks.cert-chunks.steal-after-ms=${CERT_CHUNK_STEAL_AFTER_MS:10000}\n")
                .contains("\nsite.monitor.checks.cert-chunks.leader-share-pct=${CERT_CHUNK_LEADER_SHARE_PCT:50}\n")
                .contains("\nsite.monitor.checks.cert-chunks.poll-ms=${CERT_CHUNK_POLL_MS:250}\n")
                .contains("\nsite.monitor.checks.cert-chunks.idle-poll-ms=${CERT_CHUNK_IDLE_POLL_MS:5000}\n");
        // Spring Environment'tan okunduğunda koddaki varsayılanlarla aynı (yer tutucu çözümü: env yokken varsayılan)
        MockEnvironment env = new MockEnvironment();
        for (String line : props.split("\n")) {
            if (!line.startsWith("site.monitor.checks.cert-chunks.")) continue;
            String key = line.substring(0, line.indexOf('='));
            String value = line.substring(line.indexOf(":", line.indexOf("${")) + 1, line.lastIndexOf('}'));
            env.setProperty(key, value);
        }
        assertThat(CertChunking.Tuning.from(env)).isEqualTo(CertChunking.Tuning.DEFAULTS);
        int from = props.indexOf("# Cok pod Surum 3-4");
        int to = props.indexOf("site.monitor.checks.cert-chunks.idle-poll-ms");
        assertThat(from).isPositive();
        assertThat(props.substring(from, to).chars().allMatch(c -> c < 128)).as("3-4 satırları ASCII").isTrue();
    }

    @Test
    @DisplayName("L-1 tembel başlatma: anahtar kapalıyken başlatıcı HİÇ çalışmaz (hazır + ayar olayları + boş anahtarlı yenileme dahil)")
    void startersNeverRunWhileOff() {
        Settings s = new Settings();
        CertChunking cc = new CertChunking(s.svc, null);
        List<String> ran = new ArrayList<>();
        cc.whenActivated("worker", () -> ran.add("worker"));
        cc.onReady(null);
        cc.onSettingsChanged(changed(CertChunking.KEY_ENABLED));
        cc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(), "refresh"));
        cc.onSettingsChanged(changed("site.monitor.checks.distributed.enabled"));
        assertThat(ran).isEmpty();
        assertThat(cc.activated()).isFalse();
        assertThat(cc.workerStatus()).isNull();
        assertThat(cc.coordinatorStatus()).isNull();
    }

    @Test
    @DisplayName("etkinleşme: hazırdan ÖNCE açık görülse de bekler; hazırda bir kez; ilgisiz anahtar tetiklemez; sonradan kapanınca geri alınmaz; geç kayıt hemen")
    void activatesOnceAfterReady() {
        Settings s = new Settings();
        CertChunking cc = new CertChunking(s.svc, null);
        List<String> ran = new ArrayList<>();
        cc.whenActivated("a", () -> ran.add("a"));
        s.enabled = "true";
        cc.onSettingsChanged(changed(CertChunking.KEY_ENABLED));
        assertThat(ran).as("hazırdan önce").isEmpty();
        cc.onReady(null);
        assertThat(ran).containsExactly("a");
        assertThat(cc.activated()).isTrue();
        cc.onReady(null);
        cc.onSettingsChanged(changed(CertChunking.KEY_ENABLED));
        assertThat(ran).as("bir kez").containsExactly("a");
        s.enabled = "false";
        cc.onSettingsChanged(changed(CertChunking.KEY_ENABLED));
        assertThat(cc.activated()).as("geri alınmaz").isTrue();
        cc.whenActivated("b", () -> ran.add("b"));
        assertThat(ran).as("geç kayıt hemen").containsExactly("a", "b");
        cc.whenActivated("null", null);
        assertThat(ran).hasSize(2);
    }

    @Test
    @DisplayName("ayar olayıyla etkinleşme: hazır + kapalı → başka pod'un kaydı (boş anahtarlı yenileme) açınca bir kez; ilgisiz anahtar tetiklemez")
    void activatesOnSettingsEvent() {
        Settings s = new Settings();
        CertChunking cc = new CertChunking(s.svc, null);
        List<String> ran = new ArrayList<>();
        cc.whenActivated("w", () -> ran.add("w"));
        cc.onReady(null);
        s.enabled = "true";
        cc.onSettingsChanged(changed("site.monitor.checks.distributed.enabled"));
        assertThat(ran).as("ilgisiz anahtar").isEmpty();
        cc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(), "refresh"));
        assertThat(ran).containsExactly("w");
    }

    @Test
    @DisplayName("kapanış: ContextClosedEvent'ten sonra etkinleşme yok, kayıt yok sayılır, bekleyenler düşer; başlatıcı istisnası diğerlerini durdurmaz")
    void closedAndFailingStarters() {
        Settings s = new Settings();
        CertChunking cc = new CertChunking(s.svc, null);
        List<String> ran = new ArrayList<>();
        cc.whenActivated("x", () -> ran.add("x"));
        cc.onContextClosed(null);
        assertThat(cc.closing()).isTrue();
        s.enabled = "true";
        cc.onReady(null);
        cc.onSettingsChanged(changed(CertChunking.KEY_ENABLED));
        cc.whenActivated("y", () -> ran.add("y"));
        assertThat(ran).isEmpty();
        assertThat(cc.activated()).isFalse();

        CertChunking cc2 = new CertChunking(s.svc, null);
        cc2.whenActivated("boom", () -> {
            throw new IllegalStateException("kurulamadı");
        });
        cc2.whenActivated("ok", () -> ran.add("ok"));
        cc2.onReady(null);
        assertThat(ran).containsExactly("ok");
    }

    @Test
    @DisplayName("pod sayaçları: kayıt yoksa null; son kayıt geçerli; kaynak istisnası / null → null (asla fırlatmaz)")
    void statusRegistry() {
        CertChunking cc = new CertChunking((AppSettingsService) null, null);
        assertThat(cc.workerStatus()).isNull();
        CertSweepStatus.Worker w = new CertSweepStatus.Worker(true, true, 1, 20, 40, false, 3, 2, 0, 0, 0, 40, 1_000L, false);
        cc.registerWorkerStatus(() -> w);
        assertThat(cc.workerStatus()).isSameAs(w);
        cc.registerWorkerStatus(() -> {
            throw new IllegalStateException("okunamadı");
        });
        assertThat(cc.workerStatus()).isNull();
        cc.registerWorkerStatus(null);
        assertThat(cc.workerStatus()).isNull();

        CertSweepStatus.Coordinator c = new CertSweepStatus.Coordinator(1, 2, 3, 4, 5, 6);
        cc.registerCoordinatorStatus(() -> c);
        assertThat(cc.coordinatorStatus()).isEqualTo(c);
        cc.registerCoordinatorStatus(() -> null);
        assertThat(cc.coordinatorStatus()).isNull();
    }
}
