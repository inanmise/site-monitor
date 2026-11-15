package com.sitemonitor.service;

/**
 * Alan adı doğrulama — TEK KAYNAK (2026-09-12, envanter içe aktarma).
 *
 * <p>{@code AdminController.validateDomain} ile içe aktarma servisi aynı kuralı paylaşır: URL
 * yapıştırılırsa host'a indirgenir ({@link PublicSuffixService#extractHost}), uzunluk 253, etiket
 * biçimi RFC-1123 (tek etiketli iç host'lar da kabul). Kural iki yerde yazılsaydı biri diğerinden
 * ayrışacaktı (ör. içe aktarma "www.a.com/" kabul ederken form reddederdi).
 */
public final class DomainNames {
    private DomainNames() {}

    /**
     * Alan adı bitişinin KİMLİK günü (2026-11-15, alan adı süre bitişi hata avı): kaynağın yazdığı ISO değerin baştaki
     * {@code yyyy-MM-dd} kısmı. RDAP zaman damgası verir ("2026-12-01T04:00:00Z"), WHOIS / .tr web-WHOIS yalnız tarih
     * ("2026-12-01"; {@code WhoisParser.parseDate} ISO girdide de aynı günü yazar). Kaynak RDAP ↔ WHOIS arasında gidip
     * gelince (RDAP zaman aşımı / 429 → WHOIS yedeği) AYNI bitiş farklı metin olarak gelir; ham metinle anahtarlanan
     * hatırlatma serisi "yenilendi" sanıp aynı eşiği yeniden gönderiyor, yenileme planı da "yenileme tespit edildi" diye
     * kendiliğinden kapanıyordu. Gösterim değil kimliktir (gösterim yerel güne çevrilir). Baştaki 10 karakter geçerli bir
     * ISO tarih değilse kırpılmış değerin kendisi; boş → null.
     */
    public static String expiryDay(String iso) {
        if (iso == null) return null;
        String s = iso.trim();
        if (s.isEmpty()) return null;
        if (s.length() >= 10) {
            String head = s.substring(0, 10);
            try {
                java.time.LocalDate.parse(head);
                return head;
            } catch (java.time.format.DateTimeParseException ignore) { /* ISO değil — değerin kendisi */ }
        }
        return s;
    }

    public static String validate(String domain) {
        if (domain == null || domain.isBlank())
            throw new IllegalArgumentException("Domain cannot be blank");
        String host = PublicSuffixService.extractHost(domain);
        if (host == null || host.isBlank())
            throw new IllegalArgumentException("Domain cannot be blank");
        if (host.length() > 253)
            throw new IllegalArgumentException("Domain name too long");
        if (!host.matches("^(?:[a-zA-Z0-9](?:[a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$")
                && !host.matches("^[a-zA-Z0-9\\-]{1,63}$")) {
            throw new IllegalArgumentException("Invalid domain format: " + domain);
        }
        return host;
    }
}
