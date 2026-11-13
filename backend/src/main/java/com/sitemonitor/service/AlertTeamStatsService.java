package com.sitemonitor.service;

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

    /**
     * Okuma hatası (hata taraması 2026-11-13): envanter ya da alarm okunamazsa fırlar — eskiden yutulup BOŞ tablo dönüyordu,
     * İstatistikler takım karnesi her takıma "0 alarm", Alarm Geçmişi paneli "takım yok" yazıyordu. Denetleyici 503 döner.
     */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException(Throwable cause) { super("alarm takım kırılımı okunamadı", cause); }
    }

    public Map<String, Object> build(Predicate<Long> canViewTeam, boolean globalViewer) {
        Map<String, Long> domainTeam = new LinkedHashMap<>();
        Map<String, Long> domainUg = new LinkedHashMap<>();
        String since30 = ISO.format(Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS));
        String since7 = ISO.format(Instant.now().minus(7, ChronoUnit.DAYS));
        List<Object[]> events = new ArrayList<>();
        try {
            // Dar izdüşüm (2026-11-13, performans): tam envanter / alarm entity'si (context_json dahil) okunmaz
            for (Object[] r : inventoryRepo.findActiveDomainTeams()) {
                if (r == null || r.length < 2 || r[0] == null) continue;
                String domain = String.valueOf(r[0]);
                if (r[1] instanceof Number n) domainTeam.put(domain, n.longValue());
                if (r.length > 2 && r[2] instanceof Number u) domainUg.put(domain, u.longValue());
            }
            events.addAll(alertEventRepo.findOpenTeamStatRows());                                  // açıklar: pencereden bağımsız
            events.addAll(alertEventRepo.findTeamStatRowsSince(since30));
        } catch (RuntimeException e) {
            log.warn("takım kırılımı: envanter / alarm okunamadı: {}", e.toString());
            throw new UnavailableException(e);
        }

        Map<Long, Row> rows = new LinkedHashMap<>();
        Row unassigned = new Row();
        long totalOpen = 0, totalClosed = 0, total7 = 0, total30 = 0;

        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Object[] r : events) {
            if (r == null || r.length < 5) continue;
            Long id = r[0] instanceof Number n ? Long.valueOf(n.longValue()) : null;
            Long teamId = r[1] instanceof Number n ? Long.valueOf(n.longValue()) : null;
            String eventDomain = r[2] == null ? null : String.valueOf(r[2]);
            Boolean resolved = r[3] instanceof Boolean b ? b : null;
            String createdAt = r[4] == null ? null : String.valueOf(r[4]);
            if (id != null && !seen.add(id)) continue;   // iki sorgunun kesişimi bir kez sayılır
            // Alarmın takımları (2026-10-22, ALERTS-3 / DATA-5): damga + envanterin SY ve UG takımı — hücre penceresinin
            // açtığı Alarm Geçmişi süzgeciyle (teamId = X → damga ya da envanter SY / UG) AYNI küme; böylece satırdaki
            // "(N)" pencerede listelenen satır sayısına eşit olur ve UG takımı kendi alarmlarını görür. Toplamlar her
            // alarmı BİR kez sayar.
            java.util.LinkedHashSet<Long> owners = new java.util.LinkedHashSet<>();
            if (teamId != null) owners.add(teamId);
            if (eventDomain != null) {
                Long sy = domainTeam.get(eventDomain);
                Long ug = domainUg.get(eventDomain);
                if (sy != null) owners.add(sy);
                if (ug != null) owners.add(ug);
            }
            List<Row> targets = new ArrayList<>();
            if (owners.isEmpty()) {
                if (!globalViewer) continue;                            // takımı çözülemeyen alarm kapsam dışıdır
                targets.add(unassigned);
            } else {
                for (Long t : owners) if (canViewTeam.test(t)) targets.add(rows.computeIfAbsent(t, k -> new Row()));
                if (targets.isEmpty()) continue;
            }
            boolean open = !Boolean.TRUE.equals(resolved);
            boolean in30 = createdAt != null && createdAt.compareTo(since30) >= 0;
            boolean in7 = createdAt != null && createdAt.compareTo(since7) >= 0;
            for (Row acc : targets) {
                if (open) acc.open++; else acc.closed++;
                if (in30) acc.last30++;
                if (in7) acc.last7++;
            }
            if (open) totalOpen++; else totalClosed++;
            if (in30) total30++;
            if (in7) total7++;
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
