package com.sitemonitor.service.diagnose;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bir tanılama koşusunun ortak iskeleti (2026-10-05): adım listesi ({@code steps[]}), bulgular, kopyalanabilir döküm
 * ({@code transcript}) ve süre tavanı. Ping / Port / DNS servisleri aynı sözleşmeyi bunun üzerinden üretir.
 *
 * <p>Adım: {@code {key, status: ok|warn|fail|skip, ms, detail{…}, error?}}. Döküm satırları curl -v diliyle
 * önek taşır: {@code *} bilgi, {@code >} gönderilen, {@code <} alınan.
 *
 * <p>İş parçacığı güvenliği: adımlar/döküm yalnız koşuyu yöneten iş parçacığından yazılır; paralel alt görevler kendi
 * sonuçlarını döndürür (ortak yapıya yazmaz) — {@link #transcript} senkronize, alt görevlerin satır eklemesine izin verir.
 */
public final class NetDiagRun {

    /** Bütün koşunun tavanı (ms) — HTTP/keyword ile aynı 60 sn; toplayıcı biraz önce keser. */
    public static final long RUN_CAP_MS = 58_000L;
    public static final int MIN_TIMEOUT_MS = 1000;
    public static final int MAX_TIMEOUT_MS = 30_000;

    private final long startMs = System.currentTimeMillis();
    private final long deadline = startMs + RUN_CAP_MS;
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private final List<Map<String, Object>> findings = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();
    private boolean timeLimited;

    public long startMs() { return startMs; }
    public long deadline() { return deadline; }
    public long elapsed() { return System.currentTimeMillis() - startMs; }
    public boolean expired() { return System.currentTimeMillis() >= deadline; }
    public long remaining() { return Math.max(1L, deadline - System.currentTimeMillis()); }

    /** Bir adımın kendi zaman aşımı, kalan koşu süresine kısılmış (ms). */
    public int budget(int wantMs) {
        long rem = deadline - System.currentTimeMillis() - 250L;
        return (int) Math.max(200L, Math.min(wantMs, rem));
    }

    public void markTimeLimited() { this.timeLimited = true; }
    public boolean timeLimited() { return timeLimited; }

    public List<Map<String, Object>> steps() { return steps; }
    public List<Map<String, Object>> findings() { return findings; }

    public void add(Map<String, Object> finding) { if (finding != null) findings.add(finding); }

    public void finding(String code, String severity, Map<String, Object> params) {
        findings.add(NetDiagFindings.finding(code, severity, params));
    }

    public void finding(String code, String severity, String path, Map<String, Object> params) {
        findings.add(NetDiagFindings.finding(code, severity, path, params));
    }

    // ── Döküm ──

    public synchronized void info(String s) { lines.add("* " + s); }
    public synchronized void sent(String s) { lines.add("> " + s); }
    public synchronized void recv(String s) { lines.add("< " + s); }
    public synchronized void blank() { if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) lines.add(""); }
    public synchronized void raw(String s) { lines.add(s); }

    public synchronized String transcript() {
        return String.join("\n", lines);
    }

    // ── Adımlar ──

    /** Adım başlat — {@link Step#ok}/… ile kapanınca {@link #steps}'e eklenir (sözleşme sırası = kapanış sırası). */
    public Step step(String key) { return new Step(this, key, null); }

    /** Yol adımı (Port: vekil/doğrudan yol başına) — kapanınca verilen listeye eklenir. */
    public Step step(String key, List<Map<String, Object>> into) { return new Step(this, key, into); }

    /** Çalıştırılmayan adım (önceki adım düştü / seçilmedi). */
    public void skip(String key, String reason) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reason", reason);
        steps.add(stepMap(key, "skip", null, d, null));
    }

    public static void skip(List<Map<String, Object>> into, String key, String reason) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reason", reason);
        into.add(stepMap(key, "skip", null, d, null));
    }

    static Map<String, Object> stepMap(String key, String status, Long ms, Map<String, Object> detail, Map<String, Object> error) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("key", key);
        s.put("status", status);
        s.put("ms", ms);
        s.put("detail", detail == null ? new LinkedHashMap<>() : detail);
        if (error != null) s.put("error", error);
        return s;
    }

    /** Açık adım: süre başlangıçtan ölçülür; ayrıntı kapanmadan önce doldurulabilir. */
    public static final class Step {
        private final NetDiagRun run;
        private final String key;
        private final List<Map<String, Object>> into;
        private final long t0 = System.nanoTime();
        private final Map<String, Object> detail = new LinkedHashMap<>();
        private Long fixedMs;

        Step(NetDiagRun run, String key, List<Map<String, Object>> into) {
            this.run = run;
            this.key = key;
            this.into = into;
        }

        public Step put(String k, Object v) { detail.put(k, v); return this; }
        public Map<String, Object> detail() { return detail; }
        public long ms() { return fixedMs != null ? fixedMs : (System.nanoTime() - t0) / 1_000_000L; }
        /** Süreyi ölçülen bir alt değerle sabitle (ör. komutun kendi süresi). */
        public Step ms(long ms) { this.fixedMs = ms; return this; }

        public Map<String, Object> ok() { return close("ok", null); }
        public Map<String, Object> warn() { return close("warn", null); }
        public Map<String, Object> fail(Throwable e) { return close("fail", e == null ? null : errorMap(e)); }
        public Map<String, Object> fail(String message) {
            Map<String, Object> err = null;
            if (message != null) {
                err = new LinkedHashMap<>();
                err.put("class", null);
                err.put("message", message);
            }
            return close("fail", err);
        }
        public Map<String, Object> skip(String reason) { detail.put("reason", reason); return close("skip", null); }
        public Map<String, Object> status(String status) { return close(status, null); }

        private Map<String, Object> close(String status, Map<String, Object> error) {
            Map<String, Object> s = stepMap(key, status, "skip".equals(status) ? null : ms(), detail, error);
            if (into != null) into.add(s);
            else run.steps.add(s);
            return s;
        }
    }

    // ── Yardımcılar ──

    /** {@code {class, message}} — tam sınıf adı kısaltılmış (yalnız basit ad), ileti ilk 300 karakter. */
    public static Map<String, Object> errorMap(Throwable e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("class", e.getClass().getSimpleName());
        m.put("message", message(e));
        return m;
    }

    public static String message(Throwable e) {
        if (e == null) return null;
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
        return msg.length() > 300 ? msg.substring(0, 299) + "…" : msg;
    }

    /** Bağlantı hatası → {@code refused | timeout | unreachable | error} (sınıf + ileti). */
    public static String connectOutcome(Throwable e) {
        Throwable t = e;
        for (int i = 0; t != null && i < 6; i++) {
            if (t instanceof SocketTimeoutException) return "timeout";
            if (t instanceof NoRouteToHostException) return "unreachable";
            if (t instanceof ConnectException) {
                String m = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
                if (m.contains("timed out")) return "timeout";
                if (m.contains("unreachable") || m.contains("no route")) return "unreachable";
                return "refused";
            }
            String m = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
            if (m.contains("timed out") || m.contains("timeout")) return "timeout";
            if (m.contains("network is unreachable") || m.contains("no route to host") || m.contains("host is unreachable")) return "unreachable";
            if (m.contains("connection refused")) return "refused";
            if (t.getCause() == t) break;
            t = t.getCause();
        }
        return "error";
    }

    /** IP metni (zone-id kırpılmış). */
    public static String ip(InetAddress a) {
        if (a == null) return null;
        String s = a.getHostAddress();
        int z = s.indexOf('%');
        return z >= 0 ? s.substring(0, z) : s;
    }

    /** İzlemenin zaman aşımını tanılama aralığına kısar (1–30 sn; yoksa varsayılan). */
    public static int clampTimeout(Integer ms, int fallback) {
        int v = ms == null || ms <= 0 ? fallback : ms;
        return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, v));
    }

    /** Yazdırılabilir önizleme: kontrol karakterleri {@code ·}, CR/LF {@code ⏎}; en çok {@code max} karakter. */
    public static String printable(byte[] b, int len, int max) {
        StringBuilder sb = new StringBuilder();
        if (b == null) return "";
        int n = Math.min(len, b.length);
        for (int i = 0; i < n && sb.length() < max; i++) {
            int c = b[i] & 0xff;
            if (c == '\n') sb.append('⏎');
            else if (c == '\r') continue;
            else if (c == '\t') sb.append(' ');
            else if (c < 0x20 || c == 0x7f) sb.append('·');
            else sb.append((char) c);
        }
        return sb.toString();
    }

    /** Onaltılık önizleme ({@code 48 54 54 50 …}), en çok {@code maxBytes} bayt. */
    public static String hex(byte[] b, int len, int maxBytes) {
        StringBuilder sb = new StringBuilder();
        if (b == null) return "";
        int n = Math.min(Math.min(len, maxBytes), b.length);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(i % 16 == 0 ? '\n' : ' ');
            sb.append(String.format("%02x", b[i] & 0xff));
        }
        return sb.toString();
    }
}
