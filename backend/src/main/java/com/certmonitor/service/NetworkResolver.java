package com.certmonitor.service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Çok-A-kayıtlı host'lar için "happy-eyeballs" bağlantı yardımcıları.
 *
 * <p>Sorun: bir host birden çok A kaydına çözülüp farklı IP'ler farklı portları sunduğunda
 * (ör. NetScaler: 172.31.6.32 yalnız :80, 172.31.129.6 yalnız :443), {@code new
 * InetSocketAddress(hostname, port)} ile bağlanmak JVM'in seçtiği <b>tek</b> IP'ye gider —
 * yarısı yanlış VIP'e düşüp {@code Connection refused} alır (flapping). Tarayıcı/curl davranışı
 * ise çözümlenen tüm adresleri <b>sırayla</b> deneyip ilk kabul edende durur.
 *
 * <p>Bu yardımcı, çözümlenen tüm IP'leri döndürür ve ham Socket için "ilk erişilebilen"
 * bağlantı mantığını sağlar. Tek-A / IP-literal / çözümleme-hatası durumları eski davranışı
 * korur (hostname ile bağlan → doğal {@code UnknownHostException}).
 */
public final class NetworkResolver {

    private NetworkResolver() {}

    /** Host'un tüm A/AAAA adresleri; çözümleme hatasında boş liste. IP-literal için tek elemanlı. */
    public static List<InetAddress> allAddresses(String host) {
        try {
            return new ArrayList<>(Arrays.asList(InetAddress.getAllByName(host)));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Kaba IP-literal tespiti (DNS gerektirmez) — çok-A yolunu atlamak için yeterli. */
    public static boolean isIpLiteral(String host) {
        if (host == null || host.isBlank()) return false;
        if (host.indexOf(':') >= 0) return true;                 // IPv6
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");         // IPv4
    }

    /**
     * Çözümlenen adresleri sırayla dener, ilk TCP kabul edene bağlı Socket döndürür (çağıran kapatır).
     * Çözümleme boşsa hostname ile bağlanır (doğal UnknownHost/ConnectException korunur).
     * Hepsi başarısızsa son bağlantı hatasını fırlatır.
     */
    public static Socket connectFirstReachable(String host, int port, int timeoutMs) throws IOException {
        List<InetAddress> addrs = allAddresses(host);
        if (addrs.isEmpty()) {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return s;
        }
        IOException last = null;
        for (InetAddress addr : addrs) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(addr, port), timeoutMs);
                return s;
            } catch (IOException e) {
                last = e;
                try { s.close(); } catch (IOException ignore) { /* zaten kapandı */ }
            }
        }
        throw last != null ? last
                : new ConnectException("Çözümlenen hiçbir adrese bağlanılamadı: " + host + ":" + port);
    }

    /**
     * Çözümlenen adresler içinde hedef porta TCP kabul eden ilk adresi döndürür; yoksa null.
     * Yalnız erişilebilirlik yoklaması (probe) yapar, döndürdüğü soketi hemen kapatır.
     */
    public static InetAddress firstReachable(List<InetAddress> addrs, int port, int timeoutMs) {
        for (InetAddress addr : addrs) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(addr, port), timeoutMs);
                return addr;
            } catch (IOException ignore) { /* bu IP kapalı — sıradakini dene */ }
        }
        return null;
    }
}
