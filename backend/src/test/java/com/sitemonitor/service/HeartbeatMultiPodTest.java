package com.sitemonitor.service;

import com.sitemonitor.model.SystemHeartbeat;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.RetentionRunRepository;
import com.sitemonitor.repository.SystemHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Heartbeat iki pod'da (2026-10-23, çok pod R2b, MP-35) — gerçek JPA deposu (H2):
 * <ul>
 *   <li>iki pod aynı dakikalarda yazar → küme şeridi dakikada BİR alınan (oran 1,0; eskiden "2880 / 1440");</li>
 *   <li>bir pod işaretsiz susar → pod listesinde "sessiz" (uyarı), küme şeridi yeşil kalır ama pod satırı açığı gösterir;</li>
 *   <li>kapanış işareti → "durdu" (uyarı değil); aynı adlı pod'un yeni süreci canlıyken eski süreç "yeniden başladı";</li>
 *   <li>işaret satırları son sinyal / alınan sayılmaz; eski (pod'u bilinmeyen) satırlar küme şeridinde sayılır, listede yok.</li>
 * </ul>
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class HeartbeatMultiPodTest {

    @Autowired SystemHeartbeatRepository repo;
    @Autowired JdbcTemplate jdbc;

    static final String A = "sitemonitor-6b7c-aaaaa-0000000a";
    static final String B = "sitemonitor-6b7c-bbbbb-0000000b";
    static final String B2 = "sitemonitor-6b7c-bbbbb-0000000c";   // B'nin yeniden başlamış süreci (aynı pod adı)

    ExtendedHealthService podA;
    LocalDateTime now;

    @BeforeEach
    void setUp() {
        // Kapanış işareti ayrı iş parçacığında (ortak kapanış bütçesi, D2) kendi işleminde yazılır — testin işlemi geri
        // alınınca silinmez; her test temiz tabloyla başlar.
        jdbc.update("DELETE FROM system_heartbeat");
        podA = service(A);
        now = LocalDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0);
    }

    /** Ayrı iş parçacığında yazılmış (işlenmiş) işaret satırları paylaşılan test veritabanında kalmasın — silme İŞLENİR. */
    @org.junit.jupiter.api.AfterEach
    void cleanCommitted() {
        if (!org.springframework.test.context.transaction.TestTransaction.isActive()) return;
        jdbc.update("DELETE FROM system_heartbeat");
        org.springframework.test.context.transaction.TestTransaction.flagForCommit();
        org.springframework.test.context.transaction.TestTransaction.end();
    }

    ExtendedHealthService service(String instance) {
        ExtendedHealthService s = new ExtendedHealthService(mock(NotificationLogRepository.class), repo, mock(AlertEventRepository.class),
                jdbc, mock(RdapDomainClient.class), mock(RetentionRunRepository.class), mock(AppSettingsService.class));
        s.instanceIdSupplier = () -> instance;
        return s;
    }

    void beat(String instance, LocalDateTime at) {
        SystemHeartbeat hb = new SystemHeartbeat();
        hb.setRecordedAt(at);
        hb.setInstanceId(instance);
        repo.save(hb);
    }

    void beats(String instance, int fromMinAgo, int toMinAgo) {
        List<SystemHeartbeat> rows = new ArrayList<>();
        for (int m = fromMinAgo; m >= toMinAgo; m--) {
            SystemHeartbeat hb = new SystemHeartbeat();
            hb.setRecordedAt(now.minusMinutes(m).plusSeconds(instance.equals(A) ? 5 : 40));
            hb.setInstanceId(instance);
            rows.add(hb);
        }
        repo.saveAll(rows);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    static int received(Map<String, Object> timeline) {
        return list(timeline, "buckets").stream().mapToInt(b -> (Integer) b.get("received")).sum();
    }

    @Test
    @DisplayName("iki pod aynı dakikalarda yazar → küme şeridi dakikada bir (oran 1,0), her pod'un kendi satırı tam")
    void twoWritersCountDistinctMinutes() {
        beats(A, 59, 1);
        beats(B, 59, 1);

        Map<String, Object> tl = podA.getHeartbeatTimeline(1);
        // HATA ÖNCESİ: satır sayılıyordu → 118 "alınan", oran ~2
        assertThat(received(tl)).isEqualTo(59);
        assertThat(tl).containsEntry("counting", "distinct_minutes");
        List<Map<String, Object>> pods = list(tl, "pods");
        assertThat(pods).extracting(p -> p.get("id")).containsExactlyInAnyOrder(A, B);
        for (Map<String, Object> p : pods) {
            assertThat(p).containsEntry("received", 59);
            assertThat((Integer) p.get("expected")).isBetween(59, 61);
        }

        Map<String, Object> st = podA.getHeartbeatStatus();
        assertThat(st).containsEntry("alarm", false).containsEntry("pods_live", 2L).containsEntry("pods_silent", 0L)
                .containsEntry("served_by", A).containsEntry("served_by_pod", "sitemonitor-6b7c-aaaaa");
        assertThat(list(st, "pods")).extracting(p -> p.get("state")).containsOnly("live");
    }

    @Test
    @DisplayName("B işaretsiz susar (çöktü / takıldı) → pod listesinde 'sessiz', küme alarmı yok (A yaşıyor), B'nin satırı açığı gösterir")
    void silentPodIsVisible() {
        beats(A, 59, 1);
        beats(B, 59, 15);   // son 15 dk yok

        Map<String, Object> st = podA.getHeartbeatStatus();
        // HATA ÖNCESİ: tek "son heartbeat" (A'nınki) → kart yeşil, B görünmüyordu
        assertThat(st).containsEntry("alarm", false).containsEntry("pods_silent", 1L);
        Map<String, Object> b = list(st, "pods").stream().filter(p -> B.equals(p.get("id"))).findFirst().orElseThrow();
        assertThat(b).containsEntry("state", "silent").containsEntry("name", "sitemonitor-6b7c-bbbbb");
        assertThat((Long) b.get("minutes_since")).isGreaterThanOrEqualTo(14L);
        assertThat(list(st, "pods").get(0)).as("canlı pod önce").containsEntry("id", A);

        Map<String, Object> tl = podA.getHeartbeatTimeline(1);
        assertThat(received(tl)).as("küme yine dakikada bir").isEqualTo(59);
        Map<String, Object> bRow = list(tl, "pods").stream().filter(p -> B.equals(p.get("id"))).findFirst().orElseThrow();
        assertThat(bRow).containsEntry("state", "silent").containsEntry("received", 45);
    }

    @Test
    @DisplayName("kapanış işareti → 'durdu' (uyarı değil, sayılmaz); aynı adlı yeni süreç canlıyken eski süreç 'yeniden başladı'")
    void stoppedAndRestarted() {
        beats(A, 30, 1);
        beats(B, 30, 20);
        service(B).recordShutdownMarker();                 // B düzgün kapandı (işaret şimdi)
        beat(B2, now.minusMinutes(1));                     // aynı pod adının yeni süreci
        beat("sitemonitor-6b7c-ccccc-0000000d", now.minusMinutes(20));   // C işaretsiz sustu; yerine YENİ adlı pod gelmedi

        Map<String, Object> st = podA.getHeartbeatStatus();
        Map<String, String> states = new java.util.HashMap<>();
        for (Map<String, Object> p : list(st, "pods")) states.put((String) p.get("id"), (String) p.get("state"));
        assertThat(states).containsEntry(A, "live").containsEntry(B, "stopped").containsEntry(B2, "live")
                .containsEntry("sitemonitor-6b7c-ccccc-0000000d", "silent");
        assertThat(st).containsEntry("pods_silent", 1L);
        // aynı adlı iki süreç kimliğin son ekiyle ayrılır
        assertThat(list(st, "pods")).extracting(p -> p.get("name")).contains("sitemonitor-6b7c-bbbbb · 0000000b", "sitemonitor-6b7c-bbbbb · 0000000c");
        // işaret son sinyal sayılmaz
        assertThat((List<String>) st.get("recent")).allSatisfy(ts -> assertThat(ts).isNotNull());

        // B2 hiç işaret yazmadan susarsa ve B'nin YENİ süreci olarak B3 canlıysa B2 "yeniden başladı"
        jdbc.update("DELETE FROM system_heartbeat WHERE instance_id = ?", B2);
        beat(B2, now.minusMinutes(25));
        beat("sitemonitor-6b7c-bbbbb-0000000e", now.minusMinutes(1));
        Map<String, String> st2 = new java.util.HashMap<>();
        for (Map<String, Object> p : list(podA.getHeartbeatStatus(), "pods")) st2.put((String) p.get("id"), (String) p.get("state"));
        assertThat(st2).containsEntry(B2, "restarted").containsEntry("sitemonitor-6b7c-bbbbb-0000000e", "live");
    }

    @Test
    @DisplayName("D1: susan pod'un yerine YENİ adlı pod geldiyse 'yerine yenisi geldi' (uyarı değil); yeni pod yoksa ya da yalnız eski pod yeniden başladıysa 'sessiz' kalır")
    void replacedSilentPodIsNotAWarning() {
        beats(A, 120, 1);                                          // A uzun süredir canlı
        beats(B, 120, 61);                                         // B 60 dk önce sustu (tahliye / node kaybı)
        String c = "sitemonitor-6b7c-ccccc-0000000c";             // Deployment'ın yeni pod'u (yeni ad), B sustuktan sonra
        for (int m = 50; m >= 1; m--) beat(c, now.minusMinutes(m));

        Map<String, Object> st = podA.getHeartbeatStatus();
        Map<String, Object> b = list(st, "pods").stream().filter(p -> B.equals(p.get("id"))).findFirst().orElseThrow();
        // HATA ÖNCESİ: B 24 saat "sessiz" kalıyor, genel bant sarı
        assertThat(b).containsEntry("state", "replaced").containsEntry("replaced_by", "sitemonitor-6b7c-ccccc");
        assertThat(st).containsEntry("pods_silent", 0L);
    }

    @Test
    @DisplayName("D1: takılı pod (yerine yeni pod gelmedi) ve yalnız ESKİ pod'un yeniden başlaması → 'sessiz' (uyarı) kalır")
    void hungPodWithoutReplacementStaysSilent() {
        beats(A, 120, 1);
        beats(B, 120, 61);                                         // B takıldı
        String a2 = "sitemonitor-6b7c-aaaaa-0000000f";            // A'nın konteyneri yeniden başladı (AYNI ad) — yeni pod sayılmaz
        for (int m = 40; m >= 1; m--) beat(a2, now.minusMinutes(m));
        Map<String, Object> st = podA.getHeartbeatStatus();
        Map<String, Object> b = list(st, "pods").stream().filter(p -> B.equals(p.get("id"))).findFirst().orElseThrow();
        assertThat(b).containsEntry("state", "silent");
        assertThat(st).containsEntry("pods_silent", 1L);
    }

    @Test
    @DisplayName("eski (pod'u bilinmeyen) satırlar küme şeridinde sayılır, pod listesine girmez; işaret satırı alınan değildir")
    void legacyRowsAndMarkers() {
        for (int m = 10; m >= 1; m--) beat(null, now.minusMinutes(m));
        service(A).recordShutdownMarker();
        Map<String, Object> tl = podA.getHeartbeatTimeline(1);
        assertThat(received(tl)).isEqualTo(10);
        assertThat(list(tl, "pods")).isEmpty();
        Map<String, Object> st = podA.getHeartbeatStatus();
        assertThat(list(st, "pods")).extracting(p -> p.get("state")).containsExactly("stopped");
        assertThat((Long) st.get("minutes_since")).isBetween(0L, 2L);
    }
}
