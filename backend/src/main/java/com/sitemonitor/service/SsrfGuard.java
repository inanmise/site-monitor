package com.sitemonitor.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * SSRF koruması — giden izleme/tanılama hedeflerini bağlanmadan ÖNCE doğrular. Host çözülür ve HER çözülen IP
 * denetlenir; çağıran <b>döndürülen IP'lere</b> bağlanmalı (yeniden çözmemeli → DNS-rebind kapanır).
 *
 * <p>Politika ({@link #blockReason}):
 * <ul>
 *   <li>cloud-metadata (169.254.169.254 / fd00:ec2::254) → <b>HER ZAMAN</b> reddedilir (ayardan bağımsız).</li>
 *   <li>multicast + link-local (169.254/16 dahil) → <b>HER ZAMAN</b> reddedilir.</li>
 *   <li>loopback / any-local (127/8, ::1, 0.0.0.0, ::) → {@code site.monitor.monitoring.allow-loopback-targets}
 *       (vars. <b>false</b>) açık değilse reddedilir.</li>
 *   <li>site-local / ULA (iç/private ağ) → {@code site.monitor.monitoring.allow-internal-targets} (vars.
 *       <b>true</b> — bu bir iç izleme aracı) açıksa izinli, aksi halde reddedilir.</li>
 * </ul>
 * Kararlar canlı ayardan okunur; admin iç hedefleri kapatabilir. Metadata/loopback/link-local ayardan bağımsızdır.
 */
@Service
@RequiredArgsConstructor
public class SsrfGuard {

    private final AppSettingsService appSettings;

    /** Hedef engellendiğinde fırlatılır — çağıran bunu {@code open=false} + {@code error}'a çevirir (probe akışını bozmaz). */
    public static class BlockedException extends RuntimeException {
        public BlockedException(String message) { super(message); }
    }

    /**
     * Host DNS'te ÇÖZÜLMEDİ — bir politika reddi DEĞİL.
     *
     * <p>Ayrı tip olmasının sebebi kullanıcı geri bildirimi: "izin verilmeyen tanılama hedefi"
     * mesajı, aslında DNS'te kaydı olmayan bir host için de basılıyordu ve kullanıcı aracın
     * kendisini engellediğini sanıyordu. İki durumun ÇÖZÜMÜ tamamen farklı: biri host adını
     * düzeltmek, diğeri ayar/yetki işi. {@link BlockedException} alt tipi olduğu için mevcut
     * yakalayanların hepsi eskisi gibi çalışır.
     */
    public static class UnresolvableHostException extends BlockedException {
        public UnresolvableHostException(String message) { super(message); }
    }

    /** Fetch.error() içinde DNS-çözülemedi ile politika reddini ayırt etmek için sabit önek (PageCheckerService). */
    public static final String UNRESOLVABLE_PREFIX = "çözümlenemeyen host: ";

    public static boolean isUnresolvableMessage(String error) {
        return error != null && error.startsWith(UNRESOLVABLE_PREFIX);
    }

    /** Hostu çöz + tüm çözülen IP'leri doğrula. Engelliyse {@link BlockedException}. Döndürülen adreslere bağlanılmalı. */
    public List<InetAddress> validate(String host) {
        if (host == null || host.isBlank()) throw new BlockedException("boş hedef host");
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host.trim());
        } catch (UnknownHostException e) {
            throw new UnresolvableHostException(UNRESOLVABLE_PREFIX + host);
        }
        boolean allowInternal = appSettings.getBoolean("site.monitor.monitoring.allow-internal-targets", true);
        boolean allowLoopback = appSettings.getBoolean("site.monitor.monitoring.allow-loopback-targets", false);
        for (InetAddress ip : addrs) {
            String reason = blockReason(ip, allowInternal, allowLoopback);
            if (reason != null)
                throw new BlockedException("izin verilmeyen hedef " + host + " → " + ip.getHostAddress() + " (" + reason + ")");
        }
        return List.of(addrs);
    }

    /** null = izinli; aksi halde insan-okur engel nedeni. Saf/statik → ağsız test edilebilir. */
    static String blockReason(InetAddress ip, boolean allowInternal, boolean allowLoopback) {
        if (isCloudMetadata(ip)) return "cloud-metadata endpoint";
        if (ip.isMulticastAddress()) return "multicast";
        if (ip.isLinkLocalAddress()) return "link-local (169.254/fe80)";
        if ((ip.isLoopbackAddress() || ip.isAnyLocalAddress()) && !allowLoopback) return "loopback/any-local";
        if ((ip.isSiteLocalAddress() || isUniqueLocalV6(ip)) && !allowInternal) return "iç/private ağ (allow-internal-targets kapalı)";
        return null;
    }

    /** AWS/GCP/Azure IMDS uçları — ayardan bağımsız her zaman blok. */
    static boolean isCloudMetadata(InetAddress ip) {
        String h = ip.getHostAddress();
        int z = h.indexOf('%'); if (z >= 0) h = h.substring(0, z);   // IPv6 zone-id kırp
        return "169.254.169.254".equals(h)
                || "fd00:ec2:0:0:0:0:0:254".equalsIgnoreCase(h)
                || "fd00:ec2::254".equalsIgnoreCase(h);
    }

    /** fc00::/7 (ULA) — Java {@code isSiteLocalAddress} bunu iç saymaz; iç/private olarak ele al. */
    static boolean isUniqueLocalV6(InetAddress ip) {
        return ip instanceof Inet6Address && (ip.getAddress()[0] & 0xFE) == 0xFC;
    }

    /**
     * {@link #blockReason} politikasının CIDR karşılığı — k6 {@code --blacklist-ip} için tek kaynak. Metadata +
     * multicast + link-local HER ZAMAN; loopback yalnız {@code allowLoopback=false}; RFC1918+ULA yalnız
     * {@code allowInternal=false}. (k6 kendi DNS çözümünü yapar; bu yüzden aralık kuralları verilir.)
     */
    public List<String> blacklistCidrs() {
        boolean allowInternal = appSettings.getBoolean("site.monitor.monitoring.allow-internal-targets", true);
        boolean allowLoopback = appSettings.getBoolean("site.monitor.monitoring.allow-loopback-targets", false);
        return blacklistCidrs(allowInternal, allowLoopback);
    }

    /** Saf/statik — ağsız test edilebilir. */
    static List<String> blacklistCidrs(boolean allowInternal, boolean allowLoopback) {
        java.util.List<String> c = new java.util.ArrayList<>();
        // Her zaman: cloud-metadata + multicast + link-local
        c.add("169.254.169.254/32");
        c.add("fd00:ec2::254/128");
        c.add("224.0.0.0/4");
        c.add("ff00::/8");
        c.add("169.254.0.0/16");
        c.add("fe80::/10");
        if (!allowLoopback) {
            c.add("127.0.0.0/8");
            c.add("::1/128");
        }
        if (!allowInternal) {
            c.add("10.0.0.0/8");
            c.add("172.16.0.0/12");
            c.add("192.168.0.0/16");
            c.add("fc00::/7");
        }
        return c;
    }
}
