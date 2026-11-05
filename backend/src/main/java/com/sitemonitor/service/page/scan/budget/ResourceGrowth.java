package com.sitemonitor.service.page.scan.budget;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Eğilim — "en çok büyüyen nesneler" (2026-11, F2), SAF. Nesne ayrıntısı saklanan analizlerin ({@code items_json}, izleme
 * başına son 20) sayfa yükü nesneleri adrese göre eşlenir; ilk görüldüğü analizdeki bayt ile son görüldüğündeki bayt
 * karşılaştırılır.
 *
 * <p>Sayılan nesne: sayfa yükü ({@code page_load}), indirilmiş (karar {@code skipped} değil), boyutu bilinen
 * ({@code size_source} {@code unknown} değil) ve tavanda kesilmemiş ({@code truncated} değil), başarılı (karar {@code ok}; karar
 * yoksa — algoritma 1 — HTTP 2xx/3xx). Listeye giren nesne en az iki analizde görülmüş, EN YENİ analizde hâlâ yükleniyor ve
 * büyümüştür; sıra bayt artışına göre (eşitlikte adres). Sürüm numaralı adresler (ör. {@code app.3f2a.js}) her yayında yeni
 * nesne sayılır — bilinen sınır.
 *
 * <p>Ayrıştırma AKIŞLA yapılır (nesne başına yalnız birkaç alan okunur, analiz başına en çok {@value #MAX_ITEMS_PER_SCAN}
 * nesne); düzenli ifade yok.
 */
public final class ResourceGrowth {

    /** Dönen en çok nesne. */
    public static final int MAX_RESULTS = 20;
    /** Bir analizden okunan en çok nesne (tarama tavanı 5000; savunma). */
    static final int MAX_ITEMS_PER_SCAN = 10_000;

    private static final JsonFactory JSON = new JsonFactory();

    private ResourceGrowth() {}

    /** Bir nesnenin bir analizdeki ölçümü. */
    public record Obj(String category, long bytes) {}

    /** Bir analizin sayılan nesneleri (adres → ölçüm). */
    public record ScanObjects(long scanId, Map<String, Obj> objects) {}

    /**
     * {@code items_json}'dan sayılan nesneleri çıkarır; bozuk / boş metin → boş harita (asla fırlatmaz). Aynı adres bir
     * analizde iki kez varsa büyük bayt alınır.
     */
    public static ScanObjects parse(long scanId, String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) return new ScanObjects(scanId, new LinkedHashMap<>());
        return parse(scanId, new java.io.StringReader(itemsJson));
    }

    /**
     * {@link #parse(long, String)} — karakter akışından (O-4, 2026-11): sürücünün verdiği okuyucu doğrudan ayrıştırılır, metin
     * ikinci kez kopyalanmaz. Okuyucu bu çağrıda kapatılır. null / boş → boş harita (asla fırlatmaz).
     */
    public static ScanObjects parse(long scanId, java.io.Reader itemsJson) {
        Map<String, Obj> out = new LinkedHashMap<>();
        if (itemsJson == null) return new ScanObjects(scanId, out);
        try (JsonParser p = JSON.createParser(itemsJson)) {
            if (p.nextToken() != JsonToken.START_ARRAY) return new ScanObjects(scanId, out);
            int read = 0;
            while (p.nextToken() == JsonToken.START_OBJECT && read < MAX_ITEMS_PER_SCAN) {
                read++;
                String url = null, category = null, verdict = null, sizeSource = null;
                Long bytes = null;
                int status = -1;
                boolean pageLoad = false, truncated = false;
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String name = p.currentName();
                    JsonToken v = p.nextToken();
                    if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) {   // headers / findings … okunmaz
                        p.skipChildren();
                        continue;
                    }
                    switch (name) {
                        case "url" -> url = v == JsonToken.VALUE_STRING ? p.getText() : null;
                        case "category" -> category = v == JsonToken.VALUE_STRING ? p.getText() : null;
                        case "verdict" -> verdict = v == JsonToken.VALUE_STRING ? p.getText() : null;
                        case "size_source" -> sizeSource = v == JsonToken.VALUE_STRING ? p.getText() : null;
                        case "bytes" -> bytes = v == JsonToken.VALUE_NUMBER_INT ? p.getLongValue() : null;
                        case "status" -> status = v == JsonToken.VALUE_NUMBER_INT ? p.getIntValue() : -1;
                        case "page_load" -> pageLoad = v == JsonToken.VALUE_TRUE;
                        case "truncated" -> truncated = v == JsonToken.VALUE_TRUE;
                        default -> { /* okunmayan düz alan */ }
                    }
                }
                if (url == null || bytes == null || bytes < 0 || !pageLoad || truncated) continue;
                if ("unknown".equals(sizeSource) || "skipped".equals(verdict)) continue;
                boolean ok = verdict != null ? "ok".equals(verdict) : status >= 200 && status < 400;
                if (!ok) continue;
                Obj prev = out.get(url);
                if (prev == null || bytes > prev.bytes()) out.put(url, new Obj(category, bytes));
            }
        } catch (IOException | RuntimeException e) {
            // Bozuk ayrıntı: o analiz büyümeye katılmaz (okunan kadarı da atılır — yarım analiz yanlış "büyüme" üretmesin)
            return new ScanObjects(scanId, Map.of());
        }
        return new ScanObjects(scanId, out);
    }

    /**
     * En çok büyüyen ≤ {@value #MAX_RESULTS} nesne.
     *
     * @param scans eskiden yeniye sıralı analizler (≥ 2 değilse boş liste)
     */
    public static List<Map<String, Object>> compute(List<ScanObjects> scans) {
        if (scans == null || scans.size() < 2) return List.of();
        ScanObjects newest = scans.get(scans.size() - 1);
        Map<String, Track> tracks = new HashMap<>();
        for (ScanObjects s : scans) {
            for (Map.Entry<String, Obj> e : s.objects().entrySet()) {
                Track t = tracks.get(e.getKey());
                if (t == null) {
                    tracks.put(e.getKey(), new Track(s.scanId(), e.getValue()));
                } else {
                    t.last(s.scanId(), e.getValue());
                }
            }
        }
        List<Map.Entry<String, Track>> grown = new ArrayList<>();
        for (Map.Entry<String, Track> e : tracks.entrySet()) {
            Track t = e.getValue();
            if (t.scans >= 2 && t.lastScan == newest.scanId() && t.lastBytes > t.firstBytes) grown.add(e);
        }
        grown.sort(Comparator.<Map.Entry<String, Track>>comparingLong(e -> e.getValue().lastBytes - e.getValue().firstBytes)
                .reversed().thenComparing(Map.Entry::getKey));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < grown.size() && i < MAX_RESULTS; i++) {
            Map.Entry<String, Track> e = grown.get(i);
            Track t = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", e.getKey());
            m.put("category", t.category);
            m.put("first_bytes", t.firstBytes);
            m.put("last_bytes", t.lastBytes);
            m.put("delta_bytes", t.lastBytes - t.firstBytes);
            m.put("delta_pct", pct(t.firstBytes, t.lastBytes));
            m.put("scans", t.scans);
            m.put("first_scan_id", t.firstScan);
            m.put("last_scan_id", t.lastScan);
            out.add(m);
        }
        return out;
    }

    /** Yüzde artış, bir ondalık (yarı yukarı); ilk boyut 0 → null (sonsuz değil). */
    static Double pct(long first, long last) {
        if (first <= 0) return null;
        return BigDecimal.valueOf(last - first).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(first), 1, RoundingMode.HALF_UP).doubleValue();
    }

    /** Bir adresin ilk / son görülüşü. */
    private static final class Track {
        final long firstScan;
        final long firstBytes;
        long lastScan;
        long lastBytes;
        String category;
        int scans = 1;

        Track(long scan, Obj o) {
            this.firstScan = scan;
            this.firstBytes = o.bytes();
            this.lastScan = scan;
            this.lastBytes = o.bytes();
            this.category = o.category();
        }

        void last(long scan, Obj o) {
            this.lastScan = scan;
            this.lastBytes = o.bytes();
            if (o.category() != null) this.category = o.category();
            this.scans++;
        }
    }
}
