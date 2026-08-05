package com.sitemonitor.service;

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

    /** Çok-A amplifikasyon sınırı: en fazla ilk N IP denenir (K ölü IP × timeout thread-parkını önler). */
    public static final int MAX_A_ATTEMPTS = 6;
    /** Çok-A yolunda IP başına connect üst sınırı (HttpCheckerService probe'u ile aynı desen). */
    public static final int CONNECT_CAP_MS = 4000;

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
        // Tek-A: eski davranış (tam timeout, tek deneme). Çok-A: ilk MAX_A_ATTEMPTS IP + IP başına
        // connect'i CONNECT_CAP_MS'e clamp (çok-A amplifikasyonu; tek-A hiç etkilenmez → false-down riski yok).
        boolean multi = addrs.size() > 1;
        int limit = multi ? Math.min(addrs.size(), MAX_A_ATTEMPTS) : 1;
        int perAttemptMs = multi ? Math.min(timeoutMs, CONNECT_CAP_MS) : timeoutMs;
        IOException last = null;
        for (int i = 0; i < limit; i++) {
            InetAddress addr = addrs.get(i);
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(addr, port), perAttemptMs);
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
     * ÖNCEDEN çözülmüş/doğrulanmış adres listesine sırayla bağlanır (yeniden çözmez → SsrfGuard sonrası
     * DNS-rebind kapanır). İlk TCP kabul edene bağlı Socket döndürür (çağıran kapatır); hepsi başarısızsa fırlatır.
     */
    public static Socket connectFirstReachable(List<InetAddress> addrs, int port, int timeoutMs) throws IOException {
        if (addrs == null || addrs.isEmpty())
            throw new ConnectException("boş adres listesi");
        boolean multi = addrs.size() > 1;
        int limit = multi ? Math.min(addrs.size(), MAX_A_ATTEMPTS) : 1;
        int perAttemptMs = multi ? Math.min(timeoutMs, CONNECT_CAP_MS) : timeoutMs;
        IOException last = null;
        for (int i = 0; i < limit; i++) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(addrs.get(i), port), perAttemptMs);
                return s;
            } catch (IOException e) {
                last = e;
                try { s.close(); } catch (IOException ignore) { /* zaten kapandı */ }
            }
        }
        throw last != null ? last : new ConnectException("çözümlenen hiçbir adrese bağlanılamadı:" + port);
    }

    /**
     * Çözümlenen adresler içinde hedef porta TCP kabul eden ilk adresi döndürür; yoksa null.
     * Yalnız erişilebilirlik yoklaması (probe) yapar, döndürdüğü soketi hemen kapatır.
     */
    public static InetAddress firstReachable(List<InetAddress> addrs, int port, int timeoutMs) {
        if (addrs == null || addrs.isEmpty()) return null;
        // Çok-A: ilk MAX_A_ATTEMPTS IP + IP başına CONNECT_CAP_MS clamp (amplifikasyon sınırı); tek-A tam timeout.
        boolean multi = addrs.size() > 1;
        int limit = multi ? Math.min(addrs.size(), MAX_A_ATTEMPTS) : addrs.size();
        int perAttemptMs = multi ? Math.min(timeoutMs, CONNECT_CAP_MS) : timeoutMs;
        for (int i = 0; i < limit; i++) {
            InetAddress addr = addrs.get(i);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(addr, port), perAttemptMs);
                return addr;
            } catch (IOException ignore) { /* bu IP kapalı — sıradakini dene */ }
        }
        return null;
    }
}
