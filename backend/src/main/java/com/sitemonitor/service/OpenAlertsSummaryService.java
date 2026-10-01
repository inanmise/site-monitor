package com.sitemonitor.service;

import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.util.TtlMemo;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * İzleme menüsü rozetleri (2026-09-30): kullanıcının görüş kapsamındaki AÇIK alarmların izleme türü (menü sekmesi)
 * başına özeti — sayı, seviye kırılımı, sahiplenilmemiş sayısı ve en yeni birkaç alarm (üzerine gelince açılan
 * özet kartı için). Kapsam kuralı Alarm Geçmişi listesiyle AYNI ({@code AdminController.listAlerts}: global görüntüleyici /
 * 7-24 operatörü tümünü, diğerleri {@code viewTeamIds}; alarmın takımı damgadan ya da envanterin SY/UG'sinden).
 *
 * <p><b>Performans (2026-10-01):</b> uç HER oturum açmış kullanıcı tarafından dakikada bir yoklanır. Eskiden çağrı başına
 * 3–4 sorgu (tip sayımı + 200 tam {@code AlertEvent} entity'si + sayfa COUNT'u + takım adları) atılır, seviye kırılımı
 * örneklenirdi ({@code sampled}). Şimdi:
 * <ol>
 *   <li>TEK gruplu sorgu ({@code countOpenByTypeLevelAck}: tip × seviye × sahiplenildi) → sayı, seviye kırılımı ve
 *       sahiplenilmemiş KESİN; {@code sampled} alanı korunur, hep {@code false}.</li>
 *   <li>Özet kartı satırları dar projeksiyondan ({@code findOpenSummaryItems}: 9 sütun, takım adı LEFT JOIN, COUNT yok) —
 *       en yeni {@value #ITEMS_WINDOW} açık alarm; pencere doluyken örneği {@value #TOP}'ten az kalan sekme (başka sekmenin
 *       yeni alarmları pencereyi doldurduysa) kendi tipleriyle ayrıca tamamlanır (nadir).</li>
 *   <li>{@link #cached} — kapsam anahtarı başına {@code site.monitor.open-alerts.cache-ms} (varsayılan 15 sn) bellek;
 *       {@code fresh=true} (alarm eylemi sonrası, {@code ?fresh=1}) belleği atlar ve tazeler.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class OpenAlertsSummaryService {

    /** Sekme başına özet kartında gösterilen en yeni alarm sayısı. */
    static final int TOP = 5;
    /** Özet kartı satırları için okunan en yeni açık alarm penceresi (dar projeksiyon). */
    static final int ITEMS_WINDOW = 60;
    /** Bellek anahtar tavanı (kapsam kombinasyonu) — aşılınca süresi dolanlar, yine doluysa tümü atılır. */
    static final int MEMO_MAX_KEYS = 500;

    private final AlertEventRepository alertEventRepo;

    /** Sunucu tarafı bellek penceresi (ms); 0 → kapalı (birim testlerinde {@code new} ile kurulunca varsayılan). */
    @Value("${site.monitor.open-alerts.cache-ms:15000}")
    long cacheMs;

    private final TtlMemo<Map<String, Object>> memo = new TtlMemo<>(MEMO_MAX_KEYS);

    /**
     * Denetleyici girişi: {@link #build} sonucunu görüş kapsamı anahtarı ({@code "ALL"} / sıralı takım id'leri) başına
     * {@link #cacheMs} boyunca paylaşır. {@code fresh=true} belleği atlar ve yeni sonucu yazar. Dönen harita PAYLAŞILIR —
     * çağıran değiştirmez (üst düzeyi kopyalar).
     */
    public Map<String, Object> cached(boolean seesAll, List<Long> viewTeamIds, boolean fresh) {
        if (!seesAll && (viewTeamIds == null || viewTeamIds.isEmpty())) return build(false, viewTeamIds);   // sorgusuz boş
        return memo.get(TtlMemo.scopeKey(seesAll, viewTeamIds), cacheMs, fresh, () -> build(seesAll, viewTeamIds));
    }

    /**
     * @param seesAll     global görüntüleyici / 7-24 operatörü — kapsam süzgeci yok
     * @param viewTeamIds kapsamlı kullanıcının görüş takımları (seesAll değilken; null/boş → hiçbir alarm)
     */
    public Map<String, Object> build(boolean seesAll, List<Long> viewTeamIds) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Map<String, Object>> tabs = new LinkedHashMap<>();
        for (String type : MonitorTypeCatalog.ORDER) tabs.put(type, emptyTab());
        out.put("tabs", tabs);
        out.put("total", 0L);
        out.put("sampled", false);   // 2026-10-01: kırılım artık kesin — alan geriye uyum için hep false

        boolean scoped = !seesAll;
        if (scoped && (viewTeamIds == null || viewTeamIds.isEmpty())) return out;   // kapsamsız → hiçbir alarm
        List<Long> scopeList = scoped ? viewTeamIds : List.of(-1L);

        // 1) Kesin sayılar: tip × seviye × sahiplenildi (tek gruplu sorgu)
        long total = 0;
        Map<String, Set<String>> typesByFamily = new LinkedHashMap<>();
        for (Object[] row : alertEventRepo.countOpenByTypeLevelAck(scoped, scopeList)) {
            if (row == null || row.length < 4 || row[0] == null) continue;
            String alertType = String.valueOf(row[0]);
            String family = MonitorTypeCatalog.typeOfAlert(alertType);
            long n = row[3] instanceof Number num ? num.longValue() : 0L;
            if (family == null || n <= 0) continue;
            Map<String, Object> tab = tabs.computeIfAbsent(family, k -> emptyTab());
            tab.put("count", ((Number) tab.get("count")).longValue() + n);
            @SuppressWarnings("unchecked") Map<String, Long> levels = (Map<String, Long>) tab.get("levels");
            levels.merge(levelKey(row[1] == null ? null : String.valueOf(row[1])), n, Long::sum);
            if (!Boolean.TRUE.equals(row[2])) tab.put("unacked", ((Number) tab.get("unacked")).longValue() + n);
            typesByFamily.computeIfAbsent(family, k -> new TreeSet<>()).add(alertType);
            total += n;
        }
        out.put("total", total);
        if (total == 0) return out;

        // 2) Özet kartı satırları: en yeni açık alarmlar (dar projeksiyon), sekmeye dağıtılır
        Set<String> knownTypes = new LinkedHashSet<>();
        for (Set<String> s : typesByFamily.values()) knownTypes.addAll(s);
        List<Object[]> window = alertEventRepo.findOpenSummaryItems(knownTypes, scoped, scopeList, PageRequest.of(0, ITEMS_WINDOW));
        Map<String, List<Object[]>> byFamily = new LinkedHashMap<>();
        for (Object[] r : window) {
            String family = r == null || r.length < 9 || r[2] == null ? null : MonitorTypeCatalog.typeOfAlert(String.valueOf(r[2]));
            if (family == null) continue;
            List<Object[]> l = byFamily.computeIfAbsent(family, k -> new ArrayList<>());
            if (l.size() < TOP) l.add(r);
        }
        // Pencere doluyken örneği eksik kalan sekme (başka sekmenin yeni alarmları pencereyi doldurdu) → kendi tipleriyle tamamla
        if (window.size() >= ITEMS_WINDOW) {
            for (Map.Entry<String, Set<String>> en : typesByFamily.entrySet()) {
                long count = ((Number) tabs.get(en.getKey()).get("count")).longValue();
                int got = byFamily.getOrDefault(en.getKey(), List.of()).size();
                if (got >= TOP || got >= count) continue;
                List<Object[]> own = new ArrayList<>();
                for (Object[] r : alertEventRepo.findOpenSummaryItems(en.getValue(), scoped, scopeList, PageRequest.of(0, TOP))) {
                    if (r != null && r.length >= 9 && own.size() < TOP) own.add(r);
                }
                byFamily.put(en.getKey(), own);
            }
        }
        for (Map.Entry<String, List<Object[]>> en : byFamily.entrySet()) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) tabs.get(en.getKey()).get("items");
            for (Object[] r : en.getValue()) items.add(item(r));
        }
        return out;
    }

    private static Map<String, Object> emptyTab() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", 0L);
        m.put("unacked", 0L);
        Map<String, Long> levels = new LinkedHashMap<>();
        levels.put("critical", 0L); levels.put("high", 0L); levels.put("warning", 0L); levels.put("other", 0L);
        m.put("levels", levels);
        m.put("items", new ArrayList<Map<String, Object>>());
        return m;
    }

    static String levelKey(String level) {
        String l = level == null ? "" : level.toUpperCase(Locale.ROOT);
        return switch (l) {
            case "CRITICAL" -> "critical";
            case "HIGH" -> "high";
            case "WARNING", "MEDIUM" -> "warning";
            default -> "other";
        };
    }

    /** Projeksiyon satırı ({@code findOpenSummaryItems} sütun sırası) → özet kartı öğesi (alan adları DEĞİŞMEDİ). */
    private static Map<String, Object> item(Object[] r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", asLong(r[0]));
        m.put("domain", str(r[1]));
        m.put("alert_type", str(r[2]));
        m.put("alert_level", str(r[3]));
        m.put("created_at", str(r[4]));
        m.put("acknowledged", Boolean.TRUE.equals(r[5]));
        m.put("team_id", asLong(r[6]));
        m.put("team_name", str(r[8]));
        m.put("storm_id", asLong(r[7]));
        return m;
    }

    private static Long asLong(Object v) { return v instanceof Number n ? n.longValue() : null; }

    private static String str(Object v) { return v == null ? null : String.valueOf(v); }
}
