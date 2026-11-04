package com.sitemonitor.service.page.scan.deep;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ağ profili tahmini (2026-11, kaynak analizi algoritma 3) — SAF, TEK model, doğruluk tablolu. Gerçek ölçüm DEĞİL: aynı
 * sayfanın farklı bağlantılarda yaklaşık ne kadar sürede yükleneceğinin kaba modelidir (arayüz "tahmin" yazar).
 *
 * <p>Model ({@value #PARALLEL} paralel bağlantı, bant paylaşılır):
 * <ul>
 *   <li>bağlantı kurulumu host başına {@code 3 × RTT} (TLS'li; DNS + TCP + TLS), düz HTTP'de {@code 2 × RTT}; yeni host'lar
 *       paralel kurulur → bir tur;</li>
 *   <li>HTML: kurulum + 1 RTT + bayt / bant;</li>
 *   <li>yükleme: HTML + (yeni host kurulumu) + ⌈istek / 6⌉ × RTT + stil zinciri derinliği × RTT + tüm bayt / bant;</li>
 *   <li>ilk boyama: HTML + yalnız oluşturmayı ENGELLEYEN kaynaklar için aynı hesap.</li>
 * </ul>
 * Geç yüklenen nesneler, belgeler ve bağlantılar girmez.
 */
public final class NetworkProfiles {

    public static final int PARALLEL = 6;

    /** Profil: gidiş-dönüş (ms) ve bant (Mbit/s). Sabitler TEK yerde. */
    public record Profile(String key, int rttMs, double mbps) {
        double bytesPerMs() {
            return mbps * 1_000_000.0 / 8.0 / 1000.0;
        }
    }

    public static final List<Profile> PROFILES = List.of(
            new Profile("fiber", 10, 100), new Profile("4g", 70, 9), new Profile("3g", 300, 1.6),
            new Profile("slow3g", 400, 0.4));

    /** Modeldeki nesne: host, TLS mi, bayt, engelliyor mu, stil zinciri derinliği (HTML'den 0). */
    public record Obj(String host, boolean tls, long bytes, boolean blocking, int cssDepth) {}

    private NetworkProfiles() {}

    public static List<Map<String, Object>> estimate(String mainHost, boolean mainTls, long htmlBytes, List<Obj> objs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Profile p : PROFILES) {
            long[] r = estimate(p, mainHost, mainTls, htmlBytes, objs);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p.key());
            m.put("rtt_ms", p.rttMs());
            m.put("mbps", p.mbps());
            m.put("load_ms", r[0]);
            m.put("first_paint_ms", r[1]);
            out.add(m);
        }
        return out;
    }

    /** {yükleme, ilk boyama} (ms). */
    static long[] estimate(Profile p, String mainHost, boolean mainTls, long htmlBytes, List<Obj> objs) {
        double rtt = p.rttMs();
        double bw = p.bytesPerMs();
        double html = setup(mainTls, rtt) + rtt + Math.max(0, htmlBytes) / bw;
        double load = html + phase(objs, mainHost, rtt, bw, false);
        double paint = html + phase(objs, mainHost, rtt, bw, true);
        return new long[]{Math.round(load), Math.round(paint)};
    }

    private static double phase(List<Obj> objs, String mainHost, double rtt, double bw, boolean blockingOnly) {
        int n = 0;
        long bytes = 0;
        int depth = 0;
        boolean newTls = false, newPlain = false;
        Set<String> hosts = new HashSet<>();
        for (Obj o : objs) {
            if (blockingOnly && !o.blocking()) continue;
            n++;
            bytes += Math.max(0, o.bytes());
            depth = Math.max(depth, o.cssDepth());
            if (o.host() != null && !o.host().equals(mainHost) && hosts.add(o.host())) {
                if (o.tls()) newTls = true; else newPlain = true;
            }
        }
        if (n == 0) return 0;
        double setup = newTls ? setup(true, rtt) : newPlain ? setup(false, rtt) : 0;
        int rounds = (n + PARALLEL - 1) / PARALLEL;
        return setup + rounds * rtt + depth * rtt + bytes / bw;
    }

    private static double setup(boolean tls, double rtt) {
        return (tls ? 3 : 2) * rtt;
    }
}
