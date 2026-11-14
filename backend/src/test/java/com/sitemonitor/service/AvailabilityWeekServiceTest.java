package com.sitemonitor.service;

import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.MonitorSparklineService.MonitorRef;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kişisel Panom "7 günlük erişilebilirlik" (2026-11-14). Kanıtlar: (1) kontrol ağırlıklı oran, hata varken asla %100, ölçüm
 * yoksa null (asla %0 / %100); (2) hedef canlı ayardan, hüküm KESİN orandan (yuvarlanmış gösterimden değil); (3) kapsam çağrı
 * başına: tümünü gören, kapsamlı müdür / USER (takımsız satır görünmez), paylaşım; (4) ölçülmemiş = taranan ama kontrolsüz
 * izleme — duraklatılmış ve envanteri pasif türev Port / DNS satırı (İzleme Panosu kuralı) sayılmaz; (5) en kötüler hedefin
 * altındakiler, en düşük önce, tavanlı; (6) kurum geneli görüntü paylaşılır — iki kapsam TTL içinde tür başına TEK okuma; kaynak
 * kartların ısıttığı 30 GÜNLÜK ortak SLA önbelleğinin 7 günlük dönemi (ayrı 7 günlük tarama yok); (7) bir tür okunamazsa
 * istisna (kısmi toplam yok) ve saklanmaz.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AvailabilityWeekServiceTest {

    @Mock MonitorSparklineService sparklineService;
    @Mock AppSettingsService appSettings;
    @Mock CertificateInventoryRepository inventoryRepo;
    AvailabilityWeekService svc;
    long[] memoClock = {1_000_000L};
    final Map<String, Map<Long, MonitorRef>> refs = new HashMap<>();
    final Map<String, Map<Long, Map<String, Object>>> avail = new HashMap<>();
    final List<String> activeInventory = new ArrayList<>();

    private static final Predicate<Long> ALL = t -> true;

    private static Predicate<Long> teams(Long... ids) {
        Set<Long> s = Set.of(ids);
        return t -> t != null && s.contains(t);
    }

    @BeforeEach
    void setUp() {
        svc = service(0);
        when(appSettings.getDouble(eq("site.monitor.sla.target-pct"), anyDouble())).thenReturn(99.9);
        when(sparklineService.monitorRefs(anyString())).thenAnswer(i -> refs.getOrDefault(i.getArgument(0, String.class), Map.of()));
        when(sparklineService.availabilityAll(anyString(), anyInt())).thenAnswer(i -> avail.getOrDefault(i.getArgument(0, String.class), Map.of()));
        when(inventoryRepo.findActiveDomains()).thenAnswer(i -> new ArrayList<>(activeInventory));
    }

    private AvailabilityWeekService service(long cacheMs) {
        AvailabilityWeekService s = new AvailabilityWeekService(sparklineService, appSettings, inventoryRepo);
        ReflectionTestUtils.setField(s, "cacheMs", cacheMs);
        ReflectionTestUtils.setField(s, "orgMemo", new TtlMemo<AvailabilityWeekService.OrgWeek>(2, () -> memoClock[0]));
        return s;
    }

    /** Bir izleme: takım + ad + 7 günlük sayım (n = 0 → ölçülmemiş). */
    private void monitor(String type, long id, Long team, String name, int n, int fail, int badHours) {
        monitor(type, id, team, name, true, n, fail, badHours);
    }

    private void monitor(String type, long id, Long team, String name, boolean active, int n, int fail, int badHours) {
        put(type, id, new MonitorRef(team, name, active), n, fail, badHours);
    }

    /** Port / DNS satırı: {@code standalone} false = envanter türevi (envanterin takımını taşır), {@code host} envanter anahtarı. */
    private void network(String type, long id, Long team, String name, boolean standalone, String host, int n) {
        put(type, id, new MonitorRef(team, name, true, standalone, host), n, 0, 0);
    }

    /**
     * Ortak SLA önbelleğinin 30 GÜNLÜK sonucu ({@code tür:30}) gibi: 7 günlük sayım {@code windows[days = 7]} döneminde; üst
     * düzey (30 gün) ve diğer dönemler BİLEREK farklı — servis yanlış alanı okursa sayılar tutmaz (inceleme 2026-11-14).
     */
    private void put(String type, long id, MonitorRef ref, int n, int fail, int badHours) {
        refs.computeIfAbsent(type, k -> new HashMap<>()).put(id, ref);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("n", n * 4 + 7); a.put("fail", fail * 4 + 3); a.put("bad_hours", badHours * 4 + 1);
        a.put("up_pct", MonitorSparklineService.pct(n * 4 + 7, fail * 4 + 3));
        List<Map<String, Object>> windows = new ArrayList<>();
        for (int d : new int[] {1, 7, 15, 30}) {
            Map<String, Object> w = new LinkedHashMap<>();
            int wn = d == 7 ? n : n + d * 3, wf = d == 7 ? fail : fail + d;
            w.put("days", d); w.put("n", wn); w.put("fail", wf); w.put("bad_hours", d == 7 ? badHours : badHours + d);
            w.put("up_pct", MonitorSparklineService.pct(wn, wf));
            windows.add(w);
        }
        a.put("windows", windows);
        avail.computeIfAbsent(type, k -> new HashMap<>()).put(id, a);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> out, String key) { return (List<Map<String, Object>>) out.get(key); }

    @Test
    @DisplayName("kontrol ağırlıklı oran + hedef; hedefin altındakiler sayılır ve en düşük önce listelenir; tür kırılımı yalnız ölçülen türler")
    void weightedAvailabilityAndWorst() {
        monitor("http", 1, 5L, "Ödeme API", 10_000, 0, 0);
        monitor("http", 2, 5L, "Kart API", 10_000, 500, 12);       // %95,00 → hedefin altında
        monitor("ping", 3, 5L, "Ağ geçidi", 20_000, 10, 2);        // %99,95 → hedefte
        monitor("dns", 4, 5L, null, 1_000, 30, 3);                 // %97,00, adsız
        Map<String, Object> out = svc.build(ALL);

        assertThat(out).containsEntry("days", 7).containsEntry("target_pct", 99.9)
                .containsEntry("monitors", 4L).containsEntry("unmeasured", 0L)
                .containsEntry("checks", 41_000L).containsEntry("failed", 540L)
                .containsEntry("availability_pct", 98.68).containsEntry("meets_target", false).containsEntry("below_target", 2L);
        List<Map<String, Object>> worst = list(out, "worst");
        assertThat(worst).extracting(m -> m.get("id")).containsExactly(2L, 4L);
        assertThat(worst.get(0)).containsEntry("type", "http").containsEntry("name", "Kart API").containsEntry("up_pct", 95.0)
                .containsEntry("failed", 500L).containsEntry("checks", 10_000L).containsEntry("bad_hours", 12L);
        assertThat(worst.get(1)).containsEntry("name", null);
        assertThat(list(out, "by_type")).extracting(m -> m.get("type")).containsExactly("http", "ping", "dns");
        assertThat(list(out, "by_type").get(0)).containsEntry("monitors", 2L).containsEntry("availability_pct", 97.5).containsEntry("below_target", 1L);
        // İnceleme 2026-11-14: kartların ısıttığı 30 günlük ortak anahtar okunur, 7 gün onun dönemi — ayrı 7 günlük tarama YOK;
        // izlemesi olmayan tür için SLA taraması yok
        verify(sparklineService).availabilityAll("http", 30);
        verify(sparklineService, never()).availabilityAll(anyString(), eq(7));
        verify(sparklineService, never()).availabilityAll(eq("scripted"), anyInt());
    }

    @Test
    @DisplayName("hata varken asla %100; hatasız filo %100 ve hedefte; ölçüm yoksa oran null ve hüküm null (veri yok ≠ %100)")
    void neverHundredWithAFailure_andNoData() {
        monitor("http", 1, 5L, "A", 1_000_000, 1, 1);
        Map<String, Object> one = svc.build(ALL);
        assertThat(one).containsEntry("availability_pct", 99.99).containsEntry("meets_target", true).containsEntry("below_target", 0L);
        assertThat(list(one, "worst")).isEmpty();

        refs.clear(); avail.clear();
        monitor("http", 1, 5L, "A", 500, 0, 0);
        assertThat(svc.build(ALL)).containsEntry("availability_pct", 100.0).containsEntry("meets_target", true);

        refs.clear(); avail.clear();
        monitor("http", 1, 5L, "Yeni izleme", 0, 0, 0);
        Map<String, Object> none = svc.build(ALL);
        assertThat(none).containsEntry("monitors", 0L).containsEntry("unmeasured", 1L).containsEntry("checks", 0L)
                .containsEntry("availability_pct", null).containsEntry("meets_target", null);
        assertThat(list(none, "by_type")).isEmpty();
    }

    @Test
    @DisplayName("kapsam çağrı başına: USER / kapsamlı müdür yalnız takımlarını (takımsız satır yok), paylaşım ekler, tümünü gören hepsini")
    void scopeIsAppliedPerCall() {
        monitor("http", 1, 5L, "Benim", 1_000, 0, 0);
        monitor("http", 2, 9L, "Başka takım", 1_000, 500, 9);
        monitor("http", 3, null, "Takımsız eski satır", 1_000, 100, 4);
        monitor("http", 4, 5L, "Yeni", 0, 0, 0);

        Map<String, Object> mine = svc.build(teams(5L));
        assertThat(mine).containsEntry("monitors", 1L).containsEntry("unmeasured", 1L)
                .containsEntry("availability_pct", 100.0).containsEntry("below_target", 0L);
        assertThat(list(mine, "worst")).isEmpty();

        Map<String, Object> granted = svc.build(teams(5L, 9L));
        assertThat(granted).containsEntry("monitors", 2L).containsEntry("availability_pct", 75.0).containsEntry("below_target", 1L);
        assertThat(list(granted, "worst")).extracting(m -> m.get("name")).containsExactly("Başka takım");

        Map<String, Object> all = svc.build(ALL);
        assertThat(all).containsEntry("monitors", 3L).containsEntry("below_target", 2L);
        assertThat(list(all, "worst")).extracting(m -> m.get("id")).containsExactly(2L, 3L);

        assertThat(svc.build(t -> false)).containsEntry("monitors", 0L).containsEntry("availability_pct", null);
    }

    @Test
    @DisplayName("ölçülmemiş = TARANAN ama kontrolsüz izleme: duraklatılmış ve envanteri pasif türev Port / DNS satırı (takımlı da olsa) sayılmaz — İzleme Panosu kuralı; duraklatılmışın penceredeki ölçümü sayılır")
    void unmeasuredFollowsTheOverviewRule() {
        activeInventory.addAll(List.of("aktif.example.com", "Aktif.Example.com.tr"));
        monitor("http", 1, 5L, "Etkin, kontrolsüz", true, 0, 0, 0);                     // sayılır
        monitor("http", 2, 5L, "Duraklatılmış, kontrolsüz", false, 0, 0, 0);            // beklenen durum — sayılmaz
        network("port", 3, 5L, "Türev, envanteri aktif", false, "aktif.example.com", 0); // taranır — sayılır
        network("port", 4, 5L, "Türev, envanteri pasif", false, "pasif.example.com", 0); // tarama atlar — sayılmaz
        network("dns", 5, 5L, "Türev, harf farkı", false, "aktif.example.com.tr", 0);    // ham eşleşme: harf farkı = pasif
        network("port", 6, 5L, "Bağımsız, envanterde yok", true, "baska.example.com", 0); // bağımsız hep taranır — sayılır
        monitor("http", 7, 5L, "Dün duraklatıldı", false, 1_000, 10, 1);                // penceredeki ölçümü gerçek — orana girer
        Map<String, Object> out = svc.build(ALL);
        assertThat(out).containsEntry("unmeasured", 3L).containsEntry("monitors", 1L)
                .containsEntry("checks", 1_000L).containsEntry("failed", 10L).containsEntry("availability_pct", 99.0);
        verify(inventoryRepo, times(1)).findActiveDomains();   // görüntü başına bir kez
    }

    @Test
    @DisplayName("aktif envanter okunamazsa: türev satır varsa ölçülmemiş sayısı BİLİNMİYOR (null, yanlış sayı değil); türev satır yoksa sayı kesin; oran etkilenmez")
    void unmeasuredUnknownWhenInventoryUnreadable() {
        when(inventoryRepo.findActiveDomains()).thenThrow(new IllegalStateException("db"));
        monitor("http", 1, 5L, "Etkin, kontrolsüz", true, 0, 0, 0);
        monitor("http", 2, 5L, "Ölçülen", true, 1_000, 0, 0);
        Map<String, Object> direct = svc.build(ALL);
        assertThat(direct).containsEntry("unmeasured", 1L).containsEntry("availability_pct", 100.0).containsEntry("monitors", 1L);

        network("port", 3, 5L, "Türev, kontrolsüz", false, "pasif.example.com", 0);
        Map<String, Object> unknown = svc.build(ALL);
        assertThat(unknown).containsEntry("unmeasured", null).containsEntry("availability_pct", 100.0).containsEntry("monitors", 1L);
    }

    @Test
    @DisplayName("hedef altı hüküm KESİN orandan: 199.791 / 200.000 = %99,8955 → gösterim 99,90 ama hedef 99,9'un ALTINDA (asla yeşil değil); tam hedefte olan hedefte")
    void verdictFromTheExactRatio() {
        monitor("http", 1, 5L, "Sınırda", 200_000, 209, 3);   // 199.791 başarılı
        Map<String, Object> out = svc.build(ALL);
        assertThat(out).containsEntry("availability_pct", 99.9)   // yuvarlanmış gösterim
                .containsEntry("meets_target", false).containsEntry("below_target", 1L);
        assertThat(list(out, "worst")).extracting(m -> m.get("id")).containsExactly(1L);
        assertThat(list(out, "by_type").get(0)).containsEntry("below_target", 1L);

        refs.clear(); avail.clear();
        monitor("http", 1, 5L, "Tam hedefte", 1_000, 1, 0);    // 999 / 1000 = %99,9 tam
        assertThat(svc.build(ALL)).containsEntry("meets_target", true).containsEntry("below_target", 0L);

        assertThat(AvailabilityWeekService.meetsTarget(200_000, 209, 99.9)).isFalse();
        assertThat(AvailabilityWeekService.meetsTarget(200_000, 200, 99.9)).isTrue();
        assertThat(AvailabilityWeekService.meetsTarget(0, 0, 99.9)).isNull();
    }

    @Test
    @DisplayName("en kötü liste 5 satırla sınırlı; hedef canlı ayardan — bellekteki görüntü hedefi dondurmaz")
    void worstCapAndLiveTarget() {
        AvailabilityWeekService cached = service(60_000);
        for (int i = 1; i <= 8; i++) monitor("http", i, 5L, "İzleme " + i, 1_000, i * 10, i);
        Map<String, Object> out = cached.build(ALL);
        assertThat(out).containsEntry("below_target", 8L);
        assertThat(list(out, "worst")).hasSize(5).extracting(m -> m.get("id")).containsExactly(8L, 7L, 6L, 5L, 4L);

        // Hedef %95'e indirilir: aynı görüntüden (yeniden okuma yok) yalnız %95'in altındakiler ihlal sayılır
        when(appSettings.getDouble(eq("site.monitor.sla.target-pct"), anyDouble())).thenReturn(95.0);
        Map<String, Object> lower = cached.build(ALL);
        assertThat(lower).containsEntry("target_pct", 95.0).containsEntry("below_target", 3L);   // %94, %93, %92
        verify(sparklineService, times(1)).monitorRefs("http");
    }

    @Test
    @DisplayName("kurum geneli görüntü paylaşılır: iki kapsam TTL içinde tür başına TEK okuma, kullanıcılar arası sızıntı yok; TTL sonrası yeniden")
    void orgSnapshotShared() {
        AvailabilityWeekService cached = service(60_000);
        monitor("http", 1, 5L, "A", 1_000, 0, 0);
        monitor("http", 2, 9L, "B", 1_000, 100, 2);
        assertThat(cached.build(ALL)).containsEntry("monitors", 2L);          // önce geniş kapsam doldurur
        assertThat(cached.build(teams(5L))).containsEntry("monitors", 1L).containsEntry("below_target", 0L);
        assertThat(cached.build(teams(9L))).containsEntry("monitors", 1L).containsEntry("below_target", 1L);
        for (String type : MonitorSparklineService.KINDS.keySet()) verify(sparklineService, times(1)).monitorRefs(type);
        verify(sparklineService, times(1)).availabilityAll("http", 30);

        memoClock[0] += 60_001;
        monitor("http", 3, 5L, "C", 1_000, 0, 0);
        assertThat(cached.build(teams(5L))).containsEntry("monitors", 2L);
        verify(sparklineService, times(2)).monitorRefs("http");
    }

    @Test
    @DisplayName("bir tür okunamazsa UnavailableException (sekiz türün toplamı filo gibi gösterilmez) ve görüntü saklanmaz")
    void oneTypeUnreadable_isUnavailable() {
        AvailabilityWeekService cached = service(60_000);
        monitor("http", 1, 5L, "A", 1_000, 0, 0);
        monitor("ping", 2, 5L, "B", 1_000, 0, 0);
        when(sparklineService.availabilityAll(eq("ping"), anyInt())).thenThrow(new IllegalStateException("db"))
                .thenAnswer(i -> avail.get("ping"));
        assertThatThrownBy(() -> cached.build(ALL)).isInstanceOf(AvailabilityWeekService.UnavailableException.class);
        assertThat(cached.build(ALL)).containsEntry("monitors", 2L);   // aynı TTL: başarısız görüntü bellekte değil
    }
}
