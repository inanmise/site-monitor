package com.sitemonitor.service.page.scan.deep;

import com.sitemonitor.service.page.scan.ScanItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bir derin bölümün çıktısı (2026-11, algoritma 3): JSON bölümü + öneriler. Liste yardımcıları da burada: her liste
 * {@value #MAX_ROWS} satıra kesilir ve yanına tam sayı ({@code <ad>_total}) yazılır.
 */
public record DeepSection(Map<String, Object> json, List<DeepRec> recs) {

    public static final int MAX_ROWS = 50;

    /** Listeyi {@value #MAX_ROWS} satırla yazar + {@code <key>_total}. */
    public static void putList(Map<String, Object> m, String key, List<?> rows) {
        List<?> r = rows == null ? List.of() : rows;
        m.put(key, r.size() > MAX_ROWS ? new ArrayList<>(r.subList(0, MAX_ROWS)) : r);
        m.put(key + "_total", r.size());
    }

    /** Nesnelerin toplam baytı. */
    public static long bytes(List<ScanItem> items) {
        long t = 0;
        for (ScanItem i : items) t += i.bytes;
        return t;
    }
}
