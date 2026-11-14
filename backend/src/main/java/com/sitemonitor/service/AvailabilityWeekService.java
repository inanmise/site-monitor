package com.sitemonitor.service;

import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.util.AvailabilityMath;
import com.sitemonitor.util.TtlMemo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Kişisel Panom "7 günlük erişilebilirlik" göstergesi (2026-11-14, zaman serisi paketi): görüş kapsamındaki izlemelerin son 7
 * günde ÖLÇÜLEN erişilebilirliği (kontrol ağırlıklı: başarılı kontrol ÷ tüm kontrol) ve filo hedefi
 * ({@code site.monitor.sla.target-pct}) ile karşılaştırması; hedefin altındaki izleme sayısı ve en kötü birkaç izleme.
 *
 * <p><b>Kaynak yeni tarama DEĞİL:</b> kart SLA'sının ortak önbelleği {@link MonitorSparklineService#availabilityAll} (tür
 * başına 300 sn, tüm kullanıcılar paylaşır; hata kuralı kartlarla ve İstatistikler SLA bloğuyla AYNI {@code failExpr}).
 * Kartların ve İstatistikler'in zaten ısıttığı 30 günlük anahtar ({@code tür:30}) okunur ve 7 günlük değerler onun
 * {@code windows[days = 7]} döneminden alınır — aynı geçişte, aynı kesim anıyla ve aynı yuvarlamayla sayılır
 * ({@link MonitorSparklineService#WINDOWS}). İnceleme 2026-11-14: ayrı {@code tür:7} anahtarı her duvar ekranının dokuz
 * kontrol tablosunu 300 sn'de bir AYRICA taratmasına yol açıyordu.
 *
 * <p><b>Maliyet.</b> Kurum geneli görüntü ({@link OrgWeek}) pod başına {@code site.monitor.availability-week.cache-ms} (60 sn)
 * paylaşılır: tür başına 1 dar sorgu ({@code id, team_id, name, active} + Port / DNS'te {@code standalone} ve host / alan
 * adı) + aktif envanterin alan adları (1 sorgu) + ortak SLA önbelleği. Kapsam süzgeci ve toplam HER çağrıda bellekte —
 * çağrı başına sorgu yok, kullanıcıya özgü sonuç bellekte tutulmaz. Hedef çağrı anında canlı ayardan okunur.
 *
 * <p><b>Sayım kuralı</b> İstatistikler SLA bloğuyla aynı: pencerede EN AZ bir kontrolü olan izleme "ölçülmüş"tür (şu an
 * duraklatılmış olsa da ölçümleri sayılır). {@code unmeasured} = pencerede hiç kontrolü olmayan ve TARANAN izleme: duraklatılmış
 * izlemenin kontrolsüz olması beklenir; envanter türevi (bağımsız olmayan) Port / DNS satırı da envanterin takımını taşır ama
 * host'u AKTİF ağ envanterinde yoksa tarama onu bilerek atlar — İzleme Panosu onu "duraklatılmış · envanter pasif" sayar
 * ({@link MonitoringOverviewService#inventoryInactive}, ham ve büyük/küçük harf duyarlı eşleşme, taramayla BİREBİR). İkisi
 * de "ölçülemedi" diye raporlanmaz. Aktif envanter okunamazsa ve bu kurala bağlı bir satır varsa {@code unmeasured} null'dır
 * (bilinmiyor — yanlış sayı yerine). Takımsız satırların ölçümleri yalnız tüm izlemeleri görene sayılır — kart SLA ucunun
 * ({@code /api/monitoring/sla}) kapsam kuralı.
 *
 * <p><b>Hüküm KESİN orandan</b> (inceleme 2026-11-14): {@code availability_pct} iki ondalıklı ve yukarı yuvarlanmış gösterim
 * değeridir (199.791 / 200.000 = %99,8955 → "99,90"); {@code meets_target}, {@code below_target} ve en kötüler listesi
 * yuvarlanmış değerden DEĞİL, başarılı × 100 ≥ hedef × kontrol karşılaştırmasından (BigDecimal, kayan nokta kalıntısı yok)
 * çıkar — hedefin altındaki filo asla "hedefte" sayılmaz. Arayüz hedef altındaki değeri aşağı keserek gösterir.
 *
 * <p><b>Okunamayan veri sıfır DEĞİLDİR:</b> bir tür okunamazsa {@link UnavailableException} (denetleyici 503) — sekiz türün
 * toplamı "filonun tamamı" gibi gösterilmez; görüntü saklanmaz.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AvailabilityWeekService {

    public static final int DAYS = 7;
    /** Okunan ortak SLA önbellek anahtarının penceresi — kartlar ve İstatistikler de bunu ısıtır; 7 gün onun dönemidir. */
    static final int SOURCE_DAYS = 30;
    /** Yanıttaki en kötü (hedefin altındaki) izleme satırı tavanı. */
    static final int WORST = 5;
    private static final String ORG_KEY = "ORG";
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final MonitorSparklineService sparklineService;
    private final AppSettingsService appSettings;
    private final CertificateInventoryRepository inventoryRepo;

    /** Kurum geneli görüntünün ömrü (ms); 0 → kapalı (birim testinde {@code new} ile kurulunca varsayılan). */
    @Value("${site.monitor.availability-week.cache-ms:60000}")
    long cacheMs;

    /** Final değil: birim testi saat enjekte edilmiş bellekle değiştirir. */
    private TtlMemo<OrgWeek> orgMemo = new TtlMemo<>(2);

    /** SLA verisi okunamadı — denetleyici 503 döner (kutu "okunamadı" der, asla %0 / %100). */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException(Throwable cause) { super("7 günlük erişilebilirlik okunamadı", cause); }
    }

    /**
     * Tek izlemenin 7 günlük sayımı ({@code upPct} null = pencerede kontrol yok); {@code active} = duraklatılmamış;
     * {@code inventoryInactive} = envanter türevi Port / DNS satırı, host'u aktif ağ envanterinde yok (tarama atlar);
     * {@code inventoryUnknown} = envanter türevi satır ama aktif envanter okunamadı (kural uygulanamadı).
     */
    record Row(String type, long id, Long teamId, String name, boolean active, boolean inventoryInactive, boolean inventoryUnknown,
               long checks, long failed, Double upPct, long badHours) { }

    /** Kurum geneli görüntü — kullanıcıdan bağımsız. */
    record OrgWeek(List<Row> rows, String builtAt) { }

    /** Tür başına biriktirici. */
    private static final class Acc {
        long monitors, checks, failed, below;
    }

    /**
     * Kesin karşılaştırma: {@code (kontrol − hata) × 100 ≥ hedef × kontrol}. Kontrol yoksa null. Hedef {@link BigDecimal#valueOf}
     * ile ayarın ondalık yazımından alınır (99.9 → tam 99.9) — {@code double} çarpımının kalıntısı eşiği oynatmaz.
     */
    static Boolean meetsTarget(long checks, long failed, double target) {
        if (checks <= 0) return null;
        long ok = Math.max(0L, checks - Math.min(failed, checks));
        return BigDecimal.valueOf(ok).multiply(BigDecimal.valueOf(100))
                .compareTo(BigDecimal.valueOf(target).multiply(BigDecimal.valueOf(checks))) >= 0;
    }

    /**
     * @param canViewTeam izleme OKUMA kapsamı ({@code SessionScope.canViewMonitoring}); takımsız satır için {@code null} sorulur
     */
    public Map<String, Object> build(Predicate<Long> canViewTeam) {
        // Kart SLA ucu (MonitorSparklineController.SLA_TARGET_KEY) ve İstatistikler SLA bloğuyla aynı canlı ayar
        double target = appSettings.getDouble("site.monitor.sla.target-pct", 99.9);
        OrgWeek org = orgMemo.get(ORG_KEY, cacheMs, false, this::load);

        Map<String, Acc> byType = new LinkedHashMap<>();
        for (String type : MonitorSparklineService.KINDS.keySet()) byType.put(type, new Acc());
        Acc all = new Acc();
        long unmeasured = 0;
        boolean unmeasuredUnknown = false;
        List<Row> below = new ArrayList<>();
        for (Row r : org.rows()) {
            if (!canViewTeam.test(r.teamId())) continue;
            if (r.upPct() == null || r.checks() <= 0) {
                // Yalnız TARANAN izleme "ölçülmemiş" sayılır: duraklatılmış ve envanteri pasif türev Port / DNS satırı beklenen durum
                if (r.active() && !r.inventoryInactive()) {
                    if (r.inventoryUnknown()) unmeasuredUnknown = true; else unmeasured++;
                }
                continue;
            }
            Acc t = byType.computeIfAbsent(r.type(), k -> new Acc());
            t.monitors++; t.checks += r.checks(); t.failed += r.failed();
            all.monitors++; all.checks += r.checks(); all.failed += r.failed();
            if (!Boolean.TRUE.equals(meetsTarget(r.checks(), r.failed(), target))) { t.below++; all.below++; below.add(r); }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", DAYS);
        out.put("target_pct", target);
        out.put("monitors", all.monitors);
        out.put("unmeasured", unmeasuredUnknown ? null : unmeasured);   // null = envanter okunamadı, sayı bilinmiyor
        out.put("checks", all.checks);
        out.put("failed", all.failed);
        // Hata varken asla %100 (AvailabilityMath); ölçüm yoksa null — "veri yok" %100 DEĞİLDİR
        Double pct = AvailabilityMath.pct(all.checks, all.checks - all.failed, 2);
        out.put("availability_pct", pct);
        // Hüküm KESİN orandan (yuvarlanmış gösterim değerinden değil): %99,8955 hedef 99,9'un altındadır, "99,90" görünse de
        out.put("meets_target", meetsTarget(all.checks, all.failed, target));
        out.put("below_target", all.below);

        List<Map<String, Object>> types = new ArrayList<>();
        byType.forEach((type, a) -> {
            if (a.monitors == 0) return;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("monitors", a.monitors);
            m.put("checks", a.checks);
            m.put("failed", a.failed);
            m.put("availability_pct", AvailabilityMath.pct(a.checks, a.checks - a.failed, 2));
            m.put("below_target", a.below);
            types.add(m);
        });
        out.put("by_type", types);

        // En düşük KESİN oran önce (yuvarlanmış değerde eşit görünen iki izleme de doğru sıralanır)
        below.sort(Comparator.comparingDouble((Row r) -> (double) (r.checks() - r.failed()) / r.checks())
                .thenComparing(Row::type).thenComparingLong(Row::id));
        List<Map<String, Object>> worst = new ArrayList<>();
        for (Row r : below.subList(0, Math.min(WORST, below.size()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", r.type());
            m.put("id", r.id());
            m.put("name", r.name());
            m.put("up_pct", r.upPct());
            m.put("checks", r.checks());
            m.put("failed", r.failed());
            m.put("bad_hours", r.badHours());
            worst.add(m);
        }
        out.put("worst", worst);
        out.put("generated_at", org.builtAt());
        return out;
    }

    /** Kurum geneli görüntü: tür başına 1 dar sorgu + aktif envanter (1 sorgu) + ortak SLA önbelleği. Bir tür düşerse görüntü KURULMAZ. */
    private OrgWeek load() {
        // Aktif ağ envanteri GÖRÜNTÜ BAŞINA BİR KEZ (İzleme Panosu kuralının girdisi); okunamazsa yalnız "ölçülmemiş" sayımı bilinmez
        Set<String> activeHosts;
        try {
            activeHosts = new HashSet<>(inventoryRepo.findActiveDomains());
        } catch (RuntimeException e) {
            log.debug("7 günlük erişilebilirlik: aktif envanter okunamadı: {}", e.toString());
            activeHosts = null;
        }
        List<Row> rows = new ArrayList<>();
        try {
            for (String type : MonitorSparklineService.KINDS.keySet()) {
                Map<Long, MonitorSparklineService.MonitorRef> refs = sparklineService.monitorRefs(type);
                if (refs == null || refs.isEmpty()) continue;   // türde izleme yok — SLA taraması da gereksiz
                Map<Long, Map<String, Object>> avail = sparklineService.availabilityAll(type, SOURCE_DAYS);
                for (Map.Entry<Long, MonitorSparklineService.MonitorRef> e : refs.entrySet()) {
                    MonitorSparklineService.MonitorRef ref = e.getValue();
                    Map<String, Object> w = week(avail == null ? null : avail.get(e.getKey()));
                    long n = num(w, "n"), fail = num(w, "fail");
                    Double up = w != null && w.get("up_pct") instanceof Number p && n > 0 ? Double.valueOf(p.doubleValue()) : null;
                    // Port / DNS dışında standalone null → kural uygulanmaz; türev satır = standalone false
                    boolean derived = ref != null && Boolean.FALSE.equals(ref.standalone());
                    boolean inactive = derived && activeHosts != null
                            && MonitoringOverviewService.inventoryInactive(activeHosts, ref.standalone(), ref.host());
                    boolean unknown = derived && activeHosts == null;
                    rows.add(new Row(type, e.getKey(), ref == null ? null : ref.teamId(), ref == null ? null : ref.name(),
                            ref == null || ref.active(), inactive, unknown, n, Math.min(fail, n), up, num(w, "bad_hours")));
                }
            }
        } catch (RuntimeException e) {
            log.debug("7 günlük erişilebilirlik okunamadı: {}", e.toString());
            throw new UnavailableException(e);
        }
        return new OrgWeek(rows, ISO.format(Instant.now()));
    }

    /** 30 günlük ortak sonucun 7 günlük dönemi ({@code windows[days = 7]}); yoksa (izleme sonradan eklendi) null. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> week(Map<String, Object> monitor) {
        if (monitor == null || !(monitor.get("windows") instanceof List<?> wl)) return null;
        for (Object o : wl) {
            if (o instanceof Map<?, ?> w && w.get("days") instanceof Number d && d.intValue() == DAYS) return (Map<String, Object>) w;
        }
        return null;
    }

    private static long num(Map<String, Object> m, String key) {
        return m != null && m.get(key) instanceof Number n ? Math.max(0L, n.longValue()) : 0L;
    }
}
