package com.sitemonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * İzleme kartı mini trendi (2026-09-12, zenginleştirme #4 + #14): tür başına TEK toplu sorguyla her
 * monitörün son N saatlik saatlik kovaları (adet / hata / ort. süre) ve son 5 kontrolü (ok / süre / saat).
 *
 * <p>Kart başına ayrı istek YOK (50 kart = 50 istek olurdu); sayfa tür için bir kez çeker, görünürken
 * tazeler. Sorgu NATİF ve kovalı: ham satır taşımaz (dakikalık kontrolde 24 saat × 100 monitör = 144k
 * satır olurdu) — {@code substr(checked_at,1,13)} saat kovası Postgres/H2/SQLite'ta aynı çalışır;
 * son-5 için {@code ROW_NUMBER() OVER (PARTITION BY …)} (Postgres, H2 ≥ 1.4.198).
 *
 * <p>Takım kapsaması ÇAĞIRANDA: bu servis monitör id → takım id haritasını döner, controller
 * {@code SessionScope.canView} ile süzer (IDOR).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonitorSparklineService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    public static final int MAX_HOURS = 24 * 7;
    static final int LAST_N = 5;

    /** Tür → (kontrol tablosu, monitör tablosu, süre sütunu, hata ifadesi). Sütunlar Hibernate snake_case. */
    record Kind(String table, String monitorTable, String msCol, String failExpr) {}

    static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("http",      new Kind("http_checks",      "http_monitors",      "response_ms", "CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END"));
        KINDS.put("ping",      new Kind("ping_checks",      "ping_monitors",      "rtt_ms",      "CASE WHEN error IS NOT NULL OR packet_loss >= 100 THEN 1 ELSE 0 END"));
        KINDS.put("port",      new Kind("port_checks",      "port_monitors",      "response_ms", "CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END"));
        KINDS.put("dns",       new Kind("dns_records",      "dns_monitors",       "response_ms", "CASE WHEN changed = TRUE THEN 1 ELSE 0 END"));
        KINDS.put("keyword",   new Kind("keyword_results",  "keyword_monitors",   "response_ms", "CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END"));
        KINDS.put("page",      new Kind("page_checks",      "page_monitors",      "response_ms", "CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END"));
        KINDS.put("pagespeed", new Kind("pagespeed_checks", "pagespeed_monitors", "response_ms", "CASE WHEN error_message IS NOT NULL OR (breached_metrics IS NOT NULL AND breached_metrics <> '') THEN 1 ELSE 0 END"));
        KINDS.put("scripted",  new Kind("scripted_checks",  "scripted_monitors",  "duration_ms", "CASE WHEN error IS NOT NULL OR exit_code <> 0 OR checks_failed > 0 THEN 1 ELSE 0 END"));
    }

    private final JdbcTemplate jdbc;

    public static boolean supports(String type) { return type != null && KINDS.containsKey(type); }

    /** Monitör id → takım id (null = takımsız/envanter türevi). Çağıran görünürlük süzgecini uygular. */
    public Map<Long, Long> monitorTeams(String type) {
        Kind k = KINDS.get(type);
        Map<Long, Long> out = new HashMap<>();
        jdbc.query("SELECT id, team_id FROM " + k.monitorTable(), rs -> {
            long id = rs.getLong("id");
            long tid = rs.getLong("team_id");
            out.put(id, rs.wasNull() ? null : tid);
        });
        return out;
    }

    /**
     * @param ids görünür monitör id'leri (boş → boş harita)
     * @return id → { n, fail, up_pct, buckets:[{t,n,fail,ms}], last:[{at,ok,ms}] }
     */
    public Map<Long, Map<String, Object>> sparklines(String type, int hours, Set<Long> ids) {
        Kind k = KINDS.get(type);
        int h = Math.max(1, Math.min(MAX_HOURS, hours));
        String from = ISO.format(Instant.now().minus(h, ChronoUnit.HOURS));
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        for (Long id : ids) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", 0); m.put("fail", 0); m.put("up_pct", null);
            m.put("buckets", new ArrayList<Map<String, Object>>());
            m.put("last", new ArrayList<Map<String, Object>>());
            out.put(id, m);
        }

        // Saatlik kovalar — tek sorgu, tüm monitörler; id süzgeci bellekte (IN listesi 1000+ olabilir).
        String bucketSql = "SELECT t.monitor_id, t.bucket, COUNT(*) AS n, SUM(t.fail) AS fail, AVG(t.ms) AS ms FROM ("
                + "  SELECT monitor_id, substr(checked_at,1,13) AS bucket, " + k.msCol() + " AS ms, " + k.failExpr() + " AS fail"
                + "    FROM " + k.table() + " WHERE checked_at >= ?"
                + ") t GROUP BY t.monitor_id, t.bucket ORDER BY t.monitor_id, t.bucket";
        jdbc.query(bucketSql, rs -> {
            long id = rs.getLong(1);
            Map<String, Object> m = out.get(id);
            if (m == null) return;
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("t", rs.getString(2));
            int n = rs.getInt(3), fail = rs.getInt(4);
            b.put("n", n); b.put("fail", fail);
            double ms = rs.getDouble(5);
            b.put("ms", rs.wasNull() ? null : Math.round(ms));
            @SuppressWarnings("unchecked") List<Map<String, Object>> buckets = (List<Map<String, Object>>) m.get("buckets");
            buckets.add(b);
            m.put("n", (Integer) m.get("n") + n);
            m.put("fail", (Integer) m.get("fail") + fail);
        }, from);

        // Son 5 kontrol — pencere fonksiyonu; hata verirse (eski motor) yalnız kovalarla dönülür.
        String lastSql = "SELECT monitor_id, checked_at, ms, fail FROM ("
                + "  SELECT monitor_id, checked_at, " + k.msCol() + " AS ms, " + k.failExpr() + " AS fail,"
                + "         ROW_NUMBER() OVER (PARTITION BY monitor_id ORDER BY checked_at DESC) AS rn"
                + "    FROM " + k.table() + " WHERE checked_at >= ?"
                + ") t WHERE t.rn <= " + LAST_N + " ORDER BY monitor_id, checked_at ASC";
        try {
            jdbc.query(lastSql, rs -> {
                long id = rs.getLong(1);
                Map<String, Object> m = out.get(id);
                if (m == null) return;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("at", rs.getString(2));
                long ms = rs.getLong(3);
                c.put("ms", rs.wasNull() ? null : ms);
                c.put("ok", rs.getInt(4) == 0);
                @SuppressWarnings("unchecked") List<Map<String, Object>> last = (List<Map<String, Object>>) m.get("last");
                last.add(c);
            }, from);
        } catch (Exception e) {
            log.debug("Sparkline son-5 sorgusu desteklenmedi ({}): {}", type, e.toString());
        }

        for (Map<String, Object> m : out.values()) {
            int n = (Integer) m.get("n"), fail = (Integer) m.get("fail");
            m.put("up_pct", n == 0 ? null : Math.round(1000.0 * (n - fail) / n) / 10.0);
        }
        return out;
    }

    public static final int MAX_DAYS = 90;

    /**
     * Kart dönem etiketleri (2026-09-24, kullanıcı: "x saat hata" yerine 1/7/15/30 gün etiketi, sorunlu olanda
     * alarm ikonu): istenen pencereden ({@code days}) uzun olanlar atlanır; {@code days} listede yoksa sona eklenir.
     */
    static final int[] WINDOWS = {1, 7, 15, 30};
    /** Dönem başına döndürülen en yeni hatalı saat dilimi sayısı (etiket açıklaması); fazlası "+N saat dilimi daha". */
    static final int SLOT_CAP = 6;

    /**
     * Kullanılabilirlik / SLA (2026-09-12, #11): son N günde monitör başına toplam, hata, yüzde ve
     * "hatalı saat" sayısı (hata görülen ayrık saat kovası — kesinti süresinin kaba ölçüsü). İki toplu
     * sorgu, ham satır yok; 30 gün × 100 monitör dakikalık kontrolde 4M satırı DB toplar.
     *
     * <p>2026-09-24: {@code windows} — aynı iki sorguda {@link #WINDOWS} dönemleri de sayılır (ek tarama yok):
     * adet/hata {@code SUM(CASE WHEN checked_at >= ?)} ile; hatalı saat, kovadaki SON hatalı kontrolün pencere
     * içinde olup olmadığıyla (kova başı pencereden önce başlasa da yalnız pencere içi hata sayılır); {@code slots}
     * dönemin en yeni {@link #SLOT_CAP} hatalı saat dilimi ve o dilimdeki dönem içi hata adedi ({@code h} = UTC
     * {@code yyyy-MM-ddTHH}).
     * @return id → { n, fail, up_pct (iki ondalık), bad_hours, windows:[{days, n, fail, up_pct, bad_hours, slots:[{h, fail}]}] }
     */
    public Map<Long, Map<String, Object>> availability(String type, int days, Set<Long> ids) {
        return availability(type, days, ids, Instant.now());
    }

    /** Test edilebilir çekirdek — {@code now} enjekte edilir. */
    Map<Long, Map<String, Object>> availability(String type, int days, Set<Long> ids, Instant now) {
        Kind k = KINDS.get(type);
        int d = Math.max(1, Math.min(MAX_DAYS, days));
        String from = ISO.format(now.minus(d, ChronoUnit.DAYS));
        List<Integer> wins = new ArrayList<>();
        for (int w : WINDOWS) if (w <= d) wins.add(w);
        if (!wins.contains(d)) wins.add(d);
        String[] winFrom = new String[wins.size()];
        for (int i = 0; i < wins.size(); i++) winFrom[i] = ISO.format(now.minus(wins.get(i), ChronoUnit.DAYS));

        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        for (Long id : ids) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", 0); m.put("fail", 0); m.put("up_pct", null); m.put("bad_hours", 0);
            List<Map<String, Object>> wl = new ArrayList<>();
            for (int w : wins) {
                Map<String, Object> wm = new LinkedHashMap<>();
                wm.put("days", w); wm.put("n", 0); wm.put("fail", 0); wm.put("up_pct", null); wm.put("bad_hours", 0);
                wm.put("slots", new ArrayList<Map<String, Object>>());
                wl.add(wm);
            }
            m.put("windows", wl);
            out.put(id, m);
        }

        // Adet / hata — tüm pencere + her dönem tek geçişte. Parametre sırası: select listesindeki dönem ?'leri, sonra WHERE.
        StringBuilder sql = new StringBuilder("SELECT monitor_id, COUNT(*), SUM(fail)");
        List<Object> args = new ArrayList<>();
        for (String wf : winFrom) {
            sql.append(", SUM(CASE WHEN checked_at >= ? THEN 1 ELSE 0 END), SUM(CASE WHEN checked_at >= ? THEN fail ELSE 0 END)");
            args.add(wf); args.add(wf);
        }
        sql.append(" FROM (SELECT monitor_id, checked_at, ").append(k.failExpr()).append(" AS fail FROM ").append(k.table())
           .append(" WHERE checked_at >= ?) t GROUP BY monitor_id");
        args.add(from);
        jdbc.query(sql.toString(), rs -> {
            Map<String, Object> m = out.get(rs.getLong(1));
            if (m == null) return;
            m.put("n", rs.getInt(2)); m.put("fail", rs.getInt(3));
            List<Map<String, Object>> wl = windowsOf(m);
            for (int i = 0; i < wl.size(); i++) {
                wl.get(i).put("n", rs.getInt(4 + 2 * i));
                wl.get(i).put("fail", rs.getInt(5 + 2 * i));
            }
        }, args.toArray());

        // Hatalı saatler — yalnız HATALI kovalar döner (normalde birkaç satır), en yeni kova önce. Her dönem için o
        // kovadaki dönem İÇİ hata adedi ayrı sayılır: kova başı dönemden önce başlasa da yalnız dönem içi hata sayılır,
        // ve kullanıcı "o saatte 5 hata" görür (2026-09-24: "1'den fazla hata varsa o kadar hata adedi yazsın").
        StringBuilder badSql = new StringBuilder("SELECT t.monitor_id, t.bucket, COUNT(*)");
        List<Object> badArgs = new ArrayList<>();
        for (String wf : winFrom) { badSql.append(", SUM(CASE WHEN t.checked_at >= ? THEN 1 ELSE 0 END)"); badArgs.add(wf); }
        badSql.append(" FROM (SELECT monitor_id, checked_at, substr(checked_at,1,13) AS bucket, ").append(k.failExpr())
              .append(" AS fail FROM ").append(k.table()).append(" WHERE checked_at >= ?) t WHERE t.fail = 1")
              .append(" GROUP BY t.monitor_id, t.bucket ORDER BY t.monitor_id, t.bucket DESC");
        badArgs.add(from);
        jdbc.query(badSql.toString(), rs -> {
            Map<String, Object> m = out.get(rs.getLong(1));
            if (m == null) return;
            m.put("bad_hours", (Integer) m.get("bad_hours") + 1);
            String bucket = rs.getString(2);
            List<Map<String, Object>> wl = windowsOf(m);
            for (int i = 0; i < wl.size(); i++) {
                int c = rs.getInt(4 + i);
                if (c <= 0) continue;
                Map<String, Object> wm = wl.get(i);
                wm.put("bad_hours", (Integer) wm.get("bad_hours") + 1);
                @SuppressWarnings("unchecked") List<Map<String, Object>> slots = (List<Map<String, Object>>) wm.get("slots");
                if (slots.size() < SLOT_CAP) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("h", bucket); s.put("fail", c);
                    slots.add(s);
                }
            }
        }, badArgs.toArray());

        for (Map<String, Object> m : out.values()) {
            m.put("up_pct", pct((Integer) m.get("n"), (Integer) m.get("fail")));
            for (Map<String, Object> wm : windowsOf(m)) wm.put("up_pct", pct((Integer) wm.get("n"), (Integer) wm.get("fail")));
        }
        return out;
    }

    private static Double pct(int n, int fail) { return n == 0 ? null : Math.round(10000.0 * (n - fail) / n) / 100.0; }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> windowsOf(Map<String, Object> m) { return (List<Map<String, Object>>) m.get("windows"); }
}
