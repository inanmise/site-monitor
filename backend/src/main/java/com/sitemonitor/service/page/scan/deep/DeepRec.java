package com.sitemonitor.service.page.scan.deep;

import com.sitemonitor.service.page.scan.ScanItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derin bölümlerin ürettiği öneri (2026-11, algoritma 3). Analizci bunu öneri satırına çevirir ({@code rec}); {@code finding}
 * doluysa {@code about} nesnelerine o bulgu kodu yazılır (öneriyi üreten kuralın listesi — sayı tamdır), boşsa öneri sayfa
 * düzeyindedir ve {@code about} yalnız "nesneleri göster" süzgecinin adresleridir.
 *
 * @param finding nesneye yazılan bulgu kodu (null = sayfa düzeyi)
 */
public record DeepRec(String code, String severity, Map<String, Object> params, Long savingBytes, Long savingMs,
                      List<ScanItem> about, String finding) {

    public DeepRec {
        params = params == null ? new LinkedHashMap<>() : params;
        about = about == null ? List.of() : about;
    }

    /** Parametre haritası ({@code k1, v1, k2, v2, …}; sıra korunur). */
    public static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static final String HIGH = "high", MEDIUM = "medium", LOW = "low";
}
