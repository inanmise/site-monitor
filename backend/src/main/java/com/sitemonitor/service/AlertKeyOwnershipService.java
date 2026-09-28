package com.sitemonitor.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Alarm anahtarı (host / URL / alan adı / sentetik izleme adı) → SAHİP takımlar (2026-09-28, yayın öncesi
 * regresyon taraması).
 *
 * <p><b>Neden anahtar düzeyinde.</b> Bakım penceresi alarmı TÜRLE değil ANAHTARLA tanır:
 * {@link MaintenanceService#isUnderMaintenance} yalnız dizeye bakar, yani "b.example.com" hedefi o anahtarı
 * taşıyan HER izlemenin (ping, port, DNS, sertifika…) alarmını susturur. Teyit zincirleri de aynı anahtarla
 * tutulur. Bu yüzden "bu hedef kimin?" sorusu, anahtarı taşıyan TÜM satırların sahipleri üzerinden cevaplanır —
 * aynı anahtarı iki takım izliyorsa ikisi de sahiptir.
 *
 * <p><b>Kaynaklar.</b> Dokuz izleme tablosu (tablo / hedef sütunu / yumuşak-silme süzgeci TEK kaynaktan:
 * {@link GlobalSearchService#MONITOR_KINDS}) + sertifika envanteri. DNS/Port ÇİFT KAYNAKLIDIR: envanter-türevi
 * satırın ({@code standalone} ≠ TRUE) takımı ENVANTERİNKİDİR, satırdaki {@code team_id} değil
 * (MonitoringController.effectiveTeam ile aynı kural); envanter kaydı yoksa saklanan değere düşülür. Envanter
 * satırının sahibi SY takımıdır (yoksa UG); UG takımı yalnız {@link #viewerTeams} kümesine girer.
 * Yumuşak silinen satırlar ({@code deleted_at}) sayılmaz; duraklatılmış izleme SAYILIR (sürdürülünce alarm
 * yeniden üretir).
 *
 * <p><b>Takımsız satır</b> kümeye {@code null} olarak girer — çağıran bunu "yalnız global yönetici" diye okur.
 * Hiçbir satırda geçmeyen anahtar haritada YOKTUR.
 *
 * <p><b>Hata = istisna.</b> Sorgular best-effort DEĞİL (palet aramasının aksine): bir tablonun sessizce
 * atlanması o tablonun sahiplerini kümeden düşürür ve kapı yanlış yere AÇILIR. Çağıran istisnayı
 * reddetme olarak ele alır.
 */
@Service
@RequiredArgsConstructor
public class AlertKeyOwnershipService {

    /** IN listesi parça boyutu — sürücü parametre tavanlarının güvenle altında. */
    static final int CHUNK = 500;

    private final JdbcTemplate jdbc;

    /** anahtar → sahip takımlar ({@code null} = takımsız satır). Yazma kapıları (bakım penceresi) bunu kullanır. */
    public Map<String, Set<Long>> ownerTeams(Collection<String> keys) {
        return resolve(keys, false);
    }

    /** anahtar → görüş takımları: sahipler + envanterin UG (uç gözetim) takımı. Okuma yüzeyleri bunu kullanır. */
    public Map<String, Set<Long>> viewerTeams(Collection<String> keys) {
        return resolve(keys, true);
    }

    private Map<String, Set<Long>> resolve(Collection<String> keys, boolean withOversight) {
        Map<String, Set<Long>> out = new HashMap<>();
        if (keys == null || keys.isEmpty()) return out;
        List<String> distinct = keys.stream().filter(Objects::nonNull).distinct().toList();
        for (int i = 0; i < distinct.size(); i += CHUNK) {
            List<String> part = distinct.subList(i, Math.min(distinct.size(), i + CHUNK));
            String in = String.join(",", Collections.nCopies(part.size(), "?"));
            Object[] args = part.toArray();

            // Envanter ÖNCE: çift kaynaklı DNS/Port satırı sahibini buradan alır.
            Set<String> inventoried = new HashSet<>();
            jdbc.query("SELECT domain, team_id, ug_team_id FROM certificate_inventory"
                    + " WHERE deleted_at IS NULL AND domain IN (" + in + ")", rs -> {
                String key = rs.getString("domain");
                Long sy = longOrNull(rs, "team_id"), ug = longOrNull(rs, "ug_team_id");
                inventoried.add(key);
                Set<Long> s = out.computeIfAbsent(key, k -> new HashSet<>());
                s.add(sy != null ? sy : ug);
                if (withOversight && ug != null) s.add(ug);
            }, args);

            for (Map.Entry<String, GlobalSearchService.Kind> e : GlobalSearchService.MONITOR_KINDS.entrySet()) {
                GlobalSearchService.Kind k = e.getValue();
                boolean dual = "port".equals(e.getKey()) || "dns".equals(e.getKey());
                String sql = "SELECT " + k.targetCol() + " AS target, team_id" + (dual ? ", standalone" : "")
                        + " FROM " + k.table() + " WHERE " + k.targetCol() + " IN (" + in + ")"
                        + (k.liveFilter() != null ? " AND " + k.liveFilter() : "");
                jdbc.query(sql, rs -> {
                    String key = rs.getString("target");
                    // Envanter-türevi DNS/Port: sahibi envanter (yukarıda eklendi); saklanan team_id bayat olabilir.
                    if (dual && !rs.getBoolean("standalone") && inventoried.contains(key)) return;
                    out.computeIfAbsent(key, x -> new HashSet<>()).add(longOrNull(rs, "team_id"));
                }, args);
            }
        }
        return out;
    }

    private static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
