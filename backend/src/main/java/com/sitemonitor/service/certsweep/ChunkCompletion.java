package com.sitemonitor.service.certsweep;

import com.sitemonitor.service.checks.CheckJobRunner;
import com.sitemonitor.service.checks.CheckResultCodec;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parçanın tamamlanması (2026-10-29, çok pod Sürüm 3-4 P0 sözleşmesi, tasarım R3-4 §4.2 "Complete" / §4.4) — işçi (A) kurar,
 * {@link CertSweepStore#complete} yazar; koordinatör (B) {@link CertSweepStore#readResults} ile okuyup
 * {@link #decodeResults} ile çözer. TEK kodlama kuralı burada (A ↔ B konuşmadan uyuşsun).
 *
 * <p><b>{@code results_json} biçimi</b> (kalıcı): {@code [{"i":<turdaki sıra>,"t":<kırpıldı mı>,"r":<sonuç haritası>}, …]},
 * parçanın girdi sırasıyla. {@code r} = denetleyici çıktısı + {@code run_id} (klasik toplamanın haritası), 3-3'ün TEK
 * kodlayıcısıyla ({@link CheckResultCodec#encode}: 256 K karakter sınırı, aşan sonuçta uzun metinler kırpılır → {@code t = true};
 * yine sığmazsa {@code {"failed":"ResultTooLarge"}}). Çözüm {@link CheckResultCodec#decode} ile AYNI Jackson kuralı (sayılar
 * Integer / Long / Double, listeler ArrayList, haritalar LinkedHashMap — L-3 "JSON normalleştirmesine dek aynı").
 * <b>Asla günlüğe yazılmaz, hiçbir API'den dönmez.</b>
 *
 * @param id            parça kimliği
 * @param claimToken    sahiplenme belirteci ({@link ClaimedChunk#claimToken()}) — tamamlama CAS'ı
 * @param worker        ölçen pod'un örnek kimliği ({@code SchedulerService.instanceId()})
 * @param resultsJson   yukarıdaki biçimde sonuç dizisi
 * @param checked       sonuç sayısı (= parçanın alan adı sayısı)
 * @param networkErrors ağ sınıfı hata sayısı ({@link CertChunkRunner#networkErrorCount} — toplu kesinti kuralının sayımı;
 *                      YALNIZ ilerleme / Küme kartı / aykırı pod kuralı için, karar için ASLA)
 * @param errors        {@code status = error} sayısı
 * @param warnings      {@code warning = true} sayısı
 */
public record ChunkCompletion(long id, String claimToken, String worker, String resultsJson, int checked,
                              int networkErrors, int errors, int warnings) {

    /** {@code worker} sütun sınırı. */
    public static final int MAX_WORKER_CHARS = 200;

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() { };

    /**
     * İşçinin ölçtüğü sonuçlardan tamamlama kurar.
     *
     * @param chunk         sahiplenilen parça
     * @param worker        bu pod'un örnek kimliği
     * @param results       {@link CertChunkRunner#checkDomains} çıktısı — parçanın girdileriyle AYNI uzunluk ve sırada
     * @param networkErrors {@code runner.networkErrorCount(results)}
     * @throws IllegalArgumentException sonuç sayısı girdi sayısından farklıysa (toplama kapanışta erken durdu — tamamlanmaz)
     */
    public static ChunkCompletion of(ClaimedChunk chunk, String worker, List<Map<String, Object>> results, long networkErrors) {
        if (chunk == null) throw new IllegalArgumentException("parça null");
        if (results == null || results.size() != chunk.domainCount()) {
            throw new IllegalArgumentException("sonuç sayısı " + (results == null ? "null" : results.size())
                    + " ≠ " + chunk.domainCount());
        }
        List<Result> in = new ArrayList<>(results.size());
        int errors = 0;
        int warnings = 0;
        for (int k = 0; k < results.size(); k++) {
            Map<String, Object> r = results.get(k);
            if (r != null && "error".equals(r.get("status"))) errors++;
            if (r != null && Boolean.TRUE.equals(r.get("warning"))) warnings++;
            in.add(new Result(chunk.entries().get(k).index(), r, false));
        }
        return new ChunkCompletion(chunk.id(), chunk.claimToken(), worker, encodeResults(in), results.size(),
                (int) Math.min(Integer.MAX_VALUE, Math.max(0L, networkErrors)), errors, warnings);
    }

    /** Sonuç JSON'u ve belirteç yazılmaz. */
    @Override
    public String toString() {
        return "ChunkCompletion[id=" + id + ", worker=" + worker + ", checked=" + checked + ", networkErrors="
                + networkErrors + ", errors=" + errors + ", warnings=" + warnings + ", chars="
                + (resultsJson == null ? 0 : resultsJson.length()) + "]";
    }

    /**
     * Bir alan adının sonucu.
     *
     * @param index     turdaki sıra ({@link CertChunkSpec.Entry#index()})
     * @param result    sonuç haritası (kodlamada girdi; çözümde değiştirilebilir {@link LinkedHashMap})
     * @param truncated kodlayıcı uzun metinleri kırptı mı (kodlamada girdi değeri yok sayılır, kodlayıcı belirler)
     */
    public record Result(int index, Map<String, Object> result, boolean truncated) {

        /**
         * Sonuç karar listesine olduğu gibi girebilir mi: boş değil, {@link CheckJobRunner#KEY_FAILED} işareti yok ve kırpılmamış.
         * Değilse koordinatör o alan adını KENDİSİ yeniden ölçer (§4.5 adım 3, L-4).
         */
        public boolean usable() {
            return result != null && !result.isEmpty() && result.get(CheckJobRunner.KEY_FAILED) == null && !truncated;
        }

        /** Sonuç haritası yazılmaz. */
        @Override
        public String toString() {
            return "Result[i=" + index + ", truncated=" + truncated + ", usable=" + usable() + "]";
        }
    }

    /**
     * Sonuç dizisini kodlar — her harita {@link CheckResultCodec#encode} ile (null → {@code {"failed":"NullResult"}});
     * {@code t} = kodlayıcının kırpma işareti. Asla fırlatmaz.
     */
    public static String encodeResults(List<Result> results) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('[');
        if (results != null) {
            boolean first = true;
            for (Result r : results) {
                if (r == null) continue;
                CheckResultCodec.Encoded e = CheckResultCodec.encode(r.result());
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"i\":").append(r.index()).append(",\"t\":").append(e.truncated()).append(",\"r\":")
                        .append(e.json()).append('}');
            }
        }
        return sb.append(']').toString();
    }

    /**
     * Sonuç dizisini çözer. null / boş / bozuk JSON → BOŞ liste; biçimi bozuk öğe ({@code i} sayı değil, {@code r} harita
     * değil) atlanır. Çağıran (koordinatör) eksik / yinelenen / parçaya ait olmayan sıra numaralarını kendisi ayıklar ve
     * eksikleri yeniden ölçer. Asla fırlatmaz; JSON günlüğe yazılmaz.
     */
    @SuppressWarnings("unchecked")
    public static List<Result> decodeResults(String json) {
        List<Result> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        List<Object> items;
        try {
            items = JSON.readValue(json, LIST_TYPE);
        } catch (RuntimeException e) {
            return out;
        }
        if (items == null) return out;
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object i = m.get("i");
            Object r = m.get("r");
            if (!(i instanceof Number n) || !(r instanceof Map<?, ?>)) continue;
            long idx = n.longValue();
            if (idx < 0 || idx > Integer.MAX_VALUE) continue;
            Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) r);
            out.add(new Result((int) idx, result, Boolean.TRUE.equals(m.get("t"))));
        }
        return out;
    }
}
