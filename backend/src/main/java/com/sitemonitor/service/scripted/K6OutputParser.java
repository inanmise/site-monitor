package com.sitemonitor.service.scripted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * k6 {@code --out json=<dosya>} çıktısının SAF ayrıştırıcısı (2026-10-31, k6 inceleme D1) — adım (group) bazlı süre,
 * istek, başarısız istek, p95, check sayıları, en sık hata kodu; host bazlı TLS sürümü; ilk başarısız isteğin k6
 * {@code error_code}'u.
 *
 * <p>Biçim (k6 v2.3.0 ile ölçüldü — {@code src/test/resources/k6-fixtures/out-groups-230.ndjson}): satır başına bir JSON.
 * {@code {"type":"Metric",…}} tanım satırları atlanır; {@code {"metric":"<ad>","type":"Point","data":{"time":…,
 * "value":<sayı>,"tags":{…}}}} noktaları okunur. Etiketler: {@code group} ({@code "::Giriş::Alt adım"}, kök = ""),
 * {@code url}, {@code status}, {@code expected_response}, HTTPS'te {@code tls_version}, hatalı istekte {@code error_code};
 * {@code checks} noktasında {@code check} ve değer 1/0; {@code group_duration} her grup yürütmesinde bir kez (ms).
 * Dikkat: istekte {@code tags: {name: 'x'}} verilince k6 {@code url} etiketini de {@code x} yapar — o istek için host
 * bilinemez (TLS listesine girmez).
 *
 * <p>Sınırlar (bellek + süre): toplam en çok {@link #MAX_BYTES} bayt ve {@link #MAX_LINES} satır okunur — aşılınca
 * okuma DURUR ve sonuç {@code truncated} işaretlenir; tek satır {@link #MAX_LINE_BYTES} baytı aşarsa o satır atlanır
 * (yine sayılır). En çok {@link #MAX_STEPS} adım, {@link #MAX_TLS_HOSTS} host; grup başına en çok
 * {@link #MAX_DURATIONS_PER_STEP} süre örneği (p95 için). Hiçbir koşulda istisna fırlatmaz.
 */
public final class K6OutputParser {

    private K6OutputParser() {}

    public static final long MAX_BYTES = 8L * 1024 * 1024;
    public static final int MAX_LINES = 100_000;
    public static final int MAX_LINE_BYTES = 64 * 1024;
    public static final int MAX_STEPS = 50;
    public static final int MAX_TLS_HOSTS = 20;
    public static final int MAX_STEP_NAME = 200;
    static final int MAX_DURATIONS_PER_STEP = 20_000;

    /** k6 grup yolu ayracı. */
    private static final String GROUP_SEP = "::";
    /** Adım adı ayracı (UI ile aynı). */
    public static final String NAME_SEP = " › ";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Bir adımın (k6 group) koşum özeti. {@code name} kök için "". */
    public record Step(String name, Long durationMs, int requests, int failedRequests, Long p95Ms,
                       int checksPassed, int checksFailed, Integer errorCode) {}

    /** Host başına TLS sürümü — sertifika durumu OKUMA anında eklenir. */
    public record TlsHost(String host, String tlsVersion, int requests) {}

    /**
     * Ayrıştırma sonucu.
     *
     * @param firstFailedErrorCode ilk BAŞARISIZ isteğin ({@code http_req_failed} = 1) k6 hata kodu; yoksa null
     * @param firstFailedStep      o isteğin adımı ("" = kök); yoksa null
     * @param firstFailedStatus    o isteğin HTTP durumu (0 = yanıt yok); yoksa null
     * @param firstFailedHost      o isteğin hostu (URL çözülebildiyse); yoksa null
     * @param failingCheck         ilk düşen check'in adı (adım öneki ile); yoksa null
     */
    public record Parsed(List<Step> steps, boolean truncated, List<TlsHost> tls,
                         Integer firstFailedErrorCode, String firstFailedStep, Integer firstFailedStatus,
                         String firstFailedHost, String failingCheck, boolean anyPoint) {
        public static final Parsed EMPTY = new Parsed(List.of(), false, List.of(), null, null, null, null, null, false);
    }

    /** Akış okuyucunun durumu — grup başına toplayıcı. */
    private static final class Acc {
        final String group;
        long durationSum = 0;
        boolean anyDuration = false;
        int requests = 0;
        int failed = 0;
        int checksPassed = 0;
        int checksFailed = 0;
        double[] durations = new double[16];
        int durationCount = 0;
        final Map<Integer, Integer> errorCodes = new HashMap<>();

        Acc(String group) { this.group = group; }

        void addDuration(double v) {
            if (durationCount >= MAX_DURATIONS_PER_STEP) return;
            if (durationCount == durations.length) durations = Arrays.copyOf(durations, durations.length * 2);
            durations[durationCount++] = v;
        }
    }

    /**
     * Akıştan ayrıştırır. {@code nameMask} adım / check adlarına ve hostlara uygulanır (gizli değer maskesi) — null ise
     * kimlik. Akışı kapatmak çağıranın işidir.
     */
    public static Parsed parse(InputStream in, UnaryOperator<String> nameMask) {
        if (in == null) return Parsed.EMPTY;
        UnaryOperator<String> mask = nameMask == null ? s -> s : nameMask;
        Map<String, Acc> groups = new LinkedHashMap<>();
        Map<String, int[]> tlsCounts = new LinkedHashMap<>();   // host|ver → [requests]
        boolean truncated = false;
        boolean anyPoint = false;
        Integer firstCode = null;
        String firstStep = null;
        Integer firstStatus = null;
        String firstHost = null;
        String failingCheck = null;
        long bytes = 0;
        int lines = 0;
        try {
            LineReader reader = new LineReader(in);
            while (true) {
                if (bytes >= MAX_BYTES || lines >= MAX_LINES) {
                    truncated = reader.hasMore();
                    break;
                }
                LineReader.Line line = reader.next();
                if (line == null) break;
                bytes += line.rawBytes();
                lines++;
                if (line.text() == null) continue;           // aşırı uzun satır: atlandı
                String t = line.text();
                if (!t.contains("\"Point\"")) continue;
                String metric = metricOf(t);
                if (metric == null) continue;
                JsonNode node;
                try {
                    node = MAPPER.readTree(t);
                } catch (Exception e) {
                    continue;
                }
                JsonNode data = node.path("data");
                JsonNode tags = data.path("tags");
                double value = data.path("value").asDouble(0);
                String group = tags.path("group").asText("");
                anyPoint = true;
                Acc acc = groups.get(group);
                if (acc == null) {
                    if (groups.size() >= MAX_STEPS) {
                        truncated = true;
                        continue;                            // yeni adım kabul edilmez; mevcut adımlar sayılmaya devam
                    }
                    acc = new Acc(group);
                    groups.put(group, acc);
                }
                switch (metric) {
                    case "http_reqs" -> {
                        acc.requests++;
                        Integer code = intTag(tags, "error_code");
                        if (code != null) acc.errorCodes.merge(code, 1, Integer::sum);
                        String ver = tags.path("tls_version").asText("");
                        if (!ver.isEmpty()) {
                            String host = hostOf(tags.path("url").asText(""));
                            if (host != null) {
                                String key = mask.apply(host) + "|" + ver;
                                int[] c = tlsCounts.get(key);
                                if (c != null) c[0]++;
                                else if (tlsCounts.size() < MAX_TLS_HOSTS) tlsCounts.put(key, new int[]{1});
                                else truncated = true;
                            }
                        }
                    }
                    case "http_req_failed" -> {
                        if (value >= 1) {
                            acc.failed++;
                            if (firstStep == null) {
                                firstStep = group;
                                firstCode = intTag(tags, "error_code");
                                firstStatus = intTag(tags, "status");
                                String h = hostOf(tags.path("url").asText(""));
                                firstHost = h == null ? null : mask.apply(h);
                            }
                        }
                    }
                    case "http_req_duration" -> acc.addDuration(value);
                    case "checks" -> {
                        if (value >= 1) acc.checksPassed++;
                        else {
                            acc.checksFailed++;
                            if (failingCheck == null) {
                                String name = tags.path("check").asText("");
                                String step = stepName(group);
                                failingCheck = fit(mask.apply(step.isEmpty() ? name : step + NAME_SEP + name));
                            }
                        }
                    }
                    case "group_duration" -> {
                        acc.durationSum += Math.round(value);
                        acc.anyDuration = true;
                    }
                    default -> { }
                }
            }
        } catch (Exception e) {
            truncated = true;
        }
        List<Step> steps = new ArrayList<>();
        for (Acc a : groups.values()) {
            // Kök yalnız isteği / check'i varsa adım olur (kök group_duration noktası yoktur)
            if (a.requests == 0 && a.checksPassed + a.checksFailed == 0 && !a.anyDuration) continue;
            String name = fit(mask.apply(stepName(a.group)));
            Long dur = a.group.isEmpty() || !a.anyDuration ? null : a.durationSum;
            Long p95 = a.durationCount == 0 ? null : Math.round(percentile(a.durations, a.durationCount, 0.95));
            steps.add(new Step(name, dur, a.requests, a.failed, p95, a.checksPassed, a.checksFailed,
                    mostFrequent(a.errorCodes)));
        }
        List<TlsHost> tls = new ArrayList<>();
        for (Map.Entry<String, int[]> e : tlsCounts.entrySet()) {
            int bar = e.getKey().lastIndexOf('|');
            tls.add(new TlsHost(e.getKey().substring(0, bar), e.getKey().substring(bar + 1), e.getValue()[0]));
        }
        return new Parsed(steps, truncated, tls, firstCode, firstStep == null ? null : fit(mask.apply(stepName(firstStep))),
                firstStatus, firstHost, failingCheck, anyPoint);
    }

    /** {@code "::Giriş::Alt adım"} → {@code "Giriş › Alt adım"}; kök "" → "". */
    public static String stepName(String group) {
        if (group == null || group.isEmpty()) return "";
        String g = group.startsWith(GROUP_SEP) ? group.substring(GROUP_SEP.length()) : group;
        return g.replace(GROUP_SEP, NAME_SEP);
    }

    private static String fit(String s) {
        if (s == null) return null;
        return s.length() <= MAX_STEP_NAME ? s : s.substring(0, MAX_STEP_NAME - 1) + "…";
    }

    /** Hızlı ön süzgeç: yalnız ilgilenilen metriklerin noktaları JSON olarak ayrıştırılır. */
    private static String metricOf(String line) {
        for (String m : List.of("http_reqs", "http_req_failed", "http_req_duration", "checks", "group_duration")) {
            if (line.contains("\"metric\":\"" + m + "\"")) return m;
        }
        return null;
    }

    private static Integer intTag(JsonNode tags, String name) {
        JsonNode v = tags.path(name);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText("").trim();
        if (s.isEmpty() || s.length() > 9) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** URL'nin hostu (küçük harf); URL değilse null. */
    static String hostOf(String url) {
        if (url == null || url.length() > 4096 || !(url.startsWith("http://") || url.startsWith("https://"))) return null;
        try {
            String h = URI.create(url.replace(" ", "%20")).getHost();
            return h == null || h.isBlank() ? null : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer mostFrequent(Map<Integer, Integer> counts) {
        Integer best = null;
        int bestCount = 0;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount || (e.getValue() == bestCount && best != null && e.getKey() < best)) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    /** k6'nın yüzdelik hesabı: sıralı dizide {@code p × (n − 1)} konumunda doğrusal ara değer. */
    static double percentile(double[] values, int count, double p) {
        if (count <= 0) return 0;
        double[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        if (count == 1) return sorted[0];
        double pos = p * (count - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted[lo];
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }

    /**
     * Sınırlı satır okuyucu: satırı en çok {@link #MAX_LINE_BYTES} bayta kadar biriktirir, fazlasını okuyup atar (satır
     * {@code text = null} döner). Toplam okunan bayt satır başına {@code rawBytes} ile raporlanır.
     */
    static final class LineReader {
        record Line(String text, long rawBytes) {}

        private final InputStream in;
        private final byte[] buf = new byte[8192];
        private int pos = 0, len = 0;
        private boolean eof = false;

        LineReader(InputStream in) { this.in = in; }

        boolean hasMore() throws IOException {
            if (pos < len) return true;
            if (eof) return false;
            fill();
            return pos < len;
        }

        private void fill() throws IOException {
            len = in.read(buf);
            pos = 0;
            if (len < 0) { len = 0; eof = true; }
        }

        Line next() throws IOException {
            java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream(256);
            boolean overflow = false;
            long raw = 0;
            boolean any = false;
            while (true) {
                if (pos >= len) {
                    if (eof) break;
                    fill();
                    if (eof && len == 0) break;
                    continue;
                }
                any = true;
                byte b = buf[pos++];
                raw++;
                if (b == '\n') break;
                if (raw >= MAX_BYTES) {          // satır sonu gelmeyen dev satır: toplam tavanda dur (dosya boyu değil)
                    overflow = true;
                    break;
                }
                if (!overflow) {
                    if (line.size() >= MAX_LINE_BYTES) overflow = true;
                    else line.write(b);
                }
            }
            if (!any) return null;
            if (overflow) return new Line(null, raw);
            String s = line.toString(StandardCharsets.UTF_8);
            if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
            return new Line(s, raw);
        }
    }
}
