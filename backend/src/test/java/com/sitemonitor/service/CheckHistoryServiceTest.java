package com.sitemonitor.service;

import com.sitemonitor.repository.AlertEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * SEKİZ GEÇMİŞ UCUNUN ORTAK MOTORU — buraya kadar kendi testi yoktu, yalnız controller dilimi
 * üzerinden dolaylı kapsanıyordu. Buradaki clamp'ler bozulursa etki geniş ve sessiz:
 *  - size tavanı: tek istek 500k satır çekebilir (OOM/DoS),
 *  - negatif page: PageRequest.of fırlatır → 500,
 *  - ters/gelecek tarih aralığı: anlamsız pencere,
 *  - histogram degrade dalı: bir kez ÜRETİMİ kırmış senaryo (JPQL 42803) — şerit kaybolsa da
 *    liste/sayaç/alarm YAŞAMALI.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckHistoryServiceTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Mock AlertEventRepository alertEventRepo;
    @InjectMocks CheckHistoryService service;

    private static CheckHistoryService.Query q(String from, String to, Integer days, String status, int page, int size) {
        return new CheckHistoryService.Query(from, to, days, status, page, size);
    }

    // ── resolve(): clamp'ler ────────────────────────────────────────────────────

    @Test
    @DisplayName("size tavanı 200'e kırpılır — tek istek yüz binlerce satır çekemez")
    void size_clampedToMax() {
        var r = service.resolve(q(null, null, 7, "all", 0, 100_000), 180);
        assertThat(r.size()).isEqualTo(200);
    }

    @Test
    @DisplayName("size 0/negatif → en az 1 (PageRequest fırlatmasın)")
    void size_clampedToMin() {
        assertThat(service.resolve(q(null, null, 7, "all", 0, 0), 180).size()).isEqualTo(1);
        assertThat(service.resolve(q(null, null, 7, "all", 0, -5), 180).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("negatif page → 0 (PageRequest.of negatifte fırlatır, uç 500 dönerdi)")
    void page_clampedToZero() {
        assertThat(service.resolve(q(null, null, 7, "all", -3, 50), 180).page()).isZero();
    }

    @Test
    @DisplayName("TERS aralık (from > to) → from, to'ya çekilir (boş/anlamsız pencere üretilmez)")
    void invertedRange_collapses() {
        var r = service.resolve(q("2026-08-10T00:00:00", "2026-08-01T00:00:00", null, "all", 0, 50), 180);
        assertThat(r.from()).isEqualTo(r.to());
    }

    @Test
    @DisplayName("GELECEK tarihli to → now'a kırpılır (canlı yenileme kıyası bozulmasın)")
    void futureTo_clampedToNow() {
        String future = ISO.format(Instant.now().plus(30, ChronoUnit.DAYS));
        var r = service.resolve(q(null, future, 1, "all", 0, 50), 180);
        assertThat(r.to()).isLessThan(future);
    }

    @Test
    @DisplayName("RETENTION clamp: saklama penceresinden eski from geri çekilir ve zarfta görünür")
    void retentionClamp() {
        var r = service.resolve(q("2020-01-01", null, null, "all", 0, 50), 90);
        String minFrom = ISO.format(Instant.now().minus(90, ChronoUnit.DAYS));
        assertThat(r.from()).isGreaterThanOrEqualTo(minFrom.substring(0, 10));
        assertThat(r.retentionDays()).isEqualTo(90);
    }

    @ParameterizedTest
    @CsvSource({
        "fail,    true",
        "changed, true",
        "all,     false",
        "'',      false",
    })
    @DisplayName("status → fail bayrağı: yalnız fail/changed hata sorgusuna düşer")
    void statusFlag(String status, boolean expected) {
        assertThat(service.resolve(q(null, null, 7, status, 0, 50), 180).fail()).isEqualTo(expected);
    }

    // ── normalize: yarım girdiler ───────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "2026-08-07,        2026-08-07T00:00:00",
        "2026-08-07T10:30,  2026-08-07T10:30:00",
        "2026-08-07T10:30:15, 2026-08-07T10:30:15",
    })
    @DisplayName("normalizeFrom: 10/16 karakterli girdiler gün/dakika BAŞINA tamamlanır")
    void normalizeFrom(String in, String out) {
        assertThat(CheckHistoryService.normalizeFrom(in)).isEqualTo(out);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-08-07,        2026-08-07T23:59:59",
        "2026-08-07T10:30,  2026-08-07T10:30:59",
        "2026-08-07T10:30:15, 2026-08-07T10:30:15",
    })
    @DisplayName("normalizeTo: 10/16 karakterli girdiler gün/dakika SONUNA tamamlanır")
    void normalizeTo(String in, String out) {
        assertThat(CheckHistoryService.normalizeTo(in)).isEqualTo(out);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-08-07T00:00:00, 2026-08-07T05:00:00, 16",   // ≤6 saat → dakika
        "2026-08-07T00:00:00, 2026-08-07T06:00:00, 16",   // tam sınır
        "2026-08-07T00:00:00, 2026-08-07T07:00:00, 13",   // >6 saat → saat
        "2026-08-01T00:00:00, 2026-08-08T00:00:00, 13",   // 7 gün tam sınır
        "2026-08-01T00:00:00, 2026-08-09T00:00:00, 10",   // >7 gün → gün
    })
    @DisplayName("bucketPrefixLen sınırları: 6 saat ve 7 gün eşikleri")
    void bucketPrefixLenBoundaries(String from, String to, int expected) {
        assertThat(CheckHistoryService.bucketPrefixLen(from, to)).isEqualTo(expected);
    }

    @Test
    @DisplayName("bucketPrefixLen: bozuk tarih → saat kovasına düşer (fırlatmaz)")
    void bucketPrefixLen_invalidInput_defaults() {
        assertThat(CheckHistoryService.bucketPrefixLen("bozuk", "girdi")).isEqualTo(13);
    }

    // ── execute(): degrade dalları ──────────────────────────────────────────────

    /** Histogram ve bounds dışındaki her şeyi çalışır tutan asgari kaynak. */
    private CheckHistoryService.Source<String> source(Runnable histogramBehaviour, Runnable boundsBehaviour) {
        return new CheckHistoryService.Source<>() {
            public Page<String> page(String f, String t, boolean fail, Pageable p) {
                return new PageImpl<>(List.of("row1", "row2"), p, 2);
            }
            public long total(String f, String t) { return 120; }
            public long fail(String f, String t)  { return 7; }
            public List<Object[]> histogram(String f, String t, int len) {
                histogramBehaviour.run();
                return List.<Object[]>of(new Object[]{ "2026-08-07T10", 60L, 3L });
            }
            public List<Object[]> bounds() {
                boundsBehaviour.run();
                return List.<Object[]>of(new Object[]{ "2026-01-01T00:00:00", "2026-08-07T10:00:00" });
            }
        };
    }

    @Test
    @DisplayName("HİSTOGRAM PATLARSA şerit boş döner ama liste/sayaç/alarm YAŞAR (üretimi kıran senaryo)")
    void execute_histogramThrows_restSurvives() {
        var r = service.resolve(q(null, null, 7, "all", 0, 50), 180);
        var src = source(() -> { throw new RuntimeException("JPQL 42803"); }, () -> {});

        Map<String, Object> out = service.execute(src, r, null, null);

        assertThat((List<?>) out.get("buckets")).isEmpty();      // şerit kayboldu
        assertThat((List<?>) out.get("items")).hasSize(2);       // ama liste yaşıyor
        assertThat(out.get("counts")).isEqualTo(Map.of("total", 120L, "fail", 7L));
        assertThat(out.get("total")).isEqualTo(2L);
        assertThat(out.get("retention_days")).isEqualTo(180);
    }

    @Test
    @DisplayName("BOUNDS patlarsa saklama bilgisi null olur, sekme çalışmaya devam eder")
    void execute_boundsThrows_restSurvives() {
        var r = service.resolve(q(null, null, 7, "all", 0, 50), 180);
        var src = source(() -> {}, () -> { throw new RuntimeException("bounds patladı"); });

        Map<String, Object> out = service.execute(src, r, null, null);

        assertThat(out.get("oldest_at")).isNull();
        assertThat(out.get("newest_at")).isNull();
        assertThat((List<?>) out.get("items")).hasSize(2);
        assertThat((List<?>) out.get("buckets")).hasSize(1);
    }

    @Test
    @DisplayName("Zarf sözleşmesi: tüm anahtarlar mevcut ve alarm listesi boşken bile dizi")
    void execute_envelopeContract() {
        var r = service.resolve(q(null, null, 7, "all", 0, 50), 180);
        when(alertEventRepo.findOverlappingForHistory(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of());

        Map<String, Object> out = service.execute(source(() -> {}, () -> {}), r, "a.com", Set.of("EXPIRY"));

        assertThat(out).containsKeys("items", "page", "size", "total", "counts", "range",
                "retention_days", "oldest_at", "newest_at", "buckets", "alerts");
        assertThat((List<?>) out.get("alerts")).isEmpty();
        assertThat(out.get("oldest_at")).isEqualTo("2026-01-01T00:00:00");
    }

    @Test
    @DisplayName("alertDomainKey null ise alarm sorgusu HİÇ koşmaz (gereksiz iş yok)")
    void execute_noAlertKey_skipsAlertQuery() {
        var r = service.resolve(q(null, null, 7, "all", 0, 50), 180);

        Map<String, Object> out = service.execute(source(() -> {}, () -> {}), r, null, Set.of("EXPIRY"));

        assertThat((List<?>) out.get("alerts")).isEmpty();
        org.mockito.Mockito.verify(alertEventRepo, org.mockito.Mockito.never())
                .findOverlappingForHistory(anyString(), any(), anyString(), anyString());
    }
}
