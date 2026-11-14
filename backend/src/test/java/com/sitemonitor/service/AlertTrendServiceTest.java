package com.sitemonitor.service;

import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.util.TtlMemo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Kişisel Panom "Alarm eğilimi" (2026-11-14). Kanıtlar: (1) saatlik kovalar — içinde bulunulan saat + önceki 23, sınır dışı
 * satır sayılmaz; (2) günlük kovalar Europe/Istanbul takvim günü (UTC günü DEĞİL — gece 00:00–03:00 İstanbul arasında fark
 * eder); (3) kapsam Alarm Geçmişi kuralı: tümünü gören her şeyi, kapsamlı kullanıcı damgalı takımı ya da envanterin SY / UG
 * takımı kapsamda olanı; (4) kapsamı olmayan kullanıcı sorgusuz sıfır; (5) okuma hatası sıfır DEĞİL, istisna — ve saklanmaz;
 * (6) kurum geneli görüntü paylaşılır: iki farklı kapsam TTL içinde SABİT 3 sorgu, kullanıcılar arası sızıntı yok.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertTrendServiceTest {

    /** 14 Kasım 2026 01:30 İstanbul = 13 Kasım 22:30 UTC — UTC günü ile İstanbul günü FARKLI. */
    private static final Instant NOW = Instant.parse("2026-11-13T22:30:00Z");

    @Mock AlertEventRepository alertEventRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    AlertTrendService svc;
    long[] memoClock = {1_000_000L};
    final List<Object[]> opened = new ArrayList<>();
    final List<Object[]> closed = new ArrayList<>();
    final List<Object[]> inventory = new ArrayList<>();

    @BeforeEach
    void setUp() {
        svc = service(0);
        when(alertEventRepo.countOpenedByOwnerAndHour(anyString())).thenAnswer(i -> new ArrayList<>(opened));
        when(alertEventRepo.countClosedByOwnerAndHour(anyString())).thenAnswer(i -> new ArrayList<>(closed));
        when(inventoryRepo.findDomainOwners()).thenAnswer(i -> new ArrayList<>(inventory));
    }

    private AlertTrendService service(long cacheMs) {
        AlertTrendService s = new AlertTrendService(alertEventRepo, inventoryRepo);
        ReflectionTestUtils.setField(s, "cacheMs", cacheMs);
        ReflectionTestUtils.setField(s, "orgMemo", new TtlMemo<AlertTrendService.OrgTrend>(2, () -> memoClock[0]));
        ReflectionTestUtils.setField(s, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        return s;
    }

    private static Object[] row(Long team, String domain, String hour, long count, long extra) {
        return new Object[]{team, domain, hour, count, extra};
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buckets(Map<String, Object> out) { return (List<Map<String, Object>>) out.get("buckets"); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> totals(Map<String, Object> out) { return (Map<String, Object>) out.get("totals"); }

    @Test
    @DisplayName("24 sa: 24 saatlik kova (bu saat + önceki 23); pencere dışı saat sayılmaz; toplamlar ve from / to")
    void hourlyBuckets() {
        opened.add(row(5L, "a.example.com", "2026-11-13T22", 2, 1));   // içinde bulunulan saat → son kova
        opened.add(row(5L, "a.example.com", "2026-11-12T23", 3, 0));   // ilk kova (22 − 23 saat)
        opened.add(row(5L, "a.example.com", "2026-11-12T22", 9, 9));   // pencereden bir saat ÖNCE → sayılmaz
        closed.add(row(5L, "a.example.com", "2026-11-13T10", 4, 1));
        Map<String, Object> out = svc.build("24h", true, null);

        assertThat(out).containsEntry("range", "24h").containsEntry("bucket", "hour")
                .containsEntry("from", "2026-11-12T23:00:00").containsEntry("to", "2026-11-13T22:30:00");
        List<Map<String, Object>> b = buckets(out);
        assertThat(b).hasSize(24);
        assertThat(b.get(0)).containsEntry("t", "2026-11-12T23:00:00").containsEntry("opened", 3L).containsEntry("closed", 0L)
                .doesNotContainKey("day");
        assertThat(b.get(23)).containsEntry("t", "2026-11-13T22:00:00").containsEntry("opened", 2L);
        assertThat(b.get(11)).containsEntry("t", "2026-11-13T10:00:00").containsEntry("closed", 4L);
        assertThat(totals(out)).containsEntry("opened", 5L).containsEntry("closed", 4L)
                .containsEntry("critical", 1L).containsEntry("closed_silent", 1L);
    }

    @Test
    @DisplayName("7 gün: kovalar İSTANBUL günü — 21:00 UTC ertesi İstanbul gününe, 20:00 UTC aynı güne düşer; bugün dahil 7 gün")
    void dailyBucketsFollowIstanbulDays() {
        opened.add(row(5L, "a.example.com", "2026-11-13T21", 1, 0));   // 14 Kasım 00:00 İstanbul → BUGÜN (son kova)
        opened.add(row(5L, "a.example.com", "2026-11-13T20", 2, 0));   // 13 Kasım 23:00 İstanbul → dün
        opened.add(row(5L, "a.example.com", "2026-11-07T21", 4, 0));   // 8 Kasım 00:00 İstanbul → ilk kova
        opened.add(row(5L, "a.example.com", "2026-11-07T20", 8, 0));   // 7 Kasım 23:00 İstanbul → pencere dışı
        closed.add(row(5L, "a.example.com", "2026-11-10T09", 3, 0));   // 10 Kasım 12:00 İstanbul
        Map<String, Object> out = svc.build("7d", true, null);

        assertThat(out).containsEntry("range", "7d").containsEntry("bucket", "day").containsEntry("from", "2026-11-07T21:00:00");
        List<Map<String, Object>> b = buckets(out);
        assertThat(b).hasSize(7);
        assertThat(b.get(0)).containsEntry("day", "2026-11-08").containsEntry("t", "2026-11-07T21:00:00").containsEntry("opened", 4L);
        assertThat(b.get(2)).containsEntry("day", "2026-11-10").containsEntry("closed", 3L);
        assertThat(b.get(5)).containsEntry("day", "2026-11-13").containsEntry("opened", 2L);
        assertThat(b.get(6)).containsEntry("day", "2026-11-14").containsEntry("opened", 1L);   // UTC'ye göre "yarın" olan gün
        assertThat(totals(out)).containsEntry("opened", 7L).containsEntry("closed", 3L);
    }

    @Test
    @DisplayName("kapsam = Alarm Geçmişi kuralı: damgalı takım ya da envanterin SY / UG takımı; başka takım ve takımsız alarm görünmez")
    void scopeFollowsAlertHistoryRule() {
        opened.add(row(5L, "kendi-izleme", "2026-11-13T20", 1, 0));            // damga takım 5
        opened.add(row(null, "sy.example.com", "2026-11-13T20", 2, 0));        // damgasız sertifika alarmı, envanter SY 5
        opened.add(row(null, "ug.example.com", "2026-11-13T20", 4, 0));        // envanter UG 5
        opened.add(row(9L, "baska-takim", "2026-11-13T20", 8, 8));             // takım 9
        opened.add(row(null, "sahipsiz", "2026-11-13T20", 16, 0));             // takımsız, envanterde yok
        opened.add(row(null, "dokuz.example.com", "2026-11-13T20", 32, 0));    // envanter SY 9
        closed.add(row(9L, "baska-takim", "2026-11-13T20", 64, 0));
        inventory.add(new Object[]{"sy.example.com", 5L, null});
        inventory.add(new Object[]{"ug.example.com", 7L, 5L});
        inventory.add(new Object[]{"dokuz.example.com", 9L, null});

        // Kapsamlı müdür / USER (görüş takımı 5)
        Map<String, Object> scoped = svc.build("24h", false, List.of(5L));
        assertThat(totals(scoped)).containsEntry("opened", 7L).containsEntry("closed", 0L).containsEntry("critical", 0L);
        // Paylaşımla 9'u da gören (viewTeamIdsWithGrants → 5 + 9)
        assertThat(totals(svc.build("24h", false, List.of(5L, 9L)))).containsEntry("opened", 47L).containsEntry("closed", 64L);
        // Tümünü gören (global görüntüleyici / 7-24 operatörü): takımsız dahil hepsi
        assertThat(totals(svc.build("24h", true, null))).containsEntry("opened", 63L).containsEntry("closed", 64L)
                .containsEntry("critical", 8L);
    }

    @Test
    @DisplayName("kapsamı olmayan kullanıcı (görüş takımı yok): sorgusuz sıfır kovalar")
    void emptyScopeReadsNothing() {
        Map<String, Object> out = svc.build("7d", false, List.of());
        assertThat(buckets(out)).hasSize(7).allSatisfy(b -> assertThat(b).containsEntry("opened", 0L).containsEntry("closed", 0L));
        assertThat(totals(svc.build("24h", false, null))).containsEntry("opened", 0L);
        verifyNoInteractions(alertEventRepo, inventoryRepo);
    }

    @Test
    @DisplayName("okuma hatası → UnavailableException (asla sıfır); görüntü SAKLANMAZ — düzelince ilk çağrı okur")
    void readFailureIsNotZeros() {
        AlertTrendService cached = service(30_000);
        when(alertEventRepo.countClosedByOwnerAndHour(anyString())).thenThrow(new IllegalStateException("db"))
                .thenAnswer(i -> new ArrayList<>(closed));
        opened.add(row(5L, "a", "2026-11-13T20", 3, 0));
        assertThatThrownBy(() -> cached.build("24h", true, null)).isInstanceOf(AlertTrendService.UnavailableException.class);
        // Aynı TTL içinde: başarısız görüntü bellekte değil
        assertThat(totals(cached.build("24h", true, null))).containsEntry("opened", 3L);
    }

    @Test
    @DisplayName("kurum geneli görüntü paylaşılır: iki kapsam + iki aralık TTL içinde SABİT 3 sorgu; dar kapsam geniş kapsamın verisini görmez; TTL sonrası yeniden")
    void orgSnapshotIsSharedAndScopeAppliedPerCall() {
        AlertTrendService cached = service(30_000);
        opened.add(row(5L, "a", "2026-11-13T20", 1, 0));
        opened.add(row(9L, "b", "2026-11-13T20", 10, 0));
        // Önce GENİŞ kapsam doldurur
        assertThat(totals(cached.build("24h", true, null))).containsEntry("opened", 11L);
        assertThat(totals(cached.build("24h", false, List.of(5L)))).containsEntry("opened", 1L);
        assertThat(totals(cached.build("7d", false, List.of(9L)))).containsEntry("opened", 10L);
        verify(alertEventRepo, times(1)).countOpenedByOwnerAndHour(anyString());
        verify(alertEventRepo, times(1)).countClosedByOwnerAndHour(anyString());
        verify(inventoryRepo, times(1)).findDomainOwners();

        memoClock[0] += 30_001;
        opened.add(row(5L, "a", "2026-11-13T21", 2, 0));
        assertThat(totals(cached.build("24h", false, List.of(5L)))).containsEntry("opened", 3L);
        verify(alertEventRepo, times(2)).countOpenedByOwnerAndHour(anyString());
    }

    @Test
    @DisplayName("görüntü 7 günlük pencereyi bir gün payla okur (İstanbul gün başı, UTC damga); biçimi bozuk kova atlanır")
    void snapshotWindowAndMalformedBuckets() {
        opened.add(row(5L, "a", "bozuk", 5, 0));
        opened.add(row(5L, "a", null, 5, 0));
        opened.add(row(5L, "a", "2026-11-13T20", 1, 0));
        assertThat(totals(svc.build("24h", true, null))).containsEntry("opened", 1L);
        // Bugün 14 Kasım (İstanbul) → 7 gün önce 7 Kasım 00:00 İstanbul = 6 Kasım 21:00 UTC
        verify(alertEventRepo).countOpenedByOwnerAndHour("2026-11-06T21:00:00");
        verify(alertEventRepo).countClosedByOwnerAndHour("2026-11-06T21:00:00");
        assertThat(AlertTrendService.hourOf("2026-11-13T20")).isEqualTo(Instant.parse("2026-11-13T20:00:00Z"));
        assertThat(AlertTrendService.hourOf("2026-13-45T99")).isNull();
    }
}
