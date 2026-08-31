package com.sitemonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.xbill.DNS.DClass;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * DNS sorgu servisi (dnsjava tabanlı). JNDI versiyonundan geçildi ki TTL,
 * yanıt süresi, SOA ve authoritative nameserver bilgileri yakalanabilsin.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DnsCheckerService {

    private final AppSettingsService appSettings;

    private static final String[] STANDARD_TYPES = {"A", "AAAA", "CNAME", "MX", "TXT", "NS"};

    @Async("certCheckExecutor")
    public CompletableFuture<Map<String, Object>> checkAsync(String domain, String recordType) {
        return CompletableFuture.completedFuture(check(domain, recordType));
    }

    /**
     * Tek kayıt tipi sorgusu. Sonuç:
     *   success: boolean
     *   values: List<String> (sıralı)
     *   ttl: Long (en küçük TTL, null mümkün)
     *   response_ms: Long (sorgu süresi)
     *   error: String (success=false ise)
     */
    public Map<String, Object> check(String domain, String recordType) {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            int type = typeOf(recordType);
            Message response = sendQuery(domain, type);
            long responseMs = (System.nanoTime() - start) / 1_000_000L;

            List<Record> answers = response.getSection(Section.ANSWER);
            List<String> values = new ArrayList<>();
            Long minTtl = null;
            for (Record r : answers) {
                // Skip CNAME chain hops when the question was for A / AAAA etc.;
                // keep only records that match the type we actually asked for.
                if (type != Type.CNAME && r.getType() == Type.CNAME) continue;
                if (r.getType() != type) continue;
                values.add(rdataAsString(r));
                long t = r.getTTL();
                if (minTtl == null || t < minTtl) minTtl = t;
            }
            Collections.sort(values);

            int rcode = response.getRcode();
            boolean ok = rcode == Rcode.NOERROR && !values.isEmpty();
            result.put("success", ok);
            result.put("values", values);
            result.put("ttl", minTtl);
            result.put("response_ms", responseMs);
            if (!ok) {
                result.put("error", rcode == Rcode.NOERROR ? "no answer" : Rcode.string(rcode));
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("values", List.of());
            result.put("response_ms", (System.nanoTime() - start) / 1_000_000L);
            result.put("ttl", null);
            result.put("error", e.getMessage());
            log.debug("DNS check failed for {} {}: {}", recordType, domain, e.getMessage());
        }
        return result;
    }

    /**
     * Tek bir domain için tüm temel kayıt tiplerini + SOA + authoritative NS'leri tek payload'da döner.
     * Detail modal'da kullanılır.
     */
    public Map<String, Object> enrichedQuery(String domain) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Map<String, Object>> records = new LinkedHashMap<>();
        for (String type : STANDARD_TYPES) {
            records.put(type, check(domain, type));
        }
        result.put("records", records);

        // Authoritative servers — NS kayıt değerleri
        Object nsValues = records.get("NS").get("values");
        result.put("authoritative_servers", nsValues);

        // SOA
        result.put("soa", querySoa(domain));

        return result;
    }

    /** Belirli bir resolver IP'sine (ör. 8.8.8.8) doğrudan tek-kayıt sorgusu — çoklu-resolver tutarlılık
     *  (propagation) kontrolü için. {success, values(sıralı), error}. ExtendedResolver yerine SimpleResolver(ip). */
    public Map<String, Object> checkVia(String domain, String recordType, String resolverIp) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            int type = typeOf(recordType);
            String host = toHostname(domain);
            Name name = Name.fromString(host + ".");
            Record question = Record.newRecord(name, type, DClass.IN);
            Message query = Message.newQuery(question);
            int timeoutMs = appSettings.getInt("site.monitor.dns.query-timeout-ms", 2000);
            Resolver resolver = new SimpleResolver(resolverIp);
            resolver.setTimeout(Duration.ofMillis(Math.max(500, timeoutMs)));
            Message response = resolver.send(query);
            List<String> values = new ArrayList<>();
            for (Record r : response.getSection(Section.ANSWER)) {
                if (type != Type.CNAME && r.getType() == Type.CNAME) continue;
                if (r.getType() != type) continue;
                values.add(rdataAsString(r));
            }
            Collections.sort(values);
            boolean ok = response.getRcode() == Rcode.NOERROR && !values.isEmpty();
            result.put("success", ok);
            result.put("values", values);
            if (!ok) result.put("error", response.getRcode() == Rcode.NOERROR ? "no answer" : Rcode.string(response.getRcode()));
        } catch (Exception e) {
            result.put("success", false);
            result.put("values", List.of());
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * Çoklu-resolver tutarlılık (propagation) kontrolü: domain'i her resolver'a AYRI sorar, BAŞARILI
     * cevapların değer-setlerini karşılaştırır. ≥2 başarılı resolver varsa ve setler birebir AYNI değilse
     * inconsistent=true (split-DNS / propagation gecikmesi / poisoning sinyali). perResolver: ip → "değerler" | "HATA".
     */
    public Map<String, Object> checkPropagation(String domain, String recordType, List<String> resolverIps) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> perResolver = new LinkedHashMap<>();
        Set<String> distinctSets = new HashSet<>();
        int okCount = 0;
        for (String ip : resolverIps) {
            Map<String, Object> r = checkVia(domain, recordType, ip);
            if (Boolean.TRUE.equals(r.get("success"))) {
                @SuppressWarnings("unchecked")
                List<String> v = (List<String>) r.get("values");
                perResolver.put(ip, String.join(", ", v));
                distinctSets.add(String.join("\n", v));
                okCount++;
            } else {
                perResolver.put(ip, "HATA: " + r.getOrDefault("error", "?"));
            }
        }
        out.put("inconsistent", okCount >= 2 && distinctSets.size() > 1);
        out.put("per_resolver", perResolver);
        out.put("ok_count", okCount);
        return out;
    }

    public Map<String, Object> querySoa(String domain) {
        Map<String, Object> soa = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            Message response = sendQuery(domain, Type.SOA);
            long responseMs = (System.nanoTime() - start) / 1_000_000L;
            soa.put("response_ms", responseMs);

            // SOA may come back in ANSWER (for the apex domain) or AUTHORITY
            // (for any subdomain that doesn't have its own zone).
            SOARecord soaRecord = findSoa(response.getSection(Section.ANSWER));
            if (soaRecord == null) {
                soaRecord = findSoa(response.getSection(Section.AUTHORITY));
            }
            if (soaRecord != null) {
                soa.put("primary_ns",  soaRecord.getHost().toString());
                soa.put("admin_email", soaRecord.getAdmin().toString());
                soa.put("serial",      soaRecord.getSerial());
                soa.put("refresh",     soaRecord.getRefresh());
                soa.put("retry",       soaRecord.getRetry());
                soa.put("expire",      soaRecord.getExpire());
                soa.put("minimum_ttl", soaRecord.getMinimum());
                soa.put("ttl",         soaRecord.getTTL());
                soa.put("success",     true);
            } else {
                soa.put("success", false);
                soa.put("error", Rcode.string(response.getRcode()));
            }
        } catch (Exception e) {
            soa.put("success", false);
            soa.put("response_ms", (System.nanoTime() - start) / 1_000_000L);
            soa.put("error", e.getMessage());
        }
        return soa;
    }

    private static SOARecord findSoa(List<Record> records) {
        if (records == null) return null;
        for (Record r : records) {
            if (r instanceof SOARecord s) return s;
        }
        return null;
    }

    /**
     * Low-level DNS query that bypasses every dnsjava cache layer. The
     * resolver is created fresh per call and the Message is sent directly,
     * so {@code response_ms} measures the actual network round-trip.
     */
    private Message sendQuery(String domain, int type) throws Exception {
        String host = toHostname(domain);
        Name name = Name.fromString(host + ".");
        Record question = Record.newRecord(name, type, DClass.IN);
        Message query = Message.newQuery(question);
        // ExtendedResolver: OS/nslookup gibi TÜM sistem DNS sunucularini sirayla dener (primary timeout →
        // fallback). Per-query timeout canli yapilandirilabilir; toplam response_ms timeout gecikmesini
        // yansitir → DNS_SLOW tespiti buna dayanir.
        int timeoutMs = appSettings.getInt("site.monitor.dns.query-timeout-ms", 2000);
        Resolver resolver = systemResolver();
        resolver.setTimeout(Duration.ofMillis(Math.max(500, timeoutMs)));
        return resolver.send(query);
    }

    private volatile Resolver sharedSystemResolver;

    /** ExtendedResolver'ı BİR KEZ kur (leak analizi #4) — her sorguda sistem DNS config'ini okumak CPU churn'dü.
     *  dnsjava send() eşzamanlı-güvenli; setTimeout çağıranların hepsi aynı ayar değerini yazar (benign race). */
    private Resolver systemResolver() throws java.net.UnknownHostException {
        Resolver r = sharedSystemResolver;
        if (r == null) {
            synchronized (this) {
                r = sharedSystemResolver;
                if (r == null) { r = new ExtendedResolver(); sharedSystemResolver = r; }
            }
        }
        return r;
    }

    private static int typeOf(String recordType) {
        if (recordType == null) return Type.A;
        return switch (recordType.toUpperCase(Locale.ROOT)) {
            case "A"     -> Type.A;
            case "AAAA"  -> Type.AAAA;
            case "CNAME" -> Type.CNAME;
            case "MX"    -> Type.MX;
            case "TXT"   -> Type.TXT;
            case "NS"    -> Type.NS;
            case "SOA"   -> Type.SOA;
            case "PTR"   -> Type.PTR;
            case "SRV"   -> Type.SRV;
            case "CAA"   -> Type.CAA;
            default       -> Type.A;
        };
    }

    private static String rdataAsString(Record r) {
        return r.rdataToString();
    }

    /**
     * DNS sorgusundan ÖNCE domain'i çıplak hostname'e indirger: URL şeması (https://), userinfo (@),
     * path (/…), sorgu/fragment (?#), port (:443) ve trailing-dot ayıklanır, küçültülür.
     * "https://www.akbank.com/basvuru/Juzdan/" → "www.akbank.com". Zaten çıplak host ise değişmez.
     * URL-formunda saklanan monitorlerin sahte NXDOMAIN üretmesini önler; saklanan domain'e dokunmaz.
     */
    public static String toHostname(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return s;
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);          // şema at
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);                  // userinfo at
        int cut = s.length();                                  // path / query / fragment kes
        for (char c : new char[]{'/', '?', '#'}) {
            int i = s.indexOf(c);
            if (i >= 0 && i < cut) cut = i;
        }
        s = s.substring(0, cut);
        if (s.startsWith("[")) {                               // IPv6 literal: [::1]:53 → [::1]
            int rb = s.indexOf(']');
            if (rb >= 0) s = s.substring(0, rb + 1);
        } else {
            int colon = s.lastIndexOf(':');                    // port at
            if (colon >= 0) s = s.substring(0, colon);
        }
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * Distinguishes a real DNS change from a round-robin / GeoDNS rotation.
     *
     * <ul>
     *   <li>{@code prev == null} → first ever check, neither changed nor rotated.</li>
     *   <li><b>either side has NO records</b> (null/blank) → neither. Bir "degisiklik" iddiasi
     *       KARSILASTIRILABILIR IKI gozlem ister; cozumlemenin basarisiz oldugu bir tur, kaydin
     *       degistigini degil OKUNAMADIGINI soyler. Bu dal olmadan bos<->dolu gecisi AYRIK kume
     *       gorunup CHANGED uretiyordu: gecici bir DNS hatasi, once dususte sonra kurtulusta olmak
     *       uzere IKI sahte "DEGISTI" damgasi biraktigi icin kullanici tarafindan bildirildi.
     *       Cagiranlar zaten yalnizca basarili turlari karsilastirmali (Scheduler + elle tetikleme
     *       oyle yapar); bu dal o kuralin SURUKLENMESINE karsi son savunma -- gercek bir
     *       degisikligi maskeleyemez, cunku gercek degisiklik iki dolu kume gerektirir.</li>
     *   <li>identical strings → neither.</li>
     *   <li>same line-set (different order) → neither (identical content).</li>
     *   <li>sets share at least one line → rotation (e.g. CDN returning a
     *       subset of edge IPs).</li>
     *   <li>sets disjoint → real change.</li>
     * </ul>
     */
    public static ChangeKind detectChange(String prev, String current) {
        if (prev == null) return ChangeKind.NONE;
        if (prev.equals(current)) return ChangeKind.NONE;

        Set<String> prevSet = new HashSet<>(Arrays.asList((prev == null ? "" : prev).split("\n")));
        Set<String> nextSet = new HashSet<>(Arrays.asList((current == null ? "" : current).split("\n")));
        prevSet.remove("");
        nextSet.remove("");

        if (prevSet.equals(nextSet)) return ChangeKind.NONE;
        // Taraflardan biri BOSSA karsilastirilacak bir sey yok (bkz. javadoc).
        if (prevSet.isEmpty() || nextSet.isEmpty()) return ChangeKind.NONE;

        Set<String> intersect = new HashSet<>(prevSet);
        intersect.retainAll(nextSet);
        return intersect.isEmpty() ? ChangeKind.CHANGED : ChangeKind.ROTATED;
    }

    public enum ChangeKind { NONE, ROTATED, CHANGED }

    /**
     * Beklenen-değer kilidi karşılaştırması (ESNEK / hijack-odaklı): canlı değerlerden BEKLENEN sette
     * OLMAYANLARI döner. Boş liste = sapma yok. Beklenen boş/null ise kilit kapalı → boş döner. Rotasyon
     * (canlı = beklenenin alt kümesi) sapma SAYILMAZ; yalnız beklenmeyen/enjekte edilmiş değer raporlanır.
     */
    /** Satır (\n) ayrılmış değeri trim'lenmiş, boş-olmayan satırların listesine çevirir. */
    public static List<String> splitLines(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : joined.split("\n")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static List<String> unexpectedValues(String expectedJoined, List<String> live) {
        if (live == null || live.isEmpty()) return List.of();
        Set<String> expected = new HashSet<>(splitLines(expectedJoined));
        if (expected.isEmpty()) return List.of();
        List<String> unexpected = new ArrayList<>();
        for (String v : live) {
            String t = v == null ? "" : v.trim();
            if (!t.isEmpty() && !expected.contains(t)) unexpected.add(t);
        }
        return unexpected;
    }

    /**
     * Beklenen-set flip kontrolü: beklenen liste DOLU ve canlı değerlerin TAMAMI bu listenin içindeyse true.
     * İç/dış IP arasında gidip-gelen (split-horizon) kayıtlarda DNS_CHANGED alarmını bastırmak için kullanılır;
     * beklenen liste boşsa kilit kapalı sayılır → false (mevcut alarm davranışı değişmez).
     */
    public static boolean withinExpected(String expectedJoined, List<String> live) {
        if (splitLines(expectedJoined).isEmpty()) return false;
        if (live == null || live.isEmpty()) return false;
        boolean anyNonEmpty = live.stream().anyMatch(v -> v != null && !v.isBlank());
        return anyNonEmpty && unexpectedValues(expectedJoined, live).isEmpty();
    }

    /**
     * DNS_CHANGED alarm/re-alert ctx'i: son changed kayıttan eski/yeni değerler. Hem günlük otomatik
     * re-alert (MonitoringOutageService) hem MANUEL "Tekrar Bildir" (EscalationService) BUNU kullanır —
     * manuel yol daha önce ctx'siz gidip maili boş ESKİ/YENİ kutularıyla gönderiyordu (2026-08-03 bug'ı).
     * Kayıt yoksa boş map döner (çağıran generic mesaja düşer).
     */
    public static Map<String, Object> changeCtxOf(com.sitemonitor.model.DnsRecord r) {
        if (r == null) return Map.of();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("record_type", r.getRecordType());
        ctx.put("old_values", splitLines(r.getPreviousValue()));
        ctx.put("new_values", splitLines(r.getValue()));
        ctx.put("changed_at", r.getCheckedAt());
        return ctx;
    }

    /** Domain'in son changed=true kaydı (yoksa/hata halinde null) — changeCtxOf ile birlikte kullanılır. */
    public static com.sitemonitor.model.DnsRecord lastChangedRecord(
            com.sitemonitor.repository.DnsRecordRepository repo, String domain) {
        try {
            var rows = repo.findChangedByDomain(domain, org.springframework.data.domain.PageRequest.of(0, 1));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.debug("DNS_CHANGED son değişen kayıt okunamadı: {} — {}", domain, e.getMessage());
            return null;
        }
    }

    /**
     * Sorguların hangi çözümleyicilere gittiğinin yapılandırma görünümü. ExtendedResolver yanıtı hangi
     * sunucunun verdiğini açıklamaz; bu yüzden per-sorgu atıf değil, sıralı sunucu zinciri raporlanır.
     */
    public Map<String, Object> resolverConfigInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        List<String> servers = new ArrayList<>();
        try {
            for (java.net.InetSocketAddress a : org.xbill.DNS.ResolverConfig.getCurrentConfig().servers()) {
                servers.add(a.getAddress() != null ? a.getAddress().getHostAddress() : a.getHostString());
            }
        } catch (Exception e) {
            log.debug("Resolver config okunamadı: {}", e.getMessage());
        }
        info.put("servers", servers);
        info.put("source", "os");
        info.put("timeout_ms", appSettings.getInt("site.monitor.dns.query-timeout-ms", 2000));
        return info;
    }
}
