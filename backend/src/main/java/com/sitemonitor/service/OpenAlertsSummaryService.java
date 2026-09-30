package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * İzleme menüsü rozetleri (2026-09-30): kullanıcının görüş kapsamındaki AÇIK alarmların izleme türü (menü sekmesi)
 * başına özeti — sayı, seviye kırılımı, sahiplenilmemiş sayısı ve en yeni birkaç alarm (üzerine gelince açılan
 * özet kartı için). Kapsam kuralı Alarm Geçmişi listesiyle AYNI ({@code AdminController.listAlerts}: global görüntüleyici /
 * 7-24 operatörü tümünü, diğerleri {@code viewTeamIds}; alarmın takımı damgadan ya da envanterin SY/UG'sinden).
 *
 * <p>Sayılar gruplu sorgudan ({@code countFilteredByType}) — kesin; seviye kırılımı ve örnek satırlar en yeni
 * {@value #SAMPLE} açık alarmdan (tek sayfa) — {@code sampled=true} ise kırılım örneklemdir. Menü her dakika yoklar;
 * iki sorgu, indeksli, küçük.
 */
@Service
@RequiredArgsConstructor
public class OpenAlertsSummaryService {

    /** Örnek satır sayfası (seviye kırılımı + son alarmlar). */
    static final int SAMPLE = 200;
    /** Sekme başına özet kartında gösterilen en yeni alarm sayısı. */
    static final int TOP = 5;

    private final AlertEventRepository alertEventRepo;
    private final TeamRepository teamRepo;

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
        out.put("sampled", false);

        boolean scoped = !seesAll;
        if (scoped && (viewTeamIds == null || viewTeamIds.isEmpty())) return out;   // kapsamsız → hiçbir alarm
        List<Long> scopeList = scoped ? viewTeamIds : List.of(-1L);

        long total = 0;
        for (Object[] row : alertEventRepo.countFilteredByType(false, null, null, null, null, null, scoped, scopeList)) {
            if (row == null || row.length < 2 || row[0] == null) continue;
            String family = MonitorTypeCatalog.typeOfAlert(String.valueOf(row[0]));
            long n = row[1] instanceof Number num ? num.longValue() : 0L;
            if (family == null || n <= 0) continue;
            Map<String, Object> tab = tabs.computeIfAbsent(family, k -> emptyTab());
            tab.put("count", ((Number) tab.get("count")).longValue() + n);
            total += n;
        }
        out.put("total", total);
        if (total == 0) return out;

        List<AlertEvent> sample = alertEventRepo.findFiltered(false, null, null, null, null, null, null,
                scoped, scopeList, PageRequest.of(0, SAMPLE, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        out.put("sampled", total > sample.size());

        Set<Long> teamIds = new HashSet<>();
        for (AlertEvent e : sample) if (e.getTeamId() != null) teamIds.add(e.getTeamId());
        Map<Long, String> teamNames = new HashMap<>();
        if (!teamIds.isEmpty()) {
            for (Team t : teamRepo.findAllById(teamIds)) if (t != null && t.getId() != null) teamNames.put(t.getId(), t.getName());
        }

        for (AlertEvent e : sample) {
            String family = MonitorTypeCatalog.typeOfAlert(e.getAlertType());
            if (family == null) continue;
            Map<String, Object> tab = tabs.computeIfAbsent(family, k -> emptyTab());
            @SuppressWarnings("unchecked") Map<String, Long> levels = (Map<String, Long>) tab.get("levels");
            String lvl = levelKey(e.getAlertLevel());
            levels.merge(lvl, 1L, Long::sum);
            if (!Boolean.TRUE.equals(e.getAcknowledged())) tab.put("unacked", ((Number) tab.get("unacked")).longValue() + 1);
            @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) tab.get("items");
            if (items.size() < TOP) items.add(item(e, teamNames.get(e.getTeamId())));
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

    private static Map<String, Object> item(AlertEvent e, String teamName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("domain", e.getDomain());
        m.put("alert_type", e.getAlertType());
        m.put("alert_level", e.getAlertLevel());
        m.put("created_at", e.getCreatedAt());
        m.put("acknowledged", Boolean.TRUE.equals(e.getAcknowledged()));
        m.put("team_id", e.getTeamId());
        m.put("team_name", teamName);
        m.put("storm_id", e.getStormId());
        return m;
    }
}
