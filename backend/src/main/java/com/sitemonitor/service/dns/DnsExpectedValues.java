package com.sitemonitor.service.dns;

import java.util.Locale;

/**
 * DNS izlemesinin "beklenen değer" (kilit) listesindeki ağ aralığı (CIDR) satırlarının kaydetme kuralı (2026-11-18).
 *
 * <p>Liste satır satırdır; her satır ya bir değerdir (eskisi gibi tam metin eşleşmesi) ya da — yalnız A / AAAA
 * kayıtlarında — {@code 188.114.96.0/20} gibi bir aralıktır. Değer satırları DOĞRULANMAZ (eski davranış; TXT değerleri
 * {@code ip4:10.0.0.0/8} gibi eğik çizgi içerebilir). Yalnız aralık niyeti taşıyan satırlar denetlenir:
 * <ul>
 *   <li>A / AAAA kaydında {@code /} içeren satır geçerli bir aralık olmalı ({@link #CODE_INVALID});</li>
 *   <li>aralığın ailesi kayıt tipine uymalı: A → IPv4, AAAA → IPv6 ({@link #CODE_FAMILY});</li>
 *   <li>aralık çok geniş olmamalı — kilit anlamsızlaşır: IPv4 en az /{@value #MIN_PREFIX_V4}, IPv6 en az
 *       /{@value #MIN_PREFIX_V6} ({@link #CODE_TOO_BROAD});</li>
 *   <li>başka kayıt tipinde (CNAME, MX, TXT, NS) aralık gibi okunan satır reddedilir — hiçbir değerle eşleşmezdi
 *       ({@link #CODE_RECORD_TYPE}).</li>
 * </ul>
 * JS ikizi: {@code frontend/src/components/dns/ipRanges.js} {@code expectedValueProblem}.
 */
public final class DnsExpectedValues {

    private DnsExpectedValues() {}

    public static final int MIN_PREFIX_V4 = 8;
    public static final int MIN_PREFIX_V6 = 16;

    public static final String CODE_INVALID = "CIDR_INVALID";
    public static final String CODE_FAMILY = "CIDR_FAMILY";
    public static final String CODE_TOO_BROAD = "CIDR_TOO_BROAD";
    public static final String CODE_RECORD_TYPE = "CIDR_RECORD_TYPE";

    /** İlk sorunlu satır ve nedeni. */
    public record Problem(String code, String line) {}

    /** A / AAAA mı (aralık yalnız bunlarda anlamlı)? */
    public static boolean addressType(String recordType) {
        if (recordType == null) return false;
        String t = recordType.strip().toUpperCase(Locale.ROOT);
        return "A".equals(t) || "AAAA".equals(t);
    }

    /** Listenin ilk sorunu; sorun yoksa (ya da liste boşsa) null. */
    public static Problem check(String joined, String recordType) {
        if (joined == null || joined.isBlank()) return null;
        String type = recordType == null ? "" : recordType.strip().toUpperCase(Locale.ROOT);
        boolean address = addressType(type);
        for (String raw : joined.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (!address) {
                if (IpRanges.parse(line) != null) return new Problem(CODE_RECORD_TYPE, line);
                continue;
            }
            if (line.indexOf('/') < 0) continue;
            IpRanges.Range r = IpRanges.parse(line);
            if (r == null) return new Problem(CODE_INVALID, line);
            if (r.v4() != "A".equals(type)) return new Problem(CODE_FAMILY, line);
            if (r.prefix() < (r.v4() ? MIN_PREFIX_V4 : MIN_PREFIX_V6)) return new Problem(CODE_TOO_BROAD, line);
        }
        return null;
    }
}
