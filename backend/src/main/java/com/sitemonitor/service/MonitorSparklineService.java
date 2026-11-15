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

    /**
     * Tür → (kontrol tablosu, monitör tablosu, süre sütunu, hata ifadesi[, bütçe ihlali ifadesi]). Sütunlar Hibernate snake_case.
     * {@code breachExpr} (yalnız Sayfa Hızı, 2026-11-14): ALINABİLEN kontrolün eşik aşımı — kullanılabilirliğe GİRMEZ, ayrı
     * "bütçe uyumu" sayısı olarak döner ({@code breach}, {@code budget_pct}).
     */
    record Kind(String table, String monitorTable, String msCol, String failExpr, String breachExpr,
                String undecidedExpr, String budgetDefinedExpr) {
        Kind(String table, String monitorTable, String msCol, String failExpr) { this(table, monitorTable, msCol, failExpr, null, null, null); }
    }

    /**
     * Sayfa Hızı bütçe uyumu ifadeleri (inceleme B1 / F7). {@code breach}: kanıtlı eşik aşımı. {@code undecided}: aşım yok ama
     * tanımlı bir eşik KARARSIZ (kısmi ölçüm / ölçülemeyen TTFB) — bu ölçüm uyuma SAYILMAZ (ne uyumlu ne ihlal; paydadan
     * çıkar). {@code budgetDefined}: izlemede hiç eşik yoksa bütçe uyumu null ("bütçe tanımlı değil").
     */
    static final String PAGESPEED_LOADED = "CASE WHEN " + verdict("ok", "error_message IS NOT NULL") + " = 1 THEN 0";
    static final String PAGESPEED_BREACH = PAGESPEED_LOADED
            + " WHEN breached_metrics IS NOT NULL AND breached_metrics <> '' THEN 1 ELSE 0 END";
    static final String PAGESPEED_UNDECIDED = PAGESPEED_LOADED
            + " WHEN breached_metrics IS NOT NULL AND breached_metrics <> '' THEN 0"
            + " WHEN undecided_metrics IS NOT NULL AND undecided_metrics <> '' THEN 1 ELSE 0 END";
    static final String PAGESPEED_BUDGET_DEFINED = "COALESCE(max_load_ms, 0) > 0 OR COALESCE(max_ttfb_ms, 0) > 0"
            + " OR COALESCE(max_page_kb, 0) > 0 OR COALESCE(max_requests, 0) > 0 OR COALESCE(min_page_kb, 0) > 0"
            + " OR COALESCE(max_js_kb, 0) > 0 OR COALESCE(max_img_kb, 0) > 0 OR COALESCE(max_font_kb, 0) > 0"
            + " OR COALESCE(max_third_party_kb, 0) > 0"
            // Büyüme alarmı da bütçedir (inceleme 2026-11-15) — PageSpeedRules.budgetDefined (BREACH_KEYS) ile aynı küme:
            // yalnız büyüme eşiği / bayt bandı olan izlemenin uyumu "bütçe tanımlı değil" (null) sayılmasın
            + " OR COALESCE(growth_bytes_pct, 0) > 0 OR COALESCE(growth_requests_pct, 0) > 0 OR growth_bytes_band = TRUE";

    /**
     * Hata ifadeleri KONTROLÜN KENDİ HÜKMÜNDEN okunur (DATA-1, 2026-10-22): {@code ok} / {@code open} sütunu — saatlik
     * özetler (izleme panosu, durum sayfası) da bunu sayar. Eskiden yalnız {@code error IS NOT NULL} sayılıyordu: HTTP
     * durum kodu uyuşmazlığı (503) ve anahtar kelime koşulu {@code error=NULL, ok=false} yazar → kart "%100 SLA", yeşil
     * son-5 gösteriyordu. {@code ok IS NULL} (sütundan önceki eski satır) için eski {@code error} kuralı yedektir. DNS:
     * yalnız sorgu HATASI kesintidir — planlı kayıt değişikliği ({@code changed}) kesinti DEĞİLDİR.
     *
     * <p><b>Sayfa Hızı (2026-11-14, madde 5):</b> kullanılabilirlik = "sayfa alınabildi mi" ({@code ok}), DİĞER türlerle ve
     * saatlik özetlerle / durum sayfasıyla / izleme panosuyla AYNI kural. Eskiden eşik aşımı da hata sayılıyordu: kartın SLA'sı
     * düşük, panonun kullanılabilirliği yüksek görünüyordu. Eşik aşımı artık ayrı bir sayıdır ({@code breach}) ve kart onu
     * "bütçe uyumu" diye gösterir.
     */
    static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("http",      new Kind("http_checks",      "http_monitors",      "response_ms", verdict("ok", "error IS NOT NULL")));
        KINDS.put("ping",      new Kind("ping_checks",      "ping_monitors",      "rtt_ms",      "CASE WHEN error IS NOT NULL OR packet_loss >= 100 THEN 1 ELSE 0 END"));
        KINDS.put("port",      new Kind("port_checks",      "port_monitors",      "response_ms", verdict("open", "error IS NOT NULL")));
        KINDS.put("dns",       new Kind("dns_records",      "dns_monitors",       "response_ms", "CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END"));
        KINDS.put("keyword",   new Kind("keyword_results",  "keyword_monitors",   "response_ms", verdict("ok", "error IS NOT NULL")));
        KINDS.put("page",      new Kind("page_checks",      "page_monitors",      "response_ms", verdict("ok", "error IS NOT NULL")));
        KINDS.put("pagespeed", new Kind("pagespeed_checks", "pagespeed_monitors", "response_ms",
                verdict("ok", "error_message IS NOT NULL"), PAGESPEED_BREACH, PAGESPEED_UNDECIDED, PAGESPEED_BUDGET_DEFINED));
        KINDS.put("scripted",  new Kind("scripted_checks",  "scripted_monitors",  "duration_ms",
                verdict("ok", "error IS NOT NULL OR exit_code <> 0 OR checks_failed > 0")));
        // Veritabanı sorgusu (2026-10-31): kesinti YALNIZ sorgunun çalıştırılamaması (ok = FALSE). Kural ihlali (status
        // BREACH, ok = TRUE) iş alarmıdır — SLA'yı düşürmez (kart ihlali kendi rozetiyle gösterir).
        KINDS.put("dbquery",   new Kind("dbquery_checks",   "dbquery_monitors",   "duration_ms", verdict("ok", "error_message IS NOT NULL")));
    }

    /** {@code col = FALSE} → hata; hüküm sütunu boş (eski satır) ise {@code legacyFail} kuralı. */
    static String verdict(String col, String legacyFail) {
        return "CASE WHEN " + col + " = FALSE OR (" + col + " IS NULL AND (" + legacyFail + ")) THEN 1 ELSE 0 END";
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
     * Monitörün takımı (null = takımsız), adı (boş ad null), etkin mi (duraklatılmış = false; boş = etkin) ve — YALNIZ Port /
     * DNS'te — kullanıcının eklediği bağımsız satır mı ({@code standalone}; diğer türlerde null) ile envanter eşleşmesinin
     * anahtarı ({@code host}: Port'ta host, DNS'te alan adı; diğer türlerde null). Envanter TÜREVİ Port / DNS satırı da
     * envanterin takımını taşır ({@code MonitoringController} / {@code DerivedMonitorTeamSync}) — takım alanı "türev mi"
     * sorusunu cevaplamaz, {@code standalone} cevaplar.
     */
    public record MonitorRef(Long teamId, String name, boolean active, Boolean standalone, String host) {
        /** Port / DNS dışındaki türler (bağımsızlık ve envanter anahtarı yok). */
        public MonitorRef(Long teamId, String name, boolean active) { this(teamId, name, active, null, null); }
    }

    /** Envanter eşleşmesi için ek sütun: Port → host, DNS → alan adı (İzleme Panosu {@code portSpec} / {@code dnsSpec}). */
    private static final Map<String, String> INVENTORY_KEY_COLUMN = Map.of("port", "host", "dns", "domain");

    /**
     * Monitör id → (takım, ad, etkin [, bağımsız, envanter anahtarı]) — türün TÜM monitörleri, tek dar sorgu (2026-11-14,
     * Kişisel Panom 7 günlük erişilebilirlik: kurum geneli görüntü kapsam süzgecini, en kötü satırların adını ve "ölçülmemiş"
     * sayımının envanter-pasif kuralını bu haritadan alır; {@link #monitorNames} 1000 kimlikle sınırlıdır). URL / hedef
     * BİLEREK okunmaz — sorgu dizgisinde gizli değer taşıyabilir; Port'un host'u ve DNS'in alan adı yalnız aktif envanterle
     * eşleştirmek için okunur, hiçbir yanıta yazılmaz.
     */
    public Map<Long, MonitorRef> monitorRefs(String type) {
        Kind k = KINDS.get(type);
        Map<Long, MonitorRef> out = new HashMap<>();
        if (k == null) return out;
        String keyCol = INVENTORY_KEY_COLUMN.get(type);
        String extra = keyCol == null ? "" : ", standalone, " + keyCol + " AS inv_key";
        jdbc.query("SELECT id, team_id, name, active" + extra + " FROM " + k.monitorTable(), rs -> {
            long id = rs.getLong("id");
            long tid = rs.getLong("team_id");
            Long team = rs.wasNull() ? null : tid;
            String n = rs.getString("name");
            boolean active = rs.getBoolean("active");
            if (rs.wasNull()) active = true;   // eski satır: sütun boş = etkin (varlık varsayılanı)
            String name = n == null || n.isBlank() ? null : n;
            if (keyCol == null) {
                out.put(id, new MonitorRef(team, name, active));
                return;
            }
            boolean sa = rs.getBoolean("standalone");
            Boolean standalone = rs.wasNull() ? Boolean.FALSE : sa;   // varlık varsayılanı false (envanter türevi)
            out.put(id, new MonitorRef(team, name, active, standalone, rs.getString("inv_key")));
        });
        return out;
    }

    /**
     * Monitör id → ad (2026-11-13, İstatistikler SLA ihlal listesi): yalnız verilen (en çok {@link #IN_LIMIT}) id'ler, tek dar
     * sorgu. Ad boş (eski türev satır) olan monitör haritada olmaz — çağıran "#id" yazar.
     */
    public Map<Long, String> monitorNames(String type, Collection<Long> ids) {
        Kind k = KINDS.get(type);
        Map<Long, String> out = new HashMap<>();
        if (k == null || ids == null || ids.isEmpty() || ids.size() > IN_LIMIT) return out;
        List<Object> args = new ArrayList<>(ids);
        jdbc.query("SELECT id, name FROM " + k.monitorTable() + " WHERE id IN ("
                + String.join(",", Collections.nCopies(args.size(), "?")) + ")", rs -> {
            String n = rs.getString("name");
            if (n != null && !n.isBlank()) out.put(rs.getLong("id"), n);
        }, args.toArray());
        return out;
    }

    // ── Maliyet sınırları (prod kapısı 2026-09-25, O-7) ──────────────────────────────────────────
    // Her istek türün TÜM monitörlerinin ham kontrol satırlarını tarıyordu (görünürlük süzgeci bellekte);
    // beş izlemesi olan kullanıcı da 10k monitörlük taramayı ödüyordu, 100 açık sayfa dakikada bir. Artık:
    // kapsamlı çağıranın sorgusu görünür kümeyle daraltılır (monitor_id IN, idx_*_monitor_id), penceresi
    // arayüzün kullandığı kadarla sınırlanır; global görücünün tüm-filo sonucu kısa TTL'le önbelleğe alınır
    // (veri herkes için aynı — N global istek TTL başına 1 taramaya iner).

    /** Global OLMAYAN çağıranın saat tavanı — arayüz 24 saat istiyor. */
    public static final int SCOPED_MAX_HOURS = 24;
    /** Global OLMAYAN çağıranın gün tavanı — arayüz en çok 30 gün istiyor (Sayfa Hızı 7/14/30). */
    public static final int SCOPED_MAX_DAYS = 30;
    /** Görünür küme en çok bu kadarsa sorgu IN listesiyle daraltılır (bind sınırı + plan güvenliği). */
    static final int IN_LIMIT = 1000;

    /** Sorgu daraltması: WHERE'e eklenecek parça + bağlanacak id'ler (küme büyükse ya da süzgeç istenmezse boş). */
    private record IdFilter(String sql, List<Object> args) {
        static IdFilter of(Set<Long> ids, boolean restrict) {
            if (!restrict || ids.isEmpty() || ids.size() > IN_LIMIT) return new IdFilter("", List.of());
            return new IdFilter(" AND monitor_id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")",
                    new ArrayList<>(ids));
        }
        Object[] with(Object... head) {
            List<Object> all = new ArrayList<>(Arrays.asList(head));
            all.addAll(args);
            return all.toArray();
        }
    }

    /** Global görücü (tüm filo) sonucu — kısa TTL'li ortak önbellek (CacheConfig "monitor-sparklines"). */
    @org.springframework.cache.annotation.Cacheable(value = "monitor-sparklines", key = "#type + ':' + #hours", sync = true)
    public Map<Long, Map<String, Object>> sparklinesAll(String type, int hours) {
        return sparklines(type, hours, monitorTeams(type).keySet(), false);
    }

    /** Global görücü SLA sonucu — ortak önbellek (CacheConfig "monitor-sla", 300 sn; arayüz 5 dk'da bir tazeler). */
    @org.springframework.cache.annotation.Cacheable(value = "monitor-sla", key = "#type + ':' + #days", sync = true)
    public Map<Long, Map<String, Object>> availabilityAll(String type, int days) {
        return availability(type, days, monitorTeams(type).keySet(), Instant.now(), false);
    }

    /**
     * Ortak (tüm filo) sonucun görünür alt kümesi (performans 2026-11-06, PF2 #2) — kapsamlı çağıran da ortak önbellekten
     * okur. Her monitörün sonucu YALNIZ kendi kontrol satırlarından hesaplandığı için alt küme, kapsamlı sorgunun
     * ({@link #sparklines(String, int, Set)} / {@link #availability(String, int, Set)}) çıktısıyla birebirdir. Yeni harita;
     * değerler PAYLAŞILIR (önbellekteki iç haritalar değiştirilmez), sıra ortak sonucun sırası. Ortak sonuçta olmayan görünür
     * monitör (önbellek dolduktan sonra eklenmiş) atlanır — kartı bir sonraki önbellek turunda dolar (trend 60 sn, SLA 300 sn).
     */
    public static Map<Long, Map<String, Object>> subset(Map<Long, Map<String, Object>> all, Set<Long> visible) {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        if (all == null || visible == null || visible.isEmpty()) return out;
        all.forEach((id, v) -> { if (visible.contains(id)) out.put(id, v); });
        return out;
    }

    /** {@link #sparklinesFor} kümesinin tavanı (kimlik süzgeci: favori tavanı 200 ile aynı). */
    public static final int FOR_IDS_MAX = 200;
    /** {@link #sparklinesFor} belleğindeki en çok anahtar (tür × saat × kimlik kümesi; dolunca süresi geçenler, sonra hepsi atılır). */
    static final int FOR_IDS_MEMO_KEYS = 64;

    /** Küçük kimlik kümesinin sonucu — ortak önbellekle ({@code monitor-sparklines}, 60 sn) aynı ömür. 0 → bellek kapalı. */
    @org.springframework.beans.factory.annotation.Value("${site.monitor.sparklines.ids-cache-ms:60000}")
    long idsCacheMs;

    /** Final değil: birim testi saat enjekte edilmiş bellekle değiştirir. */
    private com.sitemonitor.util.TtlMemo<Map<Long, Map<String, Object>>> idsMemo = new com.sitemonitor.util.TtlMemo<>(FOR_IDS_MEMO_KEYS);

    /**
     * Küçük, ÖNCEDEN KAPSAMLANMIŞ kimlik kümesinin trendi (2026-11-14, Kişisel Panom "favorilerin yanıt süresi"): tek favorisi
     * olan duvar ekranı türün tüm filosunu ({@link #sparklinesAll}) dakikada bir yeniden hesaplatmasın — sorgu
     * {@code monitor_id IN (…)} ile daraltılır (≤ {@value #FOR_IDS_MAX} kimlik, saat penceresi {@value #MAX_HOURS} ile
     * sınırlı). Sonuç kimlik kümesinin TAM belirlediği değerdir (kapsam süzgeci çağıranda, kümeye uygulanmış olarak gelir),
     * bu yüzden pod başına {@code tür:saat:sıralı kimlikler} anahtarıyla paylaşılır. Her izlemenin sonucu yalnız kendi
     * satırlarından hesaplandığından {@code subset(sparklinesAll(...), ids)} ile BİREBİRDİR. Saklanan değer paylaşılır —
     * değiştirilmemelidir.
     *
     * @param ids var olan ve görünür monitör kimlikleri (çağıran süzer); boş → boş harita
     */
    public Map<Long, Map<String, Object>> sparklinesFor(String type, int hours, Set<Long> ids) {
        if (ids == null || ids.isEmpty() || !supports(type)) return new LinkedHashMap<>();
        if (ids.size() > FOR_IDS_MAX) throw new IllegalArgumentException("en çok " + FOR_IDS_MAX + " kimlik");
        int h = Math.max(1, Math.min(MAX_HOURS, hours));
        List<Long> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        StringBuilder key = new StringBuilder(type).append(':').append(h).append(':');
        for (int i = 0; i < sorted.size(); i++) { if (i > 0) key.append(','); key.append(sorted.get(i)); }
        return idsMemo.get(key.toString(), idsCacheMs, false, () -> sparklines(type, h, new LinkedHashSet<>(sorted), true));
    }

    /**
     * @param ids görünür monitör id'leri (boş → boş harita); sorgu bu kümeyle daraltılır (O-7)
     * @return id → { n, fail, up_pct, buckets:[{t,n,fail,ms}], last:[{at,ok,ms}] }
     */
    public Map<Long, Map<String, Object>> sparklines(String type, int hours, Set<Long> ids) {
        return sparklines(type, hours, ids, true);
    }

    private Map<Long, Map<String, Object>> sparklines(String type, int hours, Set<Long> ids, boolean restrict) {
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

        // Saatlik kovalar — tek sorgu; görünür küme küçükse IN ile daraltılır (O-7), büyükse id süzgeci bellekte.
        IdFilter f = IdFilter.of(ids, restrict);
        String bucketSql = "SELECT t.monitor_id, t.bucket, COUNT(*) AS n, SUM(t.fail) AS fail, AVG(t.ms) AS ms FROM ("
                + "  SELECT monitor_id, substr(checked_at,1,13) AS bucket, " + k.msCol() + " AS ms, " + k.failExpr() + " AS fail"
                + "    FROM " + k.table() + " WHERE checked_at >= ?" + f.sql()
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
        }, f.with(from));

        // Son 5 kontrol — pencere fonksiyonu; hata verirse (eski motor) yalnız kovalarla dönülür.
        String lastSql = "SELECT monitor_id, checked_at, ms, fail FROM ("
                + "  SELECT monitor_id, checked_at, " + k.msCol() + " AS ms, " + k.failExpr() + " AS fail,"
                + "         ROW_NUMBER() OVER (PARTITION BY monitor_id ORDER BY checked_at DESC) AS rn"
                + "    FROM " + k.table() + " WHERE checked_at >= ?" + f.sql()
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
            }, f.with(from));
        } catch (Exception e) {
            log.debug("Sparkline son-5 sorgusu desteklenmedi ({}): {}", type, e.toString());
        }

        for (Map<String, Object> m : out.values()) {
            int n = (Integer) m.get("n"), fail = (Integer) m.get("fail");
            m.put("up_pct", com.sitemonitor.util.AvailabilityMath.pct(n, n - fail, 1));   // O-5: R6 kuralının aynı dosyadaki kardeşi
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
        return availability(type, days, ids, now, true);
    }

    private Map<Long, Map<String, Object>> availability(String type, int days, Set<Long> ids, Instant now, boolean restrict) {
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
        boolean budget = k.breachExpr() != null;   // Sayfa Hızı: ayrı bütçe uyumu sayısı (madde 5)
        for (Long id : ids) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", 0); m.put("fail", 0); m.put("up_pct", null); m.put("bad_hours", 0);
            if (budget) { m.put("breach", 0); m.put("undecided", 0); m.put("budget_pct", null); }
            List<Map<String, Object>> wl = new ArrayList<>();
            for (int w : wins) {
                Map<String, Object> wm = new LinkedHashMap<>();
                wm.put("days", w); wm.put("n", 0); wm.put("fail", 0); wm.put("up_pct", null); wm.put("bad_hours", 0);
                if (budget) { wm.put("breach", 0); wm.put("undecided", 0); wm.put("budget_pct", null); }
                wm.put("slots", new ArrayList<Map<String, Object>>());
                wl.add(wm);
            }
            m.put("windows", wl);
            out.put(id, m);
        }

        // Adet / hata — tüm pencere + her dönem tek geçişte. Parametre sırası: select listesindeki dönem ?'leri, sonra WHERE.
        // Bütçe ihlali (yalnız breachExpr'li tür) her sütun grubunun SONUNA eklenir: [n, fail, (breach)] × (tüm, dönemler).
        int stride = budget ? 4 : 2;
        StringBuilder sql = new StringBuilder("SELECT monitor_id, COUNT(*), SUM(fail)");
        if (budget) sql.append(", SUM(breach), SUM(undecided)");
        List<Object> args = new ArrayList<>();
        for (String wf : winFrom) {
            sql.append(", SUM(CASE WHEN checked_at >= ? THEN 1 ELSE 0 END), SUM(CASE WHEN checked_at >= ? THEN fail ELSE 0 END)");
            args.add(wf); args.add(wf);
            if (budget) {
                sql.append(", SUM(CASE WHEN checked_at >= ? THEN breach ELSE 0 END), SUM(CASE WHEN checked_at >= ? THEN undecided ELSE 0 END)");
                args.add(wf); args.add(wf);
            }
        }
        IdFilter f = IdFilter.of(ids, restrict);   // O-7: görünür kümeyle daralt
        sql.append(" FROM (SELECT monitor_id, checked_at, ").append(k.failExpr()).append(" AS fail");
        if (budget) sql.append(", ").append(k.breachExpr()).append(" AS breach, ").append(k.undecidedExpr()).append(" AS undecided");
        sql.append(" FROM ").append(k.table())
           .append(" WHERE checked_at >= ?").append(f.sql()).append(") t GROUP BY monitor_id");
        args.add(from);
        args.addAll(f.args());
        int head = budget ? 6 : 4;   // pencere sütunlarının başladığı indeks (1 tabanlı)
        jdbc.query(sql.toString(), rs -> {
            Map<String, Object> m = out.get(rs.getLong(1));
            if (m == null) return;
            m.put("n", rs.getInt(2)); m.put("fail", rs.getInt(3));
            if (budget) { m.put("breach", rs.getInt(4)); m.put("undecided", rs.getInt(5)); }
            List<Map<String, Object>> wl = windowsOf(m);
            for (int i = 0; i < wl.size(); i++) {
                wl.get(i).put("n", rs.getInt(head + stride * i));
                wl.get(i).put("fail", rs.getInt(head + 1 + stride * i));
                if (budget) {
                    wl.get(i).put("breach", rs.getInt(head + 2 + stride * i));
                    wl.get(i).put("undecided", rs.getInt(head + 3 + stride * i));
                }
            }
        }, args.toArray());

        // Hatalı saatler — yalnız HATALI kovalar döner (normalde birkaç satır), en yeni kova önce. Her dönem için o
        // kovadaki dönem İÇİ hata adedi ayrı sayılır: kova başı dönemden önce başlasa da yalnız dönem içi hata sayılır,
        // ve kullanıcı "o saatte 5 hata" görür (2026-09-24: "1'den fazla hata varsa o kadar hata adedi yazsın").
        StringBuilder badSql = new StringBuilder("SELECT t.monitor_id, t.bucket, COUNT(*)");
        List<Object> badArgs = new ArrayList<>();
        for (String wf : winFrom) { badSql.append(", SUM(CASE WHEN t.checked_at >= ? THEN 1 ELSE 0 END)"); badArgs.add(wf); }
        badSql.append(" FROM (SELECT monitor_id, checked_at, substr(checked_at,1,13) AS bucket, ").append(k.failExpr())
              .append(" AS fail FROM ").append(k.table()).append(" WHERE checked_at >= ?").append(f.sql()).append(") t WHERE t.fail = 1")
              .append(" GROUP BY t.monitor_id, t.bucket ORDER BY t.monitor_id, t.bucket DESC");
        badArgs.add(from);
        badArgs.addAll(f.args());
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

        // Bütçe tanımlı izlemeler (inceleme F7) — tek sorgu; okunamazsa bilinmiyor (bütçe uyumu yine hesaplanır, bayrak yazılmaz).
        java.util.Set<Long> withBudget = null;
        if (budget && k.budgetDefinedExpr() != null) {
            try {
                withBudget = new java.util.HashSet<>(jdbc.queryForList("SELECT id FROM " + k.monitorTable()
                        + " WHERE " + k.budgetDefinedExpr(), Long.class));
            } catch (Exception e) {
                log.debug("Bütçe tanımı okunamadı ({}): {}", type, e.toString());
            }
        }
        for (Map.Entry<Long, Map<String, Object>> e : out.entrySet()) {
            Map<String, Object> m = e.getValue();
            boolean defined = withBudget == null || withBudget.contains(e.getKey());
            m.put("up_pct", pct((Integer) m.get("n"), (Integer) m.get("fail")));
            if (budget) {
                if (withBudget != null) m.put("budget_defined", defined);
                m.put("budget_pct", defined ? budgetPct(m) : null);
            }
            for (Map<String, Object> wm : windowsOf(m)) {
                wm.put("up_pct", pct((Integer) wm.get("n"), (Integer) wm.get("fail")));
                if (budget) wm.put("budget_pct", defined ? budgetPct(wm) : null);
            }
        }
        return out;
    }

    /**
     * Bütçe uyumu (Sayfa Hızı, 2026-11-14): sayfanın ALINABİLDİĞİ ve eşikleri KARARA BAĞLANABİLEN kontrollerin kaçta kaçı
     * tüm eşiklerin içinde kaldı (2 hane, ihlal varken 100 gösterilmez). Alınamayan kontrol bütçe hakkında bir şey söylemez;
     * kısmi ölçümde kararsız kalan kontrol de (inceleme B1) — ikisi de paydaya girmez. Payda 0 → null (bilinmiyor, 0 değil).
     */
    static Double budgetPct(Map<String, Object> m) {
        int loaded = (Integer) m.get("n") - (Integer) m.get("fail");
        int breach = m.get("breach") instanceof Integer b ? b : 0;
        int undecided = m.get("undecided") instanceof Integer u ? u : 0;
        int decided = loaded - undecided;
        if (decided <= 0) return null;
        return com.sitemonitor.util.AvailabilityMath.pct(decided, Math.max(0, decided - breach), 2);
    }

    /**
     * Yüzde (2 hane). Hata varken ASLA 100 gösterilmez (regresyon R6): 30 günde 43.200 kontrolde tek hata
     * %100,00'e yuvarlanıyor, ipucu aynı anda "1 hata · %100,00" diyordu. Kardeş kural
     * {@code MonitoringController.uptimePct}.
     */
    static Double pct(int n, int fail) {
        return com.sitemonitor.util.AvailabilityMath.pct(n, n - fail, 2);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> windowsOf(Map<String, Object> m) { return (List<Map<String, Object>>) m.get("windows"); }
}
