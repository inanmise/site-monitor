package com.certmonitor.service;

/** Ağ davranışından bağımsız test host'ları. */
final class TestHosts {

    /**
     * "Çözülemeyen host" senaryoları için DETERMİNİSTİK ad. RFC 1035 §2.3.4 ihlali: etiket 64 karakter
     * (üst sınır 63) → hem glibc hem Windows getaddrinfo'su DNS'e hiç sormadan reddeder.
     *
     * <p>Neden düz bir {@code .invalid} adı yetmiyor: ev/ISP yönlendiricilerinin wildcard ("hijack") DNS'i
     * var olmayan adları da bir IP'ye çözüyor (ör. 192.168.1.1). O ağlarda {@code nonexistent.invalid}
     * çözülüyor ve "çözümleme hatası" testleri kod hatası olmadan kırmızıya düşüyordu.
     */
    static final String UNRESOLVABLE = "x".repeat(64) + ".invalid";

    private TestHosts() {}
}
