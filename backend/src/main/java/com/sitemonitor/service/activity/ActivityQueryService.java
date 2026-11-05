package com.sitemonitor.service.activity;

import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.service.activity.ActivityFilter.Dim;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Aktivite listesi ve özet şeridi sorguları (2026-11-05, aktivite zenginleştirmesi E). Süzgeç {@link ActivityFilter}'den
 * gelir (TEK kural); sorgular yerel SQL (JDBC) — süzgeç, aktivite satırına sonradan eklenen kolonları ({@code failure_reason})
 * da kullanır.
 *
 * <p><b>Sorgu sayısı sabit:</b> liste = sayım + sayfa (2); özet = durum sayımı (+ son zaman, aynı sorguda) + tür × eylem
 * sayımı (tür ve grup sayıları ondan Java'da çıkarılır) + saat × durum serisi (3 — önceki sürümde 4).
 *
 * <p>Satırlar {@code SELECT *} ile okunur ve {@link ActivityLog}'a ADLA eşlenir ({@link BeanPropertyRowMapper}): varlığa
 * eklenen her yeni kolon listede kendiliğinden görünür; varlıkta karşılığı olmayan kolon yok sayılır.
 */
@Service
public class ActivityQueryService {

    static final BeanPropertyRowMapper<ActivityLog> ROW = BeanPropertyRowMapper.newInstance(ActivityLog.class);

    private final JdbcTemplate jdbc;

    public ActivityQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PageResult(List<ActivityLog> rows, long total) {}

    /** Sayfalı akış — en yeni üstte, eşitlikte {@code id DESC} (sayfa sınırında satır yinelenmez / kaybolmaz). */
    public PageResult list(ActivityFilter f, int page, int size) {
        ActivityFilter.Sql w = f.where();
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM activity_log " + w.where(), Long.class, w.array());
        long t = total == null ? 0L : total;
        if (t == 0 || (long) page * size >= t) return new PageResult(List.of(), t);
        ActivityFilter.Sql p = new ActivityFilter.Sql(w.where() + " ORDER BY activity_time DESC, id DESC LIMIT ? OFFSET ?",
                append(w.args(), size, (long) page * size));
        return new PageResult(jdbc.query("SELECT * FROM activity_log " + p.where(), ROW, p.array()), t);
    }

    /** Özet ham verisi — denetleyici biçimlendirir. */
    public record SummaryData(Map<String, Long> statusCounts, String lastActivity, Map<String, Long> typeCounts,
                              Map<String, Long> groupCounts, List<Object[]> hourRows) {}

    /**
     * Özet: (1) durum başına sayım + en son zaman — DURUM süzgeci hariç; (2) tür × eylem sayımı — TÜR ve GRUP süzgeçleri
     * hariç, durum dahil: {@code type_counts} grup süzgecini Java'da uygular (tür hariç), {@code group_counts} tür süzgecini
     * (grup hariç) — her çip kendi süzgeci seçili olmasa da kendi sayısını gösterir; (3) saat × durum — durum hariç,
     * {@code seriesFrom} sonrası.
     */
    public SummaryData summary(ActivityFilter f, String seriesFrom) {
        Map<String, Long> counts = new LinkedHashMap<>();
        String last = null;
        ActivityFilter.Sql s1 = f.where(EnumSet.of(Dim.STATUS));
        for (Map<String, Object> r : jdbc.queryForList("SELECT result_status AS st, COUNT(*) AS c, MAX(activity_time) AS mx"
                + " FROM activity_log " + s1.where() + " GROUP BY result_status", s1.array())) {
            counts.merge(String.valueOf(r.get("st")), num(r.get("c")), Long::sum);
            Object mx = r.get("mx");
            if (mx != null && (last == null || mx.toString().compareTo(last) > 0)) last = mx.toString();
        }

        Map<String, Long> typeCounts = new TreeMap<>();
        Map<String, Long> groupCounts = new LinkedHashMap<>();
        for (String g : ActivityFilter.GROUPS) groupCounts.put(g, 0L);
        ActivityFilter.Sql s2 = f.where(EnumSet.of(Dim.TYPE, Dim.GROUP));
        for (Map<String, Object> r : jdbc.queryForList("SELECT monitor_type AS ty, action AS ac, COUNT(*) AS c FROM activity_log "
                + s2.where() + " GROUP BY monitor_type, action", s2.array())) {
            String type = r.get("ty") == null ? null : r.get("ty").toString();
            String group = ActivityFilter.groupOf(r.get("ac") == null ? null : r.get("ac").toString());
            long c = num(r.get("c"));
            if (type != null && (f.groups().isEmpty() || f.groups().contains(group))) typeCounts.merge(type, c, Long::sum);
            if (f.types().isEmpty() || (type != null && f.types().contains(type))) groupCounts.merge(group, c, Long::sum);
        }

        ActivityFilter.Sql s3 = f.where(EnumSet.of(Dim.STATUS)).and("activity_time >= ?", seriesFrom);
        List<Object[]> hours = jdbc.query("SELECT SUBSTRING(activity_time, 1, 13) AS hr, result_status, COUNT(*) AS c"
                        + " FROM activity_log " + s3.where() + " GROUP BY SUBSTRING(activity_time, 1, 13), result_status",
                (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3)}, s3.array());
        return new SummaryData(counts, last, typeCounts, groupCounts, hours);
    }

    static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static List<Object> append(List<Object> args, Object... more) {
        List<Object> a = new java.util.ArrayList<>(args);
        a.addAll(List.of(more));
        return a;
    }
}
