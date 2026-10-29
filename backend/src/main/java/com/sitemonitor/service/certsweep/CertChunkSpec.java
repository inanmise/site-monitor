package com.sitemonitor.service.certsweep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Yazılacak bir parça (2026-10-29, çok pod Sürüm 3-4 P0 sözleşmesi, tasarım R3-4 §4.1 / §4.5 adım 2) — koordinatör (B) kurar,
 * depo (A) {@code cert_sweep_chunks} satırına yazar ({@code domains_json} biçimi deponun iç işidir; işçiye
 * {@link ClaimedChunk#entries()} olarak döner).
 *
 * @param chunkNo     tur içinde tekil, ≥ 0 (ilk parçalar 0…n−1; ikinci görüş parçaları n'den devam eder)
 * @param entries     parçanın alan adları (en az bir); sıra = işçinin ölçme sırası (koordinatörün sırası — yayın kuruluşu
 *                    yakınlığı, §4.5; karar listesi yine klasik sırada kurulur)
 * @param avoidWorker yalnız ikinci görüş parçalarında: bu örnek kimliği bu parçayı SAHİPLENEMEZ (§2.6); diğerlerinde null
 */
public record CertChunkSpec(int chunkNo, List<Entry> entries, String avoidWorker) {

    /** {@code avoid_worker} sütun sınırı. */
    public static final int MAX_WORKER_CHARS = 200;

    public CertChunkSpec {
        if (chunkNo < 0) throw new IllegalArgumentException("chunkNo < 0: " + chunkNo);
        if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("parça boş");
        entries = List.copyOf(entries);
        if (avoidWorker != null && (avoidWorker.isBlank() || avoidWorker.length() > MAX_WORKER_CHARS)) {
            throw new IllegalArgumentException("avoidWorker geçersiz");
        }
    }

    /** İlk tur parçası (kaçınılan işçi yok). */
    public static CertChunkSpec of(int chunkNo, List<Entry> entries) {
        return new CertChunkSpec(chunkNo, entries, null);
    }

    /** Parçadaki alan adı sayısı ({@code domain_count}). */
    public int domainCount() {
        return entries.size();
    }

    /** Klasik satırlar ({@link Entry#toRow()}) — {@link CertChunkRunner#checkDomains} girdisi. */
    public List<Map<String, Object>> rows() {
        return rowsOf(entries);
    }

    /** Girdilerin klasik satırları (sıra korunur). */
    public static List<Map<String, Object>> rowsOf(List<Entry> entries) {
        List<Map<String, Object>> out = new ArrayList<>(entries == null ? 0 : entries.size());
        if (entries != null) for (Entry e : entries) out.add(e.toRow());
        return out;
    }

    @Override
    public String toString() {
        return "CertChunkSpec[no=" + chunkNo + ", domains=" + entries.size()
                + (avoidWorker == null ? "" : ", avoid=" + avoidWorker) + "]";
    }

    /**
     * Parçadaki bir alan adı — klasik toplamanın ({@code SchedulerService.runCheckForDomains}) bir satırdan okuduğu BEŞ değer
     * ve satırın turdaki sırası. Değerler satırdaki gibi HAM tutulur ({@code tls_mode} boş metin olabilir, {@code port} null
     * olabilir — klasik toplama aynı satırla aynı biçimde davransın: {@code (int) port} kutusuz açma, {@code blankToNull(tls_mode)}).
     *
     * @param index          alan adının turun vade listesindeki sırası (0…N−1) — karar listesi bu sırayla yeniden kurulur
     * @param domain         alan adı (boş olamaz)
     * @param port           port (satırdaki {@code Integer}; null olabilir — klasikte olduğu gibi toplama düşer)
     * @param useProxy       {@code Boolean.TRUE.equals(row.get("use_proxy"))}
     * @param tlsMode        satırdaki ham {@code tls_mode} (null / "" / "browser" / "default")
     * @param timeoutSeconds kayda özgü zaman aşımı (sn; null = genel ayar)
     */
    public record Entry(int index, String domain, Integer port, boolean useProxy, String tlsMode, Integer timeoutSeconds) {

        public Entry {
            if (index < 0) throw new IllegalArgumentException("index < 0: " + index);
            if (domain == null || domain.isBlank()) throw new IllegalArgumentException("domain boş");
        }

        /**
         * Klasik vade satırından (yalnız toplamanın okuduğu anahtarlar: {@code domain}, {@code port}, {@code use_proxy},
         * {@code tls_mode}, {@code timeout_seconds}; diğerleri — ör. {@code check_interval_hours} — taşınmaz). Tür hatası
         * klasik toplamadaki gibi {@code ClassCastException} fırlatır.
         */
        public static Entry of(int index, Map<String, Object> row) {
            if (row == null) throw new IllegalArgumentException("satır null");
            return new Entry(index, (String) row.get("domain"), (Integer) row.get("port"),
                    Boolean.TRUE.equals(row.get("use_proxy")), (String) row.get("tls_mode"),
                    (Integer) row.get("timeout_seconds"));
        }

        /**
         * Klasik satır: {@code domain}, {@code port}, {@code use_proxy}, {@code tls_mode} her zaman; {@code timeout_seconds}
         * yalnız doluysa ({@code loadDomainsFromInventory} ile aynı biçim). Toplama bu satırla klasikteki AYNI
         * {@code checkAsync(domain, port, use_proxy, blankToNull(tls_mode), timeout_seconds)} çağrısını yapar.
         */
        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("domain", domain);
            row.put("port", port);
            row.put("use_proxy", useProxy);
            row.put("tls_mode", tlsMode);
            if (timeoutSeconds != null) row.put("timeout_seconds", timeoutSeconds);
            return row;
        }
    }
}
