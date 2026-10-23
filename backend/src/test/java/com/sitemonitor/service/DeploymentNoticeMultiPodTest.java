package com.sitemonitor.service;

import com.sitemonitor.model.DeploymentHistory;
import com.sitemonitor.repository.DeploymentHistoryRepository;
import com.sitemonitor.service.DeploymentHistoryService.Derived;
import com.sitemonitor.service.DeploymentHistoryService.Kind;
import com.sitemonitor.service.DeploymentHistoryService.TransitionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dağıtım bildirimi ve yeniden başlatma sayımı N pod'da (2026-10-23, çok pod R2b, MP-37e). İki {@link DeploymentHistoryService}
 * örneği (iki pod) aynı H2 veritabanına yazar:
 * <ul>
 *   <li>iki yeni sürüm pod'u aynı anda açılır → TEK UPGRADE olayı (birbirini görmeden ikisi de UPGRADE türetse bile
 *       {@code schema_patch_markers} nişanını yalnız biri ekler);</li>
 *   <li>dağıtımın ortasında yeniden başlayan ESKİ sürüm pod'u (konteyner yeniden başlaması ya da eski ReplicaSet'in geç
 *       kalan pod'u) ROLLBACK + ardından UPGRADE üretmez;</li>
 *   <li>2 replikalı dağıtımın ikinci pod'u yeniden başlatma sayılmaz; aynı pod'un yeniden başlaması sayılır.</li>
 * </ul>
 * İşlemsiz koşar ({@code NOT_SUPPORTED}) — üretimdeki gibi her yazım kendi işleminde.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class DeploymentNoticeMultiPodTest {

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    @Autowired DeploymentHistoryRepository repo;
    @Autowired JdbcTemplate jdbc;

    final List<Object> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clean() {
        repo.deleteAll();
        jdbc.execute(StandaloneMonitorDeletionBackfill.MARKERS_DDL);
        jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key LIKE 'deploy-notice:%'");
        events.clear();
    }

    static String ago(long seconds) {
        return ISO.format(Instant.now().minusSeconds(seconds));
    }

    /** Bir pod'un dağıtım servisi: kendi BuildInfo'su (sürüm, helm revizyonu, pod adı, JVM başlangıcı), ortak veritabanı. */
    DeploymentHistoryService pod(String version, Integer helmRev, String podName, String startedAt) {
        BuildInfo bi = mock(BuildInfo.class);
        when(bi.get()).thenReturn(new BuildInfo.Snapshot(version, "env", "abc123", "2026-10-23T08:00:00Z", version,
                "ghcr.io/x/site-monitor:v" + version, "prod", "sitemonitor", helmRev, version, "cfg",
                podName + "-" + Integer.toHexString(podName.hashCode()), podName, podName, "node-1", "25", startedAt,
                Instant.parse(startedAt).toEpochMilli()));
        ReleaseIndexService ri = mock(ReleaseIndexService.class);
        when(ri.find(any())).thenReturn(Optional.empty());
        when(ri.all()).thenReturn(List.of());
        ApplicationEventPublisher pub = events::add;
        return new DeploymentHistoryService(repo, bi, ri, jdbc, pub, new MockEnvironment());
    }

    /** Hâlâ koşan (son görülme şimdi) önceki sürüm pod'u. */
    DeploymentHistory alive(String version, Integer helmRev, String podName, String startedAt) {
        DeploymentHistory d = new DeploymentHistory();
        d.setStartedAt(startedAt);
        d.setRecordedAt(startedAt);
        d.setLastSeenAt(ISO.format(Instant.now()));
        d.setEnvironment("prod");
        d.setVersion(version);
        d.setHelmRevision(helmRev);
        d.setPodName(podName);
        d.setHostname(podName);
        d.setSource(DeploymentHistory.SOURCE_STARTUP);
        d.setCreatedBy("SYSTEM");
        return repo.save(d);
    }

    List<TransitionEvent> transitions() {
        List<TransitionEvent> out = new ArrayList<>();
        for (Object o : events) if (o instanceof TransitionEvent t) out.add(t);
        return out;
    }

    @Test
    @DisplayName("iki yeni sürüm pod'u AYNI ANDA açılır (20 tur) → her turda TEK UPGRADE olayı")
    void twoNewPodsStartTogether_oneNotice() throws Exception {
        for (int round = 0; round < 20; round++) {
            clean();
            alive("1.0.0", 7, "sm-old-a", ago(3_600));
            alive("1.0.0", 7, "sm-old-b", ago(3_590));
            String start = ago(5);
            DeploymentHistoryService c = pod("1.1.0", 8, "sm-new-c", start);
            DeploymentHistoryService d = pod("1.1.0", 8, "sm-new-d", start);
            race(c::recordStartup, d::recordStartup);
            assertThat(transitions()).as("tur " + round).hasSize(1);
            assertThat(transitions().get(0).kind()).isEqualTo(Kind.UPGRADE);
            assertThat(transitions().get(0).fromVersion()).isEqualTo("1.0.0");
        }
    }

    @Test
    @DisplayName("birbirini görmeden ikisi de UPGRADE türetirse (ikinci pod'un JVM'i daha önce açıldı) nişan ikinci yayını durdurur")
    void bothDeriveUpgrade_markerStopsTheSecond() {
        alive("1.0.0", 7, "sm-old-a", ago(3_600));
        pod("1.1.0", 8, "sm-new-c", ago(5)).recordStartup();
        // D'nin JVM'i C'den önce açıldı ama kaydı sonra yazıldı: sırada C'den önce → C'yi görmez, o da UPGRADE türetir
        pod("1.1.0", 8, "sm-new-d", ago(9)).recordStartup();
        assertThat(transitions()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_patch_markers WHERE patch_key = ?", Integer.class,
                "deploy-notice:prod:1.1.0:8")).isEqualTo(1);
    }

    @Test
    @DisplayName("dağıtım ortasında eski pod yeniden başlar (aynı pod adı) ya da eski ReplicaSet geç kalan pod açar → olay yok, sürüm 1.1.0 kalır")
    void oldPodRestartMidRollout_noRollbackNoSecondUpgrade() {
        alive("1.0.0", 7, "sm-old-a", ago(3_600));
        DeploymentHistory oldB = alive("1.0.0", 7, "sm-old-b", ago(3_590));
        pod("1.1.0", 8, "sm-new-c", ago(60)).recordStartup();
        assertThat(transitions()).hasSize(1);
        // B çöker: son görülmesi eski, bitişi yok — aynı pod adıyla 1.0.0 yeniden başlar
        oldB.setLastSeenAt(ago(50));
        repo.save(oldB);
        pod("1.0.0", 7, "sm-old-b", ago(40)).recordStartup();
        // Eski ReplicaSet'in YENİ adlı geç kalan pod'u (A'nın yerine; A gitti): daha yüksek revizyonlu canlı pod var
        jdbc.update("UPDATE deployment_history SET ended_at = ?, end_reason = 'graceful' WHERE pod_name = 'sm-old-a'", ago(35));
        pod("1.0.0", 7, "sm-old-x", ago(30)).recordStartup();
        pod("1.1.0", 8, "sm-new-d", ago(20)).recordStartup();
        assertThat(transitions()).as("yalnız ilk UPGRADE — sahte ROLLBACK ya da ikinci UPGRADE yok").hasSize(1);

        DeploymentHistoryService reader = pod("1.1.0", 8, "sm-reader", ago(1));
        List<Derived> all = reader.derivedFor("prod");
        assertThat(all).extracting(Derived::kind).containsExactly(Kind.FIRST_SEEN, Kind.RESTART, Kind.UPGRADE, Kind.RESTART,
                Kind.RESTART, Kind.RESTART);
        assertThat(reader.current("prod").version()).isEqualTo("1.1.0");
        assertThat(reader.current("prod").liveKind()).isEqualTo(Kind.UPGRADE);
        Map<String, Object> tl = reader.timeline("prod");
        assertThat((List<?>) tl.get("transitions")).as("RESTART'sız geçişler: FIRST_SEEN + UPGRADE").hasSize(2);
        // Sayılan yeniden başlatma: yalnız aynı adla yeniden başlayan B (eski ReplicaSet'in geç kalan pod'u ve D ek replika)
        assertThat(tl.get("restartCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("2 replikalı dağıtım: ikinci yeni pod yeniden başlatma SAYILMAZ (ek replika); aynı pod'un yeniden başlaması sayılır")
    void restartsCountedPerPod() {
        alive("1.0.0", 7, "sm-old-a", ago(3_600));
        alive("1.0.0", 7, "sm-old-b", ago(3_590));
        pod("1.1.0", 8, "sm-new-c", ago(120)).recordStartup();
        pod("1.1.0", 8, "sm-new-d", ago(100)).recordStartup();
        DeploymentHistoryService reader = pod("1.1.0", 8, "sm-reader", ago(1));
        assertThat(reader.current("prod").restartsSince()).as("2 replikalı dağıtım RESTART sayılmaz").isZero();
        assertThat(reader.summary("prod").get("restartsLast7d")).isEqualTo(0);
        List<Derived> all = reader.derivedFor("prod");
        assertThat(all.get(3).replica()).isTrue();
        assertThat(reader.toMap(all.get(3))).containsEntry("replica", true);

        // D'nin konteyneri yeniden başlar (aynı pod adı, aynı sürüm) → sayılır
        jdbc.update("UPDATE deployment_history SET last_seen_at = ? WHERE pod_name = 'sm-new-d'", ago(60));
        pod("1.1.0", 8, "sm-new-d", ago(50)).recordStartup();
        assertThat(reader.current("prod").restartsSince()).isEqualTo(1);
        assertThat(reader.timeline("prod").get("restartCount")).isEqualTo(1);
        assertThat(reader.summary("prod").get("restartsLast7d")).isEqualTo(1);
        assertThat(transitions()).hasSize(1);
    }

    @Test
    @DisplayName("gerçek geri alma (helm revizyonu artar) yine ROLLBACK olarak bir kez bildirilir; ikinci pod'u ek replika")
    void realRollbackStillAnnounced() {
        alive("1.1.0", 8, "sm-new-c", ago(3_600));
        alive("1.1.0", 8, "sm-new-d", ago(3_590));
        pod("1.0.0", 9, "sm-rb-e", ago(30)).recordStartup();
        pod("1.0.0", 9, "sm-rb-f", ago(20)).recordStartup();
        assertThat(transitions()).hasSize(1);
        assertThat(transitions().get(0).kind()).isEqualTo(Kind.ROLLBACK);
        assertThat(transitions().get(0).fromVersion()).isEqualTo("1.1.0");
    }

    static void race(Runnable a, Runnable b) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> fa = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); a.run(); return null; });
            Future<?> fb = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); b.run(); return null; });
            fa.get(30, TimeUnit.SECONDS);
            fb.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
