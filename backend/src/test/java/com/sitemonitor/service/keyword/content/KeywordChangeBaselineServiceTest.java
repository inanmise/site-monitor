package com.sitemonitor.service.keyword.content;

import com.sitemonitor.model.KeywordChangeBaseline;
import com.sitemonitor.repository.KeywordChangeBaselineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * İçerik değişimi temeli (kw sözleşmesi §3.3) — H2, {@code NOT_SUPPORTED}: depo yazma yöntemleri kendi işlemlerini açmak
 * ZORUNDA ({@code @DataJpaTest}'in sarmalı eksikliği gizlemesin). İki iş parçacığı aynı anda otomatik alırsa TEK satır; elle
 * alım upsert; silme.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class KeywordChangeBaselineServiceTest {

    @Autowired KeywordChangeBaselineRepository repo;

    KeywordChangeBaselineService service;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        service = new KeywordChangeBaselineService(repo, null);
        service.forceDialect(false);   // H2: taşınabilir yol
        service.setClock(Clock.fixed(Instant.parse("2026-11-05T08:00:00Z"), ZoneOffset.UTC));
    }

    private static KeywordChangeDetector.Snapshot snap(String... lines) {
        return KeywordChangeDetector.snapshot(List.of(lines), java.util.EnumSet.noneOf(ContentMasker.Mask.class), List.of());
    }

    @Test
    @DisplayName("Otomatik alım: ilk çağrı ekler, AYNI ayar özetiyle ikincisi değiştirmez (ilk temel korunur)")
    void autoInsertIfAbsent() {
        assertThat(service.takeAuto(7L, snap("a", "b"), 120, "spec1")).isTrue();
        assertThat(service.takeAuto(7L, snap("x"), 999, "spec1")).isFalse();
        KeywordChangeBaseline b = repo.findById(7L).orElseThrow();
        assertThat(b.getFingerprint()).isEqualTo(snap("a", "b").fingerprint());
        assertThat(b.getSpecHash()).isEqualTo("spec1");
        assertThat(b.getText()).isEqualTo("a\nb");
        assertThat(b.getTextTruncated()).isFalse();
        assertThat(b.getBytes()).isEqualTo(120L);
        assertThat(b.getSource()).isEqualTo("AUTO");
        assertThat(b.getTakenBy()).isEqualTo("system");
        assertThat(b.getTakenByName()).isNull();
        assertThat(b.getTakenAt()).isEqualTo("2026-11-05T08:00:00");
        assertThat(b.getVersion()).isZero();
    }

    @Test
    @DisplayName("kw son inceleme O-4: eskimiş ayar özetli (başka maske / seçici / algoritma) temel otomatik alımda YENİLENİR — "
            + "\"temel bekleniyor\"da takılı kalmaz; elle alınmış temel de özet farklıysa yenilenir, aynıysa korunur")
    void autoRefreshesStaleSpec() {
        assertThat(service.takeAuto(8L, snap("eski"), 10, "specOld")).isTrue();
        assertThat(service.takeAuto(8L, snap("yeni", "satır"), 20, "specNew")).as("özet farklı → yenilendi").isTrue();
        KeywordChangeBaseline b = repo.findById(8L).orElseThrow();
        assertThat(b.getSpecHash()).isEqualTo("specNew");
        assertThat(b.getText()).isEqualTo("yeni\nsatır");
        assertThat(b.getBytes()).isEqualTo(20L);
        assertThat(b.getSource()).isEqualTo("AUTO");
        assertThat(b.getVersion()).isEqualTo(1L);
        // aynı özet → değişmez
        assertThat(service.takeAuto(8L, snap("baska"), 30, "specNew")).isFalse();
        assertThat(repo.findById(8L).orElseThrow().getText()).isEqualTo("yeni\nsatır");
        // elle alınmış temel: aynı özet → otomatik alım dokunmaz
        service.takeManual(9L, snap("elle"), 5, "s1", "ali", "Ali Veli");
        assertThat(service.takeAuto(9L, snap("oto"), 6, "s1")).isFalse();
        assertThat(repo.findById(9L).orElseThrow().getSource()).isEqualTo("MANUAL");
        // elle alınmış temel, ayar özeti artık farklı → otomatik alım yeniler (eski ayarla alınmış temel geçersiz)
        assertThat(service.takeAuto(9L, snap("oto"), 6, "s2")).isTrue();
        assertThat(repo.findById(9L).orElseThrow().getSource()).isEqualTo("AUTO");
    }

    @Test
    @DisplayName("İki iş parçacığı aynı anda otomatik alır → TEK satır, en çok bir 'ekledim'")
    void concurrentAutoSingleRow() throws Exception {
        for (int round = 0; round < 10; round++) {
            long id = 100 + round;
            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Boolean>> fs = new ArrayList<>();
                for (int t = 0; t < 2; t++) {
                    String line = "pod-" + t;
                    fs.add(pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return service.takeAuto(id, snap(line), 1, "s");
                    }));
                }
                int inserted = 0;
                for (Future<Boolean> f : fs) if (f.get(30, TimeUnit.SECONDS)) inserted++;
                assertThat(inserted).as("tur " + round).isLessThanOrEqualTo(1);
                assertThat(repo.findById(id)).as("tur " + round).isPresent();
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(repo.count()).isEqualTo(10);
    }

    @Test
    @DisplayName("Elle alım upsert: yoksa ekler, varsa üzerine yazar (kaynak MANUAL, alan + ad, sürüm artar)")
    void manualUpsert() {
        KeywordChangeBaseline first = service.takeManual(9L, snap("a"), 10, "s1", "ayse", "Ayşe Yılmaz (Bilgi İşlem)");
        assertThat(first.getSource()).isEqualTo("MANUAL");
        assertThat(first.getTakenBy()).isEqualTo("ayse");
        assertThat(first.getTakenByName()).isEqualTo("Ayşe Yılmaz (Bilgi İşlem)");
        service.takeAuto(9L, snap("auto"), 1, "s1");   // var → dokunmaz
        KeywordChangeBaseline second = service.takeManual(9L, snap("b", "c"), 20, "s2", "mehmet", null);
        assertThat(second.getText()).isEqualTo("b\nc");
        assertThat(second.getSpecHash()).isEqualTo("s2");
        assertThat(second.getTakenByName()).isNull();
        assertThat(second.getVersion()).isEqualTo(first.getVersion() + 1);
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Silme: satır varsa true, yoksa false; görünüm (view) temel yoksa null")
    void deleteAndView() {
        assertThat(service.view(5L)).isNull();
        service.takeAuto(5L, snap("a"), 1, "s");
        KeywordChangeDetector.BaselineView v = service.view(5L);
        assertThat(v.specHash()).isEqualTo("s");
        assertThat(v.text()).isEqualTo("a");
        assertThat(service.delete(5L)).isTrue();
        assertThat(service.delete(5L)).isFalse();
        assertThat(service.find(5L)).isEmpty();
        assertThat(service.delete(null)).isFalse();
        assertThat(service.takeAuto(null, snap("a"), 1, "s")).isFalse();
    }

    @Test
    @DisplayName("API biçimi: taken_at, taken_by_name, source, bytes (+ metinle: text_truncated, line_count, text)")
    void apiShape() {
        KeywordChangeBaseline b = service.takeManual(3L, snap("x", "y", "z"), 33, "s", "u", "Kullanıcı");
        Map<String, Object> withText = KeywordChangeBaselineService.toApi(b, true);
        assertThat(withText).containsEntry("taken_at", "2026-11-05T08:00:00").containsEntry("taken_by_name", "Kullanıcı")
                .containsEntry("source", "MANUAL").containsEntry("bytes", 33L).containsEntry("text_truncated", false)
                .containsEntry("line_count", 3).containsEntry("text", "x\ny\nz");
        assertThat(KeywordChangeBaselineService.toApi(b, false)).doesNotContainKeys("text", "line_count");
        assertThat(KeywordChangeBaselineService.toApi(null, true)).isNull();
    }
}
