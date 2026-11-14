package com.sitemonitor.service;

import com.sitemonitor.util.AvailabilityMath;
import com.sitemonitor.util.TtlMemo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Kişisel Panom "7 günlük erişilebilirlik" göstergesi (2026-11-14, zaman serisi paketi): görüş kapsamındaki izlemelerin son 7
 * günde ÖLÇÜLEN erişilebilirliği (kontrol ağırlıklı: başarılı kontrol ÷ tüm kontrol) ve filo hedefi
 * ({@code site.monitor.sla.target-pct}) ile karşılaştırması; hedefin altındaki izleme sayısı ve en kötü birkaç izleme.
 *
 * <p><b>Kaynak yeni tarama DEĞİL:</b> kart SLA'sının ortak önbelleği {@link MonitorSparklineService#availabilityAll} (tür
 * başına 300 sn, tüm kullanıcılar paylaşır; hata kuralı kartlarla ve İstatistikler SLA bloğuyla AYNI {@code failExpr}). 7 günlük
 * anahtar ({@code tür:7}) okunur — 30 günlük taramanın dörtte biri; yalnız duvar ekranı açıkken de sınırlı kalır.
 *
 * <p><b>Maliyet.</b> Kurum geneli görüntü ({@link OrgWeek}: 9 türün izleme başına kontrol / hata sayısı + takımı + adı) pod
 * başına {@code site.monitor.availability-week.cache-ms} (60 sn) paylaşılır: tür başına 1 dar sorgu ({@code id, team_id, name})
 * + ortak SLA önbelleği (soğuksa tür başına 3 sorgu). Kapsam süzgeci ve toplam HER çağrıda bellekte — çağrı başına sorgu yok,
 * kullanıcıya özgü sonuç bellekte tutulmaz. Hedef çağrı anında canlı ayardan okunur (bellekte değil).
 *
 * <p><b>Sayım kuralı</b> İstatistikler SLA bloğuyla aynı: pencerede EN AZ bir kontrolü olan izleme "ölçülmüş"tür (şu an
 * duraklatılmış olsa da ölçümleri sayılır). {@code unmeasured} = pencerede hiç kontrolü olmayan ETKİN ve TAKIMLI izleme:
 * duraklatılmış izlemenin kontrolsüz olması beklenir, takımsız satır ise envanter türevi Port / DNS kaydıdır — envanteri
 * pasifse tarama onu bilerek atlar (İzleme Panosu da "duraklatılmış" sayar); ikisi de "ölçülemedi" diye raporlanmaz.
 * Takımsız / envanter türevi satırların ÖLÇÜMLERİ yalnız tüm izlemeleri görene sayılır — kart SLA ucunun
 * ({@code /api/monitoring/sla}) kuralı.
 *
 * <p><b>Okunamayan veri sıfır DEĞİLDİR:</b> bir tür okunamazsa {@link UnavailableException} (denetleyici 503) — sekiz türün
 * toplamı "filonun tamamı" gibi gösterilmez; görüntü saklanmaz.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AvailabilityWeekService {

    public static final int DAYS = 7;
    /** Yanıttaki en kötü (hedefin altındaki) izleme satırı tavanı. */
    static final int WORST = 5;
    private static final String ORG_KEY = "ORG";
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final MonitorSparklineService sparklineService;
    private final AppSettingsService appSettings;

    /** Kurum geneli görüntünün ömrü (ms); 0 → kapalı (birim testinde {@code new} ile kurulunca varsayılan). */
    @Value("${site.monitor.availability-week.cache-ms:60000}")
    long cacheMs;

    /** Final değil: birim testi saat enjekte edilmiş bellekle değiştirir. */
    private TtlMemo<OrgWeek> orgMemo = new TtlMemo<>(2);

    /** SLA verisi okunamadı — denetleyici 503 döner (kutu "okunamadı" der, asla %0 / %100). */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException(Throwable cause) { super("7 günlük erişilebilirlik okunamadı", cause); }
    }

    /** Tek izlemenin 7 günlük sayımı ({@code upPct} null = pencerede kontrol yok); {@code active} = duraklatılmamış. */
    record Row(String type, long id, Long teamId, String name, boolean active, long checks, long failed, Double upPct, long badHours) { }

    /** Kurum geneli görüntü — kullanıcıdan bağımsız. */
    record OrgWeek(List<Row> rows, String builtAt) { }

    /** Tür başına biriktirici. */
    private static final class Acc {
        long monitors, checks, failed, below;
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
        List<Row> below = new ArrayList<>();
        for (Row r : org.rows()) {
            if (!canViewTeam.test(r.teamId())) continue;
            if (r.upPct() == null || r.checks() <= 0) {
                // Yalnız etkin + takımlı izleme "ölçülmemiş" sayılır (duraklatılmış / envanter türevi satır beklenen durumdur)
                if (r.active() && r.teamId() != null) unmeasured++;
                continue;
            }
            Acc t = byType.computeIfAbsent(r.type(), k -> new Acc());
            t.monitors++; t.checks += r.checks(); t.failed += r.failed();
            all.monitors++; all.checks += r.checks(); all.failed += r.failed();
            if (r.upPct() < target) { t.below++; all.below++; below.add(r); }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", DAYS);
        out.put("target_pct", target);
        out.put("monitors", all.monitors);
        out.put("unmeasured", unmeasured);
        out.put("checks", all.checks);
        out.put("failed", all.failed);
        // Hata varken asla %100 (AvailabilityMath); ölçüm yoksa null — "veri yok" %100 DEĞİLDİR
        Double pct = AvailabilityMath.pct(all.checks, all.checks - all.failed, 2);
        out.put("availability_pct", pct);
        // Hüküm gösterilen (iki ondalıklı) değerden: ekrandaki sayı ile "hedefte / altında" çelişmez
        out.put("meets_target", pct == null ? null : pct >= target);
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

        below.sort(Comparator.comparingDouble((Row r) -> r.upPct()).thenComparing(Row::type).thenComparingLong(Row::id));
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

    /** Kurum geneli görüntü: tür başına 1 dar sorgu + ortak SLA önbelleği. Bir tür düşerse görüntü KURULMAZ. */
    private OrgWeek load() {
        List<Row> rows = new ArrayList<>();
        try {
            for (String type : MonitorSparklineService.KINDS.keySet()) {
                Map<Long, MonitorSparklineService.MonitorRef> refs = sparklineService.monitorRefs(type);
                if (refs == null || refs.isEmpty()) continue;   // türde izleme yok — SLA taraması da gereksiz
                Map<Long, Map<String, Object>> avail = sparklineService.availabilityAll(type, DAYS);
                refs.forEach((id, ref) -> {
                    Map<String, Object> a = avail == null ? null : avail.get(id);
                    long n = num(a, "n"), fail = num(a, "fail");
                    Double up = a != null && a.get("up_pct") instanceof Number p && n > 0 ? Double.valueOf(p.doubleValue()) : null;
                    rows.add(new Row(type, id, ref == null ? null : ref.teamId(), ref == null ? null : ref.name(),
                            ref == null || ref.active(), n, Math.min(fail, n), up, num(a, "bad_hours")));
                });
            }
        } catch (RuntimeException e) {
            log.debug("7 günlük erişilebilirlik okunamadı: {}", e.toString());
            throw new UnavailableException(e);
        }
        return new OrgWeek(rows, ISO.format(Instant.now()));
    }

    private static long num(Map<String, Object> m, String key) {
        return m != null && m.get(key) instanceof Number n ? Math.max(0L, n.longValue()) : 0L;
    }
}
