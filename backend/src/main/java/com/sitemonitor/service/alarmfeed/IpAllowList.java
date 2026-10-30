package com.sitemonitor.service.alarmfeed;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;

/**
 * Alarm akışı IP izin listesi (2026-10-30) — saf, DNS sorgusu YAPMAZ ({@link InetAddress#ofLiteral} yalnız sayısal adres kabul
 * eder). Girdi: tek adres ({@code 10.20.30.40}, {@code 2001:db8::1}) ya da CIDR aralığı ({@code 10.0.0.0/8}).
 *
 * <p>Boş liste = herkes. Liste DOLUYSA yalnız eşleşen adres geçer; okunamayan bir istemci adresi hiçbir girdiyle eşleşmez
 * (kapalı başarısızlık). IPv4 adresi IPv6 aralığıyla (ve tersi) eşleşmez — IPv4'e eşlenmiş IPv6 ({@code ::ffff:a.b.c.d})
 * IPv4 olarak değerlendirilir.
 */
public final class IpAllowList {

    private IpAllowList() {}

    /** Okunamayan kayıtlı değer için: listeyi "dolu ama hiçbir şeyle eşleşmeyen" yapar (açık başarısızlık olmasın). */
    public static final String NONE = "!none";

    /**
     * Bir girdiyi doğrular ve kanonik biçime çevirir; geçersizse {@code null}. Tam uzunluktaki önek atılır
     * ({@code 1.2.3.4/32} → {@code 1.2.3.4}); ağ adresi önek uzunluğuna göre sıfırlanmaz (girildiği gibi yazılır).
     */
    public static String normalizeEntry(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        if (s.isEmpty() || s.length() > 64) return null;
        String addr = s;
        Integer prefix = null;
        int slash = s.indexOf('/');
        if (slash >= 0) {
            addr = s.substring(0, slash);
            String p = s.substring(slash + 1);
            if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(Character::isDigit)) return null;
            prefix = Integer.parseInt(p);
        }
        InetAddress ia = literal(addr);
        if (ia == null) return null;
        int bits = ia instanceof Inet4Address ? 32 : 128;
        if (prefix != null && (prefix < 0 || prefix > bits)) return null;
        String host = ia.getHostAddress();
        int zone = host.indexOf('%');
        if (zone >= 0) return null;   // kapsam kimlikli IPv6 kabul edilmez
        return prefix == null || prefix == bits ? host : host + "/" + prefix;
    }

    /** {@code entries} boşsa true; değilse istemci adresi bir girdiyle eşleşirse true. */
    public static boolean allowed(List<String> entries, String clientIp) {
        if (entries == null || entries.isEmpty()) return true;
        InetAddress client = literal(clientIp);
        if (client == null) return false;
        for (String e : entries) {
            if (e == null || NONE.equals(e)) continue;
            if (matches(e, client)) return true;
        }
        return false;
    }

    static boolean matches(String entry, InetAddress client) {
        String addr = entry;
        int prefix = -1;
        int slash = entry.indexOf('/');
        if (slash >= 0) {
            addr = entry.substring(0, slash);
            try {
                prefix = Integer.parseInt(entry.substring(slash + 1));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        InetAddress net = literal(addr);
        if (net == null) return false;
        byte[] a = net.getAddress();
        byte[] b = client.getAddress();
        if (a.length != b.length) return false;
        int bits = prefix < 0 ? a.length * 8 : prefix;
        int full = bits / 8;
        for (int i = 0; i < full; i++) if (a[i] != b[i]) return false;
        int rest = bits % 8;
        if (rest == 0) return true;
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (a[full] & mask) == (b[full] & mask);
    }

    /**
     * Dört bölümlü ondalık IPv4: her bölüm 1–3 rakam, 0–255, baştaki sıfır yok ({@code 010} sekizlik mi ondalık mı belirsiz —
     * ekrandaki doğrulamayla aynı kural, {@code alarmFeedModel.js}).
     */
    static boolean dottedQuad(String t) {
        String[] parts = t.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(Character::isDigit)) return false;
            if (p.length() > 1 && p.charAt(0) == '0') return false;
            if (Integer.parseInt(p) > 255) return false;
        }
        return true;
    }

    /** Sayısal adres → InetAddress (DNS yok); IPv4'e eşlenmiş IPv6 → IPv4. Geçersizse null. */
    static InetAddress literal(String s) {
        if (s == null) return null;
        String t = s.strip();
        if (t.isEmpty() || t.length() > 64) return null;
        if (t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length() - 1);
        // Yalnız sayısal adres karakterleri: ad çözümlemesine hiç girmez.
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '.' || c == ':';
            if (!ok) return null;
        }
        // IPv4 yalnız dört bölümlü noktalı yazımla (10 → 0.0.0.10 gibi eski biçimler kabul edilmez).
        if (t.indexOf(':') < 0 && !dottedQuad(t)) return null;
        try {
            InetAddress ia = InetAddress.ofLiteral(t);
            if (ia instanceof Inet6Address v6) {
                byte[] b = v6.getAddress();
                boolean mapped = true;
                for (int i = 0; i < 10; i++) if (b[i] != 0) { mapped = false; break; }
                if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                    return InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]});
                }
            }
            return ia;
        } catch (Exception e) {
            return null;
        }
    }
}
