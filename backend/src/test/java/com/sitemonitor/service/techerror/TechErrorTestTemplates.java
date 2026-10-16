package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.Template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test şablonları. Otomatik trend bölümü 2026-10-16'dan beri VARSAYILANDA yok (kullanıcı kararı: grafikleri görsel olarak
 * kendileri sağlıyor); özellik duruyor (ekip şablon düzenleyiciden ekleyebilir) — trend testleri bu şablonla koşar.
 */
public final class TechErrorTestTemplates {

    private TechErrorTestTemplates() {}

    /** Varsayılan şablon + 12 aylık özetlerin önünde eski varsayılan trend bölümü (beş alan). */
    public static Template withTrend(String lang) {
        boolean en = "en".equals(lang);
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorTemplate.toMap(TechErrorTemplate.defaults(lang)));
        @SuppressWarnings("unchecked") List<Object> secs = new ArrayList<>((List<Object>) raw.get("sections"));
        int at = 0;
        for (int i = 0; i < secs.size(); i++) if ("charts".equals(((Map<?, ?>) secs.get(i)).get("id"))) at = i;
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("id", "auto-trend");
        trend.put("kind", "trend");
        trend.put("title", en ? "Automatic Trends (Last 12 Months)" : "Otomatik Trendler (Son 12 Ay)");
        trend.put("series", List.of("error-rate", "total-tx", "error-count", "unmapped-count", "quota-count"));
        secs.add(at, trend);
        raw.put("sections", secs);
        return TechErrorTemplate.normalize(raw);
    }
}
