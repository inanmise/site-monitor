package com.sitemonitor.service.noc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * On izleme türünün 7/24 okuma modeli — kapsam, grup kullanım sayısı ve alarm→izleme eşlemesi TEK sorgu
 * kümesinden beslenir (tür başına varlık yüklemek, sentetik script/sayfa hızı gövdelerini de çekerdi; tek pod).
 *
 * <p><b>DNS/Port çift kaynaklıdır</b> (proje tuzağı): envanterden TÜREYEN satırın {@code team_id}'si yalnız
 * oluşturulurken kopyalanır ve bir daha tazelenmez; takım ENVANTERDEN (alan adı → envanter) okunur.
 * {@code MonitoringController.effectiveTeam} ile aynı kural: standalone → kendi takımı; türev → envanterin
 * takımı (envanter kaydı yoksa saklanan değer). Türev satır yalnız envanter kaydı AKTİF ve silinmemişken
 * listelenir (Port/DNS listeleri de öyle gösterir); standalone satırda silinmiş ({@code deleted_at}) atlanır.
 * Envanter türevlerinin görünürlüğü UG takımını da kapsar ({@code inventoryViewable} ile aynı).
 *
 * <p><b>Maliyet</b> (yayın öncesi inceleme): envanter YALNIZ DNS/Port satırları için okunur; tekil aramalarda
 * ({@link #find}, kimliksiz {@link #forAlert}) tüm tablo değil yalnız ilgili alan adı sorgulanır. Fırtına gibi çok
 * üyeli değerlendirmeler {@link #snapshot()} ile TEK anlık görüntü kurar: tür başına bir tablo okuması + en çok bir
 * envanter okuması, bütün üyeler için yeniden kullanılır (fırtına iş parçacığında üye başına sorgu yok).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NocMonitorDirectory {

    private final JdbcTemplate jdbc;

    /**
     * Bir izleme satırı. {@code teamId} ETKİN takım; {@code ugTeamId} yalnız envanter kökenlilerde (görünürlük).
     * {@code active} = izleme çalışıyor mu (türevde envanter de canlı olmalı).
     */
    public record Row(NocType type, long id, String name, String target, Long teamId, Long ugTeamId,
                      boolean active, boolean nocNotify, String nocGroupIds, boolean derived) {
        public String key() { return type.name() + ":" + id; }
    }

    record Inv(Long teamId, Long ugTeamId, int port) {}

    static boolean needsInventory(NocType t) {
        return t == NocType.DNS || t == NocType.PORT;
    }

    /** Alarmın hedef anahtarına karşılık gelen kolon (kimliksiz eşleme). */
    static String keyColumn(NocType t) {
        return switch (t) {
            case SSL, DNS, DOMAIN -> "domain";
            case PING, PORT -> "host";
            case HTTP, KEYWORD, PAGE, PAGESPEED -> "url";
            case SCRIPTED -> "name";
        };
    }

    // ── Toplu okuma ──────────────────────────────────────────────────────────

    /** Tüm izlemeler (on tür) — envanter en çok BİR kez okunur. */
    public List<Row> all() {
        List<Row> out = new ArrayList<>();
        Map<String, Inv> inv = null;
        for (NocType t : NocType.values()) {
            try {
                if (needsInventory(t) && inv == null) inv = liveInventory();
                Map<String, Inv> invMap = inv;
                out.addAll(load(t, invMap == null ? d -> null : d -> invMap.get(d), "", new Object[0]));
            } catch (Exception e) {
                // Bir tablonun okunamaması (yükseltme sırasında eksik kolon) BÜTÜN kapsam ekranını düşürmesin;
                // o tür eksik görünür ve günlüğe yazılır.
                log.warn("7/24 kapsamı: {} izlemeleri okunamadı: {}", t, e.getMessage());
            }
        }
        return out;
    }

    /** Tek türün izlemeleri (envanter yalnız DNS/Port için). */
    public List<Row> ofType(NocType t) {
        Map<String, Inv> inv = needsInventory(t) ? liveInventory() : Map.of();
        return load(t, inv::get, "", new Object[0]);
    }

    // ── Tekil okuma ──────────────────────────────────────────────────────────

    /** Tek izleme (yoksa null) — envanterden yalnız o satırın alan adı okunur. */
    public Row find(NocType t, long id) {
        List<Row> rows = load(t, singleInventory(), " AND id = ?", new Object[]{id});
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Alarm → izleme. Önce bağlamdaki {@code monitor_id} (sweep bağlamı taşır); yoksa hedef anahtarıyla
     * (host/url/alan adı) eşleşen satırlar — birden çok eşleşmede 7/24'ü AÇIK olan tercih edilir. SSL (envanter)
     * alan adıyla çözülür. Tablonun tamamı OKUNMAZ.
     */
    public Row forAlert(NocType type, String domain, Object monitorId) {
        if (type == null) return null;
        Long id = idOf(monitorId);
        if (id != null && type != NocType.SSL) {
            Row r = find(type, id);
            if (r != null) return r;
        }
        if (domain == null || domain.isBlank()) return null;
        return pick(load(type, singleInventory(), " AND LOWER(" + keyColumn(type) + ") = ?",
                new Object[]{domain.trim().toLowerCase(Locale.ROOT)}));
    }

    private static Row pick(List<Row> rows) {
        Row best = null;
        for (Row r : rows) if (best == null || (r.nocNotify() && !best.nocNotify())) best = r;
        return best;
    }

    // ── Anlık görüntü (çok üyeli değerlendirme) ──────────────────────────────

    public Snapshot snapshot() {
        return new Snapshot();
    }

    /**
     * Bir değerlendirme boyunca yeniden kullanılan okuma: tür başına tablo EN ÇOK bir kez, envanter EN ÇOK bir kez
     * (yalnız DNS/Port gerekirse). Tür+kimlik ve tür+anahtar dizinleri bellekte.
     */
    public class Snapshot {
        private Map<String, Inv> inventory;
        private final Map<NocType, List<Row>> rows = new EnumMap<>(NocType.class);
        private final Map<NocType, Map<Long, Row>> byId = new EnumMap<>(NocType.class);
        private final Map<NocType, Map<String, Row>> byKey = new EnumMap<>(NocType.class);

        private Map<String, Inv> inventory() {
            if (inventory == null) inventory = liveInventory();
            return inventory;
        }

        List<Row> rows(NocType t) {
            return rows.computeIfAbsent(t, x -> {
                List<Row> list = load(x, needsInventory(x) ? d -> inventory().get(d) : d -> null, "", new Object[0]);
                Map<Long, Row> ids = new HashMap<>();
                Map<String, Row> keys = new HashMap<>();
                for (Row r : list) {
                    ids.put(r.id(), r);
                    String k = keyOf(r);
                    Row prev = keys.get(k);
                    if (k != null && (prev == null || (r.nocNotify() && !prev.nocNotify()))) keys.put(k, r);
                }
                byId.put(x, ids);
                byKey.put(x, keys);
                return list;
            });
        }

        public Row forAlert(NocType type, String domain, Object monitorId) {
            if (type == null) return null;
            rows(type);
            Long id = idOf(monitorId);
            if (id != null && type != NocType.SSL) {
                Row r = byId.get(type).get(id);
                if (r != null) return r;
            }
            if (domain == null || domain.isBlank()) return null;
            return byKey.get(type).get(domain.trim().toLowerCase(Locale.ROOT));
        }
    }

    /** Satırın eşleme anahtarı — {@link #keyColumn} ile aynı kavram (Port: host, DNS: alan adı, SSL: alan adı). */
    static String keyOf(Row r) {
        String k = switch (r.type()) {
            case PORT -> r.target() == null ? null : r.target().substring(0, Math.max(0, r.target().lastIndexOf(':')));
            case DNS -> r.target() == null ? null : r.target().replaceFirst(" [(][^)]*[)]$", "");
            case SCRIPTED, SSL -> r.name();
            default -> r.target();
        };
        return k == null ? null : k.toLowerCase(Locale.ROOT);
    }

    // ── Envanter ─────────────────────────────────────────────────────────────

    /** Alan adı (küçük harf) → aktif, silinmemiş envanter kaydı. TÜM tablo — yalnız toplu okumada. */
    Map<String, Inv> liveInventory() {
        Map<String, Inv> m = new HashMap<>();
        jdbc.query("SELECT domain, team_id, ug_team_id, port FROM certificate_inventory "
                        + "WHERE deleted_at IS NULL AND active = TRUE",
                rs -> {
                    String d = rs.getString(1);
                    if (d != null) m.put(d.toLowerCase(Locale.ROOT),
                            new Inv(longOrNull(rs, 2), longOrNull(rs, 3), rs.getObject(4) == null ? 443 : rs.getInt(4)));
                });
        return m;
    }

    /** Tek alan adının envanter kaydı (önbellekli) — tekil aramalarda tüm tabloyu okumamak için. */
    private Function<String, Inv> singleInventory() {
        Map<String, Inv> cache = new HashMap<>();
        return d -> {
            if (d == null) return null;
            if (cache.containsKey(d)) return cache.get(d);
            List<Inv> found = new ArrayList<>();
            jdbc.query("SELECT team_id, ug_team_id, port FROM certificate_inventory "
                            + "WHERE deleted_at IS NULL AND active = TRUE AND LOWER(domain) = ?",
                    rs -> { found.add(new Inv(longOrNull(rs, 1), longOrNull(rs, 2), rs.getObject(3) == null ? 443 : rs.getInt(3))); },
                    d);
            Inv inv = found.isEmpty() ? null : found.get(0);
            cache.put(d, inv);
            return inv;
        };
    }

    // ── Satır okuma ──────────────────────────────────────────────────────────

    /**
     * @param inv   küçük harf alan adı → canlı envanter kaydı (yalnız DNS/Port çağırır)
     * @param where ek koşul (" AND …"); parametreleri {@code args}
     */
    private List<Row> load(NocType t, Function<String, Inv> inv, String where, Object[] args) {
        List<Row> out = new ArrayList<>();
        switch (t) {
            case SSL -> jdbc.query("SELECT id, domain, port, team_id, ug_team_id, active, noc_notify, noc_group_ids "
                    + "FROM certificate_inventory WHERE deleted_at IS NULL" + where, rs -> {
                String d = rs.getString(2);
                Integer port = rs.getObject(3) == null ? null : rs.getInt(3);
                String target = port == null || port == 443 ? d : d + ":" + port;
                out.add(new Row(t, rs.getLong(1), d, target, longOrNull(rs, 4), longOrNull(rs, 5),
                        bool(rs, 6), bool(rs, 7), rs.getString(8), false));
            }, args);
            case PING -> simple(t, "SELECT id, name, host, team_id, active, noc_notify, noc_group_ids FROM ping_monitors WHERE 1=1" + where, args, out);
            case HTTP -> simple(t, "SELECT id, name, url, team_id, active, noc_notify, noc_group_ids FROM http_monitors WHERE 1=1" + where, args, out);
            case KEYWORD -> simple(t, "SELECT id, name, url, team_id, active, noc_notify, noc_group_ids FROM keyword_monitors WHERE 1=1" + where, args, out);
            case PAGE -> simple(t, "SELECT id, name, url, team_id, active, noc_notify, noc_group_ids FROM page_monitors WHERE 1=1" + where, args, out);
            case PAGESPEED -> simple(t, "SELECT id, name, url, team_id, active, noc_notify, noc_group_ids FROM pagespeed_monitors WHERE 1=1" + where, args, out);
            case SCRIPTED -> simple(t, "SELECT id, name, name, team_id, active, noc_notify, noc_group_ids FROM scripted_monitors WHERE 1=1" + where, args, out);
            case DOMAIN -> simple(t, "SELECT id, name, domain, team_id, active, noc_notify, noc_group_ids FROM domain_monitors WHERE 1=1" + where, args, out);
            case DNS -> jdbc.query("SELECT id, name, domain, record_type, team_id, active, standalone, noc_notify, noc_group_ids "
                    + "FROM dns_monitors WHERE deleted_at IS NULL" + where, rs -> {
                String domain = rs.getString(3);
                String rt = rs.getString(4);
                boolean standalone = bool(rs, 7);
                Long stored = longOrNull(rs, 5);
                Inv i = standalone || domain == null ? null : inv.apply(domain.toLowerCase(Locale.ROOT));
                if (!standalone && i == null) return;   // türev ama envanteri canlı değil → listede yok
                String name = rs.getString(2);
                out.add(new Row(t, rs.getLong(1), name == null || name.isBlank() ? domain : name,
                        rt == null ? domain : domain + " (" + rt + ")",
                        standalone ? stored : (i.teamId() != null ? i.teamId() : stored),
                        standalone ? null : i.ugTeamId(),
                        bool(rs, 6), bool(rs, 8), rs.getString(9), !standalone));
            }, args);
            case PORT -> jdbc.query("SELECT id, name, host, port, team_id, active, standalone, noc_notify, noc_group_ids "
                    + "FROM port_monitors WHERE deleted_at IS NULL" + where, rs -> {
                String host = rs.getString(3);
                int port = rs.getInt(4);
                boolean standalone = bool(rs, 7);
                Long stored = longOrNull(rs, 5);
                Inv i = standalone || host == null ? null : inv.apply(host.toLowerCase(Locale.ROOT));
                // Türev satır yalnız envanterin KENDİ portu için listelenir (Port listesi domain:invPort anahtarıyla eşler).
                if (!standalone && (i == null || i.port() != port)) return;
                String name = rs.getString(2);
                out.add(new Row(t, rs.getLong(1), name == null || name.isBlank() ? host : name, host + ":" + port,
                        standalone ? stored : (i.teamId() != null ? i.teamId() : stored),
                        standalone ? null : i.ugTeamId(),
                        bool(rs, 6), bool(rs, 8), rs.getString(9), !standalone));
            }, args);
        }
        return out;
    }

    private void simple(NocType t, String sql, Object[] args, List<Row> out) {
        jdbc.query(sql, rs -> {
            String name = rs.getString(2);
            String target = rs.getString(3);
            out.add(new Row(t, rs.getLong(1), name == null || name.isBlank() ? target : name, target,
                    longOrNull(rs, 4), null, bool(rs, 5), bool(rs, 6), rs.getString(7), false));
        }, args);
    }

    private static Long idOf(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o == null) return null;
        try {
            return Long.parseLong(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longOrNull(ResultSet rs, int col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static boolean bool(ResultSet rs, int col) throws SQLException {
        boolean v = rs.getBoolean(col);
        return !rs.wasNull() && v;
    }
}
