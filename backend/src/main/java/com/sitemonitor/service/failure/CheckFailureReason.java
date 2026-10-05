package com.sitemonitor.service.failure;

import java.util.Arrays;
import java.util.List;

/**
 * İzleme kontrolü BAŞARISIZLIK NEDENİ kataloğu — Ping, Port, DNS, Sayfa Bütünlüğü, Sayfa Hızı, Durum (envanter
 * erişilebilirliği) ve Alan Adı geçmişinin TEK kod listesi (2026-10-05, kullanıcı isteği: "kontrol geçmişinde alınan
 * hatanın detayı olmayan izlemeler için … hata alındığında detaylıca ne hatası aldığını görelim").
 *
 * <p>Kodlar {@code <tablo>.failure_reason} kolonuna yazılır ve arayüzde i18n anahtarına DİNAMİK çevrilir:
 * {@code chkfail.<KOD>.short|why|effect|fix}. Kod eklemek = TR + EN metnini aynı değişiklikte eklemek
 * ({@code CheckFailureI18nGateTest} kırılır) ve arayüz kopyasını ({@code components/checks/checkFailureCodes.js})
 * güncellemek ({@code checkFailureCodes.test.js} iki listeyi karşılaştırır).
 *
 * <p>Sınıflandırma YALNIZ ÜST VERİDİR: hiçbir kod bir kontrolün ok/up/open kararını, alarmı, DNS değişiklik
 * tespitini, kurtarmayı ya da bildirimi değiştirmez (bkz. {@link CheckFailureClassifier}).
 */
public enum CheckFailureReason {

    // ── Politika / yapılandırma: istek hiç atılmadı ──
    SSRF_BLOCKED(Phase.POLICY),
    CONFIG_ERROR(Phase.POLICY),
    ICMP_UNAVAILABLE(Phase.POLICY),

    // ── Ad çözümleme ──
    DNS_NXDOMAIN(Phase.DNS),
    DNS_SERVFAIL(Phase.DNS),
    DNS_REFUSED(Phase.DNS),
    DNS_TIMEOUT(Phase.DNS),
    DNS_NO_ANSWER(Phase.DNS),
    DNS_RESOLVE(Phase.DNS),

    // ── Bağlantı (TCP / vekil) ──
    CONNECT_REFUSED(Phase.CONNECT),
    CONNECT_TIMEOUT(Phase.CONNECT),
    HOST_UNREACHABLE(Phase.CONNECT),
    PROXY_REFUSED(Phase.CONNECT),
    PROXY_ERROR(Phase.CONNECT),

    // ── TLS ──
    TLS_HANDSHAKE(Phase.TLS),
    TLS_TRUST(Phase.TLS),
    TLS_HOSTNAME(Phase.TLS),

    // ── Yanıt ──
    READ_TIMEOUT(Phase.RESPONSE),
    CONNECTION_RESET(Phase.RESPONSE),
    PROTOCOL_ERROR(Phase.RESPONSE),
    HTTP_STATUS(Phase.RESPONSE),
    REDIRECT_LIMIT(Phase.RESPONSE),
    BANNER_MISMATCH(Phase.RESPONSE),
    UDP_NO_REPLY(Phase.RESPONSE),

    // ── ICMP (ping) ──
    ICMP_NO_REPLY(Phase.ICMP),

    // ── Sayfa içeriği (sayfa geldi, kaynaklar sorunlu) ──
    RESOURCES_BROKEN(Phase.CONTENT),
    RESOURCES_TIMEOUT(Phase.CONTENT),
    MIXED_CONTENT(Phase.CONTENT),

    // ── Alan adı kaydı (RDAP / WHOIS) ──
    RDAP_NOT_FOUND(Phase.REGISTRY),
    RDAP_UNAVAILABLE(Phase.REGISTRY),
    RDAP_RATE_LIMITED(Phase.REGISTRY),
    WHOIS_UNAVAILABLE(Phase.REGISTRY),
    NO_PUBLIC_REGISTRY(Phase.REGISTRY),
    REGISTRY_NO_EXPIRY(Phase.REGISTRY),

    // ── Sınıflanamayan ──
    UNKNOWN(Phase.REQUEST);

    /** Kontrolün düştüğü evre — arayüzde "Evre" satırı ({@code chkfail.phase.<EVRE>}). */
    public enum Phase { POLICY, DNS, CONNECT, TLS, REQUEST, RESPONSE, ICMP, CONTENT, REGISTRY }

    public final Phase phase;

    CheckFailureReason(Phase phase) { this.phase = phase; }

    /** TAM kod listesi (sıra = tanım sırası) — i18n kapısı ve arayüz eşleme testi bunu okur. */
    public static final List<String> CODES = Arrays.stream(values()).map(r -> r.name()).toList();

    /** Kolon genişliği ({@code failure_reason VARCHAR(48)}). */
    public static final int CODE_MAX = 48;
}
