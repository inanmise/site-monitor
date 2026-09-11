package com.sitemonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Tablo kayıt defteri — "bu tablo ne zaman oluştu, en son ne zaman değişti?" (SQL Playground, 2026-09-11).
 *
 * <p><b>Neden var.</b> PostgreSQL tablo oluşturma zamanını SAKLAMAZ ve "son güncelleme" için tek satırlık
 * bir katalog alanı yoktur ({@code pg_stat_user_tables} yalnız kümülatif sayaç + vacuum/analyze anları
 * verir; dosya mtime'ı süper-kullanıcı ister). Bu servis iki şeyi kendisi tutar:
 * <ul>
 *   <li><b>first_seen_at</b> — tablo uygulama tarafından ilk görüldüğü an. Defter ilk kez dolarken var
 *       olan tablolar {@code bootstrap=true} işaretiyle yazılır: bunlar için değer GERÇEK oluşturma anı
 *       değil, kaydın başladığı andır (ekran "≈" ile gösterir). Sonradan doğan tablolar gerçek anı taşır.</li>
 *   <li><b>last_change_at</b> — {@code n_tup_ins+n_tup_upd+n_tup_del} sayacı bir önceki heartbeat'ten
 *       farklıysa "şimdi" yazılır → dakika hassasiyetinde "son veri değişimi". Sayaç sıfırlanırsa
 *       (pg_stat_reset / çökme) fark yine değişim sayılır (yanlış-pozitif, yanlış-negatif değil).</li>
 * </ul>
 * Heartbeat ritminde ({@link ExtendedHealthService#recordHeartbeat}) ve açılışta çalışır; hiçbir hata
 * dışarı çıkmaz (sağlık kalp atışını bozamaz). Katalogu olmayan ortamda (H2/test) yalnız first_seen tutulur.
 * Tablo {@code SchedulerService.applySchemaPatches} içinde ham DDL ile açılır (RetentionCatalog: BOUNDED).
 */
@Slf4j
@Service
public class SchemaTableRegistryService {

    public static final String TABLE = "schema_table_registry";
    public static final String DDL = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
            + "table_name VARCHAR(128) PRIMARY KEY, first_seen_at VARCHAR(30) NOT NULL, first_seen_version VARCHAR(64), "
            + "bootstrap BOOLEAN NOT NULL DEFAULT FALSE, activity_counter BIGINT, last_change_at VARCHAR(30), last_seen_at VARCHAR(30))";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;

    @Autowired(required = false)
    private BuildInfo buildInfo;

    public SchemaTableRegistryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Bir tur: yeni tabloları kaydet, sayaç değişenlere last_change_at yaz. Dönüş: değişen/eklenen satır. */
    @Transactional
    public int tick() {
        try {
            String now = ISO.format(Instant.now());
            List<String> tables = jdbc.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                            + "AND table_type = 'BASE TABLE' AND table_name <> '" + TABLE + "' ORDER BY table_name", String.class);
            Map<String, Long> activity = activityCounters();
            Map<String, Long> existing = new HashMap<>();
            for (Map<String, Object> r : jdbc.queryForList("SELECT table_name, activity_counter FROM " + TABLE)) {
                Object c = r.get("activity_counter");
                existing.put(String.valueOf(r.get("table_name")), c == null ? null : ((Number) c).longValue());
            }
            boolean bootstrap = existing.isEmpty();
            String version = buildInfo == null ? null : buildInfo.get().version();
            int touched = 0;
            List<Object[]> inserts = new ArrayList<>();
            List<Object[]> changes = new ArrayList<>();
            for (String t : tables) {
                Long cur = activity.get(t);
                if (!existing.containsKey(t)) {
                    inserts.add(new Object[]{t, now, version, bootstrap, cur, bootstrap ? null : now, now});
                    continue;
                }
                Long prev = existing.get(t);
                if (cur != null && !cur.equals(prev)) changes.add(new Object[]{cur, now, now, t});
            }
            if (!inserts.isEmpty()) {
                jdbc.batchUpdate("INSERT INTO " + TABLE + " (table_name, first_seen_at, first_seen_version, bootstrap, "
                        + "activity_counter, last_change_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?)", inserts);
                touched += inserts.size();
            }
            if (!changes.isEmpty()) {
                jdbc.batchUpdate("UPDATE " + TABLE + " SET activity_counter = ?, last_change_at = ?, last_seen_at = ? "
                        + "WHERE table_name = ?", changes);
                touched += changes.size();
            }
            if (bootstrap && !inserts.isEmpty())
                log.info("Tablo kayıt defteri ilk kez dolduruldu: {} tablo (first_seen ≈ kayıt başlangıcı)", inserts.size());
            return touched;
        } catch (Exception e) {
            log.debug("schema registry tick atlandı: {}", e.toString());
            return 0;
        }
    }

    /** relname → n_tup_ins+n_tup_upd+n_tup_del (Postgres); katalog yoksa boş. */
    Map<String, Long> activityCounters() {
        Map<String, Long> m = new HashMap<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT relname, (n_tup_ins + n_tup_upd + n_tup_del) AS activity FROM pg_stat_user_tables WHERE schemaname = 'public'")) {
                Object a = r.get("activity");
                if (a != null) m.put(String.valueOf(r.get("relname")), ((Number) a).longValue());
            }
        } catch (Exception e) {
            /* pg_stat_user_tables yok (H2) → sayaç izlenmez */
        }
        return m;
    }

    /** Tüm defter satırları: table_name → {first_seen_at, first_seen_version, bootstrap, last_change_at, last_seen_at}. */
    public Map<String, Map<String, Object>> all() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT table_name, first_seen_at, first_seen_version, bootstrap, last_change_at, last_seen_at FROM " + TABLE)) {
                out.put(String.valueOf(r.get("table_name")), row(r));
            }
        } catch (Exception e) {
            /* defter henüz yok */
        }
        return out;
    }

    public Map<String, Object> get(String tableName) {
        return all().get(tableName);
    }

    private static Map<String, Object> row(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("first_seen_at", str(r.get("first_seen_at")));
        m.put("first_seen_version", str(r.get("first_seen_version")));
        m.put("first_seen_approx", Boolean.TRUE.equals(r.get("bootstrap")));
        m.put("last_change_at", str(r.get("last_change_at")));
        m.put("last_seen_at", str(r.get("last_seen_at")));
        return m;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    /** JDBC Timestamp / Instant / String → ISO-UTC string (katalog zamanları için ortak dönüşüm). */
    public static String iso(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp ts) return ISO.format(ts.toInstant());
        if (o instanceof java.time.OffsetDateTime odt) return ISO.format(odt.toInstant());
        if (o instanceof java.time.LocalDateTime ldt) return ISO.format(ldt.toInstant(ZoneOffset.UTC));
        if (o instanceof Instant i) return ISO.format(i);
        return String.valueOf(o);
    }
}
