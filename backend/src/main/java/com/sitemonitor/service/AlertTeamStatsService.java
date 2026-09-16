package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Alarm Geçmişi takım kırılımı (2026-09-16, kullanıcı isteği): "hangi takımın kaç alarmı var,
 * kaçı açık, kaçı kapandı, son hafta/ay kaç tane açıldı?".
 *
 * <p><b>Alarmın takımı iki kaynaktan gelir.</b> İzleme alarmlarında satırın kendi {@code team_id}'si;
 * sertifika alarmlarında satırda takım YOKTUR, alan adı envanterden çözülür (SY takımı). İkisi de
 * sayılmazsa tablo eksik çıkar — envanter türevli alarmlar sessizce kaybolurdu.
 *
 * <p>Kapsam: çağıran global görüntüleyici değilse yalnız görebildiği takımlar sayılır; takımı
 * çözülemeyen alarmlar "atanmamış" satırında toplanır (yalnız global görüntüleyiciye).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertTeamStatsService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** Tarama penceresi: 30 günlük sayaç için yeterli; açık alarmlar pencereden bağımsız sayılır. */
    static final int WINDOW_DAYS = 30;

    private final AlertEventRepository alertEventRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final TeamRepository teamRepo;

    /** Satır: takım → açık / kapalı / son 7 gün / son 30 gün. */
    private static final class Row {
        long open, closed, last7, last30;
    }

    public Map<String, Object> build(Predicate<Long> canViewTeam, boolean globalViewer) {
        Map<String, Long> domainTeam = new LinkedHashMap<>();
        try {
            for (CertificateInventory i : inventoryRepo.findByActiveTrueOrderByDomainAsc())
                if (i.getDomain() != null && i.getTeamId() != null) domainTeam.put(i.getDomain(), i.getTeamId());
        } catch (Exception e) {
            log.debug("takım kırılımı: envanter okunamadı: {}", e.toString());
        }

        String since30 = ISO.format(Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS));
        String since7 = ISO.format(Instant.now().minus(7, ChronoUnit.DAYS));

        Map<Long, Row> rows = new LinkedHashMap<>();
        Row unassigned = new Row();
        long totalOpen = 0, totalClosed = 0, total7 = 0, total30 = 0;

        List<AlertEvent> events = new ArrayList<>();
        try {
            events.addAll(alertEventRepo.findAllOpenOrderBySeverity());                              // açıklar: pencereden bağımsız
            events.addAll(alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(since30));
        } catch (Exception e) {
            log.debug("takım kırılımı: alarm okunamadı: {}", e.toString());
        }

        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (AlertEvent e : events) {
            if (e.getId() != null && !seen.add(e.getId())) continue;   // iki sorgunun kesişimi bir kez sayılır
            Long teamId = e.getTeamId() != null ? e.getTeamId()
                    : (e.getDomain() != null ? domainTeam.get(e.getDomain()) : null);
            if (teamId == null && !globalViewer) continue;             // takımı çözülemeyen alarm kapsam dışıdır
            if (teamId != null && !canViewTeam.test(teamId)) continue;
            Row r = teamId == null ? unassigned : rows.computeIfAbsent(teamId, k -> new Row());
            boolean open = !Boolean.TRUE.equals(e.getResolved());
            if (open) { r.open++; totalOpen++; } else { r.closed++; totalClosed++; }
            String createdAt = e.getCreatedAt();
            if (createdAt != null && createdAt.compareTo(since30) >= 0) { r.last30++; total30++; }
            if (createdAt != null && createdAt.compareTo(since7) >= 0) { r.last7++; total7++; }
        }

        Map<Long, String> names = new LinkedHashMap<>();
        try { for (Team t : teamRepo.findAll()) names.put(t.getId(), t.getName()); }
        catch (Exception e) { log.debug("takım kırılımı: takım adları okunamadı: {}", e.toString()); }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, Row> en : rows.entrySet()) out.add(row(en.getKey(), names.get(en.getKey()), en.getValue()));
        if (unassigned.open + unassigned.closed > 0) out.add(row(null, null, unassigned));
        // En çok AÇIK alarmı olan takım üstte; eşitlikte son 7 gün, sonra ad. "Takımı çözülemeyen"
        // satırı DAİMA en sonda: bir takım değil, artık kalan kümedir — sıralamaya girerse (adı boş
        // olduğu için) listenin başına oturuyordu.
        out.sort((a, b) -> {
            boolean an = a.get("team_id") == null, bn = b.get("team_id") == null;
            if (an != bn) return an ? 1 : -1;
            int c = Long.compare((Long) b.get("open"), (Long) a.get("open"));
            if (c != 0) return c;
            c = Long.compare((Long) b.get("last7"), (Long) a.get("last7"));
            if (c != 0) return c;
            return String.valueOf(a.get("team_name")).compareToIgnoreCase(String.valueOf(b.get("team_name")));
        });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("teams", out);
        data.put("window_days", WINDOW_DAYS);
        data.put("total_open", totalOpen);
        data.put("total_closed", totalClosed);
        data.put("total_last7", total7);
        data.put("total_last30", total30);
        return data;
    }

    private static Map<String, Object> row(Long teamId, String name, Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("team_id", teamId);
        m.put("team_name", name);
        m.put("open", r.open);
        m.put("closed", r.closed);
        m.put("last7", r.last7);
        m.put("last30", r.last30);
        return m;
    }
}
